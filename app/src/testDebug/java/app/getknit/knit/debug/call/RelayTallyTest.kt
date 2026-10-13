package app.getknit.knit.debug.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RelayTallyTest {
    private val udp = "turn:spool.example:3478?transport=udp"
    private val tls = "turns:spool.example:5349?transport=tcp"
    private val tcp = "turn:spool.example:3478?transport=tcp"

    private fun relay(
        address: String,
        port: Int,
        related: String = "198.51.100.9",
        network: String = "network-id 1 network-cost 10",
    ) = "candidate:1 1 udp 41885439 $address $port typ relay raddr $related rport 0 generation 0 ufrag abcd $network"

    @Test
    fun aRelayIsLabelledByItsTransportAndTheFamilyItLeavesOn() {
        val tally = RelayTally()
        assertEquals("udp/wifi#1/ipv4", tally.gathered(relay("203.0.113.5", 50000), udp))
        val cellular = relay("203.0.113.5", 50001, related = "2001:db8::1", network = "network-id 4 network-cost 250")
        assertEquals("tls/cellular#4/ipv6", tally.gathered(cellular, tls))
        assertEquals("tcp/vpn#5/ipv4", tally.gathered(relay("203.0.113.5", 50002, network = "network-id 5 network-cost 1"), tcp))
        assertEquals("?/?#?/ipv4", tally.gathered(relay("203.0.113.5", 50003, network = ""), null))
    }

    @Test
    fun aRelatedAddressBlankedByRelayOnlySaysNoFamily() {
        // libwebrtc empties it to the relay's family, not the network's (Candidate::ToSanitizedCopy).
        val tally = RelayTally()
        assertEquals("udp/wifi#1/?", tally.gathered(relay("203.0.113.5", 50000, related = "0.0.0.0"), udp))
        assertEquals("udp/wifi#1/?", tally.gathered(relay("203.0.113.5", 50001, related = "::"), udp))
    }

    @Test
    fun onlyARelayIsCountedAndOnlyOnce() {
        val tally = RelayTally()
        assertNull(tally.gathered("candidate:2 1 udp 2122260223 192.0.2.7 40000 typ host generation 0", null))
        assertNull(tally.gathered("candidate:3 1 udp 1686052607 198.51.100.9 40001 typ srflx raddr 0.0.0.0 rport 0", udp))
        tally.gathered(relay("203.0.113.5", 50000), udp)
        // Continual gathering can surface the same candidate again.
        assertNull(tally.gathered(relay("203.0.113.5", 50000), udp))
        assertEquals("allocations gathered 1, pruned 0, held 1", tally.summary())
    }

    @Test
    fun aRemovedRelayIsPrunedAndAnUnknownRemovalIsIgnored() {
        val tally = RelayTally()
        tally.gathered(relay("203.0.113.5", 50000), udp)
        tally.gathered(relay("203.0.113.5", 50001), tls)
        tally.gathered(relay("203.0.113.5", 50002), tcp)
        val removed =
            tally.removed(
                listOf(
                    relay("203.0.113.5", 50001),
                    relay("203.0.113.5", 50002),
                    relay("203.0.113.5", 59999),
                    "candidate:2 1 udp 2122260223 192.0.2.7 40000 typ host generation 0",
                ),
            )
        assertEquals("2 (tcp/wifi#1/ipv4 1, tls/wifi#1/ipv4 1)", RelayTally.counts(removed))
        assertEquals("allocations gathered 3, pruned 2, held 1", tally.summary())
        // Removed twice: counted once.
        assertEquals(emptyList<String>(), tally.removed(listOf(relay("203.0.113.5", 50001))))
    }

    @Test
    fun aRestartRetiresTheOldSessionsRelays() {
        val tally = RelayTally()
        tally.gathered(relay("203.0.113.5", 50000), udp)
        tally.restarted()
        tally.gathered(relay("203.0.113.5", 50100), udp)
        assertEquals("allocations gathered 2, pruned 1, held 1", tally.summary())
    }

    @Test
    fun noAddressReachesALabelOrTheSummary() {
        val tally = RelayTally()
        val label = tally.gathered(relay("203.0.113.5", 50000, related = "2001:db8::1"), udp).orEmpty()
        val counted = RelayTally.counts(tally.removed(listOf(relay("203.0.113.5", 50000))))
        for (line in listOf(label, counted, tally.summary())) {
            assertFalse(line, line.contains("203.0.113.5") || line.contains("2001:db8") || line.contains("50000"))
        }
    }
}
