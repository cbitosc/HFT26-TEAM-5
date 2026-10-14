package app.getknit.knit.ui.call

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import app.getknit.knit.call.CallAudioPolicy
import app.getknit.knit.call.CallAudioRoute
import app.getknit.knit.call.CallRouting
import app.getknit.knit.call.SystemCall
import app.getknit.knit.call.SystemCallRequest
import app.getknit.knit.call.SystemCalls
import app.getknit.knit.call.SystemEnd
import app.getknit.knit.data.PeerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [SystemCalls] over Jetpack core-telecom (experimental, `CALLS_WIRED` — the library is packaged only there, and this
 * file is its one importer): every call, live or walkie, is a self-managed Telecom call, so Telecom owns its audio
 * route — the earpiece, the loudspeaker, a wired or Bluetooth (HFP / LE Audio) headset, mode and focus — and a headset's
 * button, a watch or a car can answer and hang it up (docs/VOICE_CALLS.md §3.13). Nothing else may set the route while a
 * call is up: Telecom's own guide forbids `setCommunicationDevice` and `startBluetoothSco` beside it.
 *
 * `addCall` holds its coroutine for the call's whole life, so each call runs in a job of its own on [scope], and the
 * sync gets the call once Telecom has added it. Every verb is bounded: a session Telecom has already closed may never
 * answer one. [onAnswered] runs when a headset, a watch or a car answered, once the call layer took it, so the call's
 * microphone service can start where the call screen never opens.
 */
