package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.crypto.scope.PowMiner
import app.getknit.knit.mesh.crypto.scope.SpoolPow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * The free issuers this build knows (C-7.6-33, docs/VOICE_CALLS.md §2.6): relay host name → the origin that makes free
 * accounts for it. Only from configuration — the seeded list in `res/values/spools.xml`, and a debug build's bridge —
 * never from a spool, a peer or an invite, whose `i` comes with a customer key and is that key's issuer.
 */
object FreeIssuers {
    /**
     * Reads the seeded `free_issuers` items, each `<relay host> <issuer origin>`; an item that is not two words, or
     * whose origin [IssuerOrigin] refuses for a release build, is dropped.
     */
    fun parse(items: Array<String>): Map<String, String> =
        items
            .mapNotNull { item ->
                val parts = item.trim().split(Regex("\\s+"))
                if (parts.size != 2 || !IssuerOrigin.isAcceptable(parts[1], allowCleartext = false)) return@mapNotNull null
                parts[0].lowercase() to parts[1]
            }.toMap()

    /**
     * Reads the seeded `free_issuer_am` items, each `<issuer origin> <bytes>`: the `am` a free ticket from that issuer
     * is expected to carry, which sizes the photo a phone sends before it has ever held one (`data/relay/FreeUploads`).
     * The issuer's own ticket always wins once one is held. An item that is not an origin and a positive number is
     * dropped.
     */
    fun expectedAm(items: Array<String>): Map<String, Long> =
        items
            .mapNotNull { item ->
                val parts = item.trim().split(Regex("\\s+"))
                val am = parts.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 }
                if (parts.size != 2 || am == null || !IssuerOrigin.isAcceptable(parts[0], allowCleartext = false)) return@mapNotNull null
                parts[0] to am
            }.toMap()
}

/**
 * The account route's proof-of-work stamp (docs/VOICE_CALLS.md §2.6, C-7.6-36):
 * `SHA-256("knit/issuer/v1/account" ‖ u64be(day) ‖ nonce ‖ u64be(n))` with at least `bits` leading zero bits, counted
 * as §8 counts them ([SpoolPow.leadingZeroBits]). The issuer's `day` and `bits` come from its own 200; the nonce is
 * fresh per request. Mined with [PowMiner], the same allocation-free loop §8's stamps use.
 */
object AccountStamp {
    const val NONCE_BYTES = 16

    /**
     * The most difficulty a client mines for. An issuer asking for more is answered as if it were unavailable: at 26
     * bits a stamp is about 64 M hashes, many seconds on a phone, and each bit doubles that.
     */
    const val MAX_BITS = 26

    /** `n` stays below 2^53, so the JSON number round-trips through a JavaScript issuer. */
    const val MAX_N = (1L shl 53) - 1

    private val LABEL = "knit/issuer/v1/account".toByteArray(Charsets.US_ASCII)

    // How many counters a mining step tries before it yields, so a cancelled ask stops within milliseconds.
    private const val STEP = 1L shl 16

    /** The stamp digest for [day], [nonce] and [n]: the reference [mine] is held to. */
    fun digest(
        day: Long,
        nonce: ByteArray,
        n: Long,
    ): ByteArray = MessageDigest.getInstance("SHA-256").digest(LABEL + u64be(day) + nonce + u64be(n))

    /** Whether [n] is a stamp of [bits] for [day] and [nonce]. */
    fun verify(
        day: Long,
        nonce: ByteArray,
        n: Long,
        bits: Int,
    ): Boolean = SpoolPow.leadingZeroBits(digest(day, nonce, n)) >= bits

    /**
     * The smallest `n` from 0 whose stamp meets [bits], or null past [maxAttempts] or [MAX_N]. Suspends between steps
     * so it can be cancelled; the caller runs it off the main thread.
     */
    suspend fun mine(
        day: Long,
        nonce: ByteArray,
        bits: Int,
        maxAttempts: Long,
    ): Long? {
        require(nonce.size == NONCE_BYTES) { "nonce must be $NONCE_BYTES bytes" }
        val miner = PowMiner(LABEL + u64be(day) + nonce)
        var n = 0L
        val last = minOf(maxAttempts, MAX_N + 1)
        while (n < last) {
            val stepEnd = minOf(n + STEP, last)
            while (n < stepEnd) {
                if (miner.leadingZeroBits(n) >= bits) return n
                n++
            }
            yield()
        }
        return null
    }

    @Suppress("MagicNumber") // big-endian byte lanes, as SpoolPow writes them
    private fun u64be(value: Long): ByteArray = ByteArray(Long.SIZE_BYTES) { (value ushr ((Long.SIZE_BYTES - 1 - it) * 8)).toByte() }
}

/**
 * What the issuer's account route answered (docs/VOICE_CALLS.md §2.6). Nothing here prints the key: [Created] names
 * its audience only.
 */
