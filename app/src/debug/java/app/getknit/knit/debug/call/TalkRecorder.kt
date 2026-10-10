package app.getknit.knit.debug.call

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import app.getknit.knit.call.WalkiePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Records a walkie burst as Opus packets while it is spoken (docs/VOICE_CALLS.md §3.11): the microphone through
 * [AudioRecord] at 16 kHz mono into the platform Opus encoder at [WalkiePolicy.BIT_RATE], each 20 ms packet handed on
 * the moment it comes out. There is no container, no file, and nothing on disk. The chat's `VoiceRecorder` writes AAC
 * through `MediaRecorder`, which at a speech rate is about twice Opus's size and buffers besides.
 *
 * One burst at a time: `WalkieTalk` calls [start], [stop] and [cancel] under its lock. The capture loop runs on one IO
 * coroutine, which alone feeds and drains the encoder and releases both at its end.
 */
internal class TalkRecorder(
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    @Volatile
    private var stopping = false

    @Volatile
    private var dropping = false

    @Volatile
    private var startedAt = 0L

    @Volatile
    private var peak = 0

    /** Milliseconds since [start], or 0 when not recording. */
    fun elapsedMs(): Long = startedAt.let { if (it == 0L) 0L else System.currentTimeMillis() - it }

    /** The loudest sample since the last call, 0f..1f, for the live meter. */
    fun amplitude(): Float {
        val p = peak
        peak = 0
        return (p / MAX_AMPLITUDE).coerceIn(0f, 1f)
    }

    /** Begins a burst, handing each Opus packet to [onPacket] on the capture thread; false when the microphone is busy. */
    fun start(onPacket: (ByteArray) -> Unit): Boolean {
        if (job != null) return false
        val record = openRecord() ?: return false
        val codec = openEncoder()
        if (codec == null) {
            record.release()
            return false
        }
        stopping = false
        dropping = false
        peak = 0
        startedAt = System.currentTimeMillis()
        job = scope.launch(Dispatchers.IO) { pump(record, codec, onPacket) }
        return true
    }

    /** Ends the burst; its last packets are flushed out of the encoder and handed on before this returns. */
    suspend fun stop() = end(drop = false)

    /** Ends the burst without its tail. */
    suspend fun cancel() = end(drop = true)

    private suspend fun end(drop: Boolean) {
        dropping = drop
        stopping = true
        job?.join()
        job = null
        startedAt = 0L
    }

    // The capture loop: 20 ms of PCM in, whatever packets the encoder has out, until a stop; then the flush.
    private fun pump(
        record: AudioRecord,
        codec: MediaCodec,
        onPacket: (ByteArray) -> Unit,
    ) {
        val pcm = ByteArray(FRAME_BYTES)
        val info = MediaCodec.BufferInfo()
        var ptsUs = 0L
        try {
            while (!stopping) {
                val n = record.read(pcm, 0, pcm.size)
                if (n < 0) break
                if (n == 0) continue
                peak = maxOf(peak, peakOf(pcm, n))
                if (!queue(codec, pcm, n, ptsUs, 0)) break
                ptsUs += n / PCM_BYTES * US_PER_S / WalkiePolicy.SAMPLE_RATE
                drain(codec, info, onPacket, untilEnd = false)
            }
            if (!dropping && queue(codec, pcm, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)) {
                drain(codec, info, onPacket, untilEnd = true)
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // The platform codec and the capture throw what they like; either way the burst ends with what went out.
            Log.w(TAG, "walkie capture ended: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private fun queue(
        codec: MediaCodec,
        pcm: ByteArray,
        size: Int,
        ptsUs: Long,
        flags: Int,
    ): Boolean {
        repeat(INPUT_TRIES) {
            val index = codec.dequeueInputBuffer(DEQUEUE_US)
            if (index >= 0) {
                codec.getInputBuffer(index)?.apply {
                    clear()
                    put(pcm, 0, size)
                }
                codec.queueInputBuffer(index, 0, size, ptsUs, flags)
                return true
            }
        }
        return false
    }

    private fun drain(
        codec: MediaCodec,
        info: MediaCodec.BufferInfo,
        onPacket: (ByteArray) -> Unit,
        untilEnd: Boolean,
    ) {
        var idle = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (untilEnd) DEQUEUE_US else 0L)
            if (index < 0) {
                if (!untilEnd || ++idle >= END_TRIES) return
                continue
            }
            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (!config && info.size > 0) {
                codec.getOutputBuffer(index)?.let { out ->
                    out.position(info.offset)
                    onPacket(ByteArray(info.size).also { out.get(it) })
                }
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    // VOICE_RECOGNITION first (no call-tuned AGC pumping on speech), MIC where the HAL refuses it.
    @SuppressLint("MissingPermission") // the call screen holds RECORD_AUDIO before a walkie session can connect
    private fun openRecord(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(WalkiePolicy.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        for (source in SOURCES) {
            val record =
                runCatching {
                    AudioRecord(
                        source,
                        WalkiePolicy.SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(min, FRAME_BYTES * BUFFER_FRAMES),
                    )
                }.getOrNull() ?: continue
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                runCatching { record.startRecording() }
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) return record
            }
            runCatching { record.release() }
        }
        Log.w(TAG, "walkie microphone would not open")
        return null
    }

    private fun openEncoder(): MediaCodec? =
        runCatching {
            val format =
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, WalkiePolicy.SAMPLE_RATE, 1).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, WalkiePolicy.BIT_RATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES * BUFFER_FRAMES)
                }
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
                // Constant bitrate keeps every packet one size, so a chunk's size says nothing about what was said, to
                // a radio carrier or a relay (docs/VOICE_CALLS.md §2.7). Asked for only where the encoder says it can:
                // elsewhere its default stands, and a cast's padding still keeps a relay's view to a size class.
                val caps = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_OPUS).encoderCapabilities
                if (caps?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true) {
                    format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                }
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        }.onFailure { Log.w(TAG, "walkie Opus encoder failed: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()

    private fun peakOf(
        pcm: ByteArray,
        size: Int,
    ): Int {
        var max = 0
        var i = 0
        while (i + 1 < size) {
            val sample = (pcm[i].toInt() and BYTE) or (pcm[i + 1].toInt() shl BYTE_BITS)
            max = maxOf(max, abs(sample.toShort().toInt()))
            i += PCM_BYTES
        }
        return max
    }

    private companion object {
        const val TAG = "KnitCall"
        const val PCM_BYTES = 2

        // 20 ms of 16 kHz mono PCM: one Opus frame's worth.
        const val FRAME_BYTES = WalkiePolicy.SAMPLE_RATE / 50 * PCM_BYTES
        const val BUFFER_FRAMES = 4
        const val US_PER_S = 1_000_000L
        const val DEQUEUE_US = 10_000L
        const val INPUT_TRIES = 20
        const val END_TRIES = 20
        const val MAX_AMPLITUDE = 32_767f
        const val BYTE = 0xFF
        const val BYTE_BITS = 8

        val SOURCES = listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
    }
}
