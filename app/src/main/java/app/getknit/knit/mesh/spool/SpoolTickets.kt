package app.getknit.knit.mesh.spool

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import java.util.Base64

/**
 * One entitlement ticket this device holds (experimental, docs/VOICE_CALLS.md §2.6, ADR 2026-10.xkp4): the bytes the
 * issuer signed ([tk]), its signature ([sig]), and the claims a client reads out of them. [text] is the paste-safe form
 * it was imported as, kept because that is what the store persists.
 *
 * All three are a bearer grant and the holder's daily pseudonym rides inside [tk], so none of them may reach a log
 * line, a frame, a backup or the peer's signaling. A plain class whose [toString] names the claims only, like
 * `TurnServer` hides its `pass`. The client never checks [sig]: only the spool holds the key that can.
 */
class HeldTicket private constructor(
    val text: String,
    val tk: ByteArray,
    val sig: ByteArray,
    val claims: SpoolTicket,
    /**
     * The holder's pseudonym for the day (`ps`), what a spool charges uploads to (S-7.6-22): the key of the spent mark
     * `ScopeSync` keeps after a `budget` refusal. Null when `tk` carries none, which a spool would refuse anyway.
     */
    val pseudonym: TicketPseudonym?,
) {
    /** Whether bit 0 of `fl` lets this ticket ask for TURN. */
    val grantsTurn: Boolean get() = claims.fl and SpoolTicket.FL_TURN != 0L

    /** Whether bit 1 of `fl` lets this ticket upload attachments on its own connection, within its day's budget. */
    val grantsFiles: Boolean get() = claims.fl and SpoolTicket.FL_FILES != 0L

    /** Whether bit 3 of `fl` lets this ticket cast, and unlock casts on its connection's scopes. */
    val grantsCast: Boolean get() = claims.fl and SpoolTicket.FL_CAST != 0L

    /** Whether [other] is these very bytes — what "a different ticket" means to the refusal policy. */
    fun sameAs(other: HeldTicket): Boolean = tk.contentEquals(other.tk) && sig.contentEquals(other.sig)

    override fun toString(): String = "HeldTicket(aud=${claims.aud}, exp=${claims.exp}, fl=${claims.fl})"

    companion object {
        /** `knit-ticket:v1:` + unpadded base64url of the house CBOR `{tk, sig}` — knit-spool's `Tickets.encodeText`. */
        const val PREFIX = "knit-ticket:v1:"

        /** knit-spool's `MAX_TK_BYTES`: bigger than any honest ticket by a wide margin. */
        const val MAX_TK_BYTES = 512
        const val SIG_BYTES = 64

        /**
         * An operator's paste, trimmed, or null: another prefix, base64 that is not url-safe, a `tk` past [MAX_TK_BYTES],
         * a `sig` that is not [SIG_BYTES], or claims that do not decode as a version-1 ticket — a client that cannot
         * read `aud` cannot keep the host rule, so it holds nothing it cannot read.
         */
        fun parse(text: String): HeldTicket? {
            val trimmed = text.trim()
            if (!trimmed.startsWith(PREFIX)) return null
            val body = runCatching { Base64.getUrlDecoder().decode(trimmed.removePrefix(PREFIX)) }.getOrNull() ?: return null
            val parsed = SpoolCodec.decode<TicketText>(body) ?: return null
            if (parsed.tk.size > MAX_TK_BYTES || parsed.sig.size != SIG_BYTES) return null
            val claims = SpoolCodec.decode<SpoolTicket>(parsed.tk)?.takeIf(::wellFormed) ?: return null
            val pseudonym = SpoolCodec.decode<TicketPs>(parsed.tk)?.ps?.let(::TicketPseudonym)
            return HeldTicket(trimmed, parsed.tk, parsed.sig, claims, pseudonym)
        }

        // The spool checks more (key id, window length, pseudonym size); a client only needs claims it can act on.
        private fun wellFormed(claims: SpoolTicket): Boolean =
            claims.v == SpoolTicket.VERSION && claims.aud.isNotEmpty() && claims.nbf >= 0 && claims.exp >= claims.nbf && claims.fl >= 0
    }
}

@Serializable
private class TicketText(
    val tk: ByteArray,
    val sig: ByteArray,
)

