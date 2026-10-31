package app.getknit.knit.call

import app.getknit.knit.call.CallAudioRoute.Kind
import app.getknit.knit.mesh.protocol.CallPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SystemCallSync` keeps the system's call in step with the manager's (docs/VOICE_CALLS.md §3.13): added as it dials or
 * alerts, answered and set active once each, disconnected with the cause it ended for, carried over by glare, refused
 * as the system refuses it, and a walkie kept off the earpiece.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SystemCallSyncTest {
    private val earpiece = CallAudioRoute(Kind.EARPIECE, "Earpiece", "e")
    private val speaker = CallAudioRoute(Kind.SPEAKER, "Speaker", "s")
    private val buds = CallAudioRoute(Kind.BLUETOOTH, "Pixel Buds", "b")

    // Everything both fakes were asked, in order, so a test can read what came before what.
    private val events = mutableListOf<String>()

    private inner class FakeControl : CallControl {
        override val state = MutableStateFlow<CallState?>(null)
        val muted = mutableListOf<Boolean>()
        var answers = true

        override suspend fun answer(id: String): Boolean {
            events += "control answer $id"
            if (!answers) return false
            state.value = state.value?.copy(phase = CallPhase.Connecting)
            return true
        }

        override suspend fun decline(
            id: String,
            reason: Int,
        ) {
            events += "control decline $id $reason"
            state.value = state.value?.copy(phase = CallPhase.Ended, reason = reason)
        }

        override suspend fun hangUp() {
            events += "control hangUp"
            state.value = state.value?.copy(phase = CallPhase.Ended, reason = CallPayload.REASON_USER)
        }

        override suspend fun setMuted(muted: Boolean) {
            this.muted += muted
        }
    }

    private inner class FakeCall(
        val request: SystemCallRequest,
        val onAnswer: suspend () -> Boolean,
        val onDisconnect: suspend () -> Unit,
    ) : SystemCall {
        override val routing = MutableStateFlow(CallRouting(earpiece, listOf(earpiece, speaker)))
        override val muted = MutableStateFlow(false)

        override suspend fun answer(): Boolean = true.also { events += "system answer" }

        override suspend fun setActive(): Boolean = true.also { events += "system active" }

        override suspend fun request(route: CallAudioRoute): Boolean = true.also { events += "system route ${route.key}" }

        override suspend fun disconnect(end: SystemEnd) {
            events += "system disconnect $end"
        }
    }

    private inner class FakeSystem : SystemCalls {
        var registers = true
        var refuses = false
        val calls = mutableListOf<FakeCall>()

        override suspend fun register(): Boolean = registers

        override suspend fun add(
            request: SystemCallRequest,
            onAnswer: suspend () -> Boolean,
            onDisconnect: suspend () -> Unit,
        ): SystemCall? {
            events += "system add ${if (request.outgoing) "outgoing" else "incoming"}${if (request.walkie) " walkie" else ""}"
            if (refuses) return null
            return FakeCall(request, onAnswer, onDisconnect).also { calls += it }
        }
    }

    private val control = FakeControl()
    private val system = FakeSystem()

    private fun TestScope.sync(): SystemCallSync =
        SystemCallSync(control, system, backgroundScope).also {
            it.start()
            runCurrent()
        }

    private fun TestScope.set(state: CallState) {
        control.state.value = state
        runCurrent()
    }

    private fun call(
        phase: CallPhase,
        outgoing: Boolean = true,
        id: String = "c1",
        walkie: Boolean = false,
        reason: Int? = null,
        endedByPeer: Boolean = false,
        missed: Boolean = false,
    ) = CallState(
        id = id,
        peerId = "peer",
        outgoing = outgoing,
        phase = phase,
        startedAt = 0L,
        reason = reason,
        walkie = walkie,
        endedByPeer = endedByPeer,
        missed = missed,
    )

    private val StateFlow<CallState?>.now: CallState get() = checkNotNull(value)

    @Test
    fun anOutgoingCallIsAddedAsItDialsSetActiveOnceItConnectsAndDisconnectedAsItEnds() =
        runTest {
            sync()
            set(call(CallPhase.Calling))
            set(call(CallPhase.Ringing))
            set(call(CallPhase.Connecting))
            set(call(CallPhase.Connected))
            set(call(CallPhase.Reconnecting))
            set(call(CallPhase.Connected))
            set(call(CallPhase.Ended, reason = CallPayload.REASON_USER))
            assertEquals(listOf("system add outgoing", "system active", "system disconnect LOCAL"), events)
        }

    @Test
    fun aPeersHangUpIsRemote() =
        runTest {
            sync()
            set(call(CallPhase.Connected))
            set(call(CallPhase.Ended, reason = CallPayload.REASON_USER, endedByPeer = true))
            assertEquals("system disconnect REMOTE", events.last())
        }

    @Test
    fun anIncomingCallIsAddedAsItAlertsAndAnsweredOnceWhenThisPhoneAnswersIt() =
        runTest {
            sync()
            set(call(CallPhase.Alerting, outgoing = false))
            assertEquals(listOf("system add incoming"), events)
            set(call(CallPhase.Connecting, outgoing = false))
            set(call(CallPhase.Connected, outgoing = false))
            assertEquals(listOf("system add incoming", "system answer"), events)
        }

    @Test
    fun theSystemsOwnAnswerIsNotAnsweredBack() =
        runTest {
            sync()
            set(call(CallPhase.Alerting, outgoing = false))
            assertTrue(system.calls.single().onAnswer())
            runCurrent()
            assertEquals(CallPhase.Connecting, control.state.now.phase)
            assertEquals(listOf("system add incoming", "control answer c1"), events)
        }

    @Test
    fun theSystemsAnswerToACallThatStoppedRingingSaysSoAndThisPhoneAnswersItself() =
        runTest {
            sync()
            set(call(CallPhase.Alerting, outgoing = false))
            control.answers = false
            assertEquals(false, system.calls.single().onAnswer())
            set(call(CallPhase.Connecting, outgoing = false))
            assertEquals("system answer", events.last())
        }

    @Test
    fun theSystemHangingUpARingingCallDeclinesItAndIsNotToldBack() =
        runTest {
            sync()
            set(call(CallPhase.Alerting, outgoing = false))
            system.calls.single().onDisconnect()
            runCurrent()
            assertEquals(listOf("system add incoming", "control decline c1 ${CallPayload.REASON_USER}"), events)
        }

    @Test
    fun theSystemHangingUpALiveCallHangsItUp() =
        runTest {
            sync()
            set(call(CallPhase.Connected))
            system.calls.single().onDisconnect()
            runCurrent()
            assertEquals(listOf("system add outgoing", "system active", "control hangUp"), events)
        }

    @Test
    fun aCallTheSystemRefusesIsDeclinedBusyWhileItAlerts() =
        runTest {
            system.refuses = true
            sync()
            set(call(CallPhase.Alerting, outgoing = false))
            assertEquals(listOf("system add incoming", "control decline c1 ${CallPayload.REASON_BUSY}"), events)
        }

    @Test
    fun anOutgoingCallTheSystemRefusesIsHungUpAndNotOfferedAgain() =
        runTest {
            system.refuses = true
            sync()
            set(call(CallPhase.Calling))
            set(call(CallPhase.Ringing))
            assertEquals(listOf("system add outgoing", "control hangUp"), events)
        }

    @Test
    fun aPhoneTheSystemWouldNotRegisterRefusesEveryCall() =
        runTest {
            system.registers = false
            sync()
            set(call(CallPhase.Alerting, outgoing = false))
            assertEquals(listOf("control decline c1 ${CallPayload.REASON_BUSY}"), events)
        }

    @Test
    fun glareCarriesTheSystemsCallOverToTheSurvivingId() =
        runTest {
            sync()
            set(call(CallPhase.Calling, id = "ours"))
            // Theirs survives (§3.5): answered at once, under their id, with the same peer.
            set(call(CallPhase.Connecting, outgoing = false, id = "theirs"))
            set(call(CallPhase.Connected, outgoing = false, id = "theirs"))
            set(call(CallPhase.Ended, outgoing = false, id = "theirs", reason = CallPayload.REASON_USER))
            assertEquals(listOf("system add outgoing", "system active", "system disconnect LOCAL"), events)
        }

    @Test
    fun aCallThatEndedLeavesTheNextOneToAFreshSystemCall() =
        runTest {
            sync()
            set(call(CallPhase.Connected, id = "c1"))
            set(call(CallPhase.Ended, id = "c1", reason = CallPayload.REASON_USER))
            set(call(CallPhase.Alerting, outgoing = false, id = "c2"))
            assertEquals(2, system.calls.size)
            assertEquals(listOf("system add outgoing", "system active", "system disconnect LOCAL", "system add incoming"), events)
        }

    @Test
    fun onlyTheMuteTheSystemChangesReachesTheCall() =
        runTest {
            sync()
            set(call(CallPhase.Connected))
            runCurrent()
            assertEquals(emptyList<Boolean>(), control.muted)
            system.calls
                .single()
                .muted.value = true
            runCurrent()
            assertEquals(listOf(true), control.muted)
        }

    @Test
    fun theRouteIsSharedWhileTheSystemHoldsTheCallAndClearedAfter() =
        runTest {
            val sync = sync()
            set(call(CallPhase.Connected))
            assertEquals(earpiece, sync.routing.value?.current)
            system.calls
                .single()
                .routing.value = CallRouting(buds, listOf(earpiece, speaker, buds))
            runCurrent()
            assertEquals(buds, sync.routing.value?.current)
            assertTrue(sync.request("s"))
            set(call(CallPhase.Ended, reason = CallPayload.REASON_USER))
            assertNull(sync.routing.value)
            assertEquals(false, sync.request("s"))
        }

    @Test
    fun aLiveCallOnTheEarpieceIsLeftThere() =
        runTest {
            sync()
            set(call(CallPhase.Connected))
            assertTrue(events.none { it.startsWith("system route") })
        }

    @Test
    fun aWalkieOnTheEarpieceUnaskedGoesBackToTheLoudspeakerOnce() =
        runTest {
            sync()
            set(call(CallPhase.Connected, walkie = true))
            val c = system.calls.single()
            assertEquals("system route s", events.last())
            // Refused: the same earpiece again is not asked about again.
            c.routing.value = CallRouting(earpiece, listOf(speaker, earpiece))
            runCurrent()
            assertEquals(1, events.count { it == "system route s" })
            // The headset that took it goes away: back to the loudspeaker.
            c.routing.value = CallRouting(buds, listOf(earpiece, speaker, buds))
            runCurrent()
            c.routing.value = CallRouting(earpiece, listOf(earpiece, speaker))
            runCurrent()
            assertEquals(2, events.count { it == "system route s" })
        }

    @Test
    fun anEarpieceTheUserPickedInAWalkieIsKept() =
        runTest {
            val sync = sync()
            set(call(CallPhase.Connected, walkie = true))
            val c = system.calls.single()
            c.routing.value = CallRouting(speaker, listOf(earpiece, speaker))
            runCurrent()
            sync.request(earpiece)
            c.routing.value = CallRouting(earpiece, listOf(earpiece, speaker))
            runCurrent()
            assertEquals("system route e", events.last())
        }

    @Test
    fun aLiveCallThatDropsToAWalkieLeavesTheEarpiece() =
        runTest {
            val sync = sync()
            set(call(CallPhase.Connected))
            sync.request(earpiece)
            set(call(CallPhase.Connected, walkie = true))
            assertEquals("system route s", events.last())
        }

    @Test
    fun theSystemIsToldWhyACallEnded() {
        fun end(
            outgoing: Boolean = true,
            reason: Int = CallPayload.REASON_USER,
            byPeer: Boolean = false,
            missed: Boolean = false,
            answered: Boolean = true,
        ) = SystemCallSync.endOf(
            call(CallPhase.Ended, outgoing = outgoing, reason = reason, endedByPeer = byPeer, missed = missed),
            answered,
        )
        assertEquals(SystemEnd.LOCAL, end())
        assertEquals(SystemEnd.REMOTE, end(byPeer = true))
        assertEquals(SystemEnd.BUSY, end(reason = CallPayload.REASON_BUSY, byPeer = true))
        assertEquals(SystemEnd.ERROR, end(reason = CallPayload.REASON_NO_ROUTE))
        assertEquals(SystemEnd.ERROR, end(reason = CallPayload.REASON_MEDIA, byPeer = true))
        assertEquals(
            SystemEnd.MISSED,
            end(outgoing = false, reason = CallPayload.REASON_USER, byPeer = true, missed = true, answered = false),
        )
        assertEquals(SystemEnd.MISSED, end(outgoing = false, reason = CallPayload.REASON_TIMEOUT, answered = false))
        assertEquals(SystemEnd.REJECTED, end(outgoing = false, answered = false))
        assertEquals(SystemEnd.LOCAL, end(outgoing = false))
        assertEquals(SystemEnd.LOCAL, end(reason = CallPayload.REASON_TIMEOUT))
    }
}
