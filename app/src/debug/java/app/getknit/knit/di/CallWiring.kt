package app.getknit.knit.di

import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import app.getknit.knit.call.CallManager
import app.getknit.knit.call.CallMedia
import app.getknit.knit.call.CallSignals
import app.getknit.knit.call.CallTimings
import app.getknit.knit.call.DirectCallPath
import app.getknit.knit.call.TurnSource
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.debug.call.CallAudio
import app.getknit.knit.debug.call.CallBarHost
import app.getknit.knit.debug.call.CallController
import app.getknit.knit.debug.call.CallFaces
import app.getknit.knit.debug.call.WebRtcCallMedia
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.power.AppVisibility
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.transfer.DirectWifiLease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Debug-variant voice-call wiring (experimental, docs/VOICE_CALLS.md): the WebRTC media is `debugImplementation`, so
 * only this variant has calls at all. `src/release` returns nothing and advertises no `CAP_CALL`.
 */
const val CALLS_WIRED: Boolean = true

/**
 * The call graph. [CallManager]'s seams are late-bound over [MeshManager], so building it builds nothing else — the
 * mesh graph is the service's to build, off the main thread (ADR 2026-09.vztn). The direct leg's radio lease is
 * late-bound the same way: it wraps the Wi-Fi Direct radio, which holds the mesh transport.
 */
fun callModule(): Module? =
    module {
        single { CallAudio(androidContext()) }
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
            WebRtcCallMedia(androidContext(), get(), get<CoroutineScope>(), link = get<DirectCallPath>().link, log = { Log.i(TAG, it) })
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

/** Starts the call surfaces' collectors at process start, on a background thread — never on main. */
fun startCallsIfWired(koin: Koin) {
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        runCatching { koin.get<CallController>().start() }.onFailure { Log.w(TAG, "calls not started", it) }
    }
}

/** The app's screens, under a bar that leads back to a live call ([CallBarHost]). */
@Composable
fun WithCallBar(content: @Composable () -> Unit) = CallBarHost(content)

private const val TAG = "KnitCall"
