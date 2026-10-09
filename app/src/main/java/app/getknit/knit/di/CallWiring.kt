package app.getknit.knit.di

import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import app.getknit.knit.BuildConfig
import app.getknit.knit.call.CallControl
import app.getknit.knit.call.CallLog
import app.getknit.knit.call.CallManager
import app.getknit.knit.call.CallMedia
import app.getknit.knit.call.CallReach
import app.getknit.knit.call.CallReachRule
import app.getknit.knit.call.CallSignals
import app.getknit.knit.call.CallTimings
import app.getknit.knit.call.CallToneSync
import app.getknit.knit.call.CallWalkie
import app.getknit.knit.call.CaptureHealth
import app.getknit.knit.call.CaptureJournal
import app.getknit.knit.call.CustomIce
import app.getknit.knit.call.DirectCallPath
import app.getknit.knit.call.IceProbe
import app.getknit.knit.call.OverridingTurnSource
import app.getknit.knit.call.SystemCallSync
import app.getknit.knit.call.SystemCalls
import app.getknit.knit.call.TalkLink
import app.getknit.knit.call.TurnSource
import app.getknit.knit.call.WalkieTranscriber
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.settings.CallTombStore
import app.getknit.knit.data.settings.CaptureStore
import app.getknit.knit.data.settings.OwnIceStore
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.device.DeviceImage
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.BulkRoute
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.indirectPeers
import app.getknit.knit.mesh.power.AppVisibility
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.spool.spoolPresentPeers
import app.getknit.knit.moderation.ModelLoadGuard
import app.getknit.knit.moderation.modelGuardStamp
import app.getknit.knit.net.AndroidInternetGate
import app.getknit.knit.net.InternetGate
import app.getknit.knit.transfer.DirectWifiLease
import app.getknit.knit.ui.call.CallBarHost
import app.getknit.knit.ui.call.CallController
import app.getknit.knit.ui.call.CallFaces
import app.getknit.knit.ui.call.CallService
import app.getknit.knit.ui.call.TalkLinks
import app.getknit.knit.ui.call.TalkPath
import app.getknit.knit.ui.call.TalkPlayer
import app.getknit.knit.ui.call.TalkRecorder
import app.getknit.knit.ui.call.TelecomCalls
import app.getknit.knit.ui.call.ToneGeneratorPlayer
import app.getknit.knit.ui.call.WalkieTalk
import app.getknit.knit.ui.call.WebRtcCallMedia
import app.getknit.knit.ui.call.stt.SherpaSpeech
import app.getknit.knit.ui.call.stt.SttModelStore
import app.getknit.knit.ui.callsettings.CallSettingsViewModel
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.scope.Scope
import org.koin.dsl.module

/**
 * Voice calls (experimental, docs/VOICE_CALLS.md): the one read of `BuildConfig.CALLS` — on in debug, off in a shipped
 * artifact, `-Pcalls=` overrides. Off, [callModule] is null, nothing starts, no bar is drawn and the profile carries no
 * `CAP_CALL`; the build also packages neither WebRTC nor core-telecom nor the call screen's manifest entries, so every
 * `ui/call` class is unreachable and R8 drops it (ADR 2026-10.3tee, the 2026-10-14 amendment).
 */
const val CALLS_WIRED: Boolean = BuildConfig.CALLS

/**
 * The walkie-talkie (docs/VOICE_CALLS.md §3.11, ADR 2026-10.79yb): a call with no live path is hold-to-talk bursts
 * streamed over the mesh. The one read of `BuildConfig.WALKIE`, and only where calls are wired — off, [CallManager] gets
 * [CallWalkie.None], no [WalkieTalk] is built and the profile carries no `CAP_WALKIE`, so every call is a live one.
 */
const val WALKIE_WIRED: Boolean = CALLS_WIRED && BuildConfig.WALKIE

/** The call graph, or null in a build without calls ([CALLS_WIRED]). */
fun callModule(): Module? = if (CALLS_WIRED) callGraph() else null

/**
 * [CallManager]'s seams are late-bound over [MeshManager], so building the graph builds nothing else — the mesh graph
 * is the service's to build, off the main thread (ADR 2026-09.vztn). The direct leg's radio lease is late-bound the
 * same way: it wraps the Wi-Fi Direct radio, which holds the mesh transport.
 */
