package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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

/** C-3.3-7: both kinds are taken until switched off, and each switch moves its own kind only. */
class IncomingCallStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val counter = AtomicInteger()

    private fun TestScope.newStore(): IncomingCallStore {
        val file = File(tmp.root, "settings-${counter.incrementAndGet()}.preferences_pb")
        return IncomingCallStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
    }

    @Test
    fun bothKindsAreTakenByDefault() =
        runTest {
            assertEquals(CallIntake(live = true, walkie = true), newStore().intake.first())
        }

    @Test
    fun eachSwitchRefusesItsOwnKindOnly() =
        runTest {
            val store = newStore()
            store.setLive(false)
            store.intake.first().let {
                assertFalse(it.takes(walkie = false))
                assertTrue(it.takes(walkie = true))
            }

            store.setLive(true)
            store.setWalkie(false)
            store.intake.first().let {
                assertTrue(it.takes(walkie = false))
                assertFalse(it.takes(walkie = true))
            }
        }
}
