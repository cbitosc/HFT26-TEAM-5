package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.getknit.knit.call.CallTomb
import app.getknit.knit.mesh.protocol.CallPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The calls' tombstone journal over a real Preferences DataStore (the [TicketKeyStoreTest] rig, docs/VOICE_CALLS.md
 * C-3.4-3): what one process saved, the next reads back whole; a save replaces, never appends; and a value nobody can
 * parse reads as no tombstones rather than as an error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallTombStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.dataStore() =
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "settings.preferences_pb") }

    @Test
    fun whatOneProcessSavedTheNextReadsBack() =
        runTest {
            val dataStore = dataStore()
            CallTombStore(dataStore).save(
                listOf(
                    CallTomb("c1", CallPayload.PHASE_CANCEL, CallPayload.REASON_USER, at = 10L),
                    CallTomb("c2", CallPayload.PHASE_END, rsn = null, at = 20L),
                ),
            )
            val read = CallTombStore(dataStore).load()
            assertEquals(listOf("c1", "c2"), read.map { it.id })
            assertEquals(listOf(CallPayload.PHASE_CANCEL, CallPayload.PHASE_END), read.map { it.ph })
            assertEquals(listOf(CallPayload.REASON_USER, null), read.map { it.rsn })
            assertEquals(listOf(10L, 20L), read.map { it.at })
        }

    @Test
    fun aSaveReplacesTheWholeList() =
        runTest {
            val store = CallTombStore(dataStore())
            store.save(listOf(CallTomb("c1", CallPayload.PHASE_END, null, 1L)))
            store.save(listOf(CallTomb("c2", CallPayload.PHASE_END, null, 2L)))
            assertEquals(listOf("c2"), store.load().map { it.id })
        }

    @Test
    fun nothingSavedAndAnUnreadableValueBothReadAsNoTombstones() =
        runTest {
            val dataStore = dataStore()
            assertTrue(CallTombStore(dataStore).load().isEmpty())
            dataStore.edit { it[stringPreferencesKey(SettingsKeys.CALL_TOMBS)] = "not json" }
            assertTrue(CallTombStore(dataStore).load().isEmpty())
        }
}
