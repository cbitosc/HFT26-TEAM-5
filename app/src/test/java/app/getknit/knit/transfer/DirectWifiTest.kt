package app.getknit.knit.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.security.SecureRandom

/**
 * The Wi-Fi Direct group's pure rules (ADR 2026-09.wtmz, 2026-10.kxnw): the credentials this phone mints are ones the
 * platform's `WifiP2pConfig.Builder` takes and the sweep recognises as its own, the bounds a peer's READY or invite is
 * checked against sit where the platform's do, and [inPrefix] — the gate that admits only a client arriving over the
 * group — holds on a subnet that does not end on a byte boundary.
 */
class DirectWifiTest {
    /** `WifiP2pConfig.Builder.setNetworkName`'s own pattern (AOSP), not this class's. */
    private val platformName = Regex("^DIRECT-[a-zA-Z0-9]{2}.*")

    private fun ip(text: String): InetAddress = InetAddress.getByName(text)

    @Test
    fun mintedCredentialsAreOnesThePlatformTakesAndTheSweepKnowsAsOurs() {
        val rng = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(7L) }
        repeat(50) {
            val minted = GroupCredentials.random(rng)

            assertTrue(minted.ssid, platformName.matches(minted.ssid))
            assertTrue(minted.ssid, minted.ssid.toByteArray(Charsets.UTF_8).size <= GroupCredentials.SSID_MAX_BYTES)
            assertTrue(GroupCredentials.isValid(minted.ssid, minted.passphrase))
            assertTrue(GroupCredentials.isOurs(minted.ssid))
            assertEquals(24, minted.passphrase.length)
            assertTrue(minted.passphrase, minted.passphrase.all { it.isLetterOrDigit() && it.code < 128 })
        }
    }

    @Test
    fun aGroupAnotherAppNamedIsNeverOurs() {
        // The sweep removes a leftover group by name; another app's, or a phone's own default group, must survive it.
        assertFalse(GroupCredentials.isOurs("DIRECT-ab-Pixel 8"))
        assertFalse(GroupCredentials.isOurs("DIRECT-knit"))
        assertFalse(GroupCredentials.isOurs(null))
        assertTrue(GroupCredentials.isOurs("DIRECT-kn-abcdefgh"))
    }

    @Test
    fun aNameOfThirtyTwoBytesIsValidAndOneOfThirtyThreeIsNot() {
        val head = "DIRECT-kn-"

        assertTrue(GroupCredentials.isValid(head + "x".repeat(32 - head.length), "passphrase"))
        assertFalse(GroupCredentials.isValid(head + "x".repeat(33 - head.length), "passphrase"))
    }

    @Test
    fun theNamesLengthIsCountedInUtf8BytesNotCharacters() {
        // Twelve two-byte characters: 19 characters, 31 bytes — and one more is 20 characters but 33 bytes.
        assertTrue(GroupCredentials.isValid("DIRECT-" + "é".repeat(12), "passphrase"))
        assertFalse(GroupCredentials.isValid("DIRECT-" + "é".repeat(13), "passphrase"))
    }

    @Test
    fun aNameWithoutTheDirectPrefixIsInvalid() {
        assertFalse(GroupCredentials.isValid("direct-kn-abcdefgh", "passphrase"))
        assertFalse(GroupCredentials.isValid(null, "passphrase"))
    }

    @Test
    fun aPassphraseOfEightToSixtyThreeCharactersIsValidAndNothingOutsideIs() {
        val ssid = "DIRECT-kn-abcdefgh"

        assertTrue(GroupCredentials.isValid(ssid, "x".repeat(GroupCredentials.PASSPHRASE_MIN)))
        assertFalse(GroupCredentials.isValid(ssid, "x".repeat(GroupCredentials.PASSPHRASE_MIN - 1)))
        assertTrue(GroupCredentials.isValid(ssid, "x".repeat(GroupCredentials.PASSPHRASE_MAX)))
        assertFalse(GroupCredentials.isValid(ssid, "x".repeat(GroupCredentials.PASSPHRASE_MAX + 1)))
        assertFalse(GroupCredentials.isValid(ssid, null))
    }

    @Test
    fun aByteAlignedSubnetAdmitsItsOwnAndNoOther() {
        assertTrue(ip("192.168.49.200").inPrefix(ip("192.168.49.1"), 24))
        assertFalse(ip("192.168.48.200").inPrefix(ip("192.168.49.1"), 24))
    }

    @Test
    fun aSubnetEndingMidByteComparesOnlyItsBits() {
        // 192.168.48.0/22 spans .48 to .51 in the third byte: .51 is inside, .52 is the first address past it.
        val network = ip("192.168.48.1")

        assertTrue(ip("192.168.51.9").inPrefix(network, 22))
        assertFalse(ip("192.168.52.9").inPrefix(network, 22))
        assertFalse(ip("192.168.47.9").inPrefix(network, 22))
    }

    @Test
    fun aSubnetSplittingTheLastByteComparesItsHighBits() {
        // 10.0.0.0/25 holds .0 to .127.
        assertTrue(ip("10.0.0.127").inPrefix(ip("10.0.0.1"), 25))
        assertFalse(ip("10.0.0.128").inPrefix(ip("10.0.0.1"), 25))
    }

    @Test
    fun aWholeAddressPrefixAdmitsThatAddressAlone() {
        assertTrue(ip("192.168.49.1").inPrefix(ip("192.168.49.1"), 32))
        assertFalse(ip("192.168.49.2").inPrefix(ip("192.168.49.1"), 32))
    }

    @Test
    fun anIpv6LinkLocalPrefixEndingMidByteHoldsTheWholeRange() {
        // fe80::/10: the second byte's top two bits are 10, so febf:: is inside and fec0:: is not.
        val network = ip("fe80::1")

        assertTrue(ip("febf::1").inPrefix(network, 10))
        assertFalse(ip("fec0::1").inPrefix(network, 10))
        assertTrue(ip("fe80::abcd").inPrefix(network, 64))
        assertFalse(ip("fe80:0:0:1::abcd").inPrefix(network, 64))
    }

    @Test
    fun anAddressOfTheOtherFamilyIsNeverOnTheSubnet() {
        assertFalse(ip("fe80::1").inPrefix(ip("192.168.49.1"), 0))
        assertFalse(ip("192.168.49.1").inPrefix(ip("fe80::1"), 0))
    }

    @Test
    fun aZeroPrefixAdmitsEveryAddressOfTheFamily() {
        assertTrue(ip("8.8.8.8").inPrefix(ip("192.168.49.1"), 0))
    }
}
