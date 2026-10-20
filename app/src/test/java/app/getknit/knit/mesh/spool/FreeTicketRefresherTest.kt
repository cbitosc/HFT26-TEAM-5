package app.getknit.knit.mesh.spool

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The refresher's free half (docs/VOICE_CALLS.md C-7.6-27 amended, C-7.6-33 to C-7.6-38) over plain JVM: a waiting upload
 * as the only reason a free key asks, the account exchange against a scripted issuer that answers as the coordinator's
 * route does (knit-spool-coordinator `feat/free-tickets` @ `5883035`, contract revision 3), the backoff, and a free key's
 * life — made, used until `exp`, forgotten after a 401 or a 403, replaced by a customer key.
 */
class FreeTicketRefresherTest {
    /** [TicketKeyJournal] and [FreeKeyJournal] in memory, with `TicketKeyStore`'s one-slot-per-host rules. */
    private class MemoryStore(
        private val tickets: SpoolTicketsTest.MemoryJournal,
    ) : TicketKeyJournal,
        FreeKeyJournal {
        val held = MutableStateFlow<Map<String, HeldKey>>(emptyMap())
        val states = MutableStateFlow<Map<String, KeyState>>(emptyMap())
        val issuers = MutableStateFlow<Map<String, String>>(emptyMap())
        val consent = MutableStateFlow<Map<String, Boolean>>(emptyMap())
        val free = MutableStateFlow<Map<String, FreeState>>(emptyMap())
        override val keys: Flow<Map<String, HeldKey>> = held
        override val keyStates: Flow<Map<String, KeyState>> = states
        override val freeIssuers: Flow<Map<String, String>> = issuers
        override val freeConsent: Flow<Map<String, Boolean>> = consent
        override val freeStates: Flow<Map<String, FreeState>> = free

        override suspend fun putKey(
            host: String,
            key: HeldKey,
        ) {
            if (held.value[host] == key) return
            held.value += host to key
            states.value -= host
            if (key.free) free.value -= host
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
            tickets.state.value -= host
        }

        override suspend fun setFreeIssuer(
            host: String,
            origin: String?,
        ) {
            issuers.value = if (origin == null) issuers.value - host else issuers.value + (host to origin)
        }

        override suspend fun setFreeConsent(
            host: String,
            allowed: Boolean?,
        ) {
            consent.value = if (allowed == null) consent.value - host else consent.value + (host to allowed)
            if (allowed == false && held.value[host]?.free == true) {
                held.value -= host
                states.value -= host
                tickets.state.value -= host
            }
        }

        override suspend fun putFreeState(
            host: String,
            state: FreeState,
        ) {
            free.value += host to state
        }

        override suspend fun forgetFreeKey(
            host: String,
            key: HeldKey,
            notBefore: Long,
        ) {
            if (!key.free || held.value[host] != key) return
            held.value -= host
            states.value -= host
            free.value += host to FreeState(notBefore = notBefore)
        }
    }

    /** The swap, scripted; records every ask. */
    private class ScriptedSwap : TicketIssuer {
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

    /** The account route, scripted: [params] answers the GET and [created] each POST, in turn. */
    private class ScriptedAccounts : AccountIssuer {
        val gets = mutableListOf<Pair<String, String>>()
        val posts = mutableListOf<Post>()
        var params: () -> AccountAnswer = { AccountAnswer.Params(HOST, BITS, DAY) }
        val created = ArrayDeque<AccountAnswer>()

        class Post(
            val origin: String,
            val aud: String,
            val day: Long,
            val nonce: ByteArray,
            val n: Long,
        )

        override suspend fun accountParams(
            origin: String,
            aud: String,
        ): AccountAnswer {
            gets += origin to aud
            return params()
        }

        override suspend fun createAccount(
            origin: String,
            aud: String,
            day: Long,
            nonce: ByteArray,
            n: Long,
        ): AccountAnswer {
            posts += Post(origin, aud, day, nonce, n)
            return created.removeFirstOrNull() ?: AccountAnswer.NoAnswer("unscripted")
        }
    }

    private var now = 1_800_000_100_000L
    private val ticketJournal = SpoolTicketsTest.MemoryJournal()
    private val tickets = SpoolTickets(ticketJournal) { now }
    private val store = MemoryStore(ticketJournal)
    private val swap = ScriptedSwap()
    private val accounts = ScriptedAccounts()
    private val urls = MutableStateFlow(setOf(URL))
    private var online = true
    private val logs = mutableListOf<String>()
    private var nonceSeed = 0