// The one claim [SpoolTicket] leaves out on purpose, read apart so the claims never carry it.
@Serializable
private class TicketPs(
    val ps: ByteArray? = null,
)

/**
 * A ticket's daily pseudonym (`ps`), opaque: equal by its bytes, so it can key a map, and never printed. It names the
 * holder to the spool that issued for it for a day, so it stays out of every log line, status, bridge reply and
 * rendering, like the ticket it came from (ADR 2026-10.xkp4). Nothing outside this file reads the bytes.
 */
class TicketPseudonym(
    private val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is TicketPseudonym && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "TicketPseudonym(…)"
}

/**
 * What a client does with a ticket once a spool has answered it (C-7.6-19: never retry a refused ticket on every
 * round). Pure; [SpoolTickets] applies it to the stored ticket's **verdict**, which only an import of different bytes
 * or a clear resets.
 *
 * - `expired`: the spool's clock says the ticket is over, so it is dropped.
 * - `early`: the clocks may simply disagree, so it goes out once more, on the next connection, then never again.
 * - `malformed`, `key`, `signature`, `audience`, and any word this build does not know: those bytes are never
 *   presented again. Nothing about them will change by asking twice.
 *
 * An `err` with any other code (`rate`, `internal`) is no verdict on the ticket and changes nothing here.
 */
object SpoolTicketPolicy {
    const val EXPIRED = "expired"
    const val EARLY = "early"

    /** The verdict an `early` refusal leaves: one more presentation, on the next connection. */
    const val RETRY_EARLY = "retry:early"

    sealed interface Next {
        /** Keep the ticket under [verdict] — null after an acceptance, which clears an `early` retry. */
        data class Keep(
            val verdict: String?,
        ) : Next

        data object Drop : Next
    }

    /** What follows an answer to a ticket whose stored verdict was [prior]: [refusal] is null for `ok`. */
    fun afterAnswer(
        prior: String?,
        refusal: String?,
    ): Next =
        when (refusal) {
            null -> Next.Keep(null)
            EXPIRED -> Next.Drop
            EARLY -> Next.Keep(if (prior == RETRY_EARLY) EARLY else RETRY_EARLY)
            else -> Next.Keep(refusal.take(SPOOL_ERR_CODE_MAX))
        }

    /** Whether a ticket under [verdict] may be presented at all. */
    fun presentable(verdict: String?): Boolean = verdict == null || verdict == RETRY_EARLY
}

/**
 * One stored ticket: the text it was imported as and the verdict its last answer left ([SpoolTicketPolicy]). A data
 * class for the change feed's equality; its [toString] names the verdict only.
 */
data class StoredSpoolTicket(
    val text: String,
    val verdict: String?,
) {
    override fun toString(): String = "StoredSpoolTicket(verdict=$verdict)"
}

/**
 * Where the tickets live: one per spool host name (implemented by `SettingsStore`, beside the relay list and its `?k=`
 * tokens, and kept out of a backup like the phone's own keys are). Every write is one atomic edit.
 */
interface SpoolTicketJournal {
    /** Every ticket held, by spool host name. */
    val spoolTickets: Flow<Map<String, StoredSpoolTicket>>

    /** Holds [text] for [host], replacing whatever ticket was there and clearing its verdict. */
    suspend fun putSpoolTicket(
        host: String,
        text: String,
    )

    /** Forgets [host]'s ticket and its verdict. */
    suspend fun removeSpoolTicket(host: String)

    /**
     * Leaves [verdict] on [host]'s ticket, or forgets it when [drop] — only while the ticket held there is still
     * [text], so an answer to one ticket never lands on the one imported after it.
     */
    suspend fun answerSpoolTicket(
        host: String,
        text: String,
        verdict: String?,
        drop: Boolean,
    )
}

/**
 * The client's tickets (experimental, docs/VOICE_CALLS.md §2.6, ADR 2026-10.xkp4): the import door, the one rule
 * that picks a ticket for a spool, and the refusal policy applied to what a spool answered. Stateless over the
 * [journal], so the debug bridge and `ScopeSync` can each hold one and agree.
 *
 * **The host rule is a privacy rule.** A ticket goes only to the spool whose host name is its `aud`: the pseudonym
 * inside it would otherwise reach an operator it was never meant for. [import] refuses a mismatch,
 * [presentable] re-checks it, and `SpoolConnection.present` checks it a third time against the URL it dialled.
 */
