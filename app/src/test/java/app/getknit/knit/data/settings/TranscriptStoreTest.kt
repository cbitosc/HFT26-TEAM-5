package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.getknit.knit.data.backup.SettingsSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The speech model's choices over a real Preferences DataStore (ADR 2026-10.nz3g): transcripts on and captions off
 * until the person says otherwise, the walkie card's offer dismissed for good, and — unlike the phone-bound stores
 * beside it — every one of them the person's, so a backup carries them.
 */
class TranscriptStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val counter = AtomicInteger()

    private fun TestScope.newData() =
        File(tmp.root, "settings-${counter.incrementAndGet()}.preferences_pb").let { file ->
            PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        }

    @Test
    fun transcriptsStartOnWithTheOfferStandingAndCaptionsOff() =
        runTest {
            val store = TranscriptStore(newData())

            assertTrue(store.enabled.first())
            assertFalse(store.offerDismissed.first())
            assertFalse(store.liveCaptions.first())
        }

    @Test
    fun eachChoiceRoundTripsOnItsOwnKey() =
        runTest {
            val store = TranscriptStore(newData())

            store.setEnabled(false)
            assertFalse(store.enabled.first())
            assertFalse("turning transcripts off touched the captions", store.liveCaptions.first())

            store.setLiveCaptions(true)
            assertTrue(store.liveCaptions.first())
            assertFalse(store.enabled.first())

            store.dismissOffer()
            assertTrue(store.offerDismissed.first())

            store.setEnabled(true)
            store.setLiveCaptions(false)
            assertTrue(store.enabled.first())
            assertFalse(store.liveCaptions.first())
            assertTrue("a dismissal is never cleared", store.offerDismissed.first())
        }

    @Test
    fun theChoicesRideABackup() =
        runTest {
            val data = newData()
            val store = TranscriptStore(data)
            store.setEnabled(false)
            store.setLiveCaptions(true)
            store.dismissOffer()
            val written = data.data.first()

            assertEquals(3, written.asMap().size)
            assertEquals(written.asMap(), SettingsSnapshot.strip(written).asMap())
        }
}
