package app.getknit.knit.ui.chat

import android.content.pm.PackageManager
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/**
 * The top bar's call button in a build that takes one kind of call or both (ADR 2026-10.swvp): a build with mesh calls
 * alone shows the mesh call there, one with live calls alone the live call, and one with both the one the chat puts
 * first. Its own class rather than more of `ChatScreenContentTest`, which is at detekt's size ceiling.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatCallButtonsTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun aPhoneWithAMicrophone() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_MICROPHONE, true)
    }

    private fun show(
        canCall: Boolean,
        canMeshCall: Boolean,
        meshCallFirst: Boolean = false,
    ) {
        compose.setContent {
            KnitTheme {
                ChatScreenContent(
                    conversationId = "bob",
                    state = ChatUiState(myNodeId = "me", canCall = canCall, canMeshCall = canMeshCall),
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
                    meshCallFirst = meshCallFirst,
                )
            }
        }
    }

    private fun assertBar(
        live: Int,
        mesh: Int,
    ) {
        compose.onAllNodesWithTag("chat_call").assertCountEquals(live)
        compose.onAllNodesWithTag("chat_mesh_call").assertCountEquals(mesh)
    }

    @Test
    fun aBuildWithMeshCallsAloneShowsTheMeshCall() {
        show(canCall = false, canMeshCall = true)
        assertBar(live = 0, mesh = 1)
    }

    @Test
    fun aBuildWithLiveCallsAloneShowsTheLiveCall() {
        show(canCall = true, canMeshCall = false, meshCallFirst = true)
        assertBar(live = 1, mesh = 0)
    }

    @Test
    fun aBuildWithBothShowsTheOneTheChatPutsFirst() {
        show(canCall = true, canMeshCall = true, meshCallFirst = true)
        assertBar(live = 0, mesh = 1)
    }

    @Test
    fun aBuildWithBothShowsTheLiveCallWhileALivePathLooksPossible() {
        show(canCall = true, canMeshCall = true, meshCallFirst = false)
        assertBar(live = 1, mesh = 0)
    }

    @Test
    fun aThreadThatCannotBeCalledShowsNoCall() {
        show(canCall = false, canMeshCall = false)
        assertBar(live = 0, mesh = 0)
    }
}
