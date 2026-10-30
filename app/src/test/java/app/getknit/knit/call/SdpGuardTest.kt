package app.getknit.knit.call

import app.getknit.knit.transfer.GroupAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class SdpGuardTest {
    private val sdp =
        listOf(
            "v=0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=rtpmap:111 opus/48000/2",
            "a=fingerprint:sha-256 AB:CD:EF",
            "a=candidate:1 1 udp 2122260223 192.0.2.1 50000 typ host",
            "a=candidate:2 1 udp 1686052607 198.51.100.7 50001 typ srflx raddr 192.0.2.1 rport 50000",
            "a=candidate:3 1 udp 41885439 203.0.113.9 49170 typ relay raddr 198.51.100.7 rport 50001",
            "",
        ).joinToString("\r\n")

    @Test
    fun anOpusAudioOfferKeyedByAFingerprintIsAccepted() {
        assertTrue(SdpGuard.accepts(sdp))
        assertTrue("bare newlines", SdpGuard.accepts(sdp.replace("\r\n", "\n")))
    }

    @Test
    fun sdesNoFingerprintNoOpusOrNoAudioAreRefused() {
        // C-3.7-1: DTLS-SRTP only — SDES keys in the SDP would hand the media key to whoever reads the signaling.
        assertFalse("SDES", SdpGuard.accepts(sdp.replace("a=fingerprint:sha-256 AB:CD:EF", "a=crypto:1 AES_CM_128_HMAC_SHA1_80 inline:x")))
        assertFalse("SDES beside a fingerprint", SdpGuard.accepts(sdp + "a=crypto:1 AES_CM_128_HMAC_SHA1_80 inline:x\r\n"))
        assertFalse("no fingerprint", SdpGuard.accepts(sdp.replace("a=fingerprint:sha-256 AB:CD:EF\r\n", "")))
        // C-3.7-4: Opus is mandatory.
        assertFalse("no opus", SdpGuard.accepts(sdp.replace("opus/48000/2", "PCMU/8000")))
        assertFalse("no audio m-line", SdpGuard.accepts(sdp.replace("m=audio", "m=video")))
        assertFalse("empty", SdpGuard.accepts(""))
    }

    @Test
    fun aRelayOnlyCallSendsNoHostOrReflexiveCandidate() {
        // C-3.7-3: our own address must not reach the peer when every call is relayed.
        val stripped = SdpGuard.stripToRelay(sdp)
        assertFalse(" typ host" in stripped)
        assertFalse(" typ srflx" in stripped)
        assertTrue(" typ relay" in stripped)
        assertTrue(SdpGuard.accepts(stripped))
        assertEquals(sdp.lines().size - 2, stripped.lines().size)
        assertEquals(
            listOf("candidate:3 1 udp 1 203.0.113.9 49170 typ relay"),
            SdpGuard.relayOnly(listOf("candidate:1 1 udp 1 192.0.2.1 5000 typ host", "candidate:3 1 udp 1 203.0.113.9 49170 typ relay")),
        )
    }

    @Test
    fun aCandidatesAddressIsItsFifthField() {
        assertEquals("192.0.2.1", SdpGuard.candidateAddress("a=candidate:1 1 udp 2122260223 192.0.2.1 50000 typ host"))
        assertEquals("2001:db8::7", SdpGuard.candidateAddress("candidate:2 1 udp 1686052607 2001:db8::7 50001 typ host"))
        assertEquals(null, SdpGuard.candidateAddress("candidate:2 1 udp"))
        assertEquals(null, SdpGuard.candidateAddress("a=fingerprint:sha-256 AB:CD"))
    }

    @Test
    fun aGroupCarriesItsOwnSubnetOnlyAndNeverResolvesAName() {
        val link =
            DirectLink(
                interfaceName = "p2p-wlan0-0",
                subnets = listOf(GroupAddress(InetAddress.getByName("192.168.49.1"), 24)),
            )
        assertTrue(link.carries("192.168.49.23"))
        assertFalse(link.carries("192.168.1.23"))
        assertFalse(link.carries("2001:db8::1"))
        assertFalse("a hostname is never looked up", link.carries("example.invalid"))
        assertFalse("an mDNS-obfuscated host candidate", link.carries("0b1c2d3e-aaaa.local"))
    }
}
