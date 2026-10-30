package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.getknit.knit.call.CaptureProfile
import app.getknit.knit.call.CaptureReason
import app.getknit.knit.call.CaptureRecord
import app.getknit.knit.data.backup.SettingsSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The call microphone's verdict over a real Preferences DataStore (ADR 2026-10.kw29): no verdict reads as the
 * call-tuned path, a verdict round-trips, a cleared reason does not linger, a name this build does not know reads as
 * no verdict rather than a crash, and none of it — another phone's ROM is not this one's — rides a backup.
 */
class CaptureStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val silent =
        CaptureRecord(stamp = "35|gsi", profile = CaptureProfile.FALLBACK, reason = CaptureReason.SILENT_CALL, at = 1_700_000L)

    @Test
    fun aPhoneWithNoVerdictRecordsOnTheStandardPath() =
        runTest {
            val file = File(tmp.root, "settings.preferences_pb")
            val store = CaptureStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })

            assertEquals(CaptureRecord.NONE, store.read())
        }

    @Test
    fun aVerdictRoundTripsEveryField() =
        runTest {
            val file = File(tmp.root, "settings.preferences_pb")
            val store = CaptureStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })

            store.write(silent)

            assertEquals(silent, store.read())
        }

    @Test
    fun writingNoReasonClearsTheOneStoredBefore() =
        runTest {
            val file = File(tmp.root, "settings.preferences_pb")
            val store = CaptureStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
            store.write(silent)

            store.write(silent.copy(profile = CaptureProfile.STANDARD, reason = null, at = 1_800_000L))

            val read = store.read()
            assertNull("the old reason outlived the verdict it explained", read.reason)
            assertEquals(CaptureProfile.STANDARD, read.profile)
            assertEquals(1_800_000L, read.at)
        }

    @Test
    fun aNameThisBuildDoesNotKnowReadsAsNoVerdict() =
        runTest {
            val file = File(tmp.root, "settings.preferences_pb")
            val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
            val store = CaptureStore(data)
            store.write(silent)
            // What a newer build might have written: a profile and a reason this one has no entry for.
            data.edit {
                it[stringPreferencesKey("call_capture_profile")] = "BLUETOOTH_ONLY"
                it[stringPreferencesKey("call_capture_reason")] = "OEM_QUIRK"
            }

            val read = store.read()
            assertEquals(CaptureProfile.STANDARD, read.profile)
            assertNull(read.reason)
            assertEquals("35|gsi", read.stamp)
        }

    @Test
    fun noKeyTheVerdictWritesRidesABackup() =
        runTest {
            val file = File(tmp.root, "settings.preferences_pb")
            val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
            CaptureStore(data).write(silent)
            val written = data.data.first()

            assertEquals(4, written.asMap().size)
            written.asMap().keys.forEach { key ->
                assertTrue("${key.name} would ride a backup", SettingsSnapshot.isTransient(key.name))
            }
            assertTrue(SettingsSnapshot.strip(written).asMap().isEmpty())
        }
}
