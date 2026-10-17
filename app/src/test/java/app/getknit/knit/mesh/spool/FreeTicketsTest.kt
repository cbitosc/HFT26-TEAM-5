package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.crypto.scope.SpoolPow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The free account's pure half (docs/VOICE_CALLS.md §2.6, C-7.6-33 to C-7.6-38): the stamp, every answer the account
 * route and the swap's 503 can give, the backoff, a free key's ask rule and the seeded issuers.
 *
 * The two stamp vectors are copied from knit-spool-coordinator `feat/free-tickets` @ `05b7ec4`,
 * `packages/core/src/free-tier.test.ts` (`STAMP` and `SHORT`), which the coordinator computed with Python's `hashlib`
 * from the contract's definition. Never recomputed here.
 */
class FreeTicketsTest {
    // ── the stamp: knit-spool-coordinator 05b7ec4 ──────────────────────────────────────────────────────

    private val day = 20_743L
    private val nonce = ByteArray(16) { it.toByte() }

    @Test
    fun theStampDigestIsTheCoordinatorsVector() {
        assertEquals(STAMP_H, hex(AccountStamp.digest(day, nonce, STAMP_N)))
        assertEquals(SHORT_H, hex(AccountStamp.digest(day, nonce, SHORT_N)))
        assertEquals(20, SpoolPow.leadingZeroBits(AccountStamp.digest(day, nonce, STAMP_N)))
        assertEquals(15, SpoolPow.leadingZeroBits(AccountStamp.digest(day, nonce, SHORT_N)))
    }

    @Test
    fun theShortStampPassesFifteenBitsAndFailsSixteen() {
        assertTrue(AccountStamp.verify(day, nonce, SHORT_N, bits = 15))
        assertFalse(AccountStamp.verify(day, nonce, SHORT_N, bits = 16))
        assertTrue(AccountStamp.verify(day, nonce, STAMP_N, bits = 16))
    }

    @Test
    fun miningFindsTheFirstCounterThatReachesTheBits() =
        runTest {
            // STAMP is the first n with at least 16 bits; SHORT the first with exactly 15, and no earlier n reaches 15.
            assertEquals(STAMP_N, AccountStamp.mine(day, nonce, bits = 16, maxAttempts = 1L shl 20))
            assertEquals(SHORT_N, AccountStamp.mine(day, nonce, bits = 15, maxAttempts = 1L shl 20))
        }

    @Test
    fun miningGivesUpPastItsBudget() =
        runTest {
            assertNull(AccountStamp.mine(day, nonce, bits = 16, maxAttempts = STAMP_N))
            assertEquals(STAMP_N, AccountStamp.mine(day, nonce, bits = 16, maxAttempts = STAMP_N + 1))
        }

    // ── the account route's answers ────────────────────────────────────────────────────────────────────

    private fun read(
        status: Int,
        body: String,
        retryAfterS: Long? = null,
    ) = AccountAnswer.read(status, body.toByteArray(), retryAfterS)

    @Test
    fun theParametersAreRead() {
        assertEquals(AccountAnswer.Params("lab.example", 22, 20_743), read(200, """{"aud":"lab.example","bits":22,"day":20743}"""))
        assertTrue(read(200, """{"aud":"lab.example","bits":22}""") is AccountAnswer.NoAnswer)
        assertTrue(read(200, """{"aud":"lab.example","bits":-1,"day":1}""") is AccountAnswer.NoAnswer)
    }

    @Test
    fun aNewAccountIsReadAndNeverPrintsItsKey() {
        val created = read(201, """{"key":"$KEY","aud":"lab.example"}""") as AccountAnswer.Created
        assertEquals(KEY, created.key)
        assertEquals("lab.example", created.aud)
        assertFalse(KEY in created.toString())
        // A key off the pattern is no account.
        assertTrue(read(201, """{"key":"kak1_short","aud":"lab.example"}""") is AccountAnswer.NoAnswer)
    }

    @Test
    fun aBadStampNamesWhatToMineNext() {
        assertEquals(AccountAnswer.BadStamp(23, 20_744), read(400, """{"error":"bad-stamp","bits":23,"day":20744}"""))
    }

    @Test
    fun rateLimitedAndUnavailableCarryTheirRetryAfter() {
        assertEquals(AccountAnswer.RateLimited(3_600_000), read(429, """{"error":"rate-limited"}""", retryAfterS = 3_600))
        assertEquals(AccountAnswer.Unavailable(null), read(503, """{"error":"unavailable"}"""))
        assertEquals(AccountAnswer.Unavailable(60_000), read(503, """{"error":"unavailable"}""", retryAfterS = 60))
    }

    @Test
    fun aStatusWithoutItsOwnWordIsNoAnswer() {
        // A proxy in front of the issuer answers these statuses too; only the issuer's own word counts.
        assertEquals(AccountAnswer.NoAnswer("http 503"), read(503, """{"error":"overloaded"}"""))
        assertEquals(AccountAnswer.NoAnswer("http 429"), read(429, "<html>slow down</html>"))
        assertEquals(AccountAnswer.NoAnswer("http 400"), read(400, """{"error":"bad-request"}"""))
        assertEquals(AccountAnswer.NoAnswer("http 502"), read(502, """{"error":"unavailable"}"""))
    }

