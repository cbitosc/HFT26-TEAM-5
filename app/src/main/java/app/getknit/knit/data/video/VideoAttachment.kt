package app.getknit.knit.data.video

import app.getknit.knit.mesh.protocol.VideoInfo

/** What makes an attachment a short video (ADR 2026-10.hmfe), for every surface that has to tell. */
object VideoAttachment {
    /** The longest a received description may claim: what a newer build might send, never what this one does. */
    const val MAX_CLAIMED_DURATION_MS = 10 * 60_000L

    /** The largest side a received description may claim. */
    const val MAX_CLAIMED_SIDE = 8192

    /** Whether [mime] names a video. Our own row's MIME decides, as it does for every other kind. */
    fun isVideo(mime: String?): Boolean = mime != null && mime.startsWith("video/")
}

/**
 * Whether a received video description is one a bubble can draw: a positive duration up to
 * [VideoAttachment.MAX_CLAIMED_DURATION_MS] and positive sides up to [VideoAttachment.MAX_CLAIMED_SIDE]. The sender's
 * claim, so it is bounded before it lays out anything; a poster over [VideoInfo.MAX_POSTER_BYTES] is dropped where
 * the row is built. JVM-tested in `VideoAttachmentTest`.
 */
fun VideoInfo.isPlausible(): Boolean =
    durationMs in 1..VideoAttachment.MAX_CLAIMED_DURATION_MS &&
        width in 1..VideoAttachment.MAX_CLAIMED_SIDE &&
        height in 1..VideoAttachment.MAX_CLAIMED_SIDE
