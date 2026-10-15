package app.getknit.knit.ui.call.stt

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.call.TranscriptLine
import app.getknit.knit.call.WalkieClip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A debug build's walkie clips on disk (C-3.11-9): a WAV and its sidecar per burst, capped, the oldest going first. */
@RunWith(AndroidJUnit4::class)
class WalkieClipFilesTest {
    // Unconfined: a clip is on disk by the time keep returns.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private fun files(maxBytes: Long = Long.MAX_VALUE) =
        WalkieClipFiles(
            ApplicationProvider.getApplicationContext(),
            scope,
            switch = MutableStateFlow(true),
            about = { line -> mapOf("model" to "m1", "suppressor" to if (line.mine) "on" else null) },
            maxBytes = maxBytes,
            io = Dispatchers.Unconfined,
        )

    private fun clip(
        burst: Int,
        at: Long,
        mine: Boolean = false,
    ) = WalkieClip(
        TranscriptLine(burst, at, TranscriptLine.State.Text("meet me at the gate"), audioMs = 1_000, mine = mine),
        FloatArray(16_000),
    )

    private fun WalkieClipFiles.settle() = runBlocking { count() }

    @Test
    fun aBurstIsAWavAndASidecarNamedByItsTimeAndSide() {
        val files = files()
        files.keep(clip(7, at = 1_791_990_000_123, mine = true))
        assertEquals(
            1 to (
                44L + 32_000 +
                    files.dir
                        .listFiles()!!
                        .first { it.name.endsWith(".json") }
                        .length()
            ),
            files.settle(),
        )
        val name = "20261014T150000.123Z-spoken-b7"
        assertEquals(44L + 32_000, files.dir.resolve("$name.wav").length())
        val sidecar = JSONObject(files.dir.resolve("$name.json").readText())
        assertEquals(7, sidecar.getInt("burst"))
        assertTrue(sidecar.getBoolean("mine"))
        assertEquals("Text", sidecar.getString("state"))
        assertEquals("meet me at the gate", sidecar.getString("text"))
        assertEquals("m1", sidecar.getString("model"))
        assertEquals("on", sidecar.getString("suppressor"))
    }

    @Test
    fun pastTheCapTheOldestClipsGo() {
        val files = files(maxBytes = 80_000)
        files.keep(clip(1, at = 1_000))
        files.keep(clip(2, at = 2_000))
        files.keep(clip(3, at = 3_000))
        assertEquals(2, files.settle().first)
        assertFalse(files.dir.listFiles()!!.any { it.name.contains("-b1.") })
    }

    @Test
    fun clearDeletesEveryClip() {
        val files = files()
        files.keep(clip(1, at = 1_000))
        files.settle()
        runBlocking { files.clear() }
        assertEquals(0 to 0L, runBlocking { files.count() })
    }
}
