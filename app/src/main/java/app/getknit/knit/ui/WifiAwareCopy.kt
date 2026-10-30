package app.getknit.knit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import app.getknit.knit.mesh.PlaneSupport
import app.getknit.knit.mesh.wifiAwareSupport

/**
 * Whether copy may promise Wi-Fi Aware on this phone: the same probe the onboarding gate and Diagnostics read
 * (`mesh/RadioSupport.kt`), a static fact for the process. A phone without it (no hardware, or below Android 12)
 * must never be told Wi-Fi Aware will fetch something on its own (ADR 2026-10.js2p). Previews render as a
 * two-radio phone.
 */
@Composable
fun rememberWifiAwareSupported(): Boolean {
    if (LocalInspectionMode.current) return true
    val context = LocalContext.current
    return remember(context) { wifiAwareSupport(context) == PlaneSupport.Supported }
}
