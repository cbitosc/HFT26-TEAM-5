package app.getknit.knit.ui.call.stt

import android.content.Context
import app.getknit.knit.call.TranscriptLine
import app.getknit.knit.call.WalkieClip
import app.getknit.knit.call.WalkieClips
import app.getknit.knit.call.wavOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A debug build's walkie clips on disk ([WalkieClips], C-3.11-9), for scoring the speech model against real conversations
 * in the lab: each kept burst is a 16 kHz mono WAV and a JSON sidecar beside it, in the app's external files
 * (`Android/data/<package>/files/walkie-clips/`, which `adb pull` reads without root), unencrypted, never backed up and
 * gone with the app. The sidecar has the burst's line — its text included, the one place the transcript is written down —
 * and what [about] says of the phone at the time (the model in use, the call). [on] is Diagnostics' switch, off by
 * default; turning it off keeps what was saved. The clips are capped at [maxBytes], the oldest going first.
 */
internal class WalkieClipFiles(
    context: Context,
    private val scope: CoroutineScope,
    private val switch: StateFlow<Boolean>,
    private val about: (TranscriptLine) -> Map<String, Any?> = { emptyMap() },
    private val maxBytes: Long = MAX_BYTES,
    private val log: (String) -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : WalkieClips {
    /** Where the clips go. */
    val dir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, DIR)

    private val writing = Mutex()

    override val on: Boolean get() = switch.value

    override fun keep(clip: WalkieClip) {
        scope.launch(io) { writing.withLock { write(clip) } }
    }

    /** How many clips are on disk, and their bytes, sidecars included. */
    suspend fun count(): Pair<Int, Long> =
        writing.withLock {
            val files = dir.listFiles().orEmpty()
            files.count { it.name.endsWith(WAV) } to files.sumOf { it.length() }
        }

    /** Deletes every clip. */
    suspend fun clear() {
        writing.withLock { dir.listFiles()?.forEach { it.delete() } }
    }

    private fun write(clip: WalkieClip) {
        val line = clip.line
        val name = "${stamp(line.heardAt)}-${if (line.mine) "spoken" else "heard"}-b${line.burst}"
        val state = line.state
        val sidecar =
            JSONObject()
                .put("burst", line.burst)
                .put("mine", line.mine)
                .put("heardAt", line.heardAt)
                .put("state", if (state is TranscriptLine.State.Text) "Text" else state.toString())
                .put("text", (state as? TranscriptLine.State.Text)?.text ?: JSONObject.NULL)
                .put("audioMs", line.audioMs)
                .put("tookMs", line.tookMs)
                .put("looks", line.looks)
                .put("modelMs", line.modelMs)
        about(line).forEach { (key, value) -> sidecar.put(key, value ?: JSONObject.NULL) }
        try {
            dir.mkdirs()
            File(dir, "$name$WAV").writeBytes(wavOf(clip.samples))
            File(dir, "$name$JSON").writeText(sidecar.toString())
            prune()
        } catch (e: IOException) {
            log("walkie clips: not saved: ${e.message}")
        }
    }

    // The oldest clips, a WAV and its sidecar at a time, until the rest fit [maxBytes]. Names sort by time.
    private fun prune() {
        val files = dir.listFiles().orEmpty().sortedBy { it.name }
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= maxBytes) return
            total -= file.length()
            file.delete()
        }
    }

    private companion object {
        const val DIR = "walkie-clips"
        const val WAV = ".wav"
        const val JSON = ".json"

        // About two hours of speech at 32 kB/s.
        const val MAX_BYTES = 256L * 1024 * 1024

        // UTC, so two phones' clips of one call line up by name.
        fun stamp(ms: Long): String =
            SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(ms))
    }
}
