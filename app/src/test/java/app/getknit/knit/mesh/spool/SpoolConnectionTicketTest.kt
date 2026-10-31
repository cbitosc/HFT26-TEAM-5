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
 * the hello's STUN list, and the per-bit gates of `entGates` (C-7.6-30) with the upload state they leave
 * (C-7.6-24). The record state machine around it is [SpoolConnectionTest]'s.
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
        entGates: Long? = null,
        cast: SpoolCastInfo? = null,
    ) = SpoolCodec.encode(
        SpoolHello(
            t = SpoolRecordType.HELLO,
            v = SPOOL_RECORD_VERSION,
            min = SPOOL_RECORD_VERSION,
            limits = SpoolLimits(maxBlob = 64, maxRecord = 4096, maxScopes = 8, maxPull = 2, maxFramesCap = 10, maxTtlMs = 1),
            turn = turn,
            ent = ent,
            stun = stun,
            cast = cast,
            entGates = entGates,
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
    fun `the gates are read from the hello exactly as the daemon reads them`() =
        runTest {
            // knit-spool `Records.kt`'s `ticketGates`: no `ent`, no gates; a bare `ent`, the legacy 9.
            val table =
                listOf(
                    Triple(null, null, 0L),
                    Triple(true, null, 9L),
                    Triple(true, 0L, 0L),
                    Triple(true, 2L, 2L),
                    Triple(true, 9L, 9L),
                    Triple(true, 11L, 11L),
                    Triple(null, 11L, 0L),
                )
            for ((ent, entGates, gates) in table) {
                val conn = ticketed(RecordingLink()).apply { onMessage(serverHello(ent = ent, entGates = entGates)) }
                assertEquals("ent=$ent entGates=$entGates", gates, conn.gates)
                val decoded = SpoolCodec.decode<SpoolHello>(serverHello(ent = ent, entGates = entGates))!!
                assertEquals(gates, decoded.ticketGates)
            }
        }

    @Test
    fun `a spool that gates only files is asked for TURN like an unticketed one`() =
        runTest {
            val link = RecordingLink()
            val conn = ticketed(link)
            conn.onMessage(serverHello(turn = true, ent = true, entGates = SpoolTicket.FL_FILES))
            var reply: SpoolReply? = null
            launch { reply = conn.turnCredential(3_000) }
            runCurrent()
            // No ticket held, and none needed: bit 0 is not gated here (C-7.6-30).
            val q = link.last<SpoolTurnReq>().q
            conn.onMessage(
                SpoolCodec.encode(SpoolTurnCred(t = SpoolRecordType.TURNCRED, q = q, urls = urls, user = "u", pass = "p", exp = 9)),
            )
            runCurrent()
            assertTrue(reply is SpoolReply.Turn)
            assertFalse("no ticket, so the ask rides none", conn.ticketedTurn)
        }

    @Test
    fun `each verb answers to its own bit`() =
        runTest {
            val cast = SpoolCastInfo(maxBytes = 2_048, rate = 4)
            val scope = ByteArray(32) { 7 }
            val scopeHex = scope.joinToString("") { "%02x".format(it) }

            // Casts: gated only under bit 3.
            val filesOnly = ticketed(RecordingLink())
            filesOnly.onMessage(serverHello(turn = true, ent = true, entGates = SpoolTicket.FL_FILES, cast = cast))
            filesOnly.sub(listOf(ScopeSub(scope, ScopeBounds(maxFrames = 10, ttlMs = 1, maxBlob = 64))))
            assertTrue(filesOnly.canCast(scopeHex, peerTicketed = false))
            assertFalse(filesOnly.gated(SpoolTicket.FL_TURN))
            assertTrue(filesOnly.gated(SpoolTicket.FL_FILES))
            assertEquals(SpoolUploads.Locked(SpoolUploads.MISSING), filesOnly.uploads())

            val castOnly = ticketed(RecordingLink())
            castOnly.onMessage(serverHello(turn = true, ent = true, entGates = SpoolTicket.FL_CAST, cast = cast))
            castOnly.sub(listOf(ScopeSub(scope, ScopeBounds(maxFrames = 10, ttlMs = 1, maxBlob = 64))))
            assertFalse(castOnly.canCast(scopeHex, peerTicketed = false))
            // Uploads are open where bit 1 is not gated, ticket or not.
            assertEquals(SpoolUploads.Open, castOnly.uploads())
            assertNull(castOnly.uploadPseudonym())

            // No `ent` at all: nothing is gated, whatever else the hello says.
            val open = ticketed(RecordingLink())
            open.onMessage(serverHello(turn = true, entGates = 11L, cast = cast))
            open.sub(listOf(ScopeSub(scope, ScopeBounds(maxFrames = 10, ttlMs = 1, maxBlob = 64))))
            assertTrue(open.canCast(scopeHex, peerTicketed = false))
            assertEquals(SpoolUploads.Open, open.uploads())
        }

    @Test
    fun `uploads under the files gate follow the accepted ticket`() =
        runTest {
            val link = RecordingLink()
            val conn = ticketed(link)
            conn.onMessage(serverHello(ent = true, entGates = 11L))
            assertEquals("locked at once with nothing to present", SpoolUploads.Locked(SpoolUploads.MISSING), conn.uploads())

            // On its way: the spool judges an `aput` after this `ent`, and may refuse it.
            val files = ticket(fl = SpoolTicket.FL_TURN or SpoolTicket.FL_FILES)
            conn.present(files)
            assertEquals(SpoolUploads.Locked(SpoolUploads.PENDING), conn.uploads())
            assertNull(conn.uploadPseudonym())
            conn.onMessage(ticketOk(link))
            assertEquals(SpoolUploads.Open, conn.uploads())
            assertEquals(files.pseudonym, conn.uploadPseudonym())

            // A TURN-only ticket accepted after it replaces it: the flags lack bit 1.
            conn.present(ticket(fl = SpoolTicket.FL_TURN, sigByte = 2))
            conn.onMessage(ticketOk(link))
            assertEquals(SpoolUploads.Locked(SpoolUploads.FLAGS), conn.uploads())
            assertNull(conn.uploadPseudonym())

            // A files ticket that lapses on our clock.
            conn.present(ticket(fl = SpoolTicket.FL_FILES, sigByte = 3))
            conn.onMessage(ticketOk(link))
            assertEquals(SpoolUploads.Open, conn.uploads())
            nowMs = 1_800_086_401_000L
            assertEquals(SpoolUploads.Locked(SpoolUploads.EXPIRED), conn.uploads())
            assertNull(conn.uploadPseudonym())
        }

    @Test
    fun `a late ticket refusal is never read as a refused sub`() =
        runTest {
            val errors = mutableListOf<String>()
            val link = RecordingLink()
            val conn =
                SpoolConnection(
                    "wss://lab.example/spool/v1",
                    link,
                    onDigest = {},
                    onEvent = {},
                    onScopeError = { _, code, _ -> errors += code },
                    clock = { nowMs },
                )
            conn.onMessage(serverHello(ent = true, entGates = 11L))
            val scope = ByteArray(32) { 3 }
            val scopeHex = scope.joinToString("") { "%02x".format(it) }
            conn.sub(listOf(ScopeSub(scope, ScopeBounds(maxFrames = 10, ttlMs = 1, maxBlob = 64))))
            // An `aput`'s refusal whose request already timed out: its `q` names nothing pending any more.
            conn.onMessage(
                SpoolCodec.encode(
                    SpoolErr(t = SpoolRecordType.ERR, code = SpoolErrCode.TICKET, q = 999L, scope = scope, msg = "missing"),
                ),
            )
            assertTrue(conn.isSubscribed(scopeHex))
            assertTrue(errors.isEmpty())
            // A real scoped refusal still lands.
            conn.onMessage(SpoolCodec.encode(SpoolErr(t = SpoolRecordType.ERR, code = SpoolErrCode.QUOTA, scope = scope)))
            assertFalse(conn.isSubscribed(scopeHex))
            assertEquals(listOf(SpoolErrCode.QUOTA), errors)
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
