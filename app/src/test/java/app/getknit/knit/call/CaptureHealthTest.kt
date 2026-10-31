package app.getknit.knit.call

import app.getknit.knit.call.CaptureWatch.Excuse
import app.getknit.knit.call.CaptureWatch.Health
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Which microphone path a call records from, and the watch that moves a dead one (C-3.13-12/13, ADR 2026-10.kw29). */
class CaptureHealthTest {
    private class FakeJournal : CaptureJournal {
        var record = CaptureRecord.NONE
        var failing = false

        override suspend fun read(): CaptureRecord {
            if (failing) throw IOException("unreadable")
            return record
        }

        override suspend fun write(record: CaptureRecord) {
            if (failing) throw IOException("unwritable")
            this.record = record
        }
    }

    private val journal = FakeJournal()

    @Test
    fun aGenericImageOpensOnTheFallbackAndAnyOtherPhoneOnTheStandardPath() {
        assertEquals(
            CaptureChoice(CaptureProfile.FALLBACK, CaptureReason.GENERIC_IMAGE),
            CapturePolicy.choose(CaptureRecord.NONE, STAMP, genericImage = true),
        )
        assertEquals(CaptureChoice(CaptureProfile.STANDARD, null), CapturePolicy.choose(CaptureRecord.NONE, STAMP, genericImage = false))
    }

    @Test
    fun aVerdictOfThisBuildOutranksTheImageAndAnUpdateForgetsIt() {
        val learned = CaptureRecord(STAMP, CaptureProfile.FALLBACK, CaptureReason.SILENT_CALL, at = 5L)
        assertEquals(CaptureChoice(CaptureProfile.FALLBACK, CaptureReason.SILENT_CALL), CapturePolicy.choose(learned, STAMP, false))
        assertEquals(CaptureChoice(CaptureProfile.STANDARD, null), CapturePolicy.choose(learned, "2|new", false))
        // A trial may try the standard path on a GSI.
        val chosen = CaptureRecord(STAMP, CaptureProfile.STANDARD, CaptureReason.CHOSEN, at = 5L)
        assertEquals(CaptureChoice(CaptureProfile.STANDARD, CaptureReason.CHOSEN), CapturePolicy.choose(chosen, STAMP, true))
    }

    @Test
    fun aSilentCallIsLearnedAndResetForgetsIt() =
        runBlocking {
            val health = CaptureHealth(journal, STAMP, genericImage = false, now = { 7L })
            health.load()
            assertEquals(CaptureProfile.STANDARD, health.choice.value.profile)
            health.learnedSilent()
            assertEquals(CaptureChoice(CaptureProfile.FALLBACK, CaptureReason.SILENT_CALL), health.choice.value)
            assertEquals(7L, journal.record.at)
            val next = CaptureHealth(journal, STAMP, genericImage = false)
            next.load()
            assertEquals(CaptureProfile.FALLBACK, next.choice.value.profile)
            next.reset()
            assertEquals(CaptureChoice(CaptureProfile.STANDARD, null), next.choice.value)
        }

    @Test
    fun anUnreadableStoreLeavesTheImagesAnswer() =
        runBlocking {
            journal.failing = true
            val health = CaptureHealth(journal, STAMP, genericImage = true)
            health.load()
            health.learnedSilent()
            assertEquals(CaptureProfile.FALLBACK, health.choice.value.profile)
        }

    private fun CaptureWatch.feed(
        ms: Long,
        peak: Int,
        muted: Boolean = false,
        excuse: Excuse? = null,
    ): List<Health> {
        val seen = mutableListOf<Health>()
        repeat((ms / FRAME_MS).toInt()) { frame(peak, FRAME_MS, muted) { excuse }?.let(seen::add) }
        return seen
    }

    @Test
    fun threeSecondsOfZerosOnTheStandardPathSwitchOnceThenDeadIsDead() {
        val watch = CaptureWatch(canSwitch = true)
        assertEquals(emptyList<Health>(), watch.feed(2_990L, peak = 0))
        assertEquals(listOf(Health.SWITCHED), watch.feed(10L, peak = 0))
        assertTrue(watch.switched)
        // The fallback recorder gives zeros too: nothing further to try.
        assertEquals(listOf(Health.DEAD), watch.feed(3_000L, peak = 0))
        // Sound arrives after all: the fallback is live.
        assertEquals(listOf(Health.SWITCHED), watch.feed(10L, peak = 400))
    }

    @Test
    fun aRealMicrophoneIsLiveAndItsQuietIsNeverZeros() {
        val watch = CaptureWatch(canSwitch = true)
        assertEquals(listOf(Health.LIVE), watch.feed(10L, peak = 2))
        // A quiet room's floor is still above digital silence.
        assertEquals(emptyList<Health>(), watch.feed(10_000L, peak = 3))
        assertFalse(watch.switched)
    }

    @Test
    fun aRunOfZerosIsBrokenBySoundAndMutedTimeNeverCounts() {
        val watch = CaptureWatch(canSwitch = true)
        watch.feed(2_000L, peak = 0)
        watch.feed(10L, peak = 500)
        assertEquals(emptyList<Health>(), watch.feed(2_000L, peak = 0))
        assertEquals(emptyList<Health>(), watch.feed(60_000L, peak = 0, muted = true))
        assertEquals(Health.LIVE, watch.health)
    }

    @Test
    fun aPlatformMuteOrASilencedClientIsNotADeadMicrophone() {
        val muted = CaptureWatch(canSwitch = true)
        assertEquals(emptyList<Health>(), muted.feed(9_000L, peak = 0, excuse = Excuse.MUTED))
        assertFalse(muted.switched)
        val silenced = CaptureWatch(canSwitch = true)
        assertEquals(listOf(Health.SILENCED), silenced.feed(3_000L, peak = 0, excuse = Excuse.SILENCED))
        assertFalse(silenced.switched)
        assertEquals(listOf(Health.LIVE), silenced.feed(10L, peak = 900))
    }

    @Test
    fun aRecordErrorIsDeadAtOnceAndTheFallbackHasNowhereToSwitch() {
        val standard = CaptureWatch(canSwitch = true)
        assertEquals(Health.SWITCHED, standard.failed { null })
        val fallback = CaptureWatch(canSwitch = false)
        assertEquals(Health.DEAD, fallback.failed { null })
        assertNull(fallback.failed { null })
    }

    private companion object {
        const val STAMP = "1|fingerprint"
        const val FRAME_MS = 10
    }
}
