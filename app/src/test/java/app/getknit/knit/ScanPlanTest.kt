package app.getknit.knit

import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.bluetooth.BlePresenceTracker
import app.getknit.knit.mesh.bluetooth.CodedPhyPolicy
import app.getknit.knit.mesh.bluetooth.ScanPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [ScanPlan]: far peers, when Coded-only windows run and why, and the reachable set. */
class ScanPlanTest {
    private fun snap(
        nodeId: String,
        codedLagMs: Long? = null,
        caps: Long = 0L,
    ): BlePresenceTracker.Snapshot =
        BlePresenceTracker.Snapshot(
            nodeId = nodeId,
            protoVersion = 1,
            capabilities = caps,
            psm = 100,
            digestCue = 0,
            smoothedRssi = -60.0,
            dwellMs = 15_000,
            lastSeenAgoMs = 0,
            codedLagMs = codedLagMs,
        )

    @Test
    fun aFarPeerIsUnlinkedAndHeardOnCodedAlone() {
        val stale = CodedPhyPolicy.ONE_M_FRESH_MS + 1
        val snaps =
            listOf(
                snap("near"), // 1M heard
                snap("fresh", codedLagMs = CodedPhyPolicy.ONE_M_FRESH_MS), // its 1M is still fresh
                snap("far", codedLagMs = stale),
                snap("linked", codedLagMs = stale),
            )
        assertEquals(listOf("far"), ScanPlan.far(snaps, linked = setOf("linked")))
    }

    @Test
    fun codedWindowsRunOnlyWithCodedOnAndAReasonForThem() {
        assertFalse(ScanPlan.codedWindows(on = false, alone = true, far = listOf("x"), hunt = true, chased = true))
        assertFalse("linked, nothing far, no hunt, no chase", ScanPlan.codedWindows(true, false, emptyList(), false, false))
        assertTrue(ScanPlan.codedWindows(true, alone = true, far = emptyList(), hunt = false, chased = false))
        assertTrue(ScanPlan.codedWindows(true, alone = false, far = listOf("x"), hunt = false, chased = false))
        assertTrue(ScanPlan.codedWindows(true, alone = false, far = emptyList(), hunt = true, chased = false))
        assertTrue(ScanPlan.codedWindows(true, alone = false, far = emptyList(), hunt = false, chased = true))
    }

    @Test
    fun theWindowsLineNamesTheFirstReason() {
        val chase = { listOf("c1") }
        assertEquals("alone", ScanPlan.codedWindowsWhy(alone = true, hunt = true, far = listOf("x"), chasedCoded = chase))
        assertEquals("hunt", ScanPlan.codedWindowsWhy(alone = false, hunt = true, far = listOf("x"), chasedCoded = chase))
        assertEquals("codedOnly=[x]", ScanPlan.codedWindowsWhy(alone = false, hunt = false, far = listOf("x"), chasedCoded = chase))
        assertEquals("chase=[c1]", ScanPlan.codedWindowsWhy(alone = false, hunt = false, far = emptyList(), chasedCoded = chase))
    }

    @Test
    fun theChaseIsReadOnlyWhenItIsTheReason() {
        var reads = 0
        ScanPlan.codedWindowsWhy(alone = true, hunt = false, far = emptyList()) { reads++.let { emptyList() } }
        assertEquals(0, reads)
    }

    @Test
    fun reachableIsEverySightingAndEveryLinkAndALinksPeerWins() {
        val got =
            ScanPlan.reachable(
                listOf(snap("seen", caps = 1L), snap("both", caps = 1L)),
                listOf(Peer("both", 2, 2L), Peer("unsighted", 2, 2L)),
            )
        assertEquals(setOf(Peer("seen", 1, 1L), Peer("both", 2, 2L), Peer("unsighted", 2, 2L)), got)
    }
}
