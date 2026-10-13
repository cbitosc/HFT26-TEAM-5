package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import app.getknit.knit.mesh.spool.HeldKey
import app.getknit.knit.mesh.spool.KeyAnswer
import app.getknit.knit.mesh.spool.KeyState
import app.getknit.knit.mesh.spool.TicketKeyJournal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The customer keys an invite handed this phone, and what the ticket refresher knows about asking with each
 * (experimental, docs/VOICE_CALLS.md §2.6, ADR 2026-10.3sk7), over the settings DataStore rather than grown into
 * [SettingsStore]: the same file as the relay list and its `?k=` tokens, so the same protection (ADR 2026-09.66cw:
 * app-private, out of Auto Backup and device transfer with the whole DataStore, never rendered).
 *
 * Two keys per relay host name, each a small JSON object: `spool_key:<host>` holds the issuer origin and the key, and
 * rides a Knit backup as the relay's token does (a restore is a move, ADR 2026-09.6mj7); `spool_key_state:<host>` holds
 * the last verdict, `endsAt`, the next-ask floor and the failure count, which are this phone's and stay out of one
 * (`SettingsKeys.TRANSIENT_PREFIXES`). A value that fails to parse reads as absent: a key nobody can read is no key,
 * and a state nobody can read is a fresh one, which costs one ask.
 */
class TicketKeyStore(
    private val dataStore: DataStore<Preferences>,
) : TicketKeyJournal {
    override val keys: Flow<Map<String, HeldKey>> =
        dataStore.data
            .map { prefs ->
                prefs.byPrefix(SettingsKeys.SPOOL_KEY_PREFIX) { decodeKey(it) }
            }.distinctUntilChanged()

    override val keyStates: Flow<Map<String, KeyState>> =
        dataStore.data
            .map { prefs ->
                prefs.byPrefix(SettingsKeys.SPOOL_KEY_STATE_PREFIX) { decodeState(it) }
            }.distinctUntilChanged()

    override suspend fun putKey(
        host: String,
        key: HeldKey,
    ) {
        dataStore.edit {
            if (it[SettingsKeys.spoolKeyKey(host)]?.let(::decodeKey) == key) return@edit
            it[SettingsKeys.spoolKeyKey(host)] = encodeKey(key)
            it.remove(SettingsKeys.spoolKeyStateKey(host))
        }
    }

    override suspend fun putKeyState(
        host: String,
        key: HeldKey,
        state: KeyState,
    ) {
        dataStore.edit {
            if (it[SettingsKeys.spoolKeyKey(host)]?.let(::decodeKey) != key) return@edit
            it[SettingsKeys.spoolKeyStateKey(host)] = encodeState(state)
        }
    }

    override suspend fun forgetHost(host: String) {
        dataStore.edit {
            it.remove(SettingsKeys.spoolKeyKey(host))
            it.remove(SettingsKeys.spoolKeyStateKey(host))
            it.remove(SettingsKeys.spoolTicketKey(host))
            it.remove(SettingsKeys.spoolTicketVerdictKey(host))
        }
    }

    /** Names nothing it holds. */
    override fun toString(): String = "TicketKeyStore"

    @Serializable
    private class StoredKey(
        val i: String,
        val k: String,
    )

    @Serializable
    private class StoredState(
        val a: String? = null,
        val e: Long? = null,
        val nb: Long = 0,
        val f: Int = 0,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun <T : Any> Preferences.byPrefix(
            prefix: String,
            decode: (String) -> T?,
        ): Map<String, T> =
            asMap()
                .entries
                .mapNotNull { (key, value) ->
                    if (!key.name.startsWith(prefix)) return@mapNotNull null
                    val decoded = (value as? String)?.let(decode) ?: return@mapNotNull null
                    key.name.removePrefix(prefix) to decoded
                }.toMap()

        fun encodeKey(key: HeldKey): String = json.encodeToString(StoredKey(key.issuer, key.key))

        fun decodeKey(text: String): HeldKey? =
            runCatching { json.decodeFromString<StoredKey>(text) }.getOrNull()?.let { HeldKey(it.i, it.k) }

        fun encodeState(state: KeyState): String =
            json.encodeToString(StoredState(state.answer?.word, state.endsAt, state.notBefore, state.failures))

        fun decodeState(text: String): KeyState? =
            runCatching { json.decodeFromString<StoredState>(text) }.getOrNull()?.let {
                KeyState(KeyAnswer.of(it.a), it.e, it.nb, it.f)
            }
    }
}
