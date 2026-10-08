package app.getknit.knit

import app.getknit.knit.mesh.link.FeedWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR 2026-10.nsr7's phase 2: a feed clocked by its receiver's reports keeps about [FeedWindow.QUEUE_MS] of the drain
 * queued, on any link speed, and finds a faster link by itself. The simulated link drains at a set rate from one queue
 * (the stack's and the air's), and its receiver reports as `RxReports` does for a windowed sender.
 */
class FeedWindowTest {
    /**
     * One simulated stream: the sender follows the window, the air drains [drain] B/s, and the receiver reports as
     * `RxReports` does for a windowed sender — on the chunk that brings 2 KiB since its last report, or [reportEveryMs]
     * after it once anything more landed — each report reaching the sender [lagMs] later (the reverse air, which a slow
     * connection interval stretches).
     */
    private class Sim(
        var drain: Int,
        var lagMs: Long = 100,
        private val reportEveryMs: Long = 500,
        // A dark sender: its CPU sleeps, so its waits never fire and it writes only when a report wakes it.
        private val asleep: Boolean = false,
    ) {
        val window = FeedWindow().apply { start(offset = 0, now = 0) }
        var t = 0L
        var queue = 0L // written and not yet held by the receiver
        var held = 0L
        var maxQueueMs = 0L // the deepest queue, in ms of the drain, since [measureFrom]
        private var measureFrom = 0L
        private val inFlightReports = ArrayDeque<Triple<Long, Long, Long>>() // (arrives, held, ms)
        private var reportedAt = 0L
        private var reportedHeld = 0L
        private var waitUntil = 0L

        // The writer's turn: its wait has run out, and a dark one only at the instant a report woke it (or at the start).
        private fun writerAwake(): Boolean = t >= waitUntil && (!asleep || t == waitUntil || t == 0L)

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
                while (writerAwake()) {
                    val wait = window.waitMs()
                    if (wait > 0) {
                        waitUntil = if (asleep) Long.MAX_VALUE else t + wait
                        break
                    }
                    val chunk = window.chunkBytes()
                    window.wrote(chunk, t)
                    queue += chunk
                }
                val drained = minOf(queue, drain.toLong() * STEP_MS / 1000)
                queue -= drained
                held += drained
                val grown = held - reportedHeld
                if (grown >= CHUNK_REPORT_BYTES || (grown > 0 && t - reportedAt >= reportEveryMs)) {
                    inFlightReports.addLast(Triple(t + lagMs, held, t))
                    reportedHeld = held
                    reportedAt = t
                }
                if (t >= measureFrom) maxQueueMs = maxOf(maxQueueMs, queue * 1000 / drain)
                t += STEP_MS
            }
        }

        /** What the receiver got per second over (from, now]. */
        fun throughputSince(
            from: Long,
            heldThen: Long,
        ): Long = (held - heldThen) * 1000 / (t - from)
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
    fun aDarkSenderThatWritesOnlyWhenAReportWakesItStillKeepsTheAirBusy() {
        // Device rep 2026-10-07 P9→P8, both dark: a window of half a second of the rate, refilled once a report, capped
        // the feed at window ÷ gap and shrank it to its floor. Covering the gap keeps the link's drain.
        val sim = Sim(drain = 12_000, asleep = true)
        sim.run(8_000)
        val heldAt8 = sim.held
        sim.measureFrom(8_000)
        sim.run(20_000)
        val throughput = (sim.held - heldAt8) / 12
        assertTrue("the air is kept busy: $throughput B/s of 12000", throughput >= 10_800)
        assertTrue("and a frame waits under about a second and a half: ${sim.maxQueueMs} ms", sim.maxQueueMs <= 1_500)
    }

    @Test
    fun aLongRoundTripIsCoveredByGrowingTheWindowNotByTheRateAlone() {
        // Device sweep 2026-10-07 (W3 rep 2): a dark pair at a 255 ms interval, reports a second in coming back; a window
        // sized from a rate measured through it sat at its floor, 3–4.5 KB/s on a link that drained 6.
        val sim = Sim(drain = 6_000, lagMs = 900, asleep = true)
        sim.run(10_000)
        val heldAt10 = sim.held
        sim.run(30_000)
        val throughput = (sim.held - heldAt10) / 20
        // Less the round-trip probe's dip every ten seconds: a round at one chunk, about 12% at this round trip.
        assertTrue("the air is kept busy: $throughput B/s of 6000", throughput >= 5_000)
    }

    @Test
    fun aRoundTripThatRisesMidFileIsFollowedUp() {
        // W3 rep 1 on the device: the link's interval stepped from 15 ms to 255 ms three seconds into the file, and the
        // reports' way back with it. The round trip's estimate rises at its next probe, and the window with it.
        val sim = Sim(drain = 10_000, lagMs = 50, asleep = true)
        sim.run(6_000)
        sim.lagMs = 900
        sim.run(12_000)
        val heldAt12 = sim.held
        sim.measureFrom(12_000)
        sim.run(30_000)
        val throughput = sim.throughputSince(12_000, heldAt12)
        assertTrue("the air is kept busy, less the probe's dip: $throughput B/s of 10000", throughput >= 8_000)
        assertTrue("and the queue stays short: ${sim.maxQueueMs} ms", sim.maxQueueMs <= 1_500)
    }

    @Test
    fun aLitSenderToAReceiverWhoseReportsComeBackSlowlyGetsTheLink() {
        // Device sweep 4 (2026-10-07): the P9 lit, the P8 dark. A window covering only the gap between reports got half
        // the link (2.9 and 4.0 KB/s on a link that drained 5.3 and 8.8); covering the round trip, it gets the drain.
        for ((drain, lag) in listOf(10_000 to 900L, 20_000 to 900L, 6_000 to 900L)) {
            val sim = Sim(drain = drain, lagMs = lag)
            sim.run(10_000)
            val heldAt10 = sim.held
            sim.measureFrom(10_000)
            sim.run(40_000)
            val throughput = sim.throughputSince(10_000, heldAt10)
            assertTrue("$drain B/s, $lag ms back: $throughput B/s", throughput >= drain * 85 / 100)
            // The round trip's own bytes sit in the stack too, at the slow end of each report's way back.
            assertTrue("$drain B/s, $lag ms back: the queue stays short (${sim.maxQueueMs} ms)", sim.maxQueueMs <= 2_000)
        }
    }

    @Test
    fun aDipMidFileCostsOnlyTheDip() {
        // A second feed starting, A2DP, a missed connection event: the drain halves for two seconds and comes back.
        val sim = Sim(drain = 6_000, lagMs = 900, asleep = true)
        sim.run(10_000)
        sim.drain = 3_000
        sim.run(12_000)
        sim.drain = 6_000
        sim.run(20_000)
        val heldAt20 = sim.held
        sim.run(40_000)
        val throughput = sim.throughputSince(20_000, heldAt20)
        assertTrue("back to the drain after the dip: $throughput B/s of 6000", throughput >= 5_400)
    }

    @Test
    fun aSlowLinkKeepsItsQueueShortWhereAFixedPaceWouldFillIt() {
        // 8jwn's slowest measured 1M drain: 28 KiB/s held a text minutes behind a photo on it.
        val sim = Sim(drain = 4_800)
        sim.run(5_000)
        sim.measureFrom(5_000)
        sim.run(20_000)
        // The starting 8 KiB stays the stream's floor: about 1.7 s of this drain, plus a chunk.
        assertTrue("the queue stays within a couple of seconds: ${sim.maxQueueMs} ms", sim.maxQueueMs <= 2_500)
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
        // What was queued for the faster drain, three-quarters of a second of it, is about three seconds of the slower
        // one: it drains, and the window follows the drain down meanwhile.
        sim.run(13_000)
        sim.measureFrom(13_000)
        sim.run(22_000)
        assertTrue("followed down: ${sim.window.rate}", sim.window.rate <= 7_000)
        assertTrue("its queue back within about the floor of the slower drain: ${sim.maxQueueMs} ms", sim.maxQueueMs <= 2_000)
    }

    @Test
    fun aLateReturnLeansSlowNeverFast() {
        // A report queued behind the receiver's own file: its lag overstates the queue, so the feed holds back.
        val sim = Sim(drain = 20_000, lagMs = 900)
        sim.run(5_000)
        sim.measureFrom(5_000)
        sim.run(15_000)
        // The report's way back is part of the round trip the window covers: at 900 ms the queue runs a little longer.
        assertTrue("about a second and a half: ${sim.maxQueueMs} ms", sim.maxQueueMs <= 2_000)
        assertTrue("and still most of the drain", sim.window.rate >= 15_000)
    }

    @Test
    fun beforeTheFirstReportOnlyTheStartingWindowGoes() {
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(4_096, 0)
        assertEquals(0L, w.waitMs())
        w.wrote(4_096, 0)
        assertEquals("the starting window is full: wait for a report", FeedWindow.REPORT_WAIT_MS, w.waitMs())
        assertFalse(w.pacing(FeedWindow.NO_REPORT_MS - 1))
        assertTrue("a receiver silent this long gets the old pace", w.pacing(FeedWindow.NO_REPORT_MS))
    }

    @Test
    fun theWritersOwnSleepCountsTowardTheSilenceOnlyWhenNothingWasOwed() {
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(8_192, 0)
        w.slept(ms = 10_000, now = 10_100)
        assertTrue("a window out: the receiver owes a report, so its silence counts", w.pacing(10_100))
        w.report(held = 8_192, ms = 500, now = 13_200)
        w.slept(ms = 10_000, now = 23_300)
        assertFalse("nothing out: nothing was owed, so the sleep does not count", w.pacing(23_300))
        assertTrue(w.pacing(23_200 + FeedWindow.NO_REPORT_MS))
    }

    @Test
    fun aReceiverThatReportsEveryTwoSecondsIsPacedForTheRestOfTheStream() {
        // A build or a switch that reports at nsr7's paced cadence never reports the starting window: windowed against
        // it, the feed ran in three-second fits below the paced base (the bluetooth-reviewer's finding). Its reports
        // each cover several chunks; two such, two seconds apart, and the stream is paced.
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        repeat(6) { w.wrote(2_048, 0) }
        w.report(held = 4_096, ms = 2_000, now = 2_100)
        w.report(held = 8_192, ms = 4_000, now = 4_100)
        assertFalse(w.pacing(4_100))
        w.report(held = 12_288, ms = 6_000, now = 6_100)
        assertTrue("two two-second gaps of several chunks: paced", w.pacing(6_100))
        w.report(held = 12_288, ms = 6_100, now = 6_200)
        assertTrue("for the rest of the stream", w.pacing(6_200))
        w.start(offset = 0, now = 7_000)
        assertFalse("a new stream asks again", w.pacing(7_000))
    }

    @Test
    fun aWindowedReceiverOnASlowLinkIsNotTakenForATwoSecondReporter() {
        // Its reports come one per chunk, so on a slow link, or across a probe, they are two seconds apart too; read by
        // time alone that paced the slow link's window away (the bluetooth-reviewer's finding).
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        repeat(4) { w.wrote(2_048, 0) }
        w.report(held = 2_048, ms = 2_000, now = 2_100)
        w.report(held = 4_096, ms = 4_000, now = 4_100)
        w.report(held = 6_144, ms = 6_000, now = 6_100)
        w.report(held = 8_192, ms = 8_000, now = 8_100)
        assertFalse("one chunk a report: a windowed receiver", w.pacing(8_100))
    }

    @Test
    fun theRoundTripIsTheLinksAndOutlivesTheStreamUntilAPhyStep() {
        // A file fed in full leaves its tail in the stack's queue, and the next stream's first chunk waits behind it:
        // taken fresh each stream, the estimate grew with every file sent back to back (the bluetooth-reviewer's finding).
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(4_096, 0)
        w.report(held = 4_096, ms = 400, now = 400)
        assertEquals(400L, w.rttMs)
        w.start(offset = 0, now = 1_000)
        w.wrote(4_096, 1_000)
        w.report(held = 4_096, ms = 1_000, now = 2_200) // behind the last file's tail
        assertEquals("the link's round trip, not the queue the last file left", 400L, w.rttMs)
        w.forgetRtt()
        assertEquals(FeedWindow.INITIAL_RTT_MS, w.rttMs)
    }

    @Test
    fun aChunkBehindItsWindowIsNotReadAsALinkThatSlowed() {
        // The round trip carried over from a fast lit file is short; a slow dark pair's next file sits at its window's
        // floor, and a chunk at the back of it lands a whole window's drain later. Judged against the round trip alone
        // that read as a slowdown on every report and reset the drain's filter (the bluetooth-reviewer's finding).
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(2_048, 0)
        w.report(held = 2_048, ms = 60, now = 60) // a fast file: round trip 60 ms
        w.start(offset = 0, now = 1_000)
        w.wrote(2_048, 1_000)
        w.wrote(2_048, 1_000) // 2 KiB ahead of it
        w.report(held = 2_048, ms = 400, now = 1_400) // 5 KB/s
        val rate = w.rate
        w.report(held = 4_096, ms = 900, now = 1_900) // the second, 900 ms after its write: its 2 KiB ahead at 5 KB/s
        assertEquals("the larger sample stands: no slowdown", rate, w.rate)
    }

    @Test
    fun aSampleClampedToTheCeilingDoesNotRefreshTheEstimatesAge() {
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(4_096, 0)
        w.report(held = 4_096, ms = 5_000, now = 5_000) // 5 s: stored as the 3 s ceiling
        assertEquals(FeedWindow.MAX_RTT_MS, w.rttMs)
        w.wrote(4_096, 5_000)
        w.report(held = 8_192, ms = 15_100, now = 15_100) // 10.1 s: also the ceiling, and no smaller than the 5 s
        assertTrue("its age ran from the first: the probe is due", w.probingRtt)
    }

    @Test
    fun aProbeTakesTheRoundTripOfAChunkSentUnderItsOneChunkWindow() {
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(4_096, 0)
        w.report(held = 4_096, ms = 400, now = 400) // round trip 400 ms, stamped at 400
        w.wrote(8_192, 400)
        w.report(held = 6_144, ms = 11_000, now = 11_000) // nothing smaller for ten seconds: probe
        assertTrue(w.probingRtt)
        assertEquals("room for one smallest chunk", FeedWindow.MIN_CHUNK_BYTES + 1L, w.target())
        assertEquals("6 KiB still out: wait", FeedWindow.REPORT_WAIT_MS, w.waitMs())
        w.report(held = 12_288, ms = 11_400, now = 11_400)
        assertEquals(0L, w.waitMs())
        w.wrote(2_048, 11_400)
        w.report(held = 14_336, ms = 12_100, now = 12_100)
        assertFalse("its round trip ends the probe", w.probingRtt)
        assertEquals("and is the trip, though larger than the old one", 700L, w.rttMs)
    }

    @Test
    fun aReportOfMoreThanWasWrittenOrFasterThanTheCeilingProvesNoMore() {
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(65_536, 0)
        w.report(held = 10_000_000, ms = 500, now = 600)
        assertEquals("held to what was written", 0L, w.queued())
        assertEquals("and the rate to the ceiling", FeedWindow.CEILING_BYTES_PER_SEC, w.rate)
        assertEquals("the round trip, write to report", 600L, w.rttMs)
        assertEquals("the rate over the round trip and the queue", 65_536L * (600 + FeedWindow.QUEUE_MS) / 1000, w.target())
    }

    @Test
    fun aBurstReadBackToBackMovesTheQueueButTakesNoSample() {
        // A receiver's reader catching up 16 KiB reports 8 KiB twice, ms apart: that is no drain to measure.
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(24_576, 0)
        w.report(held = 8_192, ms = 1_000, now = 1_000) // 8 KiB/s
        w.report(held = 16_384, ms = 1_003, now = 1_005)
        assertEquals("not 2.7 MB/s", 8_192, w.rate)
        assertEquals("but the queue knows what landed", 8_192L, w.queued())
        w.report(held = 20_480, ms = 1_500, now = 1_500) // measured from the last sample: 12 KiB over 500 ms
        assertEquals(24_576, w.rate)
    }

    @Test
    fun anOlderReportMovesNothing() {
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(16_384, 0)
        w.report(held = 8_192, ms = 1_000, now = 1_000)
        val rate = w.rate
        w.report(held = 4_096, ms = 500, now = 1_100) // reordered
        assertEquals(rate, w.rate)
        assertEquals(8_192L, w.queued())
    }

    @Test
    fun aResumedStreamCountsFromItsOffset() {
        // ADR 2026-10.wtyc: the receiver reports the whole length held, its kept prefix included.
        val w = FeedWindow().apply { start(offset = 100_000, now = 0) }
        w.wrote(8_192, 0)
        w.report(held = 104_096, ms = 500, now = 500)
        assertEquals("4 KiB in half a second", 8_192, w.rate)
        assertEquals(4_096L, w.queued())
    }

    @Test
    fun withNoReportNothingFreesTheWindowWhateverTheClock() {
        // No timer drains the estimate: a sender whose CPU sleeps is woken by the reports it waits on.
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(8_192, 0)
        w.report(held = 4_096, ms = 500, now = 500)
        w.wrote(32_768, 500)
        assertEquals(36_864L, w.queued())
        assertEquals(FeedWindow.REPORT_WAIT_MS, w.waitMs())
    }

    @Test
    fun aQueuedChunksRoundTripCanOnlyLowerTheEstimate() {
        // A chunk that went out behind a standing queue waited the queue too: taken as the round trip, the window would
        // grow by the queue every round (the bluetooth-reviewer's port: a 3 s queue at 10 KB/s and up).
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(4_096, 0)
        w.report(held = 4_096, ms = 400, now = 400) // 10 KB/s, round trip 400 ms
        assertEquals(400L, w.rttMs)
        w.wrote(16_384, 400) // well past the rate over the round trip: a queue stands ahead of the next chunk
        w.wrote(2_048, 400)
        w.report(held = 22_528, ms = 2_400, now = 2_400)
        assertEquals("a queued chunk's 2 s is not the air's round trip", 400L, w.rttMs)
    }

    @Test
    fun chunksCarryAQuarterSecondOfTheRateWithinTheirBounds() {
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        assertEquals(FeedWindow.INITIAL_CHUNK_BYTES, w.chunkBytes())
        w.wrote(8_192, 0)
        w.report(held = 2_000, ms = 500, now = 500) // 4 KB/s
        assertEquals(FeedWindow.MIN_CHUNK_BYTES, w.chunkBytes())
        w.wrote(32_768, 500)
        w.report(held = 40_960, ms = 1_000, now = 1_000) // 77 KB/s, held to the 64 KiB/s ceiling
        assertEquals(16 * 1024, w.chunkBytes())
    }

    private companion object {
        const val STEP_MS = 10L
        const val CHUNK_REPORT_BYTES = 2_048L
    }
}
