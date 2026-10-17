package app.getknit.knit.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure halves of [VideoFrames]: where frames are sampled, how big they are decoded, how the poster is fitted. */
class VideoFramesTest {
    @Test
    fun samplesSitEvenlyInsideTheClip() {
        assertEquals(listOf(5_000L, 15_000L, 25_000L), VideoFrames.sampleTimes(30_000, 3))
        assertEquals(listOf(0L), VideoFrames.sampleTimes(0, 3))
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
}
