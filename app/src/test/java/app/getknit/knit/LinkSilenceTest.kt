package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.LinkSilence
import app.getknit.knit.mesh.bluetooth.LinkSilence.Action
import app.getknit.knit.mesh.bluetooth.LinkSilence.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [LinkSilence] (#203, ADR 2026-10.697m): when a Bluetooth link shown dead goes down, and that a healthy one never
 * does. Pure: the clock is the test's, and the link's bytes are the stamps it passes in.
 */
class LinkSilenceTest {
    private val s = LinkSilence.QUIET_MS
    private val p = LinkSilence.ANSWER_MS
    private val w = LinkSilence.WRITE_STALL_MS

    private companion object {
        // One-way delays a keepalive may take: a quiet link, a busy one, and one queued behind a stream.
        val DELAYS = longArrayOf(5, 400, 3_000, 8_000, 14_000)
    }

    /** A link whose announces crossed at link-up: the peer has shown it answers. */
    private fun answering(): LinkSilence =
        LinkSilence(exempt = false).also {
            assertTrue(it.announce())
            it.onKeepaliveWritten(10)
            assertFalse("the peer's announce crossing ours is not answered", it.onKeepaliveIn())
            assertTrue(it.answers)
        }

    @Test
    fun anAwakeIdlePairNeverProbes() {
        // Each side's 60 s re-offer is a byte in for the other well inside the 90 s quiet.
        val link = answering()
        var rx = 0L
        for (t in 0L..1_800_000L step 5_000L) {
            if (t % 60_000L == 0L) rx = t
            assertEquals("at $t", Action.None, link.step(t, rx, null, null))
        }
    }

    @Test
    fun aPeerThatNeverAnsweredIsNeverProbedNorClosedOnItsSilence() {
        val link = LinkSilence(exempt = false)
        assertTrue(link.announce())
        link.onKeepaliveWritten(5)
        assertFalse(link.answers)
        // An hour of silence from an older build: only a stall or its own controller would close it.
        assertEquals(Action.None, link.step(3_600_000, 0, null, null))
        assertEquals(Action.None, link.step(3_600_000, 0, null, LinkSilence.Local(unansweredSinceRx = 2, lost = false)))
    }

    @Test
    fun anAnsweringLinkIsProbedTwiceThenClosedAboutAHundredAndFiftySecondsAfterItsLastByte() {
        val link = answering()
        assertEquals(Action.None, link.step(s - 1, 0, null, null))
        assertEquals(s, link.msUntilDue(0, 0, null, null))
        assertEquals(Action.Probe(1), link.step(s, 0, null, null))
        assertEquals("one probe at a time: queued, not yet written", Action.None, link.step(s + p, 0, null, null))
        link.onKeepaliveWritten(s + 100)
        assertEquals(p, link.msUntilDue(s + 100, 0, null, null))
        assertEquals(Action.None, link.step(s + 100 + p - 1, 0, null, null))
        assertEquals(Action.Probe(2), link.step(s + 100 + p, 0, null, null))
        link.onKeepaliveWritten(s + 200 + p)
        assertEquals(Action.None, link.step(s + 200 + 2 * p - 1, 0, null, null))
        val close = link.step(s + 200 + 2 * p, 0, null, null)
        assertEquals(Action.Close(Why.PROBES, s + 200 + 2 * p), close)
        assertTrue((close as Action.Close).quietMs in 150_000L..151_000L)
    }

    @Test
    fun anyByteInAfterAProbeEndsItsSilence() {
        val link = answering()
        assertEquals(Action.Probe(1), link.step(s, 0, null, null))
        link.onKeepaliveWritten(s + 50)
        // The answer, or any record, lands: the run ends, and the next probe waits for a full quiet again.
        val rx = s + 400
        assertEquals(Action.None, link.step(s + 50 + p, rx, null, null))
        assertEquals(Action.None, link.step(rx + s - 1, rx, null, null))
        assertEquals(Action.Probe(1), link.step(rx + s, rx, null, null))
    }

    @Test
    fun aPeerThatHasSentAKeepaliveAnswersEveryOneNotMatchingOneOfItsOwn() {
        val link = answering()
        assertTrue("the peer's probe", link.onKeepaliveIn())
        assertTrue("and its second, each answered", link.onKeepaliveIn())
        // Our probe waits: the next one in is its answer, or a probe that crossed it.
        assertEquals(Action.Probe(1), link.step(s, 0, null, null))
        assertFalse(link.onKeepaliveIn())
        assertTrue("then the peer's next probe is answered again", link.onKeepaliveIn())
    }

    @Test
    fun anAnswerToTheAnnounceGoesFirstWhenThePeersAnnounceArrivesBeforeOurs() {
        val link = LinkSilence(exempt = false)
        assertTrue(link.onKeepaliveIn())
        assertFalse("our answer is our announce", link.announce())
        assertTrue(link.answers)
    }

    @Test
    fun twoAnsweringSidesEndEveryExchangeWhetherProbesCrossOrNot() {
        // Offsets cross the probes or not; delays run past half the answer wait, where a time-only rule bounced.
        for (offset in listOf(0L, 300L, 5_000L, 20_000L, 40_000L)) {
            for (delay in listOf(5L, 400L, 10_000L, 16_000L, 25_000L)) {
                val keepalives = exchange(offsetMs = offset, delayMs = delay)
                // A round trip past the answer wait sends the second probe before the first one's answer is back: four.
                val most = if (2 * delay < p) 2 else 4
                assertTrue("offset $offset delay $delay: $keepalives keepalives for one silence", keepalives in 1..most)
            }
        }
    }

    /**
     * Two policies over one link, every keepalive taking [delayMs] to cross, B's last byte in [offsetMs] after A's.
     * Runs one silence to its end, asserts no close and nothing still in flight, and returns how many keepalives crossed
     * after the announces.
     */
    private fun exchange(
        offsetMs: Long,
        delayMs: Long,
    ): Int {
        val a = answering()
        val b = answering()
        val rx = longArrayOf(0, offsetMs) // A's last byte in, B's
        val sides = listOf(a, b)
        val air = ArrayList<Pair<Long, Int>>() // (arrival, to side)
        var sent = 0
        var t = 0L
        // Past every first exchange (the slowest ends at 170 s), short of either side's next silence (180 s and on).
        val until = 2 * s - 1
        while (t <= until) {
            val landed = air.filter { it.first <= t }
            air.removeAll(landed)
            landed.forEach { (_, to) ->
                rx[to] = t
                if (sides[to].onKeepaliveIn()) {
                    sides[to].onKeepaliveWritten(t)
                    air += (t + delayMs) to (1 - to)
                    sent++
                }
            }
            sides.forEachIndexed { i, side ->
                val action = side.step(t, rx[i], null, null)
                assertFalse("no close while every keepalive is answered: $action", action is Action.Close)
                if (action is Action.Probe) {
                    side.onKeepaliveWritten(t)
                    air += (t + delayMs) to (1 - i)
                    sent++
                }
            }
            t += 5
        }
        assertTrue("nothing left in flight: $air", air.isEmpty())
        return sent
    }

    @Test
    fun aSeededPairRunsSilenceAfterSilenceWithNoCloseAndNoKeepaliveLeftUnmatched() {
        // A leak in the answer count would show only in a later silence: each run spans about four. Each direction has its
        // own delay, and each write may wait behind a stream before it returns, the round trip kept inside the answer wait.
        for (seed in 0 until 60) {
            val r = Random(seed)
            val delays = longArrayOf(DELAYS[r.nextInt(DELAYS.size)], DELAYS[r.nextInt(DELAYS.size)]) // to A, to B
            val lagMost = (p - delays.sum() - 1_000).coerceAtLeast(0)
            longRun(r, delays, lagMost, "seed $seed delays ${delays.toList()} lag ≤ $lagMost")
        }
    }

    /**
     * Two policies over one link from the announces to about [s] × 4: [delays] per direction (index = the side it reaches),
     * every write returning up to [lagMost] after it was queued, in the side's queue order, and each direction first in,
     * first out. No bytes but keepalives, as between two dozing phones. Ends once nothing is queued or in flight, and checks
     * the count by behaviour: a fresh keepalive is then answered on both sides.
     */
    private fun longRun(
        r: Random,
        delays: LongArray,
        lagMost: Long,
        what: String,
    ) {
        val sides = listOf(LinkSilence(exempt = false), LinkSilence(exempt = false))
        val rx = longArrayOf(0, 0)
        val pending = List(2) { ArrayDeque<Long>() } // each side's writes, by when they return
        val air = List(2) { ArrayDeque<Long>() } // arrivals, by the side they reach
        val queue = { i: Int, t: Long ->
            pending[i].addLast(maxOf(t + if (lagMost > 0) r.nextLong(lagMost + 1) else 0, pending[i].lastOrNull() ?: 0))
        }
        sides.forEachIndexed { i, side -> if (side.announce()) queue(i, 0) }
        var t = 0L
        val busy = { pending.any { it.isNotEmpty() } || air.any { it.isNotEmpty() } }
        while (t <= 4 * s || busy()) {
            for (i in 0..1) {
                due(pending[i], t).forEach { _ ->
                    sides[i].onKeepaliveWritten(t)
                    air[1 - i].addLast(maxOf(t + delays[1 - i], air[1 - i].lastOrNull() ?: 0))
                }
                due(air[i], t).forEach { _ ->
                    rx[i] = t
                    if (sides[i].onKeepaliveIn()) queue(i, t)
                }
            }
            // Past the horizon nothing new starts: the run drains what is out.
            if (t <= 4 * s) {
                sides.forEachIndexed { i, side ->
                    val action = side.step(t, rx[i], null, null)
                    assertFalse("$what: closed at $t: $action", action is Action.Close)
                    if (action is Action.Probe) queue(i, t)
                }
            }
            t += 5
        }
        sides.forEachIndexed { i, side -> assertTrue("$what: side $i still counts an unmatched keepalive", side.onKeepaliveIn()) }
    }

    /** Takes from the head of [times] every entry due by [t], in order. */
    private fun due(
        times: ArrayDeque<Long>,
        t: Long,
    ): List<Long> = buildList { while (times.firstOrNull()?.let { it <= t } == true) add(times.removeFirst()) }

    @Test
    fun anIphoneLinkIsNeverAnnouncedProbedAnsweredOrClosed() {
        val link = LinkSilence(exempt = true)
        assertFalse(link.announce())
        assertFalse(link.onKeepaliveIn())
        assertFalse(link.onEdge(100_000))
        val local = LinkSilence.Local(unansweredSinceRx = 9, lost = true)
        assertEquals(Action.None, link.step(3_600_000, 0, blockedSince = 0, local = local))
        assertNull(link.msUntilDue(3_600_000, 0, 0, local))
    }

    @Test
    fun aWriteThatSitsTwoMinutesClosesTheLinkWhatever() {
        val link = LinkSilence(exempt = false) // an older peer: the stall is its only bound
        assertEquals(Action.None, link.step(1_000 + w - 1, rxAt = 1_000 + w - 1, blockedSince = 1_000, local = null))
        assertEquals(1L, link.msUntilDue(1_000 + w - 1, 1_000 + w - 1, 1_000, null))
        // Bytes still coming in do not keep it: the writer is what is stuck.
        assertEquals(Action.Close(Why.WRITE, 0), link.step(1_000 + w, rxAt = 1_000 + w, blockedSince = 1_000, local = null))
    }

    @Test
    fun aLinkNotSittingInAWriteIsLookedAtAgainWithinTheStallBound() {
        val link = LinkSilence(exempt = false)
        assertEquals(w, link.msUntilDue(500, 500, null, null))
    }

    @Test
    fun itsOwnControllerClosesAQuietLinkOnThreeUnansweredReadsOrALostClient() {
        val link = LinkSilence(exempt = false)
        val stale = LinkSilence.Local(unansweredSinceRx = LinkSilence.STALE_READS, lost = false)
        assertEquals("reads alone close nothing before the quiet", Action.None, link.step(s - 1, 0, null, stale))
        assertEquals(s - 1, link.msUntilDue(1, 0, null, stale))
        assertEquals(Action.Close(Why.READS, s), link.step(s, 0, null, stale))

        val two = LinkSilence(exempt = false)
        assertEquals(Action.None, two.step(10 * s, 0, null, LinkSilence.Local(unansweredSinceRx = 2, lost = false)))

        val lost = LinkSilence(exempt = false)
        val gone = LinkSilence.Local(unansweredSinceRx = 0, lost = true)
        assertEquals(Action.None, lost.step(s - 1, 0, null, gone))
        assertEquals(Action.Close(Why.LOST, s), lost.step(s, 0, null, gone))
        assertTrue(Why.LOST.local && Why.READS.local && !Why.PROBES.local && !Why.WRITE.local)
    }

    @Test
    fun towardAPeerThatAnswersItsOwnControllersReadingNeverClosesTheLink() {
        // A stack refuses reads, and an ATT timeout tells every client the connection ended while the CoC holds the ACL.
        for (local in listOf(LinkSilence.Local(0, lost = true), LinkSilence.Local(LinkSilence.STALE_READS, lost = false))) {
            val link = answering()
            assertEquals(s, link.msUntilDue(0, 0, null, local))
            assertEquals("$local probes on the quiet, as any answering link does", Action.Probe(1), link.step(s, 0, null, local))
            link.onKeepaliveWritten(s + 10)
            // The reading moves no deadline: the next look is the answer wait, not every second past the quiet.
            assertEquals(p, link.msUntilDue(s + 10, 0, null, local))
            // The answer lands: a byte after the reading clears it, and the link stays up.
            val rx = s + 500
            val after = LinkSilence.Local(0, lost = false)
            assertEquals(Action.None, link.step(s + 10 + p, rx, null, after))
            assertEquals(Action.None, link.step(rx + s - 1, rx, null, after))
        }
        // Unanswered, the probes close it, as Why.PROBES, never on the reading.
        val link = answering()
        val lost = LinkSilence.Local(0, lost = true)
        assertEquals(Action.Probe(1), link.step(s, 0, null, lost))
        link.onKeepaliveWritten(s)
        assertEquals(Action.Probe(2), link.step(s + p, 0, null, lost))
        link.onKeepaliveWritten(s + p)
        assertEquals(Action.Close(Why.PROBES, s + 2 * p), link.step(s + 2 * p, 0, null, lost))
    }

    @Test
    fun anEdgeProbesAnAnsweringLinkAtOnceButNeverClosesItBeforeTheQuiet() {
        val link = answering()
        assertTrue(link.onEdge(5_000))
        assertEquals(0L, link.msUntilDue(5_000, 4_000, null, null))
        assertEquals(Action.Probe(1), link.step(5_000, 4_000, null, null))
        link.onKeepaliveWritten(5_010)
        assertEquals(Action.Probe(2), link.step(5_010 + p, 4_000, null, null))
        link.onKeepaliveWritten(5_020 + p)
        // Two probes unanswered, but only 66 s since the last byte: the close waits for the quiet.
        assertEquals(Action.None, link.step(5_020 + 2 * p, 4_000, null, null))
        assertEquals(4_000 + s - (5_020 + 2 * p), link.msUntilDue(5_020 + 2 * p, 4_000, null, null))
        assertEquals(Action.Close(Why.PROBES, s), link.step(4_000 + s, 4_000, null, null))
    }

    @Test
    fun anEdgeAByteHasAnsweredAlreadyAsksForNothing() {
        val link = answering()
        link.onEdge(5_000)
        assertEquals(Action.None, link.step(5_100, rxAt = 5_050, blockedSince = null, local = null))
        assertEquals(Action.None, link.step(5_050 + s - 1, rxAt = 5_050, blockedSince = null, local = null))
    }

    @Test
    fun anEdgeOnALinkWhosePeerNeverAnsweredProbesNothing() {
        val link = LinkSilence(exempt = false)
        assertTrue(link.onEdge(5_000))
        assertEquals(Action.None, link.step(5_000, 4_000, null, null))
    }
}
