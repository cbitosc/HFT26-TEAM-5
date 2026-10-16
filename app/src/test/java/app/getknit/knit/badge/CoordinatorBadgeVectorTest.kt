package app.getknit.knit.badge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * The badge credential's vectors as the coordinator owns them, **copied, never recomputed**, from knit-spool-coordinator
 * `feat/supporter-badge` @ 7aa6de4, `packages/core/src/badge.test.ts` (coordinator ADR 2026-10.g3jy). Inputs: the RFC 8032
 * §7.1 TEST 1 key, `b = "supporter"`, `sub = 00 … 1f`, `nbf = 1800000000`, `exp = 1802592000`.
 */
class CoordinatorBadgeVectorTest {
    private val keys = listOf(BadgeKeys.RFC8032_TEST1)
    private val now = 1_800_000_000L + 60

    @Test
    fun `the text form carries exactly the pinned bc and sig`() {
        val wrapper = Badges.wrapperOf(TEXT)!!
        assertArrayEquals(wrapperOf(hex(BC), hex(SIG)), wrapper)
        assertEquals(Badges.Claims("supporter", 1_800_000_000L, 1_802_592_000L), Badges.claimsOf(wrapper))
    }

    @Test
    fun `the coordinator's signature passes checks 1 to 5 under the pinned TEST 1 key`() {
        assertEquals(VerifiedBadge("supporter", 1_802_592_000L), Badges.issuerChecks(Badges.wrapperOf(TEXT)!!, now, keys))
    }

    @Test
    fun `the same bc signed under the ticket label is refused`() {
        assertNull(Badges.issuerChecks(wrapperOf(hex(BC), hex(TICKET_LABEL_SIG)), now, keys))
    }

    @Test
    fun `the pinned public key is the RFC's, as the coordinator prints it`() {
        assertEquals(RFC_PUBLIC_B64URL, Base64.getUrlEncoder().withoutPadding().encodeToString(BadgeKeys.RFC8032_TEST1.publicKey))
    }

    @Test
    fun `its sub binds no holder, so no sender is ever shown it`() {
        assertNull(Badges.verify(Badges.wrapperOf(TEXT)!!, ByteArray(16), BadgeFixtures.HOLDER, now, keys))
    }

    private fun wrapperOf(
        bc: ByteArray,
        sig: ByteArray,
    ) = BadgeFixtures.wrapper(bc, sig)

    private companion object {
        const val BC =
            "a6617601636b69644821fe31dfa154a261616269737570706f72746572637375625820" +
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f636e62661a6b49d200636578701a6b715f00"
        const val SIG =
            "a6aefcd8dd3689ff74b1f77cd40ee773a49cc0e21ad5d46908db49d4e91835882b20aea0a7669c547beebd14147931f99db86c5b93f7841b56c5eed9e497a506"
        const val TEXT =
            "knit-badge:v1:omJiY1hVpmF2AWNraWRIIf4x36FUomFhYmlzdXBwb3J0ZXJjc3ViWCAAAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eH2Nu" +
                "YmYaa0nSAGNleHAaa3FfAGNzaWdYQKau_NjdNon_dLH3fNQO53OknMDiGtXUaQjbSdTpGDWIKyCuoKdmnFR77r0UFHkx-Z24bFuT94QbVsXu2eSXpQY"
        const val TICKET_LABEL_SIG =
            "92882cbd0d7e259db59e1515e80e1b648c7283844357abc74dabd8894a077d0f5c9a50394b993c926cee30e07745547f198fac8ddbccd2e07d93c6611beb5601"
        const val RFC_PUBLIC_B64URL = "11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo"
    }
}
