package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.LinkFade
import app.getknit.knit.mesh.bluetooth.LinkFade.Edge
import app.getknit.knit.mesh.bluetooth.PhyTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a Bluetooth link counts as near its floor (ADR 2026-10.79yb's ninth amendment): entry, unanswered reads, the sticky exit. */
class LinkFadeTest {
    private var tuning = PhyTuning()

    private fun fade() = LinkFade({ tuning })

    @Test
    fun theDefaultsAreTheAmendments() {
        assertEquals(-95, tuning.fadeDbm)
        assertEquals(-90, tuning.fadeExitDbm)
        assertEquals(10_000L, tuning.fadeExitHoldMs)
        assertEquals(3, tuning.fadeStaleReads)
    }

    @Test
    fun aReadAtTheFloorFadesTheLinkOnceAndAReadAboveItDoesNot() {
        val fade = fade()
        assertNull(fade.onRead(-94.9, 2_000))
        assertFalse(fade.fading)
        assertEquals(Edge.ON, fade.onRead(-95.0, 4_000))
        assertTrue(fade.fading)
        assertNull("one edge, not one per read", fade.onRead(-99.0, 6_000))
    }

    @Test
    fun aFadingLinkStaysFadingUntilStrongReadsHaveHeldForTheExitHold() {
        val fade = fade()
        fade.onRead(-97.0, 0)
        // Between the floor and the exit: still fading, however long.
        assertNull(fade.onRead(-92.0, 2_000))
        assertNull(fade.onRead(-91.0, 30_000))
        assertTrue(fade.fading)
        // At the exit from 32 s: not yet at 41.999 s, off at 42 s.
        assertNull(fade.onRead(-90.0, 32_000))
        assertNull(fade.onRead(-85.0, 41_999))
        assertEquals(Edge.OFF, fade.onRead(-80.0, 42_000))
        assertFalse(fade.fading)
    }

    @Test
    fun aWeakReadInsideTheExitHoldRestartsIt() {
        val fade = fade()
        fade.onRead(-97.0, 0)
        fade.onRead(-88.0, 2_000)
        fade.onRead(-91.0, 8_000) // dips under the exit: the hold starts over
        fade.onRead(-88.0, 10_000)
        assertNull(fade.onRead(-88.0, 19_999))
        assertEquals(Edge.OFF, fade.onRead(-88.0, 20_000))
    }

    @Test
    fun anUnansweredReadInsideTheExitHoldRestartsIt() {
        val fade = fade()
        fade.onRead(-97.0, 0)
        fade.onRead(-80.0, 2_000)
        assertNull(fade.onUnanswered())
        fade.onRead(-80.0, 8_000)
        assertNull(fade.onRead(-80.0, 17_999))
        assertEquals(Edge.OFF, fade.onRead(-80.0, 18_000))
    }

    @Test
    fun theStaleReadsInARowFadeTheLink() {
        val fade = fade()
        fade.onRead(-70.0, 0)
        assertNull(fade.onUnanswered())
        assertNull(fade.onUnanswered())
        assertEquals(Edge.ON, fade.onUnanswered())
        assertEquals(3, fade.unansweredInRow)
        assertNull("one edge, not one per read", fade.onUnanswered())
    }

    @Test
    fun anAnswerBetweenUnansweredReadsStartsTheCountOver() {
        val fade = fade()
        fade.onUnanswered()
        fade.onUnanswered()
        fade.onRead(-70.0, 5_000)
        assertNull(fade.onUnanswered())
        assertNull(fade.onUnanswered())
        assertFalse(fade.fading)
    }

    @Test
    fun aSleepThenOneUnansweredReadIsNotAFade() {
        // The read loop's wait stops while the CPU sleeps; the clock does not. Time between reads counts for nothing.
        val fade = fade()
        fade.onRead(-70.0, 0)
        assertNull(fade.onUnanswered()) // the first read after 60 s asleep
        assertFalse(fade.fading)
        assertEquals(60_000L, fade.sinceAnsweredMs(60_000))
    }

    @Test
    fun aLinkBetweenTheFloorAndTheExitIsNotFadedBySleep() {
        // The reviewer's case: a Coded link at −93 that sleeps between reads never fades on time alone.
        val fade = fade()
        listOf(0L, 300_000L, 900_000L).forEach { at ->
            assertNull(fade.onRead(-93.0, at))
            assertNull(fade.onUnanswered())
        }
        assertFalse(fade.fading)
    }

    @Test
    fun readsThatNeverAnsweredFadeTheLinkToo() {
        val fade = fade()
        fade.onUnanswered()
        fade.onUnanswered()
        assertEquals(Edge.ON, fade.onUnanswered())
        assertNull(fade.sinceAnsweredMs(1))
    }

    @Test
    fun anUnansweredFadeLeavesOnlyThroughTheExitHold() {
        val fade = fade()
        repeat(3) { fade.onUnanswered() }
        assertTrue(fade.fading)
        // An answer at a strong RSSI ends the run, not the fade.
        assertNull(fade.onRead(-70.0, 9_000))
        assertTrue(fade.fading)
        assertEquals(Edge.OFF, fade.onRead(-70.0, 19_000))
    }

    @Test
    fun theThresholdsAreTunedLive() {
        val fade = fade()
        tuning = tuning.copy(fadeDbm = -85, fadeExitDbm = -80, fadeExitHoldMs = 2_000, fadeStaleReads = 1)
        assertEquals(Edge.ON, fade.onRead(-85.0, 1_000))
        fade.onRead(-80.0, 2_000)
        assertEquals(Edge.OFF, fade.onRead(-80.0, 4_000))
        assertEquals("one unanswered read is enough at 1", Edge.ON, fade.onUnanswered())
    }
}
