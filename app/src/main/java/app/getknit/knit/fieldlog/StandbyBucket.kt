package app.getknit.knit.fieldlog

import android.annotation.SuppressLint
import android.app.usage.UsageStatsManager

// Hidden platform constants the call still returns: an app on the power-save allowlist, and one never used.
private const val STANDBY_BUCKET_EXEMPTED = 5
private const val STANDBY_BUCKET_NEVER = 50
private const val NO_BUCKET = -1

/**
 * The app standby bucket `UsageStatsManager.getAppStandbyBucket` answers for this app, by its platform value.
 * EXEMPTED and NEVER are hidden constants the call can still return; anything else is OTHER.
 */
enum class StandbyBucket(
    private val value: Int,
) {
    EXEMPTED(STANDBY_BUCKET_EXEMPTED),
    ACTIVE(UsageStatsManager.STANDBY_BUCKET_ACTIVE),
    WORKING_SET(UsageStatsManager.STANDBY_BUCKET_WORKING_SET),
    FREQUENT(UsageStatsManager.STANDBY_BUCKET_FREQUENT),
    RARE(UsageStatsManager.STANDBY_BUCKET_RARE),

    // API 30's constant, inlined: on API 29 the call never answers it.
    @SuppressLint("InlinedApi")
    RESTRICTED(UsageStatsManager.STANDBY_BUCKET_RESTRICTED),
    NEVER(STANDBY_BUCKET_NEVER),
    OTHER(NO_BUCKET),
    ;

    companion object {
        fun of(value: Int): StandbyBucket = entries.firstOrNull { it != OTHER && it.value == value } ?: OTHER
    }
}