    private fun refresher() =
        TicketRefresher(
            keys = store,
            tickets = tickets,
            issuer = swap,
            urls = urls,
            online = { online },
            clock = { now },
            log = { logs += it },
            free = store,
            accounts = accounts,
            nonces = { ByteArray(AccountStamp.NONCE_BYTES) { (it + nonceSeed++).toByte() } },
            // On the test's own dispatcher, so virtual time drives the loop's whole round.
            mine = { day, nonce, bits -> AccountStamp.mine(day, nonce, bits, 1L shl 20) },
        )

    private fun issued(
        sigByte: Byte = 7,
        expS: Long = now / 1_000 + 25 * 3_600,
    ) = IssuerAnswer.Issued(
        SpoolTicketsTest
            .ticket(
                aud = HOST,
                nbf = now / 1_000,
                exp = expS,
                fl = SpoolTicket.FL_FILES,
                sigByte = sigByte,
                ab = 1_310_720,
                am = 262_144,
            ).text,
        now + 30 * DAY_MS,
    )

    private suspend fun allowFree() {
        store.setFreeIssuer(HOST, FREE_ISSUER)
        store.setFreeConsent(HOST, true)
    }

    private val freeKey = HeldKey(FREE_ISSUER, KEY, free = true)

    // ── a free key asks only for a waiting upload ──────────────────────────────────────────────────────

    @Test
    fun aFreeKeyWithNoUploadWaitingAsksNothingHoweverCloseItsTicketIsToExp() =
        runTest {
            allowFree()
            store.putKey(HOST, freeKey)
            store.putKeyState(HOST, freeKey, KeyState(KeyAnswer.OK, notBefore = now - 1))
            // A ticket an hour from its exp: a customer key would have asked five hours ago.
            tickets.import(URL, issued(expS = now / 1_000 + 3_600).ticket)
            assertNull(refresher().runOnce())
            // Nor with none held at all.
            tickets.clear(URL)
            assertNull(refresher().runOnce())
            assertTrue(swap.asks.isEmpty())
        }

    @Test
    fun aWaitingUploadAsksOnceAndAHeldTicketEndsTheWait() =
        runTest {
            allowFree()
            store.putKey(HOST, freeKey)
            swap.next = { issued() }
            val r = refresher()
            r.uploadWanted(URL)
            r.runOnce()
            assertEquals(listOf(FREE_ISSUER to KEY), swap.asks)
            assertTrue(tickets.presentable(URL) != null)
            assertFalse(r.isWaiting(HOST))
            // Hours later, still nothing waits: no ask, even past the floor and the 6 h margin.
            now += 23 * 3_600_000L
            assertNull(r.runOnce())
            assertEquals(1, swap.asks.size)
        }

    @Test
    fun aHeldFreeTicketIsUsedUntilItsExpThoughAnUploadWaits() =
        runTest {
            allowFree()
            store.putKey(HOST, freeKey)
            tickets.import(URL, issued().ticket)
            val r = refresher()
            r.uploadWanted(URL)
            assertNull(r.runOnce())
            assertTrue(swap.asks.isEmpty())
        }

    @Test
    fun aFreeTicketRefusedAtEntIsAskedForAgainNoSoonerThanAnHourAfterIts200() =
        runTest {
            allowFree()
            store.putKey(HOST, freeKey)
            swap.next = { issued(sigByte = 1) }
            val r = refresher()
            r.uploadWanted(URL)
            r.runOnce()
            // The spool refuses it (a clock far off), so the upload stays locked and the plane marks it again.
            tickets.answered(URL, tickets.presentable(URL)!!, refusal = "signature")
            now += 10 * 60_000L
            r.uploadWanted(URL)
            r.runOnce()
            assertEquals(1, swap.asks.size)
            now += 50 * 60_000L
            r.uploadWanted(URL)
            swap.next = { issued(sigByte = 2) }
            r.runOnce()
            assertEquals(2, swap.asks.size)
        }

    @Test
    fun aFreeTicketRefusedOnImportIsAskedForAgainNoSoonerThanAnHourLater() =
        runTest {
            allowFree()
            store.putKey(HOST, freeKey)
            // The issuer sends a ticket for another audience: never held, and no loop on it.
            swap.next = {
                IssuerAnswer.Issued(SpoolTicketsTest.ticket(aud = "elsewhere.example", fl = SpoolTicket.FL_FILES).text, now + DAY_MS)
            }
            val r = refresher()
            r.uploadWanted(URL)
            r.runOnce()
            repeat(5) {
                now += 5 * 60_000L
                r.uploadWanted(URL)
                r.runOnce()
            }
            assertEquals(1, swap.asks.size)
            now += 40 * 60_000L
            r.uploadWanted(URL)
            r.runOnce()
            assertEquals(2, swap.asks.size)
        }

