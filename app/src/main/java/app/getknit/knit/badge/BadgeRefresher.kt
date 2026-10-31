package app.getknit.knit.badge

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.spool.CustomerKey
import app.getknit.knit.mesh.spool.DeviceKey
import app.getknit.knit.mesh.spool.DeviceKeyring
import app.getknit.knit.mesh.spool.HeldKey
import app.getknit.knit.mesh.spool.IssuerAnswer
import app.getknit.knit.mesh.spool.IssuerOrigin
import app.getknit.knit.mesh.spool.KeyAnswer
import app.getknit.knit.mesh.spool.KeyState
import app.getknit.knit.mesh.spool.TicketKeyJournal
import app.getknit.knit.mesh.spool.TicketRefreshPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/**
 * When the holder's phone asks for a badge, and what an answer leaves (docs/SUPPORTER_BADGE.md §5, ADR 2026-10.r99s).
 * Pure over the wall clock, so every row is a JVM test.
 *
 * - Nothing is asked while "Show my supporter badge" is off.
 * - With it on, an ask is due when no held badge verifies, or the one that does expires within [RENEW_MARGIN_MS].
 * - Never before [BadgeAskState.notBefore]: [FLOOR_MS] after any ask, [ENDED_RETRY_MS] after `ended`.
 */
object BadgeRefreshPolicy {
    const val RENEW_MARGIN_MS = 7 * 24 * 60 * 60 * 1_000L
    const val FLOOR_MS = 60 * 60 * 1_000L
    const val ENDED_RETRY_MS = 24 * 60 * 60 * 1_000L
    private const val MS_PER_S = 1_000L

    /** Epoch millis at which to ask next. [validExp] is the latest `exp` (Unix seconds) among the held badges that verify. */
    fun dueAt(
        state: BadgeAskState,
        validExp: Long?,
    ): Long = maxOf(state.notBefore, validExp?.let { it * MS_PER_S - RENEW_MARGIN_MS } ?: 0L)

    /** The held badges and ask state an [answer] at [now] leaves, given the badges [fresh] it yielded (verified, known). */
    fun afterAnswer(
        prior: BadgeAskState,
        held: List<HeldBadge>,
        answer: BadgeAnswer,
        fresh: List<HeldBadge>,
        now: Long,
    ): Pair<List<HeldBadge>, BadgeAskState> =
        when (answer) {
            is BadgeAnswer.Issued -> {
                fresh to BadgeAskState(now + FLOOR_MS, 0)
            }

            is BadgeAnswer.Ended -> {
                emptyList<HeldBadge>() to BadgeAskState(now + ENDED_RETRY_MS, 0)
            }

            // A 401, a 503, a 409 (C-5-8: the account is another phone's), a 426, a `bad-proof` and no answer all keep what
            // is held until it lapses on its own.
            BadgeAnswer.UnknownKey,
            BadgeAnswer.Unavailable,
            is BadgeAnswer.NoVerdict,
            is BadgeAnswer.BoundElsewhere,
            BadgeAnswer.UpgradeRequired,
            BadgeAnswer.BadProof,
            -> {
                held to BadgeAskState(now + FLOOR_MS, prior.failures + 1)
            }
        }
}

/**
 * The holder half of the badge (docs/SUPPORTER_BADGE.md §5): while "Show my supporter badge" is on, it asks the issuer
 * of one held customer key whose account has not ended for this phone's badge, with a fresh `r` each time, when
 * [BadgeRefreshPolicy] says it is due. Runs on the mesh session beside `TicketRefresher` (ADR 2026-10.3sk7), sleeping
 * until the next due ask (at most [maxSleepMs], since a `delay` does not count the time a phone sleeps) and waking on
 * the toggle, a key or a route. A changed set of held badges is what republishes the profile (`MeshManager`'s watcher).
 *
 * [log] gets the issuer's host, answer words and `exp`s, never a key, a `sub`, an `r` or a credential.
 */
