package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.BleFeedPolicy
import app.getknit.knit.mesh.bluetooth.LinkPhy
import app.getknit.knit.mesh.link.PaceConfig
import app.getknit.knit.mesh.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [BleFeedPolicy]: the feed window's gate, the windowed pace, the expected rate, the LE count. */
class BleFeedPolicyTest {
    private val android = Protocol.CAP_BLOB_HAVE
    private val iphone = Protocol.CAP_BLOB_HAVE or Protocol.CAP_DOORBELL

    @Test
    fun anAndroidPeerThatReportsIsWindowedOn1mAnd2m() {
        assertTrue(BleFeedPolicy.windowed(true, LinkPhy.ONE_M, android))
        assertTrue(BleFeedPolicy.windowed(true, LinkPhy.TWO_M, android))
        assertTrue("a PHY not yet read is not Coded", BleFeedPolicy.windowed(true, LinkPhy.UNKNOWN, android))
    }

    @Test
    fun theWindowNeedsTheSwitchAReportingPeerAndNotCoded() {
        assertFalse("switch off", BleFeedPolicy.windowed(false, LinkPhy.ONE_M, android))
        assertFalse("Coded keeps its own pace", BleFeedPolicy.windowed(true, LinkPhy.CODED, android))
        assertFalse("an older build never reports", BleFeedPolicy.windowed(true, LinkPhy.ONE_M, 0L))
        assertFalse("an iPhone's reports are not measured", BleFeedPolicy.windowed(true, LinkPhy.ONE_M, iphone))
    }

    @Test
    fun aWindowedPaceKeepsItsBaseAsTheFallback() {
        val base = PaceConfig(bytesPerSec = 4_096, chunkBytes = 8_192)
        assertSame(base, BleFeedPolicy.pace(base, windowed = false, queueMs = 400))
        assertEquals(base.copy(windowed = true, windowQueueMs = 400), BleFeedPolicy.pace(base, windowed = true, queueMs = 400))
    }

    private fun rate(
        codedPace: Int? = null,
        drained: Int = 0,
        windowed: Boolean = false,
        others: Int = 0,
        drainRatio: Double = 1.0,
        boosted: Int = 0,
        paced: Int = 4_096,
    ) = BleFeedPolicy.expectedRate(codedPace, drained, windowed, { others }, drainRatio, { boosted }) { paced }

    @Test
    fun aCodedLinkIsReadAtItsOwnPaceWhateverItDrained() {
        assertEquals(1_200, rate(codedPace = 1_200, drained = 30_000, windowed = true, drainRatio = 0.5))
    }

    @Test
    fun aWindowedLinkIsReadAtItsLastDrainSharedWithTheOtherWindowedFeeds() {
        assertEquals(18_000, rate(drained = 18_000, windowed = true))
        assertEquals("two others beside it", 6_000, rate(drained = 18_000, windowed = true, others = 2))
        assertEquals("the drain ratio is the paced estimate's", 18_000, rate(drained = 18_000, windowed = true, drainRatio = 0.3))
    }

    @Test
    fun beforeADrainOrPacedTheShareIsBoostedElsePacedTimesTheDrainRatio() {
        assertEquals("windowed, nothing measured yet", 2_048, rate(windowed = true, paced = 4_096, drainRatio = 0.5))
        assertEquals("a paced link ignores a drain", 4_096, rate(drained = 30_000, paced = 4_096))
        assertEquals("boosted", 9_216, rate(boosted = 18_432, drainRatio = 0.5))
        assertEquals("not boosted", 3_072, rate(boosted = 0, paced = 4_096, drainRatio = 0.75))
    }

    @Test
    fun theSharesAreReadOnlyWhenTheAnswerNeedsThem() {
        // The boosted share counts the phone's LE connections, a binder call.
        var reads = 0
        BleFeedPolicy.expectedRate(1_200, 0, false, { reads++ }, 1.0, { reads++ }) { reads++ }
        BleFeedPolicy.expectedRate(null, 18_000, true, { 0 }, 1.0, { reads++ }) { reads++ }
        assertEquals("Coded and a measured window read no share", 0, reads)
        BleFeedPolicy.expectedRate(null, 0, false, { reads++ }, 1.0, { 18_432 }) { reads++ }
        assertEquals("boosted: the paced share is not read", 0, reads)
    }

    @Test
    fun theLeCountAddsEveryOtherGattConnectionOnceAndNoMeshLinkTwice() {
        assertEquals(2, BleFeedPolicy.leLinks(2, emptyList(), setOf("A", "B")))
        assertEquals("a ring and a watch", 4, BleFeedPolicy.leLinks(2, listOf("RING", "WATCH"), setOf("A", "B")))
        assertEquals("a tuned mesh link is already counted", 3, BleFeedPolicy.leLinks(2, listOf("A", "RING"), setOf("A", "B")))
    }

    @Test
    fun aFeedCanEarnTheBoostOnlyWithEveryGateOpen() {
        fun can(
            flag: Boolean = true,
            caps: Long = android,
            on: Boolean = true,
            tuner: Boolean = true,
            audio: Boolean = false,
        ) = BleFeedPolicy.canEarn(flag, caps, on, tuner, audio)
        assertTrue(can())
        assertFalse("the build flag off", can(flag = false))
        assertFalse("an iPhone: its doorbell owns the parameters", can(caps = iphone))
        assertFalse("the trial's switch off", can(on = false))
        assertFalse("the tuner gave up", can(tuner = false))
        assertFalse("audio takes the air", can(audio = true))
    }

    @Test
    fun aFeedWillEarnOnlyWhenSomeoneAsksForTheInterval() {
        assertFalse("it cannot", BleFeedPolicy.willEarn(false, earned = true, earnedRecently = { true }, interactive = true))
        assertTrue("earned now", BleFeedPolicy.willEarn(true, earned = true, earnedRecently = { false }, interactive = false))
        assertTrue("a lit receiver asked lately", BleFeedPolicy.willEarn(true, false, earnedRecently = { true }, interactive = false))
        assertTrue("this side is lit and asks", BleFeedPolicy.willEarn(true, false, earnedRecently = { false }, interactive = true))
        assertFalse("a dark pair never asks", BleFeedPolicy.willEarn(true, false, earnedRecently = { false }, interactive = false))
    }
}