    @Test
    fun aCappedDaysRetryAfterHoldsAFreeKeyUntilThen() =
        runTest {
            allowFree()
            store.putKey(HOST, freeKey)
            swap.next = { IssuerAnswer.Unavailable(retryAfterMs = 6 * 3_600_000L) }
            val r = refresher()
            r.uploadWanted(URL)
            assertEquals(now + 6 * 3_600_000L, r.runOnce())
            now += 3 * 3_600_000L
            r.uploadWanted(URL)
            r.runOnce()
            assertEquals(1, swap.asks.size)
        }

    @Test
    fun aMarkLeftByAnUploadThatWentAsksNothingAnHourLater() =
        runTest {
            allowFree()
            store.putKey(HOST, freeKey)
            swap.next = { IssuerAnswer.NoVerdict("timeout") }
            val r = refresher()
            r.uploadWanted(URL)
            online = false
            r.runOnce()
            online = true
            now += TicketRefresher.WANT_TTL_MS + 1
            assertNull(r.runOnce())
            assertTrue(swap.asks.isEmpty())
        }

    @OptIn(ExperimentalCoroutinesApi::class) // advanceTimeBy, runCurrent
    @Test
    fun aWantThatLandsWhileARoundRunsIsNotLost() =
        runTest {
            // Two relays, `alpha` looked at first. While the round swaps `lab`'s free key, an upload locks on `alpha`,
            // already passed over. Its want must start the next round at once, not wait out the 15 min sleep: a wake
            // nobody was collecting for when it came would be dropped (mesh-lab-reviewer, 2026-10-17).
            val alpha = "alpha.example.org"
            urls.value = setOf(URL, "wss://$alpha/spool/v1")
            allowFree()
            store.setFreeIssuer(alpha, FREE_ISSUER)
            store.setFreeConsent(alpha, true)
            store.putKey(HOST, freeKey)
            lateinit var r: TicketRefresher
            swap.next = {
                r.uploadWanted("wss://$alpha/spool/v1")
                issued()
            }
            accounts.params = { AccountAnswer.Params(alpha, BITS, DAY) }
            accounts.created += AccountAnswer.Created(OTHER_KEY, alpha)
            r = refresher()
            r.uploadWanted(URL)
            r.start(backgroundScope)
            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals(listOf(FREE_ISSUER to alpha), accounts.gets)
            assertEquals(HeldKey(FREE_ISSUER, OTHER_KEY, free = true), store.held.value[alpha])
            r.stop()
        }

    // ── the account exchange (C-7.6-35, C-7.6-36) ──────────────────────────────────────────────────────

    @Test
    fun withNoKeyAWaitingUploadMakesAFreeAccountAndSwapsItsKeyAtOnce() =
        runTest {
            allowFree()
            accounts.created += AccountAnswer.Created(KEY, HOST)
            swap.next = { issued() }
            val r = refresher()
            r.uploadWanted(URL)
            r.runOnce()
            assertEquals(listOf(FREE_ISSUER to HOST), accounts.gets)
            val post = accounts.posts.single()
            assertEquals(DAY, post.day)
            assertEquals(HOST, post.aud)
            assertTrue("the posted stamp meets the bits", AccountStamp.verify(post.day, post.nonce, post.n, BITS))
            assertEquals(freeKey, store.held.value[HOST])
            assertEquals(listOf(FREE_ISSUER to KEY), swap.asks)
            val held = tickets.presentable(URL)!!
            assertEquals(262_144L, held.claims.am)
            assertEquals(1_310_720L, held.claims.ab)
        }

    @Test
    fun freeTicketsAreOnUntilThePersonTurnsThemOff() =
        runTest {
            // C-7.6-34 amended: an unanswered relay counts as on; the notice informs, it does not gate the account.
            store.setFreeIssuer(HOST, FREE_ISSUER)
            accounts.created += AccountAnswer.Created(KEY, HOST)
            swap.next = { issued() }
            val r = refresher()
            r.uploadWanted(URL)
            r.runOnce()
            assertEquals(listOf(FREE_ISSUER to HOST), accounts.gets)
            assertEquals(freeKey, store.held.value[HOST])
        }

