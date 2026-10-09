package app.getknit.knit.debug.call

import app.getknit.knit.call.BurstRow
import app.getknit.knit.call.CallManager
import app.getknit.knit.call.CallPhase
import app.getknit.knit.call.CallState
import app.getknit.knit.call.WalkiePolicy
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.VoiceAudio
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.ui.voice.VoicePlayer
import app.getknit.knit.ui.voice.VoiceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/** Where the talk button stands; [Idle.note] is what the last press came to. */
internal sealed interface Talk {
    data class Idle(
        val note: Note? = null,
    ) : Talk

    data class Recording(
        val elapsedMs: Long,
        val level: Float,
    ) : Talk

    data object Sending : Talk

    data object Hearing : Talk

    enum class Note { SENT, TOO_SHORT, NO_MIC, NOT_SENT }
}

/**
 * The walkie-talkie's half of a call (docs/VOICE_CALLS.md §3.11, ADR 2026-10.79yb): while [CallManager]'s call is a
 * connected walkie session, a held button records a burst and its release sends it to the peer as an ordinary DM voice
 * note, and the peer's bursts play by themselves, one after another, as their clips land ([WalkiePolicy]). Nothing here
 * touches the wire: the thread keeps every burst, and a late or missed one is a voice note like any other.
 *
 * Lives as long as the process (a Koin single, started with the call surfaces), so a session keeps talking with the call
 * screen off; `CallService` holds the microphone's foreground state meanwhile.
 */
