package app.getknit.knit.call

import app.getknit.knit.mesh.protocol.DirectInvite
import app.getknit.knit.transfer.GroupAddress
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.Base64

/**
 * What a callee does with the peer's direct-call invite (docs/VOICE_CALLS.md §3.10, ADR 2026-10.kxnw): the invite is
 * the peer's word, so a group the platform would refuse, a privileged or missing port, or a secret that is not a whole
 * 32 bytes makes it unusable rather than half-tried; and a candidate rides the group only when its address is an IP
 * literal on one of the group's subnets — a host name is never resolved.
 */
class CallDirectTest {
    private val secret = ByteArray(DIRECT_SECRET_BYTES) { it.toByte() }

    private fun invite(
        s: String? = "DIRECT-kn-call",
        p: String? = "passphrase",
        o: Int? = 4_000,
        k: String? = Base64.getEncoder().encodeToString(secret),
    ) = DirectInvite(s = s, p = p, o = o, k = k)

    @Test
    fun aCompleteInviteIsUsable() {
        val invite = invite()

        assertTrue(invite.isUsable())
        assertEquals("DIRECT-kn-call", invite.credentials()?.ssid)
        assertEquals("passphrase", invite.credentials()?.passphrase)
        assertArrayEquals(secret, invite.secret())
    }

    @Test
    fun theFirstUnprivilegedPortIsUsableAndTheOneBelowItIsNot() {
        assertTrue(invite(o = 1_024).isUsable())
        assertFalse(invite(o = 1_023).isUsable())
    }

    @Test
    fun theHighestPortIsUsableAndOnePastItIsNot() {
        assertTrue(invite(o = 65_535).isUsable())
        assertFalse(invite(o = 65_536).isUsable())
    }

    @Test
    fun anInviteWithNoPortIsUnusable() {
        assertFalse(invite(o = null).isUsable())
    }

    @Test
    fun aSecretOneByteShortIsNoSecret() {
        val short = invite(k = Base64.getEncoder().encodeToString(ByteArray(DIRECT_SECRET_BYTES - 1)))

        assertNull(short.secret())
        assertFalse(short.isUsable())
    }

    @Test
    fun aSecretOneByteLongIsNoSecret() {
        assertNull(invite(k = Base64.getEncoder().encodeToString(ByteArray(DIRECT_SECRET_BYTES + 1))).secret())
    }

    @Test
    fun aSecretThatIsNotBase64IsNoSecretRatherThanAThrow() {
        val garbled = invite(k = "not base64 at all!")

        assertNull(garbled.secret())
        assertFalse(garbled.isUsable())
    }

    @Test
    fun anInviteWithoutASecretIsUnusable() {
        assertFalse(invite(k = null).isUsable())
    }

    @Test
    fun aGroupNameThePlatformWouldRefuseLeavesNoCredentials() {
        // Wi-Fi Direct requires a `DIRECT-` name; anything else fails the join after the radio has been handed over.
        val refused = invite(s = "Knit-call")

        assertNull(refused.credentials())
        assertFalse(refused.isUsable())
    }

    @Test
    fun aGroupNameWithTheBarePrefixLeavesNoCredentials() {
        // `DIRECT-` with no two alphanumerics after it: the builder throws on the joining side rather than fail the join.
        val bare = invite(s = "DIRECT-")

        assertNull(bare.credentials())
        assertFalse(bare.isUsable())
    }

    @Test
    fun aPassphraseShorterThanWpaAllowsLeavesNoCredentials() {
        assertNull(invite(p = "seven77").credentials())
        assertFalse(invite(p = null).isUsable())
    }

    @Test
    fun anAddressOnTheGroupsSubnetRidesIt() {
        val link = DirectLink("p2p-wlan0-0", listOf(GroupAddress(InetAddress.getByName("192.168.49.1"), 24)))

        assertTrue(link.carries("192.168.49.77"))
        assertFalse(link.carries("192.168.50.77"))
    }

    @Test
    fun anIpv6LinkLocalCandidateRidesAGroupThatCarriesItsPrefix() {
        val link = DirectLink(null, listOf(GroupAddress(InetAddress.getByName("fe80::1"), 64)))

        assertTrue(link.carries("fe80::abcd:1234"))
        assertFalse(link.carries("fe81::abcd:1234"))
    }

    @Test
    fun anAddressOfTheOtherFamilyNeverRidesTheGroup() {
        val link = DirectLink(null, listOf(GroupAddress(InetAddress.getByName("192.168.49.1"), 24)))

        assertFalse(link.carries("fe80::c0a8:3101"))
    }

    @Test
    fun aHostNameIsNeverResolvedOntoTheGroup() {
        // `localhost` would resolve onto this very subnet: only the literal check keeps a name off the group.
        val link = DirectLink(null, listOf(GroupAddress(InetAddress.getByName("127.0.0.1"), 8)))

        assertFalse(link.carries("localhost"))
        assertTrue(link.carries("127.0.0.1"))
    }

    @Test
    fun aLiteralThatIsNoAddressIsNotCarriedRatherThanThrown() {
        val link = DirectLink(null, listOf(GroupAddress(InetAddress.getByName("192.168.49.1"), 24)))

        // Shaped like an IPv6 literal, so it passes the literal check and only the parse can refuse it.
        assertFalse(link.carries("1:2:3:4:5:6:7:8:9"))
    }

    @Test
    fun aShortFormAddressIsNotALiteralEvenWhereItWouldParseOntoTheGroup() {
        // The JDK reads `192.168.49` as 192.168.0.49, which this group holds: only the literal check refuses it.
        val link = DirectLink(null, listOf(GroupAddress(InetAddress.getByName("192.168.0.1"), 24)))

        assertFalse(link.carries("192.168.49"))
        assertTrue(link.carries("192.168.0.49"))
    }

    @Test
    fun aGroupWithNoSubnetCarriesNothing() {
        assertFalse(DirectLink("p2p-wlan0-0", emptyList()).carries("192.168.49.77"))
    }

    @Test
    fun aBuildWithoutTheRadioNeverGoesDirect() =
        runTest {
            val none = CallDirect.None

            assertNull(none.invite("call-1", "peer"))
            assertFalse(none.canJoin("peer", prewarm = true))
            assertFalse(none.pending("call-1"))
            assertNull(none.link.value)
            assertTrue(none.inbound.toList().isEmpty())
        }
}
