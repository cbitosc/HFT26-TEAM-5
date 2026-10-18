package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The model poison-pill's record ([ModelLoadJournal], ADR 037), over the settings DataStore rather than grown into
 * [SettingsStore]: three keys per model under `model_load_`, written in one edit so they never disagree. About this
 * phone's ROM, so a backup never carries them (`SettingsKeys.TRANSIENT_PREFIXES`).
 */
class ModelLoadStore(
    private val dataStore: DataStore<Preferences>,
) : ModelLoadJournal {
    override fun observeModelLoad(model: String): Flow<ModelLoadState> = dataStore.data.map { it.modelLoadState(model) }

    override suspend fun modelLoadState(model: String): ModelLoadState = dataStore.data.first().modelLoadState(model)

    /**
     * All three fields in **one** write, so they can never disagree — the `acceptSpoolConsent` argument.
     * It is also the durability barrier `moderation/ModelLoadGuard` relies on: `edit {}` fsyncs the new
     * file and renames it before it resumes, so a native crash immediately afterwards still finds this.
     */
    override suspend fun setModelLoadState(
        model: String,
        state: ModelLoadState,
    ) {
        dataStore.edit {
            it[stampKey(model)] = state.stamp
            it[pendingKey(model)] = state.pendingSince
            it[failsKey(model)] = state.fails
        }
    }

    private fun Preferences.modelLoadState(model: String) =
        ModelLoadState(
            stamp = this[stampKey(model)].orEmpty(),
            pendingSince = this[pendingKey(model)] ?: 0L,
            fails = this[failsKey(model)] ?: 0,
        )

    private companion object {
        const val PREFIX = "model_load_"

        /** Dynamic per-model keys, e.g. "model_load_stamp_toxicity". */
        fun stampKey(model: String) = stringPreferencesKey(PREFIX + "stamp_" + model)

        fun pendingKey(model: String) = longPreferencesKey(PREFIX + "pending_" + model)

        fun failsKey(model: String) = intPreferencesKey(PREFIX + "fails_" + model)
    }
}
