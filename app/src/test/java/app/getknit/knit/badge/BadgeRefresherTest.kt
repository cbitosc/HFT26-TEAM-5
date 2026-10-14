package app.getknit.knit.badge

import app.getknit.knit.badge.BadgeFixtures.HOLDER
import app.getknit.knit.badge.BadgeFixtures.NBF
import app.getknit.knit.mesh.spool.HeldKey
import app.getknit.knit.mesh.spool.KeyAnswer
import app.getknit.knit.mesh.spool.KeyState
import app.getknit.knit.mesh.spool.TicketRefresherTest.MemoryKeyJournal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The holder's side of docs/SUPPORTER_BADGE.md §5 against a fake issuer that signs with the RFC 8032 TEST 1 key, as
 * `npm run anywhere:dev` does: when it asks, with what, what each answer leaves, and that no log line carries a key,
 * a `sub`, an `r` or a credential.
 */
class BadgeRefresherTest {
    class MemoryBadgeJournal : BadgeJournal {
        val on = MutableStateFlow(false)
        val badges = MutableStateFlow<List<HeldBadge>>(emptyList())
        val state = MutableStateFlow(BadgeAskState())
        override val show: Flow<Boolean> = on
        override val held: Flow<List<HeldBadge>> = badges
        override val askState: Flow<BadgeAskState> = state

        override suspend fun setShow(on: Boolean) {
            this.on.value = on
        }

        override suspend fun putHeld(
            badges: List<HeldBadge>,
            state: BadgeAskState,
        ) {
            this.badges.value = badges
            this.state.value = state
        }

        override suspend fun putAskState(state: BadgeAskState) {
            this.state.value = state
        }

        override val sealedVersion: Flow<Long> = MutableStateFlow(0L)
        override val publishedFingerprint: Flow<String?> = MutableStateFlow(null)

        override suspend fun putSealed(
            version: Long,
            fingerprint: String,
        ) {
            (sealedVersion as MutableStateFlow).value = version
            (publishedFingerprint as MutableStateFlow).value = fingerprint
        }
    }

    /** Answers from [script]; a [Signed] answer signs each listed badge id over the `sub` it was sent. */
    class FakeIssuer(
        var script: Script = Signed(listOf("supporter")),
    ) : BadgeIssuer {
        sealed interface Script

        data class Signed(
            val ids: List<String>,
            val nbf: Long = NBF,
            val exp: Long = NBF + 30 * DAY_S,
        ) : Script

        data class Fixed(
            val answer: BadgeAnswer,
        ) : Script

        val subs = mutableListOf<ByteArray>()
        val asks = mutableListOf<Pair<String, String>>()

        override suspend fun issueBadge(
            origin: String,
            key: String,
            sub: ByteArray,
        ): BadgeAnswer {
            subs += sub
            asks += origin to key
            return when (val s = script) {
                is Fixed -> {
                    s.answer
                }

                is Signed -> {
                    BadgeAnswer.Issued(
                        s.ids.map { b ->
                            val w = BadgeFixtures.wrapper(BadgeFixtures.bc(b = b, sub = sub, nbf = s.nbf, exp = s.exp))
                            Badges.TEXT_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(w)
                        },
                    )
                }
            }
        }
    }

    private val journal = MemoryBadgeJournal()
    private val keys = MemoryKeyJournal()
    private val issuer = FakeIssuer()
    private var now = (NBF + 3_600) * 1_000
    private val logs = mutableListOf<String>()
    private var draws = 0

    private fun refresher(online: Boolean = true) =
        BadgeRefresher(
            journal = journal,
            keys = keys,
            issuer = issuer,
            selfId = { HOLDER },
            pins = listOf(BadgeKeys.RFC8032_TEST1),
            online = { online },
            clock = { now },
            random = { n -> ByteArray(n) { (draws * 16 + it).toByte() }.also { draws++ } },
            log = { logs += it },
        )

    private suspend fun holdKey(
        host: String = "relay.example",
        answer: KeyAnswer? = null,
    ) {
        val key = HeldKey("https://anywhere.example", "kak1_" + "A".repeat(43))
        keys.putKey(host, key)
        if (answer != null) keys.putKeyState(host, key, KeyState(answer))
    }

    private fun shown(): List<VerifiedBadge> =
        journal.badges.value.mapNotNull { Badges.verify(it.wrapper, it.r, HOLDER, now / 1_000, listOf(BadgeKeys.RFC8032_TEST1)) }

    @Test
    fun `with the toggle off nothing is ever asked`() =
        runTest {
            holdKey()
            assertNull(refresher().runOnce())
            assertTrue(issuer.asks.isEmpty())
        }

    @Test
    fun `with no live key nothing is asked`() =
        runTest {
            journal.on.value = true
            assertNull(refresher().runOnce())
            holdKey(answer = KeyAnswer.ENDED)
            assertNull(refresher().runOnce())
            holdKey(answer = KeyAnswer.UNKNOWN_KEY)
            assertNull(refresher().runOnce())
            assertTrue(issuer.asks.isEmpty())
        }

    @Test
    fun `turned on, it asks the key's issuer and holds a badge that verifies for this phone`() =
        runTest {
            journal.on.value = true
            holdKey()
            val next = refresher().runOnce()
            assertEquals(listOf("https://anywhere.example" to "kak1_" + "A".repeat(43)), issuer.asks)
            assertEquals(listOf(VerifiedBadge("supporter", NBF + 30 * DAY_S)), shown())
            // Next due seven days before it lapses, well past the hour floor.
            assertEquals((NBF + 30 * DAY_S) * 1_000 - BadgeRefreshPolicy.RENEW_MARGIN_MS, next)
        }

