package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.getknit.knit.call.OwnIceServers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The person's own STUN and TURN servers for calls (experimental, docs/VOICE_CALLS.md §3.14, ADR 2026-10.mr88), as typed
 * on the Calls screen, over the settings DataStore rather than grown into [SettingsStore]: each kind's switch and its
 * fields under `calls_stun_` / `calls_turn_`. A call reads them through [OwnIceServers.effective], which drops a kind
 * whose fields fail its rule. The TURN password is stored in the clear beside a relay's `?k=` token (ADR 2026-09.66cw:
 * app-private, out of Auto Backup), and these keys ride a Knit backup like every other choice the person made.
 */
class OwnIceStore(
    private val dataStore: DataStore<Preferences>,
) {
    val ownIce: Flow<OwnIceServers> =
        dataStore.data.map {
            OwnIceServers(
                stunOn = it[KEY_STUN_ON] ?: false,
                stunUrls = it[KEY_STUN_URLS].orEmpty().lines().filter(String::isNotEmpty),
                turnOn = it[KEY_TURN_ON] ?: false,
                turnUrl = it[KEY_TURN_URL].orEmpty(),
                turnUser = it[KEY_TURN_USER].orEmpty(),
                turnPass = it[KEY_TURN_PASS].orEmpty(),
            )
        }

    suspend fun setStunOn(value: Boolean) = dataStore.edit { it[KEY_STUN_ON] = value }

    suspend fun setTurnOn(value: Boolean) = dataStore.edit { it[KEY_TURN_ON] = value }

    /** The own STUN URLs, one per line; the switch is left as it is. */
    suspend fun setStunUrls(urls: List<String>) = dataStore.edit { it[KEY_STUN_URLS] = urls.joinToString("\n") }

    /** The own TURN server's fields, in one edit so a call never reads a URL with the last server's credential. */
    suspend fun setTurn(
        url: String,
        user: String,
        pass: String,
    ) = dataStore.edit {
        it[KEY_TURN_URL] = url
        it[KEY_TURN_USER] = user
        it[KEY_TURN_PASS] = pass
    }

    internal companion object {
        val KEY_STUN_ON = booleanPreferencesKey("calls_stun_own")
        val KEY_STUN_URLS = stringPreferencesKey("calls_stun_urls")
        val KEY_TURN_ON = booleanPreferencesKey("calls_turn_own")
        val KEY_TURN_URL = stringPreferencesKey("calls_turn_url")
        val KEY_TURN_USER = stringPreferencesKey("calls_turn_user")
        val KEY_TURN_PASS = stringPreferencesKey("calls_turn_pass")
    }
}
