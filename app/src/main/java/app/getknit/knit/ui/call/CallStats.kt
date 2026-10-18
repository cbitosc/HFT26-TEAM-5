package app.getknit.knit.ui.call

import app.getknit.knit.call.CaptureProfile
import app.getknit.knit.call.IceNetwork
import app.getknit.knit.call.IceProtocol
import app.getknit.knit.call.IceType
import app.getknit.knit.fieldlog.FieldEvent
import kotlin.math.roundToLong

/**
 * One reading of a call's media, as the call screen's diagnostics card draws it (experimental, `LIVE_CALLS_WIRED`). Read from
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
    // Loss over the last reading only, each way: what this phone failed to hear since the previous report, and what
    // the peer last reported failing to hear from it (its receiver report's fraction). [lossPercent] is the whole call.
    val recentLossPercent: Double? = null,
    val sentLossPercent: Double? = null,
    // Packets heard since the previous report: zero while the media is up is a peer this phone no longer hears.
    val recentPacketsReceived: Long? = null,
    // The TURN server the selected relay candidate came from, by host name ("stun.lab.lax.spool.getknit.app").
    val relayHost: String? = null,
    // This phone's relay candidates by their leg to the server ([relaySummary]), and the network the call prefers.
    val relays: String? = null,
    val prefer: String? = null,
    // Every answered candidate pair, fastest first ([pairsOf]), and how many are still unanswered.
    val pairs: List<PairInfo> = emptyList(),
    val pendingPairs: Int = 0,
    // The level (0..1) of what this phone's microphone hands WebRTC, and of what it hears from the peer: zero on one
    // side with packets flowing is a microphone sending silence (C-3.13-13).
    val micLevel: Double? = null,
    val heardLevel: Double? = null,
)

/**
 * This reading as the field log keeps it (ADR 2026-10.a239's amendment): whole milliseconds and kbit/s, loss in tenths
 * of a percent, the two audio levels in ‰, and every word of the report turned into a constant. [micPath] and
 * [capture] are the call's microphone (C-3.13-13), null where nothing reports it.
 */
internal fun CallStats.fieldSample(
    reconnecting: Boolean,
    micPath: CaptureProfile?,
    capture: CaptureStatus?,
): FieldEvent.CallSample =
    FieldEvent.CallSample(
        reconnecting = reconnecting,
        rttMs = rttMs?.roundToLong(),
        jitterMs = jitterMs?.roundToLong(),
        bufferMs = jitterBufferMs?.roundToLong(),
        lossIn10 = recentLossPercent?.tenths(),
        lossOut10 = sentLossPercent?.tenths(),
        lossCall10 = lossPercent?.tenths(),
        sendKbps = sendKbps?.roundToLong(),
        receiveKbps = receiveKbps?.roundToLong(),
        packets = recentPacketsReceived,
        micPermille = micLevel?.permille(),
        heardPermille = heardLevel?.permille(),
        local = IceType.of(localType),
        remote = IceType.of(remoteType),
        protocol = IceProtocol.of(protocol),
        relayProtocol = IceProtocol.of(relayProtocol),
        network = IceNetwork.of(networkType),
        direct = wifiDirect,
        pairs = pairs.size,
        pending = pendingPairs,
        micPath = micPath,
        micHealth = capture?.health,
    )

private fun Double.tenths(): Long = (this * TENTHS).roundToLong()

private fun Double.permille(): Long = (this * PER_MILLE).roundToLong()

private const val TENTHS = 10.0
private const val PER_MILLE = 1_000.0

/** One answered candidate pair: its types, transport, relay leg, this phone's network for it and its round trip. */
internal data class PairInfo(
    val selected: Boolean,
    val localType: String?,
    val remoteType: String?,
    val protocol: String?,
    val relayProtocol: String?,
    val networkType: String?,
    val rttMs: Double?,
) {
    /** "*relay/prflx udp/turn-tcp wifi 98ms": the log's spelling, `*` on the selected pair. */
    override fun toString(): String =
        (if (selected) "*" else "") +
            "${localType ?: "?"}/${remoteType ?: "?"} ${protocol ?: "?"}" +
            relayProtocol?.let { "/turn-$it" }.orEmpty() + " " +
            networkType?.let { "$it " }.orEmpty() +
            (rttMs?.let { "${it.roundToLong()}ms" } ?: "?")
}

/** One entry of a stats report, lifted off libwebrtc's `RTCStats` so [CallStatsReader] runs on the JVM. */
internal class StatEntry(
    val id: String,
    val type: String,
    val timestampUs: Double,
    val members: Map<String, Any?>,
)

/**
 * Every candidate pair that has had an answer, for the log line beside a route change: `*` marks the selected one,
 * then its candidate types, the local transport (and the leg to the TURN server for a relay), this phone's network
 * for it (`wifi`, `cellular`, …, as the stats name it), and its round trip —
 * the current one, else the average over every answer so far, else `?`. Fastest first, with the pairs still unanswered
 * counted. Candidate types and transports only: never an address, the rule the media's log follows.
 */
internal fun pairSummary(report: List<StatEntry>): String {
    val pairs = pairsOf(report)
    val pending = report.count { it.type == "candidate-pair" } - pairs.size
    return if (pairs.isEmpty()) "none answered yet ($pending pending)" else pairs.joinToString() + " ($pending pending)"
}

