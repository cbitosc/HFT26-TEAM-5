package app.getknit.knit

import app.getknit.knit.mesh.link.FeedWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR 2026-10.nsr7's phase 2: a feed clocked by its receiver's reports keeps about [FeedWindow.QUEUE_MS] of the drain
 * queued, on any link speed, and finds a faster link by itself. The simulated link drains at a set rate from one queue
 * (the stack's and the air's), and its receiver reports every 500 ms by its own clock while anything more has landed,
 * the report reaching the sender a lag later.
 */
class FeedWindowTest {
    /** One simulated stream: the sender follows the window, the air drains [drain] B/s, reports come back [lagMs] late. */
    private class Sim(
        var drain: Int,
        private val lagMs: Long = 100,
        private val reportEveryMs: Long = 500,
    ) {
        val window = FeedWindow().apply { start(offset = 0, now = 0) }
        var t = 0L
        var queue = 0L // written and not yet held by the receiver
        var held = 0L
        var maxQueueMs = 0L // the deepest queue, in ms of the drain, since [measureFrom]
        private var measureFrom = 0L
        private val inFlightReports = ArrayDeque<Triple<Long, Long, Long>>() // (arrives, held, ms)
        private var nextReport = reportEveryMs
        private var reportedHeld = 0L
        private var waitUntil = 0L

        fun measureFrom(at: Long) {
            measureFrom = at
            maxQueueMs = 0
        }

        fun run(untilMs: Long) {
            while (t < untilMs) {
                while (inFlightReports.firstOrNull()?.first?.let { it <= t } == true) {
                    val (_, h, ms) = inFlightReports.removeFirst()
                    window.report(h, ms, t)
                    waitUntil = t // a report wakes the writer
                }
                while (t >= waitUntil) {
                    val wait = window.waitMs(t)
                    if (wait > 0) {
                        waitUntil = t + wait
                        break
                    }
                    val chunk = window.chunkBytes()
                    window.wrote(chunk)
                    queue += chunk
                }
                val drained = minOf(queue, drain.toLong() * STEP_MS / 1000)
                queue -= drained
                held += drained
                // As FramedLink reports for a windowed sender: each interval, but only once something more has landed
                // (a chunk's arrival, or the trailing report after it); a stall says nothing.
                if (t >= nextReport && held > reportedHeld) {
                    inFlightReports.addLast(Triple(t + lagMs, held, t))
                    reportedHeld = held
                    nextReport = t + reportEveryMs
                }
                if (t >= measureFrom) maxQueueMs = maxOf(maxQueueMs, queue * 1000 / drain)
                t += STEP_MS
            }
        }
    }

    @Test
    fun aSteadyDrainIsFoundAndTheQueueStaysNearHalfASecondOfIt() {
        val sim = Sim(drain = 20_000)
        sim.run(5_000)
        sim.measureFrom(5_000)
        val heldAt5 = sim.held
        sim.run(15_000)
        assertEquals("the rate is the drain", 20_000.0, sim.window.rate.toDouble(), 2_000.0)
        val throughput = (sim.held - heldAt5) / 10
        assertTrue("the air is kept busy: $throughput B/s of 20000", throughput >= 19_000)
        assertTrue("a frame behind the file waits under a second: ${sim.maxQueueMs} ms", sim.maxQueueMs <= 1_000)
    }

    @Test
    fun aSlowLinkKeepsItsQueueShortWhereAFixedPaceWouldFillIt() {
        // 8jwn's slowest measured 1M drain: 28 KiB/s held a text minutes behind a photo on it.
        val sim = Sim(drain = 4_800)
        sim.run(5_000)
        sim.measureFrom(5_000)
        sim.run(20_000)
        assertTrue("the queue stays within a couple of seconds: ${sim.maxQueueMs} ms", sim.maxQueueMs <= 2_000)
        assertTrue("and the air stays busy", sim.window.rate >= 4_300)
    }

    @Test
    fun aFastLinkIsFoundFromTheSmallStartingWindow() {
        // The dark pair of the A/B's T8 cell drained a 341 KB photo in about 12 s: a fixed 4 KiB/s base took 111 s.
        val sim = Sim(drain = 40_000)
        sim.run(4_000)
        assertTrue("found within four seconds: ${sim.window.rate}", sim.window.rate >= 36_000)
        sim.measureFrom(4_000)
        sim.run(12_000)
        assertTrue("341 KB crosses in about 9 s", sim.held >= 341_000)
        assertTrue("still within a second: ${sim.maxQueueMs} ms", sim.maxQueueMs <= 1_000)
    }

    @Test
    fun aDrainThatFallsIsFollowedWithinAFewReports() {
        val sim = Sim(drain = 25_000)
        sim.run(8_000)
        sim.drain = 6_000 // a second link starts feeding, or the boost lets go
        sim.run(11_000)
        sim.measureFrom(11_000)
        sim.run(20_000)
        assertTrue("followed down: ${sim.window.rate}", sim.window.rate <= 7_000)
        assertTrue("its queue back within two seconds of the slower drain: ${sim.maxQueueMs} ms", sim.maxQueueMs <= 2_000)
    }

