package app.getknit.knit.ui.call.stt

import app.getknit.knit.call.SpeechToText
import app.getknit.knit.call.SttPolicy
import app.getknit.knit.moderation.ModelLease
import app.getknit.knit.moderation.ModelLoadGuard
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * The walkie transcript's speech model (docs/VOICE_CALLS.md C-3.11-9): Moonshine tiny EN v2 through sherpa-onnx's
 * ONNX Runtime, built over the files [SttModelStore] holds. The recognizer lives on a [ModelLease] like the moderation
 * models (ADR 2026-09.cq9z) — built on the first burst, released after [SttPolicy.IDLE_UNLOAD_MS] without one — and every
 * build, a probe decode included, runs under [ModelLoadGuard] (ADR 037), so a runtime that faults natively on this phone
 * is latched off instead of taking the process down on every burst. A model installed afresh gets a fresh lease: a lease
 * whose load failed stays spent, by design, and the new files deserve their own try.
 */
internal class SherpaSpeech(
    private val store: SttModelStore,
    private val guard: ModelLoadGuard,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) : SpeechToText {
    private val lock = Any()
    private var lease: ModelLease<OfflineRecognizer>? = null
    private var leaseGeneration = -1

    override suspend fun transcribe(samples: FloatArray): String? {
        val current = current() ?: return null
        return current.use { recognizer -> recognizer?.let { decodeOrNull(it, samples) } }
    }

    /** Releases the recognizer now; the next burst builds it again. */
    suspend fun unload() {
        synchronized(lock) { lease }?.unload()
    }

    /** Whether a recognizer is in memory now (the debug bridge). */
    val resident: Boolean get() = synchronized(lock) { lease }?.isResident == true

    // The lease over the model on disk now, made afresh when the store's files changed; null with no model there.
    private fun current(): ModelLease<OfflineRecognizer>? {
        val files = store.paths() ?: return null
        val generation = store.generation
        val (fresh, stale) =
            synchronized(lock) {
                val held = lease
                if (held != null && leaseGeneration == generation) return held
                val made =
                    ModelLease(
                        scope = scope,
                        idleMs = SttPolicy.IDLE_UNLOAD_MS,
                        load = { guard.guard(ModelLoadGuard.STT) { build(files) } },
                        close = { it.release() },
                    )
                lease = made
                leaseGeneration = generation
                made to held
            }
        stale?.let { scope.launch { it.unload() } }
        return fresh
    }

    // Under the guard: the recognizer and one probe decode, which is where a runtime that faults on this phone faults.
    @Suppress("TooGenericExceptionCaught") // the native runtime throws what it likes, a missing .so as an Error
    private fun build(files: Map<String, File>): OfflineRecognizer? {
        val started = System.currentTimeMillis()
        return try {
            val config =
                OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SttPolicy.SAMPLE_RATE, featureDim = FEATURE_DIM),
                    modelConfig =
                        OfflineModelConfig(
                            moonshine =
                                OfflineMoonshineModelConfig(
                                    encoder = files.getValue(ENCODER).path,
                                    mergedDecoder = files.getValue(DECODER).path,
                                ),
                            tokens = files.getValue(TOKENS).path,
                            numThreads = THREADS,
                            provider = "cpu",
                        ),
                    decodingMethod = "greedy_search",
                )
            val recognizer = OfflineRecognizer(null, config)
            decode(recognizer, FloatArray(PROBE_SAMPLES))
            log("walkie stt: recognizer built in ${System.currentTimeMillis() - started} ms")
            recognizer
        } catch (e: Throwable) {
            log("walkie stt: recognizer not built: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // A burst the runtime throws on is one burst lost, never the transcript's loop.
    @Suppress("TooGenericExceptionCaught") // as in build
    private fun decodeOrNull(
        recognizer: OfflineRecognizer,
        samples: FloatArray,
    ): String? =
        try {
            decode(recognizer, samples)
        } catch (e: Exception) {
            log("walkie stt: decode failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }

    private fun decode(
        recognizer: OfflineRecognizer,
        samples: FloatArray,
    ): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SttPolicy.SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    private companion object {
        const val ENCODER = "encoder_model.ort"
        const val DECODER = "decoder_model_merged.ort"
        const val TOKENS = "tokens.txt"
        const val FEATURE_DIM = 80

        // Two threads, as the moderation models run (TfLiteModels): half the wall time of one, for about the same energy.
        const val THREADS = 2

        // Half a second of silence: enough to run the encoder and the decoder once.
        const val PROBE_SAMPLES = SttPolicy.SAMPLE_RATE / 2
    }
}
