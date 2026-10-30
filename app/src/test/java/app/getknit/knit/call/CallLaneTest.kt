package app.getknit.knit.call

import app.getknit.knit.mesh.protocol.CallPayload
import app.getknit.knit.transfer.TransferStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

/** The call lane's framing and seal (§3.10), over in-memory streams: what one end writes, the other reads, or nothing. */
class CallLaneTest {
    private val secret = ByteArray(DIRECT_SECRET_BYTES) { it.toByte() }
    private val ringing = CallPayload(id = ID, ph = CallPayload.PHASE_RINGING, dx = CallPayload.DIRECT_JOINING)
    private val end = CallPayload(id = ID, ph = CallPayload.PHASE_END, rsn = CallPayload.REASON_USER)

    /** What a host writes: its salt, then [frames], sealed. */
    private fun hostBytes(vararg frames: LaneFrame): ByteArray {
        val out = ByteArrayOutputStream()
        val lane = CallLane.host(ByteArrayInputStream(ByteArray(0)), out, secret, ID)
        frames.forEach(lane::send)
        return out.toByteArray()
    }

    private fun joiner(
        bytes: ByteArray,
        id: String = ID,
    ): CallLane = CallLane.join(ByteArrayInputStream(bytes), ByteArrayOutputStream(), secret, id)

    /** The sealed bytes of each frame the host wrote, in order (after the salt). */
    private fun frames(bytes: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var at = CallLane.SALT_BYTES
        while (at < bytes.size) {
            val len =
                ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
            out += bytes.copyOfRange(at, at + 4 + len)
            at += 4 + len
        }
        return out
    }

    @Test
    fun everyFrameKindCrossesAndAnEndOfStreamIsNull() {
        val lines = listOf("candidate:9 1 udp 2122260223 192.168.49.1 50000 typ host")
        val joiner = joiner(hostBytes(LaneFrame.Signal(ringing), LaneFrame.Cands(ID, lines), LaneFrame.Ping, LaneFrame.Bye))
        assertEquals(ringing, (joiner.receive() as LaneFrame.Signal).payload)
        val cands = joiner.receive() as LaneFrame.Cands
        assertEquals(ID, cands.callId)
        assertEquals(lines, cands.lines)
        assertEquals(LaneFrame.Ping, joiner.receive())
        assertEquals(LaneFrame.Bye, joiner.receive())
        assertNull(joiner.receive())
    }

    @Test
    fun theJoinerWritesUnderItsOwnKeyAndOnlyTheHostReadsIt() {
        val hostOut = ByteArrayOutputStream()
        val joinerOut = ByteArrayOutputStream()
        val salted = hostBytes()
        val joiner = CallLane.join(ByteArrayInputStream(salted), joinerOut, secret, ID)
        joiner.send(LaneFrame.Signal(end))
        // The host that wrote the salt reads it…
        val host = hostWithSalt(salted, joinerOut.toByteArray(), hostOut)
        assertEquals(end, (host.receive() as LaneFrame.Signal).payload)
        // …a second joiner, reading the same bytes as if the host had sent them, does not: one key per direction.
        assertThrows(IOException::class.java) { joiner(salted + joinerOut.toByteArray()).receive() }
    }

    @Test
    fun aTamperedReplayedOrMovedFrameEndsTheLane() {
        val bytes = hostBytes(LaneFrame.Signal(ringing), LaneFrame.Signal(end))
        val salt = bytes.copyOfRange(0, CallLane.SALT_BYTES)
        val (first, second) = frames(bytes)

        val tampered = bytes.copyOf().also { it[CallLane.SALT_BYTES + 6] = (it[CallLane.SALT_BYTES + 6] + 1).toByte() }
        assertThrows(IOException::class.java) { joiner(tampered).receive() }

        val replayed = joiner(salt + first + first)
        assertEquals(ringing, (replayed.receive() as LaneFrame.Signal).payload)
        assertThrows(IOException::class.java) { replayed.receive() }

        assertThrows(IOException::class.java) { joiner(salt + second + first).receive() }
    }

    @Test
    fun aFrameIsBoundToItsCallItsSecretAndItsConnectionsSalt() {
        val bytes = hostBytes(LaneFrame.Signal(ringing))
        assertThrows(IOException::class.java) { joiner(bytes, id = "another-call").receive() }
        val otherSecret = ByteArray(DIRECT_SECRET_BYTES) { 7 }
        assertThrows(IOException::class.java) {
            CallLane.join(ByteArrayInputStream(bytes), ByteArrayOutputStream(), otherSecret, ID).receive()
        }
        val resalted = bytes.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertThrows(IOException::class.java) { joiner(resalted).receive() }
    }

    @Test
    fun anOversizedFrameIsRefusedBeforeItIsRead() {
        val salt = hostBytes()
        val huge = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(CallLane.MAX_FRAME_BYTES + 1) }.toByteArray()
        assertThrows(IOException::class.java) { joiner(salt + huge).receive() }
    }

    @Test
    fun theLaneProofIsKeyedApartFromTheSecretAndFromATransfers() {
        val proofKey = CallLane.proofKey(secret)
        assertFalse(proofKey.contentEquals(secret))
        assertEquals(DIRECT_SECRET_BYTES, proofKey.size)
        // A transfer keyed with the same 32 bytes proves nothing on a call's lane, and the other way round.
        assertFalse(TransferStream.clientProof(proofKey, ID).contentEquals(TransferStream.clientProof(secret, ID)))
        assertArrayEquals(proofKey, CallLane.proofKey(secret.copyOf()))
        assertTrue(TransferStream.readProof(ByteArrayInputStream(TransferStream.clientProof(proofKey, ID)), proofKey, ID))
    }

    /** A host lane that reads [incoming] after the salt it wrote as [salted]'s first bytes. */
    private fun hostWithSalt(
        salted: ByteArray,
        incoming: ByteArray,
        out: ByteArrayOutputStream,
    ): CallLane {
        val salt = salted.copyOfRange(0, CallLane.SALT_BYTES)
        // The host's salt is its own random draw; a fixed one stands in for "the salt it wrote".
        val rng =
            object : java.security.SecureRandom() {
                override fun nextBytes(bytes: ByteArray) {
                    salt.copyInto(bytes)
                }
            }
        return CallLane.host(ByteArrayInputStream(incoming), out, secret, ID, rng)
    }

    private companion object {
        const val ID = "call-1"
    }
}
