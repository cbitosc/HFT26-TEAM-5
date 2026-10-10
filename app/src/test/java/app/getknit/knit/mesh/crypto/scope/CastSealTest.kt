package app.getknit.knit.mesh.crypto.scope

import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RatchetHeader
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.TalkSeal
import app.getknit.knit.mesh.protocol.WireCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * **Experimental.** The cast seal (docs/VOICE_CALLS.md §2.7): a third object class beside the frame blob and the
 * attachment chunk, on [ScopeVectorTest]'s DM fixture. The vector was produced independently (pyca/cryptography's HKDF
 * and AES-GCM, by a script that first reproduces `sealBlob`), and stays out of [ScopeVectorTest], whose rows are the
 * spec's §13, until the spec accepts §7.7.
 */
class CastSealTest {
    private fun bytes(
        n: Int,
        seed: Int,
    ) = ByteArray(n) { ((it * 7 + seed) and 0xFF).toByte() }

    private val keys = ScopeCrypto.dmSealKeys(bytes(32, 1), NODE_A, NODE_B)
    private val scopeId = ScopeCrypto.dmScopeId(bytes(32, 1), NODE_A, NODE_B)
    private val sig = bytes(64, 4)
    private val signed = bytes(40, 5)

    @Test
    fun theCastSealMatchesItsPinnedHex() {
        val cast = ScopeCrypto.sealCast(keys, scopeId, sig, signed)
        assertEquals(CAST_SEAL, cast.toHex())
        // One 128-byte block: 64 + 2 + 40 rounds up, then the version, nonce and tag around it.
        assertEquals(1 + 12 + 128 + 16, cast.size)
        val opened = ScopeCrypto.openCast(keys, scopeId, cast)
        assertArrayEquals(sig, opened.sig)
        assertArrayEquals(signed, opened.signed)
    }

    @Test
    fun theSameFrameSealsToTheSameBytes() {
        assertArrayEquals(ScopeCrypto.sealCast(keys, scopeId, sig, signed), ScopeCrypto.sealCast(keys, scopeId, sig, signed))
    }

    /** A cast, a frame blob and an attachment chunk never open as one another, whatever their bytes. */
    @Test
    fun noObjectClassOpensAsAnother() {
        val cast = ScopeCrypto.sealCast(keys, scopeId, sig, signed)
        val blob = ScopeCrypto.seal(keys, scopeId, sig, signed)
        val chunk = ScopeCrypto.sealChunk(keys, scopeId, bytes(32, 6), index = 0, total = 1, chunk = signed)
        refused { ScopeCrypto.open(keys, scopeId, cast) }
        refused { ScopeCrypto.openChunk(keys, scopeId, cast) }
        refused { ScopeCrypto.openCast(keys, scopeId, blob) }
        refused { ScopeCrypto.openCast(keys, scopeId, chunk) }
        // The version byte alone is not what keeps them apart: a blob relabelled as a cast still fails its aad.
        refused { ScopeCrypto.openCast(keys, scopeId, byteArrayOf(ScopeCrypto.CAST_SEAL_VERSION) + blob.copyOfRange(1, blob.size)) }
    }

    @Test
    fun aCastOpensOnlyInItsOwnScope() {
        val cast = ScopeCrypto.sealCast(keys, scopeId, sig, signed)
        refused { ScopeCrypto.openCast(keys, bytes(32, 9), cast) }
        val tampered = cast.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        refused { ScopeCrypto.openCast(keys, scopeId, tampered) }
    }

    /** Only the one canonical plaintext opens: whole blocks, a length inside them, at most one block of zero padding. */
    @Test
    fun aPlaintextThatIsNotCanonicalIsRefused() {
        val body = sig + byteArrayOf(0, signed.size.toByte()) + signed
        refused { ScopeCrypto.openCast(keys, scopeId, sealRaw(body)) } // not padded to a block
        refused { ScopeCrypto.openCast(keys, scopeId, sealRaw(body + ByteArray(22) + ByteArray(128))) } // a block too many
        refused { ScopeCrypto.openCast(keys, scopeId, sealRaw(body + ByteArray(21) + byteArrayOf(1))) } // padding not zero
        val long = sig + byteArrayOf(0, 100) + signed
        refused { ScopeCrypto.openCast(keys, scopeId, sealRaw(long + ByteArray(128 - long.size))) } // a length past the end
        val empty = sig + byteArrayOf(0, 0)
        refused { ScopeCrypto.openCast(keys, scopeId, sealRaw(empty + ByteArray(128 - empty.size))) } // nothing carried
        // And the canonical one does open, through the same hand-rolled seal.
        assertArrayEquals(signed, ScopeCrypto.openCast(keys, scopeId, sealRaw(body + ByteArray(22))).signed)
    }

