package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The user's low-power mode switch (ADR 2026-10.d44b), over the settings DataStore rather than grown into
 * [SettingsStore], like [TicketKeyStore]. Read through `LowPowerSource`, which adds Battery Saver and the charger and
 * turns them into the posture the planes read. The posture is an overlay: this one key is all it writes, and
 * switching it off clears the key. Phone-bound: `low_power_` is in `SettingsKeys.TRANSIENT_PREFIXES`, because a
 * restore onto a new phone must not start it in low-power mode.
 */
class LowPowerSwitch(
    private val dataStore: DataStore<Preferences>,
) {
    val on: Flow<Boolean> = dataStore.data.map { it[KEY] == true }.distinctUntilChanged()

    suspend fun set(on: Boolean) {
        dataStore.edit {
            if (on) it[KEY] = true else it.remove(KEY)
        }
    }

    private companion object {
        val KEY = booleanPreferencesKey(SettingsKeys.LOW_POWER_MODE)
    }
}
