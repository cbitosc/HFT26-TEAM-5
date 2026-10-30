package app.getknit.knit.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The walkie's gain before the encoder (docs/VOICE_CALLS.md §3.11, C-3.11-5). */
class TalkGainTest {
    @Test
    fun quietSpeechIsBroughtToTheTarget() {
        val gain = TalkGain()
        val out = gain.talk(-40.0, SECONDS_10)
        assertEquals(TalkGain.TARGET_DB, rmsDb(out), 0.5)
        assertEquals(22.0, gain.gainDb, 0.01)
    }

    @Test
    fun theGainNeverPassesItsMaximum() {
        val gain = TalkGain()
        gain.talk(-46.0, SECONDS_10)
        assertEquals(TalkGain.MAX_GAIN_DB, gain.gainDb, 0.01)
    }

    @Test
    fun loudSpeechIsNeverTurnedDown() {
        val gain = TalkGain()
        val out = gain.talk(-10.0, FRAMES_PER_S)
        assertEquals(0.0, gain.gainDb, 0.01)
        assertEquals(-10.0, rmsDb(out), 0.5)
    }

    @Test
    fun theGainRisesSlowlyAndFallsAtOnce() {
        val gain = TalkGain()
        // One second of speech, a pause every fifth frame: 40 frames of it, 4 dB.
        gain.talk(-40.0, FRAMES_PER_S)
        assertEquals(TalkGain.INITIAL_GAIN_DB + 4.0, gain.gainDb, 0.01)
        gain.run(tone(-20.0))
        assertEquals(2.0, gain.gainDb, 0.01)
    }

    @Test
    fun noPeakPassesTheCeiling() {
        val gain = TalkGain()
        // A near-full-scale onset after quiet speech has raised the gain.
        gain.talk(-40.0, SECONDS_10)
        val out = gain.run(tone(-3.5))
        val ceiling = 32_768 * 10.0.pow(TalkGain.CEILING_DB / 20)
        assertTrue("peak ${peakOf(out)} over $ceiling", peakOf(out) <= ceiling + 1)
        assertEquals(1, gain.limited)
    }

    @Test
    fun noiseBetweenWordsHoldsTheGain() {
        val gain = TalkGain()
        gain.talk(-40.0, FRAMES_PER_S)
        val held = gain.gainDb
        repeat(SECONDS_10) { gain.run(tone(PAUSE_DB)) }
        assertEquals(held, gain.gainDb, 0.01)
    }

    @Test
    fun aSteadyHumNeverCountsAsSpeech() {
        val gain = TalkGain()
        repeat(SECONDS_10) { gain.run(tone(-40.0)) }
        assertEquals(TalkGain.INITIAL_GAIN_DB, gain.gainDb, 0.01)
        assertNull(gain.speechDb)
    }

    @Test
    fun silencePassesAsSilence() {
        val gain = TalkGain()
        val out = gain.run(ByteArray(FRAME_BYTES))
        assertTrue(out.all { it == 0.toByte() })
        assertNull(gain.speechDb)
    }

    @Test
    fun aBurstTalliesItsOwnSpeechAndKeepsTheGain() {
        val gain = TalkGain()
        gain.talk(-30.0, FRAMES_PER_S)
        assertEquals(-30.0, gain.speechDb!!, 0.5)
        val kept = gain.gainDb
        gain.newBurst()
        assertNull(gain.speechDb)
        assertEquals(0, gain.limited)
        assertEquals(kept, gain.gainDb, 0.0)
    }

    // [frames] of speech at [rmsDb], every fifth frame a pause between words; the last speech frame, as it went out.
    private fun TalkGain.talk(
        rmsDb: Double,
        frames: Int,
    ): ByteArray {
        var out = ByteArray(0)
        for (i in 0 until frames) {
            if (i % 5 == 0) run(tone(PAUSE_DB)) else out = run(tone(rmsDb))
        }
        return out
    }

    private fun TalkGain.run(frame: ByteArray): ByteArray = frame.also { apply(it, it.size) }

    // 20 ms of a 440 Hz sine at [rmsDb] dBFS RMS, 16 kHz mono, little-endian.
    private fun tone(rmsDb: Double): ByteArray {
        val amplitude = 32_768 * 10.0.pow(rmsDb / 20) * sqrt(2.0)
        val pcm = ByteArray(FRAME_BYTES)
        for (i in 0 until SAMPLES) {
            val s = (amplitude * sin(2 * PI * 440 * i / 16_000)).toInt()
            pcm[i * 2] = s.toByte()
            pcm[i * 2 + 1] = (s shr 8).toByte()
        }
        return pcm
    }

    private fun samples(pcm: ByteArray): List<Int> =
        (0 until pcm.size / 2).map { ((pcm[it * 2].toInt() and 0xFF) or (pcm[it * 2 + 1].toInt() shl 8)).toShort().toInt() }

    private fun rmsDb(pcm: ByteArray): Double {
        val s = samples(pcm)
        return 20 * log10(sqrt(s.sumOf { it.toDouble() * it } / s.size) / 32_768)
    }

    private fun peakOf(pcm: ByteArray): Int = samples(pcm).maxOf { abs(it) }

    private companion object {
        const val SAMPLES = 320
        const val FRAME_BYTES = SAMPLES * 2
        const val FRAMES_PER_S = 50
        const val SECONDS_10 = 500
        const val PAUSE_DB = -70.0
    }
}
