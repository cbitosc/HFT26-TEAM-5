package app.getknit.knit.mesh.spool

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The key swap on a real socket (C-7.6-28, C-7.6-29, ADR 2026-10.3sk7). The three answers are copied from
 * knit-spool-coordinator `main` @ `889bca6` — `apps/anywhere/src/app/api/v1/ticket/route.ts` (the bodies, statuses and
 * headers it writes) and `route.db.test.ts` (the values its tests expect) — so a drift there fails here rather than on
 * a phone. The client is the production base with no network binding (there is no `Network` on the JVM), and the
 * origin is `http://` loopback, which only a debug build's cleartext rule admits.
 */
class OkHttpTicketIssuerTest {
    private val server = MockWebServer()
    private lateinit var origin: String

    private val issuer =
        OkHttpTicketIssuer(
            clientFor = {
                OkHttpTicketIssuer
                    .baseClient()
                    .newBuilder()
                    .callTimeout(3, TimeUnit.SECONDS)
                    .build()
            },
            allowCleartext = true,
        )

    @Before
    fun start() {
        // 127.0.0.1, not "localhost": some CI runners cannot bind ::1.
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        origin = "http://127.0.0.1:${server.port}"
    }

    @After
    fun stop() = server.close()

    private fun swap(
        key: String = KEY,
        at: String = origin,
    ) = runBlocking { issuer.swap(at, key) }

    private fun json(
        status: Int,
        body: String,
        vararg headers: Pair<String, String>,
    ): MockResponse {
        val builder =
            MockResponse
                .Builder()
                .code(status)
                .addHeader("content-type", "application/json")
                .addHeader("cache-control", "no-store")
                .body(body)
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        return builder.build()
    }

    @Test
    fun theRequestIsAPostWithTheKeyInTheHeaderAndNowhereElse() {
        server.enqueue(json(401, UNKNOWN_KEY_BODY, "www-authenticate" to "Bearer"))
        swap()
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/ticket", request.target)
        assertEquals("Bearer $KEY", request.headers["Authorization"])
        assertEquals(0L, request.bodySize)
        assertFalse(request.requestLine.contains(KEY.removePrefix(CustomerKey.PREFIX)))
        assertNull(request.url.query)
        assertNull(request.headers["Cookie"])
    }

    @Test
    fun a200IsTheTicketTextAndTheAccountsEnd() {
        server.enqueue(json(200, okBody(SpoolTicketsTest.TEXT)))
        val answer = swap() as IssuerAnswer.Issued
        assertEquals(SpoolTicketsTest.TEXT, answer.ticket)
        assertEquals(Instant.parse("2026-11-09T12:00:00.000Z").toEpochMilli(), answer.endsAt)
        assertFalse(answer.toString().contains(SpoolTicketsTest.TEXT.removePrefix(HeldTicket.PREFIX).take(16)))
    }

    @Test
    fun a401WithItsWordIsAnUnknownKey() {
        server.enqueue(json(401, UNKNOWN_KEY_BODY, "www-authenticate" to "Bearer"))
        assertEquals(IssuerAnswer.UnknownKey, swap())
    }

    @Test
    fun a403WithItsWordIsAnEndedAccountAndWhenItEnded() {
        server.enqueue(json(403, """{"error":"ended","endsAt":"2026-10-09T12:00:00.000Z"}"""))
        assertEquals(IssuerAnswer.Ended(Instant.parse("2026-10-09T12:00:00.000Z").toEpochMilli()), swap())
    }

    @Test
    fun aStatusWithoutItsOwnWordIsNoVerdict() {
        // A proxy or a firewall in front of the issuer answers 401 and 403 too, and says nothing about the key.
        server.enqueue(json(401, """{"message":"Unauthorized"}"""))
        server.enqueue(json(403, "<html>blocked</html>"))
        server.enqueue(json(502, "bad gateway"))
        assertEquals(IssuerAnswer.NoVerdict("http 401"), swap())
        assertEquals(IssuerAnswer.NoVerdict("http 403"), swap())
        assertEquals(IssuerAnswer.NoVerdict("http 502"), swap())
    }

    @Test
    fun a200ItCannotReadIsNoVerdict() {
        server.enqueue(json(200, "not json"))
        server.enqueue(json(200, """{"ticket":"not-a-ticket","endsAt":"2026-11-09T12:00:00.000Z"}"""))
        server.enqueue(json(200, """{"ticket":"${SpoolTicketsTest.TEXT}"}"""))
        server.enqueue(json(200, """{"ticket":"${SpoolTicketsTest.TEXT}","endsAt":"next tuesday"}"""))
        repeat(4) { assertEquals(IssuerAnswer.NoVerdict("unparseable"), swap()) }
    }

