package app.getknit.knit.data.peer

import androidx.room3.Entity

/**
 * One supporter badge a peer's sealed profile carried (docs/SUPPORTER_BADGE.md §6, ADR 2026-10.r99s): the credential's
 * wrapper [wrapper] verbatim and the [r] its `sub` commits to, at [position] in the sender's display order.
 *
 * Stored as received and **verified when shown**, never only on arrival, so a badge lapses on a phone that has been
 * offline since. Kept apart from the profile's last-writer-wins row on purpose: only a sealed profile that passes the
 * version gate replaces a peer's rows (one without the field clears them), and the cleartext `profile` writer never
 * names this table, so no cleartext profile, of any version, can revert them. A table rather than a column for
 * [PeerProfileEntity]'s reason: a `ByteArray` breaks [PeerEntity]'s equality. Rows go with their pin
 * ([PeerDao.deleteOrphanBadges]) and ride a backup like `peers`.
 */
@Entity(tableName = "peer_badges", primaryKeys = ["nodeId", "position"])
data class PeerBadgeEntity(
    val nodeId: String,
    val position: Int,
    val wrapper: ByteArray,
    val r: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is PeerBadgeEntity &&
                    nodeId == other.nodeId &&
                    position == other.position &&
                    wrapper.contentEquals(other.wrapper) &&
                    r.contentEquals(other.r)
            )

    override fun hashCode(): Int = 31 * nodeId.hashCode() + position

    /** Names neither byte array: `r` is what keeps the issuer from naming the holder. */
    override fun toString(): String = "PeerBadgeEntity($nodeId, $position)"
}
