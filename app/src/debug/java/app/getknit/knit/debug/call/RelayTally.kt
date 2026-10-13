package app.getknit.knit.debug.call

/**
 * A call's TURN allocations as its log counts them (C-7.6-26). Each relay candidate libwebrtc surfaces is one
 * allocation, labelled by how this phone reaches the server (`udp`, `tcp` or `tls`, from the URL it came from) and
 * the address family of the network it leaves on (the candidate's related address, which keeps its family even where
 * libwebrtc blanks it). A removed one was pruned, or its network went away, and its allocation is being released.
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
        /** "udp/ipv4": how this phone reaches the TURN server, and the family of the network it leaves on. */
        fun label(
            sdp: String,
            serverUrl: String?,
        ): String = "${transportOf(serverUrl)}/${familyOf(sdp)}"

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

        private fun familyOf(sdp: String): String {
            val fields = sdp.split(' ')
            val related = fields.indexOf("raddr").takeIf { it >= 0 }?.let { fields.getOrNull(it + 1) }
            val address = related ?: fields.getOrNull(ADDRESS) ?: return "?"
            return if (address.contains(':')) "ipv6" else "ipv4"
        }

        // "candidate:<foundation> <component> <protocol> <priority> <address> <port> typ relay …"
        private fun relayKey(sdp: String): String? {
            val fields = sdp.removePrefix("a=").split(' ')
            if (fields.getOrNull(fields.indexOf("typ") + 1) != "relay" || fields.size <= PORT) return null
            return "${fields[ADDRESS]} ${fields[PORT]}"
        }

        private const val ADDRESS = 4
        private const val PORT = 5
    }
}
