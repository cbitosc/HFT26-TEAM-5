package app.getknit.knit.debug.call

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.getknit.knit.R
import app.getknit.knit.call.CallManager
import app.getknit.knit.call.CallPhase
import app.getknit.knit.call.CallState
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Keeps the microphone while a call is live and Knit is not on screen (experimental, debug only): a foreground
 * service of type `microphone`, started by [CallActivity] while it is in the foreground — the only place Android 14+
 * lets a microphone service start — and only once RECORD_AUDIO is granted. Stops itself when the call ends.
 *
 * Its notification is the way back to a call the user left: the ongoing CallStyle with the peer's face, where the call
 * stands (the timer once connected), Hang up, and Mute and Speaker, each naming what a tap will do. It is posted
 * again on every change of [CallManager.state], so those two always show the call as it is.
 */
class CallService :
    LifecycleService(),
    KoinComponent {
    private val calls: CallManager by inject()
    private val faces: CallFaces by inject()
    private var watching = false

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        // First, always: a started foreground service that stops before claiming the state is a crash.
        claim(calls.state.value, null)
        if (!watching) {
            watching = true
            lifecycleScope.launch {
                calls.state.collect { state ->
                    if (state == null || state.phase == CallPhase.Ended) {
                        ServiceCompat.stopForeground(this@CallService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        claim(state, faces.of(state.peerId))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun claim(
        state: CallState?,
        face: CallFace?,
    ) {
        val id = state?.id.orEmpty()
        val person = face?.person ?: Person.Builder().setName(UNNAMED).build()
        val notification =
            NotificationCompat
                .Builder(this, CallNotifications.CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_stat_mesh)
                .setContentTitle(face?.name ?: UNNAMED)
                .setContentText(textOf(state))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setColor(ONGOING_GREEN)
                .setColorized(true)
                .setContentIntent(PendingIntent.getActivity(this, 2, CallActivity.intent(this, id), CallNotifications.FLAGS))
                .setStyle(
                    NotificationCompat.CallStyle.forOngoingCall(
                        person,
                        CallActionReceiver.pending(this, CallActionReceiver.ACTION_HANGUP, id),
                    ),
                ).apply {
                    state?.connectedAt?.let {
                        setWhen(it)
                        setShowWhen(true)
                        setUsesChronometer(true)
                    }
                    if (state != null) {
                        addAction(
                            action(
                                if (state.muted) R.drawable.ic_call_mic else R.drawable.ic_call_mic_off,
                                if (state.muted) "Unmute" else "Mute",
                                CallActionReceiver.ACTION_MUTE,
                                id,
                                !state.muted,
                            ),
                        )
                        addAction(
                            action(
                                if (state.speaker) R.drawable.ic_call_earpiece else R.drawable.ic_call_speaker,
                                if (state.speaker) "Earpiece" else "Speaker",
                                CallActionReceiver.ACTION_SPEAKER,
                                id,
                                !state.speaker,
                            ),
                        )
                    }
                }.build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, CallNotifications.ID + 1, notification, type)
        } catch (e: SecurityException) {
            // RECORD_AUDIO revoked between the check and here, or started from the background: nothing to hold.
            Log.w("KnitCall", "call service refused: ${e.message}")
        }
    }

    // Each names what a tap does, with the icon of where it leads, and carries the state it sets (not a toggle), so a
    // tap on a notification a beat behind the call can't flip it the wrong way.
    private fun action(
        icon: Int,
        label: String,
        kind: String,
        id: String,
        on: Boolean,
    ): NotificationCompat.Action =
        NotificationCompat.Action
            .Builder(IconCompat.createWithResource(this, icon), label, CallActionReceiver.pending(this, kind, id, on))
            .build()

    private fun textOf(state: CallState?): String =
        when (state?.phase) {
            CallPhase.Calling -> "Calling…"
            CallPhase.Ringing -> "Ringing…"
            CallPhase.Connecting -> "Connecting…"
            CallPhase.Connected -> if (state.muted) "Voice call · muted" else "Voice call"
            else -> "Knit call"
        }

    private companion object {
        const val UNNAMED = "Knit call"

        // The ongoing-call green the system's own call chip uses, dark enough for white text.
        const val ONGOING_GREEN = 0xFF137333.toInt()
    }
}
