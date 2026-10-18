package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Which kinds of incoming call this phone takes (C-3.3-7): a [live] internet or Wi-Fi Direct call, a [walkie] mesh call. */
data class CallIntake(
    val live: Boolean = true,
    val walkie: Boolean = true,
) {
    fun takes(walkie: Boolean): Boolean = if (walkie) this.walkie else live
}

/**
 * Whether this phone takes incoming calls of each kind (experimental, docs/VOICE_CALLS.md C-3.3-7), over the settings
 * DataStore rather than grown into [SettingsStore]. Both on by default; off, an offer of that kind is answered
 * `DECLINE rsn=timeout` and never rings. Bound in every build for the Settings row, read by a call only where calls
 * are wired; rides a Knit backup like every other choice the person made.
 */
class IncomingCallStore(
    private val dataStore: DataStore<Preferences>,
) {
    /** The Calls screen's "Live calls" and "Mesh calls" switches under "Incoming calls". */
    val intake: Flow<CallIntake> = dataStore.data.map { CallIntake(live = it[KEY_LIVE] ?: true, walkie = it[KEY_WALKIE] ?: true) }

    suspend fun setLive(value: Boolean) = dataStore.edit { it[KEY_LIVE] = value }

    suspend fun setWalkie(value: Boolean) = dataStore.edit { it[KEY_WALKIE] = value }

    internal companion object {
        val KEY_LIVE = booleanPreferencesKey("calls_take_live")
        val KEY_WALKIE = booleanPreferencesKey("calls_take_walkie")
    }
}
