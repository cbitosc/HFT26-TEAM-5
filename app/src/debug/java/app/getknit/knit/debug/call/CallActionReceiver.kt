package app.getknit.knit.debug.call

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.getknit.knit.call.CallManager
import app.getknit.knit.di.isKoinStarted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/** Decline and Hang up from a call notification (experimental, debug only). Answer opens [CallActivity] instead. */
class CallActionReceiver :
    BroadcastReceiver(),
    KoinComponent {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // A tap that lands in a process with no graph has nothing to act on (NotificationActionReceiver's guard).
        if (!isKoinStarted()) return
        val calls = get<CallManager>()
        val pending = goAsync()
        get<CoroutineScope>().launch {
            try {
                when (intent.action) {
                    ACTION_DECLINE -> intent.getStringExtra(EXTRA_ID)?.let { calls.decline(it) }
                    ACTION_HANGUP -> calls.hangUp()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DECLINE = "app.getknit.knit.call.DECLINE"
        const val ACTION_HANGUP = "app.getknit.knit.call.HANGUP"
        private const val EXTRA_ID = "call_id"

        fun pending(
            context: Context,
            action: String,
            id: String,
        ): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                Intent(context, CallActionReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
                CallNotifications.FLAGS,
            )
    }
}
