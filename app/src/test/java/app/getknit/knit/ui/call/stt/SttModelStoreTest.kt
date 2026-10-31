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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class SttModelStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob())
    private val root = File(context.noBackupFilesDir, "models")

    // One file of one byte, "x", pinned by its SHA-256.
    private val model =
        SttModel(
            id = "small",
            baseUrl = "https://example.test/small/",
            files = listOf(SttFile("tokens.txt", 1, "2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881")),
            arch = SttArch.SMALL_STREAMING,
        )
    private var keptAlive = 0
    private val store = SttModelStore(context, AndroidInternetGate(context, scope), scope, model, keepAlive = { keptAlive++ })

    @After
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    // What the picker's builds left — the models it offered beside this one and the file that kept the pick — goes, and
    // the one model stays.
    @Test
    fun startSweepsThePickersLeftoversAndKeepsTheModel() {
        File(root, "moonshine-tiny-streaming-en-2026-08-21").apply { mkdirs() }.resolve("encoder.ort").writeText("old")
        File(root, "stt-model").writeText("moonshine-tiny-streaming-en-2026-08-21")
        File(root, model.id).apply { mkdirs() }.resolve("tokens.txt").writeText("x")
        File(root, "${model.id}/installed").writeText(model.id)

        store.start()

        assertEquals(listOf(model.id), root.list()?.toList())
        assertEquals(SttModelState.Ready, store.state.value)
    }

    // The keep-alive (the download service) reads the state as it starts: it must already say Downloading, or the
    // service would stop on the spot.
    @Test
    fun aDownloadSaysSoBeforeItAsksToBeKeptAlive() =
        runBlocking {
            val from = Files.createTempDirectory("stt").toFile().apply { resolve("tokens.txt").writeText("x") }
            var seen: SttModelState? = null
            lateinit var probe: SttModelStore
            probe = SttModelStore(context, AndroidInternetGate(context, scope), scope, model, keepAlive = { seen = probe.state.value })
            probe.start()

            assertTrue(probe.sideload(from))

            assertTrue("kept alive while $seen", seen is SttModelState.Downloading)
            withTimeout(5_000) { probe.state.first { it == SttModelState.Ready } }
            from.deleteRecursively()
            Unit
        }

    @Test
    fun noRouteFailsTheDownloadWithoutAskingToBeKeptAlive() {
        store.start()
        if (store.download()) return // a test host with a validated route: nothing to show here
        assertTrue(store.state.value is SttModelState.Failed)
        assertEquals(0, keptAlive)
    }
}
