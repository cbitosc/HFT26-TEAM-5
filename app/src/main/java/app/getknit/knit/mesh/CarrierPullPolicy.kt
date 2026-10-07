package app.getknit.knit.mesh

/**
 * Whether a carrier holds back the pull of an attachment it custodies (ADR 2026-10.g24x). A carrier pulls the blob a
 * carried chat frame names so a late joiner can fetch it after the author has gone (`InboundPipeline.onCarriedFrame`).
 * But when the frame is a DM whose recipient is linked to us, the recipient is a neighbour fetching it right now, and
 * every carrier pulling beside it is one more copy the author feeds at once: on 2026-10-06 three carriers took three
 * quarters of the author's Bluetooth budget while the recipient waited about 6.5 min for a 394 KB photo.
 *
 * So a pull waits while **every** frame naming the blob is a DM (a group or room frame has no one recipient, and every
 * member pulls it anyway), its recipient is linked to us, and it was carried less than [DEFER_MS] ago. A delay, never a
 * veto: once the recipient's link goes, or [DEFER_MS] has passed, the database re-arm (`MeshManager.rewantMissingBlobs`,
 * every link-up and 60 s tick) pulls it as before — by then the recipient holds it, answers the holder query, and is
 * as good a source as the author. A cleartext receipt purges the frame, and with it the want, sooner; a sealed one is
 * opaque to a carrier and purges nothing. A recipient linked to us but not to the author still gets it at once: its
 * own ask reaches us, and `BlobExchange.onRequest` pulls it through on its behalf, which this never holds back.
 */
internal object CarrierPullPolicy {
    // Long enough for the recipient to have fetched the picture over the link it shares with us — a 2 MB one at the
    // 4 KiB/s base pace takes about 8 min — before we take our own copy; short enough that a carrier still holds it
    // well inside the frame's lifetime for a late joiner.
    const val DEFER_MS = 10 * 60_000L

    /** Whether the pull of the blob [frames] name waits, with [linked] the neighbours we hold a live link to, at [now]. */
    fun defers(
        frames: Collection<CarriedAttachment>,
        linked: Set<String>,
        now: Long,
    ): Boolean =
        frames.isNotEmpty() &&
            frames.all { f -> f.recipientId != null && f.recipientId in linked && now - f.carriedAt < DEFER_MS }
}