class BadgeRefresher(
    private val journal: BadgeJournal,
    private val keys: TicketKeyJournal,
    private val issuer: BadgeIssuer,
    private val selfId: suspend () -> String,
    private val pins: List<PinnedBadgeKey>,
    private val online: () -> Boolean = { true },
    private val routeChanges: Flow<Unit> = emptyFlow(),
    private val allowCleartext: Boolean = false,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val random: (Int) -> ByteArray = { n -> ByteArray(n).also(SecureRandom()::nextBytes) },
    private val log: (String) -> Unit = {},
    private val maxSleepMs: Long = MAX_SLEEP_MS,
    // The customer keys' device keys (docs/VOICE_CALLS.md C-7.6-39, ADR 2026-10.wk3m); null signs nothing.
    private val devices: DeviceKeyring? = null,
) {
    @Volatile
    private var job: Job? = null

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

    /** Asks if an ask is due and possible; returns when the next one falls due (epoch millis), or null for never. */
    suspend fun runOnce(): Long? {
        if (!journal.show.first()) return null
        val (host, key) = issuerKey() ?: return null
        val me = selfId()
        val held = journal.held.first()
        val state = journal.askState.first()
        val due = BadgeRefreshPolicy.dueAt(state, validExp(held, me))
        return when {
            due > clock() -> due
            !online() -> clock() + maxSleepMs
            else -> ask(host, key, me, held, state)
        }
    }

    private suspend fun ask(
        host: String,
        key: HeldKey,
        me: String,
        held: List<HeldBadge>,
        prior: BadgeAskState,
    ): Long {
        val r = random(Badges.R_BYTES)
        val parkedBy =
            keys.keyStates
                .first()[host]
                ?.parkedBy
                .orEmpty()
        val device = devices?.forKey(host, key.key, avoid = parkedBy) ?: DeviceKey.Unmade
        val ready = device as? DeviceKey.Ready
        val signer = ready?.signer
        // A Keystore that refused sends nothing: an unsigned request to a bound account would park it (ADR 2026-10.wk3m).
        val answer =
            if (device is DeviceKey.Refused) {
                BadgeAnswer.NoVerdict("keystore")
            } else {
                issuer.issueBadge(key.issuer, key.key, Badges.subject(NodeId.toBytes(me), r), signer)
            }
        // C-5-8 reads a 409 as the ticket swap does: the key is parked for the ticket refresher too, until a move.
        if (answer is BadgeAnswer.BoundElsewhere) {
            val state = keys.keyStates.first()[host] ?: KeyState.FRESH
            val parked = IssuerAnswer.BoundElsewhere(answer.movableAt)
            keys.putKeyState(
                host,
                key,
                TicketRefreshPolicy.afterAnswer(state, parked, held = false, now = clock(), signedBy = ready?.id),
            )
        }
        // A 200 to a proof says the issuer bound that pair (ADR 2026-10.wk3m).
        if (answer is BadgeAnswer.Issued && ready != null) devices?.accepted(host, key.key, ready.id)
        val fresh =
            (answer as? BadgeAnswer.Issued)
                ?.badges
                ?.asSequence()
                ?.mapNotNull { Badges.wrapperOf(it) }
                ?.map { HeldBadge(it, r) }
                ?.filter { Badges.verify(it.wrapper, it.r, me, clock() / MS_PER_S, pins) != null }
                ?.distinctBy { Badges.claimsOf(it.wrapper)?.b }
                ?.take(MAX_HELD)
                ?.toList()
                .orEmpty()
        val (nextHeld, nextState) = BadgeRefreshPolicy.afterAnswer(prior, held, answer, fresh, clock())
        if (nextHeld != held) journal.putHeld(nextHeld, nextState) else journal.putAskState(nextState)
        log("badge ask at ${IssuerOrigin.host(key.issuer)}: ${describe(answer, fresh)}")
        return BadgeRefreshPolicy.dueAt(nextState, validExp(nextHeld, me))
    }

    /**
     * The key to ask with and its relay host: the first held customer key, by relay host name, whose issuer passes
     * [IssuerOrigin] and whose last ticket answer is none of `ended`, `unknown-key` and `bound-elsewhere`. Null when none,
     * so a phone with no live account here never asks. A free key (C-7.6-37) never does: a badge says its holder supports
     * Knit, and a free account pays nothing.
     */
    private suspend fun issuerKey(): Pair<String, HeldKey>? {
        val states = keys.keyStates.first()
        return keys.keys
            .first()
            .toSortedMap()
            .entries
            .firstOrNull { (host, key) ->
                !key.free &&
                    IssuerOrigin.isAcceptable(key.issuer, allowCleartext) &&
                    CustomerKey.isWellFormed(key.key) &&
                    states[host]?.answer.let { it != KeyAnswer.ENDED && it != KeyAnswer.UNKNOWN_KEY && it != KeyAnswer.BOUND_ELSEWHERE }
            }?.toPair()
    }

    private fun validExp(
        held: List<HeldBadge>,
        me: String,
    ): Long? = held.mapNotNull { Badges.verify(it.wrapper, it.r, me, clock() / MS_PER_S, pins)?.exp }.maxOrNull()

    private fun wakes(): Flow<Unit> =
        merge(
            journal.show.drop(1).map { },
            keys.keys.drop(1).map { },
            keys.keyStates.drop(1).map { },
            routeChanges,
        )

    private fun describe(
        answer: BadgeAnswer,
        fresh: List<HeldBadge>,
    ): String =
        when (answer) {
            is BadgeAnswer.Issued -> "ok, ${fresh.size} kept (exp ${fresh.mapNotNull { Badges.claimsOf(it.wrapper)?.exp }})"
            BadgeAnswer.UnknownKey -> "unknown-key"
            is BadgeAnswer.Ended -> "ended (endsAt ${answer.endsAt})"
            BadgeAnswer.Unavailable -> "unavailable"
            is BadgeAnswer.NoVerdict -> "no verdict (${answer.reason})"
            is BadgeAnswer.BoundElsewhere -> "bound-elsewhere (movableAt ${answer.movableAt})"
            BadgeAnswer.UpgradeRequired -> IssuerAnswer.UPGRADE_REQUIRED
            BadgeAnswer.BadProof -> IssuerAnswer.BAD_PROOF
        }

    companion object {
        const val MAX_SLEEP_MS = 15 * 60 * 1_000L
        const val MAX_HELD = 4
        private const val MS_PER_S = 1_000L
    }
}
