package app.getknit.knit.call

import app.getknit.knit.mesh.protocol.CallPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** How a call ended, as the system is told it (Telecom's `DisconnectCause` codes in the debug build). */
enum class SystemEnd { LOCAL, REMOTE, REJECTED, MISSED, BUSY, ERROR }

/** What a call is registered with the system as: who it is with, which way it was placed, and whether it is a walkie. */
class SystemCallRequest(
    val peerId: String,
    val outgoing: Boolean,
    val walkie: Boolean,
)

/**
 * One call the system holds for us (§3.13): its lifecycle, the microphone mute a car or a watch can set, and where its
 * audio goes. Every verb answers whether the system took it.
 */
interface SystemCall {
    val routing: Flow<CallRouting>

    val muted: Flow<Boolean>

    suspend fun answer(): Boolean

    suspend fun setActive(): Boolean

    suspend fun request(route: CallAudioRoute): Boolean

    suspend fun disconnect(end: SystemEnd)
}

/**
 * The platform's side of calls (§3.13): Telecom through core-telecom in the debug build (`debug/call/TelecomCalls`),
 * a fake under test. Registering a call hands the system its audio route — earpiece, loudspeaker, a wired or Bluetooth
 * headset — and lets a headset's button, a watch or a car answer and hang it up.
 */
interface SystemCalls {
    /** Registers this app as a placer of calls, once; false when the phone cannot take them. */
    suspend fun register(): Boolean

    /**
     * Registers the call [request] describes, or null when the system refuses it now (an emergency call, too many
     * calls). [onAnswer] and [onDisconnect] are the system's own asks — a headset, a watch, a car — and return quickly:
     * [onAnswer] says whether the call could still be answered.
     */
    suspend fun add(
        request: SystemCallRequest,
        onAnswer: suspend () -> Boolean,
        onDisconnect: suspend () -> Unit,
    ): SystemCall?
}

/** What [SystemCallSync] drives on [CallManager] when the system asks: answer, decline, hang up, mute. */
interface CallControl {
    val state: StateFlow<CallState?>

    suspend fun answer(id: String): Boolean

    suspend fun decline(
        id: String,
        reason: Int = CallPayload.REASON_USER,
    )

    suspend fun hangUp()

    suspend fun setMuted(muted: Boolean)
}

/**
 * Keeps the system's view of the one call in step with [CallManager]'s (docs/VOICE_CALLS.md §3.13), so the system owns
 * the call's audio route. Both kinds are registered, the walkie too: it differs only in starting on the loudspeaker
 * ([CallAudioPolicy.startRoute], asked for by [SystemCalls]) and in going back there when its route falls to the
 * earpiece unasked ([CallAudioPolicy.walkieCorrection]).
 *
 * It follows [CallManager.state] on [scope], as the call surfaces do, and is never on the inbound path:
 * - an outgoing call is added as it dials and set active when it connects;
 * - an incoming one is added as it alerts and answered when this phone answers it, unless the system's own answer
 *   (a headset, a watch, a car) is what answered it;
 * - a call that ends is disconnected with the cause [endOf] reads off it, unless the system ended it;
 * - glare (§3.5) replaces the call under a new id with the same peer, and the system's call carries over to it.
 *
 * A call the system refuses is refused here too: declined busy while it alerts, else hung up. The system is told of
 * no hold, so a phone call answered over ours ends it.
 */
class SystemCallSync(
    private val calls: CallControl,
    private val system: SystemCalls,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    // The system's call and what this sync knows of it. [id] moves with glare; the flags the system's callbacks set
    // are read by the state collector, so they are volatile.
    private class Held(
        @Volatile var id: String,
        val peerId: String,
        val outgoing: Boolean,
        var walkie: Boolean,
    ) {
        lateinit var call: SystemCall
        var watch: Job? = null
        var answered = false
        var active = false

        @Volatile var answeredBySystem = false

        @Volatile var endedBySystem = false

        // The route the user last picked; a walkie's correction leaves that one alone.
        @Volatile var askedFor: CallAudioRoute? = null

        @Volatile var latest: CallRouting? = null

        // The earpiece a correction was last asked away from, so a refused one is not asked again and again.
        @Volatile var corrected: String? = null
    }

    @Volatile
    private var held: Held? = null

    // The last call the system refused, so its later states are not offered to the system again.
    private var refused: String? = null

    private val _routing = MutableStateFlow<CallRouting?>(null)

    /** Where the current call's audio goes and where else it could; null with no call the system holds. */
    val routing: StateFlow<CallRouting?> = _routing.asStateFlow()

    fun start() {
        scope.launch {
            val ready = system.register()
            if (!ready) log("calls: the system would not register this app; every call is refused")
            calls.state.collect { step(it, ready) }
        }
    }

    /** Moves the current call's audio to [route] (the call screen, the notification); false when the system refused. */
    suspend fun request(route: CallAudioRoute): Boolean {
        val h = held ?: return false
        h.askedFor = route
        return h.call.request(route).also { log("call ${h.id}: audio to ${route.kind} (${route.name})${if (it) "" else " refused"}") }
    }

    /** [request] by the route's key, as a notification action carries it; false when that route is gone. */
    suspend fun request(key: String): Boolean =
        routing.value
            ?.available
            ?.firstOrNull { it.key == key }
            ?.let { request(it) } ?: false

    private suspend fun step(
        state: CallState?,
        ready: Boolean,
    ) {
        state ?: return
        val h = carried(state)
        if (state.phase == CallPhase.Ended) {
            // A call this phone placed was taken here, whatever id glare left it under.
            if (h != null) end(h, endOf(state, answered = h.outgoing || h.answered))
            return
        }
        follow(h ?: add(state, ready) ?: return, state)
    }

    // The system's call [state] is in: the same one, or the one glare's survivor with the same peer carries over
    // (§3.5). Any other the system still holds is disconnected first.
    private suspend fun carried(state: CallState): Held? {
        val h = held ?: return null
        if (h.id == state.id) return h
        if (h.peerId == state.peerId && state.phase != CallPhase.Ended) {
            log("call ${state.id}: carries the system's call of ${h.id} (glare)")
            h.id = state.id
            return h
        }
        end(h, SystemEnd.LOCAL)
        return null
    }

    // Tells the system what [state] has come to since: answered here, connected, or dropped to a walkie.
    private suspend fun follow(
        h: Held,
        state: CallState,
    ) {
        if (state.walkie && !h.walkie) {
            // A live call that dropped to a walkie (§3.11) is heard aloud from here on, whatever was picked before.
            h.walkie = true
            h.askedFor = null
            correct(h)
        }
        if (!h.outgoing && !h.answered && state.phase != CallPhase.Alerting) {
            h.answered = true
            if (!h.answeredBySystem && !h.call.answer()) log("call ${h.id}: the system did not take the answer")
        }
        if (h.outgoing && !h.active && state.phase == CallPhase.Connected) {
            h.active = true
            if (!h.call.setActive()) log("call ${h.id}: the system did not set it active")
        }
    }

    private suspend fun add(
        state: CallState,
        ready: Boolean,
    ): Held? {
        if (state.id == refused) return null
        val h = Held(state.id, state.peerId, state.outgoing, state.walkie)
        val call =
            if (ready) {
                system.add(
                    SystemCallRequest(state.peerId, state.outgoing, state.walkie),
                    onAnswer = { onSystemAnswer(h) },
                    onDisconnect = { onSystemDisconnect(h) },
                )
            } else {
                null
            }
        if (call == null) {
            refused = state.id
            log("call ${state.id}: refused by the system")
            if (!state.outgoing && state.phase == CallPhase.Alerting) calls.decline(state.id, CallPayload.REASON_BUSY) else calls.hangUp()
            return null
        }
        h.call = call
        held = h
        h.watch = scope.launch { watch(h) }
        val kind = (if (state.outgoing) "outgoing" else "incoming") + if (state.walkie) ", walkie" else ""
        log("call ${state.id}: added to the system ($kind)")
        return h
    }

    // The system's call as it moves: where its audio goes, and the mute a car or a watch sets — its changes only, the
    // first value being only where it starts.
    private suspend fun watch(h: Held) =
        coroutineScope {
            launch {
                h.call.muted
                    .drop(1)
                    .distinctUntilChanged()
                    .collect { calls.setMuted(it) }
            }
            h.call.routing.collect { r ->
                if (held !== h) return@collect
                h.latest = r
                if (r.current?.kind != CallAudioRoute.Kind.EARPIECE) h.corrected = null
                _routing.value = r
                correct(h)
            }
        }

    private suspend fun correct(h: Held) {
        if (!h.walkie) return
        val r = h.latest ?: return
        val target = CallAudioPolicy.walkieCorrection(r, h.askedFor) ?: return
        val from = r.current?.key
        if (h.corrected == from) return
        h.corrected = from
        log("call ${h.id}: walkie on the earpiece unasked; audio to ${target.name}")
        h.call.request(target)
    }

    private suspend fun end(
        h: Held,
        cause: SystemEnd,
    ) {
        if (held === h) {
            held = null
            _routing.value = null
        }
        h.watch?.cancel()
        if (h.endedBySystem) {
            log("call ${h.id}: the system ended it")
        } else {
            h.call.disconnect(cause)
            log("call ${h.id}: disconnected from the system ($cause)")
        }
    }

    // A headset's button, a watch or a car answered: the state collector must not answer the system a second time.
    private suspend fun onSystemAnswer(h: Held): Boolean {
        h.answeredBySystem = true
        val answered = calls.answer(h.id)
        if (!answered) h.answeredBySystem = false
        log("call ${h.id}: answered by the system${if (answered) "" else ", but it no longer rings"}")
        return answered
    }

    // The system hung up — a headset, a watch, a car, or a phone call answered over ours. Run off the system's callback,
    // which has to return quickly, and never told back to the system.
    private fun onSystemDisconnect(h: Held) {
        h.endedBySystem = true
        scope.launch {
            val s = calls.state.value?.takeIf { it.id == h.id && it.phase != CallPhase.Ended } ?: return@launch
            log("call ${h.id}: hung up by the system")
            if (!s.outgoing && s.phase == CallPhase.Alerting) calls.decline(s.id) else calls.hangUp()
        }
    }

    companion object {
        /**
         * The cause the system is told for [state], ended: missed and busy as such, a lost path or a media failure as an
         * error, the peer's own ending as remote, an incoming call this phone never [answered] as rejected (or missed,
         * when it rang out), else local.
         */
        fun endOf(
            state: CallState,
            answered: Boolean,
        ): SystemEnd =
            when {
                state.missed -> SystemEnd.MISSED
                state.reason == CallPayload.REASON_BUSY -> SystemEnd.BUSY
                state.reason in FAILURES -> SystemEnd.ERROR
                state.endedByPeer -> SystemEnd.REMOTE
                !state.outgoing && !answered -> if (state.reason == CallPayload.REASON_TIMEOUT) SystemEnd.MISSED else SystemEnd.REJECTED
                else -> SystemEnd.LOCAL
            }

        private val FAILURES = setOf(CallPayload.REASON_NO_ROUTE, CallPayload.REASON_MEDIA, CallPayload.REASON_ERROR)
    }
}