class SpoolTickets(
    private val journal: SpoolTicketJournal,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** What [import] made of a paste. */
    sealed interface Import {
        /** Held now, replacing whatever was held for that host (or unchanged: the same bytes keep their verdict). */
        class Held(
            val ticket: HeldTicket,
        ) : Import

        /** Not a ticket this build can read: see [HeldTicket.parse]. */
        data object Unreadable : Import

        /** The URL names no host. */
        data object NoHost : Import

        /** A ticket for [aud], which is not [host]: never held, so it can never be presented there. */
        class ForAnotherSpool(
            val aud: String,
            val host: String,
        ) : Import
    }

    /** What the oracle shows of a held ticket: its claims and verdict, never its bytes or its pseudonym. */
    class View(
        val exp: Long,
        val grantsTurn: Boolean,
        val grantsFiles: Boolean,
        val grantsCast: Boolean,
        val verdict: String?,
    )

    /** Every held ticket and verdict, distinct: what a live connection watches to present an import at once. */
    val changes: Flow<Map<String, StoredSpoolTicket>> = journal.spoolTickets.distinctUntilChanged()

    suspend fun import(
        url: String,
        text: String,
    ): Import {
        val ticket = HeldTicket.parse(text) ?: return Import.Unreadable
        val host = SpoolUrl.hostname(url) ?: return Import.NoHost
        if (ticket.claims.aud != host) return Import.ForAnotherSpool(ticket.claims.aud, host)
        // The same bytes again keep their verdict: only a *different* ticket earns a refused one another try.
        val held =
            journal.spoolTickets
                .first()[host]
                ?.text
                ?.let(HeldTicket::parse)
        if (held == null || !held.sameAs(ticket)) journal.putSpoolTicket(host, ticket.text)
        return Import.Held(ticket)
    }

    /** Forgets the ticket for [url]'s host, verdict and all; a later import of the same bytes is a fresh one. */
    suspend fun clear(url: String) {
        SpoolUrl.hostname(url)?.let { journal.removeSpoolTicket(it) }
    }

    /**
     * The ticket to present to the spool at [url] now, or null: none held, one the policy has refused for good, one
     * whose `aud` is not this host, or one past its `exp` on our clock — the spool would refuse it `expired`.
     */
    suspend fun presentable(url: String): HeldTicket? {
        val host = SpoolUrl.hostname(url) ?: return null
        val stored = journal.spoolTickets.first()[host] ?: return null
        if (!SpoolTicketPolicy.presentable(stored.verdict)) return null
        val ticket = HeldTicket.parse(stored.text) ?: return null
        return ticket.takeIf { it.claims.aud == host && clock() / MS_PER_S <= it.claims.exp }
    }

    /** The spool at [url] answered [ticket]: [refusal] is its `err ticket` word, or null for `ok`. */
    suspend fun answered(
        url: String,
        ticket: HeldTicket,
        refusal: String?,
    ) {
        val host = SpoolUrl.hostname(url) ?: return
        val prior = journal.spoolTickets.first()[host]?.takeIf { it.text == ticket.text } ?: return
        when (val next = SpoolTicketPolicy.afterAnswer(prior.verdict, refusal)) {
            SpoolTicketPolicy.Next.Drop -> journal.answerSpoolTicket(host, ticket.text, verdict = null, drop = true)
            is SpoolTicketPolicy.Next.Keep -> journal.answerSpoolTicket(host, ticket.text, next.verdict, drop = false)
        }
    }

    /** The held ticket for [url]'s host, as the oracle shows it, or null when none is held. */
    suspend fun view(url: String): View? {
        val host = SpoolUrl.hostname(url) ?: return null
        val stored = journal.spoolTickets.first()[host] ?: return null
        val ticket = HeldTicket.parse(stored.text) ?: return null
        return View(ticket.claims.exp, ticket.grantsTurn, ticket.grantsFiles, ticket.grantsCast, stored.verdict)
    }

    private companion object {
        const val MS_PER_S = 1_000L
    }
}
