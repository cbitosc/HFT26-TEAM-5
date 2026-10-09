package app.getknit.knit.debug.call

import kotlin.math.roundToLong

/**
 * One reading of a call's media, as the call screen's diagnostics card draws it (experimental, debug only). Read from
 * libwebrtc's standard stats report ([CallStatsReader]); every field is null until the report carries it. No address
 * is ever kept: the route is candidate types and the network's kind, the same rule the media's log follows.
 */
internal data class CallStats(
    // The selected pair's candidate types, e.g. "relay" ↔ "srflx".
    val localType: String? = null,
    val remoteType: String? = null,
    // The transport between the pair ("udp"/"tcp") and, for a relay candidate, the leg to the TURN server.
    val protocol: String? = null,
    val relayProtocol: String? = null,
    // The local candidate's network kind ("wifi", "cellular", "ethernet", …); the Wi-Fi Direct group reports as
    // ethernet (DirectNetworkDetector), so [wifiDirect] says which it was.
    val networkType: String? = null,
    val wifiDirect: Boolean = false,
    val codec: String? = null,
    val clockRate: Long? = null,
    val channels: Long? = null,
    val fec: Boolean = false,
    val rttMs: Double? = null,
    val jitterMs: Double? = null,
    val lossPercent: Double? = null,
    val sendKbps: Double? = null,
    val receiveKbps: Double? = null,
    val jitterBufferMs: Double? = null,
    val packetsSent: Long? = null,
    val packetsReceived: Long? = null,
    val dtlsVersion: String? = null,
    val dtlsCipher: String? = null,
    val srtpCipher: String? = null,
    val localCandidates: Int = 0,
    val remoteCandidates: Int = 0,
)

/** One entry of a stats report, lifted off libwebrtc's `RTCStats` so [CallStatsReader] runs on the JVM. */
internal class StatEntry(
    val id: String,
    val type: String,
    val timestampUs: Double,
    val members: Map<String, Any?>,
)

/**
 * Every candidate pair that has had an answer, for the log line beside a route change: `*` marks the selected one,
 * then its candidate types, the local transport (and the leg to the TURN server for a relay), and its round trip —
 * the current one, else the average over every answer so far, else `?`. Fastest first, with the pairs still unanswered
 * counted. Candidate types and transports only: never an address, the rule the media's log follows.
 */
internal fun pairSummary(report: List<StatEntry>): String {
    val byId = report.associateBy { it.id }
    val selectedId = report.firstOrNull { it.type == "transport" }?.members?.get("selectedCandidatePairId") as? String
    val pairs = report.filter { it.type == "candidate-pair" }
    val answered = pairs.filter { (it.members["responsesReceived"] as? Number)?.toLong()?.let { n -> n > 0 } == true }
    val lines =
        answered
            .map { pair ->
                val local = (pair.members["localCandidateId"] as? String)?.let(byId::get)
                val remote = (pair.members["remoteCandidateId"] as? String)?.let(byId::get)
                val rtt = pairRttMs(pair)
                val relay = (local?.members?.get("relayProtocol") as? String)?.let { "/turn-$it" }.orEmpty()
                val line =
                    (if (pair.id == selectedId) "*" else "") +
                        "${local?.members?.get("candidateType") ?: "?"}/${remote?.members?.get("candidateType") ?: "?"} " +
                        "${local?.members?.get("protocol") ?: "?"}$relay " +
                        (rtt?.let { "${it.roundToLong()}ms" } ?: "?")
                (rtt ?: Double.MAX_VALUE) to line
            }.sortedBy { it.first }
            .map { it.second }
    val pending = pairs.size - answered.size
    return if (lines.isEmpty()) "none answered yet ($pending pending)" else lines.joinToString() + " ($pending pending)"
}

private fun pairRttMs(pair: StatEntry): Double? {
    (pair.members["currentRoundTripTime"] as? Number)?.toDouble()?.let { return it * 1_000.0 }
    val total = (pair.members["totalRoundTripTime"] as? Number)?.toDouble() ?: return null
    val count = (pair.members["responsesReceived"] as? Number)?.toDouble()?.takeIf { it > 0 } ?: return null
    return total / count * 1_000.0
}

/**
 * Turns successive stats reports into [CallStats]. Bitrates are the byte counters' change since the previous report,
 * so one reader serves one session, read in order.
 */
internal class CallStatsReader {
    private var previous: Counters? = null

    private class Counters(
        val atUs: Double,
        val sent: Double?,
        val received: Double?,
    )

