package app.getknit.knit.mesh

import app.getknit.knit.data.message.AttachmentFetchRow

/**
 * Whether a received attachment waits for the user's tap before its fetch may ride Bluetooth (ADR 2026-10.js2p).
 * An attachment the sender declared larger than the user's limit is fetched on its own only over Wi-Fi Aware; over
 * Bluetooth alone, at its few KiB/s, it waits for "Tap to download". The one rule the fetch (`MeshManager`,
 * `InboundPipeline`) and the chat bubble (`ChatViewModel`) both ask. The bubble asks it of its own row, and shows the
 * card on a gated one; the fetch folds it over every row naming the hash ([fastOnly] of the rows), so another row — our
 * own, a tapped one, one under the limit or declaring nothing — can fetch it while a gated row shows the card, which
 * then gives way to the progress ring once bytes arrive.
 *
 * Judged on the sender's declared size only — a label, never a bound — so an attachment with none (an older sender,
 * an ordinary photo under [DECLARE_SIZE_FLOOR]) fetches as it always did. Our own attachments never wait.
 */
object BluetoothFetchGate {
    /**
     * The size at and above which a sender declares a photo's or voice note's length (a named file always declares
     * its own). The smallest limit the user can pick, so every attachment a limit could gate carries its size; below
     * it an ordinary photo frame stays byte-for-byte what it was before ADR 2026-10.js2p.
     */
    const val DECLARE_SIZE_FLOOR = 1_000_000L

    /**
     * Whether a fetch of an attachment of [declaredSize] waits for a tap before it may ride Bluetooth, under the
     * user's [limitBytes] (null: never ask). [approved] is the tap, kept on the message row; [own] is our own send.
     */
    fun fastOnly(
        declaredSize: Long?,
        limitBytes: Long?,
        approved: Boolean,
        own: Boolean,
    ): Boolean = !own && !approved && limitBytes != null && declaredSize != null && declaredSize > limitBytes

    /**
     * Whether the fetch of an attachment that [rows] name waits for a tap: only when *every* row does ([fastOnly] per
     * row). One row that would fetch it — ours, tapped, under the limit, or declaring no size — fetches it for all, so
     * a stranger's row naming a contact's attachment with an inflated size can never hold the contact's back (a row
     * declaring nothing shows no card to tap). No rows, nothing to judge: plain.
     */
    fun fastOnly(
        rows: List<AttachmentFetchRow>,
        limitBytes: Long?,
    ): Boolean = rows.isNotEmpty() && rows.all { fastOnly(it.attachmentSize, limitBytes, it.approved, it.own) }

    /** Whether the sender declares the length of an attachment of [sizeBytes] — always for a [named] file. */
    fun declaresSize(
        sizeBytes: Long,
        named: Boolean,
    ): Boolean = named || sizeBytes >= DECLARE_SIZE_FLOOR
}
