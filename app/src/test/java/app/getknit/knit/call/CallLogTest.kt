package app.getknit.knit.call

import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.message.CallOutcome
import app.getknit.knit.data.message.CallRecord
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.mesh.protocol.CallPayload
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.util.Collections

/** `CallLog` writes each call the manager logs into its DM thread, and a failed write costs only its own line (§3.12). */
@OptIn(ExperimentalCoroutinesApi::class)
class CallLogTest {
    private val rows = Collections.synchronizedList(mutableListOf<MessageEntity>())
    private var failNext = false
    private val messages =
        mockk<MessageRepository>().also { repo ->
            coEvery { repo.save(any()) } answers {
                if (failNext) {
                    failNext = false
                    throw IOException("database closed")
                }
                rows += firstArg<MessageEntity>()
            }
        }

    private fun TestScope.manager(): CallManager =
        CallManager(
            signals = CallSignals { _, _ -> true },
            turn = mockk(relaxed = true),
            media = mockk(relaxed = true),
            scope = backgroundScope,
            selfId = { ME },
            clock = { testScheduler.currentTime },
        ).also { CallLog(it, messages = { messages }, selfId = { ME }, scope = backgroundScope).start() }

    /** An offer from [peer] old enough never to ring: the manager logs it missed at once. */
    private suspend fun TestScope.staleOffer(
        calls: CallManager,
        id: String,
        peer: String = ALICE,
    ) {
        val sentAt = testScheduler.currentTime - CallTimings().staleOfferMs - 1
        calls.onSignal(peer, CallPayload(id = id, ph = CallPayload.PHASE_OFFER, sdp = "v=0"), sentAt)
        runCurrent()
    }

    @Test
    fun aMissedCallLandsInItsDmThreadAsACallRow() =
        runTest {
            val calls = manager()
            staleOffer(calls, "c1")
            val row = rows.single()
            assertEquals(CallRecord.rowId("c1"), row.id)
            assertEquals(MessageEntity.KIND_CALL, row.kind)
            assertEquals(ALICE, row.conversationId)
            assertEquals(ALICE, row.senderId)
            assertEquals(ME, row.recipientId)
            assertEquals(CallOutcome.Missed, CallRecord.decode(row.body)?.outcome)
        }

    @Test
    fun aFailedWriteLosesItsOwnLineAndNothingAfterIt() =
        runTest {
            val calls = manager()
            failNext = true
            staleOffer(calls, "c1")
            staleOffer(calls, "c2", peer = BOB)
            assertEquals(listOf(CallRecord.rowId("c2")), rows.map { it.id })
        }

    private companion object {
        const val ME = "me"
        const val ALICE = "alice"
        const val BOB = "bob"
    }
}