    @Synchronized
    fun read(
        report: List<StatEntry>,
        wifiDirect: Boolean,
    ): CallStats {
        val byId = report.associateBy { it.id }
        val outbound = report.firstOrNull { it.type == "outbound-rtp" && it.kind == "audio" }
        val inbound = report.firstOrNull { it.type == "inbound-rtp" && it.kind == "audio" }
        val transport = report.firstOrNull { it.type == "transport" }
        val pair =
            transport?.string("selectedCandidatePairId")?.let(byId::get)
                ?: report.firstOrNull {
                    it.type == "candidate-pair" && it.members["nominated"] == true && it.string(
                        "state",
                    ) == "succeeded"
                }
        val local = pair?.string("localCandidateId")?.let(byId::get)
        val remote = pair?.string("remoteCandidateId")?.let(byId::get)
        val codec = (inbound ?: outbound)?.string("codecId")?.let(byId::get)
        val remoteInbound = report.firstOrNull { it.type == "remote-inbound-rtp" && it.kind == "audio" }

        val sent = outbound?.number("bytesSent")
        val received = inbound?.number("bytesReceived")
        val at = (outbound ?: inbound)?.timestampUs
        val before = previous
        if (at != null) previous = Counters(at, sent, received)
        val elapsedUs = if (before != null && at != null) at - before.atUs else null

        val lost = inbound?.number("packetsLost")
        val got = inbound?.number("packetsReceived")
        val emitted = inbound?.number("jitterBufferEmittedCount")
        return CallStats(
            localType = local?.string("candidateType"),
            remoteType = remote?.string("candidateType"),
            protocol = local?.string("protocol"),
            relayProtocol = local?.string("relayProtocol"),
            networkType = local?.string("networkType"),
            wifiDirect = wifiDirect,
            codec = codec?.string("mimeType")?.substringAfter('/'),
            clockRate = codec?.number("clockRate")?.toLong(),
            channels = codec?.number("channels")?.toLong(),
            fec = codec?.string("sdpFmtpLine")?.contains("useinbandfec=1") == true,
            rttMs = (pair?.number("currentRoundTripTime") ?: remoteInbound?.number("roundTripTime"))?.times(MS_PER_S),
            jitterMs = inbound?.number("jitter")?.times(MS_PER_S),
            lossPercent = if (lost != null && got != null && lost + got > 0) PERCENT * lost.coerceAtLeast(0.0) / (lost + got) else null,
            sendKbps = rate(sent, before?.sent, elapsedUs),
            receiveKbps = rate(received, before?.received, elapsedUs),
            jitterBufferMs =
                if (emitted != null && emitted > 0) inbound.number("jitterBufferDelay")?.let { MS_PER_S * it / emitted } else null,
            packetsSent = outbound?.number("packetsSent")?.toLong(),
            packetsReceived = got?.toLong(),
            dtlsVersion = transport?.string("tlsVersion")?.let(::dtlsVersionOf),
            dtlsCipher = transport?.string("dtlsCipher"),
            srtpCipher = transport?.string("srtpCipher"),
            localCandidates = report.count { it.type == "local-candidate" },
            remoteCandidates = report.count { it.type == "remote-candidate" },
        )
    }

    private companion object {
        const val MS_PER_S = 1_000.0
        const val PERCENT = 100.0
        const val BITS_PER_BYTE = 8.0

        // kbit/s from bytes over microseconds: bytes × 8 / 1 000 bits per kbit, over µs / 1 000 000.
        const val KBPS_PER_BYTE_PER_US = BITS_PER_BYTE * 1_000.0

        val StatEntry.kind: String? get() = string("kind")

        fun StatEntry.string(name: String): String? = members[name] as? String

        // libwebrtc hands uint64 counters over as BigInteger, uint32 as Long, doubles as Double: all Numbers.
        fun StatEntry.number(name: String): Double? = (members[name] as? Number)?.toDouble()

        fun rate(
            now: Double?,
            then: Double?,
            elapsedUs: Double?,
        ): Double? {
            if (now == null || then == null || elapsedUs == null || elapsedUs <= 0 || now < then) return null
            return (now - then) * KBPS_PER_BYTE_PER_US / elapsedUs
        }

        /** The record-layer version the report gives in hex ("FEFD"), as people say it ("1.2"). */
        fun dtlsVersionOf(hex: String): String =
            when (hex.uppercase()) {
                "FEFF" -> "1.0"
                "FEFD" -> "1.2"
                "FEFC" -> "1.3"
                else -> hex
            }
    }
}
