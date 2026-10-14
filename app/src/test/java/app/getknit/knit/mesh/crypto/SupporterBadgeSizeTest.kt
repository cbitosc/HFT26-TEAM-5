package app.getknit.knit.mesh.crypto

import app.getknit.knit.badge.Badges
import app.getknit.knit.mesh.protocol.BadgeEntry
import app.getknit.knit.mesh.protocol.ProfilePayload
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What one supporter badge costs a sealed profile (docs/SUPPORTER_BADGE.md C-6-8): the issuer's own vector credential
 * (knit-spool-coordinator @ 7aa6de4) and a 16-byte `r`, on a max-size profile, in both sealed layouts. The cleartext
 * `ProfileContent` never carries it, so `CoordinationPlaneSizeBudgetTest`'s LoRa budgets do not move.
 */
class SupporterBadgeSizeTest {
    private val wrapper =
        Badges.wrapperOf(
            "knit-badge:v1:omJiY1hVpmF2AWNraWRIIf4x36FUomFhYmlzdXBwb3J0ZXJjc3ViWCAAAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eH2Nu" +
                "YmYaa0nSAGNleHAaa3FfAGNzaWdYQKau_NjdNon_dLH3fNQO53OknMDiGtXUaQjbSdTpGDWIKyCuoKdmnFR77r0UFHkx-Z24bFuT94QbVsXu2eSXpQY",
        )!!

    private fun profile(badges: List<BadgeEntry>?) =
        MessageContent(
            body = "",
            ctl = MessageContent.CTL_PROFILE,
            pr =
                ProfilePayload(
                    name = "N".repeat(32),
                    status = "S".repeat(80),
                    avatarHash = "ab".repeat(32),
                    version = 1_800_000_000_000L,
                    openToChat = true,
                    loraNode = 0xdeadbeefL,
                    loraKey = "oR62IJmFUE0Tgcw0GcypU5ZqUFCQllVBy2snB/BKQA4=",
                    badges = badges,
                ),
        )

    @Test
    fun `one badge on a max-size sealed profile`() {
        val one = listOf(BadgeEntry(w = wrapper, r = ByteArray(16)))
        val compactWithout = MessageContentV2.encodeOrNull(profile(null))!!.size
        val compactWith = MessageContentV2.encodeOrNull(profile(one))!!.size
        val namedWithout = profile(null).encode().size
        val namedWith = profile(one).encode().size
        assertEquals("the wrapper", 161, wrapper.size)
        assertEquals("compact, without", EXPECTED_COMPACT_WITHOUT, compactWithout)
        assertEquals("compact, with one badge", EXPECTED_COMPACT_WITHOUT + EXPECTED_COMPACT_COST, compactWith)
        assertEquals("named, with one badge", namedWithout + EXPECTED_NAMED_COST, namedWith)
    }

    private companion object {
        const val EXPECTED_COMPACT_WITHOUT = 211
        const val EXPECTED_COMPACT_COST = 185
        const val EXPECTED_NAMED_COST = 193
    }
}
