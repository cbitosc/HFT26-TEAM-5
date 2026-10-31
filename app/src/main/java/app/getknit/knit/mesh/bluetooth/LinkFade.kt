package app.getknit.knit.mesh.bluetooth

/**
 * Whether one Bluetooth link is near its floor (ADR 2026-10.79yb's ninth amendment): fed every link-RSSI read outcome
 * of the link's [BlePhyControl], asked by the walkie's send path through `MeshTransport.liveLinkFading`. A fading link
 * still carries the chunk, and a relay carries it too.
 *
 * Why: on 2026-10-13 a P9 ↔ P7 walkie link on Coded degraded for about 20 s before either side saw it drop
 * (`link dropped on CODED rssi=-103 (eof)` on one side, `rssi=-82` on the other), and a burst each way was lost in
 * between. Android gives an app no way to shorten the LE supervision timeout, so the link keeps counting as a path
 * until it, and the hedge has to start from what the link's own reads say.
 *
 * It fades when an answered read leaves the smoothed link RSSI at or under [PhyTuning.fadeDbm], or when
 * [PhyTuning.fadeStaleReads] reads in a row went unanswered. Reads, never time: the read loop's wait stops while the
 * CPU sleeps and nothing on the walkie path holds a wake lock, so "no answer for N seconds" was true of every link of a
 * phone that slept, and a link between the floor and the exit then never stopped fading (the bluetooth-reviewer's
 * finding on the first cut). It stops fading only on an answered read, once answered reads have held the smoothed RSSI
 * at or over [PhyTuning.fadeExitDbm] for [PhyTuning.fadeExitHoldMs]; an unanswered read restarts that hold.
 *
 * Pure and clock-injected; synchronized, because the send path asks from any thread while the read loop feeds it.
 */
class LinkFade(
    private val tuning: () -> PhyTuning,
) {
    /** A change of verdict, for the caller's one log line per edge. */
    enum class Edge { ON, OFF }

    @get:Synchronized
    var fading: Boolean = false
        private set

    /** Reads in a row that did not answer since the last that did. */
    @get:Synchronized
    var unansweredInRow: Int = 0
        private set

    // The last answered read, for the log; null before the first.
    private var answeredAt: Long? = null
    private var strongSince: Long? = null

    /** A read answered at [now], leaving the link's smoothed RSSI at [smoothed]. */
    @Synchronized
    fun onRead(
        smoothed: Double,
        now: Long,
    ): Edge? {
        val t = tuning()
        answeredAt = now
        unansweredInRow = 0
        strongSince = if (smoothed >= t.fadeExitDbm) strongSince ?: now else null
        return when {
            !fading && smoothed <= t.fadeDbm -> on()
            fading && strongSince.let { it != null && now - it >= t.fadeExitHoldMs } -> off()
            else -> null
        }
    }

    /** A read went unanswered: the stack refused the call, its callback answered with a failure, or it timed out. */
    @Synchronized
    fun onUnanswered(): Edge? {
        unansweredInRow++
        strongSince = null
        return if (!fading && unansweredInRow >= tuning().fadeStaleReads.coerceAtLeast(1)) on() else null
    }

    /** How long since the last answered read, or null before the first: for the log. */
    @Synchronized
    fun sinceAnsweredMs(now: Long): Long? = answeredAt?.let { now - it }

    private fun on(): Edge {
        fading = true
        strongSince = null
        return Edge.ON
    }

    private fun off(): Edge {
        fading = false
        return Edge.OFF
    }

    companion object {
        /** A link-RSSI read not back by now is unanswered: `BlePhyControl`'s timeout on one read. */
        const val READ_TIMEOUT_MS = 1_000L
    }
}