    @Test
    fun `each ask commits to a fresh r`() =
        runTest {
            journal.on.value = true
            holdKey()
            val refresher = refresher()
            refresher.runOnce()
            now += 24 * DAY_S * 1_000 // inside the seven-day margin
            refresher.runOnce()
            assertEquals(2, issuer.subs.size)
            assertFalse(issuer.subs[0].contentEquals(issuer.subs[1]))
            assertFalse(
                journal.badges.value
                    .single()
                    .r
                    .contentEquals(ByteArray(16) { it.toByte() }),
            )
        }

    @Test
    fun `never more than one ask an hour`() =
        runTest {
            journal.on.value = true
            holdKey()
            issuer.script = FakeIssuer.Fixed(BadgeAnswer.NoVerdict("timeout"))
            val refresher = refresher()
            refresher.runOnce()
            now += 59 * 60 * 1_000
            refresher.runOnce()
            assertEquals(1, issuer.asks.size)
            now += 60 * 1_000
            refresher.runOnce()
            assertEquals(2, issuer.asks.size)
        }

    @Test
    fun `a 200 keeps only the ids this build knows, and an empty list holds nothing`() =
        runTest {
            journal.on.value = true
            holdKey()
            issuer.script = FakeIssuer.Signed(listOf("gold", "supporter"))
            refresher().runOnce()
            assertEquals(listOf("supporter"), shown().map { it.b })
            issuer.script = FakeIssuer.Fixed(BadgeAnswer.Issued(emptyList()))
            now += 24 * DAY_S * 1_000
            refresher().runOnce()
            assertTrue(journal.badges.value.isEmpty())
        }

    @Test
    fun `ended drops the held badges and waits a day`() =
        runTest {
            journal.on.value = true
            holdKey()
            refresher().runOnce()
            now += 24 * DAY_S * 1_000
            issuer.script = FakeIssuer.Fixed(BadgeAnswer.Ended(null))
            val next = refresher().runOnce()
            assertTrue(journal.badges.value.isEmpty())
            assertEquals(now + BadgeRefreshPolicy.ENDED_RETRY_MS, next)
        }

    @Test
    fun `401, 503 and no answer keep what is held`() =
        runTest {
            journal.on.value = true
            holdKey()
            refresher().runOnce()
            val held = journal.badges.value
            for (answer in listOf(BadgeAnswer.UnknownKey, BadgeAnswer.Unavailable, BadgeAnswer.NoVerdict("offline"))) {
                now += 24 * DAY_S * 1_000 / 3
                issuer.script = FakeIssuer.Fixed(answer)
                refresher().runOnce()
                assertEquals(held, journal.badges.value)
            }
        }

    @Test
    fun `a badge under an unpinned key is not held`() =
        runTest {
            journal.on.value = true
            holdKey()
            BadgeRefresher(journal, keys, issuer, { HOLDER }, pins = emptyList(), clock = { now }).runOnce()
            assertEquals(1, issuer.asks.size)
            assertTrue(journal.badges.value.isEmpty())
        }

    @Test
    fun `offline, a due ask waits for a route`() =
        runTest {
            journal.on.value = true
            holdKey()
            assertEquals(now + BadgeRefresher.MAX_SLEEP_MS, refresher(online = false).runOnce())
            assertTrue(issuer.asks.isEmpty())
        }

    @Test
    fun `no log line carries the key, the sub, r or a credential`() =
        runTest {
            journal.on.value = true
            holdKey()
            refresher().runOnce()
            val secrets =
                listOf("kak1_", Badges.TEXT_PREFIX) +
                    issuer.subs.map { Base64.getUrlEncoder().withoutPadding().encodeToString(it) } +
                    journal.badges.value.map { hexOf(it.r) }
            assertTrue(logs.isNotEmpty())
            logs.forEach { line -> secrets.forEach { assertFalse(line, line.contains(it)) } }
        }

    @Test
    fun `the route's answers read as the contract writes them`() {
        val ok = """{"badges":[{"badge":"knit-badge:v1:AAAA","b":"supporter","nbf":1,"exp":2}]}"""
        assertEquals(listOf("knit-badge:v1:AAAA"), (BadgeAnswer.read(200, ok.encodeToByteArray()) as BadgeAnswer.Issued).badges)
        assertEquals(BadgeAnswer.UnknownKey, BadgeAnswer.read(401, """{"error":"unknown-key"}""".encodeToByteArray()))
        assertEquals(
            BadgeAnswer.Ended(1_792_454_400_000),
            BadgeAnswer.read(403, """{"error":"ended","endsAt":"2026-10-20T00:00:00.000Z"}""".encodeToByteArray()),
        )
        assertEquals(BadgeAnswer.Unavailable, BadgeAnswer.read(503, """{"error":"unavailable"}""".encodeToByteArray()))
        assertTrue(BadgeAnswer.read(400, """{"error":"bad-sub"}""".encodeToByteArray()) is BadgeAnswer.NoVerdict)
        assertTrue(BadgeAnswer.read(401, "<html>".encodeToByteArray()) is BadgeAnswer.NoVerdict)
        assertTrue(BadgeAnswer.read(200, """{"error":"x"}""".encodeToByteArray()) is BadgeAnswer.NoVerdict)
    }

    private companion object {
        const val DAY_S = 86_400L
    }
}
