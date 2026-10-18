package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The rate prompt's record (`review/ReviewPrompter`, gated by [app.getknit.knit.review.ReviewPromptPolicy]), over
 * the settings DataStore rather than grown into [SettingsStore]: three keys under `review_`, read and written by the
 * prompter alone (and reset by the debug bridge).
 */
class ReviewPromptStore(
    private val dataStore: DataStore<Preferences>,
) {
    /**
     * Local-clock time the first peer message was observed (0 until then) — the start of the engagement window.
     * Deliberately a locally-stamped watermark rather than anything derived from a message's `sentAt`, which is the
     * sender's skewable clock.
     */
    val engagementStartedAt: Flow<Long> = dataStore.data.map { it[KEY_ENGAGEMENT_STARTED_AT] ?: 0L }

    /** Local-clock time of the last rate-prompt shown (0 = never). See [recordAttempt]. */
    val lastAttemptAt: Flow<Long> = dataStore.data.map { it[KEY_LAST_ATTEMPT_AT] ?: 0L }

    /** Lifetime rate-prompts shown — we don't record the user's choice, so shown-count is all we keep. */
    val attemptCount: Flow<Long> = dataStore.data.map { it[KEY_ATTEMPT_COUNT] ?: 0L }

    suspend fun setEngagementStartedAt(value: Long) = dataStore.edit { it[KEY_ENGAGEMENT_STARTED_AT] = value }

    /** Stamps the attempt time and bumps the lifetime count in one transaction. */
    suspend fun recordAttempt(now: Long) =
        dataStore.edit {
            it[KEY_LAST_ATTEMPT_AT] = now
            it[KEY_ATTEMPT_COUNT] = (it[KEY_ATTEMPT_COUNT] ?: 0L) + 1
        }

    /** Clears all review-prompt state (debug bridge reset). */
    suspend fun clear() =
        dataStore.edit {
            it.remove(KEY_ENGAGEMENT_STARTED_AT)
            it.remove(KEY_LAST_ATTEMPT_AT)
            it.remove(KEY_ATTEMPT_COUNT)
        }

    private companion object {
        val KEY_ENGAGEMENT_STARTED_AT = longPreferencesKey("review_engagement_started_at")
        val KEY_LAST_ATTEMPT_AT = longPreferencesKey("review_last_attempt_at")
        val KEY_ATTEMPT_COUNT = longPreferencesKey("review_attempt_count")
    }
}
