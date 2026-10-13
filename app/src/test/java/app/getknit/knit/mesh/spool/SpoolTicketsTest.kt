package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.spool.SpoolTicketRecordsTest.Companion.SIG
import app.getknit.knit.mesh.spool.SpoolTicketRecordsTest.Companion.TK
import app.getknit.knit.mesh.spool.SpoolTicketRecordsTest.Companion.fromHex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The client's tickets (experimental, docs/VOICE_CALLS.md §2.6, ADR 2026-10.xkp4): the operator's paste, the host
 * rule, and the refusal policy, over an in-memory [SpoolTicketJournal].
 */
class SpoolTicketsTest {
    /** The journal `SettingsStore` implements, in memory. */
    class MemoryJournal : SpoolTicketJournal {
        val state = MutableStateFlow<Map<String, StoredSpoolTicket>>(emptyMap())
        override val spoolTickets: Flow<Map<String, StoredSpoolTicket>> = state

        override suspend fun putSpoolTicket(
            host: String,
            text: String,
        ) {
            state.value += host to StoredSpoolTicket(text, null)
        }

        override suspend fun removeSpoolTicket(host: String) {
            state.value -= host
        }

        override suspend fun answerSpoolTicket(
            host: String,
            text: String,
            verdict: String?,
            drop: Boolean,
        ) {
            if (state.value[host]?.text != text) return
            state.value = if (drop) state.value - host else state.value + (host to StoredSpoolTicket(text, verdict))
        }
    }

    private val url = "wss://Lab.Example:9480/spool/v1?k=token"

    // The vector ticket's window is 1800000000..1800086400 (Unix seconds); "now" sits inside it.
    private var nowMs = 1_800_000_100_000L
    private val journal = MemoryJournal()
    private val tickets = SpoolTickets(journal) { nowMs }

    @Test
    fun theVectorTextParsesToItsBytesAndClaims() {
        val held = HeldTicket.parse("  $TEXT\n")!!
        assertArrayEquals(TK.fromHex(), held.tk)
        assertArrayEquals(SIG.fromHex(), held.sig)
        assertEquals("lab.example", held.claims.aud)
        assertEquals(1_800_086_400L, held.claims.exp)
        assertTrue(held.grantsTurn)
        assertEquals(TEXT, held.text)
        assertEquals(TEXT, text(TK.fromHex(), SIG.fromHex()))
    }

    @Test
    fun aPasteThatIsNotATicketIsRefused() {
        assertNull(HeldTicket.parse(TEXT.replace("knit-ticket:v1:", "knit-ticket:v2:")))
        assertNull(HeldTicket.parse(TEXT.removePrefix("knit-ticket:v1:")))
        assertNull(HeldTicket.parse("knit-ticket:v1:not base64!"))
        assertNull(HeldTicket.parse(text(tk = TK.fromHex(), sig = ByteArray(63))))
        assertNull(HeldTicket.parse(text(tk = TK.fromHex(), sig = ByteArray(65))))
        // Past 512 bytes the tk is refused before it is read, however well it would decode.
        assertNull(HeldTicket.parse(text(tk = ByteArray(513), sig = SIG.fromHex())))
        assertNull(HeldTicket.parse(text(tk = byteArrayOf(0x01), sig = SIG.fromHex())))
        assertNotNull(HeldTicket.parse(text(tk = TK.fromHex(), sig = SIG.fromHex())))
    }

    @Test
    fun noRenderingOfATicketCarriesItsBytes() {
        val held = HeldTicket.parse(TEXT)!!
        val stored = StoredSpoolTicket(TEXT, SpoolTicketPolicy.RETRY_EARLY)
        for (rendered in listOf(held.toString(), stored.toString(), held.claims.toString(), "$held $stored")) {
            assertFalse(rendered, leaks(rendered))
        }
    }

    @Test
    fun theHostNameIsTheAuthorityWithoutPortTokenOrCase() {
        assertEquals("lab.example", SpoolUrl.hostname(url))
        assertEquals("10.0.2.2", SpoolUrl.hostname("ws://10.0.2.2:9480/spool/v1"))
        assertEquals("relay.example", SpoolUrl.hostname("wss://user@relay.example?k=x"))
        assertEquals("[::1]", SpoolUrl.hostname("ws://[::1]:9480/spool/v1"))
        assertNull(SpoolUrl.hostname("not a url"))
    }

