package app.getknit.knit.debug.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * The phone's audio route for a call (experimental, debug only): communication mode and transient voice focus while a
 * call's media is open, the earpiece by default and the loudspeaker on request, all put back when it closes. Counted,
 * so a glare's dropped session closing after its replacement opened does not tear the route down under it.
 */
internal class CallAudio(
    context: Context,
) {
    private val manager = checkNotNull(context.getSystemService(AudioManager::class.java))
    private var users = 0
    private var previousMode = AudioManager.MODE_NORMAL
    private var focus: AudioFocusRequest? = null

    @Synchronized
    fun begin() {
        if (users++ > 0) return
        previousMode = manager.mode
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        val request =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                ).build()
        manager.requestAudioFocus(request)
        focus = request
    }

    @Synchronized
    fun end() {
        if (users == 0 || --users > 0) return
        route(speaker = false)
        focus?.let(manager::abandonAudioFocusRequest)
        focus = null
        manager.mode = previousMode
    }

    @Synchronized
    fun setSpeaker(on: Boolean) {
        if (users > 0) route(on)
    }

    private fun route(speaker: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (speaker) {
                manager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let(manager::setCommunicationDevice)
            } else {
                manager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION") // the only switch below API 31
            manager.isSpeakerphoneOn = speaker
        }
    }
}