    @Test
    fun aLateReturnLeansSlowNeverFast() {
        // A report queued behind the receiver's own file: its lag overstates the queue, so the feed holds back.
        val sim = Sim(drain = 20_000, lagMs = 900)
        sim.run(5_000)
        sim.measureFrom(5_000)
        sim.run(15_000)
        assertTrue("never deeper than with a prompt report: ${sim.maxQueueMs} ms", sim.maxQueueMs <= 1_000)
        assertTrue("and still most of the drain", sim.window.rate >= 15_000)
    }

    @Test
    fun beforeTheFirstReportOnlyTheStartingWindowGoes() {
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(4_096)
        assertEquals(0L, w.waitMs(0))
        w.wrote(4_096)
        assertEquals("the starting window is full: wait for a report", FeedWindow.REPORT_WAIT_MS, w.waitMs(0))
        assertFalse(w.pacing(FeedWindow.NO_REPORT_MS - 1))
        assertTrue("a receiver silent this long gets the old pace", w.pacing(FeedWindow.NO_REPORT_MS))
    }

    @Test
    fun aReportEndsTheSilenceAndTheWritersOwnSleepDoesNotCountToward() {
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(8_192)
        w.slept(ms = 10_000, now = 10_100)
        assertFalse("the writer slept: no chunk went out to report", w.pacing(10_100))
        w.report(held = 4_096, ms = 500, now = 13_200)
        assertFalse(w.pacing(13_200 + FeedWindow.NO_REPORT_MS - 1))
        assertTrue(w.pacing(13_200 + FeedWindow.NO_REPORT_MS))
    }

    @Test
    fun aReportOfMoreThanWasWrittenOrFasterThanTheCeilingProvesNoMore() {
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(65_536)
        w.report(held = 10_000_000, ms = 500, now = 600)
        assertEquals("held to what was written", 0L, w.queued(600))
        assertEquals("and the rate to the ceiling", FeedWindow.CEILING_BYTES_PER_SEC, w.rate)
        assertEquals(FeedWindow.MAX_WINDOW, w.target())
    }

    @Test
    fun aBurstReadBackToBackMovesTheQueueButTakesNoSample() {
        // A receiver's reader catching up 16 KiB reports 8 KiB twice, ms apart: that is no drain to measure.
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(24_576)
        w.report(held = 8_192, ms = 1_000, now = 1_000) // 8 KiB/s
        w.report(held = 16_384, ms = 1_003, now = 1_005)
        assertEquals("not 2.7 MB/s", 8_192, w.rate)
        assertEquals("but the queue knows what landed", 8_192L, w.queued(1_005))
        w.report(held = 20_480, ms = 1_500, now = 1_500) // measured from the last sample: 12 KiB over 500 ms
        assertEquals(24_576, w.rate)
    }

    @Test
    fun anOlderReportMovesNothing() {
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(16_384)
        w.report(held = 8_192, ms = 1_000, now = 1_000)
        val rate = w.rate
        w.report(held = 4_096, ms = 500, now = 1_100) // reordered
        assertEquals(rate, w.rate)
        assertEquals(8_192L - rate * 100L / 1000, w.queued(1_100))
    }

    @Test
    fun aResumedStreamCountsFromItsOffset() {
        // ADR 2026-10.wtyc: the receiver reports the whole length held, its kept prefix included.
        val w = FeedWindow().apply { start(offset = 100_000, now = 0) }
        w.wrote(8_192)
        w.report(held = 104_096, ms = 500, now = 500)
        assertEquals("4 KiB in half a second", 8_192, w.rate)
        assertEquals(4_096L, w.queued(500))
    }

    @Test
    fun pastTheExtrapolationBoundOnlyAReportFreesTheWindow() {
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(8_192)
        w.report(held = 4_096, ms = 500, now = 500) // 8 KB/s; 4 KiB queued, target 4 KiB
        w.wrote(32_768)
        val later = 500 + FeedWindow.EXTRAPOLATE_MAX_MS + 1_000
        assertEquals("the queue drains at the rate only so far", 36_864L - 8_192L * 1_500 / 1000, w.queued(later))
        assertEquals(FeedWindow.REPORT_WAIT_MS, w.waitMs(later))
    }

    @Test
    fun chunksCarryAQuarterSecondOfTheRateWithinTheirBounds() {
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        assertEquals(FeedWindow.INITIAL_CHUNK_BYTES, w.chunkBytes())
        w.wrote(8_192)
        w.report(held = 2_000, ms = 500, now = 500) // 4 KB/s
        assertEquals(FeedWindow.MIN_CHUNK_BYTES, w.chunkBytes())
        w.wrote(32_768)
        w.report(held = 40_960, ms = 1_000, now = 1_000) // 77 KB/s, held to the 64 KiB/s ceiling
        assertEquals(16 * 1024, w.chunkBytes())
    }

    private companion object {
        const val STEP_MS = 10L
    }
}
