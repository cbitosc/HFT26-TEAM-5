package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.getknit.knit.call.CaptureJournal
import app.getknit.knit.call.CaptureProfile
import app.getknit.knit.call.CaptureReason
import app.getknit.knit.call.CaptureRecord
import kotlinx.coroutines.flow.first

/**
 * Which microphone path this phone's calls record from (`call/CaptureHealth`, ADR 2026-10.kw29), over the settings
 * DataStore rather than grown into [SettingsStore]: four keys under `call_capture_`, written in one edit. About this
 * phone's ROM, so a backup never carries them (`SettingsKeys.TRANSIENT_PREFIXES`). A name this build does not know
 * reads as no verdict.
 */
class CaptureStore(
    private val dataStore: DataStore<Preferences>,
) : CaptureJournal {
    override suspend fun read(): CaptureRecord =
        dataStore.data.first().let {
            CaptureRecord(
                stamp = it[KEY_STAMP].orEmpty(),
                profile =
                    it[KEY_PROFILE]?.let { name -> CaptureProfile.entries.firstOrNull { p -> p.name == name } } ?: CaptureProfile.STANDARD,
                reason = it[KEY_REASON]?.let { name -> CaptureReason.entries.firstOrNull { r -> r.name == name } },
                at = it[KEY_AT] ?: 0L,
            )
        }

    override suspend fun write(record: CaptureRecord) {
        dataStore.edit {
            it[KEY_STAMP] = record.stamp
            it[KEY_PROFILE] = record.profile.name
            if (record.reason != null) it[KEY_REASON] = record.reason.name else it.remove(KEY_REASON)
            it[KEY_AT] = record.at
        }
    }

    private companion object {
        val KEY_STAMP = stringPreferencesKey(SettingsKeys.CALL_CAPTURE_PREFIX + "stamp")
        val KEY_PROFILE = stringPreferencesKey(SettingsKeys.CALL_CAPTURE_PREFIX + "profile")
        val KEY_REASON = stringPreferencesKey(SettingsKeys.CALL_CAPTURE_PREFIX + "reason")
        val KEY_AT = longPreferencesKey(SettingsKeys.CALL_CAPTURE_PREFIX + "at")
    }
}
