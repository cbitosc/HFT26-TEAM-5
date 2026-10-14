package app.getknit.knit.badge

import app.getknit.knit.badge.BadgeFixtures.EXP
import app.getknit.knit.badge.BadgeFixtures.HOLDER
import app.getknit.knit.badge.BadgeFixtures.NBF
import app.getknit.knit.badge.BadgeFixtures.R
import app.getknit.knit.data.peer.PeerBadgeEntity
import app.getknit.knit.identity.NodeId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The display-time check (docs/SUPPORTER_BADGE.md C-3-3): rows verified at the local clock, and again as it moves. */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerBadgesTest {
    private val keys = listOf(BadgeKeys.RFC8032_TEST1)
    private val good = PeerBadgeEntity(HOLDER, 0, BadgeFixtures.wrapper(), R)

    @Test
    fun `only rows that verify are shown, and a row naming another sender never is`() {
        val stranger = NodeId.fromBytes(ByteArray(16) { 3 })
        val copied = PeerBadgeEntity(stranger, 0, BadgeFixtures.wrapper(), R)
        val shown = PeerBadges.verifyAll(listOf(good, copied), NBF + 10, keys)
        assertEquals(mapOf(HOLDER to listOf(VerifiedBadge("supporter", EXP))), shown)
    }

    @Test
    fun `the next check falls at the window's edges`() {
        assertEquals(EXP + 300, PeerBadges.nextBoundaryS(listOf(good), NBF))
        assertEquals(NBF - 300, PeerBadges.nextBoundaryS(listOf(good), NBF - 1_000))
        assertNull(PeerBadges.nextBoundaryS(listOf(good), EXP + 300))
    }

    @Test
    fun `a badge lapses on its own once the clock passes it, with no new row`() =
        runTest {
            var nowMs = (EXP + 200) * 1_000
            val badges = PeerBadges(MutableStateFlow(listOf(good)), backgroundScope, keys, clock = { nowMs })
            val seen = mutableListOf<Map<String, List<VerifiedBadge>>>()
            backgroundScope.launch { badges.shown.collect { seen += it } }
            runCurrent()
            assertEquals(listOf("supporter"), badges.idsOf(seen.last(), HOLDER))
            nowMs = (EXP + 300) * 1_000
            advanceTimeBy(100_001)
            runCurrent()
            assertTrue(badges.shown.first().isEmpty())
        }
}
