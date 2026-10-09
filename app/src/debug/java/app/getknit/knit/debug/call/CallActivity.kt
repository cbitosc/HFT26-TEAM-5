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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.getknit.knit.call.CallManager
import app.getknit.knit.call.CallPhase
import app.getknit.knit.call.CallState
import app.getknit.knit.call.TalkLink
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.mesh.protocol.CallPayload
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.preview.PREVIEW_NOW
import app.getknit.knit.ui.theme.KnitTheme
import app.getknit.knit.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The one call screen (experimental, debug only): the peer's face and name, incoming Answer/Decline, then status, the
 * route (direct or relayed), mute, speaker and hang up, with a live diagnostics card ([CallDiagnostics]) the header's
 * toggle hides; a walkie session (§3.11) trades mute and speaker for a hold-to-talk button over [WalkieTalk].
 * [CallScreen] is the stateless half the previews draw.
 * Shows over the lock screen for the full-screen ring. While it is in the foreground it starts [CallService], the
 * microphone's foreground service, once the call is live and RECORD_AUDIO is granted.
 */
class CallActivity :
    ComponentActivity(),
    KoinComponent {
    private val calls: CallManager by inject()
    private val peers: PeerRepository by inject()
    private val media: WebRtcCallMedia by inject()

    // Null in a build with the walkie off (`di/CallWiring.WALKIE_WIRED`): no call is ever a walkie there.
    private val walkie: WalkieTalk? = getKoin().getOrNull()
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
        // The selected candidate pair and the media's stats, read while the media is up: the pair can move (a relay
        // pair first, a direct one later), and the stats are the diagnostics card's.
        val mediaUp = state?.walkie != true && (state?.phase == CallPhase.Connecting || state?.phase == CallPhase.Connected)
        var route by remember { mutableStateOf<String?>(null) }
        var stats by remember { mutableStateOf<CallStats?>(null) }
        LaunchedEffect(mediaUp) {
            while (mediaUp) {
                route = calls.route()
                stats = media.stats()
                delay(POLL_MS)
            }
        }
        val talk by (walkie?.talk ?: NO_TALK).collectAsState()
        val link by (walkie?.link ?: NO_LINK).collectAsState()
        CallScreen(
            state = state,
            name = name,
            avatarHash = avatarHash,
            route = route.takeIf { state?.walkie != true },
            stats = stats.takeIf { state?.walkie != true },
            onAnswer = { state?.id?.let(::answer) },
            onDecline = { state?.id?.let { id -> lifecycleScope.launch { calls.decline(id) } } },
            onHangUp = { lifecycleScope.launch { calls.hangUp() } },
            onMute = { on -> lifecycleScope.launch { calls.setMuted(on) } },
            onSpeaker = { on -> lifecycleScope.launch { calls.setSpeaker(on) } },
            talk = talk,
            link = link,
            onTalk = { lifecycleScope.launch { walkie?.press() } },
            onTalkSend = { lifecycleScope.launch { walkie?.release() } },
            onTalkCancel = { lifecycleScope.launch { walkie?.cancel() } },
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
        private const val POLL_MS = 1_000L

        fun intent(
            context: Context,
            id: String,
        ): Intent = Intent(context, CallActivity::class.java).putExtra(EXTRA_ID, id)
    }
}

// Red and green as every phone's call screen draws them, whatever the theme: the hang-up must read as one at a glance.
internal val HangUpRed = Color(0xFFD93025)
private val AnswerGreen = Color(0xFF1E8E3E)
private val TalkingGreen = Color(0xFF34A853)
private val AvatarSize = 144.dp

// With the diagnostics card up the face gives it room: smaller on a tall phone, beside the name on a short one.
private val CompactAvatarSize = 104.dp
private val CompactRingReach = 16.dp
private val InlineAvatarSize = 72.dp
private val StackedMinHeight = 760.dp
private val RingReach = 40.dp
private val ActionSize = 72.dp
private val TalkSize = 120.dp
private val NO_TALK = MutableStateFlow<Talk>(Talk.Idle())
private val NO_LINK = MutableStateFlow<TalkLink?>(null)
private val ToggleSize = 64.dp
private const val RING_MS = 1_800
private const val RING_ALPHA = 0.28f
private const val ENDED_ALPHA = 0.5f
private val RINGING = setOf(CallPhase.Calling, CallPhase.Ringing, CallPhase.Alerting, CallPhase.Connecting)
private val MEDIA_UP = setOf(CallPhase.Connecting, CallPhase.Connected)

