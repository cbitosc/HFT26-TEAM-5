package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.ColdAction
import app.getknit.knit.mesh.bluetooth.ColdSightings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The bounds on cold far sightings (ADR 2026-10.yvn6, the 2026-10-20 amendment): cooldown, one at a time, budget. */
class ColdSightingsTest {
    private val cold = ColdSightings(maxPeers = 3)

    private fun start(
        peer: String,
        now: Long,
        kind: ColdAction = ColdAction.HUNT,
    ) = cold.tryStart(peer, kind, now, HOLD, COOLDOWN)

    @Test
    fun aFirstColdActionIsGrantedTheWholeHold() {
        assertEquals(HOLD, start("p", now = 0))
    }

    @Test
    fun aPeerGetsOneColdActionPerCooldownOfEitherKind() {
        start("p", now = 0)
        cold.end("p", now = 10_000)
        assertNull("a hunt", start("p", now = COOLDOWN - 1))
        assertNull("or a fast window", start("p", now = COOLDOWN - 1, kind = ColdAction.FAST_ADVERT))
        assertEquals("its 10 s spell is still inside the budget's window", HOLD - 10_000, start("p", now = COOLDOWN))
    }

    @Test
    fun oneColdHuntRunsAtATimeAndOneFastWindowBesideIt() {
        start("p", now = 0)
        assertNull("another peer's hunt waits", start("q", now = 1_000))
        assertEquals("a fast window is a lane of its own", HOLD, start("q", now = 1_000, kind = ColdAction.FAST_ADVERT))
        assertNull(start("r", now = 2_000, kind = ColdAction.FAST_ADVERT))
    }

    @Test
    fun aHuntThatEndedEarlySpendsOnlyTheTimeItRan() {
        start("p", now = 0)
        cold.end("p", now = 60_000) // linked a minute in
        assertEquals("the budget left", HOLD - 60_000, start("q", now = 70_000))
    }

    @Test
    fun thePhoneWideBudgetIsTheHoldPerCooldown() {
        assertEquals(HOLD, start("p", now = 0))
        assertNull("spent: the hold ran out at 180 s", start("q", now = HOLD))
        assertNull(start("q", now = COOLDOWN - 1))
        assertEquals("the spell left the window", HOLD, start("q", now = COOLDOWN + HOLD))
    }

    @Test
    fun theBudgetComesBackAsTheWindowSlidesPastASpell() {
        start("p", now = 0)
        // At COOLDOWN + 120 s, 120 s of the 180 s spell have slid out of the window: 60 s spent, 120 s left.
        assertEquals(120_000L, start("q", now = COOLDOWN + 120_000))
    }

    @Test
    fun aScrapOfBudgetUnderTheLeastGrantIsRefused() {
        start("p", now = 0)
        cold.end("p", now = HOLD - ColdSightings.MIN_GRANT_MS + 1)
        assertNull(start("q", now = HOLD))
        // Inside the cooldown a spent one would still refuse q; p's spell (ended at 156 001) has left the budget's window.
        assertEquals("nor is the peer's cooldown spent on it", HOLD, start("q", now = COOLDOWN + HOLD - 1))
    }

    @Test
    fun endingAPeerLeavesAnotherPeersSpellAlone() {
        start("p", now = 0, kind = ColdAction.FAST_ADVERT)
        start("q", now = 0)
        cold.end("p", now = 1_000)
        assertNull("q's hunt still runs", start("r", now = 2_000))
    }

    @Test
    fun theCooldownsAreAnLruOfTheBoundedSize() {
        // Each start ends at once, so only the cooldowns refuse.
        for ((i, peer) in listOf("a", "b", "c", "d").withIndex()) {
            val now = i * 1_000L
            start(peer, now = now, kind = ColdAction.FAST_ADVERT)
            cold.end(peer, now = now)
        }
        assertEquals("a was the eldest of four in three slots", HOLD, start("a", now = 5_000))
        assertNull("d is still cooling down", start("d", now = 5_000, kind = ColdAction.FAST_ADVERT))
    }

    @Test
    fun noneIsNeverGranted() {
        assertNull(start("p", now = 0, kind = ColdAction.NONE))
    }

    @Test
    fun clearForgetsEverySpellAndCooldown() {
        start("p", now = 0)
        cold.clear()
        assertEquals(HOLD, start("p", now = 1_000))
    }

    @Test
    fun aRunningColdHuntEndsForTheFirstReasonThatHolds() {
        assertNull("runs on", ColdSightings.endReason(linked = false, backingOff = false, present = true, off = false))
        assertEquals("linked", ColdSightings.endReason(linked = true, backingOff = true, present = false, off = true))
        assertEquals("backoff", ColdSightings.endReason(linked = false, backingOff = true, present = false, off = true))
        assertEquals("gone", ColdSightings.endReason(linked = false, backingOff = false, present = false, off = true))
        assertEquals("off", ColdSightings.endReason(linked = false, backingOff = false, present = true, off = true))
    }

    private companion object {
        const val HOLD = 180_000L
        const val COOLDOWN = 1_800_000L
    }
}
