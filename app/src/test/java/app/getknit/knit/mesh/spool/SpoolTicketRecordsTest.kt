package app.getknit.knit.mesh.spool

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **Experimental.** The entitlement-ticket records (docs/VOICE_CALLS.md §2.6), against the hex knit-spool's
 * `TicketVectorTest` pins on its `exp/turn` branch: the RFC 8032 §7.1 TEST 1 key pair, `aud = "lab.example"`,
 * `nbf = 1800000000`, `exp = 1800086400`, `ps = 00 01 … 0f`, `fl = 1`. The client never signs or verifies, so `tk` and
 * `sig` are pinned as the bytes an `ent` carries and the claims a client reads back out of them. The files fixture
 * (`tkFiles`, `sigFiles`, `helloEntGates`: knit-spool ADR 2026-10.h6pt) is copied from `exp/turn` @ `17a6b84`.
 */
class SpoolTicketRecordsTest {
    @Test
    fun entAndHelloMatchTheDaemonsVectors() {
        assertEquals(
            ENT,
            SpoolCodec.encode(SpoolEnt(t = SpoolRecordType.ENT, q = 7L, tk = TK.fromHex(), sig = SIG.fromHex())).toHex(),
        )
        assertEquals(
            HELLO,
            SpoolCodec
                .encode(
                    SpoolHello(
                        t = SpoolRecordType.HELLO,
                        v = SPOOL_RECORD_VERSION,
                        ent = true,
                        stun = listOf("stun:lab.example:3478"),
                    ),
                ).toHex(),
        )
    }

    @Test
    fun theVectorTicketDecodesToItsClaimsAndNeverToItsPseudonym() {
        val ticket = SpoolCodec.decode<SpoolTicket>(TK.fromHex())!!
        assertEquals(SpoolTicket.VERSION, ticket.v)
        assertEquals("lab.example", ticket.aud)
        assertEquals(1_800_000_000L, ticket.nbf)
        assertEquals(1_800_086_400L, ticket.exp)
        assertEquals(SpoolTicket.FL_TURN, ticket.fl)
        // `ps` (00 01 … 0f) is skipped by the codec, so no rendering of the claims can carry it.
        assertEquals("SpoolTicket(aud=lab.example, nbf=1800000000, exp=1800086400, fl=1, ab=null, am=null)", ticket.toString())
        // A ticket without `ab` or `am` decodes with neither: the spool's own budget and limit apply.
        assertNull(ticket.ab)
        assertNull(ticket.am)
        assertNull(ticket.maxChunks)

        val hello = SpoolCodec.decode<SpoolHello>(HELLO.fromHex())!!
        assertEquals(true, hello.ent)
        assertEquals(listOf("stun:lab.example:3478"), hello.stun)
        val ent = SpoolCodec.decode<SpoolEnt>(ENT.fromHex())!!
        assertArrayEquals(TK.fromHex(), ent.tk)
        assertArrayEquals(SIG.fromHex(), ent.sig)
    }

    @Test
    fun aHelloWithNeitherFieldLeavesBothAbsent() {
        // Presence is the signal (S-7.6-13): an older spool's hello, and the spec's own helloSpool vector, carry neither.
        val plain = SpoolCodec.decode<SpoolHello>(SpoolCodec.encode(SpoolHello(t = SpoolRecordType.HELLO, v = SPOOL_RECORD_VERSION)))!!
        assertNull(plain.ent)
        assertNull(plain.stun)
        assertEquals("a261746568656c6c6f617601", SpoolCodec.encode(SpoolHello(t = SpoolRecordType.HELLO, v = 1)).toHex())
    }

    @Test
    fun theFilesTicketAndTheGatedHelloMatchTheDaemonsVectors() {
        // `tkFiles` is the same fixture with `fl = 3` (TURN and files) and `ab = 134217728`, eight keys in the order
        // v, kid, aud, nbf, exp, ps, fl, ab; a client decodes it and never re-encodes it.
        val ticket = SpoolCodec.decode<SpoolTicket>(TK_FILES.fromHex())!!
        assertEquals("lab.example", ticket.aud)
        assertEquals(SpoolTicket.FL_TURN or SpoolTicket.FL_FILES, ticket.fl)
        assertEquals(134_217_728L, ticket.ab)
        // An `ent` carries the bytes as they came, so the spool verifies exactly what the issuer signed.
        val ent =
            SpoolCodec.decode<SpoolEnt>(
                SpoolCodec.encode(SpoolEnt(SpoolRecordType.ENT, 7L, TK_FILES.fromHex(), SIG_FILES.fromHex())),
            )!!
        assertArrayEquals(TK_FILES.fromHex(), ent.tk)
        assertArrayEquals(SIG_FILES.fromHex(), ent.sig)

        assertEquals(
            HELLO_ENT_GATES,
            SpoolCodec
                .encode(SpoolHello(t = SpoolRecordType.HELLO, v = SPOOL_RECORD_VERSION, ent = true, entGates = 11L))
                .toHex(),
        )
        val hello = SpoolCodec.decode<SpoolHello>(HELLO_ENT_GATES.fromHex())!!
        assertEquals(11L, hello.entGates)
        assertEquals(SpoolTicket.FL_TURN or SpoolTicket.FL_FILES or SpoolTicket.FL_CAST, hello.ticketGates)
    }

