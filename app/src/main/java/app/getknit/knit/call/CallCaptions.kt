package app.getknit.knit.call

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer

/**
 * A live call's captions (docs/VOICE_CALLS.md C-3.7-5, ADR 2026-10.nz3g): what the peer says, written out on this phone
 * by the walkie transcript's model as it plays. A live call has no bursts to bound its speech, so the model's own voice
 * detector splits it at pauses (the stream is opened `continuous`): a look then decodes only the stretch still open,
 * where a whole-line stream would decode the call's last thirty seconds again every half second, and silence costs the
 * detector alone. Each pause ends a run — one stream of the model's — and its words become a settled line.
 */
object CaptionPolicy {
    /**
     * A run that has heard nothing for this long is let go and a fresh one opened at its next look, so a stream never
     * holds minutes of a quiet line.
     */
    const val QUIET_RUN_MS = 30_000L

    /**
     * A run still talking this long is finished where it stands, at its next look: a stream must not grow for a whole
     * call. Twice the model's own advice for one stretch of input, since a cut here costs the words at the seam.
     */
    const val MAX_RUN_MS = 60_000L

    /**
     * Audio waiting for the model this long means the phone cannot keep up: captions that far behind the voice read
     * worse than none, so they stop for the rest of the call ([CaptionStatus.BEHIND]).
     */
    const val BEHIND_MS = 8_000L

    /** What a look at a run's stream says to do next, given what it has heard and how much audio it has had. */
    fun step(
        heard: Heard,
        runMs: Long,
    ): RunStep =
        when {
            // A pause after speech: the detector has closed every stretch, and nothing is open.
            heard.settled.isNotBlank() && heard.live.isBlank() -> RunStep.SETTLE
            heard.text.isBlank() && runMs >= QUIET_RUN_MS -> RunStep.DROP
            runMs >= MAX_RUN_MS -> RunStep.SETTLE
            else -> RunStep.KEEP
        }
}

/** The next move for a run after a look ([CaptionPolicy.step]). */
enum class RunStep {
    /** Go on feeding it. */
    KEEP,

    /** Finish it: its words are a settled line, and the audio after goes to a fresh run. */
    SETTLE,

    /** Let it go unheard: it has heard nothing, and a fresh run takes the audio after. */
    DROP,
}

/** Where a call's captions stand, beside their lines. */
enum class CaptionStatus {
    /** Taking the peer's audio while the person's switch is on. */
    ON,

    /** The model fell [CaptionPolicy.BEHIND_MS] behind the voice: stopped for this call. */
    BEHIND,

    /** The model would not run: stopped for this call. */
    FAILED,
}

/** One call's captions: its lines, oldest first, and where they stand. */
data class Captions(
    val lines: List<TranscriptLine> = emptyList(),
    val status: CaptionStatus = CaptionStatus.ON,
)

/**
 * Writes a live call's peer out as text as it plays (C-3.7-5). The media opens a call's captions ([begin]) with its
 * first session and closes them ([end]) with its last; each session's received track hands its decoded PCM to a
 * [source] of its own — the newest one alone is taken, so a glare's dropped session, closing beside its replacement,
 * never doubles the audio. The PCM goes into the open run and on to its stream on [dispatcher], one model call at a time
 * behind a lock, so the media's audio thread never waits on the model; each look shows the run's words so far, settled
 * and still open, and the pause that ends the run makes them its line ([CaptionPolicy.step]). A run's line appears with
 * its first words, so a quiet call shows nothing.
 *
 * [enabled] is the person's choice (the Calls screen's switch and the call screen's CC button), asked as audio comes:
 * off, the run open is finished with what it had and no audio is taken. [ready] says whether the model is on the phone,
 * asked as audio reaches the model: while it is not, the audio is dropped — a call does not wait for a download.
 *
 * The text is held here and nowhere else: never logged, stored or sent, and gone with the next call's [begin].
 * Thread-safe.
 */
