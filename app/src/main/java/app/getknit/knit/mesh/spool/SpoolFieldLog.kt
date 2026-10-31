package app.getknit.knit.mesh.spool

import app.getknit.knit.fieldlog.FieldEvent

/**
 * What a spool said, or what this phone concluded about it, as the field log spells it (ADR 2026-10.a239): the words
 * [SpoolStatus.lastError] holds — an `err` code (`SpoolErrCode`), a dial verdict, a close or a refused upgrade —
 * folded onto constants, since the log takes no string. [of] is the one place the words are read.
 */
enum class SpoolVerdict {
    /** Nothing was said: the socket went away with no close and no fault of ours. */
    NONE,

    // The client's own verdicts (ScopeSync, SpoolConnection).
    UNREACHABLE,
    NO_HELLO,
    UNRESPONSIVE,
    OVERLONG_LISTING,

    /** A close frame; its code rides beside it. */
    CLOSED,

    /** The upgrade was refused with an HTTP status, beside it. */
    HTTP,

    /** The socket died on an exception the dialer names by class: a transport failure with a response. */
    TRANSPORT,

    // The `err` codes (spec §8, SpoolErrCode).
    VERSION,
    POW,
    TOMBSTONED,
    QUOTA,
    TOO_LARGE,
    BAD_ID,
    RATE,
    NOT_SUBSCRIBED,
    MALFORMED,
    INTERNAL,
    CONFLICT,
    TICKET,

    /** An `err` code this build does not know (the registry is append-only). */
    OTHER,
    ;

    companion object {
        private val ERR_CODES =
            mapOf(
                SpoolErrCode.VERSION to VERSION,
                SpoolErrCode.POW to POW,
                SpoolErrCode.TOMBSTONED to TOMBSTONED,
                SpoolErrCode.QUOTA to QUOTA,
                SpoolErrCode.TOO_LARGE to TOO_LARGE,
                SpoolErrCode.BAD_ID to BAD_ID,
                SpoolErrCode.RATE to RATE,
                SpoolErrCode.NOT_SUBSCRIBED to NOT_SUBSCRIBED,
                SpoolErrCode.MALFORMED to MALFORMED,
                SpoolErrCode.INTERNAL to INTERNAL,
                SpoolErrCode.CONFLICT to CONFLICT,
                SpoolErrCode.TICKET to TICKET,
                ScopeSync.UNREACHABLE to UNREACHABLE,
                ScopeSync.NO_HELLO to NO_HELLO,
                ScopeSync.OVERLONG_LISTING to OVERLONG_LISTING,
                SpoolConnection.UNRESPONSIVE to UNRESPONSIVE,
            )

        /**
         * [word] as a verdict and the number it carries: a close's code (`close 4001 …`) or an HTTP status
         * (`http 429`), from `OkHttpSpoolDialer`'s spellings. A throwable's class name is [TRANSPORT].
         */
        fun of(word: String?): Pair<SpoolVerdict, Int?> {
            if (word == null) return NONE to null
            ERR_CODES[word]?.let { return it to null }
            val parts = word.split(' ')
            return when {
                parts[0] == "close" -> CLOSED to parts.getOrNull(1)?.toIntOrNull()
                parts[0] == "http" -> HTTP to parts.getOrNull(1)?.toIntOrNull()
                word.first().isUpperCase() -> TRANSPORT to null
                else -> OTHER to null
            }
        }
    }
}

/** Where a connection stands with a ticket, as the field log spells [SpoolTicketState]. */
enum class SpoolTicketWord { NONE, PENDING, ACCEPTED, REFUSED }

/** Whether a connection takes this phone's uploads, as the field log spells [SpoolUploads]. */
enum class SpoolUploadsWord { OPEN, LOCKED, SPENT }

/** The field log's sample of one spool (`FieldEvent.Spool`): counts and constants only, never a scope or its label. */
fun SpoolStatus.fieldSample(): FieldEvent.Spool =
    FieldEvent.Spool(
        relay = SpoolUrl.redact(url),
        connected = connected,
        scopes = scopes.size,
        converged = scopes.count { it.converged },
        local = scopes.sumOf { it.localCount },
        remote = scopes.sumOf { it.spoolCount },
        invalid = scopes.sumOf { it.invalidCount },
        fails = dialFailures,
        powBits = powBits,
        ticket = if (connected && ent) ticket.word() else null,
        uploads = if (connected) uploads.word() else null,
        reason = lastError?.let { SpoolVerdict.of(it).first },
    )

private fun SpoolTicketState.word() =
    when (this) {
        SpoolTicketState.None -> SpoolTicketWord.NONE
        SpoolTicketState.Pending -> SpoolTicketWord.PENDING
        is SpoolTicketState.Accepted -> SpoolTicketWord.ACCEPTED
        is SpoolTicketState.Refused -> SpoolTicketWord.REFUSED
    }

private fun SpoolUploads.word() =
    when (this) {
        SpoolUploads.Open -> SpoolUploadsWord.OPEN
        is SpoolUploads.Locked -> SpoolUploadsWord.LOCKED
        SpoolUploads.Spent -> SpoolUploadsWord.SPENT
    }
