package app.getknit.knit

import app.getknit.knit.mesh.wifiaware.NanWatchdogPolicy
import app.getknit.knit.mesh.wifiaware.NanWatchdogPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [NanWatchdogPolicy] — the pure two-tier wedge episode-clock decision. */
class NanWatchdogPolicyTest {
    // Production tuning constants (WifiAwareTransport companion), passed explicitly here.
    private val responderRefreshMs = 45_000L
    private val reattachCooldownMs = 20_000L
    private val wedgeRestartMs = 180_000L
    private val maxResponderRefreshes = 3
    private val wedgeCheckMs = 30_000L // the watchdog's own poll period — longer than the cooldown, on purpose
    private val episodeStart = 1_000_000L

    /** decide() with an owed, running episode by default; each test overrides the axis it exercises. */
    @Suppress("LongParameterList") // mirrors the policy's own (suppressed) parameter list
    private fun decide(
        healthy: Boolean = true,
        reachableOwed: Boolean = true,
        corroboratedOwed: Boolean = true,
        visible: Boolean = false,
        callLive: Boolean = false,
        now: Long,
        syncOwedSince: Long = episodeStart,
        lastLinkOrAcceptAt: Long = 0L, // < episodeStart ⇒ no progress
        lastReattachAt: Long = 0L,
        lastRestartAt: Long = 0L,
        responderRefreshes: Int = 0,
    ) = NanWatchdogPolicy.decide(
        healthy = healthy,
        reachableOwed = reachableOwed,
        corroboratedOwed = corroboratedOwed,
        visible = visible,
        callLive = callLive,
        now = now,
        syncOwedSince = syncOwedSince,
        lastLinkOrAcceptAt = lastLinkOrAcceptAt,
        lastReattachAt = lastReattachAt,
        lastRestartAt = lastRestartAt,
        responderRefreshes = responderRefreshes,
        responderRefreshMs = responderRefreshMs,
        reattachCooldownMs = reattachCooldownMs,
        wedgeRestartMs = wedgeRestartMs,
        maxResponderRefreshes = maxResponderRefreshes,
    )

    @Test
    fun unhealthyClearsTheEpisode() {
        assertEquals(
            NanWatchdogPolicy.Decision(Action.None, 0L, 0),
            decide(healthy = false, now = episodeStart + 200_000L, responderRefreshes = 2),
        )
    }

    @Test
    fun nothingOwedClearsTheEpisode() {
        assertEquals(
            NanWatchdogPolicy.Decision(Action.None, 0L, 0),
            decide(reachableOwed = false, now = episodeStart + 200_000L, responderRefreshes = 2),
        )
    }

    @Test
    fun firstOwedTickStartsTheEpisodeAtNow() {
        val now = 1_234_567L
        assertEquals(NanWatchdogPolicy.Decision(Action.None, now, 0), decide(now = now, syncOwedSince = 0L))
    }

    @Test
    fun aLinkSinceTheEpisodeBeganRestartsTheClock() {
        val now = episodeStart + 50_000L
        // lastLinkOrAcceptAt >= syncOwedSince ⇒ progress ⇒ re-anchor the episode to now, take no action.
        assertEquals(NanWatchdogPolicy.Decision(Action.None, now, 0), decide(now = now, lastLinkOrAcceptAt = episodeStart))
    }

    @Test
    fun progressRefundsTheRefreshBudget() {
        val now = episodeStart + 50_000L
        // A link formed, so the next wedge episode gets a full set of Tier-1 attempts again.
        assertEquals(
            NanWatchdogPolicy.Decision(Action.None, now, 0),
            decide(now = now, lastLinkOrAcceptAt = episodeStart, responderRefreshes = maxResponderRefreshes),
        )
    }

    @Test
    fun tier1FiresAtTheResponderRefreshBoundaryWithCooldownElapsed() {
        val now = episodeStart + responderRefreshMs // owedFor == boundary (>=)
        assertEquals(NanWatchdogPolicy.Decision(Action.RefreshResponder, episodeStart, 1), decide(now = now))
    }

    @Test
    fun tier1DoesNotFireJustBelowTheBoundary() {
        val now = episodeStart + responderRefreshMs - 1
        assertEquals(NanWatchdogPolicy.Decision(Action.None, episodeStart, 0), decide(now = now))
    }

