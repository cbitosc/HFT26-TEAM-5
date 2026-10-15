package app.getknit.knit.ui.call.stt

import ai.moonshine.voice.JNI
import ai.moonshine.voice.TranscriberOption
import ai.moonshine.voice.Transcript
import app.getknit.knit.call.Heard
import app.getknit.knit.call.SpeechStream
import app.getknit.knit.call.SpeechToText
import app.getknit.knit.call.SttArch
import app.getknit.knit.call.SttModel
import app.getknit.knit.call.SttPolicy
import app.getknit.knit.moderation.ModelLease
import app.getknit.knit.moderation.ModelLoadGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * The walkie transcript's speech model (docs/VOICE_CALLS.md C-3.11-9): Moonshine v2 Medium Streaming, through
 * Moonshine's own runtime (`moonshine-voice`, its ONNX Runtime inside), over the files [SttModelStore] holds (a
 * reinstall is a new generation, so a new lease). It is called through the library's `JNI`
 * class alone: its `Transcriber` wrapper would add a thread of its own and its own cadence, and the cadence here is
 * [app.getknit.knit.call.PassGate]'s, under the transcriber's lock.
 *
 * The loaded model lives on a [ModelLease] like the moderation models (ADR 2026-09.cq9z) — built on the first burst,
 * released when the call ends ([release]) or after [SttPolicy.IDLE_UNLOAD_MS] without one — and every build, a probe
 * stream included, runs under [ModelLoadGuard] (ADR 037), so a runtime that faults natively on this phone is latched
 * off instead of taking the process down on every burst. A model installed afresh gets a fresh lease: a lease whose load
 * failed stays spent, by design, and the new files deserve their own try. Every call a stream makes holds the lease, so
 * the reaper never frees the model under a burst; a stream whose model was freed or replaced meanwhile fails rather than
 * reach a handle that is gone.
 */