sealed interface AccountAnswer {
    /** `GET` 200: mine [bits] for [day] (the issuer's UTC day), for [aud]. */
    data class Params(
        val aud: String,
        val bits: Int,
        val day: Long,
    ) : AccountAnswer

    /** `POST` 201: a new free account's [key], for [aud]. A plain class whose [toString] never names the key. */
    class Created(
        val key: String,
        val aud: String,
    ) : AccountAnswer {
        override fun toString(): String = "Created(aud=$aud)"
    }

    /** `POST` 400 `bad-stamp`: mine again, once, with [bits] and [day]. */
    data class BadStamp(
        val bits: Int,
        val day: Long,
    ) : AccountAnswer

    /** 429 `rate-limited`: this address has made its day's accounts. Wait [retryAfterMs] when it said. */
    data class RateLimited(
        val retryAfterMs: Long?,
    ) : AccountAnswer

    /** 503 `unavailable`: no free tier for this audience now, or the day's cap is reached. Wait [retryAfterMs] when said. */
    data class Unavailable(
        val retryAfterMs: Long?,
    ) : AccountAnswer

    /**
     * Anything else — no route, a timeout, another status, a body that does not parse, an answer for another audience:
     * the issuer said nothing about this request. [reason] is a short word for the log, never a body.
     */
    data class NoAnswer(
        val reason: String,
    ) : AccountAnswer

