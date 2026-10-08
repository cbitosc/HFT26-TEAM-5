package app.getknit.knit.call

import app.getknit.knit.mesh.protocol.CallPayload
import app.getknit.knit.mesh.protocol.WireCodec
import com.google.crypto.tink.subtle.Hkdf
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One unit a call's lane carries (§3.10). */
sealed interface LaneFrame {
    /** A call signal, the same `CallPayload` the mesh carries sealed. */
    class Signal(
        val payload: CallPayload,
    ) : LaneFrame

    /** Our candidates on the group, for the peer's media only. */
    class Cands(
        val callId: String,
        val lines: List<String>,
    ) : LaneFrame

    /** Liveness: a lane that hears nothing for a while has lost its group. */
    data object Ping : LaneFrame

    /** The sender is closing the lane on purpose. */
    data object Bye : LaneFrame
}

/**
 * The sealed lane of a call's Wi-Fi Direct leg (docs/VOICE_CALLS.md §3.10): one TCP connection over the group that
 * carries the call's signals beside the mesh, its group candidates, and a ping. Pure and transport-neutral (streams
 * in, streams out), JVM-tested in `CallLaneTest`.
 *
 * The connection is admitted by the transfer's client proof (`TransferStream.clientProof`) under [proofKey], a key
 * derived for calls alone, so a lane secret and a transfer secret never prove each other. The host then writes a
 * fresh salt, and both ends derive one AES-256-GCM key per direction from the call's secret and that salt. Each
 * frame is `len(u32be) ‖ ct ‖ tag(16)`; its nonce is the frame's index in its direction and its AAD the call id,
 * both supplied by the reader's own count — so a frame replayed, dropped or moved fails its tag, and the read ends.
 * A per-connection salt keeps the counter nonces unique if a call ever opens a second lane; the leg opens one.
 *
 * Blocking I/O: callers run it on an I/O dispatcher and stop it by closing the socket under it.
 */
