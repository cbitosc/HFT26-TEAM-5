package app.getknit.knit.call

/**
 * The credential-free STUN servers a call gathers from (experimental, docs/VOICE_CALLS.md §2.6, C-7.6-20, ADR
 * 2026-10.xkp4): what connected spools advertise in HELLO and what a held TURN credential's URLs reduce to. One rule
 * for both the spool connection that reads a HELLO and the call that gathers.
 *
 * **Narrower than the spec's `stun:`/`stuns:`, on purpose.** libwebrtc parses every ICE URL when a call opens its
 * peer connection and one it refuses fails the whole configuration — a query (`?transport=`), `user@host`, a port
 * outside 1..65535 — which ends the call. So only `stun[s]:host[:port]` passes, with a DNS-shaped name, an IPv4
 * literal or a bracketed IPv6 one. `stuns:` passes because the spec allows it, but libwebrtc has no STUN over TLS: it
 * sends a plain UDP binding to that address, to port 3478 when none is named (its `pc/ice_server_parsing.cc`).
 */
object StunServers {
    /** At most this many per call, all sources together: each is a binding request on every network the phone has. */
    const val MAX = 4

    private val URL = Regex("""^stuns?:(\[[0-9A-Fa-f:.]{2,45}]|[A-Za-z0-9.-]{1,253})(?::(\d{1,5}))?$""")

    /** Whether libwebrtc will take [url] as a STUN server without failing the call's configuration. */
    fun accepts(url: String): Boolean {
        val match = URL.matchEntire(url) ?: return false
        val port = match.groupValues[2]
        return port.isEmpty() || port.toInt() in 1..MAX_PORT
    }

    /** [urls] in order, without the ones [accepts] refuses or repeats, capped at [MAX]. */
    fun pick(urls: Iterable<String>): List<String> = urls.filter(::accepts).distinct().take(MAX)

    private const val MAX_PORT = 65_535
}
