package app.getknit.knit.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.message.CallRecord
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.mesh.protocol.CallPayload
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * A call's record draws in its thread as a line with its outcome, never as a bubble of its JSON body
 * (docs/VOICE_CALLS.md §3.12). Its own class rather than more of `ChatScreenContentTest`, which is at detekt's size
 * ceiling.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatCallLineTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private fun callRow(record: CallRecord) =
        ChatRow(
            id = CallRecord.rowId(record.id),
            body = record.encode(),
            mine = record.outgoing,
            senderName = "Bob",
            senderNodeId = if (record.outgoing) "me" else "bob",
            kind = MessageEntity.KIND_CALL,
            avatarHash = null,
            sentAt = 1_700_000_000_001L,
            received = true,
            call = record,
        )

    @Test
    fun aCallsRecordIsALineWithItsOutcomeNotABubble() {
        val rows =
            listOf(
                callRow(CallRecord(id = "c1", outgoing = true, connectedAt = 0L, endedAt = 222_000L)),
                callRow(CallRecord(id = "c2", outgoing = false, missed = true)),
                callRow(CallRecord(id = "c3", outgoing = true, reason = CallPayload.REASON_NO_ROUTE, connectedAt = 0L, endedAt = 95_000L)),
            )
        compose.setContent {
            KnitTheme {
                ChatScreenContent(
                    conversationId = "bob",
                    state = ChatUiState(myNodeId = "me", rows = rows),
                    inputState = TextFieldState(""),
                    pendingAttachment = null,
                    replyingTo = null,
                    now = 1_700_000_060_000L,
                    onBack = {},
                    onOpenProfile = {},
                    onOpenGroupDetails = {},
                    onSend = {},
                    onAttachClick = {},
                    onClearAttachment = {},
                    onReceiveImage = {},
                    onTyping = {},
                    onMentionAdded = {},
                    onStartReply = {},
                    onCancelReply = {},
                    onReact = { _, _ -> },
                    onDeleteMessage = {},
                    onBlock = {},
                    onUnblock = {},
                    onCopy = {},
                    onSaveAttachment = { _, _, _ -> },
                )
            }
        }

        compose.onNodeWithText("Outgoing call · 3:42 · ", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Missed call · ", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Call dropped · 1:35 · ", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("\"id\"", substring = true).assertCountEquals(0)
    }
}
