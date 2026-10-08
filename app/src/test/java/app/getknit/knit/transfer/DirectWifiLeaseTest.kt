package app.getknit.knit.transfer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
