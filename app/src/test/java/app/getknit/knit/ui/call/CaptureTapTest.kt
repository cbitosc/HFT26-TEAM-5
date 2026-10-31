package app.getknit.knit.ui.call

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import app.getknit.knit.call.CaptureWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer

/**
 * The call microphone's tap past a call's end (ADR 2026-10.kw29). `end()` runs before the module stops recording, so
 * buffers keep coming for a moment: device-found 2026-10-14, a switched call's hang-up reopened the tap's own recorder
 * and spun the record thread on empty buffers into a stale DEAD.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureTapTest {
    private val logs = mutableListOf<String>()
    private var switches = 0
    private val tap =
        CaptureTap(
            ApplicationProvider.getApplicationContext<Context>().getSystemService(AudioManager::class.java),
            onSwitched = { switches++ },
            log = { logs += it },
        )

    private fun silence(ms: Int) = ByteBuffer.allocate(RATE / 1_000 * ms * 2)

    private fun feed(buffer: ByteBuffer) = tap.onBuffer(buffer, AudioFormat.ENCODING_PCM_16BIT, 1, RATE, buffer.capacity(), 0L)

    @Test
    fun aBufferAfterTheCallEndedIsNeverJudged() {
        tap.begin("fallback", canSwitch = false)
        tap.end()
        feed(silence(CaptureWatch.DEAD_AFTER_MS.toInt()))
        assertNull(tap.status.value)
        assertTrue(logs.toString(), logs.none { "DEAD" in it })
    }

    @Test
    fun aSwitchedCallsEndOpensNoRecorderAndStillPacesTheModulesThread() {
        tap.begin("standard", canSwitch = true)
        feed(silence(CaptureWatch.DEAD_AFTER_MS.toInt()))
        assertEquals(CaptureWatch.Health.SWITCHED, tap.status.value?.health)
        assertEquals(1, switches)
        tap.end()
        val started = System.nanoTime()
        // The module records nothing itself after a switch, so this buffer's wait is all that paces its thread.
        feed(silence(FRAME_MS))
        assertTrue((System.nanoTime() - started) / 1_000_000 >= FRAME_MS - 2)
        assertTrue(logs.toString(), logs.none { "own recorder" in it })
        assertNull(tap.status.value)
    }

    private companion object {
        const val RATE = 48_000
        const val FRAME_MS = 10
    }
}
