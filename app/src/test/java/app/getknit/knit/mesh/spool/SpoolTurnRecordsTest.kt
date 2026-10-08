package app.getknit.knit.mesh.spool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **Experimental.** The TURN provisioning records (docs/VOICE_CALLS.md §2, proposed spec §7.6), against the same hex
 * knit-spool's `TurnRecordVectorTest` pins on its `exp/turn` branch, so the two codecs cannot drift. Kept out of
 * [SpoolRecordsTest], whose vectors mirror the spec's §13 verbatim, until the spec accepts §7.6.
 */
class SpoolTurnRecordsTest {
    @Test
    fun turnRecordsMatchTheDaemonsVectors() {
        val vectors =
            mapOf(
                "helloSpoolTurn" to
                    SpoolCodec.encode(
                        SpoolHello(
                            t = SpoolRecordType.HELLO,
                            v = SPOOL_RECORD_VERSION,
                            min = 1,
                            limits =
                                SpoolLimits(
                                    maxBlob = 65_536,
                                    maxRecord = 131_072,
                                    maxScopes = 64,
                                    maxPull = 64,
                                    maxFramesCap = 1_000,
                                    maxTtlMs = 604_800_000L,
                                ),
                            powBits = 20,
                            turn = true,
                        ),
                    ),
                "turnreq" to SpoolCodec.encode(SpoolTurnReq(t = SpoolRecordType.TURNREQ, q = 7L)),
                "turncred" to
                    SpoolCodec.encode(
                        SpoolTurnCred(
                            t = SpoolRecordType.TURNCRED,
                            q = 7L,
                            urls = listOf("turn:spool.example:3478?transport=udp", "turns:spool.example:5349?transport=tcp"),
                            user = "1800000000:bmjbsibhfy2tyq2k",
                            pass = "yV18C+LcL6biIfL7ZVOuV9xVuoU=",
                            exp = 1_800_000_000L,
                        ),
                    ),
            )
        for ((name, encoded) in vectors) {
            assertEquals("record vector '$name' diverges from knit-spool's", EXPECTED.getValue(name), encoded.toHex())
        }
    }

    @Test
    fun aHelloWithoutTurnLeavesItAbsentAndAPlainHelloStillDecodes() {
        // Presence is the signal (S-7.6-1): the spec's own helloSpool vector carries none.
        val plain = SpoolCodec.decode<SpoolHello>(SpoolCodec.encode(SpoolHello(t = SpoolRecordType.HELLO, v = SPOOL_RECORD_VERSION)))
        assertNull(plain!!.turn)
        val cred = SpoolCodec.decode<SpoolTurnCred>(EXPECTED.getValue("turncred").fromHex())!!
        assertEquals(7L, cred.q)
        assertEquals(2, cred.urls.size)
        assertEquals(1_800_000_000L, cred.exp)
    }

    private companion object {
        val EXPECTED =
            mapOf(
                "helloSpoolTurn" to
                    "a661746568656c6c6f617601636d696e01666c696d697473a6676d6178426c6f621a" +
                    "00010000696d61785265636f72641a00020000696d617853636f7065731840676d61" +
                    "7850756c6c18406c6d61784672616d65734361701903e8686d617854746c4d731a24" +
                    "0c840067706f774269747314647475726ef5",
                "turnreq" to "a26174677475726e726571617107",
                "turncred" to
                    "a66174687475726e637265646171076475726c738278257475726e3a73706f6f6c2e" +
                    "6578616d706c653a333437383f7472616e73706f72743d75647078267475726e733a" +
                    "73706f6f6c2e6578616d706c653a353334393f7472616e73706f72743d7463706475" +
                    "736572781b313830303030303030303a626d6a6273696268667932747971326b6470" +
                    "617373781c79563138432b4c634c36626949664c375a564f7556397856756f553d63" +
                    "6578701a6b49d200",
            )

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