    companion object {
        /**
         * Reads a `GET` or a `POST` answer: [status], the (capped) [body], and the `retry-after` header in seconds, when
         * it was a number. Pure, so the JVM tests pin every answer. A 400, 429 or 503 counts only with its own `error`
         * word, since a proxy in front of the issuer answers those statuses too.
         */
        fun read(
            status: Int,
            body: ByteArray,
            retryAfterS: Long?,
        ): AccountAnswer {
            val parsed = runCatching { json.decodeFromString<AccountWire>(body.decodeToString()) }.getOrNull()
            val retryAfterMs = retryAfterS?.takeIf { it >= 0 }?.let { it * MS_PER_S }
            return when {
                parsed == null -> NoAnswer(if (status == HTTP_OK || status == HTTP_CREATED) "unparseable" else "http $status")
                status == HTTP_OK -> params(parsed)
                status == HTTP_CREATED -> created(parsed)
                status == HTTP_BAD_REQUEST && parsed.error == BAD_STAMP -> badStamp(parsed)
                status == HTTP_TOO_MANY && parsed.error == RATE_LIMITED -> RateLimited(retryAfterMs)
                status == HTTP_UNAVAILABLE && parsed.error == UNAVAILABLE -> Unavailable(retryAfterMs)
                else -> NoAnswer("http $status")
            }
        }

        private fun params(parsed: AccountWire): AccountAnswer {
            val aud = parsed.aud ?: return NoAnswer("unparseable")
            val bits = parsed.bits?.takeIf { it in 0..MAX_PARSED_BITS } ?: return NoAnswer("unparseable")
            val day = parsed.day?.takeIf { it >= 0 } ?: return NoAnswer("unparseable")
            return Params(aud, bits, day)
        }

        private fun created(parsed: AccountWire): AccountAnswer {
            val key = parsed.key?.takeIf(CustomerKey::isWellFormed) ?: return NoAnswer("unparseable")
            val aud = parsed.aud ?: return NoAnswer("unparseable")
            return Created(key, aud)
        }

        private fun badStamp(parsed: AccountWire): AccountAnswer {
            val bits = parsed.bits?.takeIf { it in 0..MAX_PARSED_BITS } ?: return NoAnswer("unparseable")
            val day = parsed.day?.takeIf { it >= 0 } ?: return NoAnswer("unparseable")
            return BadStamp(bits, day)
        }

        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_TOO_MANY = 429
        const val HTTP_UNAVAILABLE = 503
        private const val BAD_STAMP = "bad-stamp"
        private const val RATE_LIMITED = "rate-limited"
        private const val UNAVAILABLE = "unavailable"
        private const val MS_PER_S = 1_000L

        // A SHA-256 has 256 bits; anything past it is no stamp anyone could mine.
        private const val MAX_PARSED_BITS = 256

        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** Every field any account answer carries; what a given status needs is checked in [AccountAnswer.read]. */
@Serializable
private class AccountWire(
    val aud: String? = null,
    val bits: Int? = null,
    val day: Long? = null,
    val key: String? = null,
    val error: String? = null,
)

/**
 * The HTTP seam for the account route (docs/VOICE_CALLS.md §2.6): the `GET` that names the stamp's parameters and the
 * `POST` that spends one. No `Authorization` header on either. The one implementation that speaks OkHttp
 * (`OkHttpTicketIssuer`) keeps the key swap's rules: bound to the validated network, no redirect, short timeouts, a
 * capped body. Never throws.
 */
interface AccountIssuer {
    /** `GET <origin>/api/v1/account?aud=<aud>`. */
    suspend fun accountParams(
        origin: String,
        aud: String,
    ): AccountAnswer

    /** `POST <origin>/api/v1/account` with `{ aud, day, nonce, n }`, [nonce] as unpadded base64url. */
    suspend fun createAccount(
        origin: String,
        aud: String,
        day: Long,
        nonce: ByteArray,
        n: Long,
    ): AccountAnswer
}

/**
 * What this phone knows about making a free account for one relay host: the earliest it may ask the account route
 * again ([notBefore], epoch millis), and how many asks in a row met a 503 ([unavailable]) or no answer ([failures]),
 * which set the next backoff. Nothing secret.
 */
data class FreeState(
    val notBefore: Long = 0,
    val unavailable: Int = 0,
    val failures: Int = 0,
)

/**
 * The account route's backoff (C-7.6-37, C-7.6-38), pure over the phone's wall clock.
 *
 * - 429 waits its `retry-after`, or [UNAVAILABLE_FIRST_MS] without one.
 * - 503 (and a second `bad-stamp` in one request) waits its `retry-after`, or [UNAVAILABLE_FIRST_MS] doubling to
 *   [UNAVAILABLE_MAX_MS].
 * - No answer waits [TicketRefreshPolicy.backoff]'s 1 min doubling to 1 h: the issuer never answered, so nothing was
 *   asked of its cap.
 * - A free key forgotten after a 401 or a 403 is not replaced before the next UTC day ([nextUtcDay]).
 */
object FreeAccountPolicy {
    const val UNAVAILABLE_FIRST_MS = 60 * 60 * 1_000L
    const val UNAVAILABLE_MAX_MS = 24 * 60 * 60 * 1_000L

    // 1 h doubled five times is past the day cap already.
    private const val MAX_DOUBLINGS = 5

    fun afterUnavailable(
        prior: FreeState,
        retryAfterMs: Long?,
        now: Long,
    ): FreeState {
        val streak = prior.unavailable + 1
        val wait = retryAfterMs ?: (UNAVAILABLE_FIRST_MS shl (streak - 1).coerceIn(0, MAX_DOUBLINGS)).coerceAtMost(UNAVAILABLE_MAX_MS)
        return FreeState(notBefore = now + wait, unavailable = streak, failures = 0)
    }

    fun afterRateLimited(
        prior: FreeState,
        retryAfterMs: Long?,
        now: Long,
    ): FreeState = prior.copy(notBefore = now + (retryAfterMs ?: UNAVAILABLE_FIRST_MS), failures = 0)

    fun afterNoAnswer(
        prior: FreeState,
        now: Long,
    ): FreeState {
        val failures = prior.failures + 1
        return prior.copy(notBefore = now + TicketRefreshPolicy.backoff(failures), failures = failures)
    }

    /** The start of the UTC day after [now]: when a forgotten free key may be replaced. */
    fun nextUtcDay(now: Long): Long = (SpoolPow.utcDay(now) + 1) * SpoolPow.DAY_MS
}

/**
 * Where the free half of the key store lives (implemented by `data/settings/TicketKeyStore`): the free issuers this
 * build knows, the person's answer to the notice per relay host (C-7.6-34), and the account route's backoff. The free
 * key itself sits in [TicketKeyJournal]'s one slot per host, marked [HeldKey.free].
 */
interface FreeKeyJournal {
    /** Relay host name → free issuer origin: the seeded ones, and a debug build's own. */
    val freeIssuers: Flow<Map<String, String>>

    /** Relay host name → whether the person acknowledged free tickets there (true) or turned them off (false); absent: on, not yet told. */
    val freeConsent: Flow<Map<String, Boolean>>

    /** Relay host name → the account route's backoff there. */
    val freeStates: Flow<Map<String, FreeState>>

    /** Sets [host]'s free issuer to [origin], or back to the seeded one with null. Only the debug bridge calls it. */
    suspend fun setFreeIssuer(
        host: String,
        origin: String?,
    )

    /**
     * Records the person's answer for [host], or forgets it with null (the debug bridge's way to see the notice again).
     * Declining forgets a free key held there, its state and its ticket.
     */
    suspend fun setFreeConsent(
        host: String,
        allowed: Boolean?,
    )

    suspend fun putFreeState(
        host: String,
        state: FreeState,
    )

    /**
     * Forgets [key], a free key the issuer answered 401 or 403, with its ask state, only while it is still the key held
     * for [host]; the next account waits until [notBefore] (C-7.6-37).
     */
    suspend fun forgetFreeKey(
        host: String,
        key: HeldKey,
        notBefore: Long,
    )
}
