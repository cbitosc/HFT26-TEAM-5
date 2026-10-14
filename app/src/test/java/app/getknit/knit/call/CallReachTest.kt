package app.getknit.knit.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** C-3.3-5's evidence ([CallReachRule]): what lets an unanswered call keep trying past its first seconds. */
class CallReachTest {
    private fun reachable(
        neighbours: Set<String> = emptySet(),
        heard: Set<String> = emptySet(),
        sighted: Boolean = false,
        spool: Set<String> = emptySet(),
    ) = CallReachRule.reachable(PEER, neighbours, heard, sighted, spool)

    @Test
    fun nothingSeenIsUnreachable() {
        // 2026-10-14: no link, no Wi-Fi Aware sighting, and two relays the pair did not share.
        assertFalse(reachable())
        assertFalse("another peer's link is no path to this one", reachable(neighbours = setOf(OTHER), spool = setOf(OTHER)))
    }

    @Test
    fun aLinkASightingOrASharedRelayReaches() {
        assertTrue(reachable(neighbours = setOf(PEER)))
        assertTrue(reachable(sighted = true))
        assertTrue(reachable(spool = setOf(PEER)))
    }

    @Test
    fun aPeerHeardThroughTheMeshNeedsALinkToFloodOn() {
        assertTrue(reachable(neighbours = setOf(OTHER), heard = setOf(PEER)))
        assertFalse("heard lately, but this phone has no link left to send on", reachable(heard = setOf(PEER)))
    }

    private companion object {
        const val PEER = "fd5onykecz653tm245bkza6rna"
        const val OTHER = "hzwyqdbj74hu6gkurmcyxomaca"
    }
}
