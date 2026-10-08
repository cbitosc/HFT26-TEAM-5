package app.getknit.knit.call

import app.getknit.knit.mesh.protocol.CallPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * Two (or three) [CallManager]s over an in-process signal net, in virtual time: the protocol of docs/VOICE_CALLS.md
 * §3 end to end, with WebRTC replaced by [FakeMedia]. Signals are delivered asynchronously and in order, as the mesh
 * would; [Net.drop] loses one on the air.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallManagerTest {
    private class FakeSession(
        val servers: List<TurnServer>,
        val relay: Boolean,
        val events: (MediaEvent) -> Unit,
        private val sdp: () -> String?,
    ) : CallSession {
        var accepted: String? = null
        var answeredOffer: String? = null
        val added = Collections.synchronizedList(mutableListOf<String>())
        var micOff = false
        var closed = false

        override suspend fun offer(): String? = sdp()

        override suspend fun answer(remoteOffer: String): String? = sdp().also { answeredOffer = remoteOffer }

        override suspend fun accept(remoteAnswer: String): Boolean {
            accepted = remoteAnswer
            return true
        }

        override fun addCandidates(lines: List<String>) {
            added += lines
        }

        override fun setMuted(muted: Boolean) {
            micOff = muted
        }

        override fun setSpeaker(on: Boolean) = Unit

        override suspend fun route(): String = "host/host"

        override fun close() {
            closed = true
        }
    }

    private class FakeMedia : CallMedia {
        val sessions = Collections.synchronizedList(mutableListOf<FakeSession>())
        var sdp: String? = SDP
        var throwOnOpen = false

        val last: FakeSession get() = sessions.last()

        override fun open(
            servers: List<TurnServer>,
            relayOnly: Boolean,
            events: (MediaEvent) -> Unit,
        ): CallSession {
            check(!throwOnOpen) { "no audio device" }
            return FakeSession(servers, relayOnly, events) { sdp }.also { sessions += it }
        }
    }

    private class Net(
        private val scope: CoroutineScope,
        private val clock: () -> Long,
    ) {
        val nodes = mutableMapOf<String, CallManager>()
        var drop: (from: String, CallPayload) -> Boolean = { _, _ -> false }

        fun deliver(
            from: String,
            to: String,
            payload: CallPayload,
        ) {
            if (drop(from, payload)) return
            val target = nodes[to] ?: return
            val sentAt = clock()
            scope.launch { target.onSignal(from, payload, sentAt) }
        }
    }

    private class Side(
        val name: String,
        val manager: CallManager,
        val media: FakeMedia,
        val sent: MutableList<CallPayload>,
        val missed: MutableList<MissedCall>,
        val turnAsks: MutableList<String>,
    ) {
        var fail = false
        var turn: suspend (String) -> List<TurnServer> = { emptyList() }

        val state: CallState? get() = manager.state.value

        fun phases(): List<Int> = synchronized(sent) { sent.map { it.ph } }
    }

    private fun TestScope.side(
        name: String,
        net: Net,
    ): Side {
        val sent = Collections.synchronizedList(mutableListOf<CallPayload>())
        val missed = Collections.synchronizedList(mutableListOf<MissedCall>())
        val turnAsks = Collections.synchronizedList(mutableListOf<String>())
        val media = FakeMedia()
        lateinit var side: Side
        val manager =
            CallManager(
                signals =
                    CallSignals { peerId, payload ->
                        if (side.fail) return@CallSignals false
                        sent += payload
                        net.deliver(name, peerId, payload)
                        true
                    },
                turn =
                    TurnSource { peerId ->
                        turnAsks += peerId
                        side.turn(peerId)
                    },
                media = media,
                scope = backgroundScope,
                selfId = { name },
                clock = { testScheduler.currentTime },
                timings = TIMINGS,
            )
        side = Side(name, manager, media, sent, missed, turnAsks)
        net.nodes[name] = manager
        backgroundScope.launch { manager.missed.collect { missed += it } }
        return side
    }

    private fun TestScope.pair(): Triple<Net, Side, Side> {
        val net = Net(backgroundScope) { testScheduler.currentTime }
        return Triple(net, side(ALICE, net), side(BOB, net))
    }

    /** Alice calls Bob and Bob answers; both are left Connecting with their media open. */
    private suspend fun TestScope.connecting(): Triple<Net, Side, Side> {
        val (net, alice, bob) = pair()
        assertNull(alice.manager.start(BOB))
        runCurrent()
        assertTrue(bob.manager.answer(bob.state!!.id))
        runCurrent()
        return Triple(net, alice, bob)
    }

    @Test
    fun aCallRingsConnectsAndEndsOnBothSides() =
        runTest {
            val (_, alice, bob) = pair()
            assertNull(alice.manager.start(BOB))
            runCurrent()
            assertEquals(CallPhase.Ringing, alice.state!!.phase)
            assertEquals(CallPhase.Alerting, bob.state!!.phase)
            assertEquals(alice.state!!.id, bob.state!!.id)
            assertFalse(bob.state!!.outgoing)

            assertTrue(bob.manager.answer(bob.state!!.id))
            runCurrent()
            assertEquals(CallPhase.Connecting, alice.state!!.phase)
            assertEquals(CallPhase.Connecting, bob.state!!.phase)
            // Each side's SDP reached the other's media, and nothing else did.
            assertEquals(SDP, bob.media.last.answeredOffer)
            assertEquals(SDP, alice.media.last.accepted)

            alice.media.last.events(MediaEvent.Connected)
            bob.media.last.events(MediaEvent.Connected)
            runCurrent()
            assertEquals(CallPhase.Connected, alice.state!!.phase)
            assertEquals(CallPhase.Connected, bob.state!!.phase)

            alice.manager.hangUp()
            runCurrent()
            assertEquals(CallPhase.Ended, alice.state!!.phase)
            assertEquals(CallPhase.Ended, bob.state!!.phase)
            assertEquals(CallPayload.REASON_USER, bob.state!!.reason)
            assertTrue(alice.media.last.closed && bob.media.last.closed)
            // About five frames a call (§3.6): OFFER, RINGING, ANSWER, END — no CANDS was needed here.
            assertEquals(listOf(CallPayload.PHASE_OFFER, CallPayload.PHASE_END), alice.phases())
            assertEquals(listOf(CallPayload.PHASE_RINGING, CallPayload.PHASE_ANSWER), bob.phases())
            assertTrue(bob.missed.isEmpty())
        }

    @Test
    fun aDeclineEndsTheCallerAndIsNoMissedCall() =
        runTest {
            val (_, alice, bob) = pair()
            alice.manager.start(BOB)
            runCurrent()
            bob.manager.decline(bob.state!!.id)
            runCurrent()
            assertEquals(CallPhase.Ended, alice.state!!.phase)
            assertEquals(CallPayload.REASON_USER, alice.state!!.reason)
            assertTrue(alice.media.last.closed)
            assertTrue(bob.missed.isEmpty())
        }

    @Test
    fun anUnansweredCallRingsOutAndIsMissedOnTheCallee() =
        runTest {
            val (_, alice, bob) = pair()
            alice.manager.start(BOB)
            runCurrent()
            advanceTimeBy(TIMINGS.ringOutMs + 1)
            runCurrent()
            // C-3.3-1: the caller withdraws with CANCEL timeout, and the callee stops ringing on it.
            assertEquals(CallPayload.REASON_TIMEOUT, alice.state!!.reason)
            assertEquals(CallPayload.PHASE_CANCEL, alice.sent.last().ph)
            assertEquals(CallPhase.Ended, bob.state!!.phase)
            assertEquals(listOf(ALICE), bob.missed.map { it.peerId })
        }

    @Test
    fun aRingWhoseCancelIsLostStopsOnItsOwnAndIsMissed() =
        runTest {
            val (net, alice, bob) = pair()
            net.drop = { _, p -> p.ph == CallPayload.PHASE_CANCEL }
            alice.manager.start(BOB)
            runCurrent()
            advanceTimeBy(TIMINGS.alertMs + 1)
            runCurrent()
            assertEquals(CallPhase.Ended, bob.state!!.phase)
            assertEquals(CallPayload.REASON_TIMEOUT, bob.state!!.reason)
            assertEquals(1, bob.missed.size)
        }

    @Test
    fun aBusyCalleeDeclinesWithoutAlerting() =
        runTest {
            val (net, alice, bob) = pair()
            val carol = side(CAROL, net)
            carol.manager.start(BOB)
            runCurrent()
            val carolsCall = bob.state!!.id
            alice.manager.start(BOB)
            runCurrent()
            // C-3.3-2: Bob keeps ringing for Carol and answers Alice with DECLINE busy.
            assertEquals(carolsCall, bob.state!!.id)
            assertEquals(CallPhase.Alerting, bob.state!!.phase)
            assertEquals(CallPayload.REASON_BUSY, alice.state!!.reason)
            assertEquals(CallPhase.Ended, alice.state!!.phase)
            assertTrue(bob.missed.isEmpty())
            assertEquals(CallRefusal.BUSY, carol.manager.start(ALICE))
        }

    @Test
    fun aStaleOfferNeverRingsAndIsAMissedCall() =
        runTest {
            val (_, alice, bob) = pair()
            val offer = CallPayload(id = "c1", ph = CallPayload.PHASE_OFFER, sdp = SDP)
            bob.manager.onSignal(ALICE, offer, sentAt = testScheduler.currentTime - TIMINGS.staleOfferMs - 1)
            runCurrent()
            // C-3.4-1/2: an offer from a phone that was offline pulls in late and becomes the missed-call row.
            assertNull(bob.state)
            assertTrue(bob.sent.isEmpty())
            assertEquals(listOf(ALICE), bob.missed.map { it.peerId })
            // ...and its own late CANCEL adds nothing.
            bob.manager.onSignal(ALICE, CallPayload(id = "c1", ph = CallPayload.PHASE_CANCEL, rsn = CallPayload.REASON_TIMEOUT), 0L)
            runCurrent()
            assertEquals(1, bob.missed.size)
            assertNull(alice.state)
        }

    @Test
    fun aCancelThatOvertakesItsOfferMeansItNeverRings() =
        runTest {
            val (_, _, bob) = pair()
            val now = testScheduler.currentTime
            // C-3.4-3: a terminal phase for an unknown call is remembered; C-3.4-2: a hand-withdrawn one is not missed.
            bob.manager.onSignal(ALICE, CallPayload(id = "c1", ph = CallPayload.PHASE_CANCEL, rsn = CallPayload.REASON_USER), now)
            bob.manager.onSignal(ALICE, CallPayload(id = "c1", ph = CallPayload.PHASE_OFFER, sdp = SDP), now)
            runCurrent()
            assertNull(bob.state)
            assertTrue(bob.missed.isEmpty())
            // A caller that rang out before its offer arrived did leave a missed call.
            bob.manager.onSignal(ALICE, CallPayload(id = "c2", ph = CallPayload.PHASE_CANCEL, rsn = CallPayload.REASON_TIMEOUT), now)
            bob.manager.onSignal(ALICE, CallPayload(id = "c2", ph = CallPayload.PHASE_OFFER, sdp = SDP), now)
            runCurrent()
            assertNull(bob.state)
            assertEquals(1, bob.missed.size)
            assertTrue(bob.sent.isEmpty())
        }

    @Test
    fun aRepeatedFrameChangesNothing() =
        runTest {
            val (_, _, bob) = pair()
            val offer = CallPayload(id = "c1", ph = CallPayload.PHASE_OFFER, sdp = SDP)
            val now = testScheduler.currentTime
            bob.manager.onSignal(ALICE, offer, now)
            bob.manager.onSignal(ALICE, offer, now)
            runCurrent()
            // C-3.3-4: one alert, one RINGING, one TURN prefetch.
            assertEquals(listOf(CallPayload.PHASE_RINGING), bob.phases())
            assertEquals(listOf(ALICE), bob.turnAsks)
        }

    @Test
    fun anOfferWithoutDtlsOrOpusIsDeclinedForItsMedia() =
        runTest {
            val (_, _, bob) = pair()
            val sdes = SDP.replace("a=fingerprint:sha-256 AB:CD", "a=crypto:1 AES_CM_128_HMAC_SHA1_80 inline:x")
            bob.manager.onSignal(ALICE, CallPayload(id = "c1", ph = CallPayload.PHASE_OFFER, sdp = sdes), testScheduler.currentTime)
            runCurrent()
            assertNull(bob.state)
            assertEquals(CallPayload.PHASE_DECLINE, bob.sent.single().ph)
            assertEquals(CallPayload.REASON_MEDIA, bob.sent.single().rsn)
        }

    @Test
    fun glareLeavesOneCallTheLowerIdsAnsweredWithoutAlerting() =
        runTest {
            val (_, alice, bob) = pair()
            // Both dial before either has seen the other's offer.
            alice.manager.start(BOB)
            bob.manager.start(ALICE)
            runCurrent()
            // C-3.5-1: "alice" < "bob", so Alice's offer survives; Bob drops his own without a CANCEL and answers.
            assertEquals(alice.state!!.id, bob.state!!.id)
            assertEquals(CallPhase.Connecting, alice.state!!.phase)
            assertEquals(CallPhase.Connecting, bob.state!!.phase)
            assertFalse(CallPayload.PHASE_CANCEL in bob.phases())
            assertFalse(CallPayload.PHASE_RINGING in bob.phases())
            assertEquals(CallPayload.PHASE_ANSWER, bob.sent.last().ph)
            assertEquals(alice.state!!.id, bob.sent.last().id)
            assertTrue(
                bob.media.sessions
                    .first()
                    .closed,
            )
            assertFalse(bob.media.last.closed)
            assertTrue(alice.missed.isEmpty() && bob.missed.isEmpty())
        }

    @Test
    fun aConnectionThatNeverComesUpEndsWithNoRoute() =
        runTest {
            val (_, alice, bob) = connecting()
            advanceTimeBy(TIMINGS.connectMs + 1)
            runCurrent()
            // C-3.3-3.
            assertEquals(CallPayload.REASON_NO_ROUTE, alice.state!!.reason)
            assertEquals(CallPayload.REASON_NO_ROUTE, bob.state!!.reason)
            assertTrue(alice.media.last.closed && bob.media.last.closed)
        }

    @Test
    fun aLostPathEndsTheCallAfterItsGraceButARecoveredOneDoesNot() =
        runTest {
            val (_, alice, bob) = connecting()
            alice.media.last.events(MediaEvent.Connected)
            bob.media.last.events(MediaEvent.Connected)
            runCurrent()
            alice.media.last.events(MediaEvent.Disconnected)
            runCurrent()
            advanceTimeBy(TIMINGS.disconnectGraceMs / 2)
            alice.media.last.events(MediaEvent.Connected)
            runCurrent()
            advanceTimeBy(TIMINGS.disconnectGraceMs)
            runCurrent()
            assertEquals(CallPhase.Connected, alice.state!!.phase)

            alice.media.last.events(MediaEvent.Disconnected)
            runCurrent()
            advanceTimeBy(TIMINGS.disconnectGraceMs + 1)
            runCurrent()
            assertEquals(CallPayload.REASON_NO_ROUTE, alice.state!!.reason)
            assertEquals(CallPhase.Ended, bob.state!!.phase)
        }

    @Test
    fun anOfferThatCannotBeSealedEndsTheCall() =
        runTest {
            val (_, alice, bob) = pair()
            alice.fail = true
            alice.manager.start(BOB)
            runCurrent()
            assertEquals(CallPayload.REASON_ERROR, alice.state!!.reason)
            assertTrue(alice.media.last.closed)
            assertNull(bob.state)
        }

    @Test
    fun aMediaLayerThatThrowsEndsTheCallAndTellsThePeer() =
        runTest {
            val (_, alice, bob) = pair()
            alice.manager.start(BOB)
            runCurrent()
            bob.media.throwOnOpen = true
            bob.manager.answer(bob.state!!.id)
            runCurrent()
            assertEquals(CallPayload.REASON_ERROR, bob.state!!.reason)
            assertEquals(CallPhase.Ended, alice.state!!.phase)
        }

    @Test
    fun turnCredentialsReachTheMediaAndARelayOnlyCallSendsNoHostCandidate() =
        runTest {
            val (_, alice, bob) = pair()
            val server = TurnServer(listOf("turn:lab.example:3478?transport=udp"), "1800000000:abc", "secret")
            alice.turn = { listOf(server) }
            alice.manager.relayOnly = true
            alice.manager.start(BOB)
            runCurrent()
            assertEquals(listOf(server), alice.media.last.servers)
            assertTrue(alice.media.last.relay)
            val offer = alice.sent.first { it.ph == CallPayload.PHASE_OFFER }.sdp!!
            assertFalse(" typ host" in offer)
            assertTrue(" typ relay" in offer)
            // Without TURN the callee still answers, on whatever candidates it has.
            assertTrue(bob.manager.answer(bob.state!!.id))
            runCurrent()
            assertTrue(
                bob.media.last.servers
                    .isEmpty(),
            )
            assertFalse(bob.media.last.relay)
            assertEquals(CallPhase.Connecting, alice.state!!.phase)
            // The credential never rides a frame (C-7.6-11).
            assertTrue(alice.sent.none { "secret" in it.toString() })
        }

    @Test
    fun anOfferIsHandledWithoutWaitingOnTurnOrTheMedia() =
        runTest {
            val (_, _, bob) = pair()
            val never = CompletableDeferred<List<TurnServer>>()
            bob.turn = { never.await() }
            val handled =
                launch {
                    bob.manager.onSignal(ALICE, CallPayload(id = "c1", ph = CallPayload.PHASE_OFFER, sdp = SDP), testScheduler.currentTime)
                }
            runCurrent()
            // The inbound path is free again although the TURN prefetch never returns.
            assertTrue(handled.isCompleted)
            assertEquals(CallPhase.Alerting, bob.state!!.phase)
            assertEquals(listOf(CallPayload.PHASE_RINGING), bob.phases())
            // Answering waits on the prefetch off the caller's path; the call is still live.
            assertTrue(bob.manager.answer("c1"))
            runCurrent()
            assertEquals(CallPhase.Connecting, bob.state!!.phase)
            assertTrue(bob.media.sessions.isEmpty())
            never.complete(emptyList())
            runCurrent()
            assertEquals(1, bob.media.sessions.size)
        }

    @Test
    fun lateCandidatesRideOneCandsAndWaitForTheSdpTheyBelongTo() =
        runTest {
            val (_, alice, bob) = pair()
            alice.manager.start(BOB)
            runCurrent()
            // Alice's relay allocation lands after her offer went out, while Bob is still ringing.
            alice.media.last.events(MediaEvent.LateCandidates(listOf(RELAY_CAND)))
            runCurrent()
            assertEquals(CallPayload.PHASE_CANDS, alice.sent.last().ph)
            assertTrue(bob.media.sessions.isEmpty())
            // C-3.6-2: one CANDS per side, however many late batches the media reports.
            alice.media.last.events(MediaEvent.LateCandidates(listOf("candidate:9 1 udp 1 203.0.113.10 9 typ relay")))
            runCurrent()
            assertEquals(1, alice.phases().count { it == CallPayload.PHASE_CANDS })
            // Held until Bob's media has Alice's offer applied, then handed over.
            bob.manager.answer(bob.state!!.id)
            runCurrent()
            assertEquals(
                listOf(RELAY_CAND),
                bob.media.last.added
                    .toList(),
            )
        }

    @Test
    fun aHangUpBeforeTheOfferLeftSendsACancelAndNoOffer() =
        runTest {
            val (_, alice, bob) = pair()
            val slow = CompletableDeferred<List<TurnServer>>()
            alice.turn = { slow.await() }
            alice.manager.start(BOB)
            runCurrent()
            alice.manager.hangUp()
            slow.complete(emptyList())
            runCurrent()
            // The peer only ever hears a CANCEL user for a call it never saw: remembered, never rung, never missed.
            assertEquals(listOf(CallPayload.PHASE_CANCEL), alice.phases())
            assertTrue(alice.media.sessions.all { it.closed })
            assertNull(bob.state)
            assertTrue(bob.missed.isEmpty())
            assertEquals(CallRefusal.SELF, alice.manager.start(ALICE))
            assertNull(alice.manager.start(BOB))
        }

    private companion object {
        const val ALICE = "alice"
        const val BOB = "bob"
        const val CAROL = "carol"
        const val RELAY_CAND = "candidate:3 1 udp 41885439 203.0.113.9 49170 typ relay raddr 0.0.0.0 rport 0"

        val SDP =
            listOf(
                "v=0",
                "m=audio 9 UDP/TLS/RTP/SAVPF 111",
                "a=rtpmap:111 opus/48000/2",
                "a=fingerprint:sha-256 AB:CD",
                "a=candidate:1 1 udp 2122260223 192.0.2.1 50000 typ host",
                "a=candidate:3 1 udp 41885439 203.0.113.9 49170 typ relay raddr 0.0.0.0 rport 0",
                "",
            ).joinToString("\r\n")

        val TIMINGS = CallTimings()
    }
}
