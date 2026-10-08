package app.getknit.knit.mesh.link

/**
 * A Bluetooth file feed clocked by its receiver's reports instead of a fixed pace (ADR 2026-10.nsr7's phase 2). The
 * sender cannot see the stack's queue, but the receiver's `blobprog` reports say how much it holds, so the bytes written
 * less the bytes held is that queue plus the air. Writing only while it stays under [queueMs] of the measured drain
 * keeps a frame queued behind the file within about that of the wire on any link speed, and keeps the air busy, so the
 * feed runs at whatever the link drains — dark, lit, crowded or 2M — where a fixed pace overfeeds a slow pair and
 * starves a fast one (the A/B of 2026-10-07: 28 KiB/s held texts minutes behind a photo, 4 KiB/s took 111 s where the
 * same dark pair drained a photo in 12 s).
 *
 * - **Drain:** each report gives a sample, Δheld ÷ Δms on the receiver's clock, so a report's trip back costs it
 *   nothing. The rate is the largest of the last [SAMPLES] (BBR's choice: noise and a connection event's batching only
 *   ever pull a sample down), held to [ceiling].
 * - **Queued:** what was written less what the last report held, less what the last report's sample has drained since
 *   it came (for at most [EXTRAPOLATE_MAX_MS]: past that only a report frees the window). The latest sample, not the
 *   filter's largest, so a link that slowed is overfed for a report at most; and a queue standing past
 *   [STANDING_WINDOWS] windows at a report clears the filter, since the air set that sample. A report's own lag overstates the queue, the
 *   safe way: the feed holds back, never overfeeds.
 * - **Growth:** a link faster than the rate drains the queue between reports, and the next report resets the estimate
 *   to the truth, so the writer refills the whole window at once and the next sample reads about twice the rate. That
 *   is how a link that got faster (a boost earned, a 2M step) is found, with no probing of its own.
 * - **Silence:** before the first report the window is [INITIAL_WINDOW]. With no report for [NO_REPORT_MS] — a peer
 *   that never reports, or whose reports stopped — the owner paces the feed the old way ([pacing]) until one comes.
 *
 * One stream at a time ([start]). Not thread-safe: its owner ([FramedLink]) calls it under one lock. Pure, clocked by
 * the `now` each call passes.
 */
