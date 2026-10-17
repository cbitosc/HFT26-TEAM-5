package app.getknit.knit.mesh.spool

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * When to ask an issuer for a host's ticket, and what an answer leaves behind (C-7.6-27 and C-7.6-28, ADR
 * 2026-10.3sk7). Pure over the phone's wall clock, so every row is a JVM test.
 *
 * - A key that just arrived is asked at once, and its first ask backs off on no verdict until one comes.
 * - After a verdict of `ok` or `ended`, a host is asked when its held ticket is missing, not presentable
 *   ([SpoolTickets.presentable]: refused for good, for another host, or past `exp`), or less than [REFRESH_MARGIN_MS]
 *   from its `exp` — but never before [KeyState.notBefore]: [OK_FLOOR_MS] after a 200, so an account whose `exp` is
 *   capped at its end cannot loop (nor a 200 whose ticket was never held), and [ENDED_RETRY_MS] after a 403, since
 *   `account extend` can revive it.
 * - `unknown-key` is never asked again; only a different key ([TicketKeyJournal.putKey]) resets the state.
 * - No verdict backs off from [FIRST_BACKOFF_MS], doubling, to at most [MAX_BACKOFF_MS], keeping the last verdict; a
 *   503 that names a `retry-after` (a free key on a capped day) waits at least that long.
 * - A free key ([HeldKey.free], C-7.6-27 amended) is asked only while an upload waits and no ticket is presentable
 *   ([freeDueAt]): never on the margin, but still never before [KeyState.notBefore], so the floor after a 200 stops a
 *   ticket refused on import or at `ent` from looping.
 */
object TicketRefreshPolicy {
    const val REFRESH_MARGIN_MS = 6 * 60 * 60 * 1_000L
    const val OK_FLOOR_MS = 60 * 60 * 1_000L
    const val ENDED_RETRY_MS = 24 * 60 * 60 * 1_000L
    const val FIRST_BACKOFF_MS = 60 * 1_000L
    const val MAX_BACKOFF_MS = 60 * 60 * 1_000L

    /**
     * The epoch millis at which to ask next, or null for never. [presentableExp] is the `exp` (Unix seconds) of the
     * ticket [SpoolTickets.presentable] would present for the host now, or null when it would present none.
     */
    fun dueAt(
        state: KeyState,
        presentableExp: Long?,
    ): Long? =
        when (state.answer) {
            KeyAnswer.UNKNOWN_KEY -> null
            null -> state.notBefore
            KeyAnswer.OK, KeyAnswer.ENDED -> maxOf(state.notBefore, presentableExp?.let { it * MS_PER_S - REFRESH_MARGIN_MS } ?: 0L)
        }

    /**
     * When a free key's host is due (C-7.6-27 amended): only while [waiting] (an upload is locked there for want of a
     * ticket) and nothing is [presentable] — a held free ticket is used until its `exp` — and never before
     * [KeyState.notBefore]. Null for never: no upload waits, a ticket is held, or the key was parked.
     */
    fun freeDueAt(
        state: KeyState,
        waiting: Boolean,
        presentable: Boolean,
    ): Long? =
        when {
            !waiting || presentable -> null
            state.answer == KeyAnswer.UNKNOWN_KEY || state.answer == KeyAnswer.ENDED -> null
            else -> state.notBefore
        }

    /**
     * The state an [answer] at [now] leaves after [prior]. [held] says whether an [IssuerAnswer.Issued] ticket passed the
     * import's host rule: one that did not is no verdict, because nothing usable came back.
     */
    fun afterAnswer(
        prior: KeyState,
        answer: IssuerAnswer,
        held: Boolean,
        now: Long,
    ): KeyState =
        when (answer) {
            // A 200 whose ticket was never held is no verdict, but it still waits out the floor: asking again at once
            // would only fetch the same unusable ticket, and a free key would loop on it (C-7.6-27).
            is IssuerAnswer.Issued -> {
                if (held) {
                    KeyState(KeyAnswer.OK, answer.endsAt, now + OK_FLOOR_MS, failures = 0)
                } else {
                    noVerdict(prior, now).let { it.copy(notBefore = maxOf(it.notBefore, now + OK_FLOOR_MS)) }
                }
            }

            IssuerAnswer.UnknownKey -> {
                KeyState(KeyAnswer.UNKNOWN_KEY, prior.endsAt, notBefore = 0L, failures = 0)
            }

            is IssuerAnswer.Ended -> {
                KeyState(KeyAnswer.ENDED, answer.endsAt ?: prior.endsAt, now + ENDED_RETRY_MS, failures = 0)
            }

            is IssuerAnswer.NoVerdict -> {
                noVerdict(prior, now)
            }

            // A capped day (C-7.6-28): no ticket before the issuer's `retry-after`, so no ask before it either; without
            // one, the ordinary backoff.
            is IssuerAnswer.Unavailable -> {
                noVerdict(prior, now).let { next -> answer.retryAfterMs?.let { next.copy(notBefore = maxOf(next.notBefore, now + it)) } ?: next }
            }
        }

