package app.getknit.knit.badge

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.spool.OkHttpTicketIssuer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The badge issue end to end against a running local issuer (`npm run anywhere:dev` in knit-spool-coordinator, signing
 * with the RFC 8032 TEST 1 key): this app's own HTTP client, reader and verifier against the coordinator's route. Skipped
 * unless `KNIT_BADGE_ISSUER` (e.g. `http://localhost:3000`) and `KNIT_BADGE_KEY_LIVE` are set; `KNIT_BADGE_KEY_ENDED`
 * adds the 403 case. Run:
 *
 * ```
 * KNIT_BADGE_ISSUER=http://localhost:3000 KNIT_BADGE_KEY_LIVE=kak1_… KNIT_BADGE_KEY_ENDED=kak1_… \
 *   ./gradlew :app:testDebugUnitTest --tests 'app.getknit.knit.badge.LocalIssuerCrossCheckTest' --rerun
 * ```
 */
class LocalIssuerCrossCheckTest {
    private val origin: String? = System.getenv("KNIT_BADGE_ISSUER")
    private val live: String? = System.getenv("KNIT_BADGE_KEY_LIVE")
    private val ended: String? = System.getenv("KNIT_BADGE_KEY_ENDED")
    private val issuer = OkHttpTicketIssuer(clientFor = { OkHttpTicketIssuer.baseClient() }, allowCleartext = true)
    private val holder = NodeId.fromBytes(ByteArray(16) { (it * 3).toByte() })
    private val r = ByteArray(16) { (it + 40).toByte() }

    @Test
    fun `200, 401, 403 and 400 from the local issuer`() =
        runBlocking {
            assumeTrue("set KNIT_BADGE_ISSUER and KNIT_BADGE_KEY_LIVE", origin != null && live != null)
            val sub = Badges.subject(NodeId.toBytes(holder), r)

            val ok = issuer.issueBadge(origin!!, live!!, sub)
            assertTrue("200 expected, got $ok", ok is BadgeAnswer.Issued)
            val wrapper = checkNotNull(Badges.wrapperOf((ok as BadgeAnswer.Issued).badges.single())) { "not a knit-badge:v1: text" }
            val nowS = System.currentTimeMillis() / 1_000
            val shown = Badges.verify(wrapper, r, holder, nowS, listOf(BadgeKeys.RFC8032_TEST1))
            assertEquals("supporter", shown?.b)
            val claims = Badges.claimsOf(wrapper)!!
            assertEquals("nbf is the start of a UTC day", 0L, claims.nbf % 86_400)
            assertTrue("the window is at most 30 days", claims.exp - claims.nbf <= 30 * 86_400)
            // The same badge never shows for anyone else.
            assertEquals(null, Badges.verify(wrapper, r, NodeId.fromBytes(ByteArray(16)), nowS, listOf(BadgeKeys.RFC8032_TEST1)))

            assertEquals(BadgeAnswer.UnknownKey, issuer.issueBadge(origin, "kak1_" + "A".repeat(43), sub))
            assertEquals(BadgeAnswer.NoVerdict("http 400"), issuer.issueBadge(origin, live, sub.copyOf(31)))
            if (ended != null) assertTrue(issuer.issueBadge(origin, ended, sub) is BadgeAnswer.Ended)
        }
}
