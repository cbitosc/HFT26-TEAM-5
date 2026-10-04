package app.getknit.knit

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.content.Context
import app.getknit.knit.mesh.bluetooth.BleConnectArbiter
import app.getknit.knit.mesh.bluetooth.BleGattPayloadReader
import app.getknit.knit.mesh.bluetooth.GattPayloads
import app.getknit.knit.mesh.power.ElapsedWait
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [BleGattPayloadReader]'s give-up (#127): the read holds [BleConnectArbiter], so the scan and every dial wait on it,
 * and its 12 s are counted on the elapsed clock (ADR 2026-10.pj9w). Virtual time stands in for the monotonic clock
 * and a separate elapsed clock jumps ahead of it, the phone asleep. The device is a mock whose GATT client never
 * hears back unless a test says so: a connect the stack leaves pending, as it does for 30 s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BleGattPayloadReaderTest {
    /** Elapsed = virtual time + whatever the "phone" slept through. */
    private class Clock(
        private val scope: TestScope,
    ) {
        var slept = 0L

        fun now(): Long = scope.currentTime + slept
    }

    private val arbiter = BleConnectArbiter()
    private val gatt = mockk<BluetoothGatt>(relaxed = true)
    private var callback: BluetoothGattCallback? = null

    @Suppress("DEPRECATION") // the overload the reader calls; see BleDoorbell.Client.open
    private val device =
        mockk<BluetoothDevice>().also { device ->
            every { device.connectGatt(any(), any(), any(), any<Int>()) } answers {
                callback = arg(2)
                gatt
            }
        }

    private fun reader(clock: Clock) =
        BleGattPayloadReader(mockk<Context>(relaxed = true), arbiter, ElapsedWait(clock::now, checkMs = 2_000))

    @Test
    fun aReadLeftHangingWhileThePhoneSleptGivesUpWithinOneCheckOfWaking() =
        runTest {
            val clock = Clock(this)
            var result: BleGattPayloadReader.Result? = null
            var endedAt = -1L
            launch {
                result = reader(clock).read(device)
                endedAt = currentTime
            }
            advanceTimeBy(1_000)
            assertTrue("the read holds the arbiter, so the scan pauses for it", arbiter.busy.value)
            clock.slept = 20_000 // the phone slept 20 s; the monotonic clock saw none of it
            advanceTimeBy(5_000)
            // A withTimeoutOrNull(12_000) would still have 11 s of awake time to go; this ends at its first look.
            assertEquals(BleGattPayloadReader.Result(GattPayloads.Outcome.Failed, "timeout@connect"), result)
            assertEquals(2_000, endedAt)
            assertFalse("the radio is free again for the scan and the dials", arbiter.busy.value)
            verify { gatt.disconnect() }
            verify { gatt.close() }
        }

    @Test
    fun withoutSuspendTheReadGivesUpAtItsLength() =
        runTest {
            val reader = reader(Clock(this))
            var result: BleGattPayloadReader.Result? = null
            launch { result = reader.read(device) }
            advanceTimeBy(11_999)
            assertNull(result)
            assertTrue(arbiter.busy.value)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(BleGattPayloadReader.Result(GattPayloads.Outcome.Failed, "timeout@connect"), result)
            assertEquals(GattPayloads.READ_TIMEOUT_MS, currentTime)
            assertFalse(arbiter.busy.value)
        }

    @Test
    fun aRefusedConnectEndsTheReadWhenTheStackSaysSo() =
        runTest {
            val reader = reader(Clock(this))
            var result: BleGattPayloadReader.Result? = null
            launch { result = reader.read(device) }
            advanceTimeBy(3_000)
            callback!!.onConnectionStateChange(gatt, GATT_ERROR, BluetoothProfile.STATE_DISCONNECTED)
            runCurrent()
            assertEquals(BleGattPayloadReader.Result(GattPayloads.Outcome.Failed, "connect"), result)
            assertEquals(3_000, currentTime)
            assertFalse(arbiter.busy.value)
        }

    private companion object {
        /** The stack's catch-all status (`GATT_ERROR`, 133), which `BluetoothGatt` does not name publicly. */
        const val GATT_ERROR = 133
    }
}