    @Test
    fun nothingIsAskedOfTheIssuerOnceThePersonTurnedItOff() =
        runTest {
            store.setFreeIssuer(HOST, FREE_ISSUER)
            store.setFreeConsent(HOST, false)
            val r = refresher()
            r.uploadWanted(URL)
            r.runOnce()
            assertTrue(accounts.gets.isEmpty())
            assertTrue(swap.asks.isEmpty())
        }

    @Test
    fun aRelayThatIsTurnedOffGetsNoFreeAccountWhateverWaits() =
        runTest {
            // Only the relays the plane dials are asked for: a parked relay (or the plane off) leaves `urls` without it,
            // and an upload that waited there before it was parked asks nothing — neither the account nor the swap.
            store.setFreeIssuer(HOST, FREE_ISSUER)
            accounts.created += AccountAnswer.Created(KEY, HOST)
            swap.next = { issued() }
            urls.value = emptySet()
            val r = refresher()
            r.uploadWanted(URL)
            assertNull(r.runOnce())
            assertTrue(accounts.gets.isEmpty())
            assertTrue(swap.asks.isEmpty())
            // Back on, the upload still waiting: the account is made then.
            urls.value = setOf(URL)
            r.uploadWanted(URL)
            r.runOnce()
            assertEquals(listOf(FREE_ISSUER to HOST), accounts.gets)
        }

    @Test
    fun noFreeIssuerAsksNothing() =
        runTest {
            store.setFreeConsent(HOST, true)
            val r = refresher()
            r.uploadWanted(URL)
            assertNull(r.runOnce())
            assertTrue(accounts.gets.isEmpty())
        }

    @Test
    fun aBadStampIsMinedAgainOnceWithWhatTheIssuerNamed() =
        runTest {
            allowFree()
            accounts.created += AccountAnswer.BadStamp(BITS + 1, DAY + 1)
            accounts.created += AccountAnswer.Created(KEY, HOST)
            swap.next = { issued() }
            val r = refresher()
            r.uploadWanted(URL)
            r.runOnce()
            assertEquals(2, accounts.posts.size)
            val second = accounts.posts[1]
            assertEquals(DAY + 1, second.day)
            assertTrue(AccountStamp.verify(second.day, second.nonce, second.n, BITS + 1))
            assertFalse("a fresh nonce for the second stamp", accounts.posts[0].nonce.contentEquals(second.nonce))
            assertEquals(freeKey, store.held.value[HOST])
        }

    @Test
    fun aSecondBadStampBacksOffAsA503() =
        runTest {
            allowFree()
            accounts.created += AccountAnswer.BadStamp(BITS, DAY)
            accounts.created += AccountAnswer.BadStamp(BITS, DAY)
            val r = refresher()
            r.uploadWanted(URL)
            assertEquals(now + FreeAccountPolicy.UNAVAILABLE_FIRST_MS, r.runOnce())
            assertEquals(2, accounts.posts.size)
            assertNull(store.held.value[HOST])
        }

    @Test
    fun rateLimitedAndUnavailableWaitAsTheContractSays() =
        runTest {
            allowFree()
            val r = refresher()
            accounts.created += AccountAnswer.RateLimited(2 * 3_600_000L)
            r.uploadWanted(URL)
            assertEquals(now + 2 * 3_600_000L, r.runOnce())
            now += 2 * 3_600_000L
            accounts.params = { AccountAnswer.Unavailable(null) }
            r.uploadWanted(URL)
            assertEquals(now + 3_600_000L, r.runOnce())
            now += 3_600_000L
            r.uploadWanted(URL)
            assertEquals(now + 2 * 3_600_000L, r.runOnce())
            assertEquals(3, accounts.gets.size)
            assertEquals(1, accounts.posts.size)
        }

    @Test
    fun anUnansweredRequestBacksOffAMinuteNotAnHour() =
        runTest {
            allowFree()
            accounts.params = { AccountAnswer.NoAnswer("timeout") }
            val r = refresher()
            r.uploadWanted(URL)
            assertEquals(now + 60_000L, r.runOnce())
        }

