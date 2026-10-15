package app.getknit.knit.call

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** §3.14 (ADR 2026-10.mr88): an own server replaces the spools' per kind, and an own TURN sends no `turnreq`. */
class OverridingTurnSourceTest {
    private class FakeSpool : TurnSource {
        var asked = 0
        val issued = listOf(TurnServer(urls = listOf("turn:spool.example.org"), user = "1999999999:x", pass = "hmac"))

        override suspend fun turnServers(peerId: String): List<TurnServer> {
            asked++
            return issued
        }

        override fun stunUrls() = listOf("stun:spool.example.org")
    }

    private val own = TurnServer(urls = listOf("turn:own.example.org"), user = "ada", pass = "pw")

    @Test
    fun withNothingOwnTheSpoolsAnswerBoth() =
        runTest {
            val spool = FakeSpool()
            val source = OverridingTurnSource({ CustomIce.NONE }, spool)
            assertSame(spool.issued, source.turnServers("peer"))
            assertEquals(listOf("stun:spool.example.org"), source.stunUrls())
            assertEquals(1, spool.asked)
        }

    @Test
    fun anOwnTurnServerIsUsedAndTheSpoolsAreNeverAsked() =
        runTest {
            val spool = FakeSpool()
            val source = OverridingTurnSource({ CustomIce(turn = own) }, spool)
            assertEquals(listOf(own), source.turnServers("peer"))
            assertEquals(0, spool.asked)
            // STUN is the other kind: still the hellos'.
            assertEquals(listOf("stun:spool.example.org"), source.stunUrls())
        }

    @Test
    fun ownStunAloneStillAsksTheSpoolsForTurn() =
        runTest {
            val spool = FakeSpool()
            val source = OverridingTurnSource({ CustomIce(stun = listOf("stun:own.example.org")) }, spool)
            assertEquals(listOf("stun:own.example.org"), source.stunUrls())
            assertSame(spool.issued, source.turnServers("peer"))
            assertEquals(1, spool.asked)
        }

    /** Read on every call: a change on the Calls screen applies from the next one, with no rebuild. */
    @Test
    fun theOwnServersAreReadEachTime() =
        runTest {
            val spool = FakeSpool()
            var current = CustomIce(turn = own)
            val source = OverridingTurnSource({ current }, spool)
            assertEquals(listOf(own), source.turnServers("peer"))
            current = CustomIce.NONE
            assertSame(spool.issued, source.turnServers("peer"))
        }
}
