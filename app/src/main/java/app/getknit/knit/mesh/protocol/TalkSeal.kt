package app.getknit.knit.mesh.protocol

import java.io.ByteArrayOutputStream

/**
 * The payload of a [FrameType.TALK] frame (experimental, docs/VOICE_CALLS.md §3.11): one walkie chunk sealed on the DM
 * ratchet, laid out in binary instead of a [ChatContent]'s CBOR, because a burst sends two of these a second over links
 * as slow as Coded PHY's (2.1 KB/s measured). The compact form is the v3 seal (the nonce derived, never carried) under
 * a bare header:
 *
 * ```
 * 0x01 · varint se · varint pe · varint n · ek (32 bytes) · ciphertext (its AEAD tag included)
 * ```
 *
 * A header this form cannot carry — a session's init, a reset flag — or a peer that reads only v2 rides a [ChatContent]
 * instead, whose CBOR map never begins with 0x01. Opaque to every relay: only the addressee reads it. Pure, so
 * `TalkSealTest` pins the layout.
 */
object TalkSeal {
    private const val COMPACT = 1
    private const val EK_BYTES = 32
    private const val VARINT_BITS = 7
    private const val VARINT_MORE = 0x80
    private const val VARINT_MASK = 0x7F
    private const val VARINT_MAX_BYTES = 5

    /** [ct] under [r] in the compact form, or null when [r] carries what it cannot (an init, a flag, an odd key). */
    fun encode(
        r: RatchetHeader,
        ct: ByteArray,
    ): ByteArray? {
        if (!compactable(r)) return null
        val out = ByteArrayOutputStream(1 + 3 * VARINT_MAX_BYTES + EK_BYTES + ct.size)
        out.write(COMPACT)
        for (v in intArrayOf(r.se, r.pe, r.n)) writeVarint(out, v)
        out.write(r.ek)
        out.write(ct)
        return out.toByteArray()
    }

    // Only a plain header: no init, no flags, a whole key, and counters a varint carries.
    private fun compactable(r: RatchetHeader): Boolean {
        val plain = r.init == null && r.flags == 0
        return plain && r.ek.size == EK_BYTES && minOf(r.se, r.pe, r.n) >= 0
    }

    /** The header and ciphertext of a compact [bytes], or null when it is not one (a [ChatContent] is not). */
    fun decode(bytes: ByteArray): Pair<RatchetHeader, ByteArray>? {
        if (bytes.isEmpty() || bytes[0].toInt() != COMPACT) return null
        var at = 1
        val fields = IntArray(3)
        for (i in fields.indices) {
            val (value, next) = readVarint(bytes, at) ?: return null
            fields[i] = value
            at = next
        }
        if (at + EK_BYTES > bytes.size) return null
        val ek = bytes.copyOfRange(at, at + EK_BYTES)
        return RatchetHeader(se = fields[0], ek = ek, pe = fields[1], n = fields[2]) to bytes.copyOfRange(at + EK_BYTES, bytes.size)
    }

    private fun writeVarint(
        out: ByteArrayOutputStream,
        value: Int,
    ) {
        var v = value
        while (v >= VARINT_MORE) {
            out.write((v and VARINT_MASK) or VARINT_MORE)
            v = v ushr VARINT_BITS
        }
        out.write(v)
    }

    // The value and the offset after it, or null for a varint that runs off the end or past an Int.
    private fun readVarint(
        bytes: ByteArray,
        from: Int,
    ): Pair<Int, Int>? {
        var value = 0L
        var at = from
        for (i in 0 until VARINT_MAX_BYTES) {
            if (at >= bytes.size) return null
            val b = bytes[at++].toInt() and 0xFF
            value = value or ((b and VARINT_MASK).toLong() shl (VARINT_BITS * i))
            if (b and VARINT_MORE == 0) return value.takeIf { it <= Int.MAX_VALUE }?.let { it.toInt() to at }
        }
        return null
    }
}
