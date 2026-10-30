package app.getknit.knit.data.reaction

import androidx.room3.Embedded
import app.getknit.knit.data.message.MessageEntity

/**
 * A thread's newest live reaction to words this device wrote, with the message it targets — the chat list's
 * "Reacted 👍 to “…”" line ([ReactionDao.observeNewestOnOwnMessages], ADR 2026-10.59fu). [reactedAt] is the
 * reaction's stored last-writer-wins clock (`ReactionEntity.updatedAt`: the reactor's clock, bounded on the
 * way in), so it sorts against the thread's messages on the same sender-clock footing they sort on each other.
 */
data class ReactionHead(
    @Embedded val target: MessageEntity,
    val reactorNodeId: String,
    val emoji: String,
    val reactedAt: Long,
)
