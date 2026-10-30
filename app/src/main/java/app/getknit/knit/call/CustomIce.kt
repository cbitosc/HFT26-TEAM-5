package app.getknit.knit.call

/**
 * The person's own ICE servers (experimental, docs/VOICE_CALLS.md §3.14, ADR 2026-10.mr88): STUN URLs and one TURN
 * server with a static long-term credential, each of which **replaces** what the spools supply for its kind
 * ([OverridingTurnSource]). [stun] is empty and [turn] null where that kind is the spools'.
 */
class CustomIce(
    val stun: List<String> = emptyList(),
    val turn: TurnServer? = null,
) {
    override fun toString(): String = "CustomIce(stun=$stun, turn=$turn)"

    companion object {
        val NONE = CustomIce()
    }
}

/**
 * What the Calls screen stores, as typed: each kind's switch and its fields, kept when the switch is off so turning it
 * back on loses nothing. [effective] is the only way out to a call — a field that does not pass its rule there reads as
 * that kind switched off, so a stored value libwebrtc would refuse never reaches it. [toString] never names [turnPass].
 */
data class OwnIceServers(
    val stunOn: Boolean = false,
    val stunUrls: List<String> = emptyList(),
    val turnOn: Boolean = false,
    val turnUrl: String = "",
    val turnUser: String = "",
    val turnPass: String = "",
) {
    /** The servers a call uses: each kind only while switched on and valid, else the spools'. */
    fun effective(): CustomIce =
        CustomIce(
            stun = if (stunOn && stunValid) stunUrls else emptyList(),
            turn = if (turnOn && turnValid) turnServer() else null,
        )

    /** At least one URL, at most [StunServers.MAX], each one [StunServers.accepts], none repeated. */
    val stunValid: Boolean
        get() =
            stunUrls.isNotEmpty() && stunUrls.size <= StunServers.MAX && stunUrls.all(StunServers::accepts) &&
                stunUrls.distinct().size == stunUrls.size

    /** A URL [TurnUrls.accepts] and a credential [TurnUrls.credentialOk] takes. */
    val turnValid: Boolean
        get() = TurnUrls.accepts(turnUrl) && TurnUrls.credentialOk(turnUser) && TurnUrls.credentialOk(turnPass)

    /** The TURN server as typed, whether or not it is valid or switched on: what a probe of the fields tries. */
    fun turnServer(): TurnServer = TurnServer(urls = listOf(turnUrl), user = turnUser, pass = turnPass)

    override fun toString(): String =
        "OwnIceServers(stunOn=$stunOn, stunUrls=$stunUrls, turnOn=$turnOn, turnUrl=$turnUrl, " +
            "turnUser=${if (turnUser.isEmpty()) "" else "…"}, turnPass=${if (turnPass.isEmpty()) "" else "…"})"
}

/**
 * The TURN URLs a person may type for their own server. **As narrow as [StunServers]' rule, on purpose:** libwebrtc
 * parses every ICE URL when a call opens its peer connection, and one it refuses fails the whole configuration, which
 * ends the call. So only `turn[s]:host[:port]` passes — a DNS-shaped name, an IPv4 literal or a bracketed IPv6 one —
 * with at most a `?transport=udp|tcp` query; never `user@host` (the credential has its own fields) and never a port
 * outside 1..65535.
 */
object TurnUrls {
    private val URL =
        Regex("""^turns?:(\[[0-9A-Fa-f:.]{2,45}]|[A-Za-z0-9.-]{1,253})(?::(\d{1,5}))?(?:\?transport=(?:udp|tcp))?$""")

    /** Whether libwebrtc will take [url] as a TURN server without failing the call's configuration. */
    fun accepts(url: String): Boolean {
        val match = URL.matchEntire(url) ?: return false
        val port = match.groupValues[2]
        return port.isEmpty() || port.toInt() in 1..MAX_PORT
    }

    /** A username or password: not empty, at most [MAX_CREDENTIAL] characters, and no control character in it. */
    fun credentialOk(value: String): Boolean = value.isNotEmpty() && value.length <= MAX_CREDENTIAL && value.none(Char::isISOControl)

    private const val MAX_PORT = 65_535
    private const val MAX_CREDENTIAL = 512
}
