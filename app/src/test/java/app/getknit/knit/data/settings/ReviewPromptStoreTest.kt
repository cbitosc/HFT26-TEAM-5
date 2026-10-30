package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** The rate prompt's record: an attempt stamps and counts, and the bridge's reset clears all three keys. */
class ReviewPromptStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val counter = AtomicInteger()

    private fun TestScope.newStore(): ReviewPromptStore {
        val file = File(tmp.root, "settings-${counter.incrementAndGet()}.preferences_pb")
        return ReviewPromptStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
    }

    @Test
    fun recordAttemptStampsTheTimeAndIncrementsTheLifetimeCount() =
        runTest {
            val store = newStore()
            store.recordAttempt(now = 1_000L)
            store.recordAttempt(now = 2_000L)
            assertEquals(2_000L, store.lastAttemptAt.first())
            assertEquals(2L, store.attemptCount.first())
        }

    @Test
    fun clearResetsEngagementAttemptTimeAndCount() =
        runTest {
            val store = newStore()
            store.setEngagementStartedAt(500L)
            store.recordAttempt(now = 1_000L)

            store.clear()

            assertEquals(0L, store.engagementStartedAt.first())
            assertEquals(0L, store.lastAttemptAt.first())
            assertEquals(0L, store.attemptCount.first())
        }
}