    @Test
    fun tier1BlockedByCooldownHoldsWhileBelowRestart() {
        val now = episodeStart + 50_000L // past refresh, below restart
        assertEquals(
            NanWatchdogPolicy.Decision(Action.None, episodeStart, 0),
            decide(now = now, lastReattachAt = now - (reattachCooldownMs - 1)), // cooldown NOT elapsed
        )
    }

    @Test
    fun tier1CooldownBlockedFallsThroughToTier2() {
        val now = episodeStart + wedgeRestartMs // owedFor == restart boundary
        assertEquals(
            NanWatchdogPolicy.Decision(Action.RestartProcess, episodeStart, 0),
            decide(now = now, lastReattachAt = now - 5_000L), // tier-1 cooldown blocks; tier-2 eligible + corroborated
        )
    }

    @Test
    fun tier1StopsAtTheEpisodeCap() {
        val now = episodeStart + 150_000L // well past refresh, cooldown elapsed, still below restart
        assertEquals(
            NanWatchdogPolicy.Decision(Action.None, episodeStart, maxResponderRefreshes),
            decide(now = now, responderRefreshes = maxResponderRefreshes),
        )
    }

    @Test
    fun tier1CapLetsTier2Escalate() {
        val now = episodeStart + wedgeRestartMs
        // Cooldown long elapsed — only the cap keeps Tier 1 quiet, and that is what makes Tier 2 reachable.
        assertEquals(
            NanWatchdogPolicy.Decision(Action.RestartProcess, episodeStart, maxResponderRefreshes),
            decide(now = now, responderRefreshes = maxResponderRefreshes, lastReattachAt = episodeStart + 105_000L),
        )
    }

    /**
     * The livelock regression (three-Pixel capture, 2026-09-07). Polling at [wedgeCheckMs] — which is longer
     * than [reattachCooldownMs], so the cooldown never blocks — Tier 1 used to fire on every single tick
     * forever, and Tier 2 was therefore never reached. Walk the real cadence and assert the escalation:
     * exactly [maxResponderRefreshes] cycles, then quiet, then the corroborated restart.
     */
    @Test
    fun tier1CannotCycleForeverAndTier2IsReached() {
        var refreshes = 0
        var lastReattachAt = 0L
        val fired = mutableListOf<Long>()
        var restartedAt: Long? = null
        var now = episodeStart
        while (now <= episodeStart + wedgeRestartMs) {
            val d = decide(now = now, lastReattachAt = lastReattachAt, responderRefreshes = refreshes)
            refreshes = d.nextResponderRefreshes
            when (d.action) {
                Action.RefreshResponder -> {
                    fired += now - episodeStart
                    lastReattachAt = now
                }

                Action.RestartProcess -> {
                    restartedAt = now - episodeStart
                }

                Action.None, Action.DeferRestart -> {}
            }
            now += wedgeCheckMs
        }
        // Ticks land on wedgeCheckMs multiples, so the first eligible one is 60 s in, not the 45 s boundary.
        assertEquals(listOf(60_000L, 90_000L, 120_000L), fired)
        assertEquals(wedgeRestartMs, restartedAt)
    }

    @Test
    fun tier2GatedOffWhenUncorroborated() {
        val now = episodeStart + wedgeRestartMs
        assertEquals(
            NanWatchdogPolicy.Decision(Action.None, episodeStart, 0),
            decide(now = now, corroboratedOwed = false, lastReattachAt = now - 5_000L),
        )
    }

    @Test
    fun tier2RateLimitedHolds() {
        val now = episodeStart + wedgeRestartMs
        assertEquals(
            NanWatchdogPolicy.Decision(Action.None, episodeStart, 0),
            decide(now = now, lastReattachAt = now - 5_000L, lastRestartAt = now - (wedgeRestartMs - 1)),
        )
    }

    @Test
    fun inUseDefersADueTier2AndKeepsTheEpisode() {
        val now = episodeStart + wedgeRestartMs
        assertEquals(
            NanWatchdogPolicy.Decision(Action.DeferRestart, episodeStart, maxResponderRefreshes),
            decide(now = now, visible = true, responderRefreshes = maxResponderRefreshes, lastReattachAt = episodeStart + 120_000L),
        )
    }

