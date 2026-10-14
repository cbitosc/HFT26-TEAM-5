package app.getknit.knit.ui.call.stt

import android.content.Context
import android.net.Network
import app.getknit.knit.call.SttFile
import app.getknit.knit.call.SttModel
import app.getknit.knit.call.SttModelInstaller
import app.getknit.knit.call.SttModelState
import app.getknit.knit.net.AndroidInternetGate
import app.getknit.knit.net.InternetGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/**
 * The walkie transcript's speech model on this phone (docs/VOICE_CALLS.md C-3.11-9): fetched only when the user taps
 * Download on the call screen (or the debug bridge asks), into `noBackupFilesDir`, so neither Auto Backup nor a Knit
 * backup ever carries it. Every byte is checked against the pins in [SttModel] by [SttModelInstaller], so the host is
 * trusted with nothing.
 *
 * One of [models] is the one in use ([model], the user's pick on the card, kept in a file beside them); each sits in a
 * directory of its own, so switching back to one already fetched costs nothing. [state], [paths], [download] and
 * [delete] are about the one in use.
 *
 * The fetch binds to the network [AndroidInternetGate] hands out — a validated route, never the Wi-Fi Aware link — through
 * the platform's own `Network.openConnection`: sockets and DNS on that network, https only, no redirect followed. That
 * keeps `okhttp3` to the two importers `rules/mesh.md` allows; a fixed host serving pinned bytes needs none of the
 * preview fetcher's hostile-URL machinery.
 */
internal class SttModelStore(
    context: Context,
    private val gate: AndroidInternetGate,
    private val scope: CoroutineScope,
    val models: List<SttModel> = SttModel.ALL,
    private val log: (String) -> Unit = {},
) {
    private val root = File(context.noBackupFilesDir, "models")
    private val choice = File(root, "stt-model")
    private val lock = Any()
    private var job: Job? = null

    /** The model in use: the user's pick, the first of [models] until there is one. */
    @Volatile
    var model: SttModel = models.first()
        private set

    private val installer: SttModelInstaller get() = installerOf(model)

    private fun installerOf(m: SttModel) = SttModelInstaller(m, File(root, m.id))

    private val _state = MutableStateFlow<SttModelState>(SttModelState.Absent)

    /** Where the model stands; [SttModelState.Ready] only once every file is in and checked. */
    val state: StateFlow<SttModelState> = _state.asStateFlow()

    /** Bumped each time the model on disk changes (installed, deleted): an engine built over the old files is stale. */
    @Volatile
    var generation = 0
        private set

    private val started = AtomicBoolean()

    /**
     * Reads the disk once: the model picked, and whether it is there. Off the main thread (Koin builds this lazily).
     * Idempotent, so a caller that may come first in a fresh process (the debug bridge) calls it too.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        val picked = runCatching { choice.readText().trim() }.getOrNull()
        models.firstOrNull { it.id == picked }?.let { model = it }
        if (_state.value == SttModelState.Absent && installer.installed()) _state.value = SttModelState.Ready
    }

    /** The ids of the models on the phone now, whichever is in use. Reads the disk. */
    fun installedIds(): Set<String> = models.filter { installerOf(it).installed() }.map { it.id }.toSet()

    /**
     * Puts the model [id] in use: a download under way stops (what it had stays, for a later try), and [state] is the
     * new model's. False for an id not among [models].
     */
    suspend fun select(id: String): Boolean {
        val next = models.firstOrNull { it.id == id } ?: return false
        start()
        if (next == model) return true
        val running = synchronized(lock) { job }
        running?.cancel()
        running?.join()
        model = next
        runCatching {
            root.mkdirs()
            choice.writeText(id)
        }.onFailure { log("walkie stt: the pick of $id wasn't saved: ${it.javaClass.simpleName}") }
        generation++
        _state.value = if (installer.installed()) SttModelState.Ready else SttModelState.Absent
        log("walkie stt: using ${model.id}")
        return true
    }

    /** The model's files by name, while it is [SttModelState.Ready]. */
    fun paths(): Map<String, File>? {
        val m = model
        if (_state.value != SttModelState.Ready) return null
        val installer = installerOf(m)
        return m.files.associate { it.name to installer.pathOf(it) }
    }

    /** Whether a download now would ride mobile data or a Data Saver route: the call screen says so before the tap. */
    fun metered(): Boolean = gate.routeKind() == InternetGate.RouteKind.CELLULAR || gate.isDataRestricted()

    /** Fetches the model from its host; false (with the reason in [state]) when there is no route to fetch it over. */
    fun download(): Boolean {
        val network = gate.currentNetwork()
        if (network == null) {
            _state.value = SttModelState.Failed("No Internet connection")
            return false
        }
        return install("download") { m, file -> open(network, m, file) }
    }

    /** Installs the model from files already on the phone, named as the model names them (the debug bridge). */
    fun sideload(from: File): Boolean = install("side-load from $from") { _, file -> File(from, file.name).inputStream() }

    /** Stops a download under way; what it had is deleted. */
    fun cancel() {
        synchronized(lock) { job }?.cancel()
    }

    /** Removes the model from the phone. */
    suspend fun delete() {
        val running = synchronized(lock) { job }
        running?.cancel()
        running?.join()
        installer.delete()
        generation++
        _state.value = SttModelState.Absent
    }

    private fun install(
        what: String,
        open: (SttModel, SttFile) -> InputStream,
    ): Boolean =
        synchronized(lock) {
            if (job?.isActive == true) return true
            // The model in use when the tap came; a switch cancels this job before it moves [model].
            val m = model
            val installer = installerOf(m)
            job =
                scope.launch(Dispatchers.IO) {
                    log("walkie stt: $what of ${m.id} (${m.bytes} B)")
                    try {
                        installer.install({ open(m, it) }) { done, total -> _state.value = SttModelState.Downloading(done, total) }
                        generation++
                        _state.value = SttModelState.Ready
                        log("walkie stt: ${m.id} installed")
                    } catch (cancelled: CancellationException) {
                        if (model == m) _state.value = if (installer.installed()) SttModelState.Ready else SttModelState.Absent
                        throw cancelled
                    } catch (e: IOException) {
                        log("walkie stt: $what failed: ${e.javaClass.simpleName}: ${e.message}")
                        _state.value = SttModelState.Failed(e.message ?: "The download failed")
                    }
                }
            true
        }

    // One file over https on [network]: its pinned size checked against what the server declares before a byte is read.
    private fun open(
        network: Network,
        model: SttModel,
        file: SttFile,
    ): InputStream {
        val connection = network.openConnection(URI(model.urlOf(file)).toURL()) as HttpsURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        // The pins are over the file's own bytes: no transparent gzip in between.
        connection.setRequestProperty("Accept-Encoding", "identity")
        try {
            val code = connection.responseCode
            if (code != HttpsURLConnection.HTTP_OK) throw SttModelInstaller.Refused("The model's server answered $code for ${file.name}")
            val declared = connection.contentLengthLong
            if (declared >= 0 && declared != file.bytes) {
                throw SttModelInstaller.Refused("The server's ${file.name} is $declared bytes, not ${file.bytes}")
            }
            return object : FilterInputStream(connection.inputStream) {
                override fun close() {
                    runCatching { super.close() }
                    connection.disconnect()
                }
            }
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
