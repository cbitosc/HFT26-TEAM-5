package app.getknit.knit.call

import app.getknit.knit.mesh.protocol.CallPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The caller's tones (docs/VOICE_CALLS.md C-3.13-14): ringback only once the callee rings, a busy or congestion tone
 * for a call that never connected, a short tone when the peer or the path ends a connected one, each end heard once,
 * and nothing for a walkie or for the callee's own side.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallTonesTest {
    private val calling = CallState(id = "c1", peerId = "p", outgoing = true, phase = CallPhase.Calling, startedAt = 0L)
    private val ringing = calling.copy(phase = CallPhase.Ringing)
    private val connected = calling.copy(phase = CallPhase.Connected, connectedAt = 5L)

    private fun ended(
        from: CallState,
        reason: Int,
        byPeer: Boolean = false,
    ) = from.copy(phase = CallPhase.Ended, reason = reason, endedByPeer = byPeer)

    private fun tone(
        previous: CallState?,
        state: CallState?,
    ) = CallTonePolicy.toneFor(previous, state)

    @Test
    fun ringbackOnlyOnceTheCalleeRings() {
        assertNull(tone(null, calling))
        assertEquals(CallTone.RINGBACK, tone(calling, ringing))
        assertNull(tone(ringing, ringing.copy(phase = CallPhase.Connecting)))
    }

    @Test
    fun theCalleeHearsNoRingback() {
        val alerting = calling.copy(outgoing = false, phase = CallPhase.Alerting)
        assertNull(tone(null, alerting))
        assertNull(tone(alerting, ended(alerting, CallPayload.REASON_USER, byPeer = true)))
    }

    @Test
    fun aWalkieHasNoTones() {
        val walkie = ringing.copy(walkie = true)
        assertNull(tone(calling, walkie))
        assertNull(tone(walkie.copy(connectedAt = 1L), ended(walkie.copy(connectedAt = 1L), CallPayload.REASON_USER, byPeer = true)))
    }

    @Test
    fun aDeclineOrABusyCalleeIsBusy() {
        assertEquals(CallTone.BUSY, tone(ringing, ended(ringing, CallPayload.REASON_USER, byPeer = true)))
        assertEquals(CallTone.BUSY, tone(ringing, ended(ringing, CallPayload.REASON_BUSY, byPeer = true)))
        // A system that refused the call here declines it busy too.
        assertEquals(CallTone.BUSY, tone(calling, ended(calling, CallPayload.REASON_BUSY)))
    }

    @Test
    fun aCallThatNeverConnectedForWantOfAPathIsCongestion() {
        assertEquals(CallTone.CONGESTION, tone(calling, ended(calling, CallPayload.REASON_NO_ROUTE)))
        assertEquals(CallTone.CONGESTION, tone(ringing, ended(ringing, CallPayload.REASON_MEDIA)))
    }

    @Test
    fun aHangUpOrARingOutIsSilent() {
        assertNull(tone(ringing, ended(ringing, CallPayload.REASON_USER)))
        assertNull(tone(ringing, ended(ringing, CallPayload.REASON_TIMEOUT)))
        assertNull(tone(connected, ended(connected, CallPayload.REASON_USER)))
    }

    @Test
    fun aConnectedCallThePeerOrThePathEndedSaysSo() {
        assertEquals(CallTone.ENDED, tone(connected, ended(connected, CallPayload.REASON_USER, byPeer = true)))
        assertEquals(CallTone.ENDED, tone(connected, ended(connected, CallPayload.REASON_MEDIA)))
        val answered = connected.copy(outgoing = false)
        assertEquals(CallTone.ENDED, tone(answered, ended(answered, CallPayload.REASON_USER, byPeer = true)))
    }

    @Test
    fun anEndIsHeardOnlyOnItsStep() {
        val busy = ended(ringing, CallPayload.REASON_BUSY, byPeer = true)
        assertNull(tone(null, busy))
        assertNull(tone(busy, busy))
    }

    private class FakePlayer : TonePlayer {
        val events = mutableListOf<String>()

        override fun play(tone: CallTone) {
            events += "play $tone"
        }

        override fun stop() {
            events += "stop"
        }
    }

    private class FakeControl : CallControl {
        override val state = MutableStateFlow<CallState?>(null)

        override suspend fun answer(id: String) = true

        override suspend fun decline(
            id: String,
            reason: Int,
        ) = Unit

        override suspend fun hangUp() = Unit

        override suspend fun setMuted(muted: Boolean) = Unit
    }

    private fun TestScope.sync(): Pair<FakeControl, FakePlayer> {
        val control = FakeControl()
        val player = FakePlayer()
        CallToneSync(control, player, backgroundScope).start()
        runCurrent()
        return control to player
    }

    @Test
    fun theSyncPlaysEachToneOnceAndStopsItWhenTheCallMovesOn() =
        runTest {
            val (control, player) = sync()
            control.state.value = calling
            runCurrent()
            control.state.value = ringing
            runCurrent()
            control.state.value = ringing.copy(muted = true)
            runCurrent()
            control.state.value = connected
            runCurrent()
            assertEquals(listOf("play RINGBACK", "stop"), player.events)
        }

    @Test
    fun aSettledEndLetsItsToneFinish() =
        runTest {
            val (control, player) = sync()
            control.state.value = ringing
            runCurrent()
            val busy = ended(ringing, CallPayload.REASON_BUSY, byPeer = true)
            control.state.value = busy
            runCurrent()
            control.state.value = busy.copy(missed = true)
            runCurrent()
            assertEquals(listOf("play RINGBACK", "play BUSY"), player.events)
            // The next call cuts it.
            control.state.value = calling.copy(id = "c2")
            runCurrent()
            assertEquals("stop", player.events.last())
        }
}