/**
 * The call screen proper: the peer's face and name, where the call stands, the controls its phase offers and, while
 * the media is up, the diagnostics card for [stats] (shown until the header's toggle hides it).
 */
@Composable
internal fun CallScreen(
    state: CallState?,
    name: String,
    avatarHash: String?,
    route: String?,
    stats: CallStats?,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onMute: (Boolean) -> Unit,
    onSpeaker: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    clock: () -> Long = System::currentTimeMillis,
    talk: Talk = Talk.Idle(),
    link: TalkLink? = null,
    onTalk: () -> Unit = {},
    onTalkSend: () -> Unit = {},
    onTalkCancel: () -> Unit = {},
) {
    val phase = state?.phase
    var showStats by rememberSaveable { mutableStateOf(true) }
    val diagnostics = stats?.takeIf { showStats && phase in MEDIA_UP }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
            // With the card up, a phone too short for the stacked face puts it beside the name instead.
            val inline = diagnostics != null && maxHeight < StackedMinHeight
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Header(stats = if (phase in MEDIA_UP) showStats else null, onStats = { showStats = it })
                if (inline) {
                    Spacer(Modifier.height(8.dp))
                    InlineFace(state, name, avatarHash, route, clock)
                } else {
                    if (diagnostics != null) Spacer(Modifier.height(8.dp)) else Spacer(Modifier.weight(0.6f))
                    StackedFace(state, name, avatarHash, route, compact = diagnostics != null, clock)
                }
                if (diagnostics != null) {
                    // The room between the face and the controls; a short screen scrolls the card rather than clip it.
                    Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        CallDiagnostics(diagnostics, Modifier.verticalScroll(rememberScrollState()))
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (state?.walkie == true && phase == CallPhase.Connected) {
                    WalkieControls(state, name, talk, link, onHangUp, onTalk, onTalkSend, onTalkCancel)
                } else {
                    Controls(state, onAnswer, onDecline, onHangUp, onMute, onSpeaker)
                }
            }
        }
    }
}

/** The face as every call screen draws it: the avatar centred over the name, the status and the route. */
@Composable
private fun StackedFace(
    state: CallState?,
    name: String,
    avatarHash: String?,
    route: String?,
    compact: Boolean,
    clock: () -> Long,
) {
    val phase = state?.phase
    val size = if (compact) CompactAvatarSize else AvatarSize
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RingingAvatar(
            size = size,
            reach = if (compact) CompactRingReach else RingReach,
            pulsing = phase in RINGING,
            dimmed = phase == CallPhase.Ended,
        ) {
            Avatar(
                avatarHash = avatarHash,
                name = name,
                nodeId = state?.peerId,
                size = size,
                textStyle = if (compact) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge,
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
            } else if (phase == CallPhase.Connected && state.walkie) {
                WalkiePill()
            } else if (phase == CallPhase.Connected && route != null) {
                RoutePill(route)
            }
        }
    }
}

/** The face beside the name, status and route: what a short phone shows above the diagnostics card. */
@Composable
private fun InlineFace(
    state: CallState?,
    name: String,
    avatarHash: String?,
    route: String?,
    clock: () -> Long,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Avatar(
            avatarHash = avatarHash,
            name = name,
            nodeId = state?.peerId,
            size = InlineAvatarSize,
            textStyle = MaterialTheme.typography.headlineLarge,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                statusOf(state, clock),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state?.phase == CallPhase.Connected && state.walkie) {
                WalkiePill()
            } else if (state?.phase == CallPhase.Connected && route != null) {
                RoutePill(route)
            }
        }
    }
}

/** The experimental label, with the diagnostics card's toggle at its end while there is a card to show ([stats]). */
@Composable
private fun Header(
    stats: Boolean?,
    onStats: (Boolean) -> Unit,
) {
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Text(
            "Experimental · internal testing",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (stats != null) {
            IconToggleButton(checked = stats, onCheckedChange = onStats, modifier = Modifier.align(Alignment.CenterEnd)) {
                Icon(Icons.Outlined.Insights, contentDescription = if (stats) "Hide call diagnostics" else "Show call diagnostics")
            }
        }
    }
}