/** Every candidate pair that has had an answer, fastest first ([pairSummary]'s list, for the diagnostics card too). */
internal fun pairsOf(report: List<StatEntry>): List<PairInfo> {
    val byId = report.associateBy { it.id }
    val selectedId = report.firstOrNull { it.type == "transport" }?.members?.get("selectedCandidatePairId") as? String
    return report
        .filter { it.type == "candidate-pair" && (it.members["responsesReceived"] as? Number)?.toLong()?.let { n -> n > 0 } == true }
        .map { pair ->
            val local = (pair.members["localCandidateId"] as? String)?.let(byId::get)
            val remote = (pair.members["remoteCandidateId"] as? String)?.let(byId::get)
            PairInfo(
                selected = pair.id == selectedId,
                localType = local?.members?.get("candidateType") as? String,
                remoteType = remote?.members?.get("candidateType") as? String,
                protocol = local?.members?.get("protocol") as? String,
                relayProtocol = local?.members?.get("relayProtocol") as? String,
                networkType = local?.members?.get("networkType") as? String,
                rttMs = pairRttMs(pair),
            )
        }.sortedBy { it.rttMs ?: Double.MAX_VALUE }
}

/**
 * The host name in a candidate's server URL — "turn:stun.lab.lax.spool.getknit.app:3478?transport=udp" gives
 * "stun.lab.lax.spool.getknit.app" — or null. A server's name, never this phone's or the peer's address.
 */
internal fun hostOf(url: String?): String? {
    val rest = url?.substringAfter(':', "")?.substringBefore('?')?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        rest.startsWith("[") -> rest.substringBefore(']') + "]"
        rest.count { it == ':' } == 1 -> rest.substringBefore(':')
        else -> rest
    }
}

/**
 * This phone's relay candidates by the leg to their TURN server — "udp 4, tcp 4, tls 2" — or "none". What tells a
 * `turns:` server that allocated from one that did not, since an SDP relay line carries no such field.
 */
internal fun relaySummary(report: List<StatEntry>): String =
    report
        .filter { it.type == "local-candidate" && it.members["candidateType"] == "relay" }
        .groupingBy { it.members["relayProtocol"] as? String ?: "?" }
        .eachCount()
        .entries
        .sortedBy { it.key }
        .joinToString { "${it.key} ${it.value}" }
        .ifEmpty { "none" }

private fun pairRttMs(pair: StatEntry): Double? {
    (pair.members["currentRoundTripTime"] as? Number)?.toDouble()?.let { return it * MS_PER_S }
    val total = (pair.members["totalRoundTripTime"] as? Number)?.toDouble() ?: return null
    val count = (pair.members["responsesReceived"] as? Number)?.toDouble()?.takeIf { it > 0 } ?: return null
    return total / count * MS_PER_S
}

private const val MS_PER_S = 1_000.0

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
        val packetsLost: Double?,
        val packetsReceived: Double?,
    )

    @Suppress("CyclomaticComplexMethod", "LongMethod") // one pass over a report, a field of the result per line
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
        val mediaSource = report.firstOrNull { it.type == "media-source" && it.kind == "audio" }

        val sent = outbound?.number("bytesSent")
        val received = inbound?.number("bytesReceived")
        val lost = inbound?.number("packetsLost")
        val got = inbound?.number("packetsReceived")
        val at = (outbound ?: inbound)?.timestampUs
        val before = previous
        if (at != null) previous = Counters(at, sent, received, lost, got)
        val elapsedUs = if (before != null && at != null) at - before.atUs else null
        val gotSince = if (got != null && before?.packetsReceived != null) (got - before.packetsReceived).coerceAtLeast(0.0) else null
        // packetsLost can fall (a late packet counted lost is taken back), so a negative step counts as none lost.
        val lostSince = if (lost != null && before?.packetsLost != null) (lost - before.packetsLost).coerceAtLeast(0.0) else null
        val sinceTotal = if (gotSince != null && lostSince != null) gotSince + lostSince else null
        val recentLoss = sinceTotal?.takeIf { it > 0 }?.let { PERCENT * (lostSince ?: 0.0) / it }
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
            recentLossPercent = recentLoss,
            sentLossPercent = remoteInbound?.number("fractionLost")?.times(PERCENT),
            recentPacketsReceived = gotSince?.toLong(),
            relayHost = local?.takeIf { it.string("candidateType") == "relay" }?.string("url")?.let(::hostOf),
            relays = relaySummary(report),
            pairs = pairsOf(report),
            pendingPairs = report.count { it.type == "candidate-pair" && (it.number("responsesReceived") ?: 0.0) <= 0.0 },
            micLevel = mediaSource?.number("audioLevel"),
            heardLevel = inbound?.number("audioLevel"),
        )
    }

    private companion object {
        const val PERCENT = 100.0
        const val BITS_PER_BYTE = 8.0

        // kbit/s from bytes over microseconds: bytes × 8 / 1 000 bits per kbit, over µs / 1 000 000.
        const val KBPS_PER_BYTE_PER_US = BITS_PER_BYTE * 1_000.0

        val StatEntry.kind: String? get() = string("kind")

        fun StatEntry.string(name: String): String? = members[name] as? String

        // libwebrtc hands uint64 counters over as BigInteger, uint32 as Long, doubles as Double: all Numbers.
        fun StatEntry.number(name: String): Double? = (members[name] as? Number)?.toDouble()

        @Suppress("ComplexCondition") // a missing counter, no time between reports or a reset counter: no rate
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
