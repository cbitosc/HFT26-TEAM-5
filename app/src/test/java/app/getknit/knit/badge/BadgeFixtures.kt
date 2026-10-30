package app.getknit.knit.badge

import app.getknit.knit.identity.NodeId
import com.google.crypto.tink.subtle.Ed25519Sign
import java.io.ByteArrayOutputStream

/**
 * Badges built the way the coordinator's `issueBadge` builds them, independently of the code under test: the CBOR is
 * assembled by hand here (definite length, text keys, shortest-form integers) and signed with Tink directly.
 */
internal object BadgeFixtures {
    /** RFC 8032 §7.1 TEST 1's secret seed; its public key is [BadgeKeys.RFC8032_TEST1]. */
    val TEST1_SEED: ByteArray = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")

    const val TICKET_LABEL = "knit/spool/v1/ticket"

    /** The `sub` vector's holder: the node id whose 16 raw bytes are 00 … 0f. */
    val HOLDER: String = NodeId.fromBytes(ByteArray(16) { it.toByte() })

    /** The `sub` vector's randomness: 00 … 0f. */
    val R: ByteArray = ByteArray(16) { it.toByte() }

    const val NBF = 1_800_000_000L
    const val EXP = 1_802_592_000L

    fun sub(
        holder: String = HOLDER,
        r: ByteArray = R,
    ): ByteArray = sha256("knit/badge/v1/sub".encodeToByteArray() + NodeId.toBytes(holder) + r)

    /** The body `bc`, keys in the contract's order, plus any [extra] text-keyed uint entries a newer issuer might add. */
    fun bc(
        v: Long = 1,
        kid: ByteArray = hex(BadgeKeys.RFC8032_TEST1.kid),
        b: String = "supporter",
        sub: ByteArray = sub(),
        nbf: Long = NBF,
        exp: Long = EXP,
        extra: Map<String, ByteArray> = emptyMap(),
    ): ByteArray =
        Cbor()
            .apply {
                map(6 + extra.size)
                text("v")
                uint(v)
                text("kid")
                bytes(kid)
                text("b")
                text(b)
                text("sub")
                bytes(sub)
                text("nbf")
                uint(nbf)
                text("exp")
                uint(exp)
                extra.forEach { (k, value) ->
                    text(k)
                    bytes(value)
                }
            }.toByteArray()

    fun sign(
        bc: ByteArray,
        label: String = "knit/badge/v1",
        seed: ByteArray = TEST1_SEED,
    ): ByteArray = Ed25519Sign(seed).sign(label.encodeToByteArray() + bc)

    fun wrapper(
        bc: ByteArray = bc(),
        sig: ByteArray = sign(bc),
    ): ByteArray =
        Cbor()
            .apply {
                map(2)
                text("bc")
                bytes(bc)
                text("sig")
                bytes(sig)
            }.toByteArray()

    /** A minimal CBOR writer: definite lengths, shortest-form heads. */
    class Cbor {
        private val out = ByteArrayOutputStream()

        fun map(n: Int) = head(5, n.toLong())

        fun uint(n: Long) = head(0, n)

        fun text(s: String) {
            val b = s.encodeToByteArray()
            head(3, b.size.toLong())
            out.write(b)
        }

        fun bytes(b: ByteArray) {
            head(2, b.size.toLong())
            out.write(b)
        }

        fun toByteArray(): ByteArray = out.toByteArray()

        private fun head(
            major: Int,
            n: Long,
        ) {
            val m = major shl 5
            when {
                n < 24 -> {
                    out.write(m or n.toInt())
                }

                n < 0x100 -> {
                    out.write(m or 24)
                    out.write(n.toInt())
                }

                n < 0x10000 -> {
                    out.write(m or 25)
                    for (i in 1 downTo 0) out.write((n shr (8 * i)).toInt() and 0xff)
                }

                n < 0x100000000 -> {
                    out.write(m or 26)
                    for (i in 3 downTo 0) out.write((n shr (8 * i)).toInt() and 0xff)
                }

                else -> {
                    out.write(m or 27)
                    for (i in 7 downTo 0) out.write((n shr (8 * i)).toInt() and 0xff)
                }
            }
        }
    }
}
