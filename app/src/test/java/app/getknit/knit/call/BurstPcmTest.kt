package app.getknit.knit.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** A burst's audio for the transcript (C-3.11-9): the player's PCM to the model's 16 kHz, and cut where it is quiet. */
class BurstPcmTest {
    @Test
    fun theDecodersFortyEightKilohertzComesOutAtSixteen() {
        val pcm = BurstPcm()
        // 1 s of a 440 Hz tone at half scale, handed over in the decoder's 20 ms buffers.
        val tone = tone(48_000, 440.0, 0.5, ms = 1_000)
        tone.asList().chunked(960).forEach { pcm.add(buffer(it.toShortArray()), 48_000, 1) }
        val out = pcm.samples()
        assertEquals(16_000, out.size)
        assertEquals(1_000, pcm.audioMs)
        // A three-sample average barely touches 440 Hz: the tone's peak is still about half scale.
        val peak = out.maxOf { abs(it) }
        assertTrue("peak $peak", peak in 0.48f..0.51f)
    }

    @Test
    fun sixteenKilohertzPassesStraightThrough() {
        val pcm = BurstPcm()
        val samples = shortArrayOf(0, 16_384, -16_384, 32_767)
        pcm.add(buffer(samples), 16_000, 1)
        assertEquals(listOf(0f, 0.5f, -0.5f, 32_767 / 32_768f), pcm.samples().toList())
    }

    @Test
    fun stereoIsMixedToMono() {
        val pcm = BurstPcm()
        // Left and right interleaved: (0.5, 0) and (−0.5, −0.5) average to 0.25 and −0.5.
        pcm.add(buffer(shortArrayOf(16_384, 0, -16_384, -16_384)), 16_000, 2)
        assertEquals(listOf(0.25f, -0.5f), pcm.samples().toList())
    }

    @Test
    fun aRateThatIsNoMultipleIsInterpolated() {
        val pcm = BurstPcm()
        pcm.add(buffer(tone(24_000, 300.0, 0.5, ms = 500)), 24_000, 1)
        // Two outputs per three inputs: half a second at 16 kHz, give or take the interpolator's edge.
        assertTrue("${pcm.samples().size}", abs(pcm.samples().size - 8_000) <= 1)
    }

    @Test
    fun theBufferIsReadNotConsumed() {
        val pcm = BurstPcm()
        val buffer = buffer(shortArrayOf(1, 2, 3))
        pcm.add(buffer, 16_000, 1)
        assertEquals(0, buffer.position())
        assertEquals(6, buffer.limit())
    }

    @Test
    fun aStuckBurstStopsGrowingAtItsCap() {
        val pcm = BurstPcm(capMs = 100)
        repeat(10) { pcm.add(buffer(ShortArray(1_600)), 16_000, 1) }
        assertEquals(1_600, pcm.samples().size)
    }

    @Test
    fun audioNoLongerThanTheMaximumIsOneStretch() {
        val samples = FloatArray(16_000 * 8)
        assertEquals(listOf(0 until samples.size), segments(samples))
        assertEquals(emptyList<IntRange>(), segments(FloatArray(0)))
    }

    @Test
    fun aLongBurstIsCutAtItsPauses() {
        // 20 s of "speech" with a 200 ms pause at 6.5 s and another at 13 s.
        val rate = 16_000
        val samples = FloatArray(rate * 20) { i -> (sin(2 * PI * 200 * i / rate) * 0.5).toFloat() }
        for (pause in listOf(6_500, 13_000)) {
            val from = pause * rate / 1_000
            samples.fill(0f, from, from + rate / 5)
        }
        val cuts = segments(samples)
        assertEquals(3, cuts.size)
        // Contiguous, covering every sample, each no longer than 8 s, each cut inside a pause.
        assertEquals(0, cuts.first().first)
        assertEquals(samples.size - 1, cuts.last().last)
        cuts.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
        cuts.forEach { assertTrue("$it", it.last - it.first + 1 <= rate * 8) }
        cuts.dropLast(1).forEach { assertEquals(0f, samples[it.last + 1]) }
    }

    @Test
    fun speechWithNoPauseIsStillCutWithinTheMaximum() {
        val samples = FloatArray(16_000 * 30) { 0.3f }
        val cuts = segments(samples)
        assertTrue(cuts.all { it.last - it.first + 1 <= 16_000 * 8 })
        assertEquals(samples.size, cuts.sumOf { it.last - it.first + 1 })
    }

    private fun tone(
        rate: Int,
        hz: Double,
        amplitude: Double,
        ms: Int,
    ): ShortArray = ShortArray(rate * ms / 1_000) { i -> (sin(2 * PI * hz * i / rate) * amplitude * 32_767).toInt().toShort() }

    // As the decoder hands PCM over: native order, a direct buffer, positioned at its start.
    private fun buffer(samples: ShortArray): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder()).apply {
            asShortBuffer().put(samples)
        }
}
