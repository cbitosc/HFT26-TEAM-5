package app.getknit.knit.ui.call

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.call.CallPhase
import app.getknit.knit.call.CallState
import app.getknit.knit.call.SttModelState
import app.getknit.knit.call.TranscriptLine
import app.getknit.knit.call.WalkieStats
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The walkie transcript on the call screen (docs/VOICE_CALLS.md C-3.11-9): the diagnostics toggle trades the walkie's
 * card for the transcript, the card offers the model's download while it is missing, and a live call keeps its toggle
 * as it was.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// The small phone the call screen's previews draw (360 × 640): the card has the least room there.
@Config(qualifiers = "w360dp-h640dp")
class WalkieTranscriptScreenTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private var downloads = 0
    private var cancels = 0
    private var dismisses = 0

    private fun render(
        transcript: TranscriptUi,
        walkie: Boolean = true,
    ) {
        compose.setContent {
            KnitTheme {
                CallScreen(
                    state =
                        CallState(
                            id = "c1",
                            peerId = "hzwyqdbj74hu6gkurmcyxomaca",
                            outgoing = true,
                            phase = CallPhase.Connected,
                            startedAt = 0,
                            connectedAt = 0,
                            walkie = walkie,
                        ),
                    name = "Ada",
                    avatarHash = null,
                    route = null,
                    stats = null,
                    onAnswer = {},
                    onDecline = {},
                    onHangUp = {},
                    onMute = {},
                    onRoute = {},
                    clock = { 0L },
                    walkieStats = WalkieStats(path = "Bluetooth Coded · direct"),
                    transcript = transcript,
                    onSttDownload = { downloads++ },
                    onSttCancel = { cancels++ },
                    onSttDismiss = { dismisses++ },
                    statsShown = true,
                )
            }
        }
    }

    private fun ui(
        model: SttModelState = SttModelState.Ready,
        lines: List<TranscriptLine> = emptyList(),
    ) = TranscriptUi(lines, model, modelBytes = 44_256_550, metered = false)

    @Test
    fun theToggleTradesTheWalkieDiagnosticsForTheTranscript() {
        render(ui(lines = listOf(TranscriptLine(0, 0, TranscriptLine.State.Text("Meet me at the north gate")))))
        compose.onNodeWithText("Walkie diagnostics").assertIsDisplayed()
        compose.onNodeWithContentDescription("Show transcript").performClick()
        compose.onNodeWithText("Meet me at the north gate").assertIsDisplayed()
        compose.onNodeWithText("Walkie diagnostics").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show walkie diagnostics").performClick()
        compose.onNodeWithText("Walkie diagnostics").assertIsDisplayed()
    }

    @Test
    fun aBurstStillPlayingShowsTheWordsSoFar() {
        render(ui(lines = listOf(TranscriptLine(0, 0, TranscriptLine.State.Listening, live = "Meet me at"))))
        compose.onNodeWithContentDescription("Show transcript").performClick()
        compose.onNodeWithText("Meet me at…").assertIsDisplayed()
        compose.onNodeWithText("Listening…").assertDoesNotExist()
    }

    @Test
    fun withoutTheModelTheCardOffersItsDownload() {
        render(ui(model = SttModelState.Absent, lines = listOf(TranscriptLine(0, 0, TranscriptLine.State.NoModel))))
        compose.onNodeWithContentDescription("Show transcript").performClick()
        compose.onNodeWithText("44 MB", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Not transcribed", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Download").performClick()
        assertEquals(1, downloads)
    }

    @Test
    fun theDownloadsOfferCanBeClosedButTheTranscriptCannot() {
        render(ui(model = SttModelState.Absent))
        compose.onNodeWithContentDescription("Show transcript").performClick()
        compose.onNodeWithText("Settings › Calls", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        assertEquals(1, dismisses)
    }

    @Test
    fun aTranscriptWithItsModelHasNoClose() {
        render(ui(lines = listOf(TranscriptLine(0, 0, TranscriptLine.State.Text("Meet me at the north gate")))))
        compose.onNodeWithContentDescription("Show transcript").performClick()
        compose.onNodeWithText("Dismiss").assertDoesNotExist()
    }

    @Test
    fun aDownloadUnderWayCanBeCancelled() {
        render(ui(model = SttModelState.Downloading(done = 17_400_000, total = 44_256_550)))
        compose.onNodeWithContentDescription("Show transcript").performClick()
        compose.onNodeWithText("17 MB of 44 MB", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancels)
    }

    @Test
    fun aFailedDownloadSaysWhyAndOffersAnotherTry() {
        render(ui(model = SttModelState.Failed("No Internet connection")))
        compose.onNodeWithContentDescription("Show transcript").performClick()
        compose.onNodeWithText("No Internet connection", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, downloads)
    }

    @Test
    fun aLiveCallKeepsItsToggleAsItWas() {
        render(ui(lines = listOf(TranscriptLine(0, 0, TranscriptLine.State.Text("never shown")))), walkie = false)
        compose.onNodeWithContentDescription("Show transcript").assertDoesNotExist()
        compose.onNodeWithText("never shown").assertDoesNotExist()
    }
}
