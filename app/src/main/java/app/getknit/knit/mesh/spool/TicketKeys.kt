package app.getknit.knit.mesh.spool

import kotlinx.coroutines.flow.Flow

/**
 * A customer key's shape (experimental, docs/VOICE_CALLS.md §2.6, ADR 2026-10.3sk7): `kak1_` and 32 random bytes as
 * unpadded base64url, the pattern knit-spool-coordinator's `packages/core/src/anywhere.ts` mints and checks. The key
 * is a bearer credential that lends its holder's quota, so it is never minted into an invite by this phone, never put
 * in a URL, a query or a body, and never rendered: only its presence is.
 */
object CustomerKey {
    const val PREFIX = "kak1_"

    private val PATTERN = Regex("^kak1_[A-Za-z0-9_-]{43}$")

    fun isWellFormed(key: String): Boolean = PATTERN.matches(key)
}

/**
 * A ticket issuer's origin as an invite carries it (docs/RELAY_INVITE.md §2): `https://<host>[:port]`, scheme and host
 * lowercase, and nothing after — no path, query, fragment, user info or trailing slash — at most [MAX_CHARS]
 * characters. `http://` only where [isAcceptable] is told the build allows cleartext, the same privilege `ws://` has
 * for a relay URL. The host is a DNS-shaped name (an IPv4 literal is one) or a bracketed IPv6 literal; a port, when
 * present, is 1..65535 with no leading zero.
 *
 * Checked twice: when an invite is parsed, and before every request ([TicketRefresher]), so a key only ever goes to an
 * origin this rule passed.
 */
object IssuerOrigin {
    const val MAX_CHARS = 128

    /** Where the key swap lives under an origin (coordinator ADR 2026-10.ukvm). */
    const val TICKET_PATH = "/api/v1/ticket"

    /** Where the badge issue lives under the same origin (docs/SUPPORTER_BADGE.md §5). */
    const val BADGE_PATH = "/api/v1/badge"

    /** Where the free account route lives under a free issuer's origin (docs/VOICE_CALLS.md §2.6). */
    const val ACCOUNT_PATH = "/api/v1/account"

    private val PATTERN =
        Regex(
            "^(https?)://" +
                "([a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*|\\[[0-9a-f:.]+])" +
                "(?::([1-9][0-9]{0,4}))?$",
        )

    fun isAcceptable(
        origin: String,
        allowCleartext: Boolean,
    ): Boolean {
        if (origin.length > MAX_CHARS) return false
        val match = PATTERN.matchEntire(origin) ?: return false
        val (scheme, _, port) = match.destructured
        return (scheme != "http" || allowCleartext) && (port.isEmpty() || port.toInt() <= MAX_PORT)
    }

    /** The origin without its scheme — what the invite sheet names. Only ever called on an accepted origin. */
    fun host(origin: String): String = origin.substringAfter("://")

    /** The key swap's URL under [origin]. */
    fun ticketUrl(origin: String): String = origin + TICKET_PATH

    /** The badge issue's URL under [origin]. */
    fun badgeUrl(origin: String): String = origin + BADGE_PATH

    /** The account route's URL under [origin], without its query. */
    fun accountUrl(origin: String): String = origin + ACCOUNT_PATH

    private const val MAX_PORT = 65_535
}

/**
 * The key held for one relay host, and the issuer it goes to: a customer key an invite handed over, or a [free] one
 * this phone made at a free issuer (C-7.6-37), which never rides a backup, never asks on a schedule and never asks for
 * a badge. A data class for the change feed's equality, whose [toString] names the issuer only: the key must not reach
 * a log line through it.
 */
data class HeldKey(
    val issuer: String,
    val key: String,
    val free: Boolean = false,
) {
    override fun toString(): String = "HeldKey(issuer=$issuer, free=$free)"
}

/** The issuer's last verdict on a key, as the store keeps it and the debug dump names it. */
enum class KeyAnswer(
    val word: String,
) {
    OK("ok"),
    UNKNOWN_KEY("unknown-key"),
    ENDED("ended"),
    ;

    companion object {
        fun of(word: String?): KeyAnswer? = entries.firstOrNull { it.word == word }
    }
}

/**
 * What this phone knows about asking for one host's tickets: the last verdict ([answer], null before the first),
 * the account's end as the issuer last said it ([endsAt], epoch millis), the earliest time it may ask again
 * ([notBefore], epoch millis — the 1 h floor after an answer, the daily retry after `ended`, the backoff after no
 * answer), and how many asks in a row got no verdict ([failures], which sets the backoff). Nothing secret.
 */
data class KeyState(
    val answer: KeyAnswer? = null,
    val endsAt: Long? = null,
    val notBefore: Long = 0,
    val failures: Int = 0,
) {
    companion object {
        /** A key that just arrived: never asked, so asked at once. */
        val FRESH = KeyState()
    }
}

/**
 * Where the customer keys live (implemented by `data/settings/TicketKeyStore`, in the settings DataStore beside the
 * relay list and its `?k=` tokens, under the protection ADR 2026-09.66cw gives them). One key per relay host name,
 * the name its tickets carry as `aud`. Every write is one atomic edit.
 */
interface TicketKeyJournal {
    /** Every key held, by relay host name. */
    val keys: Flow<Map<String, HeldKey>>

    /** What the refresher knows about each host it asks for, by host name. */
    val keyStates: Flow<Map<String, KeyState>>

    /**
     * Holds [key] for [host], in the one slot a host has: a customer key replaces a free one, and a free one made after
     * a customer key ended replaces that. The same key again changes nothing; a different one (or a different issuer)
     * replaces it and resets its state to [KeyState.FRESH], which is what lifts an `unknown-key` park.
     */
    suspend fun putKey(
        host: String,
        key: HeldKey,
    )

    /** Records [state] for [host], only while the key held there is still [key] — an answer never lands on its successor. */
    suspend fun putKeyState(
        host: String,
        key: HeldKey,
        state: KeyState,
    )

    /** Forgets [host]'s key, its state and its ticket, in one edit: what removing the relay does (C-7.6-29). */
    suspend fun forgetHost(host: String)
}