@Suppress("LongMethod") // the whole call graph in one module, a definition per seam, as the other wiring files keep theirs
private fun callGraph(): Module =
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
        // C-3.13-14: the caller's ringback, busy and end tones, which the system plays none of for a self-managed call.
        single {
            val scope = get<CoroutineScope>()
            CallToneSync(calls = get(), player = ToneGeneratorPlayer(scope, log = { Log.w(TAG, it) }), scope = scope)
        }
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
        // C-3.13-12 (ADR 2026-10.kw29): which microphone path a call records from — the fallback on a generic system
        // image over an older vendor, or once a call here captured only zeros. Read from the store on the app scope;
        // until then, the image's own answer.
        single<CaptureJournal> { CaptureStore(get()) }
        single {
            CaptureHealth(
                journal = get(),
                stamp = modelGuardStamp(BuildConfig.VERSION_CODE, Build.FINGERPRINT.orEmpty()),
                genericImage = get<DeviceImage>().genericOnOlderVendor,
            ).also { health -> get<CoroutineScope>().launch { health.load() } }
        }
        single {
            WebRtcCallMedia(
                androidContext(),
                get<CoroutineScope>(),
                link = get<DirectCallPath>().link,
                capture = get(),
                log = { Log.i(TAG, it) },
            )
        }
        // The call screen reads the media's stats itself (its diagnostics card); the manager sees only the seam.
        single<CallMedia> { get<WebRtcCallMedia>() }
        // The Calls screen's Test (C-3.14-5): the same factory, and refused while a call holds it.
        single<IceProbe> { get<WebRtcCallMedia>() }
        viewModel { CallSettingsViewModel(settings = get(), ownIce = get(), probe = get()) }
        single { CallTombStore(get()) }
        single {
            // C-3.7-3's "Relay all calls", read as each call opens its media. Until the store's first read lands it
            // reads as on: a call in that instant goes through TURN rather than showing the peer an address it hides.
            val relayAll =
                get<SettingsStore>()
                    .callsRelayAll
                    .map<Boolean, Boolean?> { it }
                    .stateIn(get<CoroutineScope>(), SharingStarted.Eagerly, null)
            // §3.14 (ADR 2026-10.mr88): the person's own servers, each kind replacing the spools'. Until the store's
            // first read lands they read as none, so that instant's call asks the spools as it always did.
            val ownIce =
                get<OwnIceStore>()
                    .ownIce
                    .map { it.effective() }
                    .stateIn(get<CoroutineScope>(), SharingStarted.Eagerly, CustomIce.NONE)
            CallManager(
                signals = CallSignals { peerId, payload -> get<MeshManager>().sendCallSignal(peerId, payload) },
                turn =
                    OverridingTurnSource(
                        custom = { ownIce.value },
                        spool =
                            object : TurnSource {
                                override suspend fun turnServers(peerId: String) = get<MeshManager>().turnServers(peerId)

                                override fun stunUrls() = get<MeshManager>().stunUrls()
                            },
                    ),
                media = get(),
                scope = get<CoroutineScope>(),
                selfId = { get<Identity>().nodeId() },
                log = { Log.i(TAG, it) },
                direct = get<DirectCallPath>(),
                walkie = if (WALKIE_WIRED) walkieRoute() else CallWalkie.None,
                relayAll = { relayAll.value ?: true },
                tombs = get<CallTombStore>(),
                reach = callReach(),
            )
        }
        if (WALKIE_WIRED) {
            // The transcript (C-3.11-9): a model the user downloads, run here on the bursts this phone hears.
            single { SttModelStore(androidContext(), get<AndroidInternetGate>(), get<CoroutineScope>(), log = { Log.i(TAG, it) }) }
            single { SherpaSpeech(get(), get<ModelLoadGuard>(), get<CoroutineScope>(), log = { Log.i(TAG, it) }) }
            single {
                WalkieTranscriber(
                    speech = get<SherpaSpeech>(),
                    ready = { get<SttModelStore>().paths() != null },
                    scope = get<CoroutineScope>(),
                    // One burst at a time, in the order they ended, off the player's loop.
                    dispatcher = Dispatchers.Default.limitedParallelism(1),
                    log = { Log.i(TAG, it) },
                )
            }
            single { TalkPlayer(tap = get<WalkieTranscriber>()) }
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
                    transcript = get(),
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
 * radio with direct calls on, and this phone able to host it. A wrong guess costs little: a live call with no path
 * drops to a walkie anyway.
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
            // The caller hosts the leg, so a phone that may not host has none to offer (ADR 2026-10.w3dw).
            val hosts = get<DirectWifiLease>().mayHost()
            return neighbour && direct && hosts && !get<SettingsStore>().debugDirectCallsOff.first()
        }

        override fun castTicketed() = get<MeshManager>().castTicketed()
    }

