package app.getknit.knit.mesh.spool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the field log makes of a spool's words (ADR 2026-10.a239): constants and numbers only, never the word. */
class SpoolFieldLogTest {
    @Test
    fun everyWordTheStatusRowHoldsHasItsConstant() {
        assertEquals(SpoolVerdict.NONE to null, SpoolVerdict.of(null))
        assertEquals(SpoolVerdict.UNREACHABLE to null, SpoolVerdict.of(ScopeSync.UNREACHABLE))
        assertEquals(SpoolVerdict.NO_HELLO to null, SpoolVerdict.of(ScopeSync.NO_HELLO))
        assertEquals(SpoolVerdict.OVERLONG_LISTING to null, SpoolVerdict.of(ScopeSync.OVERLONG_LISTING))
        assertEquals(SpoolVerdict.UNRESPONSIVE to null, SpoolVerdict.of(SpoolConnection.UNRESPONSIVE))
        assertEquals(SpoolVerdict.QUOTA to null, SpoolVerdict.of(SpoolErrCode.QUOTA))
        assertEquals(SpoolVerdict.TICKET to null, SpoolVerdict.of(SpoolErrCode.TICKET))
        assertEquals(SpoolVerdict.OTHER to null, SpoolVerdict.of("a_code_from_a_newer_spool"))
    }

    @Test
    fun theDialersSpellingsKeepTheirNumberAndLoseTheirText() {
        assertEquals(SpoolVerdict.CLOSED to 4001, SpoolVerdict.of("close 4001 auth"))
        assertEquals(SpoolVerdict.CLOSED to 1000, SpoolVerdict.of("close 1000"))
        assertEquals(SpoolVerdict.HTTP to 429, SpoolVerdict.of(failureReason("ProtocolException", 429)))
        assertEquals(SpoolVerdict.UNREACHABLE to null, SpoolVerdict.of(failureReason("SocketTimeoutException", null)))
        assertEquals(SpoolVerdict.TRANSPORT to null, SpoolVerdict.of(failureReason("SSLHandshakeException", null)))
    }

    @Test
    fun aSampleCountsScopesAndNamesNone() {
        val status =
            SpoolStatus(
                url = "wss://relay.example/v1?k=token",
                connected = true,
                powBits = 12,
                lastError = SpoolErrCode.RATE,
                scopes =
                    listOf(
                        ScopeStatus("aa", "Alice", localCount = 3, spoolCount = 2, converged = true, invalidCount = 0, retiring = false),
                        ScopeStatus("bb", "Bob", localCount = 1, spoolCount = 4, converged = false, invalidCount = 1, retiring = false),
                    ),
                dialFailures = 0,
            )
        val sample = status.fieldSample()
        assertEquals("wss://relay.example/v1", sample.relay)
        assertEquals(2, sample.scopes)
        assertEquals(1, sample.converged)
        assertEquals(4, sample.local)
        assertEquals(6, sample.remote)
        assertEquals(1, sample.invalid)
        assertEquals(SpoolVerdict.RATE, sample.reason)
        assertNull("no ticket line from a spool that takes none", sample.ticket)
        assertEquals(SpoolUploadsWord.OPEN, sample.uploads)
    }
}
