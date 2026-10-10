package app.getknit.knit.debug.call

import app.getknit.knit.call.CallManager
import app.getknit.knit.call.CallPhase
import app.getknit.knit.call.CallState
import app.getknit.knit.call.OpusPackets
import app.getknit.knit.call.TalkChunk
import app.getknit.knit.call.TalkChunker
import app.getknit.knit.call.TalkIn
import app.getknit.knit.call.TalkLink
import app.getknit.knit.call.TalkQueue
import app.getknit.knit.call.TalkWire
import app.getknit.knit.call.WalkieMeter
import app.getknit.knit.call.WalkiePolicy
import app.getknit.knit.call.WalkieStats
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    enum class Note { SENT, TOO_SHORT, NO_MIC, NOT_SENT, NO_LINK }
}

/** The mesh as a walkie call sees its peer (§3.11): the link a chunk would take, and the call's hold on a Wi-Fi Aware one. */
internal interface TalkLinks {
    /** [TalkLink] to [peerId], as it changes; [peerTicketed] is whether its walkie signal said it holds a cast ticket. */
    fun linkTo(
        peerId: String,
        peerTicketed: () -> Boolean,
    ): Flow<TalkLink>

    /** Holds the Wi-Fi Aware link to [peerId] open, or raises one, for a while; false when none will come now. */
    fun raise(peerId: String): Boolean

    /**
     * The way a chunk to [peerId] goes now, by the links `MeshManager.sendCallTalk` sends on: [TalkLink] reads the
     * peers this phone hears, a peer heard but not linked gets its chunks flooded through the links there are, and one
     * with no mesh path at all gets them cast through a relay when one carries them ([peerTicketed] as for [linkTo]).
     */
    fun pathTo(
        peerId: String,
        peerTicketed: Boolean,
    ): TalkPath
}

/** The way a walkie chunk goes to the peer, as the diagnostics card names it. */
internal enum class TalkPath(
    val label: String,
) {
    WIFI_AWARE("Wi-Fi Aware · direct"),
    BLUETOOTH_CODED("Bluetooth Coded · direct"),
    BLUETOOTH("Bluetooth · direct"),
    FLOODED("Through a neighbour · flooded"),
    SPOOL("Through the relay · cast"),
    NONE("No link"),
}

/**
 * The walkie-talkie's half of a call (docs/VOICE_CALLS.md §3.11, ADR 2026-10.79yb): while [CallManager]'s call is a
 * connected walkie session, a held button streams a burst to the peer as it is spoken — a chunk of Opus packets every
 * [WalkiePolicy.CHUNK_MS] ([TalkWire]), each a sealed `talk` frame nobody custodies — and the peer's bursts play as their chunks
 * land, put back in order by [TalkQueue]. Nothing is stored and nothing reaches a thread: a chunk that is lost is
 * skipped.
 *
 * Half duplex at this end: talking holds whatever is playing, and what arrives meanwhile plays after the release.
 *
 * Lives as long as the process (a Koin single, started with the call surfaces), so a session keeps talking with the call
 * screen off; `CallService` holds the microphone's foreground state meanwhile.
 */
