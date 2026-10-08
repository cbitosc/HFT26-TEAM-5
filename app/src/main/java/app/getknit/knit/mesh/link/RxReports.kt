package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.ArrivingFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The receive side's progress reports for one link: how much of the file streaming in it holds, and when by its own
 * clock since the header, to [report], which the owner sends back to the sender (ADR 2026-10.nsr7). The sender measures
 * its drain from them, and a windowed one is clocked by them (ADR 2026-10.5buf).
 *
 * - **Paced** ([windowed] false): every [EVERY_MS] of chunks, on the reader after a chunk is written. A stall sends
 *   nothing.
 * - **Windowed:** every [WINDOWED_EVERY_MS], on the chunk that brings [WINDOWED_EVERY_BYTES] since the last report,
 *   and once more [WINDOWED_EVERY_MS] after chunks no later report carried. A windowed sender writes nothing more until
 *   it hears, so a fast pair's starting window lands inside one interval and would otherwise wait out its silence (the
 *   bluetooth-reviewer's finding). A stall still sends nothing: the trailing report goes only when the file grew.
 *
 * [arriving] is the file streaming in now, null with none or once the link is closed.
 */
internal class RxReports(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val windowed: () -> Boolean,
    private val arriving: () -> ArrivingFile?,
    private val report: (key: String, held: Long, ms: Long) -> Unit,
) {
    // When the file streaming in began, and when it was last reported, with how much it held then and the trailing
    // report armed for it. Guarded by [lock]: the trailing report runs off the reader.
    private var startedAt = 0L
    private var reportedAt = 0L
    private var reportedBytes = -1L
    private var trailing: Job? = null
    private val lock = Any()

    /** A file's header came at [at]. */
    fun started(at: Long) =
        synchronized(lock) {
            startedAt = at
            reportedAt = at
            reportedBytes = -1L
        }

    /** A chunk of the file streaming in was written. Called on the reader, outside every lock of the link's. */
    fun chunk() {
        val f = arriving() ?: return
        val windowed = windowed()
        val every = if (windowed) WINDOWED_EVERY_MS else EVERY_MS
        val t = now()
        val ms =
            synchronized(lock) {
                val wait = reportedAt + every - t
                // A windowed sender's window is freed by what landed, so a window's worth is reported on the chunk that
                // completes it, while the reader is awake for it: a dark receiver may suspend before a timer fires.
                val grown =
                    windowed && f.bytes - maxOf(reportedBytes, 0L) >= WINDOWED_EVERY_BYTES && t - reportedAt >= MIN_BYTES_GAP_MS
                if (wait <= 0 || grown) {
                    reportedAt = t
                    reportedBytes = f.bytes
                    return@synchronized t - startedAt
                }
                if (windowed && trailing == null) trailing = scope.launch { trail(f.key, wait) }
                null
            }
        if (ms != null) report(f.key, f.bytes, ms)
    }

    /** The link closed: no trailing report goes. */
    fun close() =
        synchronized(lock) {
            trailing?.cancel()
            trailing = null
        }

    /**
     * The trailing report: while chunks have landed that no report covered, one every [WINDOWED_EVERY_MS]. It ends, and
     * clears itself, only under [lock] with nothing owed, so a chunk landing as it reports is covered by its next turn,
     * and one landing after it ends arms a new one.
     */
    private suspend fun trail(
        key: String,
        ms: Long,
    ) {
        var wait = ms
        while (true) {
            // stretches: a backstop only. A receiver that suspends before it fires reports at the sender's next chunk,
            // which comes after the sender's 3 s silence; the report on a window's worth of bytes ([chunk]) runs awake.
            delay(wait)
            val bytes = arriving()?.takeIf { it.key == key }?.bytes ?: -1L // -1: the file is over
            val t = now()
            var since = -1L
            val left =
                synchronized(lock) {
                    if (bytes < 0 || bytes <= reportedBytes) {
                        trailing = null // the file is over, or reported since with nothing more landed
                        return
                    }
                    (reportedAt + WINDOWED_EVERY_MS - t).also {
                        if (it <= 0) {
                            reportedAt = t
                            reportedBytes = bytes
                            since = t - startedAt
                        }
                    }
                }
            if (since >= 0) report(key, bytes, since)
            wait = if (left <= 0) WINDOWED_EVERY_MS else left
        }
    }

    companion object {
        // How often a paced sender is reported to: often enough that a stall shows inside FeedMeter.STALL_MS, rarely
        // enough that the report costs nothing beside the file. FeedMeter's settle and smoothing count reports at this.
        const val EVERY_MS = 2_000L

        // How often a windowed one is: often enough that its window keeps up with the drain, at about 240 B/s of reverse
        // air per file.
        const val WINDOWED_EVERY_MS = 500L

        // And on every window's worth landed: the starting window, so a fast pair's first report needs no timer.
        const val WINDOWED_EVERY_BYTES = FeedWindow.INITIAL_WINDOW

        // But not closer than this to the last report: a reader catching up a burst would report ms apart, which the
        // sender could not measure a rate from anyway.
        const val MIN_BYTES_GAP_MS = 100L
    }
}
