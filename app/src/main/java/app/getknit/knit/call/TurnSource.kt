package app.getknit.knit.call

/**
 * The spool seam of a voice call: what the connected spools offer a call's ICE gathering (docs/VOICE_CALLS.md §2).
 *
 * Two questions, both abstract on purpose. This was a `fun interface` with [turnServers] alone; STUN is a second
 * question with a different shape — no peer, no wait, nothing issued, just what the hellos already said — and a
 * default for it would let an implementation that forgot it hand every call no STUN, silently, which is the very
 * failure C-7.6-20 exists to close (ADR 2026-10.xkp4). As a plain interface, every implementation answers both.
 */
interface TurnSource {
    /**
     * TURN credentials from the spools that would issue one for a call with [peerId]: those carrying its DM scope that
     * advertise `turn` (C-7.6-12), and any where this phone holds a ticket (C-7.6-21). Empty when none can — the call
     * then dials on host and server-reflexive candidates alone. Never asks a spool that did not advertise it, and
     * never waits long: a call is waiting on the answer.
     */
    suspend fun turnServers(peerId: String): List<TurnServer>

    /**
     * The credential-free STUN URIs every connected spool advertised in its HELLO (C-7.6-20), unfiltered: the call
     * holds them to `StunServers`' rule. Read on every call, with or without TURN — a phone with no STUN offers only
     * private addresses, which a paying peer's relay cannot permit.
     */
    fun stunUrls(): List<String>

    /**
     * Whether some source would issue this phone TURN now: what "Relay all calls" needs (C-3.7-3), since a relay-only
     * call with no TURN server gathers nothing. Read as a call is placed and as an offer lands, so it asks nothing and
     * never waits. A source that cannot tell says yes, and the call learns otherwise from [turnServers].
     */
    fun turnAvailable(): Boolean = true

    /** A build that takes no live calls (`LIVE_CALLS_WIRED` off): nothing ever asks it, and it has nothing to give. */
    object None : TurnSource {
        override suspend fun turnServers(peerId: String): List<TurnServer> = emptyList()

        override fun stunUrls(): List<String> = emptyList()
    }
}

/**
 * One ICE server for a call: a short-lived TURN credential (coturn's REST scheme), whose [urls] are `turn:`/`turns:`
 * URIs, or — with an empty [user] and [pass] — credential-free `stun:` URIs. A plain class, and its [toString] names
 * the URLs only, so [pass] can never reach a log line. Never sent to the peer or into any frame (C-7.6-11).
 */
class TurnServer(
    val urls: List<String>,
    val user: String,
    val pass: String,
) {
    /** A relay a call can allocate on, not the credential-free STUN entry. */
    val isTurn: Boolean get() = user.isNotEmpty()

    override fun toString(): String = "TurnServer(urls=$urls)"
}

/** "1 TURN, 1 STUN" for a log line: the credential-free STUN entry is not a relay, so it is counted apart. */
fun List<TurnServer>.iceSummary(): String {
    val stun = count { it.user.isEmpty() }
    return "${size - stun} TURN, $stun STUN"
}
