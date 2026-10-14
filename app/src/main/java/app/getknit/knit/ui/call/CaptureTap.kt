package app.getknit.knit.ui.call

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import app.getknit.knit.call.CaptureWatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer

/** What the call screen and the bridge read about the open call's microphone (C-3.13-13). */
data class CaptureStatus(
    val health: CaptureWatch.Health,
    // The profile the call opened on, by name.
    val profile: String,
    // Whether the call moved to the fallback recorder mid-call.
    val switched: Boolean,
)

/**
 * The call's microphone, watched (docs/VOICE_CALLS.md C-3.13-13, ADR 2026-10.kw29). One per audio module, installed
 * as its buffer, error and state callbacks, so it sees every 10 ms buffer the module records, on the module's record
 * thread, before WebRTC encodes it.
 *
 * Each buffer's loudest sample feeds a [CaptureWatch]. When the module's own path turns out dead (zeros, or a record
 * error) on the standard profile, the tap [switch]es: the module stops opening its own `AudioRecord`
 * (`setAudioRecordEnabled(false)`, the webrtc-sdk build's external-audio path), and from its next buffer this tap fills
 * each one from a recorder of its own, on `VOICE_RECOGNITION` (then `MIC`). The blocking read paces the record thread
 * as the module's own read did. No renegotiation, no second call: the peer hears the switch as speech arriving.
 *
 * The cost, stated: on the standard profile WebRTC configured its own echo canceller off, the platform's being there, so
 * the rest of a switched call may echo on the loudspeaker. The verdict is recorded ([onSwitched]), so the next call
 * opens on the fallback profile with WebRTC's canceller on.
 */
