package app.getknit.knit.debug.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger

class CallStatsReaderTest {
    private fun report(
        atUs: Double,
        sent: Long,
        received: Long,
    ): List<StatEntry> =
        listOf(
            StatEntry(
                "T01",
                "transport",
                atUs,
                mapOf(
                    "selectedCandidatePairId" to "CP1",
                    "tlsVersion" to "FEFD",
                    "dtlsCipher" to "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                    "srtpCipher" to "AEAD_AES_256_GCM",
                ),
            ),
            // A pair that is not the selected one must not be read.
            StatEntry("CP0", "candidate-pair", atUs, mapOf("localCandidateId" to "L0", "currentRoundTripTime" to 0.9)),
            StatEntry(
                "CP1",
                "candidate-pair",
                atUs,
                mapOf("localCandidateId" to "L1", "remoteCandidateId" to "R1", "currentRoundTripTime" to 0.042),
            ),
            StatEntry("L0", "local-candidate", atUs, mapOf("candidateType" to "host")),
            StatEntry(
                "L1",
                "local-candidate",
                atUs,
                mapOf("candidateType" to "relay", "protocol" to "udp", "relayProtocol" to "udp", "networkType" to "wifi"),
            ),
            StatEntry("R1", "remote-candidate", atUs, mapOf("candidateType" to "srflx")),
            StatEntry(
                "C111",
                "codec",
                atUs,
                mapOf("mimeType" to "audio/opus", "clockRate" to 48_000L, "channels" to 2L, "sdpFmtpLine" to "minptime=10;useinbandfec=1"),
            ),
            StatEntry(
                "OUT",
                "outbound-rtp",
                atUs,
                mapOf("kind" to "audio", "codecId" to "C111", "bytesSent" to BigInteger.valueOf(sent), "packetsSent" to 50L),
            ),
            StatEntry(
                "IN",
                "inbound-rtp",
                atUs,
                mapOf(
                    "kind" to "audio",
                    "codecId" to "C111",
                    "bytesReceived" to BigInteger.valueOf(received),
                    "packetsReceived" to 99L,
                    "packetsLost" to 1,
                    "jitter" to 0.002,
                    "jitterBufferDelay" to 4.0,
                    "jitterBufferEmittedCount" to BigInteger.valueOf(100),
                ),
            ),
        )

    @Test
    fun theSelectedPairCodecAndSuiteAreRead() {
        val stats = CallStatsReader().read(report(0.0, 0, 0), wifiDirect = false)
        assertEquals("relay", stats.localType)
        assertEquals("srflx", stats.remoteType)
        assertEquals(42.0, stats.rttMs!!, 1e-9)
        assertEquals("relay (TURN/UDP) ↔ srflx · UDP · Wi-Fi", pathOf(stats))
        assertEquals("Opus · 48 kHz · 2 ch · FEC", codecOf(stats))
        assertEquals("1.2 · ECDHE-ECDSA / AES-128-GCM-SHA256", dtlsOf(stats))
        assertEquals("AEAD_AES_256_GCM", stats.srtpCipher)
        assertEquals(2.0, stats.jitterMs!!, 1e-9)
        assertEquals(1.0, stats.lossPercent!!, 1e-9)
        assertEquals(40.0, stats.jitterBufferMs!!, 1e-9)
        assertEquals(2, stats.localCandidates)
        assertEquals(1, stats.remoteCandidates)
    }

    @Test
    fun bitratesComeFromTheSecondReportOn() {
        val reader = CallStatsReader()
        val first = reader.read(report(0.0, 0, 0), wifiDirect = false)
        assertNull(first.sendKbps)
        assertNull(first.receiveKbps)
        // 4 000 bytes in a second is 32 kbit/s; 2 000 is 16.
        val second = reader.read(report(1_000_000.0, 4_000, 2_000), wifiDirect = false)
        assertEquals(32.0, second.sendKbps!!, 1e-9)
        assertEquals(16.0, second.receiveKbps!!, 1e-9)
    }

    @Test
    fun aWifiDirectPairSaysSoAndAnEmptyReportShowsDashes() {
        val direct = CallStats(localType = "host", remoteType = "host", protocol = "udp", networkType = "ethernet", wifiDirect = true)
        assertEquals("host ↔ host · UDP · Wi-Fi Direct", pathOf(direct))
        val empty = CallStatsReader().read(emptyList(), wifiDirect = false)
        assertEquals("—", pathOf(empty))
        assertEquals("—", codecOf(empty))
        assertEquals("—", dtlsOf(empty))
        assertEquals("—", ms(empty.rttMs))
        assertFalse(empty.fec)
        assertEquals("4.0 kbps", kbps(4.0))
        assertEquals("48 kbps", kbps(48.4))
    }

