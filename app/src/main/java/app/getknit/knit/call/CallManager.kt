package app.getknit.knit.call

import app.getknit.knit.mesh.protocol.CallPayload
import app.getknit.knit.mesh.protocol.FrameId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/** Where one call stands. [Calling]/[Ringing] are the caller's; [Alerting] the callee's; the rest both sides'. */
enum class CallPhase { Calling, Ringing, Alerting, Connecting, Connected, Ended }

/** The one call this phone is in (or last was), as the call surfaces draw it. */
data class CallState(
    val id: String,
    val peerId: String,
    val outgoing: Boolean,
    val phase: CallPhase,
    val startedAt: Long,
    val connectedAt: Long? = null,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    // Why it ended (a [CallPayload] REASON_*), once [phase] is [CallPhase.Ended].
    val reason: Int? = null,
)

/** Why [CallManager.start] dialled nothing. */
enum class CallRefusal { BUSY, SELF }

/** A call that rang out unanswered, or never rang because its offer arrived stale (C-3.4-2). */
class MissedCall(
    val peerId: String,
    val at: Long,
)

/** Every timer a call runs on (docs/VOICE_CALLS.md §3.3–3.4); injectable so tests run them in virtual time. */
data class CallTimings(
    // C-3.3-1: the caller withdraws with CANCEL timeout after this long without an ANSWER.
    val ringOutMs: Long = 45_000L,
    // How long the callee alerts on its own: the caller's CANCEL can be lost, so a ring never outlives this.
    val alertMs: Long = 50_000L,
    // C-3.3-3: Connecting that has not reached ICE connected by now ends with END no_route.
    val connectMs: Long = 20_000L,
    // How long a connected call rides out a Disconnected path before it ends (no ICE restart is built, §3.8).
    val disconnectGraceMs: Long = 10_000L,
    // C-3.4-1: an OFFER whose signed sentAt is further than this from now never alerts.
    val staleOfferMs: Long = 60_000L,
)

/**
 * The state machine of a 1:1 voice call (experimental; docs/VOICE_CALLS.md §3, ADR 2026-10.3tee). Pure — the mesh
 * ([CallSignals], [TurnSource]) and WebRTC ([CallMedia]) are seams, so it runs two-sided on the JVM in
 * `CallManagerTest`, like `transfer/TransferManager`.
 *
 * One call at a time. Signaling is a sealed `CTL_CALL` DM per phase, which can arrive late, twice or out of order, so
 * every phase is idempotent on `(id, ph, gen)`, an offer alerts only inside `sentAt ± 60 s`, and a terminal phase for
 * a call we never saw is remembered so its late offer never rings.
 *
 * **[onSignal] never waits on the network or the media.** It runs on the inbound path — a spool connection's only
 * reader, or the router's single collector — so it decides under [mutex], sends the small replies (RINGING, a busy
 * DECLINE) and returns; a TURN fetch and every media step run on [scope]. A `turncred` the spool sends back could
 * otherwise never be read by the reader that is waiting for it.
 */
