package app.getknit.knit.ui.relay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.relay.RelayStatusRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.spool.SpoolStatus
import app.getknit.knit.mesh.spool.SpoolUrl
import app.getknit.knit.ui.theme.KnitTheme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The stateful half of a relay's own screen, over the real view model: a private relay's tokened URL is found by its
 * redacted route key, the screen does not leave before the stores answer, and it leaves once its row is gone.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RelayDetailScreenTest {
    private companion object {
        const val TOKENED = "wss://home.example.org/spool/v1?k=t0ken"
    }

    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val settings = mockk<SettingsStore>(relaxed = true)
    private val relayStatus = mockk<RelayStatusRepository>(relaxed = true)
    private val urls = MutableStateFlow(setOf(TOKENED))
    private var left = 0

    private fun render(statuses: Flow<List<SpoolStatus>> = flowOf(emptyList())) {
        every { settings.spoolEnabled } returns flowOf(true)
        every { settings.spoolUrls } returns urls
        every { settings.disabledSpoolUrls } returns flowOf(emptySet())
        every { relayStatus.statuses } returns statuses
        val vm = InternetRelayViewModel(settings, relayStatus)
        compose.setContent {
            KnitTheme {
                RelayDetailScreen(relayKey = SpoolUrl.redact(TOKENED), viewModel = vm, onBack = { left++ })
            }
        }
    }

    @Test
    fun aTokenedRelayIsFoundByItsRedactedKey() {
        render()
        compose.onNodeWithText("home.example.org").assertIsDisplayed()
        compose.onNodeWithTag("relay_detail_switch").assertIsDisplayed()
        assertEquals(0, left)
    }

    @Test
    fun theScreenLeavesOnceItsRelayIsGone() {
        render()
        compose.onNodeWithText("home.example.org").assertIsDisplayed()
        urls.value = emptySet()
        compose.waitForIdle()
        assertEquals(1, left)
    }

    @Test
    fun theScreenWaitsForTheStoresBeforeDecidingItsRelayIsGone() {
        // A store that has not answered yet: no row, and no leaving either.
        render(statuses = MutableSharedFlow())
        compose.waitForIdle()
        assertEquals(0, left)
    }
}