    @Test
    fun thePairSummaryListsAnsweredPairsFastestFirstWithTheSelectedOneStarredAndNoAddress() {
        val report =
            listOf(
                StatEntry("T", "transport", 0.0, mapOf("selectedCandidatePairId" to "CP2")),
                StatEntry(
                    "CP1",
                    "candidate-pair",
                    0.0,
                    mapOf(
                        "localCandidateId" to "L1",
                        "remoteCandidateId" to "R1",
                        "currentRoundTripTime" to 0.142,
                        "responsesReceived" to 3L,
                    ),
                ),
                // No current round trip yet: the average over its answers stands in.
                StatEntry(
                    "CP2",
                    "candidate-pair",
                    0.0,
                    mapOf(
                        "localCandidateId" to "L2",
                        "remoteCandidateId" to "R2",
                        "totalRoundTripTime" to 0.196,
                        "responsesReceived" to BigInteger.valueOf(2),
                    ),
                ),
                StatEntry("CP3", "candidate-pair", 0.0, mapOf("localCandidateId" to "L1", "responsesReceived" to 0L)),
                StatEntry(
                    "L1",
                    "local-candidate",
                    0.0,
                    mapOf(
                        "candidateType" to "srflx",
                        "protocol" to "udp",
                        "networkType" to "cellular",
                        "address" to "203.0.113.7",
                    ),
                ),
                StatEntry(
                    "L2",
                    "local-candidate",
                    0.0,
                    mapOf(
                        "candidateType" to "relay",
                        "protocol" to "udp",
                        "relayProtocol" to "tcp",
                        "networkType" to "wifi",
                        "address" to "198.51.100.9",
                    ),
                ),
                StatEntry("R1", "remote-candidate", 0.0, mapOf("candidateType" to "srflx", "address" to "192.0.2.1")),
                StatEntry("R2", "remote-candidate", 0.0, mapOf("candidateType" to "prflx", "address" to "192.0.2.2")),
            )
        assertEquals("*relay/prflx udp/turn-tcp wifi 98ms, srflx/srflx udp cellular 142ms (1 pending)", pairSummary(report))
        assertEquals("none answered yet (0 pending)", pairSummary(emptyList()))
    }

    @Test
    fun theRelaySummaryCountsRelayCandidatesByTheirLegToTheServer() {
        fun relay(
            id: String,
            leg: String,
        ) = StatEntry(id, "local-candidate", 0.0, mapOf("candidateType" to "relay", "relayProtocol" to leg, "address" to "198.51.100.9"))
        val report =
            listOf(
                relay("A", "udp"),
                relay("B", "udp"),
                relay("C", "tls"),
                relay("D", "tcp"),
                StatEntry("E", "local-candidate", 0.0, mapOf("candidateType" to "srflx")),
            )
        assertEquals("tcp 1, tls 1, udp 2", relaySummary(report))
        assertEquals("none", relaySummary(emptyList()))
    }

    @Test
    fun lossIsReadEachWayOverTheLastReadingAndTheRelaysServerByName() {
        fun report(
            atUs: Double,
            received: Long,
            lost: Long,
        ) = listOf(
            StatEntry("T", "transport", atUs, mapOf("selectedCandidatePairId" to "CP")),
            StatEntry("CP", "candidate-pair", atUs, mapOf("localCandidateId" to "L", "responsesReceived" to 4L)),
            StatEntry(
                "L",
                "local-candidate",
                atUs,
                mapOf(
                    "candidateType" to "relay",
                    "relayProtocol" to "udp",
                    "url" to "turn:stun.lab.lax.spool.getknit.app:3478?transport=udp",
                    "address" to "198.51.100.9",
                ),
            ),
            StatEntry("IN", "inbound-rtp", atUs, mapOf("kind" to "audio", "packetsReceived" to received, "packetsLost" to lost)),
            StatEntry("RIN", "remote-inbound-rtp", atUs, mapOf("kind" to "audio", "fractionLost" to 0.25)),
        )
        val reader = CallStatsReader()
        val first = reader.read(report(0.0, 100, 0), wifiDirect = false)
        assertNull(first.recentLossPercent)
        assertNull(first.recentPacketsReceived)
        assertEquals(25.0, first.sentLossPercent!!, 1e-9)
        assertEquals("stun.lab.lax.spool.getknit.app", first.relayHost)
        assertEquals("udp 1", first.relays)
        assertEquals(1, first.pairs.size)
        // 40 heard and 10 lost since the last reading is 20 % now, though the call as a whole lost 10 of 150.
        val second = reader.read(report(1_000_000.0, 140, 10), wifiDirect = false)
        assertEquals(20.0, second.recentLossPercent!!, 1e-9)
        assertEquals(40L, second.recentPacketsReceived)
        assertEquals(10.0 * 100 / 150, second.lossPercent!!, 1e-9)
        // Nothing heard since: no loss figure, and the zero the quality judge reads as "not hearing".
        val third = reader.read(report(2_000_000.0, 140, 10), wifiDirect = false)
        assertNull(third.recentLossPercent)
        assertEquals(0L, third.recentPacketsReceived)
    }

    @Test
    fun aServerUrlGivesItsHostName() {
        assertEquals("stun.lab.lax.spool.getknit.app", hostOf("turn:stun.lab.lax.spool.getknit.app:3478?transport=udp"))
        assertEquals("lab.lax.spool.getknit.app", hostOf("turns:lab.lax.spool.getknit.app:5349?transport=tcp"))
        assertEquals("turn.example", hostOf("turn:turn.example"))
        assertEquals("[2001:db8::1]", hostOf("turn:[2001:db8::1]:3478"))
        assertNull(hostOf(null))
        assertNull(hostOf("nonsense"))
    }

    @Test
    fun theDiagnosticsCardListsEveryAnsweredPairAndTheRelay() {
        val stats =
            CallStats(
                relayHost = "stun.lab.lax.spool.getknit.app",
                relays = "tcp 4, udp 5",
                pairs =
                    listOf(
                        PairInfo(true, "relay", "srflx", "udp", "tcp", "wifi", 81.0),
                        PairInfo(false, "srflx", "srflx", "udp", null, "cellular", null),
                    ),
                pendingPairs = 3,
            )
        assertEquals("★ relay/srflx udp/turn-tcp Wi-Fi 81 ms\n· srflx/srflx udp Cellular —\n3 unanswered", pairsText(stats))
        assertEquals("stun.lab.lax.spool.getknit.app · gathered tcp 4, udp 5", relayOf(stats))
        assertEquals("—", relayOf(CallStats()))
    }
}
