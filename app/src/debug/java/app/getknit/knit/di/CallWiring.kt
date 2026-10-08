package app.getknit.knit.di

import android.util.Log
import androidx.compose.runtime.Composable
import app.getknit.knit.call.CallManager
import app.getknit.knit.call.CallMedia
import app.getknit.knit.call.CallSignals
import app.getknit.knit.call.CallTimings
import app.getknit.knit.call.TurnSource
import app.getknit.knit.debug.call.CallAudio
import app.getknit.knit.debug.call.CallBarHost
import app.getknit.knit.debug.call.CallController
import app.getknit.knit.debug.call.CallFaces
import app.getknit.knit.debug.call.WebRtcCallMedia
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.MeshManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * mesh graph is the service's to build, off the main thread (ADR 2026-09.vztn).
 */
fun callModule(): Module? =
    module {
        single { CallAudio(androidContext()) }
        single<CallMedia> { WebRtcCallMedia(androidContext(), get(), get<CoroutineScope>(), log = { Log.i(TAG, it) }) }
        single {
            CallManager(
                signals = CallSignals { peerId, payload -> get<MeshManager>().sendCallSignal(peerId, payload) },
                turn = TurnSource { peerId -> get<MeshManager>().turnServers(peerId) },
                media = get(),
                scope = get<CoroutineScope>(),
                selfId = { get<Identity>().nodeId() },
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
