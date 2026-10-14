package app.getknit.knit.transfer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** The one Wi-Fi Direct radio, shared by a transfer and a call (ADR 2026-10.kxnw): one holder, and only it releases. */
class DirectWifiLeaseTest {
    private val log = SideLog("phone")
    private val radio = FakeDirectWifi(log)
    private val lease = DirectWifiLease(radio)
    private val transfer = lease.leasedTo(DirectWifiLease.TRANSFER)
    private val call = lease.leasedTo("call-1")

    @Test
    fun theFirstHostHoldsTheRadioAndTheOtherIsRefusedBusy() =
        runBlocking {
            call.host(GroupCredentials.random(), 1_000L)
            assertEquals("call-1", lease.owner)
            assertEquals(TransferRefusal.Busy, transfer.refusal())
            assertNull(call.refusal())
            val refused = assertThrows(DirectWifiException::class.java) { runBlocking { transfer.join(GroupCredentials.random(), 1_000L) } }
            assertEquals(TransferRefusal.Busy, refused.refusal)
            assertEquals(listOf("host"), log.events)
        }

    @Test
    fun aReleaseFromAViewThatDoesNotHoldTheRadioTearsNothingDown() =
        runBlocking {
            call.join(GroupCredentials.random(), 1_000L, ipv4 = true)
            // The transfer's manager releases on every end path, hosted or not: the call's group must survive it.
            transfer.release()
            assertEquals("call-1", lease.owner)
            assertEquals(0, radio.released.size)
            call.release()
            assertNull(lease.owner)
            assertEquals(1, radio.released.size)
            assertNull(transfer.refusal())
        }

    /** A gate that records what the lease told it; [allow] false is a phone that may not host. */
    private class RecordingGate(
        var allow: Boolean = true,
    ) : HostGate {
        val told = mutableListOf<String>()

        override fun mayHost() = allow

        override suspend fun hosting(): Boolean {
            told += "hosting"
            return allow
        }

        override suspend fun hosted() {
            told += "hosted"
        }
    }

    @Test
    fun aPhoneThatMayNotHostNeverRaisesAGroupButStillJoins() =
        runBlocking {
            val gate = RecordingGate(allow = false)
            val gated = DirectWifiLease(radio, gate)
            val caller = gated.leasedTo("call-2")
            assertFalse(gated.mayHost())
            assertEquals(TransferRefusal.CannotHost, caller.hostRefusal())
            assertNull(caller.refusal())
            val refused = assertThrows(DirectWifiException::class.java) { runBlocking { caller.host(GroupCredentials.random(), 1_000L) } }
            assertEquals(TransferRefusal.CannotHost, refused.refusal)
            assertNull(gated.owner)
            caller.join(GroupCredentials.random(), 1_000L, ipv4 = true)
            caller.release()
            assertEquals(listOf("join", "release"), log.events)
            assertEquals(emptyList<String>(), gate.told)
        }

    @Test
    fun theGateHearsAGroupOfOursGoUpAndComeDownOnce() =
        runBlocking {
            val gate = RecordingGate()
            val gated = DirectWifiLease(radio, gate)
            val sender = gated.leasedTo(DirectWifiLease.TRANSFER)
            val other = gated.leasedTo("call-3")
            sender.host(GroupCredentials.random(), 1_000L)
            // Refused Busy: no marker, and its release is no one's.
            assertThrows(DirectWifiException::class.java) { runBlocking { other.host(GroupCredentials.random(), 1_000L) } }
            other.release()
            sender.release()
            sender.release()
            assertEquals(listOf("hosting", "hosted"), gate.told)
        }

    @Test
    fun aJoinedGroupIsNeverReportedAsHosted() =
        runBlocking {
            val gate = RecordingGate()
            val gated = DirectWifiLease(radio, gate)
            val callee = gated.leasedTo("call-4")
            callee.join(GroupCredentials.random(), 1_000L, ipv4 = true)
            callee.release()
            assertEquals(emptyList<String>(), gate.told)
        }

    @Test
    fun theSweepStandsAsideWhileTheRadioIsHeld() =
        runBlocking {
            transfer.host(GroupCredentials.random(), 1_000L)
            call.sweep()
            assertEquals(0, radio.swept.size)
            transfer.release()
            call.sweep()
            assertEquals(1, radio.swept.size)
        }
}