/** The avatar, with rings rippling out of it while the call is still being set up. */
@Composable
private fun RingingAvatar(
    size: Dp,
    reach: Dp,
    pulsing: Boolean,
    dimmed: Boolean,
    avatar: @Composable () -> Unit,
) {
    val ring = MaterialTheme.colorScheme.primary
    Box(Modifier.size(size + reach * 2), contentAlignment = Alignment.Center) {
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
                val inner = size.toPx() / 2
                val spread = this.size.minDimension / 2 - inner
                listOf(wave, (wave + 0.5f) % 1f).forEach { t ->
                    drawCircle(ring.copy(alpha = RING_ALPHA * (1f - t)), radius = inner + spread * t)
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

/**
 * A connected walkie session's controls (§3.11): whether a burst can reach the peer, where the last one stands, why
 * this is a walkie when a live call dropped to one, and the hold-to-talk button beside the hang-up.
 */
@Composable
private fun WalkieControls(
    state: CallState,
    name: String,
    talk: Talk,
    link: TalkLink?,
    onHangUp: () -> Unit,
    onTalk: () -> Unit,
    onTalkSend: () -> Unit,
    onTalkCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (link == TalkLink.NONE || link == TalkLink.MESH) {
            LinkNotice(link, name)
            Spacer(Modifier.height(12.dp))
        }
        if (state.degraded) {
            Text(
                "No live connection — switched to walkie-talkie",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(talkLine(talk, name), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        // The talk button takes the centre a call screen gives its red hang-up; the hang-up steps aside to the start.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
                CallAction("End", Icons.Filled.CallEnd, HangUpRed, onHangUp)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
                TalkButton(recording = talk is Talk.Recording, onTalk, onTalkSend, onTalkCancel)
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * Why a burst may not reach the peer (§3.11): no link this phone can see — the radios are asked for one meanwhile — or
 * only a path through other phones, which works but is slower and less sure.
 */
@Composable
private fun LinkNotice(
    link: TalkLink,
    name: String,
) {
    val none = link == TalkLink.NONE
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (none) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (none) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(if (none) Icons.Outlined.LinkOff else Icons.Outlined.Hub, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                if (none) "Not linked to $name yet. Linking…" else "No direct link to $name. Going through other phones",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun talkLine(
    talk: Talk,
    name: String,
): String =
    when (talk) {
        is Talk.Recording -> {
            val seconds = talk.elapsedMs / 1_000
            "Talking %d:%02d".format(seconds / 60, seconds % 60)
        }

        Talk.Sending -> {
            "Sending…"
        }

        Talk.Hearing -> {
            "$name is talking…"
        }

        is Talk.Idle -> {
            when (talk.note) {
                Talk.Note.SENT -> "Sent"
                Talk.Note.NO_MIC -> "The microphone is busy"
                Talk.Note.NOT_SENT -> "Couldn't send that one"
                Talk.Note.NO_LINK -> "Not linked, so that one didn't go"
                Talk.Note.TOO_SHORT, null -> "Hold to talk"
            }
        }
    }

/** Held, it records; let go, it sends; a gesture the system cancels drops the burst. TalkBack gets both as actions. */
@Composable
private fun TalkButton(
    recording: Boolean,
    onTalk: () -> Unit,
    onTalkSend: () -> Unit,
    onTalkCancel: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val talk by rememberUpdatedState(onTalk)
    val send by rememberUpdatedState(onTalkSend)
    val cancel by rememberUpdatedState(onTalkCancel)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            // Never the hang-up's red: green as the answer button, brighter while it records.
            color = if (recording) TalkingGreen else AnswerGreen,
            contentColor = Color.White,
            modifier =
                Modifier
                    .size(TalkSize)
                    .semantics {
                        contentDescription = "Hold to talk"
                        customActions =
                            listOf(
                                CustomAccessibilityAction("Start talking") { true.also { talk() } },
                                CustomAccessibilityAction("Stop and send") { true.also { send() } },
                            )
                    }.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                talk()
                                if (tryAwaitRelease()) send() else cancel()
                            },
                        )
                    },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(48.dp))
            }
        }
        ButtonLabel(if (recording) "Release to send" else "Hold to talk")
    }
}

/** Where a walkie session's audio goes: over the mesh in chunks as it is spoken, never a live media path. */
@Composable
private fun WalkiePill() {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Outlined.Podcasts, contentDescription = null, modifier = Modifier.size(16.dp))
            Text("Walkie-talkie · mesh", style = MaterialTheme.typography.labelLarge)
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

/**
 * Which way the audio goes, from the selected pair's candidate types ("relay/srflx"): through TURN or not — or over
 * the call's own Wi-Fi Direct group, which the media marks with a trailing "/wifi-direct" (§3.10).
 */
@Composable
private fun RoutePill(route: String) {
    val parts = route.split('/')
    val wifiDirect = "wifi-direct" in parts
    val types = parts - "wifi-direct"
    val relayed = "relay" in types
    val label =
        when {
            wifiDirect -> "Wi-Fi Direct"
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
                when {
                    wifiDirect -> Icons.Outlined.WifiTethering
                    relayed -> Icons.Outlined.Cloud
                    else -> Icons.Outlined.Sensors
                },
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
        CallPhase.Alerting -> if (state.walkie) "Incoming walkie-talkie" else "Incoming voice call"
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
    stats: CallStats? = null,
    muted: Boolean = false,
    speaker: Boolean = false,
    reason: Int? = null,
    walkie: Boolean = false,
    degraded: Boolean = false,
    talk: Talk = Talk.Idle(),
    link: TalkLink? = null,
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
                walkie = walkie,
                degraded = degraded,
            ),
        name = name,
        avatarHash = null,
        route = route,
        stats = stats,
        onAnswer = {},
        onDecline = {},
        onHangUp = {},
        onMute = {},
        onSpeaker = {},
        clock = { PREVIEW_NOW },
        talk = talk,
        link = link,
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

@Preview(name = "Connected, diagnostics", widthDp = 411, heightDp = 891)
@Preview(name = "Connected, diagnostics, small", widthDp = 360, heightDp = 640)
@Preview(name = "Connected, diagnostics, dark", widthDp = 411, heightDp = 891, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun CallScreenDiagnosticsPreview() =
    PreviewCall(
        CallPhase.Connected,
        route = "host/host/wifi-direct",
        stats =
            CallStats(
                localType = "host",
                remoteType = "host",
                protocol = "udp",
                networkType = "ethernet",
                wifiDirect = true,
                codec = "opus",
                clockRate = 48_000,
                channels = 2,
                fec = true,
                rttMs = 4.2,
                jitterMs = 1.8,
                lossPercent = 0.0,
                sendKbps = 33.6,
                receiveKbps = 32.9,
                jitterBufferMs = 41.0,
                dtlsVersion = "1.2",
                dtlsCipher = "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                srtpCipher = "AEAD_AES_256_GCM",
                localCandidates = 5,
                remoteCandidates = 4,
            ),
    )

@Preview(name = "Ended", widthDp = 411, heightDp = 891)
@Composable
fun CallScreenEndedPreview() = PreviewCall(CallPhase.Ended, reason = CallPayload.REASON_TIMEOUT)

@Preview(name = "Walkie, degraded", widthDp = 411, heightDp = 891)
@Preview(name = "Walkie, degraded, dark", widthDp = 411, heightDp = 891, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun CallScreenWalkiePreview() = PreviewCall(CallPhase.Connected, walkie = true, degraded = true, talk = Talk.Recording(4_200, 0.6f))

@Preview(name = "Walkie, not linked", widthDp = 411, heightDp = 891)
@Preview(name = "Walkie, not linked, dark", widthDp = 411, heightDp = 891, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun CallScreenWalkieUnlinkedPreview() = PreviewCall(CallPhase.Connected, walkie = true, link = TalkLink.NONE)

@Preview(name = "Walkie, through the mesh", widthDp = 411, heightDp = 891)
@Composable
fun CallScreenWalkieMeshPreview() = PreviewCall(CallPhase.Connected, walkie = true, link = TalkLink.MESH)
