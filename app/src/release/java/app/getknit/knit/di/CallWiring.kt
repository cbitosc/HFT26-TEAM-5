package app.getknit.knit.di

import androidx.compose.runtime.Composable
import org.koin.core.Koin
import org.koin.core.module.Module

/** Release-variant voice-call wiring: none. The WebRTC media is debug-only, so this build advertises no `CAP_CALL`. */
const val CALLS_WIRED: Boolean = false

/** No calls, so no walkie-talkie either (docs/VOICE_CALLS.md §3.11): no `CAP_WALKIE`, whatever `BuildConfig.WALKIE` says. */
const val WALKIE_WIRED: Boolean = false

fun callModule(): Module? = null

@Suppress("UNUSED_PARAMETER")
fun startCallsIfWired(koin: Koin) = Unit

/** No calls, so no bar: the app's screens as they are. */
@Composable
fun WithCallBar(content: @Composable () -> Unit) = content()