internal class FeedWindow(
    private val ceiling: Int = CEILING_BYTES_PER_SEC,
) {
    /** The queue, in ms of the rate, the feed keeps ahead of the receiver. Read before every chunk from the link's pace. */
    var queueMs: Long = QUEUE_MS

    /** The drain measured from the receiver's reports, in bytes a second: 0 until two points of a stream are known. */
    var rate = 0
        private set

    private var offset = 0L
    private var written = 0L
    private var lastHeld = -1L
    private var lastMs = 0L

    // Where the next sample is measured from: the last report a sample was taken at (or the stream's start). A report
    // under [MIN_SAMPLE_MS] after it moves the queue's anchor but takes no sample, since a receiver's reader catching up a
    // burst reports bytes ms apart and the sample would read MB/s (the bluetooth-reviewer's third pass).
    private var sampleHeld = -1L
    private var sampleMs = 0L

    // When the stream began or a report last came, on the sender's clock: what the queue is extrapolated from.
    private var heardAt = 0L

    // The last report's sample: the drain since the report before. The queue is drained at it between reports, not at
    // the filter's largest, so a link that slowed is overfed for one report at most.
    private var lastSample = 0
    private val samples = IntArray(SAMPLES)
    private var sampleCount = 0
    private var nextSample = 0

    /** A stream starts at [offset] of its blob, at [now]. */
    fun start(
        offset: Long,
        now: Long,
    ) {
        this.offset = offset
        written = 0L
        lastHeld = -1L
        lastMs = 0L
        sampleHeld = -1L
        sampleMs = 0L
        heardAt = now
        rate = 0
        lastSample = 0
        sampleCount = 0
        nextSample = 0
    }

    /** [bytes] more of the stream went to the socket. */
    fun wrote(bytes: Int) {
        written += bytes
    }

    /**
     * The receiver holds [held] bytes of the blob (its whole length so far, a resume's prefix included), [ms] after the
     * stream's header reached it, and the report came at [now]. A report older than the last, or naming more than was
     * written, moves nothing past what it can prove.
     */
    fun report(
        held: Long,
        ms: Long,
        now: Long,
    ) {
        val proven = held.coerceAtMost(offset + written)
        if (proven < maxOf(lastHeld, offset)) return
        val (fromHeld, fromMs) = if (sampleHeld < 0) offset to 0L else sampleHeld to sampleMs
        if (ms - fromMs >= MIN_SAMPLE_MS) {
            val sample = ((proven - fromHeld) * MS_PER_SECOND / (ms - fromMs)).coerceIn(0L, ceiling.toLong()).toInt()
            // A queue standing well past the window says the air, not the feed, set this sample: it is the drain, and
            // the larger ones before it are a link that slowed (a second feed began, a boost let go). Kept, they would
            // overfeed it for the whole filter.
            if (offset + written - proven > STANDING_WINDOWS * target()) {
                sampleCount = 0
                nextSample = 0
            }
            samples[nextSample] = sample
            lastSample = sample
            nextSample = (nextSample + 1) % SAMPLES
            sampleCount = minOf(sampleCount + 1, SAMPLES)
            rate = samples.take(sampleCount).max()
            sampleHeld = proven
            sampleMs = ms
        }
        lastHeld = proven
        lastMs = maxOf(lastMs, ms)
        heardAt = now
    }

    /**
     * The writer slept [ms] past the wait it asked for (its `delay` stops while the CPU sleeps): no chunk went out, so
     * the receiver had nothing to report, and that time does not count toward [pacing]'s silence.
     */
    fun slept(
        ms: Long,
        now: Long,
    ) {
        if (ms > 0) heardAt = minOf(now, heardAt + ms)
    }

    /** Whether the receiver has been silent for [NO_REPORT_MS]: the owner paces the feed the old way until it reports. */
    fun pacing(now: Long): Boolean = now - heardAt >= NO_REPORT_MS

    /** The bytes the window lets be queued and on the air: [queueMs] of the rate, or [INITIAL_WINDOW] before it. */
    fun target(): Long = if (rate <= 0) INITIAL_WINDOW else (rate.toLong() * queueMs / MS_PER_SECOND).coerceIn(MIN_WINDOW, MAX_WINDOW)

    /** The bytes written that the receiver is not yet thought to hold. */
    fun queued(now: Long): Long {
        if (lastHeld < 0) return written
        val since = (now - heardAt).coerceIn(0L, EXTRAPOLATE_MAX_MS)
        val delivered = lastHeld - offset + lastSample.toLong() * since / MS_PER_SECOND
        return (written - delivered).coerceAtLeast(0L)
    }

    /**
     * How long to wait before the next chunk: 0 while the queue is under the window, else until the rate should have
     * drained it there — at least [MIN_WAIT_MS], at most [REPORT_WAIT_MS], since a report re-reads it anyway. Past the
     * extrapolation's bound only a report frees the window.
     */
    fun waitMs(now: Long): Long {
        val q = queued(now)
        val t = target()
        if (q < t) return 0L
        val left = if (lastHeld < 0) 0L else EXTRAPOLATE_MAX_MS - (now - heardAt)
        if (lastSample <= 0 || left <= 0) return REPORT_WAIT_MS
        val ms = (q - t + 1) * MS_PER_SECOND / lastSample
        return ms.coerceIn(MIN_WAIT_MS, minOf(left, REPORT_WAIT_MS).coerceAtLeast(MIN_WAIT_MS))
    }

    /** The next chunk's size: about [CHUNK_MS] of the rate, so a frame queued mid-file waits at most a small chunk. */
    fun chunkBytes(): Int {
        if (rate <= 0) return INITIAL_CHUNK_BYTES
        val chunk = rate.toLong() * CHUNK_MS / MS_PER_SECOND
        return chunk.coerceIn(MIN_CHUNK_BYTES.toLong(), LinkFraming.FILE_CHUNK_BYTES.toLong()).toInt()
    }

    companion object {
        // The queue (the stack's and the air's) a feed keeps ahead of the receiver: the wait a frame behind the file sees.
        const val QUEUE_MS = 500L

        // The fastest drain a report is believed at, which bounds a garbled or lying one: about twice the best 1M
        // drain measured (29 KB/s at a 15 ms interval, ADR 2026-10.2da4).
        const val CEILING_BYTES_PER_SEC = 64 * 1024

        // The window's bounds: never so small that a slow link stops and waits for each report, never past half a second
        // of the ceiling.
        const val MIN_WINDOW = 4L * 1024
        const val MAX_WINDOW = 32L * 1024

        // What may be queued before the first report measures anything: two seconds of the old 4 KiB/s base.
        const val INITIAL_WINDOW = 8L * 1024

        // A receiver silent this long (six report intervals) gets the old pace until it reports.
        const val NO_REPORT_MS = 3_000L

        // How far past the last report the queue is drained at the rate: past it, only a report frees the window.
        const val EXTRAPOLATE_MAX_MS = 1_500L

        // The longest one wait lasts: a report's interval. A report wakes the writer sooner.
        const val REPORT_WAIT_MS = 500L
        const val MIN_WAIT_MS = 20L

        // A chunk carries about this much of the rate, between these sizes.
        const val CHUNK_MS = 250L
        const val MIN_CHUNK_BYTES = 2048
        const val INITIAL_CHUNK_BYTES = 4096

        private const val SAMPLES = 4

        // The shortest span, on the receiver's clock, a sample is measured over.
        const val MIN_SAMPLE_MS = 250L

        // How many windows of queue at a report make it a standing one, whose sample is the drain.
        private const val STANDING_WINDOWS = 2
        private const val MS_PER_SECOND = 1_000L
    }
}
