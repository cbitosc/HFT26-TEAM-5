package app.getknit.knit.badge

import app.getknit.knit.badge.BadgeFixtures.EXP
import app.getknit.knit.badge.BadgeFixtures.HOLDER
import app.getknit.knit.badge.BadgeFixtures.NBF
import app.getknit.knit.badge.BadgeFixtures.R
import app.getknit.knit.badge.BadgeFixtures.bc
import app.getknit.knit.badge.BadgeFixtures.sign
import app.getknit.knit.badge.BadgeFixtures.wrapper
import app.getknit.knit.identity.NodeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** The six checks of docs/SUPPORTER_BADGE.md §3, one failure each, and the vectors this repo owns. */
class BadgesTest {
    private val keys = listOf(BadgeKeys.RFC8032_TEST1)
    private val now = NBF + 3_600

    private fun verify(
        wrapper: ByteArray = wrapper(),
        r: ByteArray = R,
        sender: String = HOLDER,
        nowS: Long = now,
    ) = Badges.verify(wrapper, r, sender, nowS, keys)

    @Test
    fun `the sub vector`() {
        assertEquals("aaaqeayeaudaocajbifqydiob4", HOLDER)
        assertEquals(
            "1fd9eb19889d6842dd2e28bf6c64042ef673b8e5117b802823400f811ea180c7",
            hexOf(Badges.subject(NodeId.toBytes(HOLDER), R)),
        )
    }

    @Test
    fun `the RFC 8032 TEST 1 key's kid`() {
        assertEquals("21fe31dfa154a261", BadgeKeys.kidOf(BadgeKeys.RFC8032_TEST1.publicKey))
        assertEquals(BadgeKeys.RFC8032_TEST1.kid, BadgeKeys.kidOf(BadgeKeys.RFC8032_TEST1.publicKey))
    }

    @Test
    fun `the production key is pinned as the maintainer's keygen printed it`() {
        val production = BadgeKeys.PRODUCTION
        assertEquals("ea50a7de77a33985", production.kid)
        assertEquals(production.kid, BadgeKeys.kidOf(production.publicKey))
        assertEquals(
            "Ky3T0yKKHnX9wCcJ2DGti16wcEyYDKGxmtXNb5A8JUE",
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(production.publicKey),
        )
        assertEquals(listOf(production), BadgeKeys.forBuild(debug = false))
    }

    @Test
    fun `only a debug build pins the RFC test key`() {
        assertTrue(BadgeKeys.RFC8032_TEST1 in BadgeKeys.forBuild(debug = true))
        assertFalse(BadgeKeys.RFC8032_TEST1 in BadgeKeys.forBuild(debug = false))
    }

    @Test
    fun `a well-formed badge verifies to its id and exp`() {
        assertEquals(VerifiedBadge("supporter", EXP), verify())
    }

    @Test
    fun `check 1 - the wrapper must decode with a 64-byte sig and bc of at most 256 bytes`() {
        assertNull(verify(wrapper = byteArrayOf(1, 2, 3)))
        val body = bc()
        assertNull(verify(wrapper = wrapper(body, sign(body).copyOf(63))))
        val padded = bc(extra = mapOf("pad" to ByteArray(256 - bc().size)))
        assertTrue(padded.size > 256)
        assertNull(verify(wrapper = wrapper(padded)))
    }

    @Test
    fun `a bc of exactly 256 bytes is still read, and an unknown key is ignored`() {
        val body = (0..256).map { bc(extra = mapOf("x" to ByteArray(it))) }.first { it.size == 256 }
        assertEquals(256, body.size)
        assertEquals(VerifiedBadge("supporter", EXP), verify(wrapper = wrapper(body)))
    }

    @Test
    fun `check 2 - only version 1`() {
        assertNull(verify(wrapper = wrapper(bc(v = 2))))
    }

    @Test
    fun `check 3 - an unpinned kid, a tampered body, another key or the ticket label never verify`() {
        assertNull(verify(wrapper = wrapper(bc(kid = ByteArray(8)))))
        val body = bc()
        val tampered = body.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(verify(wrapper = wrapper(tampered, sign(body))))
        assertNull(verify(wrapper = wrapper(body, sign(body, seed = ByteArray(32) { 7 }))))
        assertNull(verify(wrapper = wrapper(body, sign(body, label = BadgeFixtures.TICKET_LABEL))))
        assertNull(Badges.verify(wrapper(), R, HOLDER, now, emptyList()))
    }

    @Test
    fun `check 4 - an unknown badge id is not shown`() {
        assertNull(verify(wrapper = wrapper(bc(b = "gold"))))
    }

    @Test
    fun `check 5 - the window with five minutes of skew each side`() {
        assertEquals("supporter", verify(nowS = NBF - 300)?.b)
        assertNull(verify(nowS = NBF - 301))
        assertEquals("supporter", verify(nowS = EXP + 299)?.b)
        assertNull(verify(nowS = EXP + 300))
        val long = bc(exp = NBF + 2_678_401)
        assertNull(verify(wrapper = wrapper(long)))
        assertEquals("supporter", verify(wrapper = wrapper(bc(exp = NBF + 2_678_400)))?.b)
    }

    @Test
    fun `check 6 - sub binds the sender's id and r`() {
        val other = NodeId.fromBytes(ByteArray(16) { 9 })
        assertNull(verify(sender = other))
        assertNull(verify(r = ByteArray(16) { 1 }))
        assertNull(verify(r = R.copyOf(15)))
        assertNull(verify(sender = "not a node id"))
    }

    @Test
    fun `the text form unwraps to the wrapper`() {
        val w = wrapper()
        val text =
            Badges.TEXT_PREFIX +
                java.util.Base64
                    .getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(w)
        assertTrue(Badges.wrapperOf(text)!!.contentEquals(w))
        assertNull(Badges.wrapperOf("knit-ticket:v1:" + text.removePrefix(Badges.TEXT_PREFIX)))
        assertNull(Badges.wrapperOf(Badges.TEXT_PREFIX + "%%%"))
    }
}
