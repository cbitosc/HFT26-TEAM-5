package app.getknit.knit.di

import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import app.getknit.knit.BuildConfig
import app.getknit.knit.call.CallControl
import app.getknit.knit.call.CallLog
import app.getknit.knit.call.CallManager
import app.getknit.knit.call.CallMedia
import app.getknit.knit.call.CallSignals
import app.getknit.knit.call.CallTimings
import app.getknit.knit.call.CallWalkie
import app.getknit.knit.call.DirectCallPath
import app.getknit.knit.call.SystemCallSync
import app.getknit.knit.call.SystemCalls
import app.getknit.knit.call.TalkLink
import app.getknit.knit.call.TurnSource
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.debug.call.CallBarHost
import app.getknit.knit.debug.call.CallController
import app.getknit.knit.debug.call.CallFaces
import app.getknit.knit.debug.call.CallService
import app.getknit.knit.debug.call.TalkLinks
import app.getknit.knit.debug.call.TalkPath
import app.getknit.knit.debug.call.TalkPlayer
import app.getknit.knit.debug.call.TalkRecorder
import app.getknit.knit.debug.call.TelecomCalls
import app.getknit.knit.debug.call.WalkieTalk
import app.getknit.knit.debug.call.WebRtcCallMedia
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.BulkRoute
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.power.AppVisibility
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.net.InternetGate
import app.getknit.knit.transfer.DirectWifiLease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.dsl.module

/**
 * Debug-variant voice-call wiring (experimental, docs/VOICE_CALLS.md): the WebRTC media is `debugImplementation`, so
 * only this variant has calls at all. `src/release` returns nothing and advertises no `CAP_CALL`.
 */
const val CALLS_WIRED: Boolean = true

/**
 * The walkie-talkie (docs/VOICE_CALLS.md §3.11, ADR 2026-10.79yb): a call with no live path is hold-to-talk bursts
 * streamed over the mesh. The one read of `BuildConfig.WALKIE` — off, [CallManager] gets [CallWalkie.None], no [WalkieTalk] is
 * built and the profile carries no `CAP_WALKIE`, so every call is a live one as before.
 */
const val WALKIE_WIRED: Boolean = BuildConfig.WALKIE

/**
 * The call graph. [CallManager]'s seams are late-bound over [MeshManager], so building it builds nothing else — the
 * mesh graph is the service's to build, off the main thread (ADR 2026-09.vztn). The direct leg's radio lease is
 * late-bound the same way: it wraps the Wi-Fi Direct radio, which holds the mesh transport.
 */
fun callModule(): Module? =
    module {
        // §3.13: every call, live or walkie, is a self-managed Telecom call, which owns its audio route. The peers are
        // late-bound, as the faces' are; the sync follows the manager's call from process start (startCallsIfWired).
        single {
            val context = androidContext()
            TelecomCalls(
                context,
                peers = { get() },
                scope = get<CoroutineScope>(),
                log = { Log.i(TAG, it) },
                onAnswered = { CallService.startAnswered(context) },
            )
        }
        single<SystemCalls> { get<TelecomCalls>() }
        single<CallControl> { get<CallManager>() }
        single { SystemCallSync(calls = get(), system = get(), scope = get<CoroutineScope>(), log = { Log.i(TAG, it) }) }
        // §3.10 (ADR 2026-10.kxnw): a call between neighbours rides a Wi-Fi Direct group, on the radio the transfer
        // shares through one lease. "Directly linked" is the transfer's own gate, a mesh neighbour now.
        single {
            // Read synchronously by `canJoin`, so held as a state on the app scope from the first call on.
            val directOff = get<SettingsStore>().debugDirectCallsOff.stateIn(get<CoroutineScope>(), SharingStarted.Eagerly, false)
            DirectCallPath(
                leaseOf = { get<DirectWifiLease>() },
                scope = get<CoroutineScope>(),
                nearby = { id -> get<MeshController>().neighbors.value.any { it.nodeId == id } },
                capable = { id -> (get<PeerRepository>().find(id)?.capabilities ?: 0L) and Protocol.CAP_DIRECT_TRANSFER != 0L },
                // API 29-32 refuses Wi-Fi Direct to an app off screen; 33+ gates it on NEARBY_WIFI_DEVICES alone.
                mayJoinWhileRinging = { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || get<AppVisibility>().visible.value },
                enabled = { !directOff.value },
                log = { Log.i(TAG, it) },
            )
        }
        single {
            WebRtcCallMedia(androidContext(), get<CoroutineScope>(), link = get<DirectCallPath>().link, log = { Log.i(TAG, it) })
        }
        // The call screen reads the media's stats itself (its diagnostics card); the manager sees only the seam.
        single<CallMedia> { get<WebRtcCallMedia>() }
        single {
            CallManager(
                signals = CallSignals { peerId, payload -> get<MeshManager>().sendCallSignal(peerId, payload) },
                turn =
                    object : TurnSource {
                        override suspend fun turnServers(peerId: String) = get<MeshManager>().turnServers(peerId)

                        override fun stunUrls() = get<MeshManager>().stunUrls()
                    },
                media = get(),
                scope = get<CoroutineScope>(),
                selfId = { get<Identity>().nodeId() },
                log = { Log.i(TAG, it) },
                direct = get<DirectCallPath>(),
                walkie = if (WALKIE_WIRED) walkieRoute() else CallWalkie.None,
            )
        }
        if (WALKIE_WIRED) {
            single { TalkPlayer() }
            single { TalkRecorder(get<CoroutineScope>()) }
            single {
                WalkieTalk(
                    calls = get(),
                    recorder = get(),
                    player = get(),
                    send = { peerId, chunk, peerTicketed -> get<MeshManager>().sendCallTalk(peerId, chunk, peerTicketed) },
                    links = talkLinks(),
                    scope = get<CoroutineScope>(),
                    log = { Log.i(TAG, it) },
                    // A burst is spoken into the headset that has the call (§3.13), else the phone's own microphone.
                    headset = {
                        get<SystemCallSync>()
                            .routing.value
                            ?.current
                            ?.headset == true
                    },
                )
            }
        }
        // §3.12: each call's line in its thread. The repository is resolved at the first record, not here.
        single {
            CallLog(
                calls = get(),
                messages = { get<MessageRepository>() },
                selfId = { get<Identity>().nodeId() },
                scope = get<CoroutineScope>(),
                log = { Log.i(TAG, it) },
            )
        }
        single { CallFaces(androidContext(), peers = { get() }, blobs = { get() }, theme = { get() }) }
        single {
            CallController(
                context = androidContext(),
                calls = get(),
                scope = get<CoroutineScope>(),
                faces = get(),
                alertMs = CallTimings().alertMs,
            )
        }
    }