    @Test
    fun theFilesTicketGrantsFilesAndCarriesAPseudonymThatNeverPrints() {
        val held = HeldTicket.parse(SpoolTicketsTest.text(TK_FILES.fromHex(), SIG_FILES.fromHex()))!!
        assertEquals(true, held.grantsTurn)
        assertEquals(true, held.grantsFiles)
        assertEquals(false, held.grantsCast)
        val pseudonym = held.pseudonym!!
        // Equal by its bytes — the vector `tk` (fl = 1) carries the same `ps` — and printed by nobody.
        assertEquals(pseudonym, HeldTicket.parse(SpoolTicketsTest.text(TK.fromHex(), SIG.fromHex()))!!.pseudonym)
        assertEquals("TicketPseudonym(…)", pseudonym.toString())
        val printed = listOf(held.toString(), held.claims.toString(), pseudonym.toString()).joinToString()
        assertEquals(false, printed.contains("0001020304"))
    }

    @Test
    fun theAmTicketsMatchTheDaemonsVectorsAndReadTheirLimit() {
        // `tkFilesAm`: files alone, `ab` and `am`, nine keys ending v, …, fl, ab, am; `tkAmOnly`: `am` with no `ab`, eight.
        val both = SpoolCodec.decode<SpoolTicket>(TK_FILES_AM.fromHex())!!
        assertEquals("lab.example", both.aud)
        assertEquals(SpoolTicket.FL_FILES, both.fl)
        assertEquals(1_310_720L, both.ab)
        assertEquals(262_144L, both.am)
        assertEquals(5, both.maxChunks)
        val amOnly = SpoolCodec.decode<SpoolTicket>(TK_AM_ONLY.fromHex())!!
        assertNull(amOnly.ab)
        assertEquals(262_144L, amOnly.am)
        assertEquals(5, amOnly.maxChunks)
        // Held as they came: an `ent` carries the issuer's bytes, and the claims read off them never print `ps`.
        for ((tk, sig) in listOf(TK_FILES_AM to SIG_FILES_AM, TK_AM_ONLY to SIG_AM_ONLY)) {
            val held = HeldTicket.parse(SpoolTicketsTest.text(tk.fromHex(), sig.fromHex()))!!
            assertEquals(true, held.grantsFiles)
            assertEquals(false, held.grantsTurn)
            assertArrayEquals(tk.fromHex(), held.tk)
            assertArrayEquals(sig.fromHex(), held.sig)
            assertEquals(false, held.toString().contains("0001020304"))
        }
    }

    companion object {
        // knit-spool `feat/free-tickets` @ 061bad2, protocol/src/test/kotlin/app/getknit/spool/TicketVectorTest.kt:138-151
        // (knit-spool ADR 2026-10.h4ps): copied, never recomputed.
        const val TK_FILES_AM =
            "a9617601636b69644821fe31dfa154a261636175646b6c61622e6578616d706c65636e62661a6b49d200636578701a6b4b2380" +
                "62707350000102030405060708090a0b0c0d0e0f62666c026261621a0014000062616d1a00040000"
        const val SIG_FILES_AM =
            "6ca646d923b0d93996c771aed64d590d8fc515214eb04ec01cbc7ad838863e45" +
                "c60d831eaa035258542758592712cd7a33e23f7969884f6ae9585053275bc000"
        const val TK_AM_ONLY =
            "a8617601636b69644821fe31dfa154a261636175646b6c61622e6578616d706c65636e62661a6b49d200636578701a6b4b2380" +
                "62707350000102030405060708090a0b0c0d0e0f62666c0262616d1a00040000"
        const val SIG_AM_ONLY =
            "705b1efffa580108dd8eabad93b680366701020bb37fa5b728c65f9f2a86e1c6" +
                "0689cf76ae17a6584b30c2b3e312a5c309d0cece323fc17cad480a61998f9b06"

        // knit-spool `exp/turn` @ 17a6b84, protocol/src/test/kotlin/app/getknit/spool/TicketVectorTest.kt:139-145:
        // copied, never recomputed.
        const val TK_FILES =
            "a8617601636b69644821fe31dfa154a261636175646b6c61622e6578616d706c65636e62661a6b49d200636578701a6b4b2380" +
                "62707350000102030405060708090a0b0c0d0e0f62666c036261621a08000000"
        const val SIG_FILES =
            "1942b4b07f1ef10f7e4061e822d909876b2e5a1597469c5aa70c87c5909dbc9c" +
                "c24dc7703dfcb8a33ea190b19e4b9a9f460319f9ec2d11d17e787afe551b5d0a"
        const val HELLO_ENT_GATES = "a461746568656c6c6f61760163656e74f568656e7447617465730b"

        const val TK =
            "a7617601636b69644821fe31dfa154a261636175646b6c61622e6578616d706c65636e62661a6b49d200" +
                "636578701a6b4b238062707350000102030405060708090a0b0c0d0e0f62666c01"
        const val SIG =
            "c0740d14d5f46f2771721c593fa34a6e926b39182955cc6f8fb15365dddcf229" +
                "da2cb33bdf6f23881cf4b153eedc4238e143fa53ef1a834961040bb55818b809"
        const val ENT =
            "a4617463656e7461710762746b584ba7617601636b69644821fe31dfa154a261636175646b6c61622e6578616d706c65636e6266" +
                "1a6b49d200636578701a6b4b238062707350000102030405060708090a0b0c0d0e0f62666c01637369675840c0740d14d5f46f27" +
                "71721c593fa34a6e926b39182955cc6f8fb15365dddcf229da2cb33bdf6f23881cf4b153eedc4238e143fa53ef1a834961040bb5" +
                "5818b809"
        const val HELLO =
            "a461746568656c6c6f61760163656e74f5647374756e81757374756e3a6c61622e6578616d706c653a33343738"

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
