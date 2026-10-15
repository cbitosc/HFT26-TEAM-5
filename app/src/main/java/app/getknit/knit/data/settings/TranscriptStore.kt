package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The walkie transcript's two choices (experimental, docs/VOICE_CALLS.md C-3.11-9, ADR 2026-10.nz3g), over the settings
 * DataStore rather than grown into [SettingsStore]. Bound only where the walkie is wired; both ride a Knit backup like
 * every other choice the person made.
 */
class TranscriptStore(
    private val dataStore: DataStore<Preferences>,
) {
    /**
     * The Calls screen's "Walkie transcripts" switch, on by default (the model is still a download the person asks for).
     * Off, `WalkieTranscriber` takes no burst and the call screen draws no transcript card.
     */
    val enabled: Flow<Boolean> = dataStore.data.map { it[KEY_ENABLED] ?: true }

    /**
     * Whether the person dismissed the walkie card's offer of the speech model: once true, a walkie call draws no card
     * while the model is not on the phone, and the download is the Calls screen's alone. Never cleared.
     */
    val offerDismissed: Flow<Boolean> = dataStore.data.map { it[KEY_OFFER_DISMISSED] ?: false }

    suspend fun setEnabled(value: Boolean) = dataStore.edit { it[KEY_ENABLED] = value }

    suspend fun dismissOffer() = dataStore.edit { it[KEY_OFFER_DISMISSED] = true }

    internal companion object {
        val KEY_ENABLED = booleanPreferencesKey("calls_transcripts")
        val KEY_OFFER_DISMISSED = booleanPreferencesKey("calls_transcript_offer_dismissed")
    }
}
