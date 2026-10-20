package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** C-3.11-3: the chat picks its first call by the route until the person prefers mesh calls. */
class CallPreferenceStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val counter = AtomicInteger()

    private fun TestScope.newStore(): CallPreferenceStore {
        val file = File(tmp.root, "settings-${counter.incrementAndGet()}.preferences_pb")
        return CallPreferenceStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
    }

    @Test
    fun meshCallsAreNotPreferredByDefault() =
        runTest {
            assertFalse(newStore().preferMesh.first())
        }

    @Test
    fun theSwitchRoundTrips() =
        runTest {
            val store = newStore()
            store.setPreferMesh(true)
            assertTrue(store.preferMesh.first())
            store.setPreferMesh(false)
            assertFalse(store.preferMesh.first())
        }
}
