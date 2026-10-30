package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.spool.SpoolTicketsTest.Companion.ticket
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Experimental.** One connection's side of casts (docs/VOICE_CALLS.md §2.7): the hello's bounds, held to our own
 * ceilings; when a cast may leave at all; the local budget that stands in for a spool which drops silently; and the
 * inbound record. A cast is never a request: no `q`, no pending slot, no strike.
 */
class SpoolConnectionCastTest {
    private class RecordingLink : SpoolLink {
        val sent = mutableListOf<ByteArray>()
        var queue = 0L

        override fun send(bytes: ByteArray): Boolean {
            sent.add(bytes)
            return true
        }

        override fun close(
            code: Int,
            reason: String,
        ) = Unit

        override fun queued(): Long = queue

        fun casts(): List<SpoolCast> =
            sent.filter { SpoolCodec.peekType(it) == SpoolRecordType.CAST }.map { SpoolCodec.decode<SpoolCast>(it)!! }
    }

    private val scope = ByteArray(32) { it.toByte() }
    private val other = ByteArray(32) { (it + 1).toByte() }
    private val data = ByteArray(700) { 5 }

    // Inside the test tickets' window, 1800000000..1800086400 (Unix seconds).
    private var nowMs = 1_800_000_100_000L
    private val heard = mutableListOf<SpoolCast>()

    private fun connection(link: SpoolLink) =
        SpoolConnection(
            "wss://lab.example/spool/v1",
            link,
            onDigest = {},
            onEvent = {},
            clock = { nowMs },
            onCast = { heard += it },
        )

    private fun serverHello(
        cast: SpoolCastInfo? = SpoolCastInfo(maxBytes = 1_024, rate = 1),
        ent: Boolean? = null,
        maxRecord: Int = 4_096,
    ) = SpoolCodec.encode(
        SpoolHello(
            t = SpoolRecordType.HELLO,
            v = SPOOL_RECORD_VERSION,
            min = SPOOL_RECORD_VERSION,
            limits = SpoolLimits(maxBlob = 64, maxRecord = maxRecord, maxScopes = 8, maxPull = 2, maxFramesCap = 10, maxTtlMs = 1),
            ent = ent,
            cast = cast,
        ),
    )

    private suspend fun ready(
        link: RecordingLink,
        hello: ByteArray = serverHello(),
    ): SpoolConnection =
        connection(link).also {
            it.onMessage(hello)
            it.sub(listOf(ScopeSub(scope = scope, bounds = ScopeRegistry.DEFAULT_BOUNDS)))
        }

    @Test
    fun `the hello's cast bounds are read and held to what a record can carry`() =
        runTest {
            val conn = connection(RecordingLink())
            conn.onMessage(serverHello(cast = SpoolCastInfo(maxBytes = 1_000_000, rate = 10_000), maxRecord = 2_048))
            assertEquals(2_048, conn.cast?.maxBytes)
            assertEquals(SpoolConnection.MAX_CAST_RATE, conn.cast?.rate)
            val none = connection(RecordingLink())
            none.onMessage(serverHello(cast = null))
            assertNull(none.cast)
        }

    @Test
    fun `a cast leaves only to a spool that advertised casts, after the hello, for a subscribed scope`() =
        runTest {
            val older = RecordingLink()
            assertFalse(ready(older, serverHello(cast = null)).castOut(scope, data, peerTicketed = false))
            assertTrue("a spool that never advertised casts is sent none", older.casts().isEmpty())

            val link = RecordingLink()
            val before = connection(link)
            assertFalse("nothing before the hello", before.castOut(scope, data, peerTicketed = false))

            val conn = ready(link)
            assertFalse("a scope this connection never subscribed", conn.castOut(other, data, peerTicketed = false))
            assertFalse("over the advertised maxBytes", conn.castOut(scope, ByteArray(1_025), peerTicketed = false))
            assertTrue(conn.castOut(scope, data, peerTicketed = false))
            val sent = link.casts().single()
            assertArrayEquals(scope, sent.scope)
            assertArrayEquals(data, sent.data)
            // Exactly the record, and so no `q`: nothing is waited on.
            assertArrayEquals(SpoolCodec.encode(SpoolCast(t = SpoolRecordType.CAST, scope = scope, data = data)), link.sent.last())
        }

    @Test
    fun `the advertised rate is kept here, because the spool drops past it without a word`() =
        runTest {
            val link = RecordingLink()
            val conn = ready(link)
            // Rate 1, burst 4: four go, the fifth waits for the bucket.
            repeat(4) { assertTrue(conn.castOut(scope, data, peerTicketed = false)) }
            assertFalse(conn.castOut(scope, data, peerTicketed = false))
            nowMs += 1_000
            assertTrue(conn.castOut(scope, data, peerTicketed = false))
            assertEquals(5, link.casts().size)
            // However many were refused, the connection is as it was: no strike, no abort.
            repeat(SpoolConnection.MAX_SILENT_REQUESTS + 2) { conn.castOut(scope, data, peerTicketed = false) }
            assertTrue(conn.isReady)
            assertNull(conn.fault)
        }

    @Test
    fun `a cast that would wait behind a blob is skipped`() =
        runTest {
            val link = RecordingLink()
            val conn = ready(link)
            link.queue = SpoolConnection.CAST_QUEUE_LIMIT + 1
            assertFalse(conn.castOut(scope, data, peerTicketed = false))
            link.queue = 0
            assertTrue(conn.castOut(scope, data, peerTicketed = false))
        }

    @Test
    fun `an ent spool takes a cast only beside a cast ticket, ours or the peer's`() =
        runTest {
            val link = RecordingLink()
            val conn = ready(link, serverHello(ent = true))
            assertFalse(conn.ticketedCast)
            assertFalse("no ticket here and none the peer told us of", conn.castOut(scope, data, peerTicketed = false))
            assertTrue("the peer's ticket unlocks the scope", conn.castOut(scope, data, peerTicketed = true))

            // A TURN-only ticket unlocks nothing; one with the cast flag does, from the moment it is on its way.
            conn.present(ticket(fl = SpoolTicket.FL_TURN))
            assertFalse(conn.ticketedCast)
            conn.present(ticket(fl = SpoolTicket.FL_TURN or SpoolTicket.FL_CAST, sigByte = 2))
            assertTrue(conn.ticketedCast)
            assertTrue(conn.castOut(scope, data, peerTicketed = false))
        }

    @Test
    fun `an inbound cast reaches its callback and nothing else`() =
        runTest {
            val conn = ready(RecordingLink())
            conn.onMessage(SpoolCodec.encode(SpoolCast(t = SpoolRecordType.CAST, scope = scope, data = data)))
            assertArrayEquals(data, heard.single().data)
            conn.onMessage(byteArrayOf(0xa1.toByte(), 0x61, 0x74, 0x64) + "cast".toByteArray())
            assertEquals("an undecodable cast is dropped", 1, heard.size)
        }
}