@Suppress("LongParameterList") // the seams a burst crosses: record, store, send, show, play, mark read
internal class WalkieTalk(
    private val calls: CallManager,
    private val recorder: VoiceRecorder,
    private val attachments: AttachmentStore,
    private val mesh: MeshController,
    private val messages: MessageRepository,
    private val player: VoicePlayer,
    private val notifier: Notifier,
    private val settings: SettingsStore,
    private val selfId: suspend () -> String,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = {},
) {
    private val _talk = MutableStateFlow<Talk>(Talk.Idle())
    val talk: StateFlow<Talk> = _talk.asStateFlow()

    // For the debug bridge: bursts this phone sent, played, and let pass as too late.
    val sent = AtomicInteger()
    val heard = AtomicInteger()
    val skipped = AtomicInteger()

    // The recorder is single-threaded; every press and release takes this.
    private val lock = Mutex()
    private var recording: Job? = null

    @Volatile
    private var lastActivity = 0L

    fun start() {
        scope.launch {
            calls.state
                .map { it?.takeIf(::isSession) }
                .distinctUntilChanged { a, b -> a?.id == b?.id }
                .collectLatest { state ->
                    if (state != null) session(state) else idle()
                }
        }
    }

    /** Starts a burst; false when no walkie session is connected, or the microphone would not open. */
    suspend fun press(): Boolean =
        lock.withLock {
            if (recording != null || calls.state.value?.let(::isSession) != true) return@withLock false
            // Half duplex: talking silences whatever was playing (it stays a voice note in the thread).
            player.stop()
            if (!recorder.start()) {
                _talk.value = Talk.Idle(Talk.Note.NO_MIC)
                return@withLock false
            }
            lastActivity = clock()
            recording =
                scope.launch {
                    while (true) {
                        val elapsed = recorder.elapsedMs()
                        if (elapsed >= WalkiePolicy.MAX_BURST_MS) {
                            scope.launch { release() }
                            break
                        }
                        _talk.value = Talk.Recording(elapsed, recorder.amplitude())
                        delay(TICK_MS)
                    }
                }
            true
        }

    /** Ends the burst and sends it, unless it was too short to be one. */
    suspend fun release() {
        val (peerId, bytes, elapsed) =
            lock.withLock {
                val ticker = recording ?: return
                ticker.cancel()
                recording = null
                val elapsed = recorder.elapsedMs()
                Triple(
                    calls.state.value
                        ?.takeIf(::isSession)
                        ?.peerId,
                    recorder.stop(),
                    elapsed,
                )
            }
        val duration = bytes?.let(VoiceAudio::durationMs)?.toLong() ?: 0L
        if (peerId == null || bytes == null || minOf(elapsed, duration) < WalkiePolicy.MIN_BURST_MS) {
            _talk.value = Talk.Idle(Talk.Note.TOO_SHORT)
            return
        }
        _talk.value = Talk.Sending
        val ingested = (attachments.ingestVoice(bytes) as? AttachmentStore.IngestResult.Success)?.ingested
        val ok = ingested != null && mesh.sendChat(text = "", attachment = ingested, recipientId = peerId)
        if (ok) sent.incrementAndGet()
        lastActivity = clock()
        log("walkie: burst of ${duration}ms to $peerId ${if (ok) "sent" else "not sent"}")
        _talk.value = Talk.Idle(if (ok) Talk.Note.SENT else Talk.Note.NOT_SENT)
    }

    /** Drops the burst being recorded without sending it. */
    suspend fun cancel() {
        lock.withLock {
            recording?.cancel() ?: return@withLock
            recording = null
            recorder.cancel()
        }
        _talk.value = Talk.Idle()
    }

    private fun isSession(state: CallState): Boolean = state.walkie && state.phase == CallPhase.Connected

    private suspend fun idle() {
        cancel()
        _talk.value = Talk.Idle()
    }

    /** One connected session with [call]'s peer: play what it sends, hang up when it goes quiet. */
    private suspend fun session(call: CallState) {
        val peerId = call.peerId
        val me = selfId()
        val connectedAt = call.connectedAt ?: clock()
        lastActivity = clock()
        // Its bursts play here, so they post no notification of their own while it lasts.
        notifier.setVisibleConversation(peerId)
        log("walkie: session ${call.id} with $peerId${if (call.degraded) " (degraded from a live call)" else ""}")
        try {
            coroutineScope {
                val queue = Channel<MessageEntity>(Channel.UNLIMITED)
                launch { for (row in queue) play(peerId, row) }
                launch { hangUpWhenIdle() }
                val played = mutableSetOf<String>()
                val stale = mutableSetOf<String>()
                messages.observeNewestMessages(peerId, WINDOW).collect { rows ->
                    for (row in rows) {
                        val burst = row.toBurst() ?: continue
                        when (WalkiePolicy.verdict(burst, peerId, me, connectedAt, clock(), played)) {
                            WalkiePolicy.Verdict.PLAY -> if (played.add(row.id)) queue.send(row)
                            WalkiePolicy.Verdict.STALE -> if (stale.add(row.id)) skipped.incrementAndGet()
                            WalkiePolicy.Verdict.WAIT, WalkiePolicy.Verdict.NONE -> Unit
                        }
                    }
                }
            }
        } finally {
            notifier.setVisibleConversation(null)
        }
    }

    /** Plays [row]'s clip to its end (or until something else takes the player), then marks the thread read to it. */
    private suspend fun play(
        peerId: String,
        row: MessageEntity,
    ) {
        val hash = row.attachmentHash ?: return
        // The user may have tapped it in the thread already; a second play() of a loaded note would pause it.
        if (player.nowPlaying.value?.hash == hash) return
        lock.withLock { if (recording != null) return } // they are talking over it: it stays a voice note
        _talk.value = Talk.Hearing
        player.play(hash, row.attachmentKey)
        // A refused audio focus plays nothing and says nothing (VoicePlayer): give the start a bound.
        val started = withTimeoutOrNull(START_WAIT_MS) { player.nowPlaying.first { it?.hash == hash && it.playing } }
        if (started != null) {
            player.nowPlaying.first { it?.hash != hash || !it.playing }
            heard.incrementAndGet()
            if (settings.lastReadAt(peerId).first() < row.sentAt) settings.setLastReadAt(peerId, row.sentAt)
        }
        lastActivity = clock()
        if (_talk.value == Talk.Hearing) _talk.value = Talk.Idle()
    }

    private suspend fun hangUpWhenIdle() {
        while (true) {
            val left = lastActivity + WalkiePolicy.IDLE_END_MS - clock()
            if (left <= 0) {
                log("walkie: no burst either way for ${WalkiePolicy.IDLE_END_MS / MS_PER_MIN} min; hanging up")
                calls.hangUp()
                return
            }
            delay(left)
        }
    }

    private fun MessageEntity.toBurst(): BurstRow? =
        BurstRow(
            id = id,
            senderId = senderId,
            mime = attachmentMime,
            name = attachmentName,
            durationMs = voiceDurationMs,
            sentAt = sentAt,
            // Only an arrived message has one; ours never do.
            arrivedAt = arrivedAt ?: return null,
        )

    private companion object {
        // The thread's newest rows a session watches: far more than a burst-a-second pair could send between emissions.
        const val WINDOW = 20

        const val TICK_MS = 60L
        const val START_WAIT_MS = 3_000L
        const val MS_PER_MIN = 60_000L
    }
}