    @Test
    fun aRedirectIsNotFollowedSoTheKeyNeverLeavesItsOrigin() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(307)
                .addHeader("Location", "$origin/elsewhere")
                .build(),
        )
        assertEquals(IssuerAnswer.NoVerdict("http 307"), swap())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aBodyPastTheCapIsNoVerdict() {
        server.enqueue(json(200, "x".repeat(OkHttpTicketIssuer.MAX_BODY_BYTES.toInt() + 1)))
        assertEquals(IssuerAnswer.NoVerdict("too large"), swap())
    }

    @Test
    fun aCleartextOriginIsRefusedBeforeAnySocketWhereTheBuildDoesNotAllowIt() {
        val release = OkHttpTicketIssuer(clientFor = { OkHttpTicketIssuer.baseClient() }, allowCleartext = false)
        assertEquals(IssuerAnswer.NoVerdict("refused origin"), runBlocking { release.swap(origin, KEY) })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun noRouteIsNoVerdictWithoutASocket() {
        val offline = OkHttpTicketIssuer(clientFor = { null }, allowCleartext = true)
        assertEquals(IssuerAnswer.NoVerdict("offline"), runBlocking { offline.swap(origin, KEY) })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aDeadOriginIsNoVerdictAndNamesNoSecret() {
        val port = server.port
        server.close()
        val answer = swap(at = "http://127.0.0.1:$port")
        assertTrue(answer is IssuerAnswer.NoVerdict)
        assertFalse(answer.toString().contains(KEY.removePrefix(CustomerKey.PREFIX).take(12)))
    }

    // ── the free account's route (docs/VOICE_CALLS.md §2.6; knit-spool-coordinator feat/free-tickets @ 5883035) ──

    @Test
    fun theParametersAreAGetForTheAudienceWithNoKey() {
        server.enqueue(json(200, """{"aud":"lab.lax.spool.getknit.app","bits":22,"day":20743}"""))
        val answer = runBlocking { issuer.accountParams(origin, "lab.lax.spool.getknit.app") }
        assertEquals(AccountAnswer.Params("lab.lax.spool.getknit.app", 22, 20_743), answer)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/account?aud=lab.lax.spool.getknit.app", request.target)
        assertNull(request.headers["Authorization"])
    }

    @Test
    fun anAccountIsAPostOfTheStampWithNoKey() {
        server.enqueue(json(201, """{"key":"$KEY","aud":"lab.lax.spool.getknit.app"}"""))
        val nonce = ByteArray(16) { it.toByte() }
        val answer = runBlocking { issuer.createAccount(origin, "lab.lax.spool.getknit.app", 20_743, nonce, 174_854) }
        assertEquals(KEY, (answer as AccountAnswer.Created).key)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/account", request.target)
        assertNull(request.headers["Authorization"])
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
        assertEquals(
            """{"aud":"lab.lax.spool.getknit.app","day":20743,"nonce":"AAECAwQFBgcICQoLDA0ODw","n":174854}""",
            request.body!!.utf8(),
        )
    }

    @Test
    fun everyRefusalOfTheAccountRouteIsRead() {
        server.enqueue(json(400, """{"error":"bad-stamp","bits":22,"day":20743}"""))
        server.enqueue(json(429, """{"error":"rate-limited"}""", "retry-after" to "3600"))
        // The cap: retry-after to the next UTC midnight. A tier that is off sends none.
        server.enqueue(json(503, """{"error":"unavailable"}""", "retry-after" to "41000"))
        server.enqueue(json(503, """{"error":"unavailable"}"""))
        val nonce = ByteArray(16)
        val post = { runBlocking { issuer.createAccount(origin, "lab.example", 20_743, nonce, 1) } }
        assertEquals(AccountAnswer.BadStamp(22, 20_743), post())
        assertEquals(AccountAnswer.RateLimited(3_600_000), post())
        assertEquals(AccountAnswer.Unavailable(41_000_000), post())
        assertEquals(AccountAnswer.Unavailable(null), post())
    }

    @Test
    fun aFreeKeysSwapReadsTheCapsRetryAfter() {
        server.enqueue(json(503, """{"error":"unavailable"}""", "retry-after" to "41000"))
        assertEquals(IssuerAnswer.Unavailable(41_000_000), swap())
    }

    @Test
    fun theAccountRouteFollowsNoRedirectAndKeepsTheCleartextRule() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(307)
                .addHeader("Location", "$origin/elsewhere")
                .build(),
        )
        assertEquals(AccountAnswer.NoAnswer("http 307"), runBlocking { issuer.accountParams(origin, "lab.example") })
        assertEquals(1, server.requestCount)
        val release = OkHttpTicketIssuer(clientFor = { OkHttpTicketIssuer.baseClient() }, allowCleartext = false)
        assertEquals(AccountAnswer.NoAnswer("refused origin"), runBlocking { release.accountParams(origin, "lab.example") })
        assertEquals(1, server.requestCount)
    }

    private companion object {
        const val KEY = "kak1_FBsiKTA3PkVMU1phaG92fYSLkpmgp661vMPK0djf5u0"

        // route.ts's `{ error: "unknown-key" }` and route.db.test.ts's expected 200 values (now 2026-10-10T12:00Z,
        // exp now + 25 h, flags TURN | cast, ends 30 days on), with the vector ticket standing in for the signed one.
        const val UNKNOWN_KEY_BODY = """{"error":"unknown-key"}"""

        fun okBody(ticket: String) =
            """{"ticket":"$ticket","aud":"lab.lax.getknit.app","nbf":1791633600,"exp":1791723600,""" +
                """"flags":{"turn":true,"cast":true},"endsAt":"2026-11-09T12:00:00.000Z"}"""
    }
}
