@file:OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / setMain / advanceUntilIdle are experimental kotlinx APIs

package app.getknit.knit.ui.callsettings

import app.getknit.knit.call.IceProbe
import app.getknit.knit.call.OwnIceServers
import app.getknit.knit.call.ProbeResult
import app.getknit.knit.call.ProbeVerdict
import app.getknit.knit.call.TurnServer
import app.getknit.knit.data.settings.OwnIceStore
import app.getknit.knit.data.settings.SettingsStore
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Calls screen (§3.14): switches write through, fields are a draft written by Save, and Test tries the draft. */
class CallSettingsViewModelTest {
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val store = mockk<OwnIceStore>(relaxed = true)
    private val relayAll = MutableStateFlow(false)
    private val ownIce = MutableStateFlow(SAVED)

    private var probed: Pair<List<String>, TurnServer?>? = null
    private var probeAnswer: ProbeResult? = ProbeResult(ProbeVerdict.Reached, ProbeVerdict.AuthFailed)
    private val probe =
        IceProbe { stun, turn ->
            probed = stun to turn
            probeAnswer
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { settings.callsRelayAll } returns relayAll
        every { store.ownIce } returns ownIce
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun kotlinx.coroutines.test.TestScope.vm(): CallSettingsViewModel =
        CallSettingsViewModel(settings, store, probe).also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        }

    @Test
    fun theDraftIsLoadedOnceFromTheStore() =
        runTest {
            val vm = vm()
            advanceUntilIdle()
            assertTrue(vm.loaded.value)
            assertEquals(CallServerDraft.of(SAVED), vm.state.value.draft)
            assertFalse(vm.state.value.dirty)

            // A later store change does not overwrite what is being typed.
            vm.editDraft { it.copy(turnUser = "typed") }
            ownIce.value = SAVED.copy(turnUser = "elsewhere")
            advanceUntilIdle()
            assertEquals("typed", vm.state.value.draft.turnUser)
        }

    @Test
    fun switchesWriteThrough() =
        runTest {
            val vm = vm()
            vm.setRelayAll(true)
            vm.setStunOn(true)
            vm.setTurnOn(false)
            advanceUntilIdle()
            coVerify { settings.setCallsRelayAll(true) }
            coVerify { store.setStunOn(true) }
            coVerify { store.setTurnOn(false) }
        }

    @Test
    fun saveWritesTheTrimmedFields() =
        runTest {
            val vm = vm()
            advanceUntilIdle()
            vm.editDraft { it.copy(stunText = "stun:a.example.org\n\n stun:b.example.org:3478 ", turnUrl = " turn:t.example.org ") }
            assertTrue(vm.state.value.canSave)
            vm.save()
            advanceUntilIdle()
            coVerify { store.setStunUrls(listOf("stun:a.example.org", "stun:b.example.org:3478")) }
            coVerify { store.setTurn(url = "turn:t.example.org", user = "ada", pass = "pw") }
        }

    @Test
    fun anInvalidFieldCannotBeSaved() =
        runTest {
            val vm = vm()
            advanceUntilIdle()
            vm.editDraft { it.copy(stunText = "stun:ada@a.example.org") }
            assertFalse(vm.state.value.canSave)
            vm.save()
            advanceUntilIdle()
            coVerify(exactly = 0) { store.setStunUrls(any()) }

            // A half-typed TURN server is no more savable than a malformed one.
            vm.editDraft { it.copy(stunText = "", turnPass = "") }
            assertFalse(vm.state.value.canSave)
        }

    /** The Test tries what is typed, before Save, and an edit clears the answer it gave for other fields. */
    @Test
    fun testProbesTheDraft() =
        runTest {
            val vm = vm()
            advanceUntilIdle()
            vm.editDraft { it.copy(turnPass = "new") }
            vm.test()
            advanceUntilIdle()
            assertEquals(listOf("stun:stun.example.org"), probed?.first)
            assertEquals("new", probed?.second?.pass)
            assertEquals(
                ProbeVerdict.AuthFailed,
                vm.state.value.result
                    ?.turn,
            )
            assertFalse(vm.state.value.testing)

            vm.editDraft { it.copy(turnPass = "newer") }
            assertNull(vm.state.value.result)
        }

    @Test
    fun aRefusedProbeLeavesNoResult() =
        runTest {
            probeAnswer = null
            val vm = vm()
            advanceUntilIdle()
            vm.test()
            advanceUntilIdle()
            assertNull(vm.state.value.result)
            assertFalse(vm.state.value.testing)
        }

    private companion object {
        val SAVED =
            OwnIceServers(
                stunOn = true,
                stunUrls = listOf("stun:stun.example.org"),
                turnOn = true,
                turnUrl = "turn:t.example.org",
                turnUser = "ada",
                turnPass = "pw",
            )
    }
}
