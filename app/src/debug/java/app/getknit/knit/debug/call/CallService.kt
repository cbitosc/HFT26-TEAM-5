package app.getknit.knit.debug.call

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.getknit.knit.R
import app.getknit.knit.call.CallManager
import app.getknit.knit.call.CallPhase
import app.getknit.knit.call.CallState
import app.getknit.knit.data.PeerRepository
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Keeps the microphone while a call is live and Knit is not on screen (experimental, debug only): a foreground
 * service of type `microphone`, started by [CallActivity] while it is in the foreground — the only place Android 14+
 * lets a microphone service start — and only once RECORD_AUDIO is granted. Its notification is the ongoing CallStyle
 * with Hang up. Stops itself when the call ends.
 */
class CallService :
    LifecycleService(),
    KoinComponent {
    private val calls: CallManager by inject()
    private val peers: PeerRepository by inject()
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
                        claim(state, callerName(peers, state.peerId))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun claim(
        state: CallState?,
        name: String?,
    ) {
        val person = Person.Builder().setName(name ?: "Knit call").build()
        val notification =
            NotificationCompat
                .Builder(this, CallNotifications.CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_stat_mesh)
                .setContentTitle(name ?: "Knit call")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        2,
                        CallActivity.intent(this, state?.id.orEmpty()),
                        CallNotifications.FLAGS,
                    ),
                ).apply {
                    state?.connectedAt?.let {
                        setWhen(it)
                        setUsesChronometer(true)
                    }
                }.setStyle(
                    NotificationCompat.CallStyle.forOngoingCall(
                        person,
                        CallActionReceiver.pending(this, CallActionReceiver.ACTION_HANGUP, state?.id.orEmpty()),
                    ),
                ).build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, CallNotifications.ID + 1, notification, type)
        } catch (e: SecurityException) {
            // RECORD_AUDIO revoked between the check and here, or started from the background: nothing to hold.
            Log.w("KnitCall", "call service refused: ${e.message}")
        }
    }
}
