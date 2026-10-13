package app.getknit.knit.mesh.spool

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * When to ask an issuer for a host's ticket, and what an answer leaves behind (C-7.6-27 and C-7.6-28, ADR
 * 2026-10.3sk7). Pure over the phone's wall clock, so every row is a JVM test.
 *
 * - A key that just arrived is asked at once, and its first ask backs off on no verdict until one comes.
 * - After a verdict of `ok` or `ended`, a host is asked when its held ticket is missing, not presentable
 *   ([SpoolTickets.presentable]: refused for good, for another host, or past `exp`), or less than [REFRESH_MARGIN_MS]
 *   from its `exp` — but never before [KeyState.notBefore]: [OK_FLOOR_MS] after a 200, so an account whose `exp` is
 *   capped at its end cannot loop, and [ENDED_RETRY_MS] after a 403, since `account extend` can revive it.
 * - `unknown-key` is never asked again; only a different key ([TicketKeyJournal.putKey]) resets the state.
 * - No verdict backs off from [FIRST_BACKOFF_MS], doubling, to at most [MAX_BACKOFF_MS], keeping the last verdict.
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
            is IssuerAnswer.Issued -> {
                if (held) KeyState(KeyAnswer.OK, answer.endsAt, now + OK_FLOOR_MS, failures = 0) else noVerdict(prior, now)
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
 */
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
) {
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
        if (held.isEmpty()) return null
        val states = keys.keyStates.first()
        return activeHosts()
            .mapNotNull { (host, url) -> held[host]?.let { key -> refresh(host, url, key, states[host] ?: KeyState.FRESH) } }
            .minOrNull()
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
        )

    private fun describe(answer: IssuerAnswer): String =
        when (answer) {
            is IssuerAnswer.Issued -> "ok"
            IssuerAnswer.UnknownKey -> KeyAnswer.UNKNOWN_KEY.word
            is IssuerAnswer.Ended -> "${KeyAnswer.ENDED.word} (endsAt ${answer.endsAt})"
            is IssuerAnswer.NoVerdict -> "no verdict (${answer.reason})"
        }

    companion object {
        /** The longest the loop sleeps without re-reading the clock: a `delay` does not count the time a phone is asleep. */
        const val MAX_SLEEP_MS = 15 * 60 * 1_000L
    }
}
