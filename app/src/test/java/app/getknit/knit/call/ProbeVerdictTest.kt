package app.getknit.knit.call

import org.junit.Assert.assertEquals
import org.junit.Test

/** C-3.14-5: what the Calls screen's Test says for each kind, from what its connection gathered. */
class ProbeVerdictTest {
    /** An error on one network or address family is ordinary while another reaches the server. */
    @Test
    fun aCandidateWinsOverAnyError() {
        assertEquals(ProbeVerdict.Reached, ProbeVerdict.of(reached = true, errorCodes = listOf(701, 401)))
    }

    @Test
    fun unauthorizedIsTheCredential() {
        assertEquals(ProbeVerdict.AuthFailed, ProbeVerdict.of(reached = false, errorCodes = listOf(701, 401)))
    }

    @Test
    fun anotherServerErrorIsARefusal() {
        assertEquals(ProbeVerdict.Refused(403), ProbeVerdict.of(reached = false, errorCodes = listOf(701, 403)))
        assertEquals(ProbeVerdict.Refused(486), ProbeVerdict.of(reached = false, errorCodes = listOf(486)))
    }

    /** libwebrtc's own 7xx codes are local (no answer, no route), never a server's. */
    @Test
    fun silenceOrALocalErrorIsNoAnswer() {
        assertEquals(ProbeVerdict.NoAnswer, ProbeVerdict.of(reached = false, errorCodes = emptyList()))
        assertEquals(ProbeVerdict.NoAnswer, ProbeVerdict.of(reached = false, errorCodes = listOf(701, 701)))
    }
}
