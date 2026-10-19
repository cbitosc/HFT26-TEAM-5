package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.protocol.Protocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The two-way HELLO both radios open a link with ([LinkHandshake]): an accept-any responder learns who dialed from the
 * first record alone, the initiator confirms over the socket who it reached, and anything else in the first record —
 * a frame, a truncation, a closed socket, a tag no build knows — is a rejection, never a guess at the peer.
 */
class LinkHandshakeTest {
    private fun helloFrom(nodeId: String): ByteArray = ByteArrayOutputStream().also { LinkHandshake.writeHello(it, nodeId) }.toByteArray()

    @Test
    fun theResponderLearnsTheInitiatorsIdentityFromItsHello() {
        val peer = LinkHandshake.readHello(ByteArrayInputStream(helloFrom("a1b2c3")))

        assertNotNull(peer)
        assertEquals("a1b2c3", peer!!.nodeId)
        assertEquals(Protocol.VERSION, peer.protoVersion)
        assertEquals(Protocol.LOCAL_CAPABILITIES, peer.capabilities)
    }

    @Test
    fun theHelloIsOneHelloRecordCarryingTheAdvert() {
        val record = LinkFraming.read(ByteArrayInputStream(helloFrom("a1b2c3")))

        assertEquals(LinkFraming.Type.HELLO, record?.type)
        assertArrayEquals(Protocol.advertise("a1b2c3").encodeToByteArray(), record!!.payload)
    }

    @Test
    fun theHelloIsFlushedSoAResponderBlockedOnItsReadSeesIt() {
        // A buffered socket stream that is never flushed would leave the responder waiting out its read bound.
        var flushed = false
        val out =
            object : ByteArrayOutputStream() {
                override fun flush() {
                    flushed = true
                }
            }

        LinkHandshake.writeHello(out, "a1b2c3")

        assertTrue(flushed)
    }

    @Test
    fun theInitiatorConfirmsThePeerItReachedFromTheReply() {
        val reply = ByteArrayOutputStream().also { LinkHandshake.replyHello(it, "d4e5f6") }.toByteArray()

        assertEquals("d4e5f6", LinkHandshake.readHello(ByteArrayInputStream(reply))?.nodeId)
    }

    @Test
    fun aFirstRecordThatIsNotAHelloIsRejected() {
        val frame = LinkFraming.encode(LinkFraming.Type.FRAME, Protocol.advertise("a1b2c3").encodeToByteArray())

        assertNull(LinkHandshake.readHello(ByteArrayInputStream(frame)))
    }

    @Test
    fun aSocketClosedBeforeAnyRecordIsRejected() {
        assertNull(LinkHandshake.readHello(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun aTruncatedHelloIsRejectedRatherThanThrown() {
        val hello = helloFrom("a1b2c3")

        assertNull(LinkHandshake.readHello(ByteArrayInputStream(hello.copyOf(hello.size - 1))))
    }

    @Test
    fun aRecordTagNoBuildKnowsIsRejectedRatherThanThrown() {
        val unknown = byteArrayOf(0x7f, 0, 0, 0, 1, 0x41)

        assertNull(LinkHandshake.readHello(ByteArrayInputStream(unknown)))
    }

    @Test
    fun readingTheHelloLeavesTheRecordsBehindItForTheLink() {
        // The caller keeps reading the same stream for the link's frames, so the handshake must take one record only.
        val frame = byteArrayOf(9, 8, 7)
        val stream = ByteArrayInputStream(helloFrom("a1b2c3") + LinkFraming.encode(LinkFraming.Type.FRAME, frame))

        assertEquals("a1b2c3", LinkHandshake.readHello(stream)?.nodeId)
        val next = LinkFraming.read(stream)
        assertEquals(LinkFraming.Type.FRAME, next?.type)
        assertArrayEquals(frame, next!!.payload)
    }
}
