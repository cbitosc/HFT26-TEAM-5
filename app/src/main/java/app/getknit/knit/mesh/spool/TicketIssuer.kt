package app.getknit.knit.mesh.spool

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The HTTP seam under [TicketRefresher] (experimental, docs/VOICE_CALLS.md §2.6, ADR 2026-10.3sk7): one `POST` that
 * trades a customer key for the day's ticket, the key swap coordinator ADR 2026-10.ukvm built. The one implementation
 * that speaks OkHttp (`OkHttpTicketIssuer`) binds its socket to the validated Internet network and enforces the
 * timeouts, the no-redirect rule and the body cap; everything above it runs against a fake.
 */
interface TicketIssuer {
    /**
     * Asks [origin] (already accepted by [IssuerOrigin]) for a ticket with [key] in the `Authorization` header and
     * nothing in the URL or the body. Never throws.
     */
    suspend fun swap(
        origin: String,
        key: String,
    ): IssuerAnswer
}

/** What the issuer answered (C-7.6-28). Nothing here carries the key, and only [Issued] carries a secret. */
sealed interface IssuerAnswer {
    /**
     * 200: [ticket] is the `knit-ticket:v1:` text, still to go through [SpoolTickets.import]'s host rule; [endsAt] is the
     * account's end, epoch millis. The JSON's `aud`/`nbf`/`exp`/`flags` copies are not read: the app acts on the claims
     * inside the ticket. A plain class whose [toString] names neither.
     */
    class Issued(
        val ticket: String,
        val endsAt: Long,
    ) : IssuerAnswer {
        override fun toString(): String = "Issued(endsAt=$endsAt)"
    }

    /** 401 `unknown-key`: the key is absent, malformed or no account's. */
    data object UnknownKey : IssuerAnswer

    /** 403 `ended`: the account has ended or been revoked, at [endsAt] (epoch millis) when the answer said. */
    data class Ended(
        val endsAt: Long?,
    ) : IssuerAnswer

    /**
     * 503 `unavailable`, which only a free key meets (docs/VOICE_CALLS.md §2.6): its audience's free tier is off, or the
     * day's cap is reached and it has not been ticketed today — then with [retryAfterMs], to the next UTC midnight. No
     * verdict on the key.
     */
    data class Unavailable(
        val retryAfterMs: Long?,
    ) : IssuerAnswer

    /**
     * Anything else — no route, a timeout, another status, a redirect, a body that does not parse: no verdict on the
     * key. [reason] is a short word for the log (`offline`, `timeout`, `http 502`, `unparseable`…), never the body.
     */
    data class NoVerdict(
        val reason: String,
    ) : IssuerAnswer

    companion object {
        /**
         * Reads a response: [status], the (capped) [body] bytes and a numeric `retry-after` in seconds. Pure, so the JVM
         * tests pin the coordinator's answers through it. A 401, 403 or 503 counts only with its own `error` word, since a
         * proxy or a firewall in front of the issuer answers those statuses too and they say nothing about the key.
         */
        fun read(
            status: Int,
            body: ByteArray,
            retryAfterS: Long? = null,
        ): IssuerAnswer {
            val parsed = runCatching { json.decodeFromString<Answer>(body.decodeToString()) }.getOrNull()
            return when {
                parsed == null -> NoVerdict(if (status == HTTP_OK) "unparseable" else "http $status")
                status == HTTP_OK -> issued(parsed)
                status == HTTP_UNAUTHORIZED && parsed.error == UNKNOWN_KEY -> UnknownKey
                status == HTTP_FORBIDDEN && parsed.error == ENDED -> Ended(parsed.endsAt?.let(::instant))
                status == HTTP_UNAVAILABLE && parsed.error == UNAVAILABLE -> Unavailable(retryAfterS?.takeIf { it >= 0 }?.times(MS_PER_S))
                else -> NoVerdict("http $status")
            }
        }

        private fun issued(parsed: Answer): IssuerAnswer {
            val ticket = parsed.ticket?.takeIf { it.startsWith(HeldTicket.PREFIX) } ?: return NoVerdict("unparseable")
            val endsAt = parsed.endsAt?.let(::instant) ?: return NoVerdict("unparseable")
            return Issued(ticket, endsAt)
        }

        private fun instant(text: String): Long? =
            try {
                Instant.parse(text).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }

        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_UNAVAILABLE = 503
        private const val UNKNOWN_KEY = "unknown-key"
        private const val ENDED = "ended"
        private const val UNAVAILABLE = "unavailable"
        private const val MS_PER_S = 1_000L

        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** Every field any of the issuer's three answers carries; what a given status needs is checked in [IssuerAnswer.read]. */
@Serializable
private class Answer(
    val ticket: String? = null,
    val endsAt: String? = null,
    val error: String? = null,
)
