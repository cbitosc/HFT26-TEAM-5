package app.getknit.knit.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.emergency.EmergencyCodec
import app.getknit.knit.emergency.EmergencyIds
import app.getknit.knit.emergency.EmergencyKind
import app.getknit.knit.emergency.EmergencyLocation
import app.getknit.knit.emergency.EmergencyPayload
import app.getknit.knit.emergency.EmergencySeverity
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmergencyMessageCardTest {
    @Suppress("DEPRECATION")
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_800_000_000_000L
    private val payload =
        EmergencyPayload(
            kind = EmergencyKind.SOS,
            emergencyId = EmergencyIds.new(),
            severity = EmergencySeverity.CRITICAL,
            createdAt = now,
            expiresAt = now + 60_000L,
            message = "Need medical assistance",
            location = EmergencyLocation(17.385, 78.4867, 12f, now),
        )

    private fun render(rows: List<ChatRow>) {
        compose.setContent {
            KnitTheme {
                ChatScreenContent(
                    conversationId = "bob",
                    state = ChatUiState(myNodeId = "me", isRoom = false, rows = rows),
                    inputState = TextFieldState(""),
                    pendingAttachment = null,
                    replyingTo = null,
                    now = now + 10_000,
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
    }

    @Test
    fun onlyProjectedValidPayloadsReceiveTrustedEmergencyPresentation() {
        val encoded = EmergencyCodec.encode(payload, now)

        assertEquals(payload, authenticatedEmergency(encoded, now))
        assertNull(authenticatedEmergency(encoded, null))
        assertNull(authenticatedEmergency("ordinary chat message", now))
        assertNull(authenticatedEmergency(EmergencyCodec.PREFIX + "%%%", now))
        assertNull(authenticatedEmergency("tarnished:sos:v2:anything", now))
    }

    @Test
    fun validSosIsReadable() {
        val encoded = EmergencyCodec.encode(payload, now)
        val rows =
            listOf(
                ChatRow(
                    id = "sos",
                    body = encoded,
                    mine = false,
                    senderName = "Bob",
                    senderNodeId = "bob",
                    avatarHash = null,
                    sentAt = now,
                    received = false,
                    emergency = payload,
                ),
            )
        render(rows)

        compose.onNodeWithTag("chat_emergency_card", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Authenticated SOS", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Sender: Bob", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Severity: Critical", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Need medical assistance", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Location shared by sender", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("tarnished:sos:v1:", substring = true).assertDoesNotExist()
    }

    @Test
    fun ordinaryMessageStaysOrdinary() {
        render(
            listOf(
                ChatRow(
                    id = "ordinary",
                    body = "Ordinary chat remains ordinary",
                    mine = false,
                    senderName = "Bob",
                    senderNodeId = "bob",
                    avatarHash = null,
                    sentAt = now,
                    received = false,
                ),
            ),
        )

        compose.onNodeWithTag("chat_emergency_card").assertDoesNotExist()
        compose.onNodeWithText("Ordinary chat remains ordinary").assertIsDisplayed()
    }
}
