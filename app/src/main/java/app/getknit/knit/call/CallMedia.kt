package app.getknit.knit.call

/**
 * The media seam of a voice call: WebRTC in the debug build (`debug/call/WebRtcCallMedia`), a fake under test.
 * Opus over DTLS-SRTP, audio only (docs/VOICE_CALLS.md §3.7). [CallManager] owns every decision; this only moves
 * audio and reports what ICE did.
 */
fun interface CallMedia {
    /**
     * One call's media session. [servers] are the TURN credentials to gather relay candidates from (possibly none);
     * [relayOnly] gathers relay candidates alone (C-3.7-3). [direct] says a Wi-Fi Direct leg is planned (§3.10):
     * candidates on its group come as [MediaEvent.DirectCandidates] and its pair is preferred. [events] may arrive on
     * any thread.
     */
    fun open(
        servers: List<TurnServer>,
        relayOnly: Boolean,
        direct: Boolean,
        events: (MediaEvent) -> Unit,
    ): CallSession
}

/** One call's media: the SDP exchange, then audio. Every call is safe after [close] (a no-op or a null). */
interface CallSession {
    /** The caller's offer, with the candidates gathered so far folded in (C-3.6-1), or null when it failed. */
    suspend fun offer(): String?

    /** The callee's answer to [remoteOffer], gathered like [offer], or null when it cannot be answered. */
    suspend fun answer(remoteOffer: String): String?

    /** Applies the callee's answer; false when it was refused. */
    suspend fun accept(remoteAnswer: String): Boolean

    /**
     * The caller's ICE restart (§3.8): gathers from [servers] from now on, with fresh ICE credentials, and returns the
     * new offer with what gathering found in C-3.6-1's window (no CANDS follows it), or null when it failed. The
     * peer's answer goes through [accept].
     */
    suspend fun restart(servers: List<TurnServer>): String?

    /** The callee's answer to the caller's restart offer [remoteOffer] (§3.8), gathered from [servers] like [restart]. */
    suspend fun reanswer(
        remoteOffer: String,
        servers: List<TurnServer>,
    ): String?

    /** The peer's late `candidate:` lines (one CANDS, C-3.6-2). Only after [answer] or [accept] has applied its SDP. */
    fun addCandidates(lines: List<String>)

    fun setMuted(muted: Boolean)

    fun setSpeaker(on: Boolean)

    /**
     * The selected candidate pair's types, `local/remote` (e.g. `relay/srflx`), with `/wifi-direct` after them when the
     * pair rides the call's Wi-Fi Direct group; null before ICE has chosen.
     */
    suspend fun route(): String?

    fun close()
}

/**
 * What the media layer reports. [LateCandidates] comes at most once per session, when gathering completes late;
 * [DirectCandidates] whenever the call's Wi-Fi Direct group yields some.
 */
sealed interface MediaEvent {
    /** ICE (and DTLS) reached connected — again, after a [Disconnected]. */
    data object Connected : MediaEvent

    /** The selected path stopped answering; it may still come back. */
    data object Disconnected : MediaEvent

    /** ICE gave up. */
    data object Failed : MediaEvent

    /** Candidates gathered after [CallSession.offer]/[CallSession.answer] returned, as `candidate:` lines. */
    class LateCandidates(
        val lines: List<String>,
    ) : MediaEvent

    /**
     * Candidates on the call's Wi-Fi Direct group gathered after our description went out, as `candidate:` lines.
     * They ride the call's lane alone, never a CANDS: only a peer inside the group can reach them (§3.10).
     */
    class DirectCandidates(
        val lines: List<String>,
    ) : MediaEvent
}
