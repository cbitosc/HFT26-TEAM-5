package app.getknit.knit.debug.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.getknit.knit.call.CallManager
import app.getknit.knit.call.CallPhase
import app.getknit.knit.call.CallState
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.mesh.protocol.CallPayload
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.preview.PREVIEW_NOW
import app.getknit.knit.ui.theme.KnitTheme
import app.getknit.knit.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The one call screen (experimental, debug only): the peer's face and name, incoming Answer/Decline, then status, the
 * route (direct or relayed), mute, speaker and hang up. [CallScreen] is the stateless half the previews draw.
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
        setContent { KnitTheme { Host() } }
    }

    @Composable
    private fun Host() {
        val state by calls.state.collectAsState()
        val peerId = state?.peerId
        var name by remember { mutableStateOf("") }
        var avatarHash by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(peerId) {
            if (peerId == null) return@LaunchedEffect
            name = callerName(peers, peerId)
            avatarHash = peers.find(peerId)?.avatarHash
        }
        // The selected candidate pair, read while connected: it can move (a relay pair first, a direct one later).
        val connected = state?.phase == CallPhase.Connected
        var route by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(connected) {
            while (connected) {
                route = calls.route()
                delay(ROUTE_POLL_MS)
            }
        }
        CallScreen(
            state = state,
            name = name,
            avatarHash = avatarHash,
            route = route,
            onAnswer = { state?.id?.let(::answer) },
            onDecline = { state?.id?.let { id -> lifecycleScope.launch { calls.decline(id) } } },
            onHangUp = { lifecycleScope.launch { calls.hangUp() } },
            onMute = { on -> lifecycleScope.launch { calls.setMuted(on) } },
            onSpeaker = { on -> lifecycleScope.launch { calls.setSpeaker(on) } },
        )
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
        private const val ROUTE_POLL_MS = 2_000L

        fun intent(
            context: Context,
            id: String,
        ): Intent = Intent(context, CallActivity::class.java).putExtra(EXTRA_ID, id)
    }
}

// Red and green as every phone's call screen draws them, whatever the theme: the hang-up must read as one at a glance.
internal val HangUpRed = Color(0xFFD93025)
private val AnswerGreen = Color(0xFF1E8E3E)
private val AvatarSize = 144.dp
private val RingReach = 40.dp
private val ActionSize = 72.dp
private val ToggleSize = 64.dp
private const val RING_MS = 1_800
private const val RING_ALPHA = 0.28f
private const val ENDED_ALPHA = 0.5f
private val RINGING = setOf(CallPhase.Calling, CallPhase.Ringing, CallPhase.Alerting, CallPhase.Connecting)

/** The call screen proper: the peer's face and name, where the call stands, and the controls its phase offers. */
@Composable
internal fun CallScreen(
    state: CallState?,
    name: String,
    avatarHash: String?,
    route: String?,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onMute: (Boolean) -> Unit,
    onSpeaker: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    clock: () -> Long = System::currentTimeMillis,
) {
    val phase = state?.phase
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Experimental · internal testing",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(0.6f))
            RingingAvatar(pulsing = phase in RINGING, dimmed = phase == CallPhase.Ended) {
                Avatar(
                    avatarHash = avatarHash,
                    name = name,
                    nodeId = state?.peerId,
                    size = AvatarSize,
                    textStyle = MaterialTheme.typography.displayLarge,
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                name,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                statusOf(state, clock),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            // One slot for the reason or the route, held empty otherwise so the face doesn't jump as the call moves on.
            Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                if (phase == CallPhase.Ended) {
                    reasonOf(state.reason)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else if (phase == CallPhase.Connected && route != null) {
                    RoutePill(route)
                }
            }
            Spacer(Modifier.weight(1f))
            Controls(state, onAnswer, onDecline, onHangUp, onMute, onSpeaker)
        }
    }
}

/** The avatar, with rings rippling out of it while the call is still being set up. */
@Composable
private fun RingingAvatar(
    pulsing: Boolean,
    dimmed: Boolean,
    avatar: @Composable () -> Unit,
) {
    val ring = MaterialTheme.colorScheme.primary
    Box(Modifier.size(AvatarSize + RingReach * 2), contentAlignment = Alignment.Center) {
        if (pulsing) {
            // Reduced motion keeps one still ring: the call is still ringing, it just doesn't move.
            val wave =
                if (LocalReduceMotion.current) {
                    0.5f
                } else {
                    val transition = rememberInfiniteTransition(label = "ring")
                    val animated by transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(RING_MS, easing = LinearEasing)),
                        label = "wave",
                    )
                    animated
                }
            Canvas(Modifier.matchParentSize()) {
                val inner = AvatarSize.toPx() / 2
                val reach = size.minDimension / 2 - inner
                listOf(wave, (wave + 0.5f) % 1f).forEach { t ->
                    drawCircle(ring.copy(alpha = RING_ALPHA * (1f - t)), radius = inner + reach * t)
                }
            }
        }
        Box(Modifier.alpha(if (dimmed) ENDED_ALPHA else 1f)) { avatar() }
    }
}

