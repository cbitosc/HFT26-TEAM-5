package app.getknit.knit.data.video

import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.videoInfo
import app.getknit.knit.data.message.withVideo
import app.getknit.knit.mesh.protocol.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A received video description is a claim: bounded before it lays anything out, and its poster capped. */
class VideoAttachmentTest {
    private fun info(
        durationMs: Long = 30_000,
        width: Int = 480,
        height: Int = 854,
        poster: ByteArray = ByteArray(10),
    ) = VideoInfo(durationMs, width, height, poster)

    @Test
    fun anOrdinaryDescriptionIsPlausibleAndAnAbsurdOneIsNot() {
        assertTrue(info().isPlausible())
        assertFalse(info(durationMs = 0).isPlausible())
        assertFalse(info(durationMs = 3_600_000).isPlausible())
        assertFalse(info(width = 0).isPlausible())
        assertFalse(info(height = 100_000).isPlausible())
    }

    @Test
    fun onlyAVideoMimeIsAVideo() {
        assertTrue(VideoAttachment.isVideo("video/mp4"))
        assertFalse(VideoAttachment.isVideo("image/jpeg"))
        assertFalse(VideoAttachment.isVideo(null))
    }

    @Test
    fun aRowKeepsTheDescriptionAndDropsAnOversizedPoster() {
        val row = MessageEntity(id = "m", senderId = "s", body = "", sentAt = 1)
        val kept = row.withVideo(info(poster = ByteArray(100) { it.toByte() }))
        assertEquals(info(poster = ByteArray(100) { it.toByte() }), kept.videoInfo())
        val big = row.withVideo(info(poster = ByteArray(VideoInfo.MAX_POSTER_BYTES + 1)))
        assertNull(big.videoPoster)
        assertEquals(0, checkNotNull(big.videoInfo()).poster.size)
        assertNull(row.withVideo(null).videoInfo())
    }
}