    @Test
    fun theSwapsUnavailableCarriesItsRetryAfterAndNeedsItsWord() {
        assertEquals(IssuerAnswer.Unavailable(null), IssuerAnswer.read(503, """{"error":"unavailable"}""".toByteArray()))
        assertEquals(IssuerAnswer.Unavailable(7_000), IssuerAnswer.read(503, """{"error":"unavailable"}""".toByteArray(), 7))
        assertEquals(IssuerAnswer.NoVerdict("http 503"), IssuerAnswer.read(503, """{"error":"busy"}""".toByteArray()))
    }

    // ── the backoff (C-7.6-38) ─────────────────────────────────────────────────────────────────────────

    private val now = 1_800_000_000_000L

    @Test
    fun unavailableWaitsAnHourDoublingToADayOrItsRetryAfter() {
        var state = FreeState()
        val waits =
            (1..7).map {
                state = FreeAccountPolicy.afterUnavailable(state, retryAfterMs = null, now = now)
                state.notBefore - now
            }
        val hour = 3_600_000L
        assertEquals(listOf(hour, 2 * hour, 4 * hour, 8 * hour, 16 * hour, 24 * hour, 24 * hour), waits)
        assertEquals(now + 90_000, FreeAccountPolicy.afterUnavailable(FreeState(), 90_000, now).notBefore)
    }

    @Test
    fun rateLimitedWaitsItsRetryAfter() {
        assertEquals(now + 7_200_000, FreeAccountPolicy.afterRateLimited(FreeState(), 7_200_000, now).notBefore)
        assertEquals(now + FreeAccountPolicy.UNAVAILABLE_FIRST_MS, FreeAccountPolicy.afterRateLimited(FreeState(), null, now).notBefore)
    }

    @Test
    fun noAnswerBacksOffLikeTheSwapFromAMinuteToAnHour() {
        // Contract revision 2: the issuer never answered, so nothing was asked of its cap.
        var state = FreeState()
        val waits =
            (1..8).map {
                state = FreeAccountPolicy.afterNoAnswer(state, now)
                state.notBefore - now
            }
        val minute = 60_000L
        assertEquals(listOf(1, 2, 4, 8, 16, 32, 60, 60).map { it * minute }, waits)
    }

    @Test
    fun aForgottenKeyWaitsForTheNextUtcDay() {
        val day = SpoolPow.DAY_MS
        assertEquals((now / day + 1) * day, FreeAccountPolicy.nextUtcDay(now))
        assertEquals(day, FreeAccountPolicy.nextUtcDay(0))
    }

    // ── a free key's ask rule (C-7.6-27 amended) ───────────────────────────────────────────────────────

    @Test
    fun aFreeKeyIsDueOnlyForAWaitingUploadWithNoTicketToPresent() {
        val ok = KeyState(KeyAnswer.OK, notBefore = now + 1)
        assertNull(TicketRefreshPolicy.freeDueAt(ok, waiting = false, presentable = false))
        assertNull(TicketRefreshPolicy.freeDueAt(ok, waiting = true, presentable = true))
        // Never on the 6 h margin, but never before the hour floor either.
        assertEquals(now + 1, TicketRefreshPolicy.freeDueAt(ok, waiting = true, presentable = false))
        assertEquals(0L, TicketRefreshPolicy.freeDueAt(KeyState.FRESH, waiting = true, presentable = false))
        assertNull(TicketRefreshPolicy.freeDueAt(KeyState(KeyAnswer.UNKNOWN_KEY), waiting = true, presentable = false))
    }

    @Test
    fun aCappedDaysRetryAfterHoldsTheNextAsk() {
        val after = TicketRefreshPolicy.afterAnswer(KeyState(KeyAnswer.OK), IssuerAnswer.Unavailable(5 * 3_600_000L), held = false, now = now)
        assertEquals(now + 5 * 3_600_000L, after.notBefore)
        assertEquals(KeyAnswer.OK, after.answer)
        val bare = TicketRefreshPolicy.afterAnswer(KeyState(KeyAnswer.OK), IssuerAnswer.Unavailable(null), held = false, now = now)
        assertEquals(now + TicketRefreshPolicy.FIRST_BACKOFF_MS, bare.notBefore)
    }

    // ── the seeded issuers (C-7.6-33) ──────────────────────────────────────────────────────────────────

    @Test
    fun theSeededIssuersAreReadAndAMalformedItemIsDropped() {
        val issuers =
            FreeIssuers.parse(
                arrayOf(
                    "lax.spool.getknit.app https://anywhere.getknit.app",
                    "  Other.Example   https://issuer.example:8443 ",
                    "cleartext.example http://issuer.example",
                    "one-word",
                    "a b c",
                ),
            )
        assertEquals(
            mapOf("lax.spool.getknit.app" to "https://anywhere.getknit.app", "other.example" to "https://issuer.example:8443"),
            issuers,
        )
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        const val STAMP_N = 174_854L
        const val STAMP_H = "00000d2a3c4d623b4eb90ef544c507c7760cf1a4dbf5968173a09277f841510c"
        const val SHORT_N = 96_100L
        const val SHORT_H = "00018266ffcdc658d739b8a2c7ff73a32f78a0af100c402e07fb99a0d57434df"
        const val KEY = "kak1_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
