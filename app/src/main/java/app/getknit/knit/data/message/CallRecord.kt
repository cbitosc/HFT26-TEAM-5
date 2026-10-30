package app.getknit.knit.data.message

import app.getknit.knit.mesh.protocol.CallPayload
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a call came to, as the thread's line for it says it. Derived from a [CallRecord]'s facts at render time,
 * never stored, so the wording can change without touching a row.
 */
enum class CallOutcome {
    /** It connected; the line carries how long it lasted. */
    Completed,

    /** It connected, then its path was lost and did not come back within the reconnect window (§3.8). */
    Dropped,

    /** Ours, and the peer was in another call. */
    Busy,

    /** Ours, and the peer turned it down. */
    Declined,

    /** Ours, and it rang out unanswered. */
    NoAnswer,

    /** Ours, withdrawn by hand before an answer. */
    Cancelled,

    /** Theirs, rung out or withdrawn before we answered, or an offer that arrived too late to ring. */
    Missed,

    /** Theirs, and we turned it down. */
    DeclinedByUs,

    /** Ours, withdrawn because nothing this phone could see reached the peer (C-3.3-5). */
    Unreachable,

    /** It never connected for a reason neither person chose: no route, media refused, an error. */
    Failed,
}

/**
 * What a [MessageEntity.KIND_CALL] row's [MessageEntity.body] records about one call: who placed it and how it
 * ended. A small JSON object, the [TransferRecord] convention, so the row needs no new column (ADR 2026-10.3tee's
 * call-records amendment, docs/VOICE_CALLS.md §3.12).
 *
 * It keeps facts, not a sentence: [reason] (a `CallPayload.REASON_*`), [byPeer] (the peer's terminal phase ended
 * it, not this phone), [missed] (the manager's own C-3.4-2 verdict), [unreached] (C-3.3-5) and the two timestamps.
 * [outcome] reads them.
 * Written once, when the call ends here, and local to each phone: the peer keeps its own record of the same call
 * under the same id, and nothing about it crosses the wire. Never carries an SDP, a candidate or a TURN credential.
 */
@Serializable
data class CallRecord(
    val id: String,
    val outgoing: Boolean,
    val walkie: Boolean = false,
    val reason: Int? = null,
    val byPeer: Boolean = false,
    val missed: Boolean = false,
    val connectedAt: Long? = null,
    val endedAt: Long? = null,
    // Ours, ended no_route because nothing this phone could see reached the peer (C-3.3-5), not a failed connect.
    val unreached: Boolean = false,
) {
    fun encode(): String = json.encodeToString(this)

    val outcome: CallOutcome
        get() =
            when {
                connectedAt != null && reason == CallPayload.REASON_NO_ROUTE && !walkie -> CallOutcome.Dropped
                connectedAt != null -> CallOutcome.Completed
                missed -> CallOutcome.Missed
                !outgoing && !byPeer && reason == CallPayload.REASON_USER -> CallOutcome.DeclinedByUs
                !outgoing -> CallOutcome.Failed
                byPeer && reason == CallPayload.REASON_BUSY -> CallOutcome.Busy
                byPeer && reason == CallPayload.REASON_USER -> CallOutcome.Declined
                outgoing && unreached -> CallOutcome.Unreachable
                reason == CallPayload.REASON_TIMEOUT -> CallOutcome.NoAnswer
                !byPeer && reason == CallPayload.REASON_USER -> CallOutcome.Cancelled
                else -> CallOutcome.Failed
            }

    /** How long it was connected, or null when it never was (or its end was not seen). */
    val durationMs: Long?
        get() {
            val from = connectedAt ?: return null
            val to = endedAt ?: return null
            return (to - from).coerceAtLeast(0L)
        }

    /**
     * The chat row for this record in the DM with [peerId]: a status-notice-shaped row (`received = true`, no tick, out
     * of unread counts and search) whose sender is whoever placed the call. The id is deterministic per call, so a
     * second write for the same call (a stale offer seen twice) upserts the one row; [startedAt] is this phone's clock
     * when the call began here, so the line sits where the call happened, not where it ended.
     */
    fun toEntity(
        peerId: String,
        selfId: String,
        startedAt: Long,
    ): MessageEntity =
        MessageEntity(
            id = rowId(id),
            senderId = if (outgoing) selfId else peerId,
            recipientId = if (outgoing) peerId else selfId,
            conversationId = peerId,
            body = encode(),
            sentAt = startedAt,
            received = true,
            kind = MessageEntity.KIND_CALL,
        )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The `messages.id` of the record for call [callId]. */
        fun rowId(callId: String): String = "call:$callId"

        /** Reads [body]; null for anything that is not an encoded record (a row this build cannot read). */
        fun decode(body: String): CallRecord? = runCatching { json.decodeFromString<CallRecord>(body) }.getOrNull()
    }
}
