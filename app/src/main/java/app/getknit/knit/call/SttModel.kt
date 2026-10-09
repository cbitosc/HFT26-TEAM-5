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

/**
 * A speech model the walkie's transcript can download (C-3.11-9): its files, fetched by name under [baseUrl] and
 * pinned by size and hash here, so the host is trusted with nothing — a byte it changes is a download that fails.
 * [baseUrl] is https or nothing.
 */
class SttModel(
    val id: String,
    val baseUrl: String,
    val files: List<SttFile>,
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
         * Moonshine tiny, English, v2 (int8, ORT), as sherpa-onnx packages it (`sherpa-onnx-moonshine-tiny-en-quantized-
         * 2026-02-27`), rehosted under getknit.app with its MIT licence. `scripts/stt-model.py --check` re-derives these
         * pins from the upstream archive; never edit them by hand.
         */
        val MOONSHINE_TINY_EN_V2 =
            SttModel(
                id = "moonshine-tiny-en-2026-02-27",
                baseUrl = "https://getknit.app/models/stt/moonshine-tiny-en-2026-02-27/",
                files =
                    listOf(
                        SttFile("encoder_model.ort", 13_281_600, "94e90a4654fc45cdfedb77c4c08e1739f48862998e58fada384b25118134f221"),
                        SttFile("decoder_model_merged.ort", 30_412_256, "cf524c4862d36e9e5ab032eddc73637efd822d70e868ac575cf1a46e1e4708a0"),
                        SttFile("tokens.txt", 549_350, "2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049"),
                        SttFile("LICENSE", 13_344, "6148d7574a6554b7379b633cfd4c4fe5840c3f548d13bc83e00b52dc6fa00abd"),
                    ),
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
