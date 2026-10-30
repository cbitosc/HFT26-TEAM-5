package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.getknit.knit.data.backup.SettingsSnapshot
import app.getknit.knit.transfer.DirectHostRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Wi-Fi Direct host guard's record over a real Preferences DataStore (ADR 2026-10.w3dw): a phone that never hosted
 * reads as no record, a latch survives a round trip whole, and the record — a fact about this phone's ROM — is never
 * carried by a backup to another phone.
 */
class DirectHostStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val latched =
        DirectHostRecord(stamp = "35|gsi-over-vendor", since = 1_700_000L, sinceElapsed = 42_000L, fails = 2, latchedAt = 1_800_000L)

    @Test
    fun aPhoneThatNeverHostedReadsAsNoRecord() =
        runTest {
            val file = File(tmp.root, "settings.preferences_pb")
            val store = DirectHostStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })

            assertEquals(DirectHostRecord.NONE, store.read())
        }

    @Test
    fun aLatchedRecordRoundTripsEveryField() =
        runTest {
            val file = File(tmp.root, "settings.preferences_pb")
            val store = DirectHostStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })

            store.write(latched)

            assertEquals(latched, store.read())
        }

    @Test
    fun noKeyTheGuardWritesRidesABackup() =
        runTest {
            val file = File(tmp.root, "settings.preferences_pb")
            val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
            DirectHostStore(data).write(latched)
            val written = data.data.first()

            // The five fields are all stored (a zero would be the default anyway), and every one is phone-bound.
            assertEquals(5, written.asMap().size)
            written.asMap().keys.forEach { key ->
                assertTrue("${key.name} would ride a backup", SettingsSnapshot.isTransient(key.name))
            }
            assertTrue(SettingsSnapshot.strip(written).asMap().isEmpty())
        }
}
