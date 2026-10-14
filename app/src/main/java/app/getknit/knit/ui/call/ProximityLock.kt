package app.getknit.knit.ui.call

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * The proximity wake lock a live call holds at the ear (docs/VOICE_CALLS.md C-3.13-11, ADR 2026-10.wbua): while it is
 * held the platform turns the screen off and ignores touches whenever something is near the sensor, over the call
 * screen's keep-screen-on too. A self-managed Telecom call gets nothing of the system dialer's, so [CallService] holds
 * this for [app.getknit.knit.call.CallAudioPolicy.screenOffNearEar]. A phone without the sensor makes it a no-op.
 */
internal class ProximityLock(
    context: Context,
) {
    private val lock: PowerManager.WakeLock? =
        context.getSystemService(PowerManager::class.java)?.let { power ->
            if (power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, TAG).apply { setReferenceCounted(false) }
            } else {
                Log.i(LOG_TAG, "no proximity wake lock on this phone; the screen stays on at the ear")
                null
            }
        }

    /**
     * Holds the lock, or lets it go at once: a route that left the earpiece (the loudspeaker, a headset) wants the
     * screen back now, near or not.
     */
    fun hold(on: Boolean) {
        val lock = lock ?: return
        if (on == lock.isHeld) return
        // No timeout: the call bounds it, and [end] runs when the call does.
        if (on) lock.acquire() else lock.release()
    }

    /** Lets the lock go as a call ends: a screen dark at the ear stays dark until the phone leaves it. */
    fun end() {
        val lock = lock ?: return
        if (lock.isHeld) lock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
    }

    private companion object {
        const val TAG = "knit:call-proximity"
        const val LOG_TAG = "KnitCall"
    }
}