class CallManager(
    private val signals: CallSignals,
    private val turn: TurnSource,
    private val media: CallMedia,
    private val scope: CoroutineScope,
    private val selfId: suspend () -> String,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val timings: CallTimings = CallTimings(),
    private val newId: () -> String = { FrameId.new() },
    private val log: (String) -> Unit = {},
) {
    private class Live(
        val id: String,
        val peerId: String,
        val outgoing: Boolean,
        val startedAt: Long,
        var phase: CallPhase,
        // The caller's SDP, on the callee's side.
        val remoteOffer: String? = null,
    ) {
        var session: CallSession? = null
        var relay = false
        var connectedAt: Long? = null
        var muted = false
        var speaker = false
        var reason: Int? = null

        // The one phase timer — ring-out, alert, connect or the disconnect grace; arming one replaces the last.
        var timer: Job? = null

        // The callee's TURN prefetch, started when the offer alerts so answering does not wait for it.
        var servers: Deferred<List<TurnServer>>? = null

        // Whether our OFFER/ANSWER has gone out; late candidates wait for it, so a CANDS never overtakes it.
        var described = false
        var lateCands: List<String>? = null
        var candsSent = false

        // Whether the peer's SDP is applied; a CANDS that beats it waits here (bounded).
        var remoteApplied = false
        val earlyCands = mutableListOf<String>()
    }

    private class Send(
        val peerId: String,
        val payload: CallPayload,
    )

    // A terminal phase seen for a call id (its own [CallPayload.ph] and reason).
    private class Tomb(
        val ph: Int,
        val rsn: Int?,
    )

    private val mutex = Mutex()
    private var live: Live? = null
    private val seen = bounded<String, Unit>()
    private val tombstones = bounded<String, Tomb>()

    private val _state = MutableStateFlow<CallState?>(null)

    /** The current (or last) call, or null before the first. */
    val state: StateFlow<CallState?> = _state.asStateFlow()

    private val missedCalls = Channel<MissedCall>(MISSED_BUFFER, BufferOverflow.DROP_OLDEST)

    /** Calls this phone missed, for the one collector that notifies them; buffered so none is lost before it subscribes. */
    val missed: Flow<MissedCall> = missedCalls.receiveAsFlow()

    /**
     * Relay every new call through TURN and send no host or server-reflexive candidate (C-3.7-3). A debug-bridge
     * toggle on this branch, not a setting: in-memory, read when a call opens its media.
     */
    @Volatile
    var relayOnly: Boolean = false

    // The last TURN credentials a spool issued, for a call dialled while the spool is unreachable (see [servers]).
    @Volatile
    private var lastServers: List<TurnServer> = emptyList()

    // ---- user actions ----

    /** Dials [peerId]. Null once the call is under way; the setup runs on [scope], never the caller's. */
    suspend fun start(peerId: String): CallRefusal? {
        if (peerId == selfId()) return CallRefusal.SELF
        val l =
            mutex.withLock {
                if (live?.phase?.let { it != CallPhase.Ended } == true) return CallRefusal.BUSY
                Live(id = newId(), peerId = peerId, outgoing = true, startedAt = clock(), phase = CallPhase.Calling).also {
                    live = it
                    armRingOut(it)
                    publish()
                }
            }
        log("call ${l.id} to ${l.peerId}")
        setup(l) { dial(l) }
        return null
    }

    /** Answers the call [id] is alerting for; false when it no longer is. */
    suspend fun answer(id: String): Boolean {
        val l =
            mutex.withLock {
                val l = live?.takeIf { it.id == id && !it.outgoing && it.phase == CallPhase.Alerting } ?: return false
                l.phase = CallPhase.Connecting
                armConnect(l)
                publish()
                l
            }
        setup(l) { answerCall(l) }
        return true
    }

    /** Refuses the call [id] is alerting for. */
    suspend fun decline(id: String) {
        val sends =
            mutex.withLock {
                val l = live?.takeIf { it.id == id && !it.outgoing && it.phase == CallPhase.Alerting } ?: return
                finish(l, CallPayload.REASON_USER, CallPayload.PHASE_DECLINE)
            }
        deliver(sends)
    }

    /** Ends the current call, whatever its phase: a CANCEL before an answer, a DECLINE while alerting, else END. */
    suspend fun hangUp() {
        val sends =
            mutex.withLock {
                val l = live?.takeIf { it.phase != CallPhase.Ended } ?: return
                finish(l, CallPayload.REASON_USER, endPhase(l))
            }
        deliver(sends)
    }

    suspend fun setMuted(muted: Boolean) =
        mutex.withLock {
            val l = live?.takeIf { it.phase != CallPhase.Ended } ?: return@withLock
            l.muted = muted
            l.session?.setMuted(muted)
            publish()
        }

    suspend fun setSpeaker(on: Boolean) =
        mutex.withLock {
            val l = live?.takeIf { it.phase != CallPhase.Ended } ?: return@withLock
            l.speaker = on
            l.session?.setSpeaker(on)
            publish()
        }

    /** The live call's selected candidate pair, `local/remote` (e.g. `relay/host`), for the debug bridge. */
    suspend fun route(): String? = mutex.withLock { live?.takeIf { it.phase != CallPhase.Ended }?.session }?.route()

    // ---- the mesh's side ----

    /** One `CTL_CALL` from [senderId], an accepted contact (the pipeline checks). Returns without waiting on anything slow. */
    suspend fun onSignal(
        senderId: String,
        payload: CallPayload,
        sentAt: Long,
    ) {
        val me = selfId()
        val sends =
            mutex.withLock {
                // C-3.3-4: custody and the spool both re-deliver; the second copy changes nothing.
                if (seen.put("${payload.id}/${payload.ph}/${payload.gen}", Unit) != null) return
                when (payload.ph) {
                    CallPayload.PHASE_OFFER -> onOffer(senderId, payload, sentAt, me)
                    CallPayload.PHASE_RINGING -> onRinging(senderId, payload)
                    CallPayload.PHASE_ANSWER -> onAnswer(senderId, payload)
                    CallPayload.PHASE_DECLINE, CallPayload.PHASE_CANCEL, CallPayload.PHASE_END -> onTerminal(senderId, payload)
                    CallPayload.PHASE_CANDS -> onCands(senderId, payload)
                    else -> emptyList()
                }
            }
        deliver(sends)
    }

    @Suppress("ReturnCount") // a ladder of refusals ahead of the one admit
    private fun onOffer(
        sender: String,
        p: CallPayload,
        sentAt: Long,
        me: String,
    ): List<Send> {
        if (p.gen != 0) return emptyList() // an ICE restart (§3.8) is not built: the call rides on or ends by its timers
        val now = clock()
        if (neverRings(sender, p, sentAt, now)) return emptyList()
        val sdp = p.sdp
        if (p.m and CallPayload.MEDIA_AUDIO == 0 || sdp == null || !SdpGuard.accepts(sdp)) {
            tombstones[p.id] = Tomb(CallPayload.PHASE_DECLINE, CallPayload.REASON_MEDIA)
            return listOf(Send(sender, CallPayload(id = p.id, ph = CallPayload.PHASE_DECLINE, rsn = CallPayload.REASON_MEDIA)))
        }
        live?.takeIf { it.phase != CallPhase.Ended }?.let { return onOfferWhileLive(it, sender, p, sdp, me, now) }
        val l = Live(p.id, sender, outgoing = false, startedAt = now, phase = CallPhase.Alerting, remoteOffer = sdp)
        live = l
        l.servers = scope.async { servers(sender) }
        armTimer(l, timings.alertMs) {
            missedCalls.trySend(MissedCall(it.peerId, it.startedAt))
            finish(it, CallPayload.REASON_TIMEOUT)
        }
        publish()
        log("call ${p.id} from $sender: alerting")
        return listOf(Send(sender, CallPayload(id = p.id, ph = CallPayload.PHASE_RINGING)))
    }

    /**
     * An offer that must not ring: one for a call already ended (C-3.4-3) or one outside `sentAt ± 60 s` (C-3.4-1).
     * It is a missed call unless the caller withdrew it by hand first (C-3.4-2). True when it was such an offer.
     */
    private fun neverRings(
        sender: String,
        p: CallPayload,
        sentAt: Long,
        now: Long,
    ): Boolean {
        val tomb = tombstones[p.id]
        if (tomb == null && abs(sentAt - now) <= timings.staleOfferMs) return false
        if (tomb == null || (tomb.ph == CallPayload.PHASE_CANCEL && tomb.rsn != CallPayload.REASON_USER)) {
            missedCalls.trySend(MissedCall(sender, minOf(sentAt, now)))
        }
        if (tomb == null) tombstones[p.id] = Tomb(CallPayload.PHASE_OFFER, null)
        log("call ${p.id} from $sender: ${if (tomb == null) "stale offer" else "offer after its end"}")
        return true
    }

    /** An offer while [current] is live: busy (C-3.3-2), or glare with the same peer (C-3.5-1). */
    private fun onOfferWhileLive(
        current: Live,
        sender: String,
        p: CallPayload,
        sdp: String,
        me: String,
        now: Long,
    ): List<Send> {
        val glare =
            current.outgoing && current.peerId == sender &&
                (current.phase == CallPhase.Calling || current.phase == CallPhase.Ringing)
        if (!glare) {
            tombstones[p.id] = Tomb(CallPayload.PHASE_DECLINE, CallPayload.REASON_BUSY)
            return listOf(Send(sender, CallPayload(id = p.id, ph = CallPayload.PHASE_DECLINE, rsn = CallPayload.REASON_BUSY)))
        }
        if (me < sender) {
            // Ours survives: the peer drops its own offer and answers ours.
            tombstones[p.id] = Tomb(CallPayload.PHASE_OFFER, null)
            log("call ${p.id} from $sender: glare, ours (${current.id}) survives")
            return emptyList()
        }
        // Theirs survives: drop ours without a word — a CANCEL could race their offer on a peer that sees them out
        // of order — and answer theirs at once, without alerting. Both users asked to talk.
        log("call ${p.id} from $sender: glare, answering theirs over ours (${current.id})")
        drop(current)
        val l = Live(p.id, sender, outgoing = false, startedAt = now, phase = CallPhase.Connecting, remoteOffer = sdp)
        live = l
        armConnect(l)
        publish()
        setup(l) { answerCall(l) }
        return emptyList()
    }

    private fun onRinging(
        sender: String,
        p: CallPayload,
    ): List<Send> {
        val l = live?.takeIf { it.id == p.id && it.peerId == sender && it.outgoing } ?: return emptyList()
        if (l.phase == CallPhase.Calling) {
            l.phase = CallPhase.Ringing
            publish()
        }
        return emptyList()
    }

    private fun onAnswer(
        sender: String,
        p: CallPayload,
    ): List<Send> {
        val l = live?.takeIf { it.id == p.id && it.peerId == sender && it.outgoing } ?: return emptyList()
        if (p.gen != 0 || (l.phase != CallPhase.Calling && l.phase != CallPhase.Ringing)) return emptyList()
        val sdp = p.sdp
        if (sdp == null || !SdpGuard.accepts(sdp)) return finish(l, CallPayload.REASON_MEDIA, CallPayload.PHASE_END)
        l.phase = CallPhase.Connecting
        armConnect(l)
        publish()
        log("call ${l.id}: answered")
        setup(l) { applyAnswer(l, sdp) }
        return emptyList()
    }

    private fun onTerminal(
        sender: String,
        p: CallPayload,
    ): List<Send> {
        val l = live
        if (l != null && l.id == p.id && l.peerId == sender) {
            if (l.phase == CallPhase.Ended) return emptyList()
            if (!l.outgoing && l.phase == CallPhase.Alerting && p.ph == CallPayload.PHASE_CANCEL) {
                missedCalls.trySend(MissedCall(l.peerId, l.startedAt))
            }
            return finish(l, p.rsn ?: CallPayload.REASON_USER)
        }
        // A call we never saw, or one already replaced: remembered, so its late offer never rings (C-3.4-3).
        if (p.id !in tombstones) tombstones[p.id] = Tomb(p.ph, p.rsn)
        return emptyList()
    }

    private fun onCands(
        sender: String,
        p: CallPayload,
    ): List<Send> {
        val l = live?.takeIf { it.id == p.id && it.peerId == sender && it.phase != CallPhase.Ended } ?: return emptyList()
        val lines = p.cand.orEmpty().filter { it.isNotBlank() }
        if (p.gen != 0 || lines.isEmpty()) return emptyList()
        val session = l.session
        if (session != null && l.remoteApplied) {
            session.addCandidates(lines)
        } else {
            l.earlyCands += lines.take((MAX_EARLY_CANDS - l.earlyCands.size).coerceAtLeast(0))
        }
        return emptyList()
    }

    // ---- setup, off the caller's and the inbound path ----

    /**
     * The TURN servers a call gathers from: fresh credentials when a spool issues them; else the last ones while they
     * can still start an allocation; else those servers as plain STUN, which needs no credential. A phone that just
     * changed networks dials before its spool has reconnected, and with no STUN either its offer carries only
     * addresses nothing outside its carrier can reach — and the peer's relay accepts traffic only from addresses the
     * offer named (device-observed 2026-10-07, a Pixel 9 seconds onto LTE). Reusing a live credential bends C-7.6-11's
     * fetch-per-call SHOULD only when fetching has just failed.
     */
    private suspend fun servers(peerId: String): List<TurnServer> {
        val fresh = turn.turnServers(peerId)
        if (fresh.isNotEmpty()) return fresh.also { lastServers = it }
        val nowS = clock() / MS_PER_S
        val cached = lastServers
        val live = cached.filter { (it.user.substringBefore(':').toLongOrNull() ?: 0L) - CREDENTIAL_MARGIN_S > nowS }
        if (live.isNotEmpty()) {
            log("call TURN: none issued, reusing ${live.size} live credential(s)")
            return live
        }
        val stun = cached.mapNotNull(::stunOnly)
        if (stun.isNotEmpty()) log("call TURN: none issued, STUN only from ${stun.size} known server(s)")
        return stun
    }

    /** [server]'s UDP URLs as `stun:` ones, without the credential — what a binding request needs. */
    private fun stunOnly(server: TurnServer): TurnServer? {
        val urls =
            server.urls
                .filter { it.startsWith("turn:") && !it.contains("transport=tcp") }
                .map { "stun:" + it.removePrefix("turn:").substringBefore('?') }
                .distinct()
        return if (urls.isEmpty()) null else TurnServer(urls = urls, user = "", pass = "")
    }

    /**
     * Runs one setup step of [l] on [scope]. A step that throws (the media layer, a TURN fetch) ends the call at once
     * and tells the peer, rather than leaving it to its phase timer.
     */
    private fun setup(
        l: Live,
        step: suspend () -> Unit,
    ) {
        scope.launch {
            try {
                step()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception, // a setup step never throws into the app scope
            ) {
                log("call ${l.id}: setup failed: ${e.javaClass.simpleName}")
                deliver(mutex.withLock { if (current(l)) finish(l, CallPayload.REASON_ERROR, endPhase(l)) else emptyList() })
            }
        }
    }

    private suspend fun dial(l: Live) {
        val servers = servers(l.peerId)
        val session = openFor(l, servers) ?: return
        val sdp = session.offer()?.let { if (l.relay) SdpGuard.stripToRelay(it) else it }
        val offer =
            mutex.withLock {
                if (!current(l)) return
                if (sdp == null || !SdpGuard.accepts(sdp)) {
                    // Nothing reached the peer yet, so nothing is owed it.
                    finish(l, CallPayload.REASON_MEDIA)
                    return
                }
                CallPayload(id = l.id, ph = CallPayload.PHASE_OFFER, sdp = sdp)
            }
        log("call ${l.id}: offering (${servers.size} TURN, relay=${l.relay})")
        if (!signals.sendCallSignal(l.peerId, offer)) {
            mutex.withLock { if (current(l)) finish(l, CallPayload.REASON_ERROR) }
            return
        }
        described(l)
    }

    private suspend fun answerCall(l: Live) {
        val servers = l.servers?.await() ?: servers(l.peerId)
        val session = openFor(l, servers) ?: return
        val sdp = session.answer(checkNotNull(l.remoteOffer))?.let { if (l.relay) SdpGuard.stripToRelay(it) else it }
        val sends =
            mutex.withLock {
                if (!current(l)) return
                if (sdp == null || !SdpGuard.accepts(sdp)) {
                    return@withLock finish(l, CallPayload.REASON_MEDIA, CallPayload.PHASE_DECLINE)
                }
                remoteApplied(l, session)
                listOf(Send(l.peerId, CallPayload(id = l.id, ph = CallPayload.PHASE_ANSWER, sdp = sdp)))
            }
        if (!deliver(sends)) {
            mutex.withLock { if (current(l)) finish(l, CallPayload.REASON_ERROR) }
            return
        }
        if (sends.any { it.payload.ph == CallPayload.PHASE_ANSWER }) described(l)
    }

    private suspend fun applyAnswer(
        l: Live,
        sdp: String,
    ) {
        val session = mutex.withLock { l.session?.takeIf { current(l) } } ?: return
        val accepted = session.accept(sdp)
        val sends =
            mutex.withLock {
                if (!current(l)) return
                if (!accepted) return@withLock finish(l, CallPayload.REASON_MEDIA, CallPayload.PHASE_END)
                remoteApplied(l, session)
                emptyList()
            }
        deliver(sends)
    }

    /** Opens [l]'s media, or closes it again at once when the call ended meanwhile. */
    private suspend fun openFor(
        l: Live,
        servers: List<TurnServer>,
    ): CallSession? {
        val relay = relayOnly
        val session = media.open(servers, relay) { event -> scope.launch { onMedia(l, event) } }
        val kept =
            mutex.withLock {
                if (!current(l)) return@withLock false
                l.session = session
                l.relay = relay
                session.setMuted(l.muted)
                session.setSpeaker(l.speaker)
                true
            }
        if (!kept) session.close()
        return session.takeIf { kept }
    }

    /** Our description is out: the late candidates that waited for it may follow now. */
    private suspend fun described(l: Live) {
        val sends =
            mutex.withLock {
                if (!current(l)) return
                l.described = true
                l.lateCands?.let { cands(l, it) }.orEmpty()
            }
        deliver(sends)
    }

    private suspend fun onMedia(
        l: Live,
        event: MediaEvent,
    ) {
        val sends =
            mutex.withLock {
                if (!current(l)) return
                when (event) {
                    MediaEvent.Connected -> {
                        if (l.phase == CallPhase.Connecting) {
                            l.phase = CallPhase.Connected
                            l.connectedAt = clock()
                            log("call ${l.id}: connected")
                        }
                        if (l.phase == CallPhase.Connected) {
                            l.timer?.cancel()
                            l.timer = null
                            publish()
                        }
                        emptyList()
                    }

                    MediaEvent.Disconnected -> {
                        if (l.phase == CallPhase.Connected) {
                            armTimer(l, timings.disconnectGraceMs) { finish(it, CallPayload.REASON_NO_ROUTE, CallPayload.PHASE_END) }
                        }
                        emptyList()
                    }

                    MediaEvent.Failed -> {
                        finish(l, CallPayload.REASON_NO_ROUTE, CallPayload.PHASE_END)
                    }

                    is MediaEvent.LateCandidates -> {
                        if (l.described) cands(l, event.lines) else emptyList<Send>().also { l.lateCands = event.lines }
                    }
                }
            }
        deliver(sends)
    }

    // ---- under [mutex] ----

    private fun current(l: Live): Boolean = live === l && l.phase != CallPhase.Ended

    /** How this side ends [l] toward the peer: a CANCEL before an answer, a DECLINE while alerting, else END. */
    private fun endPhase(l: Live): Int =
        when {
            l.outgoing && (l.phase == CallPhase.Calling || l.phase == CallPhase.Ringing) -> CallPayload.PHASE_CANCEL
            !l.outgoing && l.phase == CallPhase.Alerting -> CallPayload.PHASE_DECLINE
            else -> CallPayload.PHASE_END
        }

    /** The one CANDS this side sends for the call (C-3.6-2), relay-only when the call is. */
    private fun cands(
        l: Live,
        lines: List<String>,
    ): List<Send> {
        val out = (if (l.relay) SdpGuard.relayOnly(lines) else lines).filter { it.isNotBlank() }
        if (l.candsSent || out.isEmpty()) return emptyList()
        l.candsSent = true
        return listOf(Send(l.peerId, CallPayload(id = l.id, ph = CallPayload.PHASE_CANDS, cand = out)))
    }

    private fun remoteApplied(
        l: Live,
        session: CallSession,
    ) {
        l.remoteApplied = true
        if (l.earlyCands.isNotEmpty()) session.addCandidates(l.earlyCands.toList())
        l.earlyCands.clear()
    }

    private fun armRingOut(l: Live) =
        armTimer(l, timings.ringOutMs) {
            if (it.phase == CallPhase.Calling || it.phase == CallPhase.Ringing) {
                finish(it, CallPayload.REASON_TIMEOUT, CallPayload.PHASE_CANCEL)
            } else {
                emptyList()
            }
        }

    private fun armConnect(l: Live) =
        armTimer(l, timings.connectMs) {
            if (it.phase == CallPhase.Connecting) finish(it, CallPayload.REASON_NO_ROUTE, CallPayload.PHASE_END) else emptyList()
        }

    /** Replaces [l]'s phase timer. The fire clears the slot first, so [finish] never cancels the coroutine it runs on. */
    private fun armTimer(
        l: Live,
        ms: Long,
        onFire: (Live) -> List<Send>,
    ) {
        l.timer?.cancel()
        l.timer =
            scope.launch {
                delay(ms)
                val sends =
                    mutex.withLock {
                        if (!current(l)) return@launch
                        l.timer = null
                        onFire(l)
                    }
                deliver(sends)
            }
    }

    /**
     * Ends [l] here; returns the frame that tells the peer when [tellPhase] is given. Its id is remembered, so a late
     * copy of any of its frames changes nothing.
     */
    private fun finish(
        l: Live,
        reason: Int,
        tellPhase: Int? = null,
    ): List<Send> {
        drop(l)
        l.phase = CallPhase.Ended
        l.reason = reason
        publish()
        log("call ${l.id}: ended ($reason${tellPhase?.let { ", telling the peer with $it" }.orEmpty()})")
        return tellPhase?.let { listOf(Send(l.peerId, CallPayload(id = l.id, ph = it, rsn = reason))) }.orEmpty()
    }

    /** Releases everything [l] holds; the setup coroutines see it gone at their next step. */
    private fun drop(l: Live) {
        l.timer?.cancel()
        l.timer = null
        l.servers?.cancel()
        l.session?.close()
        l.session = null
        if (l.id !in tombstones) tombstones[l.id] = Tomb(CallPayload.PHASE_END, null)
    }

    private fun publish() {
        _state.value =
            live?.let {
                CallState(
                    id = it.id,
                    peerId = it.peerId,
                    outgoing = it.outgoing,
                    phase = it.phase,
                    startedAt = it.startedAt,
                    connectedAt = it.connectedAt,
                    muted = it.muted,
                    speaker = it.speaker,
                    reason = it.reason,
                )
            }
    }

    private suspend fun deliver(sends: List<Send>): Boolean {
        var all = true
        for (send in sends) {
            if (!signals.sendCallSignal(send.peerId, send.payload)) {
                log("call ${send.payload.id}: phase ${send.payload.ph} could not be sealed to ${send.peerId}")
                all = false
            }
        }
        return all
    }

    private companion object {
        // How many call ids (and id/phase/gen keys) are remembered: far more than a phone sees inside a custody TTL.
        const val MAX_REMEMBERED = 256

        // A CANDS can beat the peer's SDP being applied; this many lines wait for it.
        const val MAX_EARLY_CANDS = 64

        const val MISSED_BUFFER = 16

        const val MS_PER_S = 1_000L

        // A cached credential is reused only while it can still start an allocation with time to spare.
        const val CREDENTIAL_MARGIN_S = 60L

        fun <K, V> bounded(): MutableMap<K, V> =
            object : LinkedHashMap<K, V>() {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > MAX_REMEMBERED
            }
    }
}
