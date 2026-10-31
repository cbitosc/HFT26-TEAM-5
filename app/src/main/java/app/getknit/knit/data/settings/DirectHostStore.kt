package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.getknit.knit.transfer.DirectHostJournal
import app.getknit.knit.transfer.DirectHostRecord
import kotlinx.coroutines.flow.first

/**
 * The Wi-Fi Direct host guard's record (`transfer/DirectHostGuard`, ADR 2026-10.w3dw), over the settings DataStore
 * rather than grown into [SettingsStore]: five keys under `direct_host_`, written in one edit so they never disagree.
 * About this phone's ROM, so a backup never carries them (`SettingsKeys.TRANSIENT_PREFIXES`).
 */
class DirectHostStore(
    private val dataStore: DataStore<Preferences>,
) : DirectHostJournal {
    override suspend fun read(): DirectHostRecord =
        dataStore.data.first().let {
            DirectHostRecord(
                stamp = it[KEY_STAMP].orEmpty(),
                since = it[KEY_SINCE] ?: 0L,
                sinceElapsed = it[KEY_SINCE_ELAPSED] ?: 0L,
                fails = it[KEY_FAILS] ?: 0,
                latchedAt = it[KEY_LATCHED_AT] ?: 0L,
            )
        }

    override suspend fun write(record: DirectHostRecord) {
        dataStore.edit {
            it[KEY_STAMP] = record.stamp
            it[KEY_SINCE] = record.since
            it[KEY_SINCE_ELAPSED] = record.sinceElapsed
            it[KEY_FAILS] = record.fails
            it[KEY_LATCHED_AT] = record.latchedAt
        }
    }

    private companion object {
        val KEY_STAMP = stringPreferencesKey(SettingsKeys.DIRECT_HOST_PREFIX + "stamp")
        val KEY_SINCE = longPreferencesKey(SettingsKeys.DIRECT_HOST_PREFIX + "since")
        val KEY_SINCE_ELAPSED = longPreferencesKey(SettingsKeys.DIRECT_HOST_PREFIX + "since_elapsed")
        val KEY_FAILS = intPreferencesKey(SettingsKeys.DIRECT_HOST_PREFIX + "fails")
        val KEY_LATCHED_AT = longPreferencesKey(SettingsKeys.DIRECT_HOST_PREFIX + "latched_at")
    }
}
