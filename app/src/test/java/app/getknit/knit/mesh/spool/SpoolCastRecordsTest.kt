package app.getknit.knit.mesh.spool

import kotlinx.serialization.Serializable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * **Experimental.** The cast records (docs/VOICE_CALLS.md §2.7), against the hex knit-spool's `CastRecordVectorTest`
 * pins on its `exp/turn` branch: one record for both directions, the hello's `cast` bounds beside `ent`, and a ticket
 * that carries the cast flag (`fl = 9`: TURN and cast) on the §2.6 fixture. The two repos must agree byte for byte.
 */
class SpoolCastRecordsTest {
    @Test
    fun castAndHelloMatchTheDaemonsVectors() {
        assertEquals(CAST, SpoolCodec.encode(SpoolCast(t = SpoolRecordType.CAST, scope = SCOPE, data = DATA)).toHex())
        assertEquals(
            HELLO,
            SpoolCodec
                .encode(
                    SpoolHello(
                        t = SpoolRecordType.HELLO,
                        v = SPOOL_RECORD_VERSION,
                        ent = true,
                        cast = SpoolCastInfo(maxBytes = 2_048, rate = 4),
                    ),
                ).toHex(),
        )
    }

    @Test
    fun theVectorsDecodeBack() {
        val cast = SpoolCodec.decode<SpoolCast>(CAST.fromHex())!!
        assertArrayEquals(SCOPE, cast.scope)
        assertArrayEquals(DATA, cast.data)
        assertEquals(SpoolRecordType.CAST, SpoolCodec.peekType(CAST.fromHex()))
        val hello = SpoolCodec.decode<SpoolHello>(HELLO.fromHex())!!
        assertEquals(2_048, hello.cast?.maxBytes)
        assertEquals(4, hello.cast?.rate)
    }

    @Test
    fun aCastTicketCarriesBitThree() {
        val ticket = SpoolCodec.decode<SpoolTicket>(TK_CAST.fromHex())!!
        assertEquals(SpoolTicket.FL_TURN or SpoolTicket.FL_CAST, ticket.fl)
        // The text an operator pastes, as knit-spool's `Tickets.encodeText` writes it.
        val body = SpoolCodec.encode(TicketBody(tk = TK_CAST.fromHex(), sig = SIG_CAST.fromHex()))
        val held = HeldTicket.parse(HeldTicket.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(body))!!
        assertEquals(true, held.grantsCast)
        assertEquals(true, held.grantsTurn)
    }

    @Test
    fun aHelloWithoutCastLeavesItAbsent() {
        assertNull(SpoolCodec.decode<SpoolHello>(SpoolCodec.encode(SpoolHello(t = SpoolRecordType.HELLO, v = SPOOL_RECORD_VERSION)))!!.cast)
    }

    @Serializable
    private class TicketBody(
        val tk: ByteArray,
        val sig: ByteArray,
    )

    companion object {
        private val SCOPE = ByteArray(32) { it.toByte() }
        private val DATA = ByteArray(16) { (0xa0 + it).toByte() }

        const val CAST =
            "a3617464636173746573636f70655820000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                "646461746150a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"
        const val HELLO = "a461746568656c6c6f61760163656e74f56463617374a2686d61784279746573190800647261746504"
        const val TK_CAST =
            "a7617601636b69644821fe31dfa154a261636175646b6c61622e6578616d706c65636e62661a6b49d200636578701a6b4b2380" +
                "62707350000102030405060708090a0b0c0d0e0f62666c09"
        const val SIG_CAST =
            "9f0d82c5e15ddc486fc693a848dd2e937df32e505f22631dde6a49f82435d147" +
                "b64da30b5ac432ef18ee9e2349c4c8915cc8e9d5a3e829f95c2784bc5a5f530f"

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
