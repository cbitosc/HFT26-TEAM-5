package app.getknit.knit.call

/**
 * The spool seam of a voice call: TURN credentials from the spools that carry [peerId]'s DM scope and advertise
 * `turn` (docs/VOICE_CALLS.md §2, C-7.6-12). Empty when no spool can issue one — the call then dials on host and
 * server-reflexive candidates alone. Never asks a spool that did not advertise it, and never waits long: a call is
 * waiting on the answer.
 */
fun interface TurnSource {
    suspend fun turnServers(peerId: String): List<TurnServer>
}

/**
 * One short-lived TURN credential (coturn's REST scheme): [urls] are `turn:`/`turns:` URIs. A plain class, and its
 * [toString] names the URLs only, so [pass] can never reach a log line. Never sent to the peer or into any frame
 * (C-7.6-11).
 */
class TurnServer(
    val urls: List<String>,
    val user: String,
    val pass: String,
) {
    override fun toString(): String = "TurnServer(urls=$urls)"
}
