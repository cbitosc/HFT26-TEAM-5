package app.getknit.knit.debug.call

/**
 * A call's TURN allocations as its log counts them (C-7.6-26). Each relay candidate libwebrtc surfaces is one
 * allocation, labelled `udp/wifi#2/ipv4`: how this phone reaches the server (`udp`, `tcp` or `tls`, from the URL it
 * came from), then the network it leaves on, by the line's `network-cost` (`wifi`, `cellular`, `vpn`) and libwebrtc's
 * `network-id` for it (an IPv4 and an IPv6 network of one interface are two ids), then that network's address family,
 * read from the related address (the mapped address the server saw). Relay-only (C-3.7-3) blanks that address to an
 * empty one of the *relay's* family, which says nothing of the network, so the family is then `?`. The JNI hands a
 * gathered candidate over with no adapter type, which is why the cost names the network.
 * A removed one was pruned, or its network went away, and its allocation is being released.
 * Types and counts only: the relay address that matches a removal to its gathering stays in here, never in a log.
 *
 * What it cannot see: a relay pruned the moment it was ready, because a better one on its network was already up.
 * libwebrtc releases that allocation without ever surfacing its candidate, so with pruning on, `gathered` is a floor
 * and `held` is exact. With pruning off ([WebRtcCallMedia.turnPruneOff]) every allocation surfaces.
 */
internal class RelayTally {
    // Relay address and port -> label, for the relays surfaced and not removed since.
    private val live = mutableMapOf<String, String>()
    private val seen = mutableSetOf<String>()
    private var gathered = 0

    /** [sdp]'s label when it is a relay candidate not counted before, else null. */
    @Synchronized
    fun gathered(
        sdp: String,
        serverUrl: String?,
    ): String? {
        val key = relayKey(sdp) ?: return null
        if (!seen.add(key)) return null
        gathered++
        return label(sdp, serverUrl).also { live[key] = it }
    }

    /** The labels of the counted relays among [candidates] (SDP lines), now released. */
    @Synchronized
    fun removed(candidates: List<String>): List<String> = candidates.mapNotNull { relayKey(it)?.let(live::remove) }

    /**
     * An ICE restart (C-3.8): libwebrtc prunes every port of the old session once the new one starts, without a
     * removal for any of them, so the relays held now are counted as pruned from here.
     */
    @Synchronized
    fun restarted() = live.clear()

    /** "allocations gathered N, pruned M, held K": the line the measured call reads. */
    @Synchronized
    fun summary(): String = "allocations gathered $gathered, pruned ${gathered - live.size}, held ${live.size}"

    companion object {
        /** "udp/wifi#2/ipv4": how this phone reaches the TURN server, and the network it leaves on. */
        fun label(
            sdp: String,
            serverUrl: String?,
        ): String {
            val fields = sdp.split(' ')
            return "${transportOf(serverUrl)}/${networkOf(fields)}/${familyOf(fields)}"
        }

        /** "2 (tls/ipv4 1, udp/ipv6 1)". */
        fun counts(labels: List<String>): String {
            val each =
                labels
                    .groupingBy { it }
                    .eachCount()
                    .entries
                    .sortedBy { it.key }
                    .joinToString { "${it.key} ${it.value}" }
            return "${labels.size}${if (each.isEmpty()) "" else " ($each)"}"
        }

        // RFC 7065: turns: is TLS over TCP; turn: is UDP unless its transport says tcp.
        private fun transportOf(url: String?): String =
            when {
                url == null -> "?"
                url.startsWith("turns:") -> "tls"
                url.startsWith("turn:") && url.contains("transport=tcp") -> "tcp"
                url.startsWith("turn:") -> "udp"
                else -> "?"
            }

        // rtc_base/network_constants.h: 10 Wi-Fi or Ethernet, 1 VPN, 250 to 980 cellular by generation, 50 unknown.
        private fun networkOf(fields: List<String>): String {
            val cost = attribute(fields, "network-cost")?.toIntOrNull()
            val kind =
                when {
                    cost == null -> "?"
                    cost == VPN_COST -> "vpn"
                    cost <= LOW_COST -> "wifi"
                    cost >= CELLULAR_COST -> "cellular"
                    else -> "unknown"
                }
            return "$kind#${attribute(fields, "network-id") ?: "?"}"
        }

        private fun familyOf(fields: List<String>): String {
            val related = attribute(fields, "raddr") ?: return "?"
            return when {
                related == "0.0.0.0" || related == "::" -> "?"
                related.contains(':') -> "ipv6"
                else -> "ipv4"
            }
        }

        private fun attribute(
            fields: List<String>,
            name: String,
        ): String? = fields.indexOf(name).takeIf { it >= 0 }?.let { fields.getOrNull(it + 1) }

        // "candidate:<foundation> <component> <protocol> <priority> <address> <port> typ relay …"
        private fun relayKey(sdp: String): String? {
            val fields = sdp.removePrefix("a=").split(' ')
            if (fields.getOrNull(fields.indexOf("typ") + 1) != "relay" || fields.size <= PORT) return null
            return "${fields[ADDRESS]} ${fields[PORT]}"
        }

        private const val VPN_COST = 1
        private const val LOW_COST = 10
        private const val CELLULAR_COST = 248
        private const val ADDRESS = 4
        private const val PORT = 5
    }
}