internal class CallLane private constructor(
    input: InputStream,
    output: OutputStream,
    private val sendKey: ByteArray,
    private val recvKey: ByteArray,
    private val aad: ByteArray,
) {
    private val reader = DataInputStream(BufferedInputStream(input))
    private val writer = DataOutputStream(BufferedOutputStream(output))
    private val writeLock = Any()
    private var sent = 0L
    private var received = 0L

    /** Seals and writes [frame]; throws [IOException] when the connection is gone. Safe from any thread. */
    fun send(frame: LaneFrame) {
        val plain = encode(frame)
        synchronized(writeLock) {
            val sealed = cipher(Cipher.ENCRYPT_MODE, sendKey, sent++).doFinal(plain)
            writer.writeInt(sealed.size)
            writer.write(sealed)
            writer.flush()
        }
    }

    /**
     * The next frame, or null at a clean end of the stream. A frame that fails its tag — tampered, replayed, dropped
     * from or moved within the stream — or that will not decode throws [IOException]: the lane is over.
     */
    fun receive(): LaneFrame? {
        val len =
            try {
                reader.readInt()
            } catch (_: EOFException) {
                return null
            }
        if (len !in GCM_TAG_BYTES..MAX_FRAME_BYTES) throw IOException("lane frame of $len bytes")
        val sealed = ByteArray(len).also(reader::readFully)
        val plain =
            try {
                cipher(Cipher.DECRYPT_MODE, recvKey, received++).doFinal(sealed)
            } catch (e: GeneralSecurityException) {
                throw IOException("lane frame failed its tag", e)
            }
        return decode(plain)
    }

    private fun cipher(
        mode: Int,
        key: ByteArray,
        index: Long,
    ): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce(index)))
            updateAAD(aad)
        }

    @Serializable
    private class LaneCands(
        val id: String,
        val cand: List<String>,
    )

    companion object {
        const val SALT_BYTES = 16
        const val MAX_FRAME_BYTES = 64 * 1024

        private const val KEY_BYTES = 32
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val NONCE_BYTES = 12
        private const val COUNTER_BYTES = 8
        private const val BITS_PER_BYTE = 8

        /** Tink spells the same primitive without the dash. */
        private const val HKDF_MAC = "HMACSHA256"

        private const val TYPE_SIGNAL: Byte = 1
        private const val TYPE_CANDS: Byte = 2
        private const val TYPE_PING: Byte = 3
        private const val TYPE_BYE: Byte = 4

        /** Domain separation: disjoint from `knit/xfer/v2/…`, `knit/scope/v1/…`, `knit/dm/v2/…`. */
        private val PROOF_LABEL = "knit/call/v1/lane-proof".toByteArray(Charsets.US_ASCII)
        private val HOST_TO_JOINER = "knit/call/v1/lane/h2j".toByteArray(Charsets.US_ASCII)
        private val JOINER_TO_HOST = "knit/call/v1/lane/j2h".toByteArray(Charsets.US_ASCII)
        private val ZERO_SALT = ByteArray(KEY_BYTES)

        /** The key the joiner proves it holds when it connects; never the call's secret itself. */
        fun proofKey(secret: ByteArray): ByteArray {
            require(secret.size == DIRECT_SECRET_BYTES) { "lane secret must be $DIRECT_SECRET_BYTES bytes" }
            return Hkdf.computeHkdf(HKDF_MAC, secret, ZERO_SALT, PROOF_LABEL, KEY_BYTES)
        }

        /** The host's end, once the joiner's proof is read: writes the connection's salt, then frames. */
        fun host(
            input: InputStream,
            output: OutputStream,
            secret: ByteArray,
            callId: String,
            rng: SecureRandom = SecureRandom(),
        ): CallLane {
            val salt = ByteArray(SALT_BYTES).also(rng::nextBytes)
            output.write(salt)
            output.flush()
            return CallLane(input, output, key(secret, salt, HOST_TO_JOINER), key(secret, salt, JOINER_TO_HOST), aad(callId))
        }

        /** The joiner's end, once its proof is written: reads the host's salt, then frames. */
        fun join(
            input: InputStream,
            output: OutputStream,
            secret: ByteArray,
            callId: String,
        ): CallLane {
            val salt = ByteArray(SALT_BYTES).also(DataInputStream(input)::readFully)
            return CallLane(input, output, key(secret, salt, JOINER_TO_HOST), key(secret, salt, HOST_TO_JOINER), aad(callId))
        }

        private fun key(
            secret: ByteArray,
            salt: ByteArray,
            label: ByteArray,
        ): ByteArray {
            require(secret.size == DIRECT_SECRET_BYTES) { "lane secret must be $DIRECT_SECRET_BYTES bytes" }
            return Hkdf.computeHkdf(HKDF_MAC, secret, salt, label, KEY_BYTES)
        }

        private fun aad(callId: String): ByteArray = callId.toByteArray(Charsets.UTF_8)

        /** Zero-padded big-endian frame index: unique per frame under a key unique per direction and connection. */
        private fun nonce(index: Long): ByteArray =
            ByteArray(NONCE_BYTES).also { out ->
                for (i in 0 until COUNTER_BYTES) out[NONCE_BYTES - 1 - i] = (index ushr (BITS_PER_BYTE * i)).toByte()
            }

        @OptIn(ExperimentalSerializationApi::class)
        private fun encode(frame: LaneFrame): ByteArray =
            when (frame) {
                is LaneFrame.Signal -> {
                    byteArrayOf(TYPE_SIGNAL) + WireCodec.cbor.encodeToByteArray(CallPayload.serializer(), frame.payload)
                }

                is LaneFrame.Cands -> {
                    byteArrayOf(TYPE_CANDS) +
                        WireCodec.cbor.encodeToByteArray(LaneCands.serializer(), LaneCands(frame.callId, frame.lines))
                }

                LaneFrame.Ping -> {
                    byteArrayOf(TYPE_PING)
                }

                LaneFrame.Bye -> {
                    byteArrayOf(TYPE_BYE)
                }
            }

        @OptIn(ExperimentalSerializationApi::class)
        private fun decode(plain: ByteArray): LaneFrame {
            val body = plain.copyOfRange(minOf(1, plain.size), plain.size)
            val frame =
                try {
                    when (plain.firstOrNull()) {
                        TYPE_SIGNAL -> {
                            LaneFrame.Signal(WireCodec.cbor.decodeFromByteArray(CallPayload.serializer(), body))
                        }

                        TYPE_CANDS -> {
                            WireCodec.cbor.decodeFromByteArray(LaneCands.serializer(), body).let { LaneFrame.Cands(it.id, it.cand) }
                        }

                        TYPE_PING -> {
                            LaneFrame.Ping
                        }

                        TYPE_BYE -> {
                            LaneFrame.Bye
                        }

                        else -> {
                            null
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    // kotlinx's SerializationException is one: a body that will not decode ends the lane like a bad tag.
                    throw IOException("lane frame did not decode", e)
                }
            return frame ?: throw IOException("lane frame of type ${plain.firstOrNull()}")
        }
    }
}
