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
        assertEquals("SpoolTicket(aud=lab.example, nbf=1800000000, exp=1800086400, fl=1, ab=null)", ticket.toString())
        // A ticket without `ab` decodes with none: the spool's own budget applies.
        assertNull(ticket.ab)

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

    companion object {
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
