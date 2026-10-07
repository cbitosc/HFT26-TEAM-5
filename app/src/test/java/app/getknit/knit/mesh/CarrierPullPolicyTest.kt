package app.getknit.knit.mesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 2026-10.g24x: a carrier holds back a DM's picture while its recipient is linked, for a bounded while. */
class CarrierPullPolicyTest {
    private val linked = setOf("bob")

    private fun dm(
        to: String?,
        at: Long = 0L,
    ) = CarriedAttachment("h", to, at)

    @Test
    fun aDmToALinkedRecipientWaits() {
        assertTrue(CarrierPullPolicy.defers(listOf(dm("bob")), linked, now = 0L))
        assertTrue(CarrierPullPolicy.defers(listOf(dm("bob"), dm("bob", at = 5L)), linked, now = 10L))
    }

    @Test
    fun aRecipientNotLinkedOrAGroupOrRoomFramePullsAtOnce() {
        assertFalse(CarrierPullPolicy.defers(listOf(dm("carol")), linked, now = 0L))
        assertFalse(CarrierPullPolicy.defers(listOf(dm(null)), linked, now = 0L))
        assertFalse("one frame that is not held back is enough", CarrierPullPolicy.defers(listOf(dm("bob"), dm(null)), linked, now = 0L))
        assertFalse(CarrierPullPolicy.defers(emptyList(), linked, now = 0L))
    }

    @Test
    fun theHoldEndsOnceTheWindowHasPassed() {
        val window = CarrierPullPolicy.DEFER_MS
        assertTrue(CarrierPullPolicy.defers(listOf(dm("bob", at = 1_000L)), linked, now = 1_000L + window - 1))
        assertFalse(CarrierPullPolicy.defers(listOf(dm("bob", at = 1_000L)), linked, now = 1_000L + window))
    }
}
