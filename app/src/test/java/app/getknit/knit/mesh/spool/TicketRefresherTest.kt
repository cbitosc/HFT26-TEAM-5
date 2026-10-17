package app.getknit.knit.mesh.spool

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-7.6-27 and C-7.6-28 (ADR 2026-10.3sk7) on plain JVM: [TicketRefreshPolicy] row by row over a fake clock, then
 * [TicketRefresher.runOnce] against an in-memory key store, the real [SpoolTickets] over [SpoolTicketsTest.MemoryJournal]
 * and a scripted issuer — what it asks, when, with what, what it holds, and that no log line carries a key or a ticket.
 */
class TicketRefresherTest {
    /** The key store in memory, with [TicketKeyJournal]'s replace and stale-answer rules. */
    class MemoryKeyJournal(
        private val tickets: SpoolTicketsTest.MemoryJournal? = null,
    ) : TicketKeyJournal {
        val held = MutableStateFlow<Map<String, HeldKey>>(emptyMap())
        val states = MutableStateFlow<Map<String, KeyState>>(emptyMap())
        override val keys: Flow<Map<String, HeldKey>> = held
        override val keyStates: Flow<Map<String, KeyState>> = states

        override suspend fun putKey(
            host: String,
            key: HeldKey,
        ) {
            if (held.value[host] == key) return
            held.value += host to key
            states.value -= host
        }

        override suspend fun putKeyState(
            host: String,
            key: HeldKey,
            state: KeyState,
        ) {
            if (held.value[host] == key) states.value += host to state
        }

        override suspend fun forgetHost(host: String) {
            held.value -= host
            states.value -= host
            tickets?.let { it.state.value -= host }
        }
    }

    /** An issuer that answers from a script, recording every ask. */
    private class ScriptedIssuer : TicketIssuer {
        val asks = mutableListOf<Pair<String, String>>()
        var next: () -> IssuerAnswer = { IssuerAnswer.NoVerdict("unscripted") }

        override suspend fun swap(
            origin: String,
            key: String,
        ): IssuerAnswer {
            asks += origin to key
            return next()
        }
    }

    private var now = 1_800_000_100_000L
    private val ticketJournal = SpoolTicketsTest.MemoryJournal()
    private val tickets = SpoolTickets(ticketJournal) { now }
    private val keys = MemoryKeyJournal(ticketJournal)
    private val issuer = ScriptedIssuer()
    private val urls = MutableStateFlow(setOf(URL))
    private var online = true
    private val logs = mutableListOf<String>()

    private fun refresher(allowCleartext: Boolean = false) =
        TicketRefresher(
            keys = keys,
            tickets = tickets,
            issuer = issuer,
            urls = urls,
            online = { online },
            allowCleartext = allowCleartext,
            clock = { now },
            log = { logs += it },
        )

    private fun issued(
        aud: String = HOST,
        expS: Long = now / 1_000 + 25 * 3_600,
        endsAt: Long = now + 30 * DAY_MS,
        sigByte: Byte = 7,
    ) = IssuerAnswer.Issued(SpoolTicketsTest.ticket(aud = aud, nbf = now / 1_000, exp = expS, sigByte = sigByte).text, endsAt)

    // ── the schedule, pure ────────────────────────────────────────────────────────────────────────────

    @Test
    fun aKeyThatJustArrivedIsDueAtOnceWhateverTicketIsHeld() {
        assertEquals(0L, TicketRefreshPolicy.dueAt(KeyState.FRESH, presentableExp = null))
        assertEquals(0L, TicketRefreshPolicy.dueAt(KeyState.FRESH, presentableExp = now / 1_000 + 20 * 3_600))
    }

    @Test
    fun anOkKeyIsDueSixHoursBeforeItsTicketsExpOrAtOnceWithNoPresentableTicket() {
        val ok = KeyState(KeyAnswer.OK, notBefore = now - 1)
        val exp = now / 1_000 + 20 * 3_600
        assertEquals(exp * 1_000 - TicketRefreshPolicy.REFRESH_MARGIN_MS, TicketRefreshPolicy.dueAt(ok, exp))
        assertEquals(now - 1, TicketRefreshPolicy.dueAt(ok, presentableExp = null))
    }

    @Test
    fun theHourFloorAfterA200HoldsEvenWhenTheTicketIsCappedShort() {
        // An account a day from its end gets an exp capped at that end: inside the 6 h margin from the start, so
        // without the floor the phone would ask again the moment the answer landed.
        val issued = IssuerAnswer.Issued("knit-ticket:v1:x", now + 3_600_000)
        val after = TicketRefreshPolicy.afterAnswer(KeyState.FRESH, issued, held = true, now = now)
        assertEquals(KeyAnswer.OK, after.answer)
        assertEquals(now + TicketRefreshPolicy.OK_FLOOR_MS, TicketRefreshPolicy.dueAt(after, presentableExp = now / 1_000 + 3_600))
    }

