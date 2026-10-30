package app.getknit.knit.mesh.link

import app.getknit.knit.identity.NodeId
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
        val peer = LinkHandshake.readHello(ByteArrayInputStream(helloFrom(A)))

        assertNotNull(peer)
        assertEquals(A, peer!!.nodeId)
        assertEquals(Protocol.VERSION, peer.protoVersion)
        assertEquals(Protocol.LOCAL_CAPABILITIES, peer.capabilities)
    }

    @Test
    fun theHelloIsOneHelloRecordCarryingTheAdvert() {
        val record = LinkFraming.read(ByteArrayInputStream(helloFrom(A)))

        assertEquals(LinkFraming.Type.HELLO, record?.type)
        assertArrayEquals(Protocol.advertise(A).encodeToByteArray(), record!!.payload)
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

        LinkHandshake.writeHello(out, A)

        assertTrue(flushed)
    }

    @Test
    fun theInitiatorConfirmsThePeerItReachedFromTheReply() {
        val reply = ByteArrayOutputStream().also { LinkHandshake.replyHello(it, B) }.toByteArray()

        assertEquals(B, LinkHandshake.readHello(ByteArrayInputStream(reply))?.nodeId)
    }

    @Test
    fun aFirstRecordThatIsNotAHelloIsRejected() {
        val frame = LinkFraming.encode(LinkFraming.Type.FRAME, Protocol.advertise(A).encodeToByteArray())

        assertNull(LinkHandshake.readHello(ByteArrayInputStream(frame)))
    }

    @Test
    fun aHelloNamingANodeIdThatIsNotCanonicalIsRejected() {
        // #199: SQLite drops a leading U+FEFF from a UTF-16 bind, so a BOM-led twin would pass the transports'
        // duplicate-link checks and then read and write the real id's peers row.
        val hello = LinkFraming.encode(LinkFraming.Type.HELLO, Protocol.advertise("\uFEFF$A").encodeToByteArray())

        assertNull(LinkHandshake.readHello(ByteArrayInputStream(hello)))
    }

    @Test
    fun aSocketClosedBeforeAnyRecordIsRejected() {
        assertNull(LinkHandshake.readHello(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun aTruncatedHelloIsRejectedRatherThanThrown() {
        val hello = helloFrom(A)

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
        val stream = ByteArrayInputStream(helloFrom(A) + LinkFraming.encode(LinkFraming.Type.FRAME, frame))

        assertEquals(A, LinkHandshake.readHello(stream)?.nodeId)
        val next = LinkFraming.read(stream)
        assertEquals(LinkFraming.Type.FRAME, next?.type)
        assertArrayEquals(frame, next!!.payload)
    }

    private companion object {
        val A = NodeId.derive("handshake-a")
        val B = NodeId.derive("handshake-b")
    }
}