internal class WalkieTalk(
    private val calls: CallManager,
    private val recorder: TalkRecorder,
    private val player: TalkPlayer,
    // `MeshManager.sendCallTalk`: one chunk's plaintext sealed into a `talk` frame of the bytes returned; null when
    // nothing could be sealed. The flag is the peer's cast ticket, from its walkie signal (§2.7).
    private val send: suspend (peerId: String, chunk: ByteArray, peerTicketed: Boolean) -> Int?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = {},
    private val links: TalkLinks? = null,
) {
    private val _talk = MutableStateFlow<Talk>(Talk.Idle())
    val talk: StateFlow<Talk> = _talk.asStateFlow()

    // For the debug bridge: bursts this phone sent and played, chunks out and in, and chunks skipped as never come.
    val sent = AtomicInteger()
    val heard = AtomicInteger()
    val chunksOut = AtomicInteger()
    val chunksIn = AtomicInteger()
    val lost = AtomicInteger()

    // The session's numbers for the diagnostics card ([stats]).
    private val meter = WalkieMeter()

    // Whether the Wi-Fi Aware link to the walkie call's peer is held now (`TalkLinks.raise`).
    @Volatile
    private var holding = false

    /** The burst being spoken: its bytes on their way to [streamer], and whether its end sends ([kept]) or drops it. */
    private class Outgoing(
        val ticker: Job,
        val bytes: Channel<ByteArray>,
        val kept: CompletableDeferred<Boolean>,
        val streamer: Deferred<Talk.Note>,
    )

    // The recorder is single-threaded; every press, release and cancel takes this.
    private val lock = Mutex()
    private var outgoing: Outgoing? = null

    // This session's next burst number.
    private var burst = 0

    @Volatile
    private var lastActivity = 0L

    private val _link = MutableStateFlow<TalkLink?>(null)

    /** Where a chunk to the walkie call's peer would go now; null outside a walkie call. */
    val link: StateFlow<TalkLink?> = _link.asStateFlow()

    fun start() {
        scope.launch {
            calls.state
                .map { it?.takeIf(::isSession) }
                .distinctUntilChanged { a, b -> a?.id == b?.id }
                .collectLatest { state ->
                    if (state != null) session(state) else idle()
                }
        }
        scope.launch {
            calls.state
                .map { it?.takeIf { s -> s.walkie && s.phase != CallPhase.Ended } }
                .distinctUntilChanged { a, b -> a?.id == b?.id }
                .collectLatest { call -> if (call != null) linking(call) else _link.value = null }
        }
    }

    /**
     * From the ring to the hang-up of a walkie call: track the link to its peer, and keep holding a Wi-Fi Aware one
     * ([WalkiePolicy.LINK_ASK_MS]), which the radio would otherwise close between bursts. Bluetooth can take minutes to
     * link two phones that Wi-Fi Aware already sees, and with no link at all a chunk goes nowhere.
     */
    private suspend fun linking(call: CallState) {
        val mesh = links ?: return
        coroutineScope {
            launch { mesh.linkTo(call.peerId) { peerTicketed() }.collect { _link.value = it } }
            var armed: Boolean? = null
            while (true) {
                val now = mesh.raise(call.peerId)
                if (now != armed) log("walkie: Wi-Fi Aware link to ${call.peerId} ${if (now) "asked for" else "not available now"}")
                armed = now
                holding = now
                delay(WalkiePolicy.LINK_ASK_MS)
            }
        }
    }

    /** The session's numbers now, for the call screen's diagnostics card; read once a second while it shows. */
    fun stats(): WalkieStats {
        val peerId =
            calls.state.value
                ?.takeIf { it.walkie }
                ?.peerId
        return meter.snapshot().copy(burstsOut = sent.get(), burstsIn = heard.get(), path = peerId?.let(::pathTo))
    }

    // Whether the walkie call's peer said it holds a cast ticket (§2.7): then a relay that sells casts carries ours.
    private fun peerTicketed(): Boolean =
        calls.state.value
            ?.takeIf { it.walkie }
            ?.peerCastTicketed == true

    // Where a chunk to the peer goes now ("Wi-Fi Aware · direct · held"), and whether the call holds that link.
    private fun pathTo(peerId: String): String? {
        val path = links?.pathTo(peerId, peerTicketed()) ?: return null
        return path.label + if (holding && path == TalkPath.WIFI_AWARE) " · held" else ""
    }

    /** Starts a burst; false when no walkie session is connected, or the microphone would not open. */
    suspend fun press(): Boolean =
        lock.withLock {
            if (outgoing != null) return@withLock false
            val call = calls.state.value?.takeIf(::isSession) ?: return@withLock false
            // Half duplex: what plays waits for the release.
            player.hold(true)
            val bytes = Channel<ByteArray>(Channel.UNLIMITED)
            if (!recorder.start { bytes.trySend(it) }) {
                player.hold(false)
                _talk.value = Talk.Idle(Talk.Note.NO_MIC)
                return@withLock false
            }
            lastActivity = clock()
            val kept = CompletableDeferred<Boolean>()
            val streamer = scope.async(Dispatchers.Default) { stream(call, burst++, bytes, kept) }
            val ticker =
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
            outgoing = Outgoing(ticker, bytes, kept, streamer)
            true
        }

    /** Ends the burst: its last chunk goes out, unless it ended before its first did. */
    suspend fun release() {
        val o =
            lock.withLock {
                val o = outgoing ?: return
                outgoing = null
                o.ticker.cancel()
                _talk.value = Talk.Sending
                // Joins the drain, so every byte recorded has reached the streamer before its channel closes.
                recorder.stop()
                o
            }
        o.bytes.close()
        o.kept.complete(true)
        val note = o.streamer.await()
        player.hold(false)
        lastActivity = clock()
        _talk.value = Talk.Idle(note)
    }

    /** Drops the burst being spoken; a peer already hearing it is told it ended. */
    suspend fun cancel() {
        val o =
            lock.withLock {
                val o = outgoing ?: return@withLock null
                outgoing = null
                o.ticker.cancel()
                recorder.cancel()
                o
            }
        if (o != null) {
            o.bytes.close()
            o.kept.complete(false)
            o.streamer.await()
            player.hold(false)
        }
        _talk.value = Talk.Idle()
    }

    private fun isSession(state: CallState): Boolean = state.walkie && state.phase == CallPhase.Connected

    private suspend fun idle() {
        cancel()
        _talk.value = Talk.Idle()
    }

    /**
     * One burst out: packets as the encoder makes them, a chunk each time [WalkiePolicy.CHUNK_MS] of audio is in hand,
     * and the rest with the end mark. Ended before its first chunk, it was a slipped thumb, and nothing goes at all.
     */
    private suspend fun stream(
        call: CallState,
        b: Int,
        packets: ReceiveChannel<ByteArray>,
        kept: Deferred<Boolean>,
    ): Talk.Note {
        val chunker = TalkChunker()
        val tag = TalkWire.tagOf(call.id)
        var q = 0
        var delivered = 0
        var bytesOut = 0
        // For the burst's log line: when each chunk was cut and when its send returned, from the press.
        val began = clock()
        val timings = mutableListOf<String>()
        meter.burstOut()

        suspend fun out(
            audio: ByteArray,
            end: Boolean,
        ) {
            val cut = clock() - began
            val chunk = TalkWire.encode(tag, TalkChunk(b = b, q = q++, a = audio, e = end))
            bytesOut += chunk.size
            // No link at this instant (a held link coming round again, say) is worth a moment's wait; the chunks
            // behind it queue meanwhile and go in order.
            var frame: Int? = null
            for (attempt in 0 until SEND_TRIES) {
                frame = send(call.peerId, chunk, peerTicketed())
                if (frame != null || attempt == SEND_TRIES - 1) break
                delay(SEND_RETRY_MS)
            }
            if (frame != null) {
                delivered++
                chunksOut.incrementAndGet()
            }
            val handed = clock() - began
            meter.chunkOut(audio, frame, handed - cut)
            timings += "$cut>$handed"
        }
        for (packet in packets) chunker.add(packet)?.let { out(it, end = false) }
        val keep = kept.await()
        if (q == 0) return Talk.Note.TOO_SHORT
        out(if (keep) chunker.flush() else ByteArray(0), end = true)
        log(
            "walkie: burst $b of ${chunker.totalMs}ms to ${call.peerId} in $q chunks ($bytesOut B before the seal), $delivered sealed; " +
                "cut>sent ms ${timings.joinToString(
                    " ",
                )}",
        )
        if (delivered == 0) return if (_link.value == TalkLink.NONE) Talk.Note.NO_LINK else Talk.Note.NOT_SENT
        sent.incrementAndGet()
        return Talk.Note.SENT
    }

    /** One connected session with [call]'s peer: play what it streams, hang up when it goes quiet. */
    private suspend fun session(call: CallState) {
        burst = 0
        lastActivity = clock()
        meter.reset()
        log("walkie: session ${call.id} with ${call.peerId}${if (call.degraded) " (degraded from a live call)" else ""}")
        coroutineScope {
            launch { hangUpWhenIdle() }
            hear(call.id)
        }
    }

    /** Plays the peer's bursts as their chunks land, in order, until the session ends. */
    private suspend fun hear(callId: String) =
        coroutineScope {
            val inbox = calls.talk.filter { it.callId == callId }.produceIn(this)
            val queue = TalkQueue()
            var skipped = 0
            // Per burst, for its log line: when each chunk came, and when the burst began to play.
            val arrivals = mutableMapOf<Int, MutableList<Long>>()
            var playedAt = 0L

            fun take(t: TalkIn) {
                if (queue.offer(t.chunk, t.at)) {
                    chunksIn.incrementAndGet()
                    arrivals.getOrPut(t.chunk.b) { mutableListOf() } += t.at
                    meter.chunkIn(t.chunk, t.at, t.sentAt, t.frameBytes)
                }
                lastActivity = clock()
            }
            try {
                while (true) {
                    when (val step = queue.poll(clock())) {
                        is TalkQueue.Step.Play -> {
                            if (step.first) {
                                playedAt = clock()
                                arrivals[step.burst]?.minOrNull()?.let { meter.started(it, playedAt) }
                                if (_talk.value is Talk.Idle) _talk.value = Talk.Hearing
                            }
                            withContext(Dispatchers.IO) { player.play(OpusPackets.split(step.audio), step.first, step.last) }
                            if (step.last) {
                                logHeard(step.burst, arrivals.remove(step.burst).orEmpty(), playedAt, queue.skipped - skipped)
                                heard.incrementAndGet()
                                if (_talk.value == Talk.Hearing) _talk.value = Talk.Idle()
                            }
                            lost.addAndGet(queue.skipped - skipped)
                            meter.skipped(queue.skipped - skipped)
                            skipped = queue.skipped
                            lastActivity = clock()
                        }

                        is TalkQueue.Step.Wait -> {
                            withTimeoutOrNull(step.ms) { inbox.receive() }?.let(::take)
                        }

                        TalkQueue.Step.Idle -> {
                            take(inbox.receive())
                        }
                    }
                    // Whatever else came meanwhile goes into the queue before it is asked again.
                    while (true) take(inbox.tryReceive().getOrNull() ?: break)
                }
            } finally {
                player.close()
                if (_talk.value == Talk.Hearing) _talk.value = Talk.Idle()
            }
        }

    // One line per burst heard: when each chunk came after the first, and when the burst began to play.
    private fun logHeard(
        b: Int,
        at: List<Long>,
        playedAt: Long,
        skippedSince: Int,
    ) {
        val first = at.minOrNull() ?: return
        val offsets = at.joinToString("/") { "${it - first}" }
        log("walkie: heard burst $b: ${at.size} chunks, $skippedSince skipped, arrivals +$offsets ms, began +${playedAt - first} ms")
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

    private companion object {
        const val TICK_MS = 60L

        // A chunk that finds no link tries again this often, this many times: 2.5 s, past a held Wi-Fi Aware link's
        // recycle (about 1.9 s from its window's teardown to the next link-up, device-measured).
        const val SEND_RETRY_MS = 250L
        const val SEND_TRIES = 11
        const val MS_PER_MIN = 60_000L
    }
}
