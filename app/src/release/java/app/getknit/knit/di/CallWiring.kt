package app.getknit.knit.di

import org.koin.core.Koin
import org.koin.core.module.Module

/** Release-variant voice-call wiring: none. The WebRTC media is debug-only, so this build advertises no `CAP_CALL`. */
const val CALLS_WIRED: Boolean = false

fun callModule(): Module? = null

@Suppress("UNUSED_PARAMETER")
fun startCallsIfWired(koin: Koin) = Unit
