package app.getknit.knit.call

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The walkie's transcript (docs/VOICE_CALLS.md §3.11, C-3.11-9): each burst this phone hears is written out as text on
 * the call screen by a speech model run here, on the receiver, after the burst has played — and each burst this phone
 * speaks, from the microphone's PCM before Opus, so the two phones' lines side by side show what the codec cost the
 * model. Nothing about it is on the wire: the sender sends the same frames, and the text never leaves the phone or
 * outlives the session.
 */
object SttPolicy {
    /** What the model takes: 16 kHz mono, as the burst was captured before Opus. */
    const val SAMPLE_RATE = 16_000

    /** A burst shorter than this is a slipped thumb, not speech: no model run. */
    const val MIN_SPEECH_MS = 300L

    /**
     * The longest stretch handed to the model at once. Moonshine v2 tiny returns nothing past about 9 s (its encoder's
     * 540 frames; desktop-measured), so a longer burst is cut at its quietest point between [SEGMENT_MIN_MS] and this.
     */
    const val SEGMENT_MAX_MS = 8_000L
    const val SEGMENT_MIN_MS = 5_000L

    /** The window [segments] measures loudness over when it looks for the quietest place to cut. */
    const val QUIET_WINDOW_MS = 50L

    /** The transcript keeps this many bursts; older ones fall off the top. */
    const val MAX_LINES = 50

    /** The engine is released after this long without a burst to transcribe: its native heap is the cost. */
    const val IDLE_UNLOAD_MS = 2 * 60_000L
}

/**
 * PCM on its way through the walkie — the player's decoded audio as it goes to the speaker, or the recorder's as it
 * goes to the encoder: 16-bit native-order samples in [pcm] between its position and its limit, at [rate] Hz over
 * [channels] interleaved channels. Called on the player's or the recorder's thread; the buffer is the receiver's to
 * read, never to keep.
 */
fun interface TalkPcmTap {
    fun pcm(
        pcm: ByteBuffer,
        rate: Int,
        channels: Int,
    )
}

/** A speech model: [SttPolicy.SAMPLE_RATE] mono samples in −1..1 to their text, or null when it could not run. */
fun interface SpeechToText {
    suspend fun transcribe(samples: FloatArray): String?

    /** Where a burst is cut for this model ([segments]): the stretches it is handed one at a time. */
    fun cut(samples: FloatArray): List<IntRange> = segments(samples)
}

/**
 * One burst's audio gathered for the model: the player's PCM, whatever its rate and layout, mixed to mono and brought to
 * [SttPolicy.SAMPLE_RATE] as it comes — by averaging whole blocks where the rate is a multiple (the decoder's 48 kHz,
 * three to one), by linear interpolation where it is not — and capped at [capMs] so a stuck burst cannot grow without
 * bound. At 8 kbps Opus codes speech narrowband, so a block average's soft low-pass costs nothing the model would hear.
 * Pure (`BurstPcmTest`); not thread-safe.
 */
class BurstPcm(
    capMs: Long = WalkiePolicy.MAX_BURST_MS + CAP_SLACK_MS,
) {
    private val cap = (capMs * SttPolicy.SAMPLE_RATE / MS_PER_S).toInt()
    private var out = FloatArray(INITIAL_SAMPLES)
    private var size = 0

    // The block being averaged (an integer ratio), or the resampler's phase and last sample (any other).
    private var blockSum = 0f
    private var blockCount = 0
    private var phase = 0.0
    private var previous = 0f
    private var rate = 0

    /** How much audio is held, in milliseconds. */
    val audioMs: Long get() = size * MS_PER_S / SttPolicy.SAMPLE_RATE

    /** Takes [pcm]'s samples between its position and its limit; [pcm] itself is left as it was. */
    fun add(
        pcm: ByteBuffer,
        rate: Int,
        channels: Int,
    ) {
        if (rate <= 0 || channels <= 0) return
        if (rate != this.rate) {
            this.rate = rate
            blockSum = 0f
            blockCount = 0
            phase = 0.0
        }
        val shorts = pcm.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        while (shorts.remaining() >= channels) {
            var sum = 0f
            repeat(channels) { sum += shorts.get() }
            take(sum / channels / FULL_SCALE)
        }
    }

    /** The audio held, at [SttPolicy.SAMPLE_RATE]. */
    fun samples(): FloatArray = out.copyOf(size)

    private fun take(sample: Float) {
        val r = rate
        if (r % SttPolicy.SAMPLE_RATE == 0) {
            val factor = r / SttPolicy.SAMPLE_RATE
            blockSum += sample
            if (++blockCount < factor) return
            put(blockSum / factor)
            blockSum = 0f
            blockCount = 0
            return
        }
        // Any other rate: an output sample every rate/16000 inputs, interpolated between the two around it.
        val step = r.toDouble() / SttPolicy.SAMPLE_RATE
        while (phase < 1.0) {
            put((previous + (sample - previous) * phase).toFloat())
            phase += step
        }
        phase -= 1.0
        previous = sample
    }

    private fun put(sample: Float) {
        if (size >= cap) return
        if (size == out.size) out = out.copyOf(minOf(cap, out.size * 2))
        out[size++] = sample
    }

    private companion object {
        const val MS_PER_S = 1_000L
        const val FULL_SCALE = 32_768f
        const val INITIAL_SAMPLES = SttPolicy.SAMPLE_RATE * 4

        // A burst ends a little after the recorder's cap: the encoder's tail and the last chunk's rounding.
        const val CAP_SLACK_MS = 2_000L
    }
}

