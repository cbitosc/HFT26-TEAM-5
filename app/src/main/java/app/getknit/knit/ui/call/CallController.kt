package app.getknit.knit.ui.call

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.getknit.knit.MainActivity
import app.getknit.knit.R
import app.getknit.knit.call.CallManager
import app.getknit.knit.call.CallPhase
import app.getknit.knit.call.CallState
import app.getknit.knit.call.MissedCall
import app.getknit.knit.data.PeerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The Android half of a call's alerting (experimental, `CALLS_WIRED`): rings an incoming call with a CallStyle
 * notification and a full-screen intent to [CallActivity], opens [CallActivity] for an outgoing one, and notifies a
 * missed call. Collects [CallManager] from process start ([app.getknit.knit.di.startCallsIfWired]), so a call rings
 * whether or not the UI was ever opened; the mesh service keeps the process (and its spool connection) alive.
 */
internal class CallController(
    private val context: Context,
    private val calls: CallManager,
    private val scope: CoroutineScope,
    private val faces: CallFaces,
    private val alertMs: Long,
) {
    @Volatile
    private var ringing: String? = null

    @Volatile
    private var opened: String? = null

    fun start() {
        CallNotifications.ensureChannels(context)
        askForFullScreen()
        scope.launch { calls.state.collect { onState(it) } }
        scope.launch { calls.missed.collect { onMissed(it) } }
    }

    private suspend fun onState(state: CallState?) {
        state ?: return
        when {
            state.phase == CallPhase.Alerting -> {
                if (ringing != state.id) {
                    ringing = state.id
                    ring(state)
                }
            }

            state.outgoing && state.phase == CallPhase.Calling -> {
                // The user just tapped Call, so Knit is in the foreground and may start an activity.
                if (opened != state.id) {
                    opened = state.id
                    context.startActivity(CallActivity.intent(context, state.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }

            ringing == state.id -> {
                ringing = null
                NotificationManagerCompat.from(context).cancel(CallNotifications.TAG_RING, CallNotifications.ID)
            }
        }
    }

    private suspend fun ring(state: CallState) {
        val face = faces.of(state.peerId)
        val screen = PendingIntent.getActivity(context, 0, CallActivity.intent(context, state.id), CallNotifications.FLAGS)
        val answer =
            PendingIntent.getActivity(
                context,
                1,
                CallActivity.intent(context, state.id).setAction(CallActivity.ACTION_ANSWER),
                CallNotifications.FLAGS,
            )
        val decline = CallActionReceiver.pending(context, CallActionReceiver.ACTION_DECLINE, state.id)
        val notification =
            NotificationCompat
                .Builder(context, CallNotifications.CHANNEL_RING)
                .setSmallIcon(R.drawable.ic_stat_mesh)
                .setContentTitle(face.name)
                // As the ongoing call's: CallStyle sets no large icon, and a watch's card draws only that.
                .setLargeIcon(face.icon.toIcon(context))
                .setContentText(if (state.walkie) "Knit walkie-talkie" else "Knit voice call")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setContentIntent(screen)
                .setFullScreenIntent(screen, true)
                // A lost CANCEL and a dead process must not leave the phone ringing (the manager's alert timeout).
                .setTimeoutAfter(alertMs)
                .setStyle(NotificationCompat.CallStyle.forIncomingCall(face.person, decline, answer))
                .build()
                .apply { flags = flags or Notification.FLAG_INSISTENT }
        CallNotifications.post(context, CallNotifications.TAG_RING, notification)
    }

    /**
     * Android 14+ grants a sideloaded build no full-screen intents, and nothing an app does can grant it: without it an
     * incoming call is a heads-up the locked phone never shows. So the status channel says so once per process, and
     * its tap opens the one system page that can allow it (or `adb shell appops set app.getknit.knit
     * USE_FULL_SCREEN_INTENT allow`).
     */
    private fun askForFullScreen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        if (context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() != false) return
        val settings =
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val notification =
            NotificationCompat
                .Builder(context, CallNotifications.CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_stat_mesh)
                .setContentTitle("Allow full-screen calls")
                .setContentText("Without it a call can't ring on the lock screen")
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(context, 3, settings, CallNotifications.FLAGS))
                .build()
        CallNotifications.post(context, CallNotifications.TAG_FULL_SCREEN, notification)
    }

    private suspend fun onMissed(missed: MissedCall) {
        val face = faces.of(missed.peerId)
        val open =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_ROUTE, "chat/${missed.peerId}")
        val notification =
            NotificationCompat
                .Builder(context, CallNotifications.CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_stat_mesh)
                .setContentTitle("Missed call")
                .setContentText(face.name)
                .setLargeIcon(face.icon.toIcon(context))
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                .setWhen(missed.at)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(context, missed.peerId.hashCode(), open, CallNotifications.FLAGS))
                .build()
        CallNotifications.post(context, "${CallNotifications.TAG_MISSED}${missed.peerId}", notification)
    }
}

/** The display name a call surface shows for [peerId], disambiguated like every other label in the app. */
internal suspend fun callerName(
    peers: PeerRepository,
    peerId: String,
): String = peers.labelIndex().labelFor(peerId, peers.find(peerId)?.name).text

/** The calls' notification channels and the one guarded post (`CALLS_WIRED`). */
internal object CallNotifications {
    const val CHANNEL_RING = "knit_calls"
    const val CHANNEL_STATUS = "knit_call_status"
    const val TAG_RING = "call:ring"
    const val TAG_MISSED = "call:missed:"
    const val TAG_FULL_SCREEN = "call:fullscreen"
    const val ID = 4_100
    const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun ensureChannels(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL_RING, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Calls (experimental)")
                .setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                ).setVibrationEnabled(true)
                .build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL_STATUS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("Call status (experimental)")
                .build(),
        )
    }

    fun post(
        context: Context,
        tag: String,
        notification: Notification,
    ) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        runCatching { manager.notify(tag, ID, notification) }
    }
}
