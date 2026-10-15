package app.getknit.knit.call

/**
 * The Calls screen's Test (docs/VOICE_CALLS.md C-3.14-5, ADR 2026-10.mr88): gathers against the servers as typed, before
 * they are saved, with no peer and no microphone, and says whether each kind answered. WebRTC where calls are wired
 * (`ui/call/WebRtcCallMedia`), a fake under test.
 */
fun interface IceProbe {
    /**
     * Tries [stun] (credential-free URLs) and [turn] side by side, each kind on its own connection so an answer is never
     * credited to the wrong one. A kind not given is [ProbeVerdict.NotTried]. Null while a call holds the media: a probe
     * never builds or touches the audio under one.
     */
    suspend fun probe(
        stun: List<String>,
        turn: TurnServer?,
    ): ProbeResult?
}

/** One Test's answer, a verdict per kind. Never carries an address: the screen says only that one was found. */
data class ProbeResult(
    val stun: ProbeVerdict,
    val turn: ProbeVerdict,
)

/** What one kind of server did during a probe. */
sealed interface ProbeVerdict {
    /** Not asked: the kind had nothing to try. */
    data object NotTried : ProbeVerdict

    /** STUN reported this phone's public address, or TURN allocated a relay. */
    data object Reached : ProbeVerdict

    /** The TURN server refused the username or password (STUN/TURN error 401). */
    data object AuthFailed : ProbeVerdict

    /** The server answered with an error other than 401: [code] is the STUN/TURN error code (403, 437, 486 …). */
    data class Refused(
        val code: Int,
    ) : ProbeVerdict

    /** Nothing came back in the probe's window: a wrong host or port, a firewall, or no route (error 701 or silence). */
    data object NoAnswer : ProbeVerdict

    companion object {
        /**
         * The verdict for one kind from what its connection gathered: [reached] (a server-reflexive candidate for STUN,
         * a relay one for TURN) wins over any error, since an error on one network or address family is ordinary while
         * another succeeds; else 401 says the credential, any other server error says the server refused, and 701 or
         * nothing at all says it never answered.
         */
        fun of(
            reached: Boolean,
            errorCodes: Collection<Int>,
        ): ProbeVerdict =
            when {
                reached -> Reached
                UNAUTHORIZED in errorCodes -> AuthFailed
                else -> errorCodes.firstOrNull { it in SERVER_ERRORS }?.let(::Refused) ?: NoAnswer
            }

        private const val UNAUTHORIZED = 401

        // STUN/TURN error codes a server sends (RFC 8489 §14.8): 3xx-6xx. libwebrtc's own 7xx are local, never a server's.
        private val SERVER_ERRORS = 300..699
    }
}
