package app.getknit.knit.call

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The walkie's transcript (C-3.11-9): a burst heard becomes a line, and its text once the model has had it. */
@OptIn(ExperimentalCoroutinesApi::class)
class WalkieTranscriberTest {
    private var now = 1_000L
    private var modelReady = true
    private val heard = mutableListOf<Int>()
    private val logs = mutableListOf<String>()

    // On the test's scheduler but outside backgroundScope, whose work advanceUntilIdle leaves alone.
    private fun TestScope.transcriber(
        speech: SpeechToText =
            SpeechToText { s ->
                heard += s.size
                "words ${s.size}"
            },
    ): WalkieTranscriber {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return WalkieTranscriber(
            speech = speech,
            ready = { modelReady },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            dispatcher = dispatcher,
            clock = { now },
            log = { logs += it },
        )
    }

    @Test
    fun aBurstIsListenedToThenTranscribed() =
        runTest {
            val t = transcriber()
            t.begin(0)
            assertEquals(listOf(TranscriptLine(0, 1_000, TranscriptLine.State.Listening)), t.lines.value)
            t.pcm(pcm(ms = 2_000), 48_000, 1)
            t.end(0)
            assertEquals(
                TranscriptLine.State.Pending,
                t.lines.value
                    .single()
                    .state,
            )
            advanceUntilIdle()
            val line = t.lines.value.single()
            assertEquals(TranscriptLine.State.Text("words 32000"), line.state)
            assertEquals(2_000, line.audioMs)
            assertEquals(listOf(32_000), heard)
        }

    @Test
    fun theTextIsNeverLogged() =
        runTest {
            val t = transcriber(SpeechToText { "meet me at the gate" })
            t.begin(0)
            t.pcm(pcm(ms = 1_000), 48_000, 1)
            t.end(0)
            advanceUntilIdle()
            assertTrue(logs.isNotEmpty())
            assertFalse(logs.any { "gate" in it })
        }

    @Test
    fun aSlippedThumbIsNoSpeechAndNeverReachesTheModel() =
        runTest {
            val t = transcriber()
            t.begin(0)
            t.pcm(pcm(ms = 200), 48_000, 1)
            t.end(0)
            advanceUntilIdle()
            assertEquals(
                TranscriptLine.State.NoSpeech,
                t.lines.value
                    .single()
                    .state,
            )
            assertTrue(heard.isEmpty())
        }

    @Test
    fun withoutTheModelABurstIsMarkedSo() =
        runTest {
            modelReady = false
            val t = transcriber()
            t.begin(0)
            t.pcm(pcm(ms = 1_000), 48_000, 1)
            t.end(0)
            advanceUntilIdle()
            assertEquals(
                TranscriptLine.State.NoModel,
                t.lines.value
                    .single()
                    .state,
            )
            assertTrue(heard.isEmpty())
        }

    @Test
    fun anEmptyAnswerIsNoSpeechAndANullOneAFailure() =
        runTest {
            val answers = ArrayDeque(listOf<String?>("  ", null))
            val t = transcriber(SpeechToText { answers.removeFirst() })
            for (b in 0..1) {
                t.begin(b)
                t.pcm(pcm(ms = 1_000), 48_000, 1)
                t.end(b)
            }
            advanceUntilIdle()
            assertEquals(listOf(TranscriptLine.State.NoSpeech, TranscriptLine.State.Failed), t.lines.value.map { it.state })
        }

    @Test
    fun aLongBurstGoesToTheModelInStretchesAndComesBackAsOneLine() =
        runTest {
            val t = transcriber()
            t.begin(0)
            t.pcm(pcm(ms = 20_000), 48_000, 1)
            t.end(0)
            advanceUntilIdle()
            // Even loudness throughout: no pause to find, so cut anywhere from 5 s to 8 s in.
            assertTrue("${heard.size}", heard.size in 3..4)
            assertTrue(heard.all { it <= 16_000 * 8 })
            assertEquals(16_000 * 20, heard.sum())
            val text =
                (
                    t.lines.value
                        .single()
                        .state as TranscriptLine.State.Text
                ).text
            assertEquals(heard.joinToString(" ") { "words $it" }, text)
        }