internal class MoonshineSpeech(
    private val store: SttModelStore,
    private val guard: ModelLoadGuard,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) : SpeechToText {
    /**
     * One loaded model: the runtime's handle for it, and the key terms it was last given. Compared by identity, since a
     * handle is reused once freed.
     */
    private class Engine(
        val handle: Int,
    ) {
        var keyterms: List<String> = emptyList()
    }

    private val lock = Any()
    private var lease: ModelLease<Engine>? = null

    // What the lease was built over: the store's generation, and how a burst becomes lines.
    private var leaseKey: Pair<Int, LineMode>? = null

    /**
     * How a burst becomes lines ([LineMode]). Read when the model loads, so a change makes the next burst reload it; the
     * debug bridge sets it for an A/B.
     */
    @Volatile
    var lines = LineMode.WHOLE

    /**
     * The names the next burst listens for (`sttKeyterms`): given to the model as a burst opens, when they differ from
     * what it has, since a load starts it with none. Never logged.
     */
    @Volatile
    var keyterms: List<String> = emptyList()

    override suspend fun open(): SpeechStream? {
        val current = current() ?: return null
        return current.use { engine ->
            engine ?: return@use null
            listen(engine)
            val stream = JNI.moonshineCreateStream(engine.handle, 0)
            if (stream < 0 || JNI.moonshineStartStream(engine.handle, stream) != JNI.MOONSHINE_ERROR_NONE) {
                if (stream >= 0) JNI.moonshineFreeStream(engine.handle, stream)
                log("walkie stt: no stream: $stream")
                return@use null
            }
            Stream(current, engine, stream)
        }
    }

    // Inside the lease: [keyterms] to [engine], if it has others. A list the runtime refuses is logged by its size and
    // not tried again until it changes; the burst goes on without it.
    @Suppress("TooGenericExceptionCaught") // the native runtime throws what it likes
    private fun listen(engine: Engine) {
        val wanted = keyterms
        if (wanted == engine.keyterms) return
        engine.keyterms = wanted
        val result =
            try {
                JNI.moonshineTranscriberSetKeyterms(engine.handle, wanted.joinToString(","))
            } catch (e: Exception) {
                log("walkie stt: key terms not set: ${e.javaClass.simpleName}")
                return
            }
        if (result != JNI.MOONSHINE_ERROR_NONE) log("walkie stt: ${wanted.size} key terms refused: ${JNI.moonshineErrorToString(result)}")
    }

    /** Releases the model now; the next burst builds it again. */
    suspend fun unload() {
        synchronized(lock) { lease }?.unload()
    }

    override suspend fun release() = unload()

    /** Whether a model is in memory now (the debug bridge). */
    val resident: Boolean get() = synchronized(lock) { lease }?.isResident == true

    /**
     * One burst's stream. Its own calls never overlap (the transcriber's lock), but they share the model with the other
     * side's burst, so each runs inside the lease's [ModelLease.use].
     */
    private inner class Stream(
        private val lease: ModelLease<Engine>,
        private val engine: Engine,
        private val stream: Int,
    ) : SpeechStream {
        private var closed = false

        override suspend fun add(samples: FloatArray): Boolean =
            call { JNI.moonshineAddAudioToStream(engine.handle, stream, samples, SttPolicy.SAMPLE_RATE, 0) == JNI.MOONSHINE_ERROR_NONE }
                ?: false

        override suspend fun look(): Heard? = call { heardOf(JNI.moonshineTranscribeStream(engine.handle, stream, 0), final = false) }

        override suspend fun finish(): Heard? {
            val heard =
                call {
                    JNI.moonshineStopStream(engine.handle, stream)
                    // What is left in the stream since the last look, as the library's own stop does.
                    heardOf(JNI.moonshineTranscribeStream(engine.handle, stream, JNI.MOONSHINE_FLAG_FORCE_UPDATE), final = true)
                }
            close()
            return heard
        }

        override suspend fun close() {
            if (closed) return
            closed = true
            lease.use { current -> if (current === engine) JNI.moonshineFreeStream(engine.handle, stream) }
        }

        // [block] on the model this stream was opened on, or null (and the stream closed) when that model is gone, the
        // runtime refused, or it threw.
        @Suppress("TooGenericExceptionCaught") // the native runtime throws what it likes
        private suspend fun <R : Any> call(block: () -> R?): R? {
            if (closed) return null
            val result =
                lease.use { current ->
                    if (current !== engine) return@use null
                    try {
                        block()
                    } catch (e: Exception) {
                        log("walkie stt: stream call failed: ${e.javaClass.simpleName}: ${e.message}")
                        null
                    }
                }
            if (result == null || result == false) close()
            return result
        }
    }

    // The lease over the model on disk now, made afresh when the store's files changed; null with no model there.
    private fun current(): ModelLease<Engine>? {
        val key = store.generation to lines
        val files = store.paths() ?: return null
        val model = store.model
        val dir = files.values.firstOrNull()?.parentFile ?: return null
        val (fresh, stale) =
            synchronized(lock) {
                val held = lease
                if (held != null && leaseKey == key) return held
                val made =
                    ModelLease(
                        scope = scope,
                        idleMs = SttPolicy.IDLE_UNLOAD_MS,
                        load = { guard.guard(ModelLoadGuard.STT) { build(model, dir, key.second == LineMode.SPLIT) } },
                        close = { JNI.moonshineFreeTranscriber(it.handle) },
                    )
                lease = made
                leaseKey = key
                made to held
            }
        stale?.let { scope.launch { it.unload() } }
        return fresh
    }

    // Under the guard: the model and one probe stream over silence, which is where a runtime that faults on this phone
    // faults.
    @Suppress("TooGenericExceptionCaught") // the native runtime throws what it likes, a missing .so as an Error
    private fun build(
        model: SttModel,
        dir: File,
        split: Boolean,
    ): Engine? {
        val started = System.currentTimeMillis()
        return try {
            JNI.ensureLibraryLoaded()
            val handle = JNI.moonshineLoadTranscriberFromFiles(dir.path, archOf(model.arch), optionsFor(split))
            if (handle < 0) {
                log("walkie stt: ${model.id} not loaded: ${JNI.moonshineErrorToString(handle)}")
                return null
            }
            if (!probe(handle)) {
                JNI.moonshineFreeTranscriber(handle)
                log("walkie stt: ${model.id} failed its probe")
                return null
            }
            val lines = if (split) "split at pauses" else "one line a burst"
            log("walkie stt: ${model.id} loaded in ${System.currentTimeMillis() - started} ms, $lines")
            Engine(handle)
        } catch (e: Throwable) {
            log("walkie stt: ${model.id} not loaded: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun probe(handle: Int): Boolean {
        val stream = JNI.moonshineCreateStream(handle, 0)
        if (stream < 0) return false
        try {
            if (JNI.moonshineStartStream(handle, stream) != JNI.MOONSHINE_ERROR_NONE) return false
            JNI.moonshineAddAudioToStream(handle, stream, FloatArray(PROBE_SAMPLES), SttPolicy.SAMPLE_RATE, 0)
            JNI.moonshineStopStream(handle, stream)
            return JNI.moonshineTranscribeStream(handle, stream, JNI.MOONSHINE_FLAG_FORCE_UPDATE) != null
        } finally {
            JNI.moonshineFreeStream(handle, stream)
        }
    }

    private companion object {
        // Half a second of silence: enough to run every graph once.
        const val PROBE_SAMPLES = SttPolicy.SAMPLE_RATE / 2

        // The longest line, in seconds: Moonshine's advice for one stretch of input. A longer burst splits there.
        const val MAX_LINE_S = 30

        // The text is never logged, and no line carries its audio back: the transcript wants the words alone. Unless
        // [split], the voice detector is off (threshold 0) and a line runs to [MAX_LINE_S], as Moonshine scores itself.
        fun optionsFor(split: Boolean): Array<Any> =
            listOfNotNull(
                TranscriberOption("log_output_text", "false"),
                TranscriberOption("return_audio_data", "false"),
                TranscriberOption("vad_threshold", "0").takeUnless { split },
                TranscriberOption("vad_max_segment_duration", "$MAX_LINE_S").takeUnless { split },
            ).toTypedArray()

        fun archOf(arch: SttArch): Int =
            when (arch) {
                SttArch.TINY_STREAMING -> JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING
                SttArch.SMALL_STREAMING -> JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING
                SttArch.MEDIUM_STREAMING -> JNI.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING
            }

        // The lines the model has closed at a pause are settled; the one still open is its live guess — unless this is
        // the stream's last look, which closes them all.
        fun heardOf(
            transcript: Transcript?,
            final: Boolean,
        ): Heard? {
            val lines = transcript?.lines ?: return null

            fun words(of: List<ai.moonshine.voice.TranscriptLine>) =
                of.mapNotNull { it.text?.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
            if (final) return Heard(words(lines))
            val (done, open) = lines.partition { it.isComplete }
            return Heard(words(done), words(open))
        }
    }
}

/**
 * How the transcript turns a burst into lines, the model's own voice detector being what splits one at a pause.
 * [WHOLE] keeps it off, a burst being one line of up to 30 s: the talk button already bounds the speech, and a split
 * decodes each half without the other, which Moonshine measured at 1.5–2 points of word error on clean speech (16.7 %
 * against 0 on the lab's noisy clip), though every look re-decodes the burst so far. [SPLIT] cuts at pauses: its looks
 * stay cheap and its text is final the moment the burst ends, and the seams cost words. A third mode, live words from a
 * split transcriber and the text from a second whole-burst one, read no better than [WHOLE] and cost more on every count
 * (ADR 2026-10.nz3g).
 */
internal enum class LineMode { WHOLE, SPLIT }
