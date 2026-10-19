package app.getknit.knit.data

import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a stranger's name clash fades (ADR 2026-10.k8ge): a week of silence, judged strictly, and never for an anchored
 * or verified peer; and the hourly re-judge that lets a stranger cross the line on the calendar with no write.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LabelFadeTest {
    private val fade = LabelFade(anchors = mockk(), clock = { 0L }, ticks = emptyFlow(), transact = { it() })
    private val now = 1_800_000_000_000L

    @Test
    fun aStrangerSilentForExactlyAWeekStillClashes() {
        assertFalse(fade.fades("stranger", verified = false, lastSeenAt = now - LabelFade.LABEL_FADE_MS, anchors = emptySet(), now = now))
    }

    @Test
    fun aStrangerSilentForAWeekAndAMillisecondFades() {
        assertTrue(
            fade.fades("stranger", verified = false, lastSeenAt = now - LabelFade.LABEL_FADE_MS - 1, anchors = emptySet(), now = now),
        )
    }

    @Test
    fun anAnchoredPeerNeverFadesHoweverLongItIsSilent() {
        assertFalse(fade.fades("contact", verified = false, lastSeenAt = 0L, anchors = setOf("contact"), now = now))
    }

    @Test
    fun aVerifiedPeerNeverFadesHoweverLongItIsSilent() {
        assertFalse(fade.fades("met", verified = true, lastSeenAt = 0L, anchors = emptySet(), now = now))
    }

    @Test
    fun theHourlyTickFiresAtOnceAndThenEveryHour() =
        runTest {
            var ticks = 0
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { LabelFade.hourly().collect { ticks++ } }
            runCurrent()
            assertEquals("a directory opened now is judged now", 1, ticks)

            advanceTimeBy(LabelFade.TICK_MS - 1)
            assertEquals(1, ticks)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, ticks)
        }
}
