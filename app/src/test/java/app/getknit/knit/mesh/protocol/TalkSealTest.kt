package app.getknit.knit.mesh.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A `talk` frame's payload (docs/VOICE_CALLS.md §3.11): the compact seal's layout, its refusals, and its size. */
class TalkSealTest {
    private val ek = ByteArray(32) { it.toByte() }

    @Test
    fun aCompactSealRoundTripsItsHeaderAndCiphertext() {
        val ct = ByteArray(40) { (it * 3).toByte() }
        val bytes = TalkSeal.encode(RatchetHeader(se = 300, ek = ek, pe = 2, n = 70_000), ct)!!
        // The form byte, then se in two varint bytes, pe in one, n in three, the key and the ciphertext.
        assertEquals(1 + 2 + 1 + 3 + 32 + 40, bytes.size)
        assertEquals(1, bytes[0].toInt())
        val (r, back) = TalkSeal.decode(bytes)!!
        assertEquals(300, r.se)
        assertEquals(2, r.pe)
        assertEquals(70_000, r.n)
        assertArrayEquals(ek, r.ek)
        assertNull(r.init)
        assertEquals(0, r.flags)
        assertArrayEquals(ct, back)
    }

    @Test
    fun aHeaderTheCompactFormCannotCarryIsRefused() {
        val ct = ByteArray(16)
        assertNull(TalkSeal.encode(RatchetHeader(se = 1, ek = ek, pe = 1, n = 1, flags = RatchetHeader.FLAG_RESET), ct))
        assertNull(TalkSeal.encode(RatchetHeader(se = 1, ek = ByteArray(31), pe = 1, n = 1), ct))
        assertNull(TalkSeal.encode(RatchetHeader(se = -1, ek = ek, pe = 1, n = 1), ct))
    }

    @Test
    fun aChatContentIsNeverReadAsACompactSeal() {
        val enc =
            EncEnvelope(
                v = 3,
                nonce = ByteArray(0),
                ct = ByteArray(16),
                keys = emptyList(),
                r = RatchetHeader(se = 1, ek = ek, pe = 1, n = 1),
            )
        assertNull(TalkSeal.decode(WireCodec.encodePayload(ChatContent(enc = enc))))
        assertNull(TalkSeal.decode(ByteArray(0)))
        // A key cut short, or a varint that never ends.
        assertNull(TalkSeal.decode(byteArrayOf(1, 1, 1, 1) + ByteArray(31)))
        assertNull(TalkSeal.decode(byteArrayOf(1, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte())))
    }

    /**
     * The budget that makes a walkie work over Coded PHY (about 2.1 KB/s): a 480 ms chunk of Opus at 8 kbps (24 packets of
     * 20 bytes, each behind its length, under a six-byte chunk header) in an unsigned `talk` frame with an 11-character
     * id. Two of these a second must stay well inside the link.
     */
    @Test
    fun aChunkFrameFitsCodedPhy() {
        val plaintext = ByteArray(6 + 24 * 21)
        val sealed = TalkSeal.encode(RatchetHeader(se = 12, ek = ek, pe = 11, n = 4_000), ByteArray(plaintext.size + 16))!!
        val env =
            RelayEnvelope(
                type = FrameType.TALK,
                id = FrameId.new().take(11),
                senderId = "24sd5jd4cptmcs6ltzati2bbsq",
                sentAt = 1_791_533_000_000L,
                recipientId = "ijeeg44piugnvpxwvrf7sfd3b4",
                payload = sealed,
            )
        val wire = WireCodec.encodeWire(WireEnvelope(ttl = 0, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env)))
        assertTrue("a chunk frame is ${wire.size} B", wire.size <= MAX_CHUNK_FRAME)
        // Under 1.5 KB/s at two chunks a second, against Coded PHY's 2.1.
        assertTrue(wire.size * 1_000 / 480 <= 1_500)
    }

    private companion object {
        const val MAX_CHUNK_FRAME = 720
    }
}
