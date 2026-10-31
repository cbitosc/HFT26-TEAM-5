package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** ADR 055 / 2026-09.m8kc: both Wi-Fi Aware journals persist, under the key names a held phone already holds. */
class NanJournalStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun theInitiatorLatchAndGiveUpStampPersist() =
        runTest {
            val file = File(tmp.root, "settings.preferences_pb")
            val store = NanJournalStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
            assertEquals(NanInitiatorLatch.NONE, store.initiatorLatch())
            assertEquals("", store.awareGiveUpStamp())
            store.setInitiatorLatch(NanInitiatorLatch(stamp = "build+rom", probedAt = 1_700_000_000_000L))
            store.setAwareGiveUpStamp("build+rom")
            assertEquals(NanInitiatorLatch(stamp = "build+rom", probedAt = 1_700_000_000_000L), store.initiatorLatch())
            assertEquals("build+rom", store.awareGiveUpStamp())
        }

    @Test
    fun aRecordWrittenBeforeTheStoreMovedOutOfSettingsStoreIsStillRead() =
        runTest {
            val file = File(tmp.root, "settings.preferences_pb")
            val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
            dataStore.edit {
                it[stringPreferencesKey("aware_give_up_stamp")] = "old+rom"
                it[stringPreferencesKey("nan_initiator_hold_stamp")] = "old+rom"
                it[longPreferencesKey("nan_initiator_hold_probed_at")] = 42L
            }

            val store = NanJournalStore(dataStore)
            assertEquals("old+rom", store.awareGiveUpStamp())
            assertEquals(NanInitiatorLatch(stamp = "old+rom", probedAt = 42L), store.initiatorLatch())
        }
}