    /** The wait after the [failures]-th ask in a row with no verdict: 1 min, 2, 4, … capped at 1 h. */
    fun backoff(failures: Int): Long {
        val doublings = (failures - 1).coerceIn(0, MAX_DOUBLINGS)
        return (FIRST_BACKOFF_MS shl doublings).coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun noVerdict(
        prior: KeyState,
        now: Long,
    ): KeyState {
        val failures = prior.failures + 1
        return prior.copy(failures = failures, notBefore = now + backoff(failures))
    }

    private const val MS_PER_S = 1_000L

    // 1 min doubled six times is past the hour cap already; bounding the shift keeps it far from overflow.
    private const val MAX_DOUBLINGS = 6
}

/**
 * The client half of the key swap (experimental, docs/VOICE_CALLS.md §2.6, ADR 2026-10.3sk7): while the Internet plane
 * runs, it asks each active relay's issuer for that relay's ticket when [TicketRefreshPolicy] says it is due, and puts
 * a fetched ticket through [SpoolTickets.import] — the host rule's door — so `ScopeSync`'s store watch presents it on
 * the live connection at once.
 *
 * "While the plane runs" is [urls]: the relays dialled right now (`SettingsStore.activeSpoolUrls`), empty with the
 * plane off, and a parked relay is not asked for. One coroutine on the mesh session, started beside `ScopeSync`: it
 * sleeps until the earliest due ask, at most [maxSleepMs] so a jumped wall clock is re-read, and wakes early on a new
 * key, a ticket change, a relay change or a new route. Asks wait for [online]; a phone with no route asks nobody.
 *
 * C-7.6-29: a key goes only to the issuer origin stored with it, re-checked by [IssuerOrigin] before every ask with
 * this build's cleartext rule, and only through [TicketIssuer.swap]'s header. [log] gets hosts, verdict words and
 * times, never a key, a ticket or a body.
 *
 * **A waiting upload asks** (C-7.6-27 amended): `ScopeSync` calls [uploadWanted] when an upload of ours is locked
 * `missing` or `expired` behind a relay's files gate, which wakes the loop and marks the host waiting for
 * [WANT_TTL_MS] (the plane re-marks it on every look while the upload stays locked). A customer key's schedule already
 * asks for a missing ticket, so for it the mark is only the wake. A free key asks for nothing else. With no key, where
 * [free] knows a free issuer for the host and the person allowed it (C-7.6-33, C-7.6-34), the loop makes a free
 * account through [accounts] first — `GET` the parameters, mine the stamp off the main thread, `POST` — holds its key
 * and swaps it at once (C-7.6-35, C-7.6-36); a customer key last answered `ended` falls back to one the same way. A
 * free key answered 401 or 403 is forgotten, and the next account waits for the next UTC day (C-7.6-37). The account
 * route backs off by [FreeAccountPolicy] (C-7.6-38). A 200 that leaves a ticket held clears the host's mark.
 */
@Suppress("TooManyFunctions") // the key swap's steps and the free account's, each one small
class TicketRefresher(
    private val keys: TicketKeyJournal,
    private val tickets: SpoolTickets,
    private val issuer: TicketIssuer,
    private val urls: Flow<Set<String>>,
    private val online: () -> Boolean = { true },
    private val routeChanges: Flow<Unit> = emptyFlow(),
    private val allowCleartext: Boolean = false,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = {},
    private val maxSleepMs: Long = MAX_SLEEP_MS,
    // The free half: null in a build or a test that makes no free accounts.
    private val free: FreeKeyJournal? = null,
    private val accounts: AccountIssuer? = null,
    // Draws a fresh account nonce; the tests pin it.
    private val nonces: () -> ByteArray = { ByteArray(AccountStamp.NONCE_BYTES).also(SecureRandom()::nextBytes) },
    // Mines one stamp off the main thread; the tests swap in a cheap one.
    private val mine: suspend (Long, ByteArray, Int) -> Long? = { day, nonce, bits ->
        withContext(Dispatchers.Default) { AccountStamp.mine(day, nonce, bits, MINE_ATTEMPTS) }
    },
) {
    // Relay host name → when an upload there was last locked for want of a ticket (epoch millis).
    private val waiting = ConcurrentHashMap<String, Long>()

    private val wanted = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val _asking = MutableStateFlow<Set<String>>(emptySet())

    /** The relay host names an account request or a free key's swap is running for right now: the bridge's `asking`. */
    val asking: StateFlow<Set<String>> = _asking.asStateFlow()

    /**
     * An upload of ours is locked `missing` or `expired` on the relay at [url] for want of a files ticket (C-7.6-27
     * amended): mark its host waiting and wake the loop. Cheap and repeatable; called from the plane's worker.
     */
    fun uploadWanted(url: String) {
        val host = SpoolUrl.hostname(url) ?: return
        val fresh = waiting.put(host, clock()) == null
        if (fresh) log("$host: an upload waits for a ticket")
        wanted.tryEmit(Unit)
    }

    /** Whether an upload is waiting at [host] right now, as the bridge reports it. */
    fun isWaiting(host: String): Boolean = waitingAt(host)

    private fun waitingAt(host: String): Boolean {
        val at = waiting[host] ?: return false
        if (clock() - at <= WANT_TTL_MS) return true
        waiting.remove(host, at)
        return false
    }

    @Volatile
    private var job: Job? = null

    /** Starts the loop on the mesh session scope. Restart-safe, like `ScopeSync.start`. */
    fun start(session: CoroutineScope) {
        if (job?.isActive == true) return
        job =
            session.launch {
                while (isActive) {
                    val next = runOnce()
                    val wait = if (next == null) maxSleepMs else (next - clock()).coerceIn(0L, maxSleepMs)
                    withTimeoutOrNull(wait) { wakes().first() }
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Asks every host that is due now and has a route, and returns when the next ask falls due (epoch millis), or null
     * when none ever will. With no route, a due ask stays due, and the answer is the sleep cap rather than "now", so an
     * offline phone waits for a route instead of spinning.
     */
    suspend fun runOnce(): Long? {
        val held = keys.keys.first()
        if (held.isEmpty() && waiting.isEmpty()) return null
        val states = keys.keyStates.first()
        return activeHosts()
            .mapNotNull { (host, url) ->
                val key = held[host]
                val state = states[host] ?: KeyState.FRESH
                when {
                    key == null -> makeFreeAccount(host, url)
                    key.free -> refreshFree(host, url, key, state)
                    state.answer == KeyAnswer.ENDED && freeIssuerFor(host) != null && waitingAt(host) -> makeFreeAccount(host, url)
                    else -> refresh(host, url, key, state)
                }
            }.minOrNull()
    }

    /** Asks for [host] if it is due and there is a route; returns when it is next due, or null for never. */
    private suspend fun refresh(
        host: String,
        url: String,
        key: HeldKey,
        state: KeyState,
    ): Long? {
        if (!IssuerOrigin.isAcceptable(key.issuer, allowCleartext) || !CustomerKey.isWellFormed(key.key)) return null
        val due = TicketRefreshPolicy.dueAt(state, tickets.presentable(url)?.claims?.exp) ?: return null
        return when {
            due > clock() -> due
            !online() -> clock() + maxSleepMs
            else -> TicketRefreshPolicy.dueAt(ask(host, url, key, state), tickets.presentable(url)?.claims?.exp)
        }
    }

    /** A free key's ask ([TicketRefreshPolicy.freeDueAt]): only for a waiting upload. Next due, or null for never. */
    private suspend fun refreshFree(
        host: String,
        url: String,
        key: HeldKey,
        state: KeyState,
    ): Long? {
        if (!IssuerOrigin.isAcceptable(key.issuer, allowCleartext) || !CustomerKey.isWellFormed(key.key)) return null
        if (free?.freeConsent?.first()?.get(host) == false) return null
        val due = TicketRefreshPolicy.freeDueAt(state, waitingAt(host), tickets.presentable(url) != null) ?: return null
        return when {
            due > clock() -> due
            !online() -> clock() + maxSleepMs
            else -> {
                val next = asking(host) { ask(host, url, key, state) }
                TicketRefreshPolicy.freeDueAt(next, waitingAt(host), tickets.presentable(url) != null)
            }
        }
    }

    /** The free issuer for [host] when the person allowed it there (C-7.6-33, C-7.6-34), else null. */
    private suspend fun freeIssuerFor(host: String): String? {
        val journal = free ?: return null
        if (accounts == null || journal.freeConsent.first()[host] != true) return null
        return journal.freeIssuers.first()[host]?.takeIf { IssuerOrigin.isAcceptable(it, allowCleartext) }
    }

    /**
     * With no key for [host] (or one whose account ended), and an upload waiting there, makes a free account and swaps
     * its key at once (C-7.6-35). Next due, or null for never: no free issuer, no leave, no upload waiting.
     */
    private suspend fun makeFreeAccount(
        host: String,
        url: String,
    ): Long? {
        if (!waitingAt(host)) return null
        val origin = freeIssuerFor(host) ?: return null
        val journal = free ?: return null
        val prior = journal.freeStates.first()[host] ?: FreeState()
        return when {
            prior.notBefore > clock() -> prior.notBefore
            !online() -> clock() + maxSleepMs
            else -> {
                val key = asking(host) { createAccount(host, origin, prior) }
                if (key == null) {
                    journal.freeStates.first()[host]?.notBefore
                } else {
                    keys.putKey(host, key)
                    refreshFree(host, url, key, KeyState.FRESH)
                }
            }
        }
    }

    /**
     * One account request at [origin] for [host] (C-7.6-35, C-7.6-36): the parameters, a stamp, the `POST`, and once
     * more on a first `bad-stamp`. The key it made, or null with the backoff recorded.
     */
    private suspend fun createAccount(
        host: String,
        origin: String,
        prior: FreeState,
    ): HeldKey? {
        val journal = free ?: return null
        val route = accounts ?: return null
        val params = route.accountParams(origin, host)
        var (bits, day) =
            when {
                params is AccountAnswer.Params && params.aud == host -> {
                    params.bits to params.day
                }

                params is AccountAnswer.Params -> {
                    return backOff(host, prior, AccountAnswer.NoAnswer("audience ${params.aud}"))
                }

                else -> {
                    return backOff(host, prior, params)
                }
            }
        repeat(STAMP_TRIES) { attempt ->
            if (bits > AccountStamp.MAX_BITS) return backOff(host, prior, AccountAnswer.Unavailable(null), "asks $bits bits")
            val nonce = nonces()
            val started = clock()
            val n = mine(day, nonce, bits) ?: return backOff(host, prior, AccountAnswer.NoAnswer("mining"))
            log("$host: mined a $bits-bit account stamp in ${clock() - started} ms")
            when (val created = route.createAccount(origin, host, day, nonce, n)) {
                is AccountAnswer.Created -> {
                    if (created.aud != host) return backOff(host, prior, AccountAnswer.NoAnswer("audience ${created.aud}"))
                    journal.putFreeState(host, FreeState())
                    log("$host: free account made at ${IssuerOrigin.host(origin)}")
                    return HeldKey(origin, created.key, free = true)
                }

                is AccountAnswer.BadStamp -> {
                    // C-7.6-36: mine again once with what the issuer named; a second refusal backs off as a 503.
                    if (attempt == STAMP_TRIES - 1) return backOff(host, prior, AccountAnswer.Unavailable(null), "bad-stamp twice")
                    bits = created.bits
                    day = created.day
                }

                else -> {
                    return backOff(host, prior, created)
                }
            }
        }
        return null
    }

    /** Records the account route's backoff after [answer] (C-7.6-38); always null, for [createAccount]'s returns. */
    private suspend fun backOff(
        host: String,
        prior: FreeState,
        answer: AccountAnswer,
        why: String? = null,
    ): HeldKey? {
        val now = clock()
        val next =
            when (answer) {
                is AccountAnswer.RateLimited -> FreeAccountPolicy.afterRateLimited(prior, answer.retryAfterMs, now)
                is AccountAnswer.Unavailable -> FreeAccountPolicy.afterUnavailable(prior, answer.retryAfterMs, now)
                else -> FreeAccountPolicy.afterNoAnswer(prior, now)
            }
        free?.putFreeState(host, next)
        log("$host: free account not made: ${why ?: describe(answer)}; next ask after ${next.notBefore}")
        return null
    }

    private suspend fun <T> asking(
        host: String,
        block: suspend () -> T,
    ): T {
        _asking.update { it + host }
        try {
            return block()
        } finally {
            _asking.update { it - host }
        }
    }

    private suspend fun ask(
        host: String,
        url: String,
        key: HeldKey,
        prior: KeyState,
    ): KeyState {
        val answer = issuer.swap(key.issuer, key.key)
        val held =
            (answer as? IssuerAnswer.Issued)?.let {
                when (val imported = tickets.import(url, it.ticket)) {
                    is SpoolTickets.Import.Held -> {
                        log("$host: ticket fetched from ${IssuerOrigin.host(key.issuer)} (exp ${imported.ticket.claims.exp})")
                        true
                    }

                    is SpoolTickets.Import.ForAnotherSpool -> {
                        log("$host: the issuer sent a ticket for ${imported.aud}; never held")
                        false
                    }

                    else -> {
                        log("$host: the issuer sent a ticket this build cannot hold")
                        false
                    }
                }
            } ?: false
        val next = TicketRefreshPolicy.afterAnswer(prior, answer, held, clock())
        if (answer !is IssuerAnswer.Issued) log("$host: ticket ask: ${describe(answer)}")
        // The ticket the waiting upload needed is held: the plane presents it, and only a new lock asks again.
        if (held) waiting.remove(host)
        if (key.free && (answer is IssuerAnswer.UnknownKey || answer is IssuerAnswer.Ended)) {
            // C-7.6-37: a free key the issuer no longer knows is forgotten, and replaced no sooner than tomorrow (UTC).
            free?.forgetFreeKey(host, key, FreeAccountPolicy.nextUtcDay(clock()))
            log("$host: free key forgotten")
            return next
        }
        keys.putKeyState(host, key, next)
        return next
    }

    /** Each active relay's host name with one URL for it (two relays on one host share its ticket and its key). */
    private suspend fun activeHosts(): Map<String, String> =
        urls
            .first()
            .sorted()
            .mapNotNull { url -> SpoolUrl.hostname(url)?.let { it to url } }
            .distinctBy { it.first }
            .toMap()

    // The route watch is armed only while a relay is in use, as `MeshManager.watchRoute` arms the plane's: a phone with
    // the plane off registers no connectivity callback for this.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun wakes(): Flow<Unit> =
        merge(
            keys.keys.drop(1).map { },
            tickets.changes.drop(1).map { },
            urls.distinctUntilChanged().drop(1).map { },
            urls.map { it.isNotEmpty() }.distinctUntilChanged().flatMapLatest { armed -> if (armed) routeChanges else emptyFlow() },
            wanted,
            (free?.freeConsent ?: flowOf(emptyMap())).drop(1).map { },
        )

    private fun describe(answer: AccountAnswer): String =
        when (answer) {
            is AccountAnswer.RateLimited -> "rate-limited"
            is AccountAnswer.Unavailable -> "unavailable"
            is AccountAnswer.BadStamp -> "bad-stamp"
            is AccountAnswer.NoAnswer -> "no answer (${answer.reason})"
            is AccountAnswer.Params, is AccountAnswer.Created -> "ok"
        }

    private fun describe(answer: IssuerAnswer): String =
        when (answer) {
            is IssuerAnswer.Issued -> "ok"
            IssuerAnswer.UnknownKey -> KeyAnswer.UNKNOWN_KEY.word
            is IssuerAnswer.Ended -> "${KeyAnswer.ENDED.word} (endsAt ${answer.endsAt})"
            is IssuerAnswer.NoVerdict -> "no verdict (${answer.reason})"
            is IssuerAnswer.Unavailable -> "unavailable (retry after ${answer.retryAfterMs} ms)"
        }

    companion object {
        /** The longest the loop sleeps without re-reading the clock: a `delay` does not count the time a phone is asleep. */
        const val MAX_SLEEP_MS = 15 * 60 * 1_000L

        /**
         * How long an upload counts as waiting after the plane last found it locked: past the 10 min the plane takes to
         * look again at a settled attachment, so a waiting upload stays marked, and short enough that a mark left by
         * an upload that went (or aged out of custody) asks nothing a day later.
         */
        const val WANT_TTL_MS = 60 * 60 * 1_000L

        // C-7.6-36: the first stamp, and one more after a `bad-stamp`.
        private const val STAMP_TRIES = 2

        // 2^(MAX_BITS + 4): a 26-bit stamp is found well inside it unless the hash is broken.
        private const val MINE_ATTEMPTS = 1L shl (AccountStamp.MAX_BITS + 4)
    }
}