    @Test
    fun inUseDefersNothingThatWasNotDue() {
        // Below the restart boundary, uncorroborated or rate-limited, a held kill is plain None: the transport logs
        // a deferral only for a kill it would otherwise have run.
        val due = episodeStart + wedgeRestartMs
        assertEquals(Action.None, decide(now = due - 1, visible = true, lastReattachAt = due - 5_000L).action)
        assertEquals(Action.None, decide(now = due, visible = true, corroboratedOwed = false, lastReattachAt = due - 5_000L).action)
        assertEquals(
            Action.None,
            decide(now = due, visible = true, lastReattachAt = due - 5_000L, lastRestartAt = due - (wedgeRestartMs - 1)).action,
        )
    }

    @Test
    fun inUseNeverBlocksTier1() {
        assertEquals(
            NanWatchdogPolicy.Decision(Action.RefreshResponder, episodeStart, 1),
            decide(now = episodeStart + responderRefreshMs, visible = true),
        )
        // ...nor a Tier 1 that is still owed when the Tier-2 boundary arrives (the cooldown-elapsed case).
        assertEquals(
            NanWatchdogPolicy.Decision(Action.RefreshResponder, episodeStart, 3),
            decide(now = episodeStart + wedgeRestartMs, visible = true, responderRefreshes = 2),
        )
    }

    /**
     * The real cadence with Knit on screen past the Tier-2 boundary (P9, 2026-10-20): the three Tier-1 cycles run as
     * without the hold, every due tick defers, the clock and the count survive every deferral, and the first tick after
     * the screen goes away restarts.
     */
    @Test
    fun aDeferredTier2FiresOnTheFirstTickAfterUseEnds() {
        var refreshes = 0
        var lastReattachAt = 0L
        var since = episodeStart
        val fired = mutableListOf<Long>()
        val deferred = mutableListOf<Long>()
        var restartedAt: Long? = null
        val useEndsAt = episodeStart + wedgeRestartMs + 3 * wedgeCheckMs
        var now = episodeStart
        while (restartedAt == null && now <= useEndsAt + wedgeCheckMs) {
            val d =
                decide(
                    now = now,
                    syncOwedSince = since,
                    visible = now < useEndsAt,
                    lastReattachAt = lastReattachAt,
                    responderRefreshes = refreshes,
                )
            since = d.nextSyncOwedSince
            refreshes = d.nextResponderRefreshes
            when (d.action) {
                Action.RefreshResponder -> {
                    fired += now - episodeStart
                    lastReattachAt = now
                }

                Action.DeferRestart -> {
                    deferred += now - episodeStart
                }

                Action.RestartProcess -> {
                    restartedAt = now - episodeStart
                }

                Action.None -> {}
            }
            now += wedgeCheckMs
        }
        assertEquals(listOf(60_000L, 90_000L, 120_000L), fired)
        assertEquals(listOf(180_000L, 210_000L, 240_000L), deferred)
        assertEquals(episodeStart, since)
        assertEquals(maxResponderRefreshes, refreshes)
        assertEquals(useEndsAt - episodeStart, restartedAt)
    }

    @Test
    fun aLiveCallAloneHoldsADueTier2() {
        // Off screen with a call running (a walkie in a pocket): the kill would end the call, so it is held as surely as
        // a lit screen holds it. Fails if the call stops reaching the hold.
        val now = episodeStart + wedgeRestartMs
        val held = decide(now = now, callLive = true, responderRefreshes = maxResponderRefreshes, lastReattachAt = now - 5_000L)
        assertEquals(NanWatchdogPolicy.Decision(Action.DeferRestart, episodeStart, maxResponderRefreshes), held)
        assertEquals(
            "neither on screen nor in a call: the due kill runs",
            Action.RestartProcess,
            decide(now = now, responderRefreshes = maxResponderRefreshes, lastReattachAt = now - 5_000L).action,
        )
    }

    @Test
    fun tier2NotesLogEachLineOncePerEpisode() {
        val notes = NanWatchdogPolicy.Tier2Notes()
        notes.episode(episodeStart)
        assertTrue(notes.shouldLogInUse())
        assertTrue(notes.shouldLogReclaim())
        repeat(5) {
            notes.episode(episodeStart) // the same episode, tick after tick
            assertFalse(notes.shouldLogInUse())
            assertFalse(notes.shouldLogReclaim())
        }
    }

