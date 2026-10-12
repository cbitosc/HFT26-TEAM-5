package app.getknit.knit.data.message

import app.getknit.knit.mesh.protocol.CallPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A call record's JSON body, the outcome its facts read as, and the notice-shaped chat row it becomes (§3.12). */
class CallRecordTest {
    private val record =
        CallRecord(
            id = "c1",
            outgoing = true,
            reason = CallPayload.REASON_USER,
            connectedAt = 1_000L,
            endedAt = 223_500L,
        )

    @Test
    fun encodeThenDecodeRoundTripsAndGarbageDecodesToNull() {
        assertEquals(record, CallRecord.decode(record.encode()))
        val missed = CallRecord(id = "c2", outgoing = false, walkie = true, missed = true)
        assertEquals(missed, CallRecord.decode(missed.encode()))
        assertNull(CallRecord.decode("not json"))
        assertNull(CallRecord.decode("{\"id\":\"c1\"}"))
    }

    @Test
    fun theRowIsANoticeShapedDmRowFromWhoeverCalled() {
        val outgoing = record.toEntity(peerId = "peer", selfId = "me", startedAt = 5L)
        assertEquals("call:c1", outgoing.id)
        assertEquals("me", outgoing.senderId)
        assertEquals("peer", outgoing.recipientId)
        assertEquals("peer", outgoing.conversationId)
        assertEquals(5L, outgoing.sentAt)
        assertEquals(MessageEntity.KIND_CALL, outgoing.kind)
        assertTrue(outgoing.received)
        assertTrue(outgoing.isStatusNotice)
        assertEquals(record, CallRecord.decode(outgoing.body))
        val incoming = record.copy(outgoing = false).toEntity(peerId = "peer", selfId = "me", startedAt = 5L)
        assertEquals("peer", incoming.senderId)
        assertEquals("me", incoming.recipientId)
    }

    @Test
    fun aConnectedCallIsCompletedWithItsLengthWhoeverEndedIt() {
        assertEquals(CallOutcome.Completed, record.outcome)
        assertEquals(222_500L, record.durationMs)
        assertEquals(CallOutcome.Completed, record.copy(outgoing = false, byPeer = true, reason = CallPayload.REASON_ERROR).outcome)
        // A connected call whose end was never seen has no length to show.
        assertNull(record.copy(endedAt = null).durationMs)
        assertNull(record.copy(connectedAt = null).durationMs)
    }

    @Test
    fun aConnectedCallWhosePathWasLostIsDroppedOnBothSidesButAWalkieIsNot() {
        // §3.8: the reconnect window ran out. The side whose window did it and the side its END reached alike.
        val lost = record.copy(reason = CallPayload.REASON_NO_ROUTE)
        assertEquals(CallOutcome.Dropped, lost.outcome)
        assertEquals(CallOutcome.Dropped, lost.copy(outgoing = false, byPeer = true).outcome)
        assertEquals(222_500L, lost.durationMs)
        assertEquals(CallOutcome.Completed, lost.copy(walkie = true).outcome)
        // One that never connected is a failure, not a drop.
        assertEquals(CallOutcome.Failed, lost.copy(connectedAt = null).outcome)
    }

    @Test
    fun anUnconnectedCallReadsAsWhoEndedItAndWhy() {
        fun ours(
            reason: Int,
            byPeer: Boolean,
        ) = CallRecord(id = "c", outgoing = true, reason = reason, byPeer = byPeer).outcome

        fun theirs(
            reason: Int?,
            byPeer: Boolean,
            missed: Boolean = false,
        ) = CallRecord(id = "c", outgoing = false, reason = reason, byPeer = byPeer, missed = missed).outcome

        assertEquals(CallOutcome.Busy, ours(CallPayload.REASON_BUSY, byPeer = true))
        assertEquals(CallOutcome.Declined, ours(CallPayload.REASON_USER, byPeer = true))
        assertEquals(CallOutcome.NoAnswer, ours(CallPayload.REASON_TIMEOUT, byPeer = false))
        assertEquals(CallOutcome.Cancelled, ours(CallPayload.REASON_USER, byPeer = false))
        assertEquals(CallOutcome.Failed, ours(CallPayload.REASON_NO_ROUTE, byPeer = false))
        assertEquals(CallOutcome.Failed, ours(CallPayload.REASON_MEDIA, byPeer = true))

        assertEquals(CallOutcome.Missed, theirs(CallPayload.REASON_TIMEOUT, byPeer = false, missed = true))
        assertEquals(CallOutcome.Missed, theirs(null, byPeer = false, missed = true))
        assertEquals(CallOutcome.DeclinedByUs, theirs(CallPayload.REASON_USER, byPeer = false))
        assertEquals(CallOutcome.Failed, theirs(CallPayload.REASON_NO_ROUTE, byPeer = false))
        assertEquals(CallOutcome.Failed, theirs(CallPayload.REASON_ERROR, byPeer = true))
    }
}
