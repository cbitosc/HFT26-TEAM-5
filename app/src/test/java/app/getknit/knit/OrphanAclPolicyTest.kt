package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.OrphanAclPolicy
import app.getknit.knit.mesh.bluetooth.OrphanAclPolicy.Release
import app.getknit.knit.mesh.bluetooth.OrphanAclPolicy.Report
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [OrphanAclPolicy]: which LE connections a closed link left up, and how each is let go and reported (#142). */
class OrphanAclPolicyTest {
    private val policy = OrphanAclPolicy<String>()
    private val grace = OrphanAclPolicy.GRACE_MS
    private val addr = "AA:BB:CC:DD:EE:01"
    private val node = "n1"

    private fun check(
        now: Long,
        busy: Set<String> = emptySet(),
        armOn: Boolean = true,
    ) = policy.check(now, busy, armOn)

    /** A link on [address] whose ACL came up at 0 and ended at [endedAt]. */
    private fun linkEnded(
        endedAt: Long = 1_000,
        address: String = addr,
        armed: Boolean? = true,
    ) {
        policy.onAcl(address, up = true, now = 0)
        policy.onLinkUp(address)
        armed?.let { policy.onArmed(address, it) }
        policy.onLinkEnded(address, node, "device-$address", endedAt)
    }

    @Test
    fun anAclThatDropsWithItsLinkIsNeverAnOrphan() {
        linkEnded(endedAt = 1_000)
        policy.onAcl(addr, up = false, now = 3_000) // the arm's 1–4 s

        assertEquals(emptyList<Any>(), check(1_000 + grace + 60_000))
        assertEquals(0, policy.watching)
        assertEquals(0, policy.reported)
    }

    @Test
    fun aDisconnectBeforeTheLinkEndsLeavesNothingToWatch() {
        policy.onAcl(addr, up = true, now = 0)
        policy.onAcl(addr, up = false, now = 500) // the ACL went first: a supervision timeout, the link's EOF after
        policy.onLinkEnded(addr, node, "device", 1_000)

        assertEquals(0, policy.watching)
        assertEquals(emptyList<Any>(), check(1_000 + grace * 10))
    }

    @Test
    fun anAddressWhoseConnectEdgeWasNeverSeenIsNeverFlagged() {
        policy.onLinkEnded(addr, node, "device", 1_000)

        assertEquals(0, policy.watching)
        assertEquals(emptyList<Any>(), check(1_000 + grace * 10))
    }

    @Test
    fun anAclStillUpPastTheGraceIsReleased() {
        linkEnded(endedAt = 1_000)

        assertEquals(emptyList<Any>(), check(1_000 + grace - 1))
        val actions = check(1_000 + grace)

        assertEquals(listOf(Release(addr, node, "device-$addr", tries = 1, endedAgoMs = grace)), actions)
        assertTrue(policy.aclUp(addr))
    }

    @Test
    fun aReleaseThatClearsTheAclIsReportedOnceAsCleared() {
        linkEnded(endedAt = 1_000)
        check(61_000) // release 1
        policy.onAcl(addr, up = false, now = 63_000)

        assertEquals(emptyList<Any>(), check(63_000 + grace - 1)) // down, but not yet for a grace: a re-dial may follow
        val actions = check(121_000)

        assertEquals(listOf(Report(addr, node, linked = true, armed = true, tries = 1, cleared = true, ageMs = 62_000)), actions)
        assertEquals(0, policy.watching)
        assertEquals(emptyList<Any>(), check(181_000))
    }

    @Test
    fun twoReleasesThenExactlyOneReport() {
        linkEnded(endedAt = 1_000, armed = false)

        assertEquals(1, (check(61_000).single() as Release<*>).tries)
        assertEquals(2, (check(121_000).single() as Release<*>).tries)
        assertEquals(listOf(Report(addr, node, linked = true, armed = false, tries = 2, cleared = false, ageMs = 180_000)), check(181_000))
        assertEquals(emptyList<Any>(), check(241_000))
        assertEquals(emptyList<Any>(), check(3_600_000))

        // It finally goes: no second report.
        policy.onAcl(addr, up = false, now = 3_700_000)
        assertEquals(emptyList<Any>(), check(3_700_000 + grace))
        assertEquals(2, policy.released)
        assertEquals(1, policy.reported)
        assertEquals(0, policy.watching)
    }

    @Test
    fun androidTwelvesAcceptListRedialEndsTheReleasesAfterTheOneItUndid() {
        linkEnded(endedAt = 1_000)
        check(61_000) // release 1
        policy.onAcl(addr, up = false, now = 62_000)
        policy.onAcl(addr, up = true, now = 64_500) // the stack re-dials the dropped peer: no channel on it

        assertEquals(emptyList<Any>(), check(64_500 + grace - 1))
        // A second release would only invite the next re-dial: it is reported as kept instead.
        val report = check(121_000).single() as Report
        assertEquals(1, report.tries)
        assertFalse(report.cleared)
        assertEquals(emptyList<Any>(), check(181_000))
        assertEquals(1, policy.released)
    }

    @Test
    fun anAclThatDroppedAndCameBackWithinTheGraceGetsOneTry() {
        linkEnded(endedAt = 1_000)
        policy.onAcl(addr, up = false, now = 3_000)
        policy.onAcl(addr, up = true, now = 5_000) // re-made before any round could forget it

        assertEquals(1, (check(5_000 + grace).single() as Release<*>).tries)
        assertEquals(1, (check(80_000).single() as Report).tries)
    }

    @Test
    fun anAclThatCameBackAfterTheGraceStillGetsTwoTries() {
        linkEnded(endedAt = 1_000)
        check(61_000) // release 1
        policy.onAcl(addr, up = false, now = 62_000)
        policy.onAcl(addr, up = true, now = 62_000 + grace) // not the accept list's 2–3 s re-dial

        assertEquals(2, (check(140_000).single() as Release<*>).tries)
    }

    @Test
    fun aChannelThatNeverBecameALinkIsWatchedAndReportedAsSuch() {
        policy.onAcl(addr, up = true, now = 0)
        policy.onLinkEnded(addr, node, "device", 1_000, linked = false) // a refused dialer
        policy.onArming(addr)
        policy.onArmed(addr, attached = false)

        assertEquals(
            listOf(Report(addr, node, linked = false, armed = false, tries = 0, cleared = false, ageMs = 60_000)),
            check(61_000, armOn = false),
        )
    }

    @Test
    fun atStopAnUpAclWhoseArmNeverAttachedIsHandedBackOnce() {
        val pending = "AA:BB:CC:DD:EE:02"
        val attached = "AA:BB:CC:DD:EE:03"
        val down = "AA:BB:CC:DD:EE:04"
        linkEnded(endedAt = 1_000, armed = false)
        linkEnded(endedAt = 1_000, address = attached, armed = true)
        linkEnded(endedAt = 1_000, address = down, armed = false)
        policy.onAcl(down, up = false, now = 2_000)
        policy.onAcl(pending, up = true, now = 0)
        policy.onLinkUp(pending)
        policy.onArming(pending) // a link-up arm still running: it does its own job
        policy.onLinkEnded(pending, node, "device-$pending", 1_000)

        val unarmed = policy.stop(5_000)

        assertEquals(listOf(OrphanAclPolicy.Unarmed(addr, node, "device-$addr", linked = true, endedAgoMs = 4_000)), unarmed)
        assertEquals(1, policy.reported)
        assertEquals(0, policy.watching)
        assertFalse(policy.aclUp(addr))
    }

    @Test
    fun aDoorbellAttachMarksTheLinkArmed() {
        linkEnded(endedAt = 1_000, armed = null)
        policy.onArmed(addr, attached = true) // the doorbell lookup's attach, reported after the link's end

        assertTrue((check(61_000, armOn = false).single() as Report).armed)
        assertEquals(emptyList<OrphanAclPolicy.Unarmed<String>>(), policy.stop(62_000))
    }

    @Test
    fun aRelinkForgetsTheEntry() {
        linkEnded(endedAt = 1_000)
        policy.onLinkUp(addr)

        assertEquals(0, policy.watching)
        assertEquals(emptyList<Any>(), check(1_000 + grace * 10))
    }

    @Test
    fun aBusyAddressIsLeftAloneThatRound() {
        linkEnded(endedAt = 1_000)

        assertEquals(emptyList<Any>(), check(61_000, busy = setOf(addr))) // a dial in flight on it
        assertEquals(1, (check(121_000).single() as Release<*>).tries)
    }

    @Test
    fun withTheArmOffNothingIsReleasedAndTheOrphanIsReportedWithNoTries() {
        linkEnded(endedAt = 1_000, armed = null)

        assertEquals(
            listOf(Report(addr, node, linked = true, armed = false, tries = 0, cleared = false, ageMs = 60_000)),
            check(61_000, armOn = false),
        )
        assertEquals(emptyList<Any>(), check(121_000, armOn = false))
        assertEquals(0, policy.released)
    }

    @Test
    fun anOrphanThatClearsOnItsOwnAfterTheGraceIsReportedWithNoTries() {
        linkEnded(endedAt = 1_000)
        policy.onAcl(addr, up = false, now = 40_000) // the peer let it go, after the grace, before any round

        assertEquals(listOf(Report(addr, node, linked = true, armed = true, tries = 0, cleared = true, ageMs = 39_000)), check(61_000))
    }

    @Test
    fun anArmThatEndsAfterTheLinkStillMarksIt() {
        linkEnded(endedAt = 1_000, armed = null)
        policy.onArmed(addr, attached = true)

        val report = check(61_000, armOn = false).single() as Report
        assertTrue(report.armed)
    }

    @Test
    fun atMostSixteenAddressesAreWatchedAndTheOldestGoesFirst() {
        val addresses = (0 until OrphanAclPolicy.MAX_ENTRIES + 1).map { "AA:BB:CC:DD:EE:%02X".format(it) }
        addresses.forEachIndexed { i, a -> linkEnded(endedAt = 1_000L + i, address = a) }

        assertEquals(OrphanAclPolicy.MAX_ENTRIES, policy.watching)
        val released = check(1_000_000).map { it.address }
        assertEquals(addresses.drop(1), released)
    }

    @Test
    fun clearForgetsEveryEdgeAndEveryEntry() {
        linkEnded(endedAt = 1_000)
        policy.clear()

        assertFalse(policy.aclUp(addr))
        assertEquals(0, policy.watching)
        assertEquals(emptyList<Any>(), check(1_000_000))
    }
}
