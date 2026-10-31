package app.getknit.knit.ui.diagnostics

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** A debug build's "Save walkie clips" switch (C-3.11-9): offered only there, off until the user turns it on. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnosticsWalkieClipsTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private fun state() = DiagnosticsUiState(myNodeId = "8f3a2b1c9d4e", myName = "Ada Lovelace")

    private fun setWalkieClipsContent(
        offered: Boolean,
        on: Boolean,
        onSet: (Boolean) -> Unit = {},
    ) {
        compose.setContent {
            KnitTheme {
                DiagnosticsScreenContent(
                    state = state(),
                    health = TransportHealth.Healthy,
                    lastCrash = null,
                    now = 0L,
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onRestartMesh = {},
                    onScan = {},
                    onOpenCrashLog = {},
                    moderationLatched = false,
                    onResetModeration = {},
                    walkieClipsOffered = offered,
                    walkieClips = on,
                    onSetWalkieClips = onSet,
                )
            }
        }
    }

    @Test
    fun theWalkieClipsSwitchIsAbsentUnlessOffered() {
        setWalkieClipsContent(offered = false, on = false)
        compose.onNodeWithTag("walkie_clips_switch").assertDoesNotExist()
    }

    @Test
    fun theWalkieClipsSwitchIsOffUntilTurnedOn() {
        val sets = mutableListOf<Boolean>()
        setWalkieClipsContent(offered = true, on = false, onSet = { sets += it })
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("walkie_clips_switch"))
        compose.onNodeWithTag("walkie_clips_switch").assertIsOff().performClick()
        assertEquals(listOf(true), sets)
    }

    @Test
    fun anOnWalkieClipsSwitchTurnsOff() {
        val sets = mutableListOf<Boolean>()
        setWalkieClipsContent(offered = true, on = true, onSet = { sets += it })
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("walkie_clips_switch"))
        compose.onNodeWithTag("walkie_clips_switch").assertIsOn().performClick()
        assertEquals(listOf(false), sets)
    }
}
