package app.getknit.knit.transfer

import app.getknit.knit.crash.ProcessExitEvidence
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The Wi-Fi Direct host guard (ADR 2026-10.w3dw): a generic image never hosts; any other phone latches only after two
 * unexplained deaths running while a group of ours was up, inside one boot.
 */
class DirectHostGuardTest {
    private class FakeJournal : DirectHostJournal {
        var record = DirectHostRecord.NONE
        var failing = false
        var writes = 0

        override suspend fun read(): DirectHostRecord {
            if (failing) throw IOException("store unreadable")
            return record
        }

        override suspend fun write(record: DirectHostRecord) {
            if (failing) throw IOException("store unwritable")
            writes++
            this.record = record
        }
    }

    private val journal = FakeJournal()
    private var nowMs = 1_000_000L
    private var elapsedMs = 50_000L
    private var exit: ProcessExitEvidence? = null

    private fun guard(generic: Boolean = false) =
        DirectHostGuard(journal, exits = { exit }, stamp = STAMP, genericImage = generic, now = { nowMs }, elapsed = { elapsedMs })

    /** One process: start, raise a group, and die with it up (no release). */
    private fun diesHosting() =
        runBlocking {
            val g = guard()
            g.load()
            assertTrue(g.hosting())
            nowMs += 60_000L
            elapsedMs += 60_000L
        }

    @Test
    fun aGenericImageNeverHostsAndHasNoLatchToClear() =
        runBlocking {
            val g = guard(generic = true)
            assertFalse(g.mayHost())
            g.load()
            assertFalse(g.hosting())
            assertEquals(HostVerdict.GenericImage, g.verdict.value)
            g.reset()
            assertEquals(HostVerdict.GenericImage, g.verdict.value)
            assertEquals(0L, journal.record.since)
        }

    @Test
    fun aCleanHostAndReleaseLeavesNoMarker() =
        runBlocking {
            val g = guard()
            g.load()
            assertTrue(g.hosting())
            assertTrue(journal.record.since > 0L)
            g.hosted()
            assertEquals(0L, journal.record.since)
            assertEquals(0, journal.record.fails)
        }

    @Test
    fun twoUnexplainedDeathsRunningLatchAndOneDoesNot() =
        runBlocking {
            diesHosting()
            val second = guard()
            second.load()
            assertEquals(1, journal.record.fails)
            assertTrue(second.mayHost())
            assertTrue(second.hosting())
            nowMs += 60_000L
            elapsedMs += 60_000L
            val third = guard()
            third.load()
            assertEquals(HostVerdict.Latched(nowMs), third.verdict.value)
            assertFalse(third.mayHost())
            assertFalse(third.hosting())
        }

    @Test
    fun aCleanHostBetweenTwoDeathsStartsTheCountAgain() =
        runBlocking {
            diesHosting()
            val g = guard()
            g.load()
            assertTrue(g.hosting())
            g.hosted()
            assertTrue(g.hosting())
            nowMs += 1_000L
            elapsedMs += 1_000L
            val next = guard()
            next.load()
            assertEquals(1, journal.record.fails)
            assertTrue(next.mayHost())
        }

    @Test
    fun aRebootWhileHostingCountsForNothing() =
        runBlocking {
            diesHosting()
            // A dead battery or a restart: the boot clock started over. A system_server crash keeps it running.
            elapsedMs = 5_000L
            val g = guard()
            g.load()
            assertEquals(0, journal.record.fails)
            assertEquals(0L, journal.record.since)
            assertTrue(g.mayHost())
        }

    @Test
    fun aDeathThePlatformExplainsCountsForNothing() =
        runBlocking {
            diesHosting()
            exit = ProcessExitEvidence(at = nowMs - 1_000L, nativeFault = false, explained = true)
            guard().load()
            diesHosting()
            exit = ProcessExitEvidence(at = nowMs - 1_000L, nativeFault = true, explained = false)
            val g = guard()
            g.load()
            assertEquals(0, journal.record.fails)
            assertTrue(g.mayHost())
        }

    @Test
    fun anExitRecordOlderThanTheGroupIsNotItsExplanation() =
        runBlocking {
            diesHosting()
            exit = ProcessExitEvidence(at = 1L, nativeFault = false, explained = true)
            guard().load()
            assertEquals(1, journal.record.fails)
        }

    @Test
    fun aNewBuildOfTheAppOrTheOsGetsAnotherTry() =
        runBlocking {
            journal.record = DirectHostRecord(STAMP, since = 0L, sinceElapsed = 0L, fails = 2, latchedAt = 5L)
            val latched = guard()
            latched.load()
            assertEquals(HostVerdict.Latched(5L), latched.verdict.value)
            val updated =
                DirectHostGuard(journal, { exit }, stamp = "2|other", genericImage = false, now = { nowMs }, elapsed = { elapsedMs })
            updated.load()
            assertEquals(HostVerdict.Allowed, updated.verdict.value)
            assertEquals("2|other", journal.record.stamp)
        }

    @Test
    fun theResetLetsALatchedPhoneHostAtOnce() =
        runBlocking {
            journal.record = DirectHostRecord(STAMP, since = 0L, sinceElapsed = 0L, fails = 2, latchedAt = 5L)
            val g = guard()
            g.load()
            g.reset()
            assertEquals(HostVerdict.Allowed, g.verdict.value)
            assertTrue(g.hosting())
        }

    @Test
    fun anUnreadableStoreFailsOpen() =
        runBlocking {
            journal.failing = true
            val g = guard()
            g.load()
            assertTrue(g.mayHost())
            assertTrue(g.hosting())
            g.hosted()
        }

    @Test
    fun aHostWaitsForTheStartsSettleAndLoadsOnce() =
        runBlocking {
            diesHosting()
            val writesBefore = journal.writes
            val g = guard()
            // No load() first: hosting() settles what the dead process left before it marks a new group.
            assertTrue(g.hosting())
            g.load()
            assertEquals(1, journal.record.fails)
            assertEquals(writesBefore + 2, journal.writes)
        }

    private companion object {
        const val STAMP = "1|fingerprint"
    }
}