    @Test
    fun aTicketForAnotherHostIsNeverHeld() =
        runTest {
            val refused = tickets.import("wss://other.example/spool/v1", TEXT)
            assertTrue(refused is SpoolTickets.Import.ForAnotherSpool)
            assertEquals("lab.example", (refused as SpoolTickets.Import.ForAnotherSpool).aud)
            assertTrue(journal.state.value.isEmpty())
            assertEquals(SpoolTickets.Import.Unreadable, tickets.import(url, "knit-ticket:v1:AAAA"))

            assertTrue(tickets.import(url, TEXT) is SpoolTickets.Import.Held)
            assertNotNull(tickets.presentable(url))
            // Kept by host name, so the same spool on another port or behind another token presents it, and no other
            // host ever does.
            assertNotNull(tickets.presentable("ws://lab.example/spool/v1"))
            assertNull(tickets.presentable("wss://other.example/spool/v1"))
        }

    @Test
    fun aTicketPastItsExpOnOurClockIsNotPresented() =
        runTest {
            tickets.import(url, TEXT)
            nowMs = 1_800_086_400_000L
            assertNotNull(tickets.presentable(url))
            nowMs += 1_000
            assertNull(tickets.presentable(url))
        }

    @Test
    fun eachRefusalWordIsHandledByThePolicy() =
        runTest {
            for (word in listOf("malformed", "key", "signature", "audience", "a-word-from-a-later-spool")) {
                tickets.clear(url)
                tickets.import(url, TEXT)
                val held = tickets.presentable(url)!!
                tickets.answered(url, held, word)
                assertNull(word, tickets.presentable(url))
                // Importing the same bytes again does not earn them another try; clearing first, or a different
                // ticket, does.
                tickets.import(url, TEXT)
                assertNull(word, tickets.presentable(url))
                assertEquals(word, tickets.view(url)!!.verdict)
            }
            tickets.clear(url)
            tickets.import(url, TEXT)
            assertNotNull(tickets.presentable(url))
        }

    @Test
    fun anExpiredRefusalDropsTheTicket() =
        runTest {
            tickets.import(url, TEXT)
            tickets.answered(url, tickets.presentable(url)!!, "expired")
            assertNull(tickets.view(url))
            assertTrue(journal.state.value.isEmpty())
        }

    @Test
    fun anEarlyRefusalEarnsOneMorePresentationAndAnAcceptanceClearsIt() =
        runTest {
            tickets.import(url, TEXT)
            val held = tickets.presentable(url)!!
            tickets.answered(url, held, "early")
            assertEquals(SpoolTicketPolicy.RETRY_EARLY, tickets.view(url)!!.verdict)
            assertNotNull(tickets.presentable(url))
            tickets.answered(url, held, "early")
            assertNull(tickets.presentable(url))
            assertEquals("early", tickets.view(url)!!.verdict)

            tickets.clear(url)
            tickets.import(url, TEXT)
            tickets.answered(url, held, "early")
            tickets.answered(url, held, null)
            assertNull(tickets.view(url)!!.verdict)
            assertNotNull(tickets.presentable(url))
        }

    @Test
    fun anAnswerToAReplacedTicketNeverLandsOnItsSuccessor() =
        runTest {
            tickets.import(url, TEXT)
            val first = tickets.presentable(url)!!
            val second = text(tk = TK.fromHex(), sig = ByteArray(64) { 7 })
            assertTrue(tickets.import(url, second) is SpoolTickets.Import.Held)
            tickets.answered(url, first, "signature")
            assertNull(tickets.view(url)!!.verdict)
            assertEquals(second, tickets.presentable(url)!!.text)
        }