/**
 * Where [samples] (at [rate]) is cut for the model: stretches no longer than [maxMs], each ending at the quietest
 * [SttPolicy.QUIET_WINDOW_MS] between [minMs] and [maxMs] from its start — a pause between words, as a rule, so no word
 * is cut in two. Audio no longer than [maxMs] is one stretch. Pure (`BurstPcmTest`).
 */
fun segments(
    samples: FloatArray,
    rate: Int = SttPolicy.SAMPLE_RATE,
    minMs: Long = SttPolicy.SEGMENT_MIN_MS,
    maxMs: Long = SttPolicy.SEGMENT_MAX_MS,
): List<IntRange> {
    val max = (maxMs * rate / MS_PER_SECOND).toInt()
    val min = (minMs * rate / MS_PER_SECOND).toInt()
    val window = (SttPolicy.QUIET_WINDOW_MS * rate / MS_PER_SECOND).toInt().coerceAtLeast(1)
    val hop = (window / 2).coerceAtLeast(1)
    val cuts = mutableListOf<IntRange>()
    var start = 0
    while (samples.size - start > max) {
        var quietest = start + max
        var least = Double.MAX_VALUE
        var at = start + min
        while (at + window <= start + max) {
            var energy = 0.0
            for (i in at until at + window) energy += samples[i] * samples[i]
            if (energy < least) {
                least = energy
                quietest = at + window / 2
            }
            at += hop
        }
        cuts += start until quietest
        start = quietest
    }
    if (start < samples.size) cuts += start until samples.size
    return cuts
}

private const val MS_PER_SECOND = 1_000L

/**
 * One burst, as the transcript shows it: [burst] is the sender's number, [heardAt] when it began to play — or, [mine],
 * a burst this phone spoke, numbered by its own count and stamped when the button went down, transcribed from the
 * microphone before Opus touched it.
 */
data class TranscriptLine(
    val burst: Int,
    val heardAt: Long,
    val state: State,
    // The burst's audio and the model's time over it, for the diagnostics; 0 until known.
    val audioMs: Long = 0,
    val tookMs: Long = 0,
    val mine: Boolean = false,
) {
    sealed interface State {
        /** The burst is playing, or being spoken. */
        data object Listening : State

        /** Played; the model has it. */
        data object Pending : State

        data class Text(
            val text: String,
        ) : State

        /** Too short to be speech, or the model heard none. */
        data object NoSpeech : State

        /** No speech model on this phone when the burst ended. */
        data object NoModel : State

        /** The model would not load, or failed on this burst. */
        data object Failed : State
    }
}

/** [lines] with [line] in place of the line for its burst, or after the rest; at most [max], oldest dropped. */
fun withLine(
    lines: List<TranscriptLine>,
    line: TranscriptLine,
    max: Int = SttPolicy.MAX_LINES,
): List<TranscriptLine> {
    val at = lines.indexOfFirst { it.sameBurst(line) }
    val next = if (at >= 0) lines.toMutableList().also { it[at] = line } else lines + line
    return if (next.size > max) next.takeLast(max) else next
}

// Each side numbers its own bursts, so a burst is its number and whose it is.
private fun TranscriptLine.sameBurst(other: TranscriptLine) = burst == other.burst && mine == other.mine

/**
 * Writes the peer's walkie bursts out as text (C-3.11-9). The player's loop opens a burst ([begin]) as it starts to
 * play it, its decoded PCM comes through [pcm] (this is the player's [TalkPcmTap]), and the burst is handed to the
 * model when it has played to its end ([end]) — on [dispatcher], one burst at a time in the order they ended (a fair
 * lock, since the model suspends), so a long one never holds up the playback of the next. [ready] says whether the
 * model is on the phone; a burst that ends without it is marked [TranscriptLine.State.NoModel] and its audio dropped.
 *
 * The bursts this phone speaks go the same way through [mic], the recorder's tap, opened and ended with `mine = true`
 * — the microphone's PCM before the encoder, so set beside the other phone's line for the same burst it shows what
 * Opus cost the model and what the model gets wrong on its own. One of each can be open at once: talking holds the
 * playback mid-burst, it does not end it.
 *
 * A session ([reset]) starts the transcript afresh; a burst still with the model from the last one is abandoned. The
 * text is held here and nowhere else: it is never logged, stored or sent. Thread-safe: [pcm] comes on the player's
 * thread and [mic] on the recorder's, the rest from the player's loop, the talk button and the call screen.
 */