/**
 * C-3.3-5's evidence that a call's signals can reach a peer, read off the mesh's own sets by [CallReachRule]: a live
 * link, the peer heard through one, a Wi-Fi Aware sighting ([MeshTransport.bulkRouteTo]), or a spool we share a scope on
 * that the peer's own traffic reached (`spoolPresentPeers`, the presence dot's window). All in memory.
 */
private fun Scope.callReach(): CallReach =
    CallReach { peerId ->
        val mesh = get<MeshController>()
        val now = System.currentTimeMillis()
        CallReachRule.reachable(
            peerId,
            neighbours = mesh.neighbors.value.mapTo(HashSet()) { it.nodeId },
            heardIndirectly = indirectPeers(mesh.heardIndirectly.value, now).keys,
            sighted = get<MeshTransport>().bulkRouteTo(peerId) != BulkRoute.None,
            spoolPresent = spoolPresentPeers(mesh.spoolStatus(), now),
        )
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
            // A relay's hello, its SUB's answer, a ticket's verdict and the peer's traffic arrive on no flow the walkie can
            // watch, so the spool half is looked at again every few seconds; the radios' half moves the moment they do. A
            // relay counts only where the peer was lately seen: one it never was on fans the cast out to nobody.
            val castable =
                flow {
                    while (true) {
                        emit(manager.castCoversTalk(peerId, peerTicketed()))
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
            val manager = get<MeshManager>()
            val cast = !meshPath && manager.canCastTalk(peerId, peerTicketed)
            return when {
                cast && manager.castCoversTalk(peerId, peerTicketed) -> TalkPath.SPOOL
                cast -> TalkPath.CAST_UNSEEN
                linked.isEmpty() -> TalkPath.NONE
                transport.bulkRouteTo(peerId) == BulkRoute.Linked -> TalkPath.WIFI_AWARE
                linked.none { it.nodeId == peerId } -> TalkPath.FLOODED
                transport.liveLinkFading(peerId) && manager.canCastTalk(peerId, peerTicketed) -> TalkPath.FADING
                transport.slowLinkTo(peerId) -> TalkPath.BLUETOOTH_CODED
                else -> TalkPath.BLUETOOTH
            }
        }
    }

/** Starts the call surfaces' collectors at process start, on a background thread — never on main. */
fun startCallsIfWired(koin: Koin) {
    if (!CALLS_WIRED) return
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        // First: a call that rings before the system holds it has no route but the one the phone was left in.
        runCatching { koin.get<SystemCallSync>().start() }.onFailure { Log.w(TAG, "system calls not started", it) }
        runCatching { koin.get<CallToneSync>().start() }.onFailure { Log.w(TAG, "call tones not started", it) }
        runCatching { koin.get<CallController>().start() }.onFailure { Log.w(TAG, "calls not started", it) }
        runCatching { koin.get<CallLog>().start() }.onFailure { Log.w(TAG, "call records not started", it) }
        if (WALKIE_WIRED) runCatching { koin.get<WalkieTalk>().start() }.onFailure { Log.w(TAG, "walkie not started", it) }
        if (WALKIE_WIRED) runCatching { koin.get<SttModelStore>().start() }.onFailure { Log.w(TAG, "walkie stt not started", it) }
    }
}

/** The app's screens, under a bar that leads back to a live call ([CallBarHost]) — or as they are, with no calls. */
@Composable
fun WithCallBar(content: @Composable () -> Unit) {
    if (CALLS_WIRED) CallBarHost(content) else content()
}

private const val TAG = "KnitCall"

/** How often the walkie's link looks again at whether a relay would cast to its peer. */
private const val CAST_LOOK_MS = 3_000L