    @Test
    fun burstsAreTranscribedOneAtATimeInTheOrderTheyEnded() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val order = mutableListOf<Int>()
            val t =
                transcriber(
                    SpeechToText { s ->
                        val n = s.size / 16_000
                        order += n
                        if (n == 1) gate.await()
                        "burst of $n s"
                    },
                )
            t.begin(0)
            t.pcm(pcm(ms = 1_000), 48_000, 1)
            t.end(0)
            t.begin(1)
            t.pcm(pcm(ms = 2_000), 48_000, 1)
            t.end(1)
            advanceUntilIdle()
            // The second waits behind the first, which waits on the gate.
            assertEquals(listOf(1), order)
            assertEquals(TranscriptLine.State.Pending, t.lines.value[1].state)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf(1, 2), order)
            assertEquals(listOf("burst of 1 s", "burst of 2 s"), t.lines.value.map { (it.state as TranscriptLine.State.Text).text })
        }

    @Test
    fun aNewSessionStartsEmptyAndTheOldOnesTextNeverLandsInIt() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val t =
                transcriber(
                    SpeechToText {
                        gate.await()
                        "from the last session"
                    },
                )
            t.begin(4)
            t.pcm(pcm(ms = 1_000), 48_000, 1)
            t.end(4)
            advanceUntilIdle()
            t.reset()
            assertTrue(t.lines.value.isEmpty())
            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(t.lines.value.isEmpty())
        }

    @Test
    fun aBurstCutOffByTheSessionsEndIsDropped() =
        runTest {
            val t = transcriber()
            t.begin(0)
            t.pcm(pcm(ms = 1_000), 48_000, 1)
            t.abandon()
            // Its end never comes now; if it did, it would find nothing open.
            t.end(0)
            advanceUntilIdle()
            assertTrue(t.lines.value.isEmpty())
            assertTrue(heard.isEmpty())
        }

    @Test
    fun aSpokenBurstIsTranscribedFromTheMicBesideOneHeardUnderTheSameNumber() =
        runTest {
            val t = transcriber()
            t.begin(0)
            t.pcm(pcm(ms = 1_000), 48_000, 1)
            // Talking holds the playback mid-burst: both are open at once, and each keeps its own audio.
            t.begin(0, mine = true)
            t.mic.pcm(pcm(ms = 2_000, rate = 16_000), 16_000, 1)
            t.end(0, mine = true)
            t.end(0)
            advanceUntilIdle()
            assertEquals(
                listOf(false to "words 16000", true to "words 32000"),
                t.lines.value.map { it.mine to (it.state as TranscriptLine.State.Text).text },
            )
        }

    @Test
    fun aCancelledSpokenBurstLeavesTheHeardOneAlone() =
        runTest {
            val t = transcriber()
            t.begin(3)
            t.begin(3, mine = true)
            t.mic.pcm(pcm(ms = 1_000, rate = 16_000), 16_000, 1)
            t.abandon(mine = true)
            t.end(3, mine = true)
            assertEquals(listOf(false), t.lines.value.map { it.mine })
            assertEquals(
                TranscriptLine.State.Listening,
                t.lines.value
                    .single()
                    .state,
            )
        }

    @Test
    fun theTranscriptKeepsItsNewestLines() {
        var lines = emptyList<TranscriptLine>()
        for (b in 0 until 60) lines = withLine(lines, TranscriptLine(b, b.toLong(), TranscriptLine.State.Pending), max = 50)
        assertEquals((10 until 60).toList(), lines.map { it.burst })
        // A line already there is replaced in place.
        lines = withLine(lines, TranscriptLine(20, 20, TranscriptLine.State.NoSpeech), max = 50)
        assertEquals(50, lines.size)
        assertEquals(TranscriptLine.State.NoSpeech, lines[10].state)
    }

    // [ms] of a quiet tone at [rate], as the player's tap (48 kHz) or the recorder's (16 kHz) hands it on.
    private fun pcm(
        ms: Int,
        rate: Int = 48_000,
    ): ByteBuffer {
        val samples = rate / 1_000 * ms
        return ByteBuffer.allocateDirect(samples * 2).order(ByteOrder.nativeOrder()).apply {
            val shorts = asShortBuffer()
            for (i in 0 until samples) shorts.put(((i % 48) * 100 - 2_400).toShort())
        }
    }
}
