package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Low-power mode's switch over a real Preferences DataStore, as [SettingsStoreTest] runs the store. */
class LowPowerSwitchTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val counter = AtomicInteger()

    @Test
    fun `low-power mode writes its own key and nothing else`() =
        runTest {
            // ADR 2026-10.d44b: the mode is an overlay, so switching it on and off leaves every other key as it was.
            val file = File(tmp.root, "settings-${counter.incrementAndGet()}.preferences_pb")
            val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
            val store = SettingsStore(data)
            val switch = LowPowerSwitch(data)
            store.setDynamicColor(true)
            store.setLinkPreviewsEnabled(true)
            val before = data.data.first().asMap()

            switch.set(true)
            assertTrue(switch.on.first())
            val key = booleanPreferencesKey("low_power_mode")
            val on = data.data.first().asMap()
            assertEquals(true, on[key])
            assertEquals(before, on - key)

            switch.set(false)
            assertFalse(switch.on.first())
            assertEquals(before, data.data.first().asMap())
        }
}