    @Test
    fun anUnknownKeyIsNeverAskedAgain() {
        val after = TicketRefreshPolicy.afterAnswer(KeyState(KeyAnswer.OK, endsAt = 5), IssuerAnswer.UnknownKey, held = false, now = now)
        assertEquals(KeyAnswer.UNKNOWN_KEY, after.answer)
        assertEquals(5L, after.endsAt)
        assertNull(TicketRefreshPolicy.dueAt(after, presentableExp = null))
    }

    @Test
    fun anEndedAccountIsRecordedAndAskedAgainNoSoonerThanADayLater() {
        val after = TicketRefreshPolicy.afterAnswer(KeyState.FRESH, IssuerAnswer.Ended(now - DAY_MS), held = false, now = now)
        assertEquals(KeyAnswer.ENDED, after.answer)
        assertEquals(now - DAY_MS, after.endsAt)
        assertEquals(now + TicketRefreshPolicy.ENDED_RETRY_MS, TicketRefreshPolicy.dueAt(after, presentableExp = null))
    }

    @Test
    fun noVerdictBacksOffFromAMinuteDoublingToAnHourAndKeepsTheLastVerdict() {
        assertEquals(listOf(1L, 2, 4, 8, 16, 32, 60, 60, 60).map { it * 60_000 }, (1..9).map(TicketRefreshPolicy::backoff))
        var state = KeyState(KeyAnswer.OK, endsAt = 9)
        val waits = mutableListOf<Long>()
        repeat(8) {
            state = TicketRefreshPolicy.afterAnswer(state, IssuerAnswer.NoVerdict("timeout"), held = false, now = now)
            waits += state.notBefore - now
        }
        assertEquals(listOf(1L, 2, 4, 8, 16, 32, 60, 60).map { it * 60_000 }, waits)
        assertEquals(KeyAnswer.OK, state.answer)
        assertEquals(9L, state.endsAt)
        val recovered = TicketRefreshPolicy.afterAnswer(state, IssuerAnswer.Issued("t", 10), held = true, now = now)
        assertEquals(0, recovered.failures)
    }

    @Test
    fun a200WhoseTicketWasNotHeldIsNoVerdictButWaitsOutTheFloor() {
        // Contract revision 1: asking again at once would fetch the same unusable ticket, and a free key would loop.
        val after = TicketRefreshPolicy.afterAnswer(KeyState.FRESH, IssuerAnswer.Issued("t", 10), held = false, now = now)
        assertNull(after.answer)
        assertEquals(1, after.failures)
        assertEquals(now + TicketRefreshPolicy.OK_FLOOR_MS, TicketRefreshPolicy.dueAt(after, null))
    }

    // ── the refresher, against the stores ─────────────────────────────────────────────────────────────