    @Test
    fun thePolicyTable() {
        assertEquals(SpoolTicketPolicy.Next.Keep(null), SpoolTicketPolicy.afterAnswer(null, null))
        assertEquals(SpoolTicketPolicy.Next.Keep(null), SpoolTicketPolicy.afterAnswer(SpoolTicketPolicy.RETRY_EARLY, null))
        assertEquals(SpoolTicketPolicy.Next.Drop, SpoolTicketPolicy.afterAnswer(null, "expired"))
        assertEquals(SpoolTicketPolicy.Next.Keep(SpoolTicketPolicy.RETRY_EARLY), SpoolTicketPolicy.afterAnswer(null, "early"))
        assertEquals(SpoolTicketPolicy.Next.Keep("early"), SpoolTicketPolicy.afterAnswer(SpoolTicketPolicy.RETRY_EARLY, "early"))
        assertEquals(SpoolTicketPolicy.Next.Keep("signature"), SpoolTicketPolicy.afterAnswer(null, "signature"))
        // A spool's word is bounded like an err code before it is stored.
        assertEquals(SpoolTicketPolicy.Next.Keep("x".repeat(SPOOL_ERR_CODE_MAX)), SpoolTicketPolicy.afterAnswer(null, "x".repeat(500)))
        assertTrue(SpoolTicketPolicy.presentable(null))
        assertTrue(SpoolTicketPolicy.presentable(SpoolTicketPolicy.RETRY_EARLY))
        assertFalse(SpoolTicketPolicy.presentable("early"))
        assertFalse(SpoolTicketPolicy.presentable("key"))
    }

    /** The whole ticket as the issuer encodes it (knit-spool's `Ticket`), for minting test tickets. */
    @Serializable
    private class WireTicket(
        val v: Int,
        val kid: ByteArray,
        val aud: String,
        val nbf: Long,
        val exp: Long,
        val ps: ByteArray,
        val fl: Long,
    )

    @Serializable
    private class TicketText(
        val tk: ByteArray,
        val sig: ByteArray,
    )

    companion object {
        /** The vector ticket as `knit-ticket:v1:` text — knit-spool's `Tickets.encodeText(tk, sig)` over the vector. */
        const val TEXT =
            "knit-ticket:v1:omJ0a1hLp2F2AWNraWRIIf4x36FUomFjYXVka2xhYi5leGFtcGxlY25iZhprSdIAY2V4cBprSyOAYnBzUAABAgMEBQYH" +
                "CAkKCwwNDg9iZmwBY3NpZ1hAwHQNFNX0bydxchxZP6NKbpJrORgpVcxvj7FTZd3c8inaLLM7328jiBz0sVPu3EI44UP6U-8ag0lhBAu1WBi4CQ"

        /** Whether [line] carries any form of the vector ticket: its text, its body, or `tk`/`sig` in hex or base64. */
        fun leaks(line: String): Boolean {
            val body = TEXT.removePrefix("knit-ticket:v1:")
            val forms =
                listOf(
                    body.take(24),
                    TK,
                    SIG,
                    TK.take(32),
                    SIG.take(32),
                    Base64.getEncoder().encodeToString(TK.fromHex()).take(24),
                    Base64.getEncoder().encodeToString(SIG.fromHex()).take(24),
                    // The pseudonym, 00 01 … 0f, in the two spellings a careless rendering would use.
                    "000102030405060708090a0b0c0d0e0f",
                    "[0, 1, 2, 3, 4, 5",
                )
            return forms.any { it in line }
        }

        fun text(
            tk: ByteArray,
            sig: ByteArray,
        ): String = "knit-ticket:v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(SpoolCodec.encode(TicketText(tk, sig)))

        /**
         * A ticket with these claims, as a client holds it — every wire field filled, `kid` and `ps` fixed, `sig` a
         * placeholder (the client never checks it), so [sigByte] is what makes two otherwise equal tickets different.
         */
        fun ticket(
            aud: String = "lab.example",
            nbf: Long = 1_800_000_000L,
            exp: Long = 1_800_086_400L,
            fl: Long = SpoolTicket.FL_TURN,
            sigByte: Byte = 1,
            // Shifts every byte of `ps`: a different pseudonym, as the next day's ticket or another account's carries.
            psSeed: Int = 0,
        ): HeldTicket {
            val tk =
                SpoolCodec.encode(
                    WireTicket(
                        v = 1,
                        kid = ByteArray(8),
                        aud = aud,
                        nbf = nbf,
                        exp = exp,
                        ps =
                            ByteArray(16) {
                                (it + psSeed).toByte()
                            },
                        fl = fl,
                    ),
                )
            return HeldTicket.parse(text(tk, ByteArray(64) { sigByte }))!!
        }
    }
}
