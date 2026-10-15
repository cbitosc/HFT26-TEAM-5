package app.getknit.knit.call

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** One file of a speech model, pinned: its name, its exact size and its SHA-256 (lower-case hex). */
data class SttFile(
    val name: String,
    val bytes: Long,
    val sha256: String,
)

/** Which of Moonshine's streaming English architectures a [SttModel] is: what the engine is told when it loads the files. */
enum class SttArch { TINY_STREAMING, SMALL_STREAMING, MEDIUM_STREAMING }

/**
 * A speech model the walkie's transcript can download (C-3.11-9): its files, fetched by name under [baseUrl] and
 * pinned by size and hash here, so the host is trusted with nothing — a byte it changes is a download that fails.
 * [baseUrl] is https or nothing. The app ships one, [MOONSHINE_MEDIUM_STREAMING_EN].
 */
class SttModel(
    val id: String,
    val baseUrl: String,
    val files: List<SttFile>,
    val arch: SttArch,
) {
    init {
        require(baseUrl.startsWith("https://") && baseUrl.endsWith("/")) { "a model is fetched over https, from a directory" }
        require(files.isNotEmpty() && files.all { it.name.matches(SAFE_NAME) }) { "a model's files are plain names" }
    }

    /** Every file's bytes together: what a download costs. */
    val bytes: Long get() = files.sumOf { it.bytes }

    fun urlOf(file: SttFile): String = baseUrl + file.name

    companion object {
        private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")

        /**
         * Moonshine v2 Medium Streaming, English (245M parameters, int8 `.ort`, MIT): 6.65 % on the Open ASR average,
         * against Small's 7.84 and Whisper Small's 8.59. The walkie's one model: Tiny (12.0 %) read too poorly, and Small was
         * set aside for Medium's accuracy at about twice the fetch and the model time (ADR 2026-10.nz3g).
         * Moonshine's own files (`medium-streaming-en/quantized_26_08_21`, the per-channel quantization), rehosted under
         * getknit.app with the library's licence. `scripts/stt-model.py --check` re-derives these pins from upstream; never
         * edit them by hand.
         */
        val MOONSHINE_MEDIUM_STREAMING_EN =
            SttModel(
                id = "moonshine-medium-streaming-en-2026-08-21",
                baseUrl = "https://getknit.app/models/stt/moonshine-medium-streaming-en-2026-08-21/",
                files =
                    listOf(
                        SttFile("adapter.ort", 3_651_296, "3f2a287def57cc094367a0eec3c4f5fc36a32ec420e86764b696920991b20281"),
                        SttFile("cross_kv.ort", 11_643_776, "642f6e21cd305be79342207c6f9e6b681d469d55bc48c72b27b84846fb71fd1e"),
                        SttFile("decoder_kv.ort", 146_972_408, "193bb366492b74fc4ad338c6778e8d8eb916aaa11b5aa264f9057f4db7759486"),
                        SttFile("encoder.ort", 94_705_376, "12915e76ebac7dd287c5ea63965d06103a53ba1ce242a4a34f318f3958c60c37"),
                        SttFile("frontend.model.ort", 28_720, "95768855c70c8251eeecc05fedf69999da1b8ab16f605c9f457fd3354b0ad6b5"),
                        SttFile("frontend.weights.ort", 11_889_560, "5ac941f490cbe035b335b99a414cc393d62d4c6f9f2423495b286870d271d709"),
                        SttFile("streaming_config.json", 513, "28e83b7a28e91472692a035e0dae3116422ae43aeb2bef5ed822c44ce89b88af"),
                        SttFile("tokenizer.bin", 249_974, "6884b35fd6377d4c4d32336a0bc152f36b64d1e45b6503683cdc238250a8472d"),
                        SttFile("LICENSE", 14_180, "fa7d1174dd8af6a7cd280be20b80d10095ed4c19b5b20b61a7715c3ad790dc5f"),
                    ),
                arch = SttArch.MEDIUM_STREAMING,
            )
    }
}

/** Where the walkie's speech model stands on this phone. */
sealed interface SttModelState {
    data object Absent : SttModelState

    data class Downloading(
        val done: Long,
        val total: Long,
    ) : SttModelState

    data object Ready : SttModelState

    data class Failed(
        val reason: String,
    ) : SttModelState
}