internal class CaptureTap(
    private val audio: AudioManager,
    // Called once per call that switched: the verdict the next calls open on.
    private val onSwitched: () -> Unit,
    private val log: (String) -> Unit,
) : JavaAudioDeviceModule.AudioBufferCallback,
    JavaAudioDeviceModule.AudioRecordErrorCallback,
    JavaAudioDeviceModule.AudioRecordStateCallback {
    private val lock = Any()

    @Volatile private var watch = CaptureWatch(canSwitch = false)

    @Volatile private var profile = ""

    // Set while the call is muted (the track disabled); the module records on regardless, and a mute is not a verdict.
    @Volatile var muted = false

    // The module this tap is the callbacks of: what a switch tells to stop recording itself. Set once it is built.
    @Volatile var module: JavaAudioDeviceModule? = null

    /** A trial's dead microphone (`…debug.CALL --es op capture --es fault silence`): the module's own buffers zeroed. */
    @Volatile var faultSilence = false

    // The module records nothing itself and this tap fills its buffers: from a switch until the next call re-enables it.
    @Volatile private var external = false

    // Between begin() and end(). end() runs before the module stops recording, so the buffers in between belong to no call.
    @Volatile private var calling = false

    // The tap's own recorder after a switch; opened and read on the record thread only, released under [lock].
    private var record: AudioRecord? = null

    private val _status = MutableStateFlow<CaptureStatus?>(null)

    /** The open call's microphone, or null with no call. */
    val status: StateFlow<CaptureStatus?> = _status.asStateFlow()

    /** A call opens: watch afresh, recording through the module's own path. [canSwitch] is false on the fallback profile. */
    fun begin(
        profileName: String,
        canSwitch: Boolean,
    ) {
        synchronized(lock) {
            closeRecord()
            external = false
            // A call opens unmuted; the manager re-applies its mute to the new session (setMuted) if it has one.
            muted = false
            profile = profileName
            watch = CaptureWatch(canSwitch)
            calling = true
        }
        module?.setAudioRecordEnabled(true)
        publish()
    }

    /**
     * The call ended: give the tap's recorder back, and stop reporting. It runs before the module stops recording, so
     * [calling] is cleared under the lock: a buffer already waiting on it must find the call over, or it opens a
     * recorder again. [external] stays: after a switch the module still records nothing itself, and the tap paces its
     * thread until it stops.
     */
    fun end() {
        synchronized(lock) {
            calling = false
            closeRecord()
        }
        _status.value = null
    }

    override fun onBuffer(
        buffer: ByteBuffer,
        audioFormat: Int,
        channelCount: Int,
        sampleRate: Int,
        bytesRead: Int,
        captureTimestampNs: Long,
    ): Long {
        if (external) {
            readOwn(buffer, audioFormat, channelCount, sampleRate)
        } else if (faultSilence) {
            zero(buffer)
        }
        // A buffer between end() and the module's stop is no call's: judged, it would read as a dead microphone.
        if (!calling) return captureTimestampNs
        val frameMs = frameMs(buffer.capacity(), channelCount, sampleRate)
        watch.frame(peakOf(buffer), frameMs, muted, ::excuse)?.let(::changed)
        return captureTimestampNs
    }

    override fun onWebRtcAudioRecordInitError(errorMessage: String) = recordFailed("init: $errorMessage")

    override fun onWebRtcAudioRecordStartError(
        errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode,
        errorMessage: String,
    ) = recordFailed("start $errorCode: $errorMessage")

    override fun onWebRtcAudioRecordError(errorMessage: String) = recordFailed(errorMessage)

    override fun onWebRtcAudioRecordStart() = Unit

    override fun onWebRtcAudioRecordStop() {
        synchronized(lock) { closeRecord() }
    }

    private fun recordFailed(why: String) {
        log("call capture: record error ($why)")
        if (!calling) return
        watch.failed(::excuse)?.let(::changed)
    }

    private fun changed(health: CaptureWatch.Health) {
        log("call capture: $health on $profile${if (external) " (own recorder)" else ""}")
        if (health == CaptureWatch.Health.SWITCHED && !external) switch()
        publish()
    }

    /** From the next buffer on, the module records nothing itself and this tap fills its buffers. */
    private fun switch() {
        external = true
        module?.setAudioRecordEnabled(false)
        onSwitched()
    }

    private fun publish() {
        _status.value = CaptureStatus(watch.health, profile, watch.switched)
    }

    /**
     * Fills [buffer] from the tap's own recorder, opened on first use; zeros (paced) while none will open, and once the
     * call has ended, opening nothing.
     */
    private fun readOwn(
        buffer: ByteBuffer,
        audioFormat: Int,
        channelCount: Int,
        sampleRate: Int,
    ) {
        val paced =
            synchronized(lock) {
                if (!calling) {
                    zero(buffer)
                    return@synchronized false
                }
                val own = record ?: openRecord(audioFormat, channelCount, sampleRate).also { record = it }
                buffer.clear()
                val read = own?.read(buffer, buffer.capacity(), AudioRecord.READ_BLOCKING) ?: -1
                if (read < buffer.capacity()) {
                    for (i in maxOf(read, 0) until buffer.capacity()) buffer.put(i, 0)
                }
                read >= 0
            }
        if (!paced) Thread.sleep(frameMs(buffer.capacity(), channelCount, sampleRate).toLong())
    }

    @SuppressLint("MissingPermission") // a call's media opens only with RECORD_AUDIO held (CallActivity.answer, dial)
    private fun openRecord(
        audioFormat: Int,
        channelCount: Int,
        sampleRate: Int,
    ): AudioRecord? {
        val mask = if (channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val min = AudioRecord.getMinBufferSize(sampleRate, mask, audioFormat)
        for (source in OWN_SOURCES) {
            val opened =
                runCatching {
                    AudioRecord(
                        source,
                        sampleRate,
                        mask,
                        audioFormat,
                        maxOf(min, sampleRate / BUFFERS_PER_S * 2),
                    )
                }.getOrNull()
            if (opened?.state == AudioRecord.STATE_INITIALIZED) {
                runCatching { opened.startRecording() }
                if (opened.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    log("call capture: own recorder on source $source, ${sampleRate}Hz x$channelCount")
                    return opened
                }
            }
            opened?.let { runCatching { it.release() } }
        }
        log("call capture: own recorder would not open")
        return null
    }

    private fun closeRecord() {
        val own = record ?: return
        record = null
        runCatching { own.stop() }
        runCatching { own.release() }
    }

    /** Asked as a silent run completes: a microphone the platform muted or silenced is not a dead one. */
    private fun excuse(): CaptureWatch.Excuse? {
        if (runCatching { audio.isMicrophoneMute }.getOrDefault(false)) return CaptureWatch.Excuse.MUTED
        val silenced = runCatching { audio.activeRecordingConfigurations.any { it.isClientSilenced } }.getOrDefault(false)
        return if (silenced) CaptureWatch.Excuse.SILENCED else null
    }

    private companion object {
        /** The walkie's order (`TalkRecorder`): the phone's microphone without the call-tuned VoIP route. */
        val OWN_SOURCES = listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)

        const val BUFFERS_PER_S = 10
        const val PCM_BYTES = 2
        const val MS_PER_S = 1_000

        fun frameMs(
            bytes: Int,
            channels: Int,
            rate: Int,
        ): Int = if (rate <= 0 || channels <= 0) 0 else bytes / (PCM_BYTES * channels) * MS_PER_S / rate

        /** The loudest 16-bit little-endian sample in [buffer], by absolute index (the position is not ours to move). */
        fun peakOf(buffer: ByteBuffer): Int {
            var peak = 0
            var i = 0
            val end = buffer.capacity() - 1
            while (i < end) {
                val sample = ((buffer.get(i + 1).toInt() shl 8) or (buffer.get(i).toInt() and 0xFF)).toShort().toInt()
                val abs = if (sample < 0) -sample else sample
                if (abs > peak) peak = abs
                if (peak > CaptureWatch.DIGITAL_SILENCE) return peak
                i += PCM_BYTES
            }
            return peak
        }

        fun zero(buffer: ByteBuffer) {
            for (i in 0 until buffer.capacity()) buffer.put(i, 0)
        }
    }
}