class CallCaptioner(
    private val speech: SpeechToText,
    private val ready: () -> Boolean,
    scope: CoroutineScope,
    private val enabled: () -> Boolean = { true },
    private val dispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val _captions = MutableStateFlow(Captions())

    /** This call's captions. */
    val captions: StateFlow<Captions> = _captions.asStateFlow()

    private val workScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val lock = Any()
    private val serial = Mutex()

    /** One run on its way through: its audio under [lock], its stream under [serial]. */
    private class Run(
        val number: Int,
        val session: Int,
    ) {
        // Under [lock]: the audio not yet fed, whether a feed is on its way, and whether the run was let go.
        val audio = BurstPcm(capMs = CaptionPolicy.MAX_RUN_MS + CAP_SLACK_MS)
        var queued = false
        var dropped = false

        // Under [serial]: the model's stream once open, how much audio it has had, and whether its line is up.
        var stream: SpeechStream? = null
        val gate = PassGate()
        var fedMs = 0.0
        var shown = false
        var looks = 0
        var modelMs = 0L
    }

    // Under [lock]: the call's number, which every piece of work must still match; the run open now; the source taken;
    // whether the call's media is up, whether its captions stopped ([CaptionStatus]), and the next run's number.
    private var session = 0
    private var current: Run? = null
    private var source: TalkPcmTap? = null
    private var mediaUp = false
    private var stopped = false
    private var runs = 0

    /** A call's first media session opened: empty captions, and nothing from the last call still to come. */
    fun begin() {
        val gone =
            synchronized(lock) {
                session++
                mediaUp = true
                stopped = false
                runs = 0
                source = null
                current.also { current = null }
            }
        gone?.let(::dropLater)
        _captions.value = Captions()
    }

    /**
     * The tap for one media session's received audio. A newer one supersedes it: from then on its PCM is ignored.
     */
    fun source(): TalkPcmTap {
        val tap =
            object : TalkPcmTap {
                override fun pcm(
                    pcm: ByteBuffer,
                    rate: Int,
                    channels: Int,
                ) = take(this, pcm, rate, channels)
            }
        synchronized(lock) { source = tap }
        return tap
    }

    /**
     * The call's last media session closed: the run open is finished, so its last words are kept, and the model is let
     * go once that work has run. The lines stay until the next [begin].
     */
    fun end() {
        val run =
            synchronized(lock) {
                mediaUp = false
                source = null
                current.also { current = null }
            }
        run?.let { r -> workScope.launch(dispatcher) { serial.withLock { settle(r) } } }
        workScope.launch(dispatcher) { serial.withLock { speech.release() } }
    }

    private fun take(
        from: TalkPcmTap,
        pcm: ByteBuffer,
        rate: Int,
        channels: Int,
    ) {
        if (!enabled()) return switchedOff()
        val run =
            synchronized(lock) {
                if (from !== source || !mediaUp || stopped) return
                val run = current ?: Run(runs++, session).also { fresh -> current = fresh }
                run.audio.add(pcm, rate, channels)
                if (run.queued) return
                run.queued = true
                run
            }
        workScope.launch(dispatcher) { serial.withLock { feed(run) } }
    }

    // Switched off mid-call: the run open settles with what it has, and captions that stopped may try again once the
    // switch is back — the next audio then starts a run.
    private fun switchedOff() {
        val run =
            synchronized(lock) {
                if (stopped && mediaUp) {
                    stopped = false
                    _captions.update { it.copy(status = CaptionStatus.ON) }
                }
                current.also { current = null }
            } ?: return
        workScope.launch(dispatcher) { serial.withLock { settle(run) } }
    }

    // Under [serial]: the audio come in since the last feed, to the run's stream — opening it first — and a look when
    // one is due, after which the run goes on, settles or is let go.
    private suspend fun feed(run: Run) {
        val samples =
            synchronized(lock) {
                run.queued = false
                if (run.dropped || run.session != session) return
                run.audio.drain()
            }
        if (!ready()) return
        val opened = run.stream != null
        val stream = streamOf(run) ?: return
        // A backlog is only the model's fault once its stream was open: the first feed carries what came while it loaded.
        if (opened && samples.size > CaptionPolicy.BEHIND_MS * SttPolicy.SAMPLE_RATE / MS_PER_S) return stop(run, CaptionStatus.BEHIND)
        if (samples.isEmpty()) return
        if (!timed(run) { stream.add(samples) }) return stop(run, CaptionStatus.FAILED)
        val ms = samples.size * MS_PER_S.toDouble() / SttPolicy.SAMPLE_RATE
        run.fedMs += ms
        if (!run.gate.fed(ms)) return
        val began = clock()
        val heard = timed(run) { stream.look() } ?: return stop(run, CaptionStatus.FAILED)
        run.gate.took(clock() - began)
        run.looks++
        if (heard.text.isNotBlank()) show(run, heard)
        next(run, CaptionPolicy.step(heard, run.fedMs.toLong()))
    }

    // Under [serial]: what a look said to do with [run].
    private suspend fun next(
        run: Run,
        step: RunStep,
    ) {
        when (step) {
            // Fed again as more audio comes.
            RunStep.KEEP -> {
                return
            }

            RunStep.SETTLE -> {
                detach(run)
                settle(run)
            }

            RunStep.DROP -> {
                detach(run)
                synchronized(lock) { run.dropped = true }
                close(run)
            }
        }
    }

    // [run] is no longer the one taking audio: the next audio opens a fresh run.
    private fun detach(run: Run) =
        synchronized(lock) {
            if (current === run) current = null
        }

    // Under [serial], with [run] detached: what is left of its audio to the stream, then its last look as its line.
    private suspend fun settle(run: Run) {
        val rest =
            synchronized(lock) {
                if (run.dropped || run.session != session) null else run.audio.drain().also { run.dropped = true }
            }
        if (rest == null) return close(run)
        val stream = run.stream ?: return
        if (rest.isNotEmpty() && ready()) {
            if (!timed(run) { stream.add(rest) }) return stop(run, CaptionStatus.FAILED)
            run.fedMs += rest.size * MS_PER_S.toDouble() / SttPolicy.SAMPLE_RATE
        }
        val heard = timed(run) { stream.finish() }
        run.stream = null
        // Timings only: the text itself is never logged.
        log(
            "captions: run ${run.number}: ${run.fedMs.toLong()} ms of audio, ${run.looks} look(s), ${run.modelMs} ms in " +
                "the model, ${heard?.text?.length?.let { "$it chars" } ?: "failed"}",
        )
        val text = heard?.text.orEmpty()
        updateLines(run) { lines ->
            val at = lines.indexOfFirst { it.burst == run.number }
            when {
                text.isBlank() -> lines.filterNot { it.burst == run.number }
                at >= 0 -> withLine(lines, lines[at].copy(state = TranscriptLine.State.Text(text), live = "", settled = ""))
                else -> withLine(lines, TranscriptLine(run.number, clock(), TranscriptLine.State.Text(text)))
            }
        }
    }

    // Under [serial]: [run]'s words so far, on its line — put up with its first words.
    private fun show(
        run: Run,
        heard: Heard,
    ) {
        val heardAt = clock()
        updateLines(run) { lines ->
            val line =
                lines.firstOrNull { it.burst == run.number }
                    ?: TranscriptLine(run.number, heardAt, TranscriptLine.State.Listening).takeUnless { run.shown }
                    ?: return@updateLines lines
            run.shown = true
            withLine(lines, line.copy(settled = heard.settled.trim(), live = heard.live.trim()))
        }
    }

    // Under [serial]: the run's stream, opened on first use; null once the model would not run, which stops the call's
    // captions.
    private suspend fun streamOf(run: Run): SpeechStream? {
        run.stream?.let { return it }
        val opened = timed(run) { speech.open(continuous = true) }
        if (opened == null) {
            stop(run, CaptionStatus.FAILED)
            return null
        }
        if (synchronized(lock) { run.dropped || run.session != session }) {
            opened.close()
            return null
        }
        run.stream = opened
        return opened
    }

    // Under [serial]: this call's captions stop with [status]; their lines stay.
    private suspend fun stop(
        run: Run,
        status: CaptionStatus,
    ) {
        val stale =
            synchronized(lock) {
                run.dropped = true
                if (run.session != session) return@synchronized true
                stopped = true
                if (current === run) current = null
                false
            }
        close(run)
        if (stale) return
        log("captions: stopped: ${status.name.lowercase()}")
        // The run's words so far stay as its line: nothing will settle them now.
        _captions.update { captions ->
            val lines =
                captions.lines.mapNotNull { line ->
                    when {
                        line.burst != run.number || line.state != TranscriptLine.State.Listening -> line
                        line.words.isBlank() -> null
                        else -> line.copy(state = TranscriptLine.State.Text(line.words), settled = "", live = "")
                    }
                }
            captions.copy(lines = lines, status = status)
        }
    }

    private suspend fun close(run: Run) {
        run.stream?.close()
        run.stream = null
    }

    // A run let go from outside the model's lock: its stream closed behind whatever work for it is queued.
    private fun dropLater(run: Run) {
        synchronized(lock) { run.dropped = true }
        workScope.launch(dispatcher) { serial.withLock { close(run) } }
    }

    private inline fun <R> timed(
        run: Run,
        call: () -> R,
    ): R {
        val began = clock()
        return call().also { run.modelMs += clock() - began }
    }

    // [change] to the lines, while [run] is still this call's.
    private fun updateLines(
        run: Run,
        change: (List<TranscriptLine>) -> List<TranscriptLine>,
    ) {
        _captions.update { captions ->
            if (run.session != synchronized(lock) { session }) return@update captions
            captions.copy(lines = change(captions.lines))
        }
    }

    private companion object {
        const val MS_PER_S = 1_000L

        // A run ends at its next look past the cap: the cadence's longest stretch, and some.
        const val CAP_SLACK_MS = 10_000L
    }
}
