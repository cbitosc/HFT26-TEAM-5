package app.getknit.knit.debug.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.getknit.knit.call.CallManager
import app.getknit.knit.call.CallPhase
import app.getknit.knit.call.CallState
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.mesh.protocol.CallPayload
import app.getknit.knit.ui.theme.KnitTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The one call screen (experimental, debug only): incoming Answer/Decline, then status, mute, speaker and hang up.
 * Shows over the lock screen for the full-screen ring. While it is in the foreground it starts [CallService], the
 * microphone's foreground service, once the call is live and RECORD_AUDIO is granted.
 */
class CallActivity :
    ComponentActivity(),
    KoinComponent {
    private val calls: CallManager by inject()
    private val peers: PeerRepository by inject()
    private var pendingAnswer: String? = null
    private var serviceStarted = false

    private val micRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val id = pendingAnswer
            pendingAnswer = null
            if (granted && id != null) {
                answer(id)
            } else if (!granted) {
                Toast.makeText(this, "Knit needs the microphone to answer", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        handle(intent)
        lifecycleScope.launch {
            calls.state.collect { state ->
                when {
                    state == null -> {
                        finish()
                    }

                    state.phase == CallPhase.Ended -> {
                        delay(ENDED_LINGER_MS)
                        finish()
                    }

                    state.phase != CallPhase.Alerting -> {
                        startService()
                    }
                }
            }
        }
        setContent {
            KnitTheme {
                val state by calls.state.collectAsState()
                var name by remember { mutableStateOf("") }
                LaunchedEffect(state?.peerId) { state?.peerId?.let { name = callerName(peers, it) } }
                CallScreen(
                    state = state,
                    name = name,
                    onAnswer = { state?.id?.let(::answer) },
                    onDecline = { state?.id?.let { id -> lifecycleScope.launch { calls.decline(id) } } },
                    onHangUp = { lifecycleScope.launch { calls.hangUp() } },
                    onMute = { on -> lifecycleScope.launch { calls.setMuted(on) } },
                    onSpeaker = { on -> lifecycleScope.launch { calls.setSpeaker(on) } },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_ANSWER) intent.getStringExtra(EXTRA_ID)?.let(::answer)
    }

    private fun micGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun answer(id: String) {
        if (!micGranted()) {
            pendingAnswer = id
            micRequest.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        lifecycleScope.launch { if (calls.answer(id)) startService() }
    }

    private fun startService() {
        if (serviceStarted || !micGranted()) return
        serviceStarted = true
        ContextCompat.startForegroundService(this, Intent(this, CallService::class.java))
    }

    companion object {
        const val ACTION_ANSWER = "app.getknit.knit.call.ANSWER"
        private const val EXTRA_ID = "call_id"
        private const val ENDED_LINGER_MS = 1_500L

        fun intent(
            context: Context,
            id: String,
        ): Intent = Intent(context, CallActivity::class.java).putExtra(EXTRA_ID, id)
    }
}

@Composable
private fun CallScreen(
    state: CallState?,
    name: String,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onMute: (Boolean) -> Unit,
    onSpeaker: (Boolean) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(64.dp))
                Text(name, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(statusOf(state), style = MaterialTheme.typography.bodyLarge)
                Text("Experimental — internal testing", style = MaterialTheme.typography.labelSmall)
            }
            if (state?.phase == CallPhase.Alerting) {
                Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                    RoundButton(Icons.Filled.CallEnd, "Decline", Color(0xFFC62828), onDecline)
                    RoundButton(Icons.Filled.Call, "Answer", Color(0xFF2E7D32), onAnswer)
                }
            } else if (state != null && state.phase != CallPhase.Ended) {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledIconToggleButton(checked = state.muted, onCheckedChange = onMute) {
                        Icon(if (state.muted) Icons.Filled.MicOff else Icons.Filled.Mic, contentDescription = "Mute")
                    }
                    RoundButton(Icons.Filled.CallEnd, "Hang up", Color(0xFFC62828), onHangUp)
                    FilledIconToggleButton(checked = state.speaker, onCheckedChange = onSpeaker) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Speaker")
                    }
                }
            } else {
                Spacer(Modifier.height(72.dp))
            }
        }
    }
}

@Composable
private fun RoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.height(72.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White),
    ) {
        Icon(icon, contentDescription = label)
    }
}

@Composable
private fun statusOf(state: CallState?): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val connectedAt = state?.connectedAt
    if (state?.phase == CallPhase.Connected && connectedAt != null) {
        // Ticks only while connected: the screen is never under a Compose test, and leaves on Ended.
        LaunchedEffect(connectedAt) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
        val seconds = ((now - connectedAt) / 1_000).coerceAtLeast(0)
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }
    return when (state?.phase) {
        null -> ""
        CallPhase.Calling -> "Calling…"
        CallPhase.Ringing -> "Ringing…"
        CallPhase.Alerting -> "Incoming call"
        CallPhase.Connecting -> "Connecting…"
        CallPhase.Connected -> "Connected"
        CallPhase.Ended -> "Call ended — ${reasonOf(state.reason)}"
    }
}

private fun reasonOf(reason: Int?): String =
    when (reason) {
        CallPayload.REASON_USER -> "hung up"
        CallPayload.REASON_BUSY -> "busy"
        CallPayload.REASON_TIMEOUT -> "no answer"
        CallPayload.REASON_NO_ROUTE -> "no route between the phones"
        CallPayload.REASON_MEDIA -> "media not supported"
        else -> "error"
    }