    @Test
    fun anAnswerForAnotherAudienceIsNotActedOn() =
        runTest {
            allowFree()
            accounts.params = { AccountAnswer.Params("elsewhere.example", BITS, DAY) }
            val r = refresher()
            r.uploadWanted(URL)
            r.runOnce()
            assertTrue("no stamp spent on another audience", accounts.posts.isEmpty())
            now += 2 * 60_000L
            accounts.params = { AccountAnswer.Params(HOST, BITS, DAY) }
            accounts.created += AccountAnswer.Created(KEY, "elsewhere.example")
            r.uploadWanted(URL)
            r.runOnce()
            assertNull(store.held.value[HOST])
            assertTrue(swap.asks.isEmpty())
        }

    @Test
    fun anIssuerAskingForMoreBitsThanThisPhoneMinesIsLeftAlone() =
        runTest {
            allowFree()
            accounts.params = { AccountAnswer.Params(HOST, AccountStamp.MAX_BITS + 1, DAY) }
            val r = refresher()
            r.uploadWanted(URL)
            assertEquals(now + FreeAccountPolicy.UNAVAILABLE_FIRST_MS, r.runOnce())
            assertTrue(accounts.posts.isEmpty())
        }

    // ── a free key's life (C-7.6-37) ───────────────────────────────────────────────────────────────────

    @Test
    fun aFreeKeyAnswered401Or403IsForgottenAndReplacedNoSoonerThanTomorrow() =
        runTest {
            allowFree()
            store.putKey(HOST, freeKey)
            swap.next = { IssuerAnswer.Ended(now) }
            val r = refresher()
            r.uploadWanted(URL)
            r.runOnce()
            assertNull(store.held.value[HOST])
            val tomorrow = FreeAccountPolicy.nextUtcDay(now)
            assertEquals(
                tomorrow,
                store.free.value
                    .getValue(HOST)
                    .notBefore,
            )
            r.uploadWanted(URL)
            assertEquals(tomorrow, r.runOnce())
            assertTrue(accounts.gets.isEmpty())
            now = tomorrow
            accounts.created += AccountAnswer.Created(OTHER_KEY, HOST)
            swap.next = { issued() }
            r.uploadWanted(URL)
            r.runOnce()
            assertEquals(HeldKey(FREE_ISSUER, OTHER_KEY, free = true), store.held.value[HOST])
        }

    @Test
    fun aCustomerKeyWhoseAccountEndedFallsBackToAFreeOne() =
        runTest {
            allowFree()
            val customer = HeldKey(ISSUER, OTHER_KEY)
            store.putKey(HOST, customer)
            store.putKeyState(HOST, customer, KeyState(KeyAnswer.ENDED, notBefore = now + DAY_MS))
            accounts.created += AccountAnswer.Created(KEY, HOST)
            swap.next = { issued() }
            val r = refresher()
            // With no upload waiting, the ended key keeps its daily retry and nothing else happens.
            r.runOnce()
            assertTrue(accounts.gets.isEmpty())
            r.uploadWanted(URL)
            r.runOnce()
            assertEquals(freeKey, store.held.value[HOST])
            assertEquals(listOf(FREE_ISSUER to KEY), swap.asks)
        }

    @Test
    fun aCustomerKeyReplacesAFreeOneAndIsAskedOnItsSchedule() =
        runTest {
            allowFree()
            store.putKey(HOST, freeKey)
            store.putKey(HOST, HeldKey(ISSUER, OTHER_KEY))
            swap.next = { issued() }
            refresher().runOnce()
            assertEquals(listOf(ISSUER to OTHER_KEY), swap.asks)
        }

    @Test
    fun noLogLineCarriesAFreeKeyOrItsTicket() =
        runTest {
            allowFree()
            accounts.created += AccountAnswer.Created(KEY, HOST)
            val answer = issued()
            swap.next = { answer }
            val r = refresher()
            r.uploadWanted(URL)
            r.runOnce()
            val body = answer.ticket.removePrefix(HeldTicket.PREFIX)
            assertTrue(logs.isNotEmpty())
            assertTrue(logs.none { KEY in it || body in it })
            assertTrue("the mining time is logged", logs.any { "-bit account stamp in" in it })
        }

    private companion object {
        const val HOST = "lab.example.org"
        const val URL = "wss://lab.example.org/spool/v1?k=fixture-token"
        const val ISSUER = "https://anywhere.example.org"
        const val FREE_ISSUER = "https://free.example.org"
        const val KEY = "kak1_FBsiKTA3PkVMU1phaG92fYSLkpmgp661vMPK0djf5u0"
        const val OTHER_KEY = "kak1_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val BITS = 8
        const val DAY = 20_833L
        const val DAY_MS = 86_400_000L
    }
}
