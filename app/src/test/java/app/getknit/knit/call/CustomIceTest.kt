package app.getknit.knit.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §3.14 (ADR 2026-10.mr88): what an own server may be, and that a call only ever sees a valid one. */
class CustomIceTest {
    @Test
    fun turnUrlsTakeTheShapesLibwebrtcTakes() {
        listOf(
            "turn:turn.example.org",
            "turn:turn.example.org:3478",
            "turns:turn.example.org:5349",
            "turn:203.0.113.7:3478?transport=udp",
            "turns:turn.example.org:443?transport=tcp",
            "turn:[2001:db8::1]:3478",
        ).forEach { assertTrue(it, TurnUrls.accepts(it)) }
    }

    /** One URL libwebrtc refuses fails the whole configuration, so the call: never let one through. */
    @Test
    fun turnUrlsRefuseWhatWouldEndTheCall() {
        listOf(
            "",
            "turn:",
            "stun:turn.example.org",
            "turn://turn.example.org",
            "turn:ada@turn.example.org",
            "turn:turn.example.org:0",
            "turn:turn.example.org:65536",
            "turn:turn.example.org?transport=tls",
            "turn:turn.example.org:3478?transport=udp&x=1",
            "turn:turn.example.org ",
        ).forEach { assertFalse(it, TurnUrls.accepts(it)) }
    }

    @Test
    fun aCredentialIsNonEmptyAndPrintable() {
        assertTrue(TurnUrls.credentialOk("ada"))
        assertTrue(TurnUrls.credentialOk("correct horse battery"))
        assertFalse(TurnUrls.credentialOk(""))
        assertFalse(TurnUrls.credentialOk("ada\n"))
    }

    @Test
    fun eachKindIsUsedOnlyWhileOnAndValid() {
        val own =
            OwnIceServers(
                stunOn = true,
                stunUrls = listOf("stun:stun.example.org:3478"),
                turnOn = true,
                turnUrl = "turn:turn.example.org",
                turnUser = "ada",
                turnPass = "pw",
            )
        val ice = own.effective()
        assertEquals(listOf("stun:stun.example.org:3478"), ice.stun)
        assertEquals(listOf("turn:turn.example.org"), ice.turn?.urls)

        assertTrue(
            own
                .copy(stunOn = false)
                .effective()
                .stun
                .isEmpty(),
        )
        assertNull(own.copy(turnOn = false).effective().turn)
        assertNull(own.copy(turnPass = "").effective().turn)
        assertNull(own.copy(turnUrl = "turn:ada@turn.example.org").effective().turn)
        assertTrue(
            own
                .copy(stunUrls = emptyList())
                .effective()
                .stun
                .isEmpty(),
        )
        assertTrue(
            own
                .copy(stunUrls = List(StunServers.MAX + 1) { "stun:s$it.example.org" })
                .effective()
                .stun
                .isEmpty(),
        )
        assertTrue(
            own
                .copy(stunUrls = listOf("stun:a.example.org", "stun:a.example.org"))
                .effective()
                .stun
                .isEmpty(),
        )
    }

    /** A TURN password never reaches a log line through either type's toString. */
    @Test
    fun noToStringNamesThePassword() {
        val own = OwnIceServers(turnOn = true, turnUrl = "turn:t.example.org", turnUser = "ada", turnPass = "s3cret-pass")
        assertFalse(own.toString().contains("s3cret-pass"))
        assertFalse(own.toString().contains("ada"))
        assertFalse(own.effective().toString().contains("s3cret-pass"))
    }
}