    @Test
    fun aNewKeyIsAskedAtOnceAndItsTicketIsHeldForTheRelaysHost() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            issuer.next = { issued() }
            val next = refresher().runOnce()
            assertEquals(listOf(ISSUER to KEY), issuer.asks)
            assertTrue(tickets.presentable(URL) != null)
            assertEquals(
                KeyAnswer.OK,
                keys.states.value
                    .getValue(HOST)
                    .answer,
            )
            assertEquals(
                now + 30 * DAY_MS,
                keys.states.value
                    .getValue(HOST)
                    .endsAt,
            )
            // Next: 6 h before a 25 h ticket's exp.
            assertEquals(now + 19 * 3_600_000L, next)
        }

    @Test
    fun nothingIsAskedBeforeItIsDueAndAgainOnceTheTicketIsSixHoursFromExp() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            issuer.next = { issued(sigByte = 1) }
            val r = refresher()
            r.runOnce()
            now += 18 * 3_600_000L
            r.runOnce()
            assertEquals(1, issuer.asks.size)
            now += 3_600_000L + 1
            issuer.next = { issued(sigByte = 2) }
            r.runOnce()
            assertEquals(2, issuer.asks.size)
        }

    @Test
    fun aRefusedTicketIsReplacedButNoSoonerThanTheHourFloor() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            issuer.next = { issued(sigByte = 1) }
            val r = refresher()
            r.runOnce()
            val held = tickets.presentable(URL)!!
            tickets.answered(URL, held, refusal = "signature")
            assertNull(tickets.presentable(URL))
            now += 30 * 60_000L
            r.runOnce()
            assertEquals(1, issuer.asks.size)
            now += 30 * 60_000L
            issuer.next = { issued(sigByte = 2) }
            r.runOnce()
            assertEquals(2, issuer.asks.size)
            assertTrue(tickets.presentable(URL) != null)
        }

    @Test
    fun aTicketForAnotherHostIsNeverHeld() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            issuer.next = { issued(aud = "elsewhere.example") }
            refresher().runOnce()
            assertNull(tickets.view(URL))
            assertTrue(ticketJournal.state.value.isEmpty())
            assertEquals(
                1,
                keys.states.value
                    .getValue(HOST)
                    .failures,
            )
        }

    @Test
    fun anUnknownKeyParksUntilADifferentKeyArrives() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            issuer.next = { IssuerAnswer.UnknownKey }
            val r = refresher()
            assertNull(r.runOnce())
            now += 7 * DAY_MS
            r.runOnce()
            assertEquals(1, issuer.asks.size)
            keys.putKey(HOST, HeldKey(ISSUER, KEY)) // the same key again: still parked
            r.runOnce()
            assertEquals(1, issuer.asks.size)
            keys.putKey(HOST, HeldKey(ISSUER, OTHER_KEY))
            issuer.next = { issued() }
            r.runOnce()
            assertEquals(ISSUER to OTHER_KEY, issuer.asks.last())
        }

    @Test
    fun anEndedAccountKeepsItsKeyAndIsAskedDaily() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            issuer.next = { IssuerAnswer.Ended(now - 1) }
            val r = refresher()
            assertEquals(now + DAY_MS, r.runOnce())
            assertEquals(HeldKey(ISSUER, KEY), keys.held.value[HOST])
            now += DAY_MS - 1
            r.runOnce()
            assertEquals(1, issuer.asks.size)
            now += 1
            r.runOnce()
            assertEquals(2, issuer.asks.size)
        }

    @Test
    fun noRouteAsksNobodyAndWaitsForOne() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            online = false
            assertEquals(now + TicketRefresher.MAX_SLEEP_MS, refresher().runOnce())
            assertTrue(issuer.asks.isEmpty())
            assertEquals(null, keys.states.value[HOST])
        }

    @Test
    fun withThePlaneOffOrTheRelayParkedNothingIsAsked() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            urls.value = emptySet()
            assertNull(refresher().runOnce())
            assertTrue(issuer.asks.isEmpty())
        }

    @Test
    fun aKeyGoesOnlyToAnHttpsOriginUnlessTheBuildAllowsCleartext() =
        runTest {
            keys.putKey(HOST, HeldKey("http://127.0.0.1:3000", KEY))
            issuer.next = { issued() }
            refresher(allowCleartext = false).runOnce()
            assertTrue(issuer.asks.isEmpty())
            refresher(allowCleartext = true).runOnce()
            assertEquals(listOf("http://127.0.0.1:3000" to KEY), issuer.asks)
        }

    @Test
    fun forgettingTheHostForgetsTheKeyItsStateAndItsTicket() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            issuer.next = { issued() }
            refresher().runOnce()
            keys.forgetHost(HOST)
            assertTrue(keys.held.value.isEmpty())
            assertTrue(keys.states.value.isEmpty())
            assertNull(tickets.view(URL))
        }

    @Test
    fun noLogLineCarriesTheKeyOrTheTicket() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            val answer = issued()
            val answers = ArrayDeque(listOf(answer, IssuerAnswer.NoVerdict("timeout"), IssuerAnswer.Ended(1), IssuerAnswer.UnknownKey))
            issuer.next = { answers.removeFirst() }
            val r = refresher()
            r.runOnce()
            repeat(3) {
                tickets.clear(URL)
                now += 2 * DAY_MS
                r.runOnce()
            }
            assertEquals(4, issuer.asks.size)
            val body = answer.ticket.removePrefix(HeldTicket.PREFIX)
            for (line in logs) {
                assertFalse(line, line.contains(KEY.removePrefix(CustomerKey.PREFIX).take(12)))
                assertFalse(line, line.contains(body.take(16)))
                assertFalse(line, line.contains("fixture-token"))
            }
            assertFalse(answer.toString().contains(body.take(16)))
            assertFalse(HeldKey(ISSUER, KEY).toString().contains(KEY.removePrefix(CustomerKey.PREFIX).take(12)))
        }

    private companion object {
        const val HOST = "lab.example.org"
        const val URL = "wss://lab.example.org/spool/v1?k=fixture-token"
        const val ISSUER = "https://anywhere.example.org"
        const val KEY = "kak1_FBsiKTA3PkVMU1phaG92fYSLkpmgp661vMPK0djf5u0"
        const val OTHER_KEY = "kak1_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val DAY_MS = 86_400_000L
    }
}