    @Test
    fun theSealRefusesWhatCannotBeCast() {
        refused { ScopeCrypto.sealCast(keys, scopeId, ByteArray(0), signed) }
        refused { ScopeCrypto.sealCast(keys, scopeId, sig, ByteArray(0)) }
        refused { ScopeCrypto.sealCast(keys, scopeId, sig, ByteArray(0x10000)) }
    }

    /**
     * What a spool carries for one 480 ms walkie chunk: the frame `TalkSealTest` sizes for Coded PHY, signed, padded,
     * and sealed. About 1.7 KB/s at two a second, well inside knit-spool's default `SPOOL_MAX_CAST` of 2048.
     */
    @Test
    fun aChunkCastIsOnePaddedSizeClass() {
        val talk = TalkSeal.encode(RatchetHeader(se = 12, ek = ByteArray(32), pe = 11, n = 4_000), ByteArray(6 + 24 * 21 + 16))!!
        val env =
            RelayEnvelope(
                type = FrameType.TALK,
                id = FrameId.new().take(11),
                senderId = "24sd5jd4cptmcs6ltzati2bbsq",
                sentAt = 1_791_533_000_000L,
                recipientId = "ijeeg44piugnvpxwvrf7sfd3b4",
                payload = talk,
            )
        val frame = WireCodec.encodeEnvelope(env)
        val cast = ScopeCrypto.sealCast(keys, scopeId, sig, frame)
        assertEquals("a chunk's frame is ${frame.size} B", CHUNK_CAST_BYTES, cast.size)
        assertTrue(cast.size <= 2_048)
        // A frame a few bytes either way lands in the same class: the spool sees the class, not the frame.
        assertEquals(cast.size, ScopeCrypto.sealCast(keys, scopeId, sig, frame + ByteArray(8)).size)
    }

    /** The cast seal over an arbitrary plaintext, so the opener's canonical-form checks can be reached at all. */
    private fun sealRaw(pt: ByteArray): ByteArray {
        val nonce = ByteArray(12) { 7 }
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.sealKey, "AES"), GCMParameterSpec(128, nonce))
                updateAAD("knit/scope/v1/cast".toByteArray() + scopeId)
            }
        return byteArrayOf(ScopeCrypto.CAST_SEAL_VERSION) + nonce + cipher.doFinal(pt)
    }

    private fun refused(block: () -> Unit) {
        val outcome = runCatching(block)
        assertTrue("expected a refusal, got ${outcome.getOrNull()}", outcome.isFailure)
    }

    private companion object {
        const val NODE_A = "aaaaabbbbbcccccdddddeeeeef"
        const val NODE_B = "zzzzzyyyyyxxxxxwwwwwvvvvvu"

        /** One walkie chunk's cast: a 693-byte frame padded with its signature into 768, plus 1 + 12 + 16. */
        const val CHUNK_CAST_BYTES = 797

        const val CAST_SEAL =
            "0486dfeaff6b30c3cebc549d35183a43023f9b0c354d6f49d5cc1bac81def2774409f7f4ef746f0f4491271f09f7d2635d25" +
                "34931edda7a37adf7b73bd7c0e4a7a08e381a0861afaa8d7bd57436bf0bf26a80fd14a99a9aeadc356cc58a785ee926ca293" +
                "e1632be7c7da48dbbb51a7949e6743e392774d9f469d69cdd86b02f9c71f91415ca1b114fe8db955ad94fa4679cffb59ade4" +
                "6563a3650fd4b3"

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