class WalkieTranscriber(
    private val speech: SpeechToText,
    private val ready: () -> Boolean,
    scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) : TalkPcmTap {
    private val _lines = MutableStateFlow<List<TranscriptLine>>(emptyList())

    /** This session's bursts, oldest first. */
    val lines: StateFlow<List<TranscriptLine>> = _lines.asStateFlow()

    private val work = SupervisorJob(scope.coroutineContext[Job])
    private val workScope = CoroutineScope(scope.coroutineContext + work)
    private val lock = Any()
    private val serial = Mutex()

    /** The burst open on one side and its audio so far. */
    private class Slot {
        var open: Int? = null
        var audio: BurstPcm? = null

        fun clear() {
            open = null
            audio = null
        }
    }

    // The burst playing now, the one being spoken, and the session's number, which a finished transcription must still
    // match.
    private val heard = Slot()
    private val spoken = Slot()
    private var session = 0

    private fun slot(mine: Boolean) = if (mine) spoken else heard

    /** The recorder's tap: the microphone's PCM for the burst being spoken. */
    val mic = TalkPcmTap { pcm, rate, channels -> synchronized(lock) { spoken.audio?.add(pcm, rate, channels) } }

    /** A new session: an empty transcript, and nothing from the last one still to come. */
    fun reset() {
        synchronized(lock) {
            session++
            heard.clear()
            spoken.clear()
        }
        work.cancelChildren()
        _lines.value = emptyList()
    }

    /** The burst [burst] has begun to play, or ([mine]) to be spoken. */
    fun begin(
        burst: Int,
        mine: Boolean = false,
    ) {
        synchronized(lock) {
            slot(mine).open = burst
            slot(mine).audio = BurstPcm()
        }
        _lines.update { withLine(it, TranscriptLine(burst, clock(), TranscriptLine.State.Listening, mine = mine)) }
    }

    override fun pcm(
        pcm: ByteBuffer,
        rate: Int,
        channels: Int,
    ) {
        synchronized(lock) { heard.audio?.add(pcm, rate, channels) }
    }

    /** The burst [burst] has played, or ([mine]) been spoken, to its end: to the model with it. */
    fun end(
        burst: Int,
        mine: Boolean = false,
    ) {
        val (held, gen) =
            synchronized(lock) {
                val slot = slot(mine)
                if (slot.open != burst) return
                val held = slot.audio
                slot.clear()
                held to session
            }
        val line = _lines.value.firstOrNull { it.burst == burst && it.mine == mine } ?: return
        val samples = held?.samples() ?: FloatArray(0)
        val audioMs = held?.audioMs ?: 0L
        val state =
            when {
                audioMs < SttPolicy.MIN_SPEECH_MS -> TranscriptLine.State.NoSpeech
                !ready() -> TranscriptLine.State.NoModel
                else -> TranscriptLine.State.Pending
            }
        _lines.update { withLine(it, line.copy(state = state, audioMs = audioMs)) }
        if (state != TranscriptLine.State.Pending) return
        workScope.launch(dispatcher) { serial.withLock { transcribe(line.copy(audioMs = audioMs), samples, gen) } }
    }

    /** The session ended with a burst still playing, or ([mine]) a spoken one was dropped: drop it, and its line with it. */
    fun abandon(mine: Boolean = false) {
        val burst =
            synchronized(lock) {
                val slot = slot(mine)
                val burst = slot.open ?: return
                slot.clear()
                burst
            }
        _lines.update { lines ->
            lines.filterNot { it.burst == burst && it.mine == mine && it.state == TranscriptLine.State.Listening }
        }
    }

    private suspend fun transcribe(
        line: TranscriptLine,
        samples: FloatArray,
        gen: Int,
    ) {
        val began = clock()
        val parts = speech.cut(samples)
        val texts = mutableListOf<String>()
        var failed = false
        for (range in parts) {
            val text = speech.transcribe(samples.copyOfRange(range.first, range.last + 1))
            if (text == null) {
                failed = true
                break
            }
            if (text.isNotBlank()) texts += text.trim()
        }
        val took = clock() - began
        val state =
            when {
                failed -> TranscriptLine.State.Failed
                texts.isEmpty() -> TranscriptLine.State.NoSpeech
                else -> TranscriptLine.State.Text(texts.joinToString(" "))
            }
        // Timings only: the text itself is never logged.
        log(
            "walkie: transcribed ${if (line.mine) "spoken" else "heard"} burst ${line.burst}: " +
                "${line.audioMs} ms of audio in $took ms, ${parts.size} part(s), ${state.label()}",
        )
        synchronized(lock) { if (gen != session) return }
        // A line that fell off the top meanwhile stays off: put back, it would land out of order.
        _lines.update { lines ->
            if (lines.none { it.sameBurst(line) }) lines else withLine(lines, line.copy(state = state, tookMs = took))
        }
    }

    private fun TranscriptLine.State.label(): String =
        when (this) {
            is TranscriptLine.State.Text -> "${text.length} chars"
            else -> javaClass.simpleName
        }
}
