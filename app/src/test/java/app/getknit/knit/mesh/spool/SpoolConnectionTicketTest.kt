package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.spool.SpoolTicketsTest.Companion.ticket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Experimental.** One connection's side of entitlement tickets and STUN (docs/VOICE_CALLS.md §2.6, ADR 2026-10.xkp4):
 * when an `ent` goes out and to whom, what its answer does to the connection, the `turnreq` gate on an `ent` spool,
 * and the hello's STUN list. The record state machine around it is [SpoolConnectionTest]'s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpoolConnectionTicketTest {
    private class RecordingLink : SpoolLink {
        val sent = mutableListOf<ByteArray>()
        var closedWith: Pair<Int, String>? = null

        override fun send(bytes: ByteArray): Boolean {
            sent.add(bytes)
            return true
        }

        override fun close(
            code: Int,
            reason: String,
        ) {
            closedWith = code to reason
        }

        inline fun <reified T> last(): T = SpoolCodec.decode<T>(sent.last())!!
    }

    private val urls = listOf("turn:spool.example:3478?transport=udp")

    private fun serverHello(
        turn: Boolean? = null,
        ent: Boolean? = null,
        stun: List<String>? = null,
    ) = SpoolCodec.encode(
        SpoolHello(
            t = SpoolRecordType.HELLO,
            v = SPOOL_RECORD_VERSION,
            min = SPOOL_RECORD_VERSION,
            limits = SpoolLimits(maxBlob = 64, maxRecord = 4096, maxScopes = 8, maxPull = 2, maxFramesCap = 10, maxTtlMs = 1),
            turn = turn,
            ent = ent,
            stun = stun,
        ),
    )

    /** A connection to a host no test ticket names. */
    private fun plain(link: SpoolLink) = SpoolConnection("ws://spool/spool/v1", link, onDigest = {}, onEvent = {})

    private fun ok(q: Long) = SpoolCodec.encode(SpoolOk(t = SpoolRecordType.OK, q = q))

    private fun RecordingLink.sentType(type: String): Boolean = sent.any { SpoolCodec.peekType(it) == type }

    // Inside the test tickets' window, 1800000000..1800086400 (Unix seconds).
    private var nowMs = 1_800_000_100_000L

    private val answers = mutableListOf<Pair<HeldTicket, SpoolReply>>()

    /** A connection to `lab.example` — the host the test tickets name — recording what each ticket was answered. */
    private fun ticketed(link: SpoolLink) =
        SpoolConnection(
            "wss://lab.example/spool/v1?k=token",
            link,
            onDigest = {},
            onEvent = {},
            onTicketAnswer = { held, reply -> answers += held to reply },
            clock = { nowMs },
        )

    private fun ticketOk(link: RecordingLink) = ok(link.last<SpoolEnt>().q)

    private fun ticketErr(
        q: Long,
        msg: String,
    ) = SpoolCodec.encode(SpoolErr(t = SpoolRecordType.ERR, code = SpoolErrCode.TICKET, q = q, msg = msg))

    @Test
    fun `an ent goes out after hello, only to a spool that advertised it, and only for its own host`() =
        runTest {
            val held = ticket()
            val plain = RecordingLink()
            val older = ticketed(plain)
            assertFalse("nothing before the hello", older.present(held))
            older.onMessage(serverHello(turn = true))
            assertFalse(older.ent)
            assertFalse("a spool that never advertised ent skips it unanswered", older.present(held))
            assertFalse(plain.sentType(SpoolRecordType.ENT))

            val link = RecordingLink()
            val conn = ticketed(link)
            conn.onMessage(serverHello(turn = true, ent = true))
            assertTrue(conn.ent)
            // The privacy rule: a ticket for another host never reaches this one, whoever asks.
            assertFalse(conn.present(ticket(aud = "other.example")))
            assertFalse(link.sentType(SpoolRecordType.ENT))
            // A connection to another host refuses the lab ticket the same way.
            val elsewhere = RecordingLink()
            plain(elsewhere).apply { onMessage(serverHello(ent = true)) }.present(held).let(::assertFalse)

            assertTrue(conn.present(held))
            val ent = link.last<SpoolEnt>()
            assertTrue(ent.tk.contentEquals(held.tk))
            assertTrue(ent.sig.contentEquals(held.sig))
            assertEquals(SpoolTicketState.Pending, conn.ticketState())
        }

    @Test
    fun `an accepted ent binds its claims and a refused one neither aborts nor strikes`() =
        runTest {
            val link = RecordingLink()
            val conn = ticketed(link)
            conn.onMessage(serverHello(turn = true, ent = true))
            val first = ticket()
            conn.present(first)
            conn.onMessage(ticketOk(link))
            assertEquals(SpoolTicketState.Accepted(exp = 1_800_086_400L, fl = SpoolTicket.FL_TURN), conn.ticketState())
            assertTrue(answers.single().second is SpoolReply.Ok)

            // More refusals than the strike budget: each one is an answer, never a silence, and the accepted ticket
            // stays bound (S-7.6-15).
            repeat(SpoolConnection.MAX_SILENT_REQUESTS + 1) { i ->
                conn.present(ticket(sigByte = (i + 2).toByte()))
                conn.onMessage(ticketErr(link.last<SpoolEnt>().q, "signature"))
            }
            assertNull(link.closedWith)
            assertTrue(conn.isReady)
            assertNull(conn.fault)
            assertEquals(SpoolTicketState.Accepted(exp = 1_800_086_400L, fl = SpoolTicket.FL_TURN), conn.ticketState())
            val refused = answers.last().second as SpoolReply.Failed
            assertEquals(SpoolErrCode.TICKET, refused.code)
            assertEquals("signature", refused.msg)

            // With nothing accepted, the state says why.
            val fresh = RecordingLink()
            val other = ticketed(fresh)
            other.onMessage(serverHello(ent = true))
            other.present(first)
            other.onMessage(ticketErr(fresh.last<SpoolEnt>().q, "audience"))
            assertEquals(SpoolTicketState.Refused("audience"), other.ticketState())
        }

    @Test
    fun `a ticket presented mid-connection replaces the accepted one`() =
        runTest {
            val link = RecordingLink()
            val conn = ticketed(link)
            conn.onMessage(serverHello(turn = true, ent = true))
            conn.present(ticket(fl = 0))
            conn.onMessage(ticketOk(link))
            assertFalse(conn.ticketedTurn)

            conn.present(ticket(exp = 1_800_090_000L, sigByte = 9))
            // On its way, it already makes this a ticketed TURN connection: the ask will wait for it.
            assertTrue(conn.ticketedTurn)
            conn.onMessage(ticketOk(link))
            assertEquals(SpoolTicketState.Accepted(exp = 1_800_090_000L, fl = SpoolTicket.FL_TURN), conn.ticketState())
            assertTrue(conn.ticketedTurn)

            // With that one live, a TURN ask does not wait on a third still on its way, and a refusal it then gets
            // leaves the accepted ticket in place; once it lapses, nothing older is shown in its stead.
            conn.present(ticket(sigByte = 10))
            var reply: SpoolReply? = null
            launch { reply = conn.turnCredential(3_000) }
            runCurrent()
            val q = link.last<SpoolTurnReq>().q
            conn.onMessage(
                SpoolCodec.encode(SpoolTurnCred(t = SpoolRecordType.TURNCRED, q = q, urls = urls, user = "u", pass = "p", exp = 9)),
            )
            runCurrent()
            assertTrue(reply is SpoolReply.Turn)
            nowMs = 1_800_090_001_000L
            assertEquals(SpoolTicketState.Pending, conn.ticketState())
        }

    @Test
    fun `an acceptance clears the refusal before it, so a lapsed ticket reads none`() =
        runTest {
            val link = RecordingLink()
            val conn = ticketed(link)
            conn.onMessage(serverHello(turn = true, ent = true))
            conn.present(ticket(sigByte = 2))
            conn.onMessage(ticketErr(link.last<SpoolEnt>().q, "signature"))
            assertEquals(SpoolTicketState.Refused("signature"), conn.ticketState())
            conn.present(ticket(sigByte = 3))
            conn.onMessage(ticketOk(link))
            nowMs = 1_800_086_401_000L
            assertEquals(SpoolTicketState.None, conn.ticketState())
        }

    @Test
    fun `an ent spool is sent no turnreq without an accepted ticket that grants turn`() =
        runTest {
            val link = RecordingLink()
            val conn = ticketed(link)
            conn.onMessage(serverHello(turn = true, ent = true))
            assertEquals(SpoolReply.Closed, conn.turnCredential(3_000))
            assertFalse(link.sentType(SpoolRecordType.TURNREQ))

            conn.present(ticket(fl = 0))
            conn.onMessage(ticketOk(link))
            assertEquals(SpoolReply.Closed, conn.turnCredential(3_000))
            // A ticket without the TURN flag would be refused `flags`.
            assertFalse(link.sentType(SpoolRecordType.TURNREQ))
        }

    @Test
    fun `a turnreq waits for the ent on its way and goes only once it is accepted`() =
        runTest {
            val link = RecordingLink()
            val conn = ticketed(link)
            conn.onMessage(serverHello(turn = true, ent = true))
            conn.present(ticket())
            val entQ = link.last<SpoolEnt>().q

            var reply: SpoolReply? = null
            launch { reply = conn.turnCredential(3_000) }
            runCurrent()
            assertFalse(link.sentType(SpoolRecordType.TURNREQ))
            advanceTimeBy(1_000)
            conn.onMessage(ok(entQ))
            runCurrent()
            val q = link.last<SpoolTurnReq>().q
            conn.onMessage(
                SpoolCodec.encode(SpoolTurnCred(t = SpoolRecordType.TURNCRED, q = q, urls = urls, user = "u", pass = "p", exp = 9)),
            )
            runCurrent()
            assertTrue(reply is SpoolReply.Turn)

            // Refused instead, the ask ends there.
            val refusedLink = RecordingLink()
            val refused = ticketed(refusedLink)
            refused.onMessage(serverHello(turn = true, ent = true))
            refused.present(ticket())
            var none: SpoolReply? = null
            launch { none = refused.turnCredential(3_000) }
            runCurrent()
            refused.onMessage(ticketErr(refusedLink.last<SpoolEnt>().q, "key"))
            runCurrent()
            assertEquals(SpoolReply.Closed, none)
            assertFalse(refusedLink.sentType(SpoolRecordType.TURNREQ))
        }

    @Test
    fun `an ent that is never answered costs the turn ask its one short wait`() =
        runTest {
            val link = RecordingLink()
            val conn = ticketed(link)
            conn.onMessage(serverHello(turn = true, ent = true))
            conn.present(ticket())
            var reply: SpoolReply? = null
            launch { reply = conn.turnCredential(3_000) }
            advanceTimeBy(3_001)
            runCurrent()
            assertEquals(SpoolReply.Closed, reply)
            assertFalse(link.sentType(SpoolRecordType.TURNREQ))
            assertNull(link.closedWith)
        }

    @Test
    fun `an accepted ticket lapses at its exp, and a ticket refusal of a turnreq forgets it`() =
        runTest {
            val link = RecordingLink()
            val conn = ticketed(link)
            conn.onMessage(serverHello(turn = true, ent = true))
            conn.present(ticket())
            conn.onMessage(ticketOk(link))
            nowMs = 1_800_086_401_000L
            assertEquals(SpoolTicketState.None, conn.ticketState())
            assertFalse(conn.ticketedTurn)
            assertEquals(SpoolReply.Closed, conn.turnCredential(3_000))
            assertFalse(link.sentType(SpoolRecordType.TURNREQ))

            // The spool's clock can disagree with ours: its `err ticket` is the last word on this connection.
            nowMs = 1_800_000_100_000L
            conn.present(ticket(sigByte = 5))
            conn.onMessage(ticketOk(link))
            var reply: SpoolReply? = null
            launch { reply = conn.turnCredential(3_000) }
            runCurrent()
            conn.onMessage(ticketErr(link.last<SpoolTurnReq>().q, "expired"))
            runCurrent()
            assertEquals("expired", (reply as SpoolReply.Failed).msg)
            assertEquals(SpoolTicketState.None, conn.ticketState())
            assertFalse(conn.ticketedTurn)
        }

    @Test
    fun `a hello's stun list is held to the rule libwebrtc parses and capped at four`() =
        runTest {
            val link = RecordingLink()
            val conn = plain(link)
            conn.onMessage(
                serverHello(
                    stun =
                        listOf(
                            "stun:lab.example:3478",
                            "stun:lab.example:3478",
                            "stun:lab.example:3478?transport=udp",
                            "turn:lab.example:3478",
                            "stun:user@lab.example",
                            "stun:lab.example:70000",
                            "stuns:lab.example:5349",
                            "stun:10.0.2.2",
                            "stun:[2001:db8::1]:3478",
                            "stun:fifth.example",
                        ),
                ),
            )
            assertEquals(
                listOf("stun:lab.example:3478", "stuns:lab.example:5349", "stun:10.0.2.2", "stun:[2001:db8::1]:3478"),
                conn.stun,
            )
            val none = plain(RecordingLink()).apply { onMessage(serverHello()) }
            assertTrue(none.stun.isEmpty())
            assertFalse(none.ent)
        }
}