internal class TelecomCalls(
    private val context: Context,
    private val peers: () -> PeerRepository,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val onAnswered: () -> Unit = {},
) : SystemCalls {
    private val manager by lazy { CallsManager(context.applicationContext) }

    override suspend fun register(): Boolean =
        withContext(Dispatchers.IO) {
            // Calls ride the mesh, a Wi-Fi Direct group or the Internet: none wants a premium cellular slice.
            runCatching {
                manager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE or CallsManager.CAPABILITY_OPT_OUT_OF_PREMIUM_NETWORK)
            }.onFailure { log("telecom: not registered: ${it.javaClass.simpleName}: ${it.message}") }
                .isSuccess
        }

    override suspend fun add(
        request: SystemCallRequest,
        onAnswer: suspend () -> Boolean,
        onDisconnect: suspend () -> Unit,
    ): SystemCall? {
        // What a car, a watch or the headset's own voice prompt names the call by.
        val name = runCatching { callerName(peers(), request.peerId) }.getOrNull()?.takeIf { it.isNotBlank() } ?: UNNAMED
        val start = if (request.walkie) startingSpeaker() else null
        val attributes =
            CallAttributesCompat(
                displayName = name,
                address = Uri.fromParts(SCHEME, request.peerId, null),
                direction = if (request.outgoing) CallAttributesCompat.DIRECTION_OUTGOING else CallAttributesCompat.DIRECTION_INCOMING,
                callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                // No SUPPORTS_SET_INACTIVE: a phone call answered over ours ends it rather than holding it.
                callCapabilities = 0,
                preferredStartingCallEndpoint = start,
            )
        val added = CompletableDeferred<TelecomCall?>()
        val job =
            scope.launch {
                try {
                    manager.addCall(
                        attributes,
                        onAnswer = {
                            check(onAnswer()) { "the call no longer rings" }
                            // Inside Telecom's own callback: the window Android lets a backgrounded app take the mic in.
                            onAnswered()
                        },
                        onDisconnect = { onDisconnect() },
                        onSetActive = {},
                        onSetInactive = { error("a Knit call cannot be held") },
                    ) {
                        added.complete(TelecomCall(this))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    // A CallException when the platform refuses it now; whatever else the binder throws besides.
                    log("telecom: call with ${request.peerId} not added: ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    added.complete(null)
                }
            }
        val call = withTimeoutOrNull(ADD_TIMEOUT_MS) { added.await() }
        if (call == null) {
            job.cancel()
            return null
        }
        call.job = job
        log("telecom: call with ${request.peerId} added${start?.let { ", starting on ${it.name}" }.orEmpty()}")
        return call
    }

    // The loudspeaker a walkie starts on (§3.13), from the endpoints Telecom would offer a new call; none when a headset
    // would take it, or when Telecom does not say in time.
    private suspend fun startingSpeaker(): CallEndpointCompat? {
        val endpoints =
            withTimeoutOrNull(START_ENDPOINTS_MS) { manager.getAvailableStartingCallEndpoints().first() } ?: return null
        val target = CallAudioPolicy.startRoute(walkie = true, available = endpoints.map { it.toRoute() }) ?: return null
        return endpoints.firstOrNull { it.identifier.toString() == target.key }
    }

    private inner class TelecomCall(
        private val control: CallControlScope,
    ) : SystemCall {
        var job: Job? = null

        // The endpoints Telecom last offered, by key, so a route the sync names can be handed back as Telecom's own.
        @Volatile
        private var endpoints: Map<String, CallEndpointCompat> = emptyMap()

        override val routing: Flow<CallRouting> =
            combine(control.currentCallEndpoint, control.availableEndpoints) { current, available ->
                endpoints = available.associateBy { it.identifier.toString() }
                CallRouting(current.toRoute(), available.map { it.toRoute() })
            }

        override val muted: Flow<Boolean> = control.isMuted

        override suspend fun answer(): Boolean = verb("answer") { control.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL) }

        override suspend fun setActive(): Boolean = verb("setActive") { control.setActive() }

        override suspend fun request(route: CallAudioRoute): Boolean {
            val endpoint = endpoints[route.key] ?: return false
            return verb("route ${route.kind}") { control.requestEndpointChange(endpoint) }
        }

        override suspend fun disconnect(end: SystemEnd) {
            if (job?.isActive == false) return
            verb("disconnect $end") { control.disconnect(DisconnectCause(end.code())) }
            job?.cancel()
        }

        private suspend fun verb(
            what: String,
            call: suspend () -> CallControlResult,
        ): Boolean {
            val result =
                withTimeoutOrNull(VERB_TIMEOUT_MS) {
                    runCatching { call() }.onFailure { if (it is CancellationException) throw it }.getOrNull()
                }
            val ok = result is CallControlResult.Success
            if (!ok) log("telecom: $what ${if (result == null) "unanswered" else "refused: $result"}")
            return ok
        }
    }

    private fun SystemEnd.code(): Int =
        when (this) {
            SystemEnd.LOCAL -> DisconnectCause.LOCAL

            SystemEnd.REMOTE -> DisconnectCause.REMOTE

            SystemEnd.REJECTED -> DisconnectCause.REJECTED

            SystemEnd.MISSED -> DisconnectCause.MISSED

            SystemEnd.BUSY -> DisconnectCause.BUSY

            // Remapped to LOCAL by the library below SDK 38.
            SystemEnd.ERROR -> DisconnectCause.ERROR
        }

    private companion object {
        const val SCHEME = "knit"
        const val UNNAMED = "Knit call"

        // Telecom adds a call in well under a second; 1.1.0-beta01 bounded its own wait on Bluetooth at 2 s.
        const val ADD_TIMEOUT_MS = 5_000L
        const val START_ENDPOINTS_MS = 1_000L
        const val VERB_TIMEOUT_MS = 3_000L
    }
}

/** Telecom's endpoint as the call layer's route (§3.13); a type the call layer has no word for is [CallAudioRoute.Kind.OTHER]. */
internal fun CallEndpointCompat.toRoute(): CallAudioRoute =
    CallAudioRoute(
        kind =
            when (type) {
                CallEndpointCompat.TYPE_EARPIECE -> CallAudioRoute.Kind.EARPIECE
                CallEndpointCompat.TYPE_SPEAKER -> CallAudioRoute.Kind.SPEAKER
                CallEndpointCompat.TYPE_WIRED_HEADSET -> CallAudioRoute.Kind.WIRED
                CallEndpointCompat.TYPE_BLUETOOTH -> CallAudioRoute.Kind.BLUETOOTH
                else -> CallAudioRoute.Kind.OTHER
            },
        name = name.toString(),
        key = identifier.toString(),
    )
