package app.getknit.knit.debug.call

import android.content.Context
import app.getknit.knit.call.CallMedia
import app.getknit.knit.call.CallSession
import app.getknit.knit.call.MediaEvent
import app.getknit.knit.call.TurnServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume

/**
 * [CallMedia] over libwebrtc (experimental, debug only — the dependency is `debugImplementation`): one audio-only
 * Unified Plan peer connection per call, Opus over DTLS-SRTP, bundled and RTCP-muxed (C-3.7-4). The factory and its
 * audio device module are built on the first call and kept for the process.
 */
internal class WebRtcCallMedia(
    private val context: Context,
    private val audio: CallAudio,
    private val log: (String) -> Unit,
) : CallMedia {
    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions(),
        )
        val adm =
            JavaAudioDeviceModule
                .builder(context.applicationContext)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
        PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory()
    }

    override fun open(
        servers: List<TurnServer>,
        relayOnly: Boolean,
        events: (MediaEvent) -> Unit,
    ): CallSession = WebRtcSession(factory, servers, relayOnly, events, audio, log)
}

private class WebRtcSession(
    factory: PeerConnectionFactory,
    servers: List<TurnServer>,
    relayOnly: Boolean,
    private val events: (MediaEvent) -> Unit,
    private val audio: CallAudio,
    private val log: (String) -> Unit,
) : CallSession {
    private val lock = Any()
    private val gathered = CompletableDeferred<Unit>()

    // Set when our description is handed to the call layer: a candidate after it is a late one (C-3.6-2).
    private var described = false
    private val late = mutableListOf<String>()
    private var lateReported = false
    private var closed = false

    @Volatile
    private var selected: String? = null

    private val source: AudioSource = factory.createAudioSource(MediaConstraints())
    private val track: AudioTrack = factory.createAudioTrack("knit-audio", source)

    private val observer =
        object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                log("webrtc: connection $state")
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> events(MediaEvent.Connected)
                    PeerConnection.PeerConnectionState.DISCONNECTED -> events(MediaEvent.Disconnected)
                    PeerConnection.PeerConnectionState.FAILED -> events(MediaEvent.Failed)
                    else -> Unit
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state != PeerConnection.IceGatheringState.COMPLETE) return
                gathered.complete(Unit)
                val lines =
                    synchronized(lock) {
                        if (!described || lateReported) return
                        lateReported = true
                        late.toList()
                    }
                if (lines.isNotEmpty()) events(MediaEvent.LateCandidates(lines))
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                synchronized(lock) { if (described && !lateReported) late += candidate.sdp }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
                selected = "${typeOf(event.local)}/${typeOf(event.remote)}"
                log("webrtc: route $selected")
            }

            override fun onAddStream(stream: MediaStream) = Unit

            override fun onRemoveStream(stream: MediaStream) = Unit

            override fun onDataChannel(channel: DataChannel) = Unit

            override fun onRenegotiationNeeded() = Unit
        }

    private val pc: PeerConnection

    init {
        val ice =
            servers.map {
                PeerConnection.IceServer
                    .builder(it.urls)
                    .setUsername(it.user)
                    .setPassword(it.pass)
                    .createIceServer()
            }
        val config =
            PeerConnection.RTCConfiguration(ice).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
                // C-3.7-3: relay candidates only; SdpGuard strips anything else on the way out as well.
                iceTransportsType = if (relayOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
            }
        pc = checkNotNull(factory.createPeerConnection(config, observer)) { "no peer connection" }
        pc.addTrack(track, listOf("knit"))
        audio.begin()
        log("webrtc: opened (${servers.size} TURN, relay=$relayOnly)")
    }

    override suspend fun offer(): String? {
        val description = create { pc.createOffer(it, MediaConstraints()) } ?: return null
        if (!apply { pc.setLocalDescription(it, description) }) return null
        return gatheredDescription()
    }

    override suspend fun answer(remoteOffer: String): String? {
        if (!apply { pc.setRemoteDescription(it, SessionDescription(SessionDescription.Type.OFFER, remoteOffer)) }) return null
        val description = create { pc.createAnswer(it, MediaConstraints()) } ?: return null
        if (!apply { pc.setLocalDescription(it, description) }) return null
        return gatheredDescription()
    }

    override suspend fun accept(remoteAnswer: String): Boolean =
        apply { pc.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, remoteAnswer)) }

    override fun addCandidates(lines: List<String>) {
        if (synchronized(lock) { closed }) return
        // One audio m-line, bundled: its mid is "0" under Unified Plan.
        lines.forEach { pc.addIceCandidate(IceCandidate("0", 0, it)) }
    }

    override fun setMuted(muted: Boolean) {
        if (synchronized(lock) { !closed }) track.setEnabled(!muted)
    }

    override fun setSpeaker(on: Boolean) = audio.setSpeaker(on)

    override suspend fun route(): String? = selected

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        gathered.complete(Unit)
        // dispose() closes the connection and its senders; the source is ours to free.
        pc.dispose()
        source.dispose()
        audio.end()
        log("webrtc: closed")
    }

    /** Waits for gathering (C-3.6-1: up to [GATHER_MS]), then hands over the description with what was gathered. */
    private suspend fun gatheredDescription(): String? {
        withTimeoutOrNull(GATHER_MS) { gathered.await() }
        synchronized(lock) {
            if (closed) return null
            described = true
            // Gathering already finished: nothing can be late.
            if (gathered.isCompleted) lateReported = true
        }
        return pc.localDescription?.description
    }

    private suspend fun create(call: (SdpObserver) -> Unit): SessionDescription? =
        withTimeoutOrNull(SDP_MS) {
            suspendCancellableCoroutine { cont ->
                call(
                    object : SdpObserver {
                        override fun onCreateSuccess(description: SessionDescription) = cont.resume(description)

                        override fun onCreateFailure(error: String?) {
                            log("webrtc: create failed: $error")
                            cont.resume(null)
                        }

                        override fun onSetSuccess() = Unit

                        override fun onSetFailure(error: String?) = Unit
                    },
                )
            }
        }

    private suspend fun apply(call: (SdpObserver) -> Unit): Boolean =
        withTimeoutOrNull(SDP_MS) {
            suspendCancellableCoroutine { cont ->
                call(
                    object : SdpObserver {
                        override fun onCreateSuccess(description: SessionDescription) = Unit

                        override fun onCreateFailure(error: String?) = Unit

                        override fun onSetSuccess() = cont.resume(true)

                        override fun onSetFailure(error: String?) {
                            log("webrtc: set failed: $error")
                            cont.resume(false)
                        }
                    },
                )
            }
        } == true

    private companion object {
        const val GATHER_MS = 1_500L
        const val SDP_MS = 10_000L

        fun typeOf(candidate: IceCandidate): String = candidate.sdp.substringAfter(" typ ", "?").substringBefore(' ')
    }
}
