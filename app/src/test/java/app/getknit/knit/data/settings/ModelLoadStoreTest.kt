package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** ADR 037: the poison-pill record round-trips per model, under the key names a latched phone already holds. */
class ModelLoadStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun modelLoadStateDefaultsToNothingAttemptedAndRoundTripsPerModel() =
        runTest {
            val file = File(tmp.root, "settings.preferences_pb")
            val store = ModelLoadStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
            assertEquals(ModelLoadState.NONE, store.modelLoadState("toxicity"))

            store.setModelLoadState("toxicity", ModelLoadState("16|rom", pendingSince = 1_700L, fails = 1))

            assertEquals(ModelLoadState("16|rom", 1_700L, 1), store.modelLoadState("toxicity"))
            // Keyed per model: latching the text classifier must not touch the image one.
            assertEquals(ModelLoadState.NONE, store.modelLoadState("nsfw"))
            assertEquals(ModelLoadState("16|rom", 1_700L, 1), store.observeModelLoad("toxicity").first())
        }

    @Test
    fun aRecordWrittenBeforeTheStoreMovedOutOfSettingsStoreIsStillRead() =
        runTest {
            val file = File(tmp.root, "settings.preferences_pb")
            val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
            dataStore.edit {
                it[stringPreferencesKey("model_load_stamp_toxicity")] = "16|rom"
                it[longPreferencesKey("model_load_pending_toxicity")] = 1_700L
                it[intPreferencesKey("model_load_fails_toxicity")] = 2
            }

            assertEquals(ModelLoadState("16|rom", 1_700L, 2), ModelLoadStore(dataStore).modelLoadState("toxicity"))
        }
}
