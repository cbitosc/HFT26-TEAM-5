package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.spool.SoftwareDeviceSigner.Companion.hex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device proof's message and one signature (docs/VOICE_CALLS.md C-7.6-41, ADR 2026-10.wk3m), for
 * knit-spool-coordinator's issuer to copy into its own test (ADR 2026-10.473c's vector guard), as the stamp vector was
 * shared. Made once with openssl; the pair is a test pair and signs nothing real.
 *
 * ```text
 * path = "/api/v1/ticket"
 * t    = 1800000000                     (Knit-Device-Time: "1800000000")
 * k    = "kak1_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"   (32 bytes 00..1f, unpadded base64url)
 *
 * m (hex) = 6b6e69742f6973737565722f76312f646576696365   "knit/issuer/v1/device"
 *           00
 *           2f6170692f76312f7469636b6574                 "/api/v1/ticket"
 *           00
 *           000000006b49d200                             u64be(1800000000)
 *           8333299e67143e85b2f24515072f02ea61d41b96e5e7fd8820d0a7b1d269d64a   SHA-256(k)
 *
 * private key, PKCS#8 DER (hex):
 *   308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b02010104204743b5bf1014cbf0ed21ea6207edcc01782ab8ae
 *   6d298011a6383053d9a6ca78a14403420004e00f11f41284b40839388a4752efc4537c5c2dad4964340db84224c614307fb35871b7bf581d47
 *   80d086b2b2568589dc10cf5e1c790ab3d6d8f280671a173bfa
 * (the scalar d = 4743b5bf1014cbf0ed21ea6207edcc01782ab8ae6d298011a6383053d9a6ca78)
 *
 * public key, SPKI DER (hex) — Knit-Device-Key is its unpadded base64url:
 *   3059301306072a8648ce3d020106082a8648ce3d03010703420004e00f11f41284b40839388a4752efc4537c5c2dad4964340db84224c614
 *   307fb35871b7bf581d4780d086b2b2568589dc10cf5e1c790ab3d6d8f280671a173bfa
 *
 * one ECDSA-SHA256 signature over m, DER (hex) — ECDSA is randomised, so check it verifies, never that a fresh one equals it:
 *   3045022100c6615f4e91487d498cb37ef3bcb6e3ed7aacd94bf7ca695b18965f2634eb1713022059991b01851e4b2c872ff209290848500d54
 *   2364a8ddd435f20aca9223fdc21d
 * ```
 */
class DeviceProofVectorTest {
    @Test
    fun theMessageIsPinned() {
        assertArrayEquals(M.hex(), DeviceProof.message(PATH, T, K))
    }

    @Test
    fun thePinnedSignatureVerifiesUnderThePinnedKey() {
        assertTrue(SoftwareDeviceSigner.verifies(SPKI.hex(), M.hex(), SIGNATURE.hex()))
    }

    @Test
    fun aSignatureForOnePathOrKeyDoesNotVerifyForAnother() {
        // The path stops a swap's proof from being replayed as a move; the key's hash, one account's against another's.
        assertFalse(SoftwareDeviceSigner.verifies(SPKI.hex(), DeviceProof.message(IssuerOrigin.MOVE_PATH, T, K), SIGNATURE.hex()))
        assertFalse(SoftwareDeviceSigner.verifies(SPKI.hex(), DeviceProof.message(PATH, T, K.dropLast(1) + "g"), SIGNATURE.hex()))
        assertFalse(SoftwareDeviceSigner.verifies(SPKI.hex(), DeviceProof.message(PATH, T + 1, K), SIGNATURE.hex()))
    }

    @Test
    fun theFixedPairSignsWhatItsPublicHalfVerifies() =
        runBlocking {
            val signer = SoftwareDeviceSigner(SoftwareDeviceSigner.fixedPair())
            assertArrayEquals(SPKI.hex(), signer.publicKey())
            assertTrue(SoftwareDeviceSigner.verifies(SPKI.hex(), M.hex(), signer.sign(M.hex())!!))
        }

    @Test
    fun theHeadersAreUnpaddedBase64url() {
        val encoded = DeviceProof.b64(SPKI.hex())
        assertFalse(encoded.contains('=') || encoded.contains('+') || encoded.contains('/'))
        assertEquals(
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE4A8R9BKEtAg5OIpHUu_EU3xcLa1JZDQNuEIkxhQwf7NYcbe_WB1HgNCGsrJWhYncEM9eHHkKs9bY8oBnGhc7-g",
            encoded,
        )
    }

    companion object {
        const val PATH = "/api/v1/ticket"
        const val T = 1_800_000_000L
        const val K = "kak1_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        const val M =
            "6b6e69742f6973737565722f76312f646576696365002f6170692f76312f7469636b657400000000006b49d200" +
                "8333299e67143e85b2f24515072f02ea61d41b96e5e7fd8820d0a7b1d269d64a"
        const val PKCS8 =
            "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b02010104204743b5bf1014cbf0ed21ea6207edcc01782ab8ae" +
                "6d298011a6383053d9a6ca78a14403420004e00f11f41284b40839388a4752efc4537c5c2dad4964340db84224c614307fb35871b7bf581d47" +
                "80d086b2b2568589dc10cf5e1c790ab3d6d8f280671a173bfa"
        const val SPKI =
            "3059301306072a8648ce3d020106082a8648ce3d03010703420004e00f11f41284b40839388a4752efc4537c5c2dad4964340db84224c614" +
                "307fb35871b7bf581d4780d086b2b2568589dc10cf5e1c790ab3d6d8f280671a173bfa"
        const val SIGNATURE =
            "3045022100c6615f4e91487d498cb37ef3bcb6e3ed7aacd94bf7ca695b18965f2634eb1713022059991b01851e4b2c872ff209290848500d54" +
                "2364a8ddd435f20aca9223fdc21d"
    }
}
