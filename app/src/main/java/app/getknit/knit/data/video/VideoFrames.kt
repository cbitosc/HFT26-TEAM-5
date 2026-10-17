package app.getknit.knit.data.video

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import app.getknit.knit.data.ByteArrayMediaSource
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

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
 * carried inline in the message (ADR 2026-10.hmfe), the best-looking of a few frames across the clip ([posterScore]).
 * [sample] is what screening classifies.
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

    /** How many frames, spread across the clip, the poster is chosen from. */
    const val POSTER_CANDIDATES = 8

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

    /**
     * The poster JPEG for [bytes] — [POSTER_LONG_SIDE] on its long side, at most [POSTER_MAX_BYTES] — or null.
     *
     * Chosen, not taken at a fixed instant: [POSTER_CANDIDATES] frames spread across the clip ([sampleTimes], never
     * its very start, which is often black) are ranked by [posterOrder], and the best one whose JPEG fits is the
     * poster. A fade, a lens cap or a pocket at the instant a fixed rule picks would otherwise be every receiver's
     * picture of the video until it plays.
     */
    fun poster(bytes: ByteArray): ByteArray? {
        val meta = meta(bytes) ?: return null
        val times = sampleTimes(meta.durationMs, POSTER_CANDIDATES)
        return withRetriever(bytes) { r ->
            val frames = times.mapNotNull { r.scaledFrame(it, POSTER_LONG_SIDE, meta) }
            try {
                val scores = frames.map { frame -> posterScore(frame.argb(), frame.width, frame.height) }
                posterOrder(scores).firstNotNullOfOrNull { i ->
                    fitJpeg(POSTER_MAX_BYTES, POSTER_QUALITIES) { quality ->
                        ByteArrayOutputStream().also { frames[i].compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
                    }
                }
            } finally {
                frames.forEach(Bitmap::recycle)
            }
        }
    }

    /**
     * How a candidate frame looks as a poster, from its luma: [exposure] (0 for a black or blown-out frame, 1 across
     * the usable middle), [contrast] (the luma's standard deviation) and [detail] (the mean step between neighbouring
     * pixels, capped at [DETAIL_CAP] so a noisy or busy frame earns no more than a sharp one). [meanLuma] breaks ties.
     */
    data class PosterScore(
        val exposure: Double,
        val contrast: Double,
        val detail: Double,
        val meanLuma: Double,
    ) {
        val value: Double get() = exposure * (contrast + DETAIL_WEIGHT * detail)
    }

    /** Below this mean luma (0..255) a frame is black; it is fully exposed from [EXPOSED_FROM]. */
    private const val BLACK_BELOW = 16.0
    private const val EXPOSED_FROM = 48.0

    /** Above this mean luma a frame is blown out; it is fully exposed up to [EXPOSED_TO]. */
    private const val BLOWN_ABOVE = 240.0
    private const val EXPOSED_TO = 216.0

    /** Where [PosterScore.detail] stops counting: past it a frame is as sharp as a poster needs. */
    private const val DETAIL_CAP = 12.0

    /** Mid-grey, what [posterOrder] falls back to nearness to. */
    private const val MID_LUMA = 128.0

    /** What a step of detail is worth against a step of contrast. */
    private const val DETAIL_WEIGHT = 2.0

    /** The [PosterScore] of a frame given as [argb] pixels, [width] × [height]. Pure: `VideoFramesTest`. */
    fun posterScore(
        argb: IntArray,
        width: Int,
        height: Int,
    ): PosterScore {
        val n = width * height
        if (n <= 0 || argb.size < n) return PosterScore(0.0, 0.0, 0.0, 0.0)
        val luma = IntArray(n) { i -> luma(argb[i]) }
        val mean = luma.average()
        val variance = luma.sumOf { (it - mean) * (it - mean) } / n
        var steps = 0L
        var count = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val here = luma[y * width + x]
                if (x + 1 < width) {
                    steps += abs(here - luma[y * width + x + 1])
                    count++
                }
                if (y + 1 < height) {
                    steps += abs(here - luma[(y + 1) * width + x])
                    count++
                }
            }
        }
        val detail = if (count == 0) 0.0 else steps.toDouble() / count
        val exposure =
            ramp(mean, BLACK_BELOW, EXPOSED_FROM) * ramp(-mean, -BLOWN_ABOVE, -EXPOSED_TO)
        return PosterScore(exposure, sqrt(variance), detail.coerceAtMost(DETAIL_CAP), mean)
    }

    /**
     * The candidates' indices, best poster first: by [PosterScore.value], then the nearer to mid-grey (so a clip dark
     * or blown out all through still gets its least extreme frame), then the earlier.
     */
    fun posterOrder(scores: List<PosterScore>): List<Int> =
        scores.indices.sortedWith(
            compareByDescending<Int> { scores[it].value }.thenBy { abs(scores[it].meanLuma - MID_LUMA) }.thenBy { it },
        )

    /** 0 at or below [zero], 1 at or above [one], linear between. */
    private fun ramp(
        v: Double,
        zero: Double,
        one: Double,
    ): Double = ((v - zero) / (one - zero)).coerceIn(0.0, 1.0)

    /** Rec. 601 luma of an ARGB pixel, 0..255. */
    private fun luma(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (LUMA_R * r + LUMA_G * g + LUMA_B * b) shr LUMA_SHIFT
    }

    /** Rec. 601's luma weights in 8-bit fixed point (0.299, 0.587, 0.114 of 256). */
    private const val LUMA_R = 77
    private const val LUMA_G = 150
    private const val LUMA_B = 29
    private const val LUMA_SHIFT = 8

    private fun Bitmap.argb(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

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
