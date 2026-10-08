package app.getknit.knit.call

/**
 * What a call's SDP must say before it is applied or sent (docs/VOICE_CALLS.md §3.7): DTLS-SRTP keyed by a
 * fingerprint, never SDES (C-3.7-1), and Opus on an audio m-line (C-3.7-4). libwebrtc then checks the remote DTLS
 * certificate against that fingerprint itself (C-3.7-2), and an SDP only ever arrives inside a sealed, signed
 * `CallPayload` — so the fingerprint is the peer's own. Pure: JVM-tested in `SdpGuardTest`.
 */
object SdpGuard {
    private val newline = Regex("\r?\n")
    private val opus = Regex("""^a=rtpmap:\d+ opus/48000(/\d+)?$""", RegexOption.IGNORE_CASE)

    /** Whether [sdp] may be applied or sent. */
    fun accepts(sdp: String): Boolean {
        val lines = sdp.split(newline).map { it.trim() }
        return lines.any { it.startsWith("m=audio ") } &&
            lines.any { it.startsWith("a=fingerprint:") } &&
            lines.none { it.startsWith("a=crypto:") } &&
            lines.any { opus.matches(it) }
    }

    /**
     * [sdp] with every candidate but the relayed ones removed — what a relay-only call sends (C-3.7-3): gathering
     * under a relay policy should already produce no others, and this makes sure our own address never leaves.
     */
    fun stripToRelay(sdp: String): String =
        sdp
            .split(newline)
            .filterNot { it.startsWith("a=candidate:") && !isRelay(it) }
            .joinToString("\r\n")

    /** [lines] (`candidate:` lines, as a CANDS carries them) without the non-relayed ones. */
    fun relayOnly(lines: List<String>): List<String> = lines.filter(::isRelay)

    private fun isRelay(candidate: String): Boolean = " typ relay" in candidate
}
