package app.getknit.knit.call

import app.getknit.knit.data.message.CallOutcome
import app.getknit.knit.mesh.protocol.CallPayload
import app.getknit.knit.mesh.protocol.DirectInvite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import java.util.Base64
import java.util.Collections

/**
 * Two (or three) [CallManager]s over an in-process signal net, in virtual time: the protocol of docs/VOICE_CALLS.md
 * §3 end to end, with WebRTC replaced by [FakeMedia]. Signals are delivered asynchronously and in order, as the mesh
 * would; [Net.drop] loses one on the air.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // one call's whole contract over one two-sided harness, its direct leg included; splitting would scatter it
class CallManagerTest {
    private class FakeSession(
        val servers: List<TurnServer>,
        val relay: Boolean,
        val direct: Boolean,
        val events: (MediaEvent) -> Unit,
        private val sdp: () -> String?,
    ) : CallSession {
        var accepted: String? = null
        var accepts = 0
        var answeredOffer: String? = null
        val added = Collections.synchronizedList(mutableListOf<String>())
        var micOff = false
        var closed = false

        override suspend fun offer(): String? = sdp()

        override suspend fun answer(remoteOffer: String): String? = sdp().also { answeredOffer = remoteOffer }

        // §3.8: each restart's (or restart answer's) ICE servers, and the restart offers this side answered.
        val restarts = Collections.synchronizedList(mutableListOf<List<TurnServer>>())
        val reanswered = Collections.synchronizedList(mutableListOf<String>())

        override suspend fun restart(servers: List<TurnServer>): String? = sdp().also { restarts += servers }

        override suspend fun reanswer(
            remoteOffer: String,
            servers: List<TurnServer>,
        ): String? =
            sdp().also {
                restarts += servers
                reanswered += remoteOffer
            }

        override suspend fun accept(remoteAnswer: String): Boolean {
            accepted = remoteAnswer
            accepts += 1
            return true
        }

        override fun addCandidates(lines: List<String>) {
            added += lines
        }

        override fun setMuted(muted: Boolean) {
            micOff = muted
        }

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
            direct: Boolean,
            events: (MediaEvent) -> Unit,
        ): CallSession {
            check(!throwOnOpen) { "no audio device" }
            return FakeSession(servers, relayOnly, direct, events) { sdp }.also { sessions += it }
        }
    }

    /**
     * The direct leg (§3.10) as a recorder: what [CallManager] asked of it. Its "lane" hands a mirrored signal or a
     * group candidate straight to the peer's [CallManager] while [laneUp] — the real lane's job, minus the radio.
     */
    private class FakeDirect(
        private val name: String,
        private val scope: CoroutineScope,
    ) : CallDirect {
        var peer: FakeDirect? = null
        var offering = false
        var joinable = true
        var prewarmAllowed = true
        var laneUp = false
        val joins = Collections.synchronizedList(mutableListOf<String>())
        val hosts = Collections.synchronizedList(mutableListOf<String>())
        val ends = Collections.synchronizedList(mutableListOf<Pair<String, CallPayload?>>())
        val laneCands = Collections.synchronizedList(mutableListOf<String>())
        private val legs = Collections.synchronizedSet(mutableSetOf<String>())
        private val ended = Collections.synchronizedSet(mutableSetOf<String>())
        private val events = MutableSharedFlow<LaneEvent>(extraBufferCapacity = 64)

        override val inbound = events
        override val link: StateFlow<DirectLink?> = MutableStateFlow(null)

        override suspend fun invite(
            callId: String,
            peerId: String,
        ): DirectInvite? = if (offering) INVITE else null

        override fun canJoin(
            peerId: String,
            prewarm: Boolean,
        ): Boolean = joinable && (!prewarm || prewarmAllowed)

        override fun join(
            callId: String,
            peerId: String,
            invite: DirectInvite,
        ) {
            joins += callId
            legs += callId
        }

        override fun host(
            callId: String,
            peerId: String,
            invite: DirectInvite,
        ) {
            hosts += callId
            legs += callId
        }

        override fun mirror(
            callId: String,
            payload: CallPayload,
        ) {
            if (callId in ended || callId !in legs) return
            lane(LaneEvent.Signal(name, payload))
        }

        override fun candidates(
            callId: String,
            lines: List<String>,
        ) {
            laneCands += lines
            lane(LaneEvent.Candidates(name, callId, lines))
        }

        override fun end(
            callId: String,
            terminal: CallPayload?,
        ) {
            ends += callId to terminal
            if (callId in legs && callId !in ended) terminal?.let { lane(LaneEvent.Signal(name, it)) }
            ended += callId
            legs -= callId
        }

        override fun pending(callId: String): Boolean = callId in legs && !laneUp

        private fun lane(event: LaneEvent) {
            val target = peer ?: return
            if (laneUp) scope.launch { target.events.emit(event) }
        }
    }

    /** The walkie's seam (§3.11): off by default, so every other case runs the calls exactly as a build without it. */
    private class FakeWalkie : CallWalkie {
        override var enabled = false
        var peerCapable = true
        var live = true
        var ticketed = false

        override suspend fun capable(peerId: String) = peerCapable

        override suspend fun liveLikely(peerId: String) = live

        override fun castTicketed() = ticketed
    }

    /** The tombstones' journal (C-3.4-3) as a process death leaves it: what the last save wrote, and nothing else. */
    private class FakeTombs(
        var kept: List<CallTomb> = emptyList(),
    ) : CallTombJournal {
        var failLoad = false

        override suspend fun load(): List<CallTomb> {
            check(!failLoad) { "unreadable" }
            return kept
        }

        override suspend fun save(tombs: List<CallTomb>) {
            kept = tombs
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
        val logged: MutableList<CallLogged>,
        val turnAsks: MutableList<String>,
        val direct: FakeDirect,
        val walkie: FakeWalkie,
    ) {
        var fail = false
        var turn: suspend (String) -> List<TurnServer> = { emptyList() }

        // "Relay all calls" (C-3.7-3), as the manager reads it at each call.
        var relay = false

        // C-3.3-5: whether anything this side can see reaches the peer, as the manager looks while it has no RINGING.
        var reachable = true

        // What this side's connected spools advertise as STUN in their HELLOs (C-7.6-20).
        var stun: List<String> = emptyList()

        val state: CallState? get() = manager.state.value

        fun phases(): List<Int> = synchronized(sent) { sent.map { it.ph } }
    }

    private fun TestScope.side(
        name: String,
        net: Net,
        dropToWalkie: Boolean = false,
        tombs: CallTombJournal = CallTombJournal.None,
    ): Side {
        val sent = Collections.synchronizedList(mutableListOf<CallPayload>())
        val missed = Collections.synchronizedList(mutableListOf<MissedCall>())
        val logged = Collections.synchronizedList(mutableListOf<CallLogged>())
        val turnAsks = Collections.synchronizedList(mutableListOf<String>())
        val media = FakeMedia()
        val direct = FakeDirect(name, backgroundScope)
        val walkie = FakeWalkie()
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
                    object : TurnSource {
                        override suspend fun turnServers(peerId: String): List<TurnServer> {
                            turnAsks += peerId
                            return side.turn(peerId)
                        }

                        override fun stunUrls(): List<String> = side.stun
                    },
                media = media,
                scope = backgroundScope,
                selfId = { name },
                clock = { testScheduler.currentTime },
                timings = TIMINGS,
                direct = direct,
                walkie = walkie,
                dropToWalkie = dropToWalkie,
                relayAll = { side.relay },
                tombs = tombs,
                reach = { side.reachable },
            )
        side = Side(name, manager, media, sent, missed, logged, turnAsks, direct, walkie)
        net.nodes[name] = manager
        backgroundScope.launch { manager.missed.collect { missed += it } }
        backgroundScope.launch { manager.logged.collect { logged += it } }
        return side
    }

    private fun TestScope.pair(dropToWalkie: Boolean = false): Triple<Net, Side, Side> {
        val net = Net(backgroundScope) { testScheduler.currentTime }
        val alice = side(ALICE, net, dropToWalkie)
        val bob = side(BOB, net, dropToWalkie)
        alice.direct.peer = bob.direct
        bob.direct.peer = alice.direct
        return Triple(net, alice, bob)
    }

    /** Alice and Bob are neighbours: Alice's calls offer a group, and both lanes carry once their legs exist. */
    private fun TestScope.neighbours(): Triple<Net, Side, Side> =
        pair().also { (_, alice, bob) ->
            alice.direct.offering = true
            bob.direct.offering = true
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
    fun aCallNothingReachesSaysSoAndEndsNoRouteLongBeforeItsRingOut() =
        runTest {
            val (net, alice, bob) = pair()
            // The peer is off every link and on no relay we share: the OFFER goes nowhere and no RINGING comes back.
            alice.reachable = false
            net.drop = { from, _ -> from == ALICE }
            alice.manager.start(BOB)
            runCurrent()
            // C-3.3-5: the screen says so at once, while the call is still trying.
            assertEquals(CallPhase.Calling, alice.state!!.phase)
            assertTrue(alice.state!!.unreachable)
            advanceTimeBy(TIMINGS.unreachMs - 1)
            runCurrent()
            assertEquals(CallPhase.Calling, alice.state!!.phase)
            advanceTimeBy(TIMINGS.reachLookMs + 1)
            runCurrent()
            assertEquals(CallPhase.Ended, alice.state!!.phase)
            assertEquals(CallPayload.REASON_NO_ROUTE, alice.state!!.reason)
            assertTrue(alice.state!!.unreachable)
            // Withdrawn like a ring-out, so an OFFER a carrier brings later reads as missed, not as a ring.
            assertEquals(CallPayload.PHASE_CANCEL, alice.sent.last().ph)
            assertEquals(CallPayload.REASON_NO_ROUTE, alice.sent.last().rsn)
            assertTrue(alice.media.last.closed)
            assertEquals(CallOutcome.Unreachable, alice.onlyOutcome())
            assertNull(bob.state)
        }

    @Test
    fun aRingingProvesTheCallReachesThePeerWhateverTheMeshSays() =
        runTest {
            val (_, alice, _) = pair()
            // Nothing this phone can see reaches Bob, yet the OFFER got there (a quiet subscriber on a shared relay).
            alice.reachable = false
            alice.manager.start(BOB)
            runCurrent()
            assertEquals(CallPhase.Ringing, alice.state!!.phase)
            assertFalse(alice.state!!.unreachable)
            advanceTimeBy(TIMINGS.unreachMs * 2)
            runCurrent()
            assertEquals(CallPhase.Ringing, alice.state!!.phase)
            advanceTimeBy(TIMINGS.ringOutMs)
            runCurrent()
            assertEquals(CallPayload.REASON_TIMEOUT, alice.state!!.reason)
            assertEquals(CallOutcome.NoAnswer, alice.onlyOutcome())
        }

    @Test
    fun aPeerThatComesWithinReachKeepsTheCallTryingUntilItsRingOut() =
        runTest {
            val (net, alice, _) = pair()
            alice.reachable = false
            net.drop = { from, _ -> from == ALICE }
            alice.manager.start(BOB)
            runCurrent()
            assertTrue(alice.state!!.unreachable)
            advanceTimeBy(TIMINGS.unreachMs / 2)
            // A link to Bob comes up before the window runs out: the flag goes, and so does the early end.
            alice.reachable = true
            advanceTimeBy(TIMINGS.reachLookMs + 1)
            runCurrent()
            assertFalse(alice.state!!.unreachable)
            advanceTimeBy(TIMINGS.unreachMs)
            runCurrent()
            assertEquals(CallPhase.Calling, alice.state!!.phase)
            // Losing it again starts the window over rather than ending at once.
            alice.reachable = false
            advanceTimeBy(TIMINGS.reachLookMs + 1)
            runCurrent()
            assertTrue(alice.state!!.unreachable)
            assertEquals(CallPhase.Calling, alice.state!!.phase)
            advanceTimeBy(TIMINGS.ringOutMs)
            runCurrent()
            assertEquals(CallPhase.Ended, alice.state!!.phase)
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

    /**
     * C-3.4-3 across a process death: Bob heard Alice withdraw a call by hand before its offer reached him, and his
     * process died. The relaunched one, over the same journal, still never rings for the offer when it arrives — nor
     * calls it missed — while an offer it never heard the end of still rings.
     */
    @Test
    fun aCancelHeardBeforeARestartStillKeepsItsLateOfferFromRinging() =
        runTest {
            val journal = FakeTombs()
            val (net, _, bob) = pair()
            val first = side(BOB, net, tombs = journal)
            first.manager.onSignal(ALICE, CallPayload(id = "c1", ph = CallPayload.PHASE_CANCEL, rsn = CallPayload.REASON_USER), 0L)
            runCurrent()
            assertEquals(listOf("c1"), journal.kept.map { it.id })

            val relaunched = side(BOB, net, tombs = journal)
            val now = testScheduler.currentTime
            relaunched.manager.onSignal(ALICE, CallPayload(id = "c1", ph = CallPayload.PHASE_OFFER, sdp = SDP), now)
            runCurrent()
            assertNull(relaunched.state)
            assertTrue(relaunched.missed.isEmpty() && relaunched.logged.isEmpty() && relaunched.sent.isEmpty())

            relaunched.manager.onSignal(ALICE, CallPayload(id = "c2", ph = CallPayload.PHASE_OFFER, sdp = SDP), now)
            runCurrent()
            assertEquals(CallPhase.Alerting, relaunched.state!!.phase)
            assertNull("the first process's manager heard none of it", bob.state)
        }

    /**
     * A call Bob declined, then a process death, then a second copy of its offer (a spool keeps a DM scope's frames for
     * 48 h; the router's own dedup lasts ten minutes): the relaunched process neither rings nor logs it again.
     */
    @Test
    fun aCallEndedBeforeARestartIsNotRungAgainByALateCopy() =
        runTest {
            val journal = FakeTombs()
            val net = Net(backgroundScope) { testScheduler.currentTime }
            val alice = side(ALICE, net)
            side(BOB, net, tombs = journal).also { bob ->
                alice.manager.start(BOB)
                runCurrent()
                bob.manager.decline(bob.state!!.id)
                runCurrent()
            }
            val offer = alice.sent.first { it.ph == CallPayload.PHASE_OFFER }
            // Ended here, so it is kept as ended, whichever phase told the peer.
            assertEquals(CallPayload.PHASE_END, journal.kept.single { it.id == offer.id }.ph)

            val relaunched = side(BOB, net, tombs = journal)
            relaunched.manager.onSignal(ALICE, offer, testScheduler.currentTime)
            runCurrent()
            assertNull(relaunched.state)
            assertTrue(relaunched.missed.isEmpty() && relaunched.logged.isEmpty())
        }

    /** A tombstone older than any late copy can be (48 h, a spool's DM scope) is not carried into a new process. */
    @Test
    fun aTombstonePastItsKeepIsLeftBehind() =
        runTest {
            val journal = FakeTombs(listOf(CallTomb("c1", CallPayload.PHASE_CANCEL, CallPayload.REASON_USER, at = 0L)))
            advanceTimeBy(49 * HOUR_MS)
            val (net, _, _) = pair()
            val relaunched = side(BOB, net, tombs = journal)
            relaunched.manager.onSignal(ALICE, CallPayload(id = "c1", ph = CallPayload.PHASE_OFFER, sdp = SDP), testScheduler.currentTime)
            runCurrent()
            assertEquals(CallPhase.Alerting, relaunched.state!!.phase)
            // The next save drops it from the journal too.
            relaunched.manager.decline(relaunched.state!!.id)
            runCurrent()
            assertEquals(listOf(CallPayload.PHASE_END), journal.kept.map { it.ph })
        }

    /** A journal that cannot be read costs the process its old tombstones and nothing else: calls still ring. */
    @Test
    fun anUnreadableJournalStopsNoCall() =
        runTest {
            val journal = FakeTombs().apply { failLoad = true }
            val net = Net(backgroundScope) { testScheduler.currentTime }
            val alice = side(ALICE, net)
            val bob = side(BOB, net, tombs = journal)
            alice.manager.start(BOB)
            runCurrent()
            assertEquals(CallPhase.Alerting, bob.state!!.phase)
        }

    /** The one record [this] side logged, as its outcome — the line its thread shows (§3.12). */
    private fun Side.onlyOutcome(): CallOutcome = synchronized(logged) { logged.single().record.outcome }

    @Test
    fun aConnectedCallIsRecordedOnBothSidesUnderOneIdWithItsLength() =
        runTest {
            val (_, alice, bob) = connecting()
            alice.media.last.events(MediaEvent.Connected)
            bob.media.last.events(MediaEvent.Connected)
            runCurrent()
            advanceTimeBy(222_000L)
            bob.manager.hangUp()
            runCurrent()
            val ours = alice.logged.single()
            val theirs = bob.logged.single()
            assertEquals(BOB, ours.peerId)
            assertEquals(ALICE, theirs.peerId)
            assertEquals(ours.record.id, theirs.record.id)
            assertTrue(ours.record.outgoing)
            assertFalse(theirs.record.outgoing)
            assertEquals(CallOutcome.Completed, ours.record.outcome)
            assertEquals(CallOutcome.Completed, theirs.record.outcome)
            assertTrue(ours.record.byPeer)
            assertFalse(theirs.record.byPeer)
            assertEquals(222_000L, theirs.record.durationMs)
            // Nothing about the record went on the wire: the same four phases as a call without one.
            assertEquals(listOf(CallPayload.PHASE_OFFER), alice.phases())
            assertEquals(listOf(CallPayload.PHASE_RINGING, CallPayload.PHASE_ANSWER, CallPayload.PHASE_END), bob.phases())
        }

    @Test
    fun aDeclinedCallIsRecordedAsDeclinedFromEachSide() =
        runTest {
            val (_, alice, bob) = pair()
            alice.manager.start(BOB)
            runCurrent()
            bob.manager.decline(bob.state!!.id)
            runCurrent()
            assertEquals(CallOutcome.Declined, alice.onlyOutcome())
            assertEquals(CallOutcome.DeclinedByUs, bob.onlyOutcome())
        }

    @Test
    fun aCallThatRingsOutIsNoAnswerHereAndMissedThere() =
        runTest {
            val (_, alice, bob) = pair()
            alice.manager.start(BOB)
            runCurrent()
            advanceTimeBy(TIMINGS.ringOutMs + 1)
            runCurrent()
            assertEquals(CallOutcome.NoAnswer, alice.onlyOutcome())
            assertEquals(CallOutcome.Missed, bob.onlyOutcome())
            // The ring stopped on the caller's CANCEL; the callee's own alert timer fires nothing more.
            advanceTimeBy(TIMINGS.alertMs)
            runCurrent()
            assertEquals(1, bob.logged.size)
        }

    @Test
    fun aCallWithdrawnByHandIsCancelledHereAndMissedThere() =
        runTest {
            val (_, alice, bob) = pair()
            alice.manager.start(BOB)
            runCurrent()
            alice.manager.hangUp()
            runCurrent()
            assertEquals(CallOutcome.Cancelled, alice.onlyOutcome())
            assertEquals(CallOutcome.Missed, bob.onlyOutcome())
            assertEquals(1, bob.missed.size)
        }

    @Test
    fun aBusyCalleeIsRecordedOnTheCallerOnly() =
        runTest {
            val (net, alice, bob) = pair()
            val carol = side(CAROL, net)
            carol.manager.start(BOB)
            runCurrent()
            alice.manager.start(BOB)
            runCurrent()
            assertEquals(CallOutcome.Busy, alice.onlyOutcome())
            // Bob is still ringing for Carol, and his busy DECLINE made no line of its own.
            assertTrue(bob.logged.isEmpty())
            assertTrue(carol.logged.isEmpty())
        }

    @Test
    fun aStaleOfferIsRecordedMissedOnceUnderItsOwnId() =
        runTest {
            val (_, _, bob) = pair()
            val sentAt = testScheduler.currentTime - TIMINGS.staleOfferMs - 1
            bob.manager.onSignal(ALICE, CallPayload(id = "c1", ph = CallPayload.PHASE_OFFER, sdp = SDP), sentAt)
            bob.manager.onSignal(ALICE, CallPayload(id = "c1", ph = CallPayload.PHASE_CANCEL, rsn = CallPayload.REASON_TIMEOUT), 0L)
            runCurrent()
            val entry = bob.logged.single()
            assertEquals("c1", entry.record.id)
            assertEquals(ALICE, entry.peerId)
            assertEquals(sentAt, entry.startedAt)
            assertEquals(CallOutcome.Missed, entry.record.outcome)
            // A hand-withdrawn call whose CANCEL beat its offer is no record at all (C-3.4-2).
            val now = testScheduler.currentTime
            bob.manager.onSignal(ALICE, CallPayload(id = "c2", ph = CallPayload.PHASE_CANCEL, rsn = CallPayload.REASON_USER), now)
            bob.manager.onSignal(ALICE, CallPayload(id = "c2", ph = CallPayload.PHASE_OFFER, sdp = SDP), now)
            runCurrent()
            assertEquals(1, bob.logged.size)
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

    /** Alice calls Bob, Bob answers and both media connect. */
    private suspend fun TestScope.connected(): Triple<Net, Side, Side> =
        connecting().also { (_, alice, bob) ->
            alice.media.last.events(MediaEvent.Connected)
            bob.media.last.events(MediaEvent.Connected)
            runCurrent()
        }

    /** The ICE restarts (§3.8) [this] side sent: OFFERs with a generation. */
    private fun Side.restarts(): List<CallPayload> = synchronized(sent) { sent.filter { it.ph == CallPayload.PHASE_OFFER && it.gen > 0 } }

    @Test
    fun aLostPathIsWonBackByTheCallersRestartUnderTheSameCall() =
        runTest {
            val (_, alice, bob) = connected()
            val connectedAt = alice.state!!.connectedAt
            val credential = TurnServer(urls = listOf("turn:turn.example:3478"), user = "9999999999:a", pass = "p")
            alice.turn = { listOf(credential) }

            // The callee notices first: it reconnects too, but never restarts.
            bob.media.last.events(MediaEvent.Disconnected)
            alice.media.last.events(MediaEvent.Disconnected)
            runCurrent()
            assertEquals(CallPhase.Reconnecting, alice.state!!.phase)
            assertEquals(CallPhase.Reconnecting, bob.state!!.phase)
            advanceTimeBy(TIMINGS.restartDelayMs - 1)
            runCurrent()
            assertTrue(alice.restarts().isEmpty())

            advanceTimeBy(2)
            runCurrent()
            val restart = alice.restarts().single()
            assertEquals(1, restart.gen)
            assertEquals(alice.state!!.id, restart.id)
            assertTrue(bob.restarts().isEmpty())
            // Fresh ICE servers for the restart: the TURN credential is fetched again.
            assertEquals(2, alice.turnAsks.size)
            assertEquals(
                listOf(credential),
                alice.media.last.restarts
                    .single()
                    .filter { it.user.isNotEmpty() },
            )
            // Bob answers it on the call's own session, without ringing; Alice applies the answer.
            assertEquals(listOf(SDP), bob.media.last.reanswered)
            assertEquals(1, bob.lastOf(CallPayload.PHASE_ANSWER).gen)
            assertEquals(2, alice.media.last.accepts)
            assertEquals(CallPhase.Reconnecting, bob.state!!.phase)
            assertTrue(bob.missed.isEmpty())

            alice.media.last.events(MediaEvent.Connected)
            bob.media.last.events(MediaEvent.Connected)
            runCurrent()
            assertEquals(CallPhase.Connected, alice.state!!.phase)
            assertEquals(CallPhase.Connected, bob.state!!.phase)
            assertEquals(connectedAt, alice.state!!.connectedAt)
            advanceTimeBy(TIMINGS.reconnectWindowMs * 2)
            runCurrent()
            assertEquals(CallPhase.Connected, alice.state!!.phase)
            assertEquals(1, alice.restarts().size)
            assertEquals(1, alice.media.sessions.size)
            assertEquals(1, bob.media.sessions.size)
        }

    @Test
    fun aBlipIceHealsOnItsOwnCostsNoFrame() =
        runTest {
            val (_, alice, _) = connected()
            alice.media.last.events(MediaEvent.Disconnected)
            runCurrent()
            advanceTimeBy(TIMINGS.restartDelayMs / 2)
            alice.media.last.events(MediaEvent.Connected)
            runCurrent()
            advanceTimeBy(TIMINGS.reconnectWindowMs * 2)
            runCurrent()
            assertEquals(CallPhase.Connected, alice.state!!.phase)
            assertTrue(alice.restarts().isEmpty())
        }

    @Test
    fun aPathThatDoesNotComeBackEndsDroppedAfterTwoRestarts() =
        runTest {
            val (net, alice, bob) = connected()
            net.drop = { _, p -> p.gen > 0 }
            // A Failed starts the window as a Disconnected does, and while reconnecting ends nothing.
            alice.media.last.events(MediaEvent.Failed)
            runCurrent()
            assertEquals(CallPhase.Reconnecting, alice.state!!.phase)
            advanceTimeBy(TIMINGS.restartDelayMs + 1)
            alice.media.last.events(MediaEvent.Failed)
            runCurrent()
            assertEquals(listOf(1), alice.restarts().map { it.gen })
            advanceTimeBy(TIMINGS.restartRetryMs)
            runCurrent()
            assertEquals(listOf(1, 2), alice.restarts().map { it.gen })
            assertEquals(CallPhase.Reconnecting, alice.state!!.phase)

            advanceTimeBy(TIMINGS.reconnectWindowMs - TIMINGS.restartDelayMs - TIMINGS.restartRetryMs - 2)
            runCurrent()
            assertEquals(CallPhase.Reconnecting, alice.state!!.phase)
            advanceTimeBy(2)
            runCurrent()
            assertEquals(CallPhase.Ended, alice.state!!.phase)
            assertEquals(CallPayload.REASON_NO_ROUTE, alice.state!!.reason)
            assertEquals(CallPayload.REASON_NO_ROUTE, bob.state!!.reason)
            assertEquals(2, alice.restarts().size)
            assertEquals(CallOutcome.Dropped, alice.onlyOutcome())
            assertEquals(CallOutcome.Dropped, bob.onlyOutcome())
        }

    @Test
    fun aCalleeAloneEndsAtItsWindowAndEachRestartItAnswersRenewsIt() =
        runTest {
            val (_, alice, bob) = connected()
            bob.media.last.events(MediaEvent.Disconnected)
            runCurrent()
            // Alice's media notices later; her restart reaches Bob inside his window and buys him a fresh one.
            advanceTimeBy(TIMINGS.reconnectWindowMs / 2)
            alice.media.last.events(MediaEvent.Disconnected)
            runCurrent()
            advanceTimeBy(TIMINGS.restartDelayMs + 1)
            runCurrent()
            assertEquals(1, bob.media.last.reanswered.size)
            advanceTimeBy(TIMINGS.reconnectWindowMs / 2)
            runCurrent()
            assertEquals(CallPhase.Reconnecting, bob.state!!.phase)

            // Nothing connects: Alice's own window ends the call, and her END ends Bob's.
            advanceTimeBy(TIMINGS.reconnectWindowMs)
            runCurrent()
            assertEquals(CallPhase.Ended, alice.state!!.phase)
            assertEquals(CallPhase.Ended, bob.state!!.phase)
            assertEquals(CallPayload.PHASE_END, alice.lastOf(CallPayload.PHASE_END).ph)
        }

    @Test
    fun aCalleeWhoseCallerNeverRestartsEndsAtItsOwnWindow() =
        runTest {
            val (_, alice, bob) = connected()
            bob.media.last.events(MediaEvent.Disconnected)
            runCurrent()
            advanceTimeBy(TIMINGS.reconnectWindowMs - 1)
            runCurrent()
            assertEquals(CallPhase.Reconnecting, bob.state!!.phase)
            advanceTimeBy(2)
            runCurrent()
            assertEquals(CallPayload.REASON_NO_ROUTE, bob.state!!.reason)
            assertEquals(CallPhase.Ended, alice.state!!.phase)
            assertTrue(bob.restarts().isEmpty())
        }

    @Test
    fun aRestartOfferThatDoesNotFitChangesNothingAndNeverRings() =
        runTest {
            val (_, alice, bob) = pair()
            val now = { testScheduler.currentTime }
            // No call at all: a restart of an unknown one is not an offer to ring for.
            bob.manager.onSignal(ALICE, CallPayload(id = "x", ph = CallPayload.PHASE_OFFER, gen = 1, sdp = SDP), now())
            runCurrent()
            assertNull(bob.state)
            assertTrue(bob.sent.isEmpty() && bob.missed.isEmpty())

            assertNull(alice.manager.start(BOB))
            runCurrent()
            val id = alice.state!!.id
            // Ringing, not yet connected: a restart has nothing to restart.
            bob.manager.onSignal(ALICE, CallPayload(id = id, ph = CallPayload.PHASE_OFFER, gen = 1, sdp = SDP), now())
            runCurrent()
            assertEquals(CallPhase.Alerting, bob.state!!.phase)
            assertTrue(bob.manager.answer(id))
            runCurrent()
            alice.media.last.events(MediaEvent.Connected)
            bob.media.last.events(MediaEvent.Connected)
            runCurrent()

            fun restart(gen: Int) = CallPayload(id = id, ph = CallPayload.PHASE_OFFER, gen = gen, sdp = SDP)
            // From someone else; stale by its signed sentAt; to the caller, who only ever restarts.
            bob.manager.onSignal(CAROL, restart(2), now())
            bob.manager.onSignal(ALICE, restart(3), now() - TIMINGS.reconnectWindowMs - 1)
            alice.manager.onSignal(BOB, restart(4), now())
            runCurrent()
            assertTrue(
                bob.media.last.reanswered
                    .isEmpty() &&
                    alice.media.last.reanswered
                        .isEmpty(),
            )

            // A fitting one is answered once, however often it arrives; an older one after it is not.
            bob.manager.onSignal(ALICE, restart(6), now())
            bob.manager.onSignal(ALICE, restart(6), now())
            bob.manager.onSignal(ALICE, restart(5), now())
            runCurrent()
            assertEquals(1, bob.media.last.reanswered.size)
            val answered = synchronized(bob.sent) { bob.sent.filter { it.ph == CallPayload.PHASE_ANSWER && it.gen > 0 } }
            assertEquals(listOf(6), answered.map { it.gen })
            // Still connected: the callee's own media saw no loss, so it has no window to run.
            assertEquals(CallPhase.Connected, bob.state!!.phase)
            assertTrue(bob.missed.isEmpty())
        }

    @Test
    fun anAnswerToASupersededRestartIsIgnored() =
        runTest {
            val (net, alice, bob) = connected()
            var held: CallPayload? = null
            net.drop = { from, p ->
                (from == BOB && p.ph == CallPayload.PHASE_ANSWER && p.gen == 1).also { if (it) held = p }
            }
            alice.media.last.events(MediaEvent.Disconnected)
            runCurrent()
            advanceTimeBy(TIMINGS.restartDelayMs + TIMINGS.restartRetryMs + 1)
            runCurrent()
            assertEquals(listOf(1, 2), alice.restarts().map { it.gen })
            // The gen-2 answer was applied; the gen-1 one, arriving after it, is not.
            assertEquals(2, alice.media.last.accepts)
            alice.manager.onSignal(BOB, checkNotNull(held), testScheduler.currentTime)
            runCurrent()
            assertEquals(2, alice.media.last.accepts)
        }

    @Test
    fun aRelayOnlyRestartCarriesRelayCandidatesAlone() =
        runTest {
            val (_, alice, bob) = pair()
            alice.relay = true
            assertNull(alice.manager.start(BOB))
            runCurrent()
            assertTrue(bob.manager.answer(bob.state!!.id))
            runCurrent()
            alice.media.last.events(MediaEvent.Connected)
            runCurrent()
            alice.media.last.events(MediaEvent.Disconnected)
            runCurrent()
            advanceTimeBy(TIMINGS.restartDelayMs + 1)
            runCurrent()
            val sdp = checkNotNull(alice.restarts().single().sdp)
            assertFalse("typ host" in sdp)
            assertTrue("typ relay" in sdp)
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
            alice.relay = true
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
    fun aCallDialledWhileTheSpoolIsAwayReusesALiveCredentialThenFallsBackToStun() =
        runTest {
            val (_, alice, _) = pair()
            // exp is Unix seconds; virtual time starts at 0, so this one is good for 300 s.
            val server =
                TurnServer(
                    listOf("turn:lab.example:3478?transport=udp", "turns:lab.example:5349?transport=tcp"),
                    "300:abc",
                    "secret",
                )
            alice.turn = { listOf(server) }
            alice.manager.start(BOB)
            runCurrent()
            alice.manager.hangUp()
            runCurrent()

            // The spool is unreachable (a network change): the credential still has minutes to run, so it is reused.
            alice.turn = { emptyList() }
            alice.manager.start(BOB)
            runCurrent()
            assertEquals(listOf(server), alice.media.last.servers)
            alice.manager.hangUp()
            runCurrent()

            // Past its expiry (less the margin) only the servers' STUN half is left, with no credential.
            advanceTimeBy(250_000)
            alice.manager.start(BOB)
            runCurrent()
            val stun =
                alice.media.last.servers
                    .single()
            assertEquals(listOf("stun:lab.example:3478"), stun.urls)
            assertEquals("", stun.user)
            assertEquals("", stun.pass)
        }

    @Test
    fun spoolStunRidesEveryCallBesideTurnAndWithoutIt() =
        runTest {
            val (_, alice, bob) = pair()
            val server = TurnServer(listOf("turn:pay.example:3478?transport=udp"), "1800000000:abc", "secret")
            alice.turn = { listOf(server) }
            alice.stun = listOf("stun:lab.example:3478")
            // The free side: no spool issues it TURN, and its hellos still hand it a STUN server (C-7.6-20). Read when
            // the offer rings, which is when the callee prefetches its servers.
            bob.stun = listOf("stun:lab.example:3478")
            alice.manager.start(BOB)
            runCurrent()
            val withTurn = alice.media.last.servers
            assertEquals(listOf(server), withTurn.take(1))
            assertEquals(listOf("stun:lab.example:3478"), withTurn.drop(1).single().urls)
            assertEquals("", withTurn.last().user)
            assertEquals("", withTurn.last().pass)

            assertTrue(bob.manager.answer(bob.state!!.id))
            runCurrent()
            val free =
                bob.media.last.servers
                    .single()
            assertEquals(listOf("stun:lab.example:3478"), free.urls)
            assertEquals("", free.user)
        }

    @Test
    fun spoolStunJoinsTheCachedFallbackWithoutRepeatsAndCapped() =
        runTest {
            val (_, alice, _) = pair()
            val server = TurnServer(listOf("turn:lab.example:3478?transport=udp"), "300:abc", "secret")
            alice.turn = { listOf(server) }
            alice.manager.start(BOB)
            runCurrent()
            alice.manager.hangUp()
            runCurrent()

            // Past the credential's expiry: its STUN half, then what the hellos say — the same server once.
            alice.turn = { emptyList() }
            alice.stun =
                listOf(
                    "stun:lab.example:3478",
                    "stun:other.example:3478",
                    "turn:other.example:3478",
                    "stun:other.example:3478?transport=udp",
                    "stun:third.example",
                    "stuns:fourth.example:5349",
                    "stun:fifth.example",
                )
            advanceTimeBy(250_000)
            alice.manager.start(BOB)
            runCurrent()
            val stun =
                alice.media.last.servers
                    .single()
            assertEquals(
                listOf("stun:lab.example:3478", "stun:other.example:3478", "stun:third.example", "stuns:fourth.example:5349"),
                stun.urls,
            )
            assertEquals(StunServers.MAX, stun.urls.size)
        }

    @Test
    fun theStunRuleTakesOnlyWhatLibwebrtcParses() {
        for (good in listOf("stun:lab.example", "stun:lab.example:3478", "stuns:lab.example:5349", "stun:10.0.2.2:1", "stun:[::1]:3478")) {
            assertTrue(good, StunServers.accepts(good))
        }
        for (bad in listOf(
            "turn:lab.example:3478",
            "stun:lab.example:3478?transport=udp",
            "stun:user@lab.example",
            "stun:lab.example:0",
            "stun:lab.example:65536",
            "stun://lab.example",
            "stun:lab example",
            "stun:lab.example,stun:x",
            "STUN:lab.example",
            "stun:",
        )) {
            assertFalse(bad, StunServers.accepts(bad))
        }
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

    // ---- §3.10: the Wi-Fi Direct leg of a call between neighbours ----

    private fun Side.offer(): CallPayload = synchronized(sent) { sent.first { it.ph == CallPayload.PHASE_OFFER } }

    private fun Side.lastOf(ph: Int): CallPayload = synchronized(sent) { sent.last { it.ph == ph } }

    @Test
    fun aNeighbourCallOffersItsGroupAndTheCallerHostsOnlyOnceTheCalleeJoins() =
        runTest {
            val (_, alice, bob) = neighbours()
            alice.manager.start(BOB)
            runCurrent()
            val id = alice.state!!.id
            assertEquals(INVITE, alice.offer().dw)
            assertTrue(alice.media.last.direct)
            // Bob joins while he rings and says so; only then does Alice raise the group.
            assertEquals(listOf(id), bob.direct.joins)
            assertEquals(CallPayload.DIRECT_JOINING, bob.lastOf(CallPayload.PHASE_RINGING).dx)
            assertEquals(listOf(id), alice.direct.hosts)

            assertTrue(bob.manager.answer(id))
            runCurrent()
            assertTrue(bob.media.last.direct)
            assertEquals(CallPayload.DIRECT_JOINING, bob.lastOf(CallPayload.PHASE_ANSWER).dx)
            // One attempt per call on each side, whichever frame said it.
            assertEquals(listOf(id), alice.direct.hosts)
            assertEquals(listOf(id), bob.direct.joins)
        }

    @Test
    fun aCalleeThatCannotJoinNeverMakesTheCallerHost() =
        runTest {
            val (_, alice, bob) = neighbours()
            bob.direct.joinable = false
            alice.manager.start(BOB)
            runCurrent()
            assertNull(bob.lastOf(CallPayload.PHASE_RINGING).dx)
            bob.manager.answer(bob.state!!.id)
            runCurrent()
            assertNull(bob.lastOf(CallPayload.PHASE_ANSWER).dx)
            assertTrue(alice.direct.hosts.isEmpty() && bob.direct.joins.isEmpty())
            assertEquals(CallPhase.Connecting, alice.state!!.phase)
        }

    @Test
    fun relayOnlyOnEitherSideKeepsTheCallOffTheGroup() =
        runTest {
            val (_, alice, bob) = neighbours()
            alice.relay = true
            alice.manager.start(BOB)
            runCurrent()
            // A group pair would show the peer our real address: no invite at all.
            assertNull(alice.offer().dw)
            assertFalse(alice.media.last.direct)
            assertTrue(bob.direct.joins.isEmpty())
            alice.manager.hangUp()
            runCurrent()

            alice.relay = false
            bob.relay = true
            alice.sent.clear()
            alice.manager.start(BOB)
            runCurrent()
            assertEquals(INVITE, alice.offer().dw)
            assertNull(bob.lastOf(CallPayload.PHASE_RINGING).dx)
            assertTrue(bob.direct.joins.isEmpty() && alice.direct.hosts.isEmpty())
        }

    @Test
    fun aStaleJoiningIsNotHostedButTheAnswersIs() =
        runTest {
            val (net, alice, bob) = neighbours()
            net.drop = { _, p -> p.ph == CallPayload.PHASE_RINGING }
            alice.manager.start(BOB)
            runCurrent()
            val id = alice.state!!.id
            // Custody hands Alice the RINGING the radio hand-over lost, long after Bob's join gave up.
            alice.manager.onSignal(
                BOB,
                CallPayload(id = id, ph = CallPayload.PHASE_RINGING, dx = CallPayload.DIRECT_JOINING),
                testScheduler.currentTime - TIMINGS.directCommitMaxAgeMs - 1,
            )
            runCurrent()
            assertTrue(alice.direct.hosts.isEmpty())
            bob.manager.answer(id)
            runCurrent()
            assertEquals(listOf(id), alice.direct.hosts)
        }

    @Test
    fun aCalleeThatMayNotJoinWhileRingingJoinsAtTheAnswer() =
        runTest {
            val (_, alice, bob) = neighbours()
            bob.manager.directMode = DirectMode.ANSWER
            alice.manager.start(BOB)
            runCurrent()
            assertNull(bob.lastOf(CallPayload.PHASE_RINGING).dx)
            assertTrue(bob.direct.joins.isEmpty() && alice.direct.hosts.isEmpty())
            val first = bob.state!!.id
            bob.manager.answer(first)
            runCurrent()
            assertEquals(listOf(first), bob.direct.joins)
            assertEquals(CallPayload.DIRECT_JOINING, bob.lastOf(CallPayload.PHASE_ANSWER).dx)
            assertEquals(listOf(first), alice.direct.hosts)
            alice.manager.hangUp()
            runCurrent()

            // API 29-32 off screen: the same, under the default mode.
            bob.manager.directMode = DirectMode.PREWARM
            bob.direct.prewarmAllowed = false
            alice.manager.start(BOB)
            runCurrent()
            val second = bob.state!!.id
            assertEquals(listOf(first), bob.direct.joins)
            bob.manager.answer(second)
            runCurrent()
            assertEquals(listOf(first, second), bob.direct.joins)
            assertEquals(listOf(first, second), alice.direct.hosts)
        }

    @Test
    fun everyEndEndsTheDirectLegOnceWithTheFrameThatTellsThePeer() =
        runTest {
            val (_, alice, bob) = neighbours()
            alice.manager.start(BOB)
            runCurrent()
            val id = alice.state!!.id
            bob.manager.decline(id)
            runCurrent()
            val bobs = bob.direct.ends.single()
            assertEquals(id, bobs.first)
            assertEquals(CallPayload.PHASE_DECLINE, bobs.second?.ph)
            // Alice was told; she tells nobody, and her leg ends all the same.
            assertEquals(id to null, alice.direct.ends.single())

            alice.manager.start(BOB)
            runCurrent()
            val next = alice.state!!.id
            bob.manager.answer(next)
            runCurrent()
            alice.manager.hangUp()
            runCurrent()
            assertEquals(
                CallPayload.PHASE_END,
                alice.direct.ends
                    .single { it.first == next }
                    .second
                    ?.ph,
            )
            assertEquals(1, bob.direct.ends.count { it.first == next })
        }

    @Test
    fun aFailureWhileTheGroupComesUpWaitsForItUnderTheLongerConnectTimer() =
        runTest {
            val (_, alice, bob) = neighbours()
            alice.manager.start(BOB)
            runCurrent()
            bob.manager.answer(bob.state!!.id)
            runCurrent()
            // No other route: ICE gives up before the group is there.
            alice.media.last.events(MediaEvent.Failed)
            bob.media.last.events(MediaEvent.Failed)
            runCurrent()
            assertEquals(CallPhase.Connecting, alice.state!!.phase)
            assertEquals(CallPhase.Connecting, bob.state!!.phase)
            advanceTimeBy(TIMINGS.connectMs + 1)
            runCurrent()
            assertEquals(CallPhase.Connecting, alice.state!!.phase)
            advanceTimeBy(TIMINGS.directConnectMs - TIMINGS.connectMs)
            runCurrent()
            assertEquals(CallPayload.REASON_NO_ROUTE, alice.state!!.reason)
            assertEquals(CallPayload.REASON_NO_ROUTE, bob.state!!.reason)
        }

    @Test
    fun signalsRideTheLaneWhenTheMeshLosesThem() =
        runTest {
            val (net, alice, bob) = neighbours()
            alice.manager.start(BOB)
            runCurrent()
            val id = alice.state!!.id
            // The group took the only radio the mesh had between them: from here, only the lane carries.
            net.drop = { _, _ -> true }
            alice.direct.laneUp = true
            bob.direct.laneUp = true
            bob.manager.answer(id)
            runCurrent()
            assertEquals(CallPhase.Connecting, alice.state!!.phase)
            assertEquals(SDP, alice.media.last.accepted)
            alice.media.last.events(MediaEvent.Connected)
            bob.media.last.events(MediaEvent.Connected)
            runCurrent()
            alice.manager.hangUp()
            runCurrent()
            assertEquals(CallPhase.Ended, bob.state!!.phase)
            assertEquals(CallPayload.REASON_USER, bob.state!!.reason)
        }

    @Test
    fun aSignalByBothPathsCountsOnce() =
        runTest {
            val (_, alice, bob) = neighbours()
            alice.direct.laneUp = true
            bob.direct.laneUp = true
            alice.manager.start(BOB)
            runCurrent()
            bob.manager.answer(bob.state!!.id)
            runCurrent()
            assertEquals(1, alice.media.last.accepts)
            assertEquals(CallPhase.Connecting, alice.state!!.phase)
        }

    @Test
    fun groupCandidatesRideTheLaneOnlyAndWaitForTheRemoteSdp() =
        runTest {
            val (_, alice, bob) = neighbours()
            alice.direct.laneUp = true
            bob.direct.laneUp = true
            alice.manager.start(BOB)
            runCurrent()
            alice.media.last.events(MediaEvent.DirectCandidates(listOf(GROUP_CAND)))
            runCurrent()
            assertEquals(listOf(GROUP_CAND), alice.direct.laneCands)
            assertFalse(CallPayload.PHASE_CANDS in alice.phases())
            // Bob only rings: his media opens at the answer, and the candidate waits for it.
            bob.manager.answer(bob.state!!.id)
            runCurrent()
            assertTrue(GROUP_CAND in bob.media.last.added)
        }

    @Test
    fun glareHostsOnlyTheSurvivingCall() =
        runTest {
            val (_, alice, bob) = neighbours()
            alice.manager.start(BOB)
            bob.manager.start(ALICE)
            runCurrent()
            val id = alice.state!!.id
            assertEquals(id, bob.state!!.id)
            // Bob answered Alice's offer at once and joins her group; his own offer raised nothing.
            assertEquals(listOf(id), bob.direct.joins)
            assertEquals(listOf(id), alice.direct.hosts)
            assertTrue(bob.direct.hosts.isEmpty() && alice.direct.joins.isEmpty())
            assertEquals(CallPayload.DIRECT_JOINING, bob.lastOf(CallPayload.PHASE_ANSWER).dx)
        }

    @Test
    fun aNeighbourCallDialsWithNoTurnAndNoStunAtAll() =
        runTest {
            val (_, alice, bob) = neighbours()
            alice.manager.start(BOB)
            runCurrent()
            assertTrue(
                alice.media.last.servers
                    .isEmpty(),
            )
            assertEquals(INVITE, alice.offer().dw)
            bob.manager.answer(bob.state!!.id)
            runCurrent()
            assertTrue(
                bob.media.last.servers
                    .isEmpty(),
            )
            alice.media.last.events(MediaEvent.Connected)
            bob.media.last.events(MediaEvent.Connected)
            runCurrent()
            assertEquals(CallPhase.Connected, alice.state!!.phase)
            assertEquals(CallPhase.Connected, bob.state!!.phase)
        }

    // ---- §3.11: the walkie-talkie ----

    /**
     * Alice and Bob both run the walkie; [live] is whether a live call looks possible from Alice's side. The drop from a
     * live call is on here (its machinery stays tested while the shipped default parks it).
     */
    private fun TestScope.walkies(
        live: Boolean,
        dropToWalkie: Boolean = true,
    ): Triple<Net, Side, Side> =
        pair(dropToWalkie).also { (_, alice, bob) ->
            alice.walkie.enabled = true
            bob.walkie.enabled = true
            alice.walkie.live = live
        }

    /**
     * §2.7: a walkie OFFER or ANSWER says whether its sender holds a cast ticket, so a free peer knows a relay that sells
     * casts will carry its chunks too, and each side keeps what the other said. A phone with no ticket says nothing:
     * the flag is omitted, as every default is.
     */
    @Test
    fun aWalkieSignalCarriesTheSendersCastTicketAndTheOtherSideKeepsIt() =
        runTest {
            val (_, alice, bob) = walkies(live = false)
            alice.walkie.ticketed = true
            assertNull(alice.manager.start(BOB))
            runCurrent()
            assertTrue(alice.offer().ct)
            assertTrue(bob.state!!.peerCastTicketed)

            assertTrue(bob.manager.answer(bob.state!!.id))
            runCurrent()
            assertFalse("bob holds no ticket and says so by saying nothing", bob.lastOf(CallPayload.PHASE_ANSWER).ct)
            assertFalse(alice.state!!.peerCastTicketed)
            assertTrue(bob.state!!.peerCastTicketed)
        }

    /** The flag rides only beside the walkie bit: a live call with no drop to a walkie never carries it. */
    @Test
    fun aLiveCallNeverCarriesTheCastFlag() =
        runTest {
            val (_, alice, _) = walkies(live = true, dropToWalkie = false)
            alice.walkie.ticketed = true
            assertNull(alice.manager.start(BOB, CallKind.LIVE))
            runCurrent()
            assertEquals(CallPayload.MEDIA_AUDIO, alice.offer().m)
            assertFalse(alice.offer().ct)
        }

    @Test
    fun aCallWithNoLivePathIsAWalkieThatConnectsWithoutMedia() =
        runTest {
            val (_, alice, bob) = walkies(live = false)
            assertNull(alice.manager.start(BOB))
            runCurrent()
            val offer = alice.offer()
            assertEquals(CallPayload.MEDIA_WALKIE, offer.m)
            assertNull(offer.sdp)
            assertNull(offer.dw)
            assertEquals(CallPhase.Ringing, alice.state!!.phase)
            assertEquals(CallPhase.Alerting, bob.state!!.phase)
            assertTrue(bob.state!!.walkie)

            assertTrue(bob.manager.answer(bob.state!!.id))
            runCurrent()
            for (side in listOf(alice, bob)) {
                assertEquals(CallPhase.Connected, side.state!!.phase)
                assertTrue(side.state!!.walkie)
                assertFalse(side.state!!.degraded)
                assertTrue(side.state!!.connectedAt != null)
                // No media, no ICE servers, no group: a walkie session carries nothing live.
                assertTrue(side.media.sessions.isEmpty())
                assertTrue(side.turnAsks.isEmpty())
            }
            assertEquals(CallPayload.MEDIA_WALKIE, bob.lastOf(CallPayload.PHASE_ANSWER).m)

            // No connect timer and no grace: it stays up until somebody hangs up.
            advanceTimeBy(TIMINGS.directConnectMs + TIMINGS.reconnectWindowMs)
            runCurrent()
            assertEquals(CallPhase.Connected, alice.state!!.phase)
            bob.manager.hangUp()
            runCurrent()
            assertEquals(CallPhase.Ended, alice.state!!.phase)
            assertEquals(CallPayload.REASON_USER, alice.state!!.reason)
            assertEquals(listOf(CallPayload.PHASE_OFFER), alice.phases())
            assertEquals(listOf(CallPayload.PHASE_RINGING, CallPayload.PHASE_ANSWER, CallPayload.PHASE_END), bob.phases())
        }

    @Test
    fun aCallThatLooksLiveOffersTheDropAndAPeerWithoutTheWalkieIsNeverOfferedIt() =
        runTest {
            val (_, alice, bob) = walkies(live = true)
            alice.manager.start(BOB)
            runCurrent()
            assertEquals(CallPayload.MEDIA_AUDIO or CallPayload.MEDIA_WALKIE, alice.offer().m)
            assertFalse(bob.state!!.walkie)
            alice.manager.hangUp()
            runCurrent()

            // A peer whose profile carries no CAP_WALKIE gets a plain live call even with no live path in sight.
            alice.walkie.live = false
            alice.walkie.peerCapable = false
            alice.manager.start(BOB)
            runCurrent()
            assertEquals(CallPayload.MEDIA_AUDIO, alice.sent.last { it.ph == CallPayload.PHASE_OFFER }.m)
            assertFalse(alice.state!!.walkie)
        }

    @Test
    fun aWalkieOfferToABuildWithoutOneIsDeclinedForItsMedia() =
        runTest {
            val (_, alice, bob) = walkies(live = false)
            bob.walkie.enabled = false
            alice.manager.start(BOB, CallKind.WALKIE)
            runCurrent()
            assertNull(bob.state)
            assertEquals(CallPayload.REASON_MEDIA, bob.sent.single().rsn)
            assertEquals(CallPhase.Ended, alice.state!!.phase)
            assertEquals(CallPayload.REASON_MEDIA, alice.state!!.reason)
        }

    @Test
    fun aLiveCallThatNeverConnectsDropsToAWalkieOnBothSidesWithoutAFrame() =
        runTest {
            val (_, alice, bob) = walkies(live = true)
            alice.manager.start(BOB)
            runCurrent()
            bob.manager.answer(bob.state!!.id)
            runCurrent()
            assertEquals(CallPayload.MEDIA_AUDIO or CallPayload.MEDIA_WALKIE, bob.lastOf(CallPayload.PHASE_ANSWER).m)
            advanceTimeBy(TIMINGS.connectMs + 1)
            runCurrent()
            for (side in listOf(alice, bob)) {
                assertEquals(CallPhase.Connected, side.state!!.phase)
                assertTrue(side.state!!.walkie && side.state!!.degraded)
                assertTrue(side.media.last.closed)
            }
            // Each side ran its own timer: no END, nothing after the ANSWER.
            assertEquals(listOf(CallPayload.PHASE_OFFER), alice.phases())
            assertEquals(listOf(CallPayload.PHASE_RINGING, CallPayload.PHASE_ANSWER), bob.phases())
        }

    @Test
    fun aLostPathDropsToAWalkieAndTheOtherSideFollowsWhenItsMediaSeesItGo() =
        runTest {
            val (net, alice, bob) = walkies(live = true)
            alice.manager.start(BOB)
            runCurrent()
            bob.manager.answer(bob.state!!.id)
            runCurrent()
            alice.media.last.events(MediaEvent.Connected)
            bob.media.last.events(MediaEvent.Connected)
            runCurrent()
            val connectedAt = alice.state!!.connectedAt

            // The restarts go unanswered: the drop waits out the reconnect window (§3.8) first.
            net.drop = { _, p -> p.gen > 0 }
            alice.media.last.events(MediaEvent.Failed)
            runCurrent()
            assertEquals(CallPhase.Reconnecting, alice.state!!.phase)
            advanceTimeBy(TIMINGS.reconnectWindowMs + 1)
            runCurrent()
            assertTrue(alice.state!!.walkie && alice.state!!.degraded)
            assertEquals(connectedAt, alice.state!!.connectedAt)
            assertTrue(alice.media.last.closed)
            // A closing session's last word changes nothing.
            alice.media.last.events(MediaEvent.Connected)
            runCurrent()
            assertTrue(alice.state!!.walkie)

            // Bob's media sees the path go; past his own window he is in the walkie too.
            bob.media.last.events(MediaEvent.Disconnected)
            runCurrent()
            advanceTimeBy(TIMINGS.reconnectWindowMs + 1)
            runCurrent()
            assertTrue(bob.state!!.walkie && bob.state!!.degraded)
            assertEquals(CallPhase.Connected, bob.state!!.phase)
            assertFalse(CallPayload.PHASE_END in alice.phases() + bob.phases())
        }

    @Test
    fun withTheDropParkedALiveCallCarriesNoWalkieBitAndEndsNoRoute() =
        runTest {
            val (_, alice, bob) = walkies(live = true, dropToWalkie = false)
            alice.manager.start(BOB)
            runCurrent()
            assertEquals(CallPayload.MEDIA_AUDIO, alice.offer().m)
            bob.manager.answer(bob.state!!.id)
            runCurrent()
            assertEquals(CallPayload.MEDIA_AUDIO, bob.lastOf(CallPayload.PHASE_ANSWER).m)
            advanceTimeBy(TIMINGS.connectMs + 1)
            runCurrent()
            assertEquals(CallPayload.REASON_NO_ROUTE, alice.state!!.reason)
            assertEquals(CallPayload.REASON_NO_ROUTE, bob.state!!.reason)
            assertFalse(alice.state!!.walkie || bob.state!!.walkie)
        }

    @Test
    fun aCalleeWithTheDropParkedIgnoresTheBitAnOlderBuildOffers() =
        runTest {
            // Alice is an older build that still offers the drop; Bob's build parks it.
            val net = Net(backgroundScope) { testScheduler.currentTime }
            val alice = side(ALICE, net, dropToWalkie = true)
            val bob = side(BOB, net, dropToWalkie = false)
            alice.direct.peer = bob.direct
            bob.direct.peer = alice.direct
            for (side in listOf(alice, bob)) side.walkie.enabled = true
            alice.walkie.live = true
            alice.manager.start(BOB)
            runCurrent()
            assertEquals(CallPayload.MEDIA_AUDIO or CallPayload.MEDIA_WALKIE, alice.offer().m)
            bob.manager.answer(bob.state!!.id)
            runCurrent()
            assertEquals(CallPayload.MEDIA_AUDIO, bob.lastOf(CallPayload.PHASE_ANSWER).m)
            advanceTimeBy(TIMINGS.connectMs + 1)
            runCurrent()
            assertEquals(CallPayload.REASON_NO_ROUTE, alice.state!!.reason)
            assertEquals(CallPayload.REASON_NO_ROUTE, bob.state!!.reason)
            assertFalse(alice.state!!.walkie || bob.state!!.walkie)
        }

    @Test
    fun aCalleeThatCannotDropLeavesTheCallToEndAsBefore() =
        runTest {
            val (_, alice, bob) = walkies(live = true)
            bob.walkie.enabled = false
            alice.manager.start(BOB)
            runCurrent()
            bob.manager.answer(bob.state!!.id)
            runCurrent()
            // Bob's build ignores the bit, so his ANSWER does not echo it and neither side may drop.
            assertEquals(CallPayload.MEDIA_AUDIO, bob.lastOf(CallPayload.PHASE_ANSWER).m)
            advanceTimeBy(TIMINGS.connectMs + 1)
            runCurrent()
            assertEquals(CallPayload.REASON_NO_ROUTE, alice.state!!.reason)
            assertEquals(CallPayload.REASON_NO_ROUTE, bob.state!!.reason)
            assertFalse(alice.state!!.walkie || bob.state!!.walkie)
        }

    @Test
    fun walkieGlareLeavesTheLowerIdsSessionConnected() =
        runTest {
            val (_, alice, bob) = walkies(live = false)
            bob.walkie.live = false
            alice.manager.start(BOB)
            bob.manager.start(ALICE)
            runCurrent()
            assertEquals(alice.state!!.id, bob.state!!.id)
            for (side in listOf(alice, bob)) {
                assertEquals(CallPhase.Connected, side.state!!.phase)
                assertTrue(side.state!!.walkie)
            }
            assertEquals(CallPayload.PHASE_ANSWER, bob.sent.last().ph)
            assertEquals(CallPayload.MEDIA_WALKIE, bob.sent.last().m)
        }

    @Test
    fun aConnectedWalkiePassesOnEveryChunkOfItsPeersBurstAndNothingElse() =
        runTest {
            val (_, alice, bob) = walkies(live = false)
            val heard = Collections.synchronizedList(mutableListOf<TalkIn>())
            backgroundScope.launch { bob.manager.talk.collect { heard += it } }

            suspend fun chunk(
                q: Int,
                from: String = ALICE,
                tag: Int = TalkWire.tagOf(bob.state!!.id),
            ) = bob.manager.onTalk(from, TalkWire.encode(tag, TalkChunk(b = 0, q = q, a = byteArrayOf(q.toByte()))))
            alice.manager.start(BOB)
            runCurrent()
            // Alerting is not a session yet: nothing plays.
            chunk(0)
            runCurrent()
            assertTrue(heard.isEmpty())

            bob.manager.answer(bob.state!!.id)
            runCurrent()
            (1..3).forEach { chunk(it) }
            // Another call's tag, another peer, and bytes that are no chunk are not this session's.
            chunk(4, tag = TalkWire.tagOf(bob.state!!.id) xor 1)
            chunk(5, from = CAROL)
            bob.manager.onTalk(ALICE, byteArrayOf(1))
            runCurrent()
            assertEquals(listOf(1, 2, 3), heard.map { it.chunk.q })
            assertTrue(heard.all { it.callId == bob.state!!.id })
            // A chunk is not a signal: nothing answers it.
            assertEquals(listOf(CallPayload.PHASE_RINGING, CallPayload.PHASE_ANSWER), bob.phases())

            bob.manager.hangUp()
            runCurrent()
            chunk(6)
            runCurrent()
            assertEquals(3, heard.size)
        }

    private companion object {
        const val ALICE = "alice"
        const val BOB = "bob"
        const val CAROL = "carol"
        const val HOUR_MS = 60 * 60_000L
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

        val INVITE =
            DirectInvite(
                s = "DIRECT-kn-abcdefgh",
                p = "abcdefghijklmnopqrstuvwx",
                o = 45_000,
                k = Base64.getEncoder().encodeToString(ByteArray(DIRECT_SECRET_BYTES)),
            )

        const val GROUP_CAND = "candidate:9 1 udp 2122260223 192.168.49.1 50000 typ host"
    }
}