/**
 * When a call is placed as a walkie (§3.11): the peer must run one, and a live call must look impossible — no Internet
 * route here (no spool, no STUN or TURN), and no Wi-Fi Direct leg, which wants a neighbour that runs the transfer's
 * radio with direct calls on. A wrong guess costs little: a live call with no path drops to a walkie anyway.
 */
private fun Scope.walkieRoute(): CallWalkie =
    object : CallWalkie {
        override val enabled = true

        override suspend fun capable(peerId: String) =
            (get<PeerRepository>().find(peerId)?.capabilities ?: 0L) and Protocol.CAP_WALKIE != 0L

        override suspend fun liveLikely(peerId: String): Boolean {
            if (get<InternetGate>().isOnline()) return true
            val neighbour = get<MeshController>().neighbors.value.any { it.nodeId == peerId }
            val direct = (get<PeerRepository>().find(peerId)?.capabilities ?: 0L) and Protocol.CAP_DIRECT_TRANSFER != 0L
            return neighbour && direct && !get<SettingsStore>().debugDirectCallsOff.first()
        }

        override fun castTicketed() = get<MeshManager>().castTicketed()
    }

/**
 * The walkie's view of the mesh (§3.11): a chunk's link to a peer, from the live links, the peers heard through them
 * and whether a relay would cast to it (§2.7), and the call's hold on a Wi-Fi Aware link to it (`MeshTransport.holdLink`).
 */
private fun Scope.talkLinks(): TalkLinks =
    object : TalkLinks {
        override fun linkTo(
            peerId: String,
            peerTicketed: () -> Boolean,
        ): Flow<TalkLink> {
            val mesh = get<MeshController>()
            val manager = get<MeshManager>()
            // A relay's hello, its SUB's answer and a ticket's verdict arrive on no flow the walkie can watch, so the
            // spool half is looked at again every few seconds; the radios' half moves the moment they do.
            val castable =
                flow {
                    while (true) {
                        emit(manager.canCastTalk(peerId, peerTicketed()))
                        delay(CAST_LOOK_MS)
                    }
                }
            return combine(mesh.neighbors, mesh.heardIndirectly, castable) { linked, heard, cast ->
                TalkLink.of(peerId, linked.mapTo(HashSet()) { it.nodeId }, heard.keys, cast)
            }.distinctUntilChanged()
        }

        override fun raise(peerId: String): Boolean = get<MeshTransport>().holdLink(peerId)

        // sendCallTalk's order: the peer's own link, a Wi-Fi Aware one first (sendLive), else a flood over any link,
        // and a cast through a relay for a peer the mesh has no path to, or beside the peer's own link when it fades.
        override fun pathTo(
            peerId: String,
            peerTicketed: Boolean,
        ): TalkPath {
            val transport = get<MeshTransport>()
            val linked = transport.neighbors.value
            val meshPath =
                linked.any { it.nodeId == peerId } || (linked.isNotEmpty() && peerId in get<MeshController>().heardIndirectly.value)
            return when {
                !meshPath && get<MeshManager>().canCastTalk(peerId, peerTicketed) -> TalkPath.SPOOL
                linked.isEmpty() -> TalkPath.NONE
                transport.bulkRouteTo(peerId) == BulkRoute.Linked -> TalkPath.WIFI_AWARE
                linked.none { it.nodeId == peerId } -> TalkPath.FLOODED
                transport.liveLinkFading(peerId) && get<MeshManager>().canCastTalk(peerId, peerTicketed) -> TalkPath.FADING
                transport.slowLinkTo(peerId) -> TalkPath.BLUETOOTH_CODED
                else -> TalkPath.BLUETOOTH
            }
        }
    }

/** Starts the call surfaces' collectors at process start, on a background thread — never on main. */
fun startCallsIfWired(koin: Koin) {
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        // First: a call that rings before the system holds it has no route but the one the phone was left in.
        runCatching { koin.get<SystemCallSync>().start() }.onFailure { Log.w(TAG, "system calls not started", it) }
        runCatching { koin.get<CallController>().start() }.onFailure { Log.w(TAG, "calls not started", it) }
        runCatching { koin.get<CallLog>().start() }.onFailure { Log.w(TAG, "call records not started", it) }
        if (WALKIE_WIRED) runCatching { koin.get<WalkieTalk>().start() }.onFailure { Log.w(TAG, "walkie not started", it) }
    }
}

/** The app's screens, under a bar that leads back to a live call ([CallBarHost]). */
@Composable
fun WithCallBar(content: @Composable () -> Unit) = CallBarHost(content)

private const val TAG = "KnitCall"

/** How often the walkie's link looks again at whether a relay would cast to its peer. */
private const val CAST_LOOK_MS = 3_000L
