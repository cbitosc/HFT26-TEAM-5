package app.getknit.knit.call

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One burst as the transcript's model got it, for a debug build's lab eval (C-3.11-9): [samples] at
 * [SttPolicy.SAMPLE_RATE] mono, −1..1, the whole burst, and [line], what the transcript made of it once it was final.
 */
class WalkieClip(
    val line: TranscriptLine,
    val samples: FloatArray,
)

/**
 * Where a debug build keeps the walkie's bursts while Diagnostics' "Save walkie clips" is on (`SettingsStore.debugWalkieClips`,
 * off by default), so the speech model can be scored against real conversations off the phone. Unencrypted, by design:
 * a lab phone's clips are pulled over adb. A release build never keeps one.
 */
interface WalkieClips {
    /** Whether a burst beginning now is kept. Read once, as it begins. */
    val on: Boolean

    /** Keeps [clip]. Called on the transcriber's dispatcher, so it must hand the writing off, never wait on it. */
    fun keep(clip: WalkieClip)
}

/** A burst's audio as it is drained to the model, growing as it comes. Pure; not thread-safe. */
class SampleTape {
    private var held = FloatArray(SttPolicy.SAMPLE_RATE)
    private var size = 0

    fun add(samples: FloatArray) {
        if (size + samples.size > held.size) held = held.copyOf(maxOf(size + samples.size, held.size * 2))
        samples.copyInto(held, size)
        size += samples.size
    }

    fun samples(): FloatArray = held.copyOf(size)
}

/**
 * [samples] (−1..1) as a 16-bit PCM mono WAV at [rate] Hz: the 44-byte canonical header, then the samples little-endian
 * — what `…debug.STT`'s `pcm` op reads back, header skipped, and what any player opens. Pure.
 */
fun wavOf(
    samples: FloatArray,
    rate: Int = SttPolicy.SAMPLE_RATE,
): ByteArray {
    val data = samples.size * BYTES_PER_SAMPLE
    val out = ByteBuffer.allocate(WAV_HEADER_BYTES + data).order(ByteOrder.LITTLE_ENDIAN)
    out.put("RIFF".toByteArray()).putInt(WAV_HEADER_BYTES - RIFF_PREAMBLE_BYTES + data).put("WAVE".toByteArray())
    out
        .put("fmt ".toByteArray())
        .putInt(FMT_CHUNK_BYTES)
        .putShort(PCM_FORMAT)
        .putShort(1)
    out
        .putInt(rate)
        .putInt(rate * BYTES_PER_SAMPLE)
        .putShort(BYTES_PER_SAMPLE.toShort())
        .putShort(BITS_PER_SAMPLE)
    out.put("data".toByteArray()).putInt(data)
    samples.forEach { out.putShort((it.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()) }
    return out.array()
}

private const val WAV_HEADER_BYTES = 44
private const val RIFF_PREAMBLE_BYTES = 8
private const val FMT_CHUNK_BYTES = 16
private const val PCM_FORMAT: Short = 1
private const val BYTES_PER_SAMPLE = 2
private const val BITS_PER_SAMPLE: Short = 16
