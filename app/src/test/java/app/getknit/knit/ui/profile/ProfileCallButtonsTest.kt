package app.getknit.knit.ui.profile

import android.content.pm.PackageManager
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.ui.Reach
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/**
 * The profile's call buttons beside Message: the live Call and the Mesh call each where the state offers it, and each
 * placing its own kind. Its own class, as the chat's `ChatCallButtonsTest` is, since both need a microphone feature.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileCallButtonsTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val placed = mutableListOf<String>()

    @Before
    fun aPhoneWithAMicrophone() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_MICROPHONE, true)
    }

    private fun show(
        canCall: Boolean,
        canMeshCall: Boolean,
    ) {
        compose.setContent {
            KnitTheme {
                ProfileDetailsScreenContent(
                    state =
                        ProfileDetailsUiState(
                            nodeId = "8f3a2b1c9d4e",
                            displayName = "Ada Lovelace",
                            status = "",
                            avatarHash = null,
                            reach = Reach.Direct,
                            isBlocked = false,
                            canCall = canCall,
                            canMeshCall = canMeshCall,
                        ),
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onMessage = {},
                    onOpenGroup = {},
                    onScan = {},
                    onBlock = {},
                    onUnblock = {},
                    onRemoveContact = {},
                    onMarkVerified = {},
                    onClearVerification = {},
                    onCall = { placed += "live" },
                    onMeshCall = { placed += "mesh" },
                )
            }
        }
    }

    private fun assertButtons(
        live: Int,
        mesh: Int,
    ) {
        compose.onAllNodesWithTag("profile_details_call").assertCountEquals(live)
        compose.onAllNodesWithTag("profile_details_mesh_call").assertCountEquals(mesh)
    }

    @Test
    fun aPeerWhoTakesBothKindsGetsBothButtonsEachPlacingItsOwnKind() {
        // The test holds the grant the gate asks for, so a press places the call rather than asking.
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        show(canCall = true, canMeshCall = true)
        assertButtons(live = 1, mesh = 1)

        compose.onNodeWithTag("profile_details_mesh_call").performClick()
        compose.onNodeWithTag("profile_details_call").performClick()
        compose.waitForIdle()
        assertEquals(listOf("mesh", "live"), placed)
    }

    @Test
    fun aPeerWhoTakesMeshCallsAloneGetsTheMeshCallOnly() {
        show(canCall = false, canMeshCall = true)
        assertButtons(live = 0, mesh = 1)
    }

    @Test
    fun aPeerWhoTakesLiveCallsAloneGetsTheLiveCallOnly() {
        show(canCall = true, canMeshCall = false)
        assertButtons(live = 1, mesh = 0)
    }

    @Test
    fun aPeerWhoTakesNeitherGetsNoCall() {
        show(canCall = false, canMeshCall = false)
        assertButtons(live = 0, mesh = 0)
    }
}
