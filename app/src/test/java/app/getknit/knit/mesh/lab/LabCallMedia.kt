package app.getknit.knit.mesh.lab

import app.getknit.knit.call.CallMedia
import app.getknit.knit.call.CallSession
import app.getknit.knit.call.MediaEvent
import app.getknit.knit.call.TurnServer

/**
 * A call's media in the lab (`call/CallMedia`, docs/VOICE_CALLS.md §3.7): no audio and no ICE, only the SDP a real
 * session hands the call layer, and "ICE connected" the moment this side holds both descriptions — the caller once it
 * accepts the answer, the callee once it answers. The lab runs a call's signalling between real stacks; the media is
 * the debug build's, and `CallManagerTest` drives its failures.
 */
internal class LabCallMedia : CallMedia {
    override fun open(
        servers: List<TurnServer>,
        relayOnly: Boolean,
        direct: Boolean,
        events: (MediaEvent) -> Unit,
    ): CallSession = Session(events)

    private class Session(
        private val events: (MediaEvent) -> Unit,
    ) : CallSession {
        @Volatile
        private var closed = false

        override suspend fun offer(): String = SDP

        override suspend fun answer(remoteOffer: String): String = SDP.also { connected() }

        override suspend fun accept(remoteAnswer: String): Boolean = true.also { connected() }

        override suspend fun restart(servers: List<TurnServer>): String = SDP

        override suspend fun reanswer(
            remoteOffer: String,
            servers: List<TurnServer>,
        ): String = SDP

        override fun addCandidates(lines: List<String>) = Unit

        override fun setMuted(muted: Boolean) = Unit

        override suspend fun route(): String = "host/host"

        override fun close() {
            closed = true
        }

        private fun connected() {
            if (!closed) events(MediaEvent.Connected)
        }
    }

    private companion object {
        // What `SdpGuard` asks of a description: DTLS-SRTP with a fingerprint, and Opus on an audio m-line.
        val SDP =
            listOf(
                "v=0",
                "m=audio 9 UDP/TLS/RTP/SAVPF 111",
                "a=rtpmap:111 opus/48000/2",
                "a=fingerprint:sha-256 AB:CD",
                "",
            ).joinToString("\r\n")
    }
}
