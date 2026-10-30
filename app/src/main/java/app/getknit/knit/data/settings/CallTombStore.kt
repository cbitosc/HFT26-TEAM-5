package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.getknit.knit.call.CallTomb
import app.getknit.knit.call.CallTombJournal
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The calls this phone saw end, kept across a process death (experimental, docs/VOICE_CALLS.md C-3.4-3, ADR
 * 2026-10.3tee's fifth amendment), over the settings DataStore rather than grown into [SettingsStore]: one key,
 * `call_tombs`, holding a JSON list no longer than `CallManager`'s bound. They are about this phone's calls, so a backup
 * never carries them (`SettingsKeys.TRANSIENT_PREFIXES`); a restored phone holds new sessions, which cannot open the old
 * ones' frames anyway. A value that fails to parse reads as empty, which costs at most one late offer's ring.
 */
class CallTombStore(
    private val dataStore: DataStore<Preferences>,
) : CallTombJournal {
    override suspend fun load(): List<CallTomb> =
        dataStore.data
            .first()[KEY]
            ?.let(::decode)
            .orEmpty()

    override suspend fun save(tombs: List<CallTomb>) {
        dataStore.edit { it[KEY] = json.encodeToString(tombs.map { t -> Stored(t.id, t.ph, t.rsn, t.at) }) }
    }

    /** Names nothing it holds. */
    override fun toString(): String = "CallTombStore"

    @Serializable
    private class Stored(
        val i: String,
        val p: Int,
        val r: Int? = null,
        val t: Long,
    )

    private companion object {
        val KEY = stringPreferencesKey(SettingsKeys.CALL_TOMBS)
        val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String): List<CallTomb>? =
            runCatching { json.decodeFromString<List<Stored>>(text) }
                .getOrNull()
                ?.map { CallTomb(it.i, it.p, it.r, it.t) }
    }
}
