package app.getknit.knit.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The pure halves of [VideoFrames]: where frames are sampled, how big they are decoded, which one the poster is and
 * how it is fitted.
 */
class VideoFramesTest {
    @Test
    fun samplesSitEvenlyInsideTheClip() {
        assertEquals(listOf(5_000L, 15_000L, 25_000L), VideoFrames.sampleTimes(30_000, 3))
        assertEquals(listOf(0L), VideoFrames.sampleTimes(0, 3))
    }

    @Test
    fun theScreenLooksOnceInsideEachSliceAtAnInstantNobodyCanPredict() {
        val draws = (1..50).map { seed -> VideoFrames.screenTimes(60_000, 12, Random(seed)) }
        draws.forEach { times ->
            assertEquals(12, times.size)
            times.forEachIndexed { i, t -> assertTrue("$t in slice $i", t >= i * 5_000L && t < (i + 1) * 5_000L) }
        }
        // Not the fixed instants a sender could dodge: the draws differ from each other.
        assertTrue(draws.distinct().size > 1)
        assertEquals(listOf(0L), VideoFrames.screenTimes(0, 12, Random(1)))
        assertEquals(listOf(0L), VideoFrames.screenTimes(60_000, 0, Random(1)))
        // Slices shorter than a millisecond are their own start.
        assertEquals(listOf(0L, 0L, 1L), VideoFrames.screenTimes(2, 3, Random(1)))
        // A slice's end is never drawn: a 1 ms slice can only be its start, whatever the draw.
        (1..20).forEach { seed -> assertEquals(listOf(0L, 1L, 2L), VideoFrames.screenTimes(3, 3, Random(seed))) }
    }

    @Test
    fun aFrameIsBoundedByItsLongSideAndNeverUpscaled() {
        assertEquals(144 to 256, VideoFrames.bounded(480, 854, 256))
        assertEquals(256 to 144, VideoFrames.bounded(854, 480, 256))
        assertEquals(100 to 50, VideoFrames.bounded(100, 50, 256))
    }

    @Test
    fun thePosterTakesTheFirstQualityThatFits() {
        val sizes = mapOf(70 to 9_000, 60 to 7_000, 50 to 5_500, 40 to 4_000)
        val fitted = VideoFrames.fitJpeg(6 * 1024, listOf(70, 60, 50, 40)) { ByteArray(sizes.getValue(it)) }
        assertEquals(5_500, fitted?.size)
        assertNull(VideoFrames.fitJpeg(1_000, listOf(70, 60)) { ByteArray(sizes.getValue(it)) })
    }

    @Test
    fun thePosterIsAnExposedFrameWithSomethingInItNotABlackOne() {
        // ADR 2026-10.hmfe's amendment: a fixed instant drew a black poster; the candidates are ranked instead.
        val scores =
            listOf(
                VideoFrames.posterScore(flat(4), W, H), // black
                VideoFrames.posterScore(flat(128), W, H), // a grey wall: exposed, nothing in it
                VideoFrames.posterScore(checker(70, 190), W, H), // a scene
                VideoFrames.posterScore(flat(250), W, H), // blown out
            )
        assertEquals(listOf(2, 1, 3, 0), VideoFrames.posterOrder(scores))
        assertEquals(0.0, scores[0].value, 0.0)
        assertEquals(0.0, scores[3].value, 0.0)
    }

    @Test
    fun aSharpFrameBeatsABlurredOneAndBusyDetailEarnsNoMoreThanSharp() {
        val sharp = VideoFrames.posterScore(checker(70, 190, block = 4), W, H)
        val blurred = VideoFrames.posterScore(gradient(70, 190), W, H)
        assertTrue(sharp.value > blurred.value)
        val busy = VideoFrames.posterScore(checker(70, 190, block = 1), W, H)
        assertEquals("detail is capped", sharp.detail, busy.detail, 0.0)
    }

    @Test
    fun aClipDarkAllThroughGetsItsLeastDarkFrameAndATieTheEarlierOne() {
        val dark = listOf(flat(2), flat(9), flat(5)).map { VideoFrames.posterScore(it, W, H) }
        assertEquals(1, VideoFrames.posterOrder(dark).first())
        val same = List(3) { VideoFrames.posterScore(checker(70, 190), W, H) }
        assertEquals(listOf(0, 1, 2), VideoFrames.posterOrder(same))
    }

    private fun grey(y: Int) = (0xFF shl 24) or (y shl 16) or (y shl 8) or y

    private fun flat(y: Int) = IntArray(W * H) { grey(y) }

    private fun checker(
        a: Int,
        b: Int,
        block: Int = 4,
    ) = IntArray(W * H) { i -> if ((i % W / block + i / W / block) % 2 == 0) grey(a) else grey(b) }

    /** A smooth left-to-right ramp: the same contrast budget as a scene, no edges. */
    private fun gradient(
        from: Int,
        to: Int,
    ) = IntArray(W * H) { i -> grey(from + (to - from) * (i % W) / (W - 1)) }

    private companion object {
        const val W = 64
        const val H = 36
    }
}