@Composable
private fun Controls(
    state: CallState?,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onMute: (Boolean) -> Unit,
    onSpeaker: (Boolean) -> Unit,
) {
    // A fixed band, so the face and name above don't jump when the controls change or go.
    Box(Modifier.fillMaxWidth().heightIn(min = 112.dp), contentAlignment = Alignment.BottomCenter) {
        if (state == null || state.phase == CallPhase.Ended) return@Box
        if (state.phase == CallPhase.Alerting) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                CallAction("Decline", Icons.Filled.CallEnd, HangUpRed, onDecline)
                CallAction("Answer", Icons.Filled.Call, AnswerGreen, onAnswer)
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom,
            ) {
                CallToggle("Mute", if (state.muted) Icons.Filled.MicOff else Icons.Filled.Mic, state.muted, onMute)
                CallAction("End", Icons.Filled.CallEnd, HangUpRed, onHangUp)
                CallToggle("Speaker", Icons.AutoMirrored.Filled.VolumeUp, state.speaker, onSpeaker)
            }
        }
    }
}

@Composable
private fun CallAction(
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(ActionSize),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(32.dp))
        }
        ButtonLabel(label)
    }
}

@Composable
private fun CallToggle(
    label: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconToggleButton(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(ToggleSize),
            shape = CircleShape,
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
        }
        ButtonLabel(label)
    }
}

/** The word under a button: the button already carries it as its description, so it is not read twice. */
@Composable
private fun ButtonLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 8.dp).clearAndSetSemantics {},
    )
}

/** Which way the audio goes, from the selected pair's candidate types ("relay/srflx"): through TURN or not. */
@Composable
private fun RoutePill(route: String) {
    val types = route.split('/')
    val relayed = "relay" in types
    val label =
        when {
            relayed -> "Relayed"
            types.all { it == "host" } -> "Direct · same network"
            else -> "Direct"
        }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                if (relayed) Icons.Outlined.Cloud else Icons.Outlined.Sensors,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun statusOf(
    state: CallState?,
    clock: () -> Long,
): String {
    val connectedAt = state?.connectedAt
    if (state?.phase == CallPhase.Connected && connectedAt != null) {
        var now by remember { mutableLongStateOf(clock()) }
        LaunchedEffect(connectedAt) {
            while (true) {
                now = clock()
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
        CallPhase.Alerting -> "Incoming voice call"
        CallPhase.Connecting -> "Connecting…"
        CallPhase.Connected -> "Connected"
        CallPhase.Ended -> "Call ended"
    }
}

/** Why it ended, when it is anything but somebody hanging up. */
private fun reasonOf(reason: Int?): String? =
    when (reason) {
        CallPayload.REASON_USER -> null
        CallPayload.REASON_BUSY -> "Busy"
        CallPayload.REASON_TIMEOUT -> "No answer"
        CallPayload.REASON_NO_ROUTE -> "No route between the phones"
        CallPayload.REASON_MEDIA -> "Media not supported"
        else -> "Something went wrong"
    }

private const val PREVIEW_PEER = "hzwyqdbj74hu6gkurmcyxomaca"

@Composable
private fun PreviewCall(
    phase: CallPhase,
    outgoing: Boolean = true,
    name: String = "Ada Lovelace",
    route: String? = null,
    muted: Boolean = false,
    speaker: Boolean = false,
    reason: Int? = null,
) = KnitPreview {
    CallScreen(
        state =
            CallState(
                id = "preview",
                peerId = PREVIEW_PEER,
                outgoing = outgoing,
                phase = phase,
                startedAt = PREVIEW_NOW - 90_000,
                connectedAt = (PREVIEW_NOW - 83_000).takeIf { phase == CallPhase.Connected },
                muted = muted,
                speaker = speaker,
                reason = reason,
            ),
        name = name,
        avatarHash = null,
        route = route,
        onAnswer = {},
        onDecline = {},
        onHangUp = {},
        onMute = {},
        onSpeaker = {},
        clock = { PREVIEW_NOW },
    )
}

@Preview(name = "Incoming", widthDp = 411, heightDp = 891)
@Composable
fun CallScreenIncomingPreview() = PreviewCall(CallPhase.Alerting, outgoing = false)

@Preview(name = "Incoming, long name", widthDp = 411, heightDp = 891)
@Composable
fun CallScreenLongNamePreview() = PreviewCall(CallPhase.Alerting, outgoing = false, name = "IMABETHENEXTTRILLIONARE🔥🔥🔥🔥")

@Preview(name = "Ringing", widthDp = 411, heightDp = 891)
@Composable
fun CallScreenRingingPreview() = PreviewCall(CallPhase.Ringing)

@Preview(name = "Connected", widthDp = 411, heightDp = 891)
@Preview(name = "Connected, dark", widthDp = 411, heightDp = 891, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun CallScreenConnectedPreview() = PreviewCall(CallPhase.Connected, route = "relay/srflx", muted = true)

@Preview(name = "Ended", widthDp = 411, heightDp = 891)
@Composable
fun CallScreenEndedPreview() = PreviewCall(CallPhase.Ended, reason = CallPayload.REASON_TIMEOUT)
