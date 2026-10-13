package app.getknit.knit.call

import app.getknit.knit.call.CallAudioRoute.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The route rules both call kinds share (docs/VOICE_CALLS.md §3.13): where a walkie starts and goes back to, and the
 * notification's target.
 */
class CallAudioPolicyTest {
    private val earpiece = CallAudioRoute(Kind.EARPIECE, "Earpiece", "e")
    private val speaker = CallAudioRoute(Kind.SPEAKER, "Speaker", "s")
    private val buds = CallAudioRoute(Kind.BLUETOOTH, "Pixel Buds", "b")
    private val wired = CallAudioRoute(Kind.WIRED, "Wired headset", "w")

    @Test
    fun aWalkieStartsOnTheLoudspeakerWhereALiveCallLeavesItToTheSystem() {
        assertEquals(speaker, CallAudioPolicy.startRoute(walkie = true, available = listOf(earpiece, speaker)))
        assertNull(CallAudioPolicy.startRoute(walkie = false, available = listOf(earpiece, speaker)))
    }

    @Test
    fun aConnectedHeadsetTakesAWalkieAsItWouldACall() {
        assertNull(CallAudioPolicy.startRoute(walkie = true, available = listOf(earpiece, speaker, buds)))
        assertNull(CallAudioPolicy.startRoute(walkie = true, available = listOf(earpiece, speaker, wired)))
    }

    @Test
    fun aPhoneWithNoLoudspeakerAsksForNothing() {
        assertNull(CallAudioPolicy.startRoute(walkie = true, available = listOf(earpiece)))
        assertNull(CallAudioPolicy.walkieCorrection(CallRouting(earpiece, listOf(earpiece)), askedFor = null))
    }

    @Test
    fun aWalkieThatFallsToTheEarpieceUnaskedGoesBackToTheLoudspeaker() {
        // The headset went away and the system fell back to the earpiece, as it does for a call.
        assertEquals(speaker, CallAudioPolicy.walkieCorrection(CallRouting(earpiece, listOf(earpiece, speaker)), askedFor = buds))
        assertEquals(speaker, CallAudioPolicy.walkieCorrection(CallRouting(earpiece, listOf(earpiece, speaker)), askedFor = null))
    }

    @Test
    fun anEarpieceTheUserPickedIsKept() {
        assertNull(CallAudioPolicy.walkieCorrection(CallRouting(earpiece, listOf(earpiece, speaker)), askedFor = earpiece))
    }

    @Test
    fun onlyTheEarpieceIsCorrected() {
        assertNull(CallAudioPolicy.walkieCorrection(CallRouting(buds, listOf(earpiece, speaker, buds)), askedFor = null))
        assertNull(CallAudioPolicy.walkieCorrection(CallRouting(speaker, listOf(earpiece, speaker)), askedFor = null))
        assertNull(CallAudioPolicy.walkieCorrection(CallRouting(null, listOf(earpiece, speaker)), askedFor = null))
    }

    @Test
    fun theNotificationMovesOntoTheLoudspeakerOrOffItToTheBestOtherRoute() {
        assertEquals(speaker, CallAudioPolicy.notificationTarget(CallRouting(earpiece, listOf(earpiece, speaker))))
        assertEquals(speaker, CallAudioPolicy.notificationTarget(CallRouting(buds, listOf(earpiece, speaker, buds))))
        assertEquals(earpiece, CallAudioPolicy.notificationTarget(CallRouting(speaker, listOf(speaker, earpiece))))
        assertEquals(wired, CallAudioPolicy.notificationTarget(CallRouting(speaker, listOf(earpiece, speaker, wired))))
        assertEquals(buds, CallAudioPolicy.notificationTarget(CallRouting(speaker, listOf(earpiece, wired, speaker, buds))))
    }

    @Test
    fun theNotificationHasNowhereToGoWithoutASecondRoute() {
        assertNull(CallAudioPolicy.notificationTarget(CallRouting(speaker, listOf(speaker))))
        assertNull(CallAudioPolicy.notificationTarget(CallRouting(earpiece, listOf(earpiece))))
    }
}
