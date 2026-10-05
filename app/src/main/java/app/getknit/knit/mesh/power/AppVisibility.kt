package app.getknit.knit.mesh.power

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether Knit's own UI is on screen: `MainActivity` between `onStart` and `onStop`. Together with
 * [PowerState.interactive] it says the person is using Knit now, not merely using the phone, which is the case the
 * Bluetooth scan hunts harder for (ADR 2026-10.yvn6, the 2026-10-04 amendment).
 *
 * Kept apart from [PowerStateSource] on purpose: the Wi-Fi Aware transport heals on every [PowerState] change, and
 * opening the app is not a reason to re-arm NAN.
 */
class AppVisibility {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    fun set(visible: Boolean) {
        _visible.value = visible
    }
}
