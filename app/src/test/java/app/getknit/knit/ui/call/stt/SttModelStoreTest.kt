package app.getknit.knit.ui.call.stt

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.call.SttArch
import app.getknit.knit.call.SttFile
import app.getknit.knit.call.SttModel
import app.getknit.knit.call.SttModelState
import app.getknit.knit.net.AndroidInternetGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SttModelStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob())

    private fun model(id: String) =
        SttModel(
            id = id,
            baseUrl = "https://example.test/$id/",
            files = listOf(SttFile("tokens.txt", 1, "00")),
            arch = SttArch.TINY_STREAMING,
        )

    private val tiny = model("tiny")
    private val base = model("base")
    private val store = SttModelStore(context, AndroidInternetGate(context, scope), scope, models = listOf(tiny, base))

    @After
    fun tearDown() = scope.cancel()

    // The card redraws on the pick itself: two models both off the phone leave [SttModelStore.state] where it was.
    @Test
    fun aPickBetweenModelsInTheSameStateMovesTheSelectedFlow() =
        runTest {
            store.start()
            assertEquals(SttModelState.Absent, store.state.value)
            assertEquals(tiny, store.selected.value)

            store.select(base.id)

            assertEquals(SttModelState.Absent, store.state.value)
            assertEquals(base, store.selected.value)
            assertEquals(base, store.model)
        }
}
