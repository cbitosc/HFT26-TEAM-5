package app.getknit.knit.debug.call

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import app.getknit.knit.call.OpusPackets
import kotlinx.coroutines.delay
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Plays the peer's walkie bursts as their chunks land (docs/VOICE_CALLS.md §3.11): the platform Opus decoder, configured
 * with the `OpusHead` an extractor would hand it and fed the bare packets, into a streaming [AudioTrack] on the
 * voice-communication usage. That usage follows the call's route, which Telecom holds for the whole session along with
 * its focus (§3.13): the loudspeaker a walkie starts on, the earpiece or a headset when the user picks one, and a
 * headset that takes the call — a Bluetooth one over its call link, both ways.
 *
 * Driven by one coroutine (`WalkieTalk`'s player loop, on the IO dispatcher); only [hold] comes from another thread.
 * Writes never block: a full or held track is waited on with [delay], so the loop stays cancellable when the session
 * ends and [close] runs after it, never beside it.
 */
internal class TalkPlayer {
    private val info = MediaCodec.BufferInfo()
    private val trackLock = Any()

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null

    // PCM frames written into [track], its frame size, and the next access unit's timestamp.
    private var written = 0L
    private var frameBytes = 2
    private var ptsUs = 0L

    @Volatile
    private var held = false

    /**
     * Plays [packets] (Opus) of a burst: [first] opens it, [last] plays it out and closes it. Suspends while the track
     * is full or [hold] holds it.
     */
    suspend fun play(
        packets: List<ByteArray>,
        first: Boolean,
        last: Boolean,
    ) {
        // A burst left open — its last chunk lost and given up on elsewhere — gives way to the next.
        if (first) close()
        if (codec == null && packets.isNotEmpty()) open()
        codec?.let { c ->
            for (packet in packets) feed(c, packet)
            if (last) {
                endOfStream(c)
                playOut()
            }
        }
        if (last) close()
    }

    /** Holds playback while this phone talks, and lets it go on after; what arrives meanwhile waits. */
    fun hold(on: Boolean) {
        held = on
        synchronized(trackLock) {
            val t = track ?: return
            if (on) t.pause() else t.play()
        }
    }

    /** Releases the decoder and the track; nothing if no burst is open. */
    fun close() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        synchronized(trackLock) {
            track?.let {
                // Where the burst played: the route is Telecom's, so this is the one place it shows (§3.13).
                Log.i(TAG, "walkie out: ${it.routedDevice?.type?.let(::deviceName) ?: "unrouted"}")
                runCatching { it.pause() }
                runCatching { it.flush() }
                runCatching { it.release() }
            }
            track = null
        }
    }

    private fun open() {
        // Opus always decodes to 48 kHz; the burst is mono.
        val format =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, OPUS_RATE, 1).apply {
                setByteBuffer(CSD_0, ByteBuffer.wrap(opusHead()))
                setByteBuffer(CSD_1, nanos(0L))
                setByteBuffer(CSD_2, nanos(SEEK_PREROLL_NS))
            }
        codec =
            runCatching {
                MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
                    configure(format, null, null, 0)
                    start()
                }
            }.onFailure { Log.w(TAG, "walkie decoder failed: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull() ?: return
        ptsUs = 0L
        newTrack(OPUS_RATE, 1)
    }

    private fun newTrack(
        rate: Int,
        channels: Int,
    ) {
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val t =
            AudioTrack
                .Builder()
                .setAudioAttributes(ATTRIBUTES)
                .setAudioFormat(
                    AudioFormat
                        .Builder()
                        .setSampleRate(rate)
                        .setChannelMask(mask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                ).setBufferSizeInBytes(maxOf(min, rate * channels * PCM_BYTES * BUFFER_MS / MS_PER_S))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        synchronized(trackLock) {
            track?.release()
            track = t
            written = 0L
            frameBytes = channels * PCM_BYTES
            if (!held) t.play()
        }
    }

    private suspend fun feed(
        c: MediaCodec,
        unit: ByteArray,
    ) {
        repeat(INPUT_TRIES) {
            val index = c.dequeueInputBuffer(DEQUEUE_US)
            if (index >= 0) {
                c.getInputBuffer(index)?.apply {
                    clear()
                    put(unit)
                }
                c.queueInputBuffer(index, 0, unit.size, ptsUs, 0)
                ptsUs += OpusPackets.durationUs(unit)
                drain(c, untilEnd = false)
                return
            }
            // No free input until the decoder hands some output on.
            drain(c, untilEnd = false)
        }
        Log.w(TAG, "walkie decoder took no input; a packet dropped")
    }

    private suspend fun endOfStream(c: MediaCodec) {
        val index = c.dequeueInputBuffer(DEQUEUE_US)
        if (index < 0) return
        c.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(c, untilEnd = true)
    }

    // Hands the decoder's PCM to the track: what is ready now, or (at a burst's end) everything up to its EOS.
    private suspend fun drain(
        c: MediaCodec,
        untilEnd: Boolean,
    ) {
        var idle = 0
        while (true) {
            val index = c.dequeueOutputBuffer(info, if (untilEnd) DEQUEUE_US else 0L)
            when {
                index >= 0 -> {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    c.getOutputBuffer(index)?.takeIf { info.size > 0 }?.let { write(it, info.offset, info.size) }
                    c.releaseOutputBuffer(index, false)
                    if (eos) return
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    retune(c.outputFormat)
                }

                !untilEnd || ++idle >= END_TRIES -> {
                    return
                }
            }
        }
    }

    // A decoder that settles on another rate or layout than the header said (SBR, say) gets a track to match.
    private fun retune(format: MediaFormat) {
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
        val t = track
        if (t == null || t.sampleRate != rate || t.channelCount != channels) newTrack(rate, channels)
    }

    private suspend fun write(
        pcm: ByteBuffer,
        offset: Int,
        size: Int,
    ) {
        pcm.position(offset)
        pcm.limit(offset + size)
        while (pcm.hasRemaining()) {
            val t = track ?: return
            val n = t.write(pcm, pcm.remaining(), AudioTrack.WRITE_NON_BLOCKING)
            if (n < 0) return
            if (n == 0) delay(WAIT_MS)
        }
        written += size / frameBytes
    }

    /**
     * Lets the track play the burst out before [close] releases it. Never `stop()`: it zeroes the head position this
     * waits on (device-observed, a 4 s stall per burst). A buffer of silence after the burst carries its last frames
     * past the track's start threshold instead, and the release cuts the silence off. A held track waits for its release.
     */
    private suspend fun playOut() {
        val t = track ?: return
        val burst = written
        val rate = t.sampleRate.coerceAtLeast(1)
        val silence = ByteBuffer.allocateDirect(t.bufferSizeInFrames * frameBytes)
        write(silence, 0, silence.capacity())
        var waited = 0L
        val budget = burst * MS_PER_S / rate + TAIL_SLACK_MS
        while (t.playbackHeadPosition.toLong() < burst) {
            if (!held) {
                if (waited >= budget) break
                waited += WAIT_MS
            }
            delay(WAIT_MS)
        }
    }

    // The identification header an Ogg Opus stream opens with (RFC 7845 §5.1): version 1, mono, no pre-skip, 16 kHz in.
    private fun opusHead(): ByteArray =
        ByteBuffer
            .allocate(OPUS_HEAD_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusHead".toByteArray(Charsets.US_ASCII))
            .put(1)
            .put(1)
            .putShort(0)
            .putInt(16_000)
            .putShort(0)
            .put(0)
            .array()

    private fun nanos(value: Long): ByteBuffer =
        ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.nativeOrder()).putLong(value).apply {
            flip()
        }

    private companion object {
        const val TAG = "KnitCall"
        const val CSD_0 = "csd-0"
        const val CSD_1 = "csd-1"
        const val CSD_2 = "csd-2"
        const val OPUS_RATE = 48_000
        const val OPUS_HEAD_BYTES = 19
        const val SEEK_PREROLL_NS = 80_000_000L
        const val PCM_BYTES = 2
        const val BUFFER_MS = 250
        const val MS_PER_S = 1_000
        const val DEQUEUE_US = 10_000L
        const val INPUT_TRIES = 50
        const val END_TRIES = 20
        const val WAIT_MS = 10L
        const val TAIL_SLACK_MS = 500L

        val ATTRIBUTES: AudioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
    }
}

/**
 * An audio device's type as a walkie log line names it (`walkie in:` / `walkie out:`), for the device trials.
 * `TYPE_BLE_HEADSET` is API 31, but an inlined int: an older phone never reports it.
 */
@SuppressLint("InlinedApi")
internal fun deviceName(type: Int): String =
    when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "phone mic"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth sco"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth a2dp"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "bluetooth le"
        AudioDeviceInfo.TYPE_HEARING_AID -> "hearing aid"
        else -> "type $type"
    }
