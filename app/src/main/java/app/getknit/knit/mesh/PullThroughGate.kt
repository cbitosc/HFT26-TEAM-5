package app.getknit.knit.mesh

import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.data.message.AttachmentFetchRow

/**
 * Until when a want of ours may ask its neighbours to pull a blob through, carried on every ask as
 * `BlobReqContent.until` (work item #195, ADR 2026-10.2drc). For a DM's attachment, a custody window from the DM's
 * `sentAt`. A pull never needed a carrier: a phone in the middle fetches by hash from whoever answers HAVE, so this
 * deadline costs reach. Past [ForwardRepository.DEFAULT_TTL_MS] a DM's far end two or more hops away — an author
 * re-wanting its own picture after a wipe, a frame that arrived late, a recipient out of reach for a day — is no longer
 * pulled from, and is reached again once the two ends link directly. The bound is the DM's own: the mesh stops carrying
 * the frame itself at 24 h from its `sentAt` on every node (ADR 006). Without it the pull bought every neighbour a want
 * of its own, every 6 min, for good: the Pixel 9's card `92134a58…`, echoed by a Pixel 7 and a Moto G that held no row
 * and no custody row for it.
 *
 * Folded over the rows the way `BluetoothFetchGate` folds them (ADR 2026-10.js2p): a deadline only when *every* row
 * naming the hash is a DM's, and then the latest of them. A group's or a room's row means none — those frames are
 * carried by members and the room — and one stranger's old DM naming a hash cannot cut a contact's fetch short. Our own
 * DM rows count like anyone's. A function of the rows and the frame-global clock only.
 */
object PullThroughGate {
    /** The deadline for pulling the attachment [rows] name through, or null for none: no row, nothing to judge. */
    fun until(rows: List<AttachmentFetchRow>): Long? =
        if (rows.isNotEmpty() && rows.all { it.dm }) rows.maxOf { it.sentAt } + ForwardRepository.DEFAULT_TTL_MS else null
}
