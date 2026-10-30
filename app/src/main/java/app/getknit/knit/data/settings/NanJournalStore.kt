package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * Both Wi-Fi Aware journals — the attach give-up ([NanAttachJournal], ADR 055) and the initiator hold
 * ([NanInitiatorJournal], ADR 2026-09.m8kc) — over the settings DataStore rather than grown into [SettingsStore].
 * About this phone's ROM, so a backup never carries them (`SettingsKeys.TRANSIENT_PREFIXES`).
 */
class NanJournalStore(
    private val dataStore: DataStore<Preferences>,
) : NanAttachJournal,
    NanInitiatorJournal {
    override suspend fun awareGiveUpStamp(): String = dataStore.data.first()[KEY_AWARE_GIVE_UP_STAMP].orEmpty()

    override suspend fun setAwareGiveUpStamp(stamp: String) {
        dataStore.edit { it[KEY_AWARE_GIVE_UP_STAMP] = stamp }
    }

    override suspend fun initiatorLatch(): NanInitiatorLatch =
        dataStore.data.first().let {
            NanInitiatorLatch(
                stamp = it[KEY_INITIATOR_HOLD_STAMP].orEmpty(),
                probedAt = it[KEY_INITIATOR_HOLD_PROBED_AT] ?: 0L,
            )
        }

    override suspend fun setInitiatorLatch(latch: NanInitiatorLatch) {
        dataStore.edit {
            it[KEY_INITIATOR_HOLD_STAMP] = latch.stamp
            it[KEY_INITIATOR_HOLD_PROBED_AT] = latch.probedAt
        }
    }

    private companion object {
        val KEY_AWARE_GIVE_UP_STAMP = stringPreferencesKey("aware_give_up_stamp")
        val KEY_INITIATOR_HOLD_STAMP = stringPreferencesKey("nan_initiator_hold_stamp")
        val KEY_INITIATOR_HOLD_PROBED_AT = longPreferencesKey("nan_initiator_hold_probed_at")
    }
}