    @Test
    fun tier2NotesReArmOnAnEpisodeRestart() {
        // A link mid-episode restarts the clock at a later now (decide's progress branch): a fresh episode.
        val notes = NanWatchdogPolicy.Tier2Notes()
        notes.episode(episodeStart)
        assertTrue(notes.shouldLogInUse())
        assertTrue(notes.shouldLogReclaim())
        notes.episode(episodeStart + 90_000L)
        assertTrue(notes.shouldLogInUse())
        assertTrue(notes.shouldLogReclaim())
        assertFalse(notes.shouldLogInUse())
    }

    @Test
    fun tier2NotesReArmWhenAnEpisodeEnds() {
        // Convergence or an unhealthy tick ends the episode (nextSyncOwedSince = 0); a later episode starting at the very
        // same instant's clock value still gets its lines, because the end in between re-armed them.
        val notes = NanWatchdogPolicy.Tier2Notes()
        notes.episode(episodeStart)
        assertTrue(notes.shouldLogInUse())
        assertTrue(notes.shouldLogReclaim())
        notes.episode(0L)
        assertTrue(notes.shouldLogInUse())
        assertTrue(notes.shouldLogReclaim())
        notes.episode(episodeStart)
        assertTrue(notes.shouldLogInUse())
        assertTrue(notes.shouldLogReclaim())
    }

    @Test
    fun tier2NotesLinesAreIndependent() {
        // The screen hold fires for minutes before the screen goes off and the re-claim refusal takes over: the first
        // line's spend must leave the second's armed, and the reverse.
        val notes = NanWatchdogPolicy.Tier2Notes()
        notes.episode(episodeStart)
        assertTrue(notes.shouldLogInUse())
        assertFalse(notes.shouldLogInUse())
        assertTrue("the in-use line spent nothing of the re-claim line", notes.shouldLogReclaim())
        assertFalse(notes.shouldLogReclaim())

        val other = NanWatchdogPolicy.Tier2Notes()
        other.episode(episodeStart)
        assertTrue(other.shouldLogReclaim())
        assertTrue("the re-claim line spent nothing of the in-use line", other.shouldLogInUse())
    }

    /**
     * The latch fed by the real cadence: decide's episode clock, every tick, through the screen hold, the screen going
     * off onto a refused re-claim, a link that restarts the episode, and the episode's end.
     */
    @Test
    fun tier2NotesOverTheRealCadence() {
        val notes = NanWatchdogPolicy.Tier2Notes()
        var since = 0L
        var refreshes = 0
        var lastReattachAt = 0L
        var lastLink = 0L
        val inUseLines = mutableListOf<Long>()
        val reclaimLines = mutableListOf<Long>()
        val linkAt = episodeStart + 300_000L
        var now = episodeStart
        while (now <= episodeStart + 720_000L) {
            val visible = now < episodeStart + 240_000L
            if (now == linkAt) lastLink = now - 1 // a link just before this tick: progress restarts the episode now
            val d =
                decide(
                    now = now,
                    syncOwedSince = since,
                    visible = visible,
                    lastLinkOrAcceptAt = lastLink,
                    lastReattachAt = lastReattachAt,
                    responderRefreshes = refreshes,
                )
            since = d.nextSyncOwedSince
            refreshes = d.nextResponderRefreshes
            notes.episode(since)
            when (d.action) {
                Action.RefreshResponder -> {
                    lastReattachAt = now
                }

                Action.DeferRestart -> {
                    if (notes.shouldLogInUse()) inUseLines += now - episodeStart
                }

                // The transport's refused re-claim: off screen on a non-exempt phone, the kill never runs.
                Action.RestartProcess -> {
                    if (notes.shouldLogReclaim()) reclaimLines += now - episodeStart
                }

                Action.None -> {}
            }
            now += wedgeCheckMs
        }
        // First episode: one in-use line at the Tier-2 boundary, one re-claim line once the screen is off. The link
        // restarts the episode at 300 s, whose own Tier 2 comes due 180 s later and gets its own re-claim line.
        assertEquals(listOf(180_000L), inUseLines)
        assertEquals(listOf(240_000L, 480_000L), reclaimLines)
    }
}