/**
 * Puts [model] into [dir], one file at a time, each streamed to `<name>.part` through a running SHA-256 and renamed into
 * place only when its size and hash are the pinned ones. A file past its pinned size is cut off there, not read to the
 * end. The model counts as installed ([installed]) once a marker naming it is written, after every file is in: a phone
 * that died half way holds no marker, and the next install re-checks what is there and fetches the rest.
 *
 * [open] is the source: an https fetch on the phone, a directory for a side-load, bytes in a test. Pure JVM
 * (`SttModelInstallerTest`); one install at a time.
 */
class SttModelInstaller(
    private val model: SttModel,
    private val dir: File,
) {
    /** Why an install did not finish, in words the call screen shows. */
    class Refused(
        message: String,
    ) : IOException(message)

    private val marker = File(dir, MARKER)

    /** Whether the model is on the phone: its marker, and every file at its pinned size (hashed at install, not here). */
    fun installed(): Boolean =
        runCatching { marker.isFile && marker.readText() == model.id && model.files.all { File(dir, it.name).length() == it.bytes } }
            .getOrDefault(false)

    /** The path of [file] in [dir]. */
    fun pathOf(file: SttFile): File = File(dir, file.name)

    /**
     * Installs the model from [open], reporting the bytes in hand against the total to [progress]. Throws [Refused] (or
     * the source's [IOException]) when a file cannot be had or fails its pins, and cancellation as it comes; a file half
     * written is deleted either way.
     */
    suspend fun install(
        open: (SttFile) -> InputStream,
        progress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        if (installed()) return
        if (!dir.isDirectory && !dir.mkdirs()) throw Refused("Couldn't make the model's folder")
        marker.delete()
        var done = 0L
        progress(done, model.bytes)
        for (file in model.files) {
            val target = pathOf(file)
            if (!(target.length() == file.bytes && sha256(target) == file.sha256)) {
                fetch(file, open, target) { n -> progress(done + n, model.bytes) }
            }
            done += file.bytes
            progress(done, model.bytes)
        }
        marker.writeText(model.id)
    }

    /** Removes the model: the marker first, so a delete cut short leaves a model that is not installed. */
    fun delete() {
        marker.delete()
        dir.deleteRecursively()
    }

    private suspend fun fetch(
        file: SttFile,
        open: (SttFile) -> InputStream,
        target: File,
        progress: (Long) -> Unit,
    ) {
        val part = File(dir, file.name + PART)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val count = open(file).use { input -> part.outputStream().use { output -> copy(file, input, output, digest, progress) } }
            refuseUnless(count == file.bytes) { "${file.name} came short: $count of ${file.bytes} bytes" }
            refuseUnless(digest.digest().toHex() == file.sha256) { "${file.name} failed its checksum" }
            target.delete()
            refuseUnless(part.renameTo(target)) { "Couldn't put ${file.name} in place" }
        } finally {
            part.delete()
        }
    }

    // [input] to [output] through [digest], never past [file]'s pinned size; the bytes copied.
    private suspend fun copy(
        file: SttFile,
        input: InputStream,
        output: OutputStream,
        digest: MessageDigest,
        progress: (Long) -> Unit,
    ): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var count = 0L
        var reported = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n < 0) return count
            count += n
            refuseUnless(count <= file.bytes) { "${file.name} is larger than it should be" }
            digest.update(buffer, 0, n)
            output.write(buffer, 0, n)
            if (count - reported >= REPORT_BYTES) {
                reported = count
                progress(count)
            }
        }
    }

    private inline fun refuseUnless(
        ok: Boolean,
        reason: () -> String,
    ) {
        if (!ok) throw Refused(reason())
    }

    private fun sha256(file: File): String? =
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            digest.digest().toHex()
        }.getOrNull()

    private fun ByteArray.toHex(): String =
        buildString(size * 2) {
            for (b in this@toHex) {
                append(HEX[(b.toInt() shr NIBBLE) and LOW])
                append(HEX[b.toInt() and LOW])
            }
        }

    private companion object {
        const val MARKER = "installed"
        const val PART = ".part"
        const val BUFFER_BYTES = 64 * 1024
        const val REPORT_BYTES = 256 * 1024L
        const val HEX = "0123456789abcdef"
        const val NIBBLE = 4
        const val LOW = 0x0F
    }
}
