package app.getknit.knit.data.video

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import app.getknit.knit.data.ByteArrayMediaSource
import java.io.ByteArrayOutputStream

/** What a held video's own bytes say about it: what the bubble and the player trust once the bytes are local. */
data class VideoMeta(
    val durationMs: Long,
    val width: Int,
    val height: Int,
)

/**
 * Frames and properties read out of an MP4 held in memory, through `MediaMetadataRetriever` over
 * [ByteArrayMediaSource] — so a video is never staged as a file to be looked at, the same rule as playback.
 *
 * Every frame is decoded through `getScaledFrameAtTime`, bounded to the size it is wanted at, never at the stream's
 * own resolution: a received video's dimensions are its sender's to choose (ADR 051's reason for images).
 *
 * [meta] is the received side's "derive, don't carry" read (`voiceDurationMs`'s rule): the sender's declared duration
 * and dimensions label the bubble before the bytes land, and these replace them after. [poster] is the sender's still,
 * carried inline in the message (ADR 2026-10.hmfe). [sample] is what screening classifies.
 */
object VideoFrames {
    private const val TAG = "VideoFrames"
    private const val US_PER_MS = 1000L

    /** The poster's long side, in pixels. */
    const val POSTER_LONG_SIDE = 256

    /** The poster's largest encoding: it rides inside every copy of the message. */
    const val POSTER_MAX_BYTES = 6 * 1024

    /** JPEG qualities tried in turn until the poster fits [POSTER_MAX_BYTES]. */
    val POSTER_QUALITIES = listOf(70, 60, 50, 40, 30)

    /** Where the poster is taken: a second in, or halfway through a shorter clip (a first frame is often black). */
    private const val POSTER_AT_MS = 1_000L

    /** The duration, upright dimensions and rotation-applied size of [bytes], or null if no video track reads. */
    fun meta(bytes: ByteArray): VideoMeta? =
        withRetriever(bytes) { r ->
            val width = r.int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: return@withRetriever null
            val height = r.int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: return@withRetriever null
            val rotation = r.int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val quarter = rotation == 90 || rotation == 270
            VideoMeta(duration, if (quarter) height else width, if (quarter) width else height)
        }

    /** The poster JPEG for [bytes] — [POSTER_LONG_SIDE] on its long side, at most [POSTER_MAX_BYTES] — or null. */
    fun poster(bytes: ByteArray): ByteArray? {
        val meta = meta(bytes) ?: return null
        val at = minOf(POSTER_AT_MS, meta.durationMs / 2)
        val frame = frameAt(bytes, at, POSTER_LONG_SIDE, meta) ?: return null
        return try {
            fitJpeg(POSTER_MAX_BYTES, POSTER_QUALITIES) { quality ->
                ByteArrayOutputStream().also { frame.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            }
        } finally {
            frame.recycle()
        }
    }

    /**
     * [count] frames spread evenly across [bytes], each at most [longSide] on its long side: what the NSFW screen
     * classifies. Fewer when a frame does not decode; empty when nothing does (the caller then fails open, as the
     * image screen does on bytes it cannot decode).
     */
    fun sample(
        bytes: ByteArray,
        count: Int,
        longSide: Int,
    ): List<Bitmap> {
        val meta = meta(bytes) ?: return emptyList()
        val times = sampleTimes(meta.durationMs, count)
        return withRetriever(bytes) { r -> times.mapNotNull { r.scaledFrame(it, longSide, meta) } }.orEmpty()
    }

    /** [count] instants evenly inside a clip of [durationMs], neither at its very start nor its very end. */
    fun sampleTimes(
        durationMs: Long,
        count: Int,
    ): List<Long> {
        if (count <= 0 || durationMs <= 0) return listOf(0L)
        return (0 until count).map { i -> durationMs * (2 * i + 1) / (2L * count) }
    }

    /**
     * The first of [encode]'s outputs, tried at each of [qualities] in turn, that fits in [maxBytes]; the last one
     * tried if none does is still too big, so null.
     */
    fun fitJpeg(
        maxBytes: Int,
        qualities: List<Int>,
        encode: (Int) -> ByteArray,
    ): ByteArray? = qualities.asSequence().map(encode).firstOrNull { it.size <= maxBytes }

    private fun frameAt(
        bytes: ByteArray,
        atMs: Long,
        longSide: Int,
        meta: VideoMeta,
    ): Bitmap? = withRetriever(bytes) { it.scaledFrame(atMs, longSide, meta) }

    private fun MediaMetadataRetriever.scaledFrame(
        atMs: Long,
        longSide: Int,
        meta: VideoMeta,
    ): Bitmap? {
        val (w, h) = bounded(meta.width, meta.height, longSide)
        return getScaledFrameAtTime(atMs * US_PER_MS, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)
    }

    /** [width] × [height] scaled so the long side is at most [longSide], aspect kept, never upscaled. */
    fun bounded(
        width: Int,
        height: Int,
        longSide: Int,
    ): Pair<Int, Int> {
        val long = maxOf(width, height)
        if (long <= longSide || long <= 0) return width.coerceAtLeast(1) to height.coerceAtLeast(1)

        fun scale(side: Int) = ((side.toLong() * longSide + long / 2) / long).toInt().coerceAtLeast(1)
        return scale(width) to scale(height)
    }

    private inline fun <T> withRetriever(
        bytes: ByteArray,
        block: (MediaMetadataRetriever) -> T?,
    ): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(ByteArrayMediaSource(bytes))
            block(retriever)
        } catch (e: IllegalArgumentException) {
            // A source the retriever cannot open (setDataSource), or a frame size it refuses.
            Log.w(TAG, "video read failed: ${e.javaClass.simpleName}")
            null
        } catch (e: IllegalStateException) {
            Log.w(TAG, "video read failed: ${e.javaClass.simpleName}")
            null
        } finally {
            retriever.release()
        }
    }

    private fun MediaMetadataRetriever.int(key: Int): Int? = extractMetadata(key)?.toIntOrNull()
}
