package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.getknit.knit.mesh.lora.AirBooking
import app.getknit.knit.mesh.lora.LoraPlaneSnapshot
import app.getknit.knit.mesh.lora.SeenStamp
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The LoRa plane's limiters over the settings DataStore: nothing stored and an unreadable blob both read as no
 * snapshot (the plane then starts on a clean window, costing one window at most), a snapshot round-trips whole, and
 * one a newer build wrote with a field this one does not know still loads what this build does know — those
 * limiters are still owed their air.
 */
class LoraPlaneStateStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val counter = AtomicInteger()

    private fun TestScope.newSettings(): SettingsStore {
        val file = File(tmp.root, "settings-${counter.incrementAndGet()}.preferences_pb")
        return SettingsStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
    }

    private val snapshot =
        LoraPlaneSnapshot(
            savedAtWall = 1_700_000_000_000L,
            air = listOf(AirBooking(atWall = 1_699_999_990_000L, ms = 412L, bucket = "CHAT")),
            selfProfileAtWall = 1_699_999_000_000L,
            profileSeen = listOf(SeenStamp("a1b2c3", 1_699_998_000_000L)),
            dmProbes = listOf(SeenStamp("d4e5f6", 1_699_997_000_000L)),
        )

    @Test
    fun nothingStoredLoadsAsNoSnapshot() =
        runTest {
            assertNull(LoraPlaneStateStore(newSettings()).load())
        }

    @Test
    fun aSnapshotRoundTripsWhole() =
        runTest {
            val settings = newSettings()
            LoraPlaneStateStore(settings).save(snapshot)

            // A fresh store over the same settings: what is read back is what the DataStore holds, not a cached copy.
            assertEquals(snapshot, LoraPlaneStateStore(settings).load())
        }

    @Test
    fun anUnreadableBlobLoadsAsNoSnapshot() =
        runTest {
            val settings = newSettings()
            // A write cut short: the start of a valid snapshot's JSON, no more.
            settings.setLoraPlaneState("""{"savedAtWall":1700000000000,"air":[{"atWall":""")

            assertNull(LoraPlaneStateStore(settings).load())
        }

    @Test
    fun aSnapshotANewerBuildWroteStillLoadsWhatThisOneKnows() =
        runTest {
            val settings = newSettings()
            settings.setLoraPlaneState(
                """{"savedAtWall":1700000000000,"air":[{"atWall":1699999990000,"ms":412,"bucket":"CHAT"}],""" +
                    """"meshCoreWindows":[{"id":7,"spent":3}]}""",
            )

            assertEquals(
                LoraPlaneSnapshot(
                    savedAtWall = 1_700_000_000_000L,
                    air = listOf(AirBooking(atWall = 1_699_999_990_000L, ms = 412L, bucket = "CHAT")),
                ),
                LoraPlaneStateStore(settings).load(),
            )
        }
}
