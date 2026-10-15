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

/**
 * The walkie's transcript (C-3.11-9): a burst heard becomes a line, its words stream in while it plays, and its text
 * settles once the model has finished it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalkieTranscriberTest {
    private var now = 1_000L
    private var modelReady = true
    private val logs = mutableListOf<String>()

    /** A model whose every stream hears "n samples" for the n samples it was fed: live while open, settled at the end. */
    private inner class FakeSpeech(
        private val opens: Boolean = true,
        // What finish answers, given the samples fed; null fails.
        private val final: (Int) -> String? = { "words $it" },
        private val lookCostMs: Long = 0,
        private val finishGate: CompletableDeferred<Unit>? = null,
    ) : SpeechToText {
        val streams = mutableListOf<FakeStream>()

        override suspend fun open(): SpeechStream? = if (opens) FakeStream().also { streams += it } else null

        inner class FakeStream : SpeechStream {
            val adds = mutableListOf<Int>()
            var looks = 0
            var finished = false
            var closed = false
            var failLook = false
            val fed get() = adds.sum()

            override suspend fun add(samples: FloatArray): Boolean {
                check(!closed)
                adds += samples.size
                return true
            }

            override suspend fun look(): Heard? {
                check(!closed)
                looks++
                now += lookCostMs
                return if (failLook) null else Heard("", "heard $fed")
            }

            override suspend fun finish(): Heard? {
                check(!closed)
                finishGate?.await()
                finished = true
                closed = true
                return final(fed)?.let { Heard(it) }
            }

            override suspend fun close() {
                closed = true
            }
        }
    }

    // On the test's scheduler but outside backgroundScope, whose work advanceUntilIdle leaves alone.

    /** A debug build's clip switch: [on] as set, every clip kept in [kept]. */
    private class FakeClips(
        override var on: Boolean = true,
    ) : WalkieClips {
        val kept = mutableListOf<WalkieClip>()

        override fun keep(clip: WalkieClip) {
            kept += clip
        }
    }

    private fun TestScope.transcriber(
        speech: SpeechToText = FakeSpeech(),
        clips: WalkieClips? = null,
    ): WalkieTranscriber {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return WalkieTranscriber(
            speech = speech,
            ready = { modelReady },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            dispatcher = dispatcher,
            clock = { now },
            log = { logs += it },
            clips = clips,
        )
    }

    private fun WalkieTranscriber.line(index: Int = 0) = lines.value[index]

    @Test
    fun aBurstsWordsStreamInWhileItPlaysAndSettleAtItsEnd() =
        runTest {
            val speech = FakeSpeech()
            val t = transcriber(speech)
            t.begin(0)
            assertEquals(listOf(TranscriptLine(0, 1_000, TranscriptLine.State.Listening)), t.lines.value)
            t.pcm(pcm(ms = 2_000), 48_000, 1)
            advanceUntilIdle()
            // Still playing: the model has looked once, and the line shows what it heard.
            assertEquals(TranscriptLine.State.Listening, t.line().state)
            assertEquals("heard 32000", t.line().live)
            t.end(0)
            // Ended: the words so far stay up while the model finishes.
            assertEquals(TranscriptLine.State.Pending, t.line().state)
            assertEquals("heard 32000", t.line().live)
            advanceUntilIdle()
            val line = t.line()
            assertEquals(TranscriptLine.State.Text("words 32000"), line.state)
            assertEquals("", line.live)
            assertEquals(2_000, line.audioMs)
            assertEquals(1, line.looks)
            val stream = speech.streams.single()
            assertEquals(listOf(32_000), stream.adds)
            assertTrue(stream.finished)
        }

    @Test
    fun audioIsFedInTheOrderItCameAndLooksKeepTheCadence() =
        runTest {
            val speech = FakeSpeech()
            val t = transcriber(speech)
            t.begin(0)
            // Two seconds in the decoder's 100 ms of buffers, each fed before the next arrives.
            repeat(20) {
                t.pcm(pcm(ms = 100), 48_000, 1)
                advanceUntilIdle()
            }
            t.end(0)
            advanceUntilIdle()
            val stream = speech.streams.single()
            assertEquals(List(20) { 1_600 }, stream.adds)
            // A look every 500 ms of audio.
            assertEquals(4, stream.looks)
            assertEquals(TranscriptLine.State.Text("words 32000"), t.line().state)
        }

    @Test
    fun aSlowPhoneLooksLessOften() =
        runTest {
            // Each look takes a second and a half: the next must wait for as much audio.
            val speech = FakeSpeech(lookCostMs = 1_500)
            val t = transcriber(speech)
            t.begin(0)
            repeat(40) {
                t.pcm(pcm(ms = 100), 48_000, 1)
                advanceUntilIdle()
            }
            t.end(0)
            advanceUntilIdle()
            // At 0.5 s, then every 1.5 s after: 2.0 and 3.5.
            assertEquals(3, speech.streams.single().looks)
        }

    @Test
    fun theTextIsNeverLogged() =
        runTest {
            val t = transcriber(FakeSpeech(final = { "meet me at the gate" }))
            t.begin(0)
            t.pcm(pcm(ms = 1_000), 48_000, 1)
            t.end(0)
            advanceUntilIdle()
            assertTrue(logs.isNotEmpty())
            assertFalse(logs.any { "gate" in it })
        }

    @Test
    fun aSlippedThumbIsNoSpeechAndItsStreamIsClosedUnfinished() =
        runTest {
            val speech = FakeSpeech()
            val t = transcriber(speech)
            t.begin(0)
            t.pcm(pcm(ms = 200), 48_000, 1)
            // Fed as it played, so its stream is open by the release.
            advanceUntilIdle()
            t.end(0)
            advanceUntilIdle()
            assertEquals(TranscriptLine.State.NoSpeech, t.line().state)
            val stream = speech.streams.single()
            assertTrue(stream.closed)
            assertFalse(stream.finished)
        }

    @Test
    fun withoutTheModelABurstIsMarkedSoAndNoStreamOpens() =
        runTest {
            modelReady = false
            val speech = FakeSpeech()
            val t = transcriber(speech)
            t.begin(0)
            t.pcm(pcm(ms = 1_000), 48_000, 1)
            t.end(0)
            advanceUntilIdle()
            assertEquals(TranscriptLine.State.NoModel, t.line().state)
            assertTrue(speech.streams.isEmpty())
        }

    @Test
    fun aModelThatArrivesMidBurstIsFedTheWholeBurst() =
        runTest {
            modelReady = false
            val speech = FakeSpeech()
            val t = transcriber(speech)
            t.begin(0)
            t.pcm(pcm(ms = 1_000), 48_000, 1)
            advanceUntilIdle()
            modelReady = true
            t.pcm(pcm(ms = 1_000), 48_000, 1)
            t.end(0)
            advanceUntilIdle()
            assertEquals(listOf(32_000), speech.streams.single().adds)
            assertEquals(TranscriptLine.State.Text("words 32000"), t.line().state)
        }

    @Test
    fun anEmptyAnswerIsNoSpeechAndANullOneAFailure() =
        runTest {
            val answers = ArrayDeque(listOf<String?>("  ", null))
            val t = transcriber(FakeSpeech(final = { answers.removeFirst() }))
            for (b in 0..1) {
                t.begin(b)
                t.pcm(pcm(ms = 1_000), 48_000, 1)
                t.end(b)
            }
            advanceUntilIdle()
            assertEquals(listOf(TranscriptLine.State.NoSpeech, TranscriptLine.State.Failed), t.lines.value.map { it.state })
        }

    @Test
    fun aModelThatWillNotRunIsAFailure() =
        runTest {
            val t = transcriber(FakeSpeech(opens = false))
            t.begin(0)
            t.pcm(pcm(ms = 1_000), 48_000, 1)
            t.end(0)
            advanceUntilIdle()
            assertEquals(TranscriptLine.State.Failed, t.line().state)
        }

    @Test
    fun aLookTheModelFailsOnFailsTheBurstAndClosesItsStream() =
        runTest {
            val speech = FakeSpeech()
            val t = transcriber(speech)
            t.begin(0)
            t.pcm(pcm(ms = 100), 48_000, 1)
            advanceUntilIdle()
            val stream = speech.streams.single()
            stream.failLook = true
            t.pcm(pcm(ms = 1_000), 48_000, 1)
            t.pcm(pcm(ms = 1_000), 48_000, 1)
            t.end(0)
            advanceUntilIdle()
            assertTrue(stream.closed)
            assertFalse(stream.finished)
            assertEquals(1, speech.streams.size)
            assertEquals(TranscriptLine.State.Failed, t.line().state)
        }

    @Test
    fun aNewSessionStartsEmptyAndTheOldOnesTextNeverLandsInIt() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val t = transcriber(FakeSpeech(finishGate = gate))
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
    fun aBurstStillPlayingAtANewSessionIsClosedUnheard() =
        runTest {
            val speech = FakeSpeech()
            val t = transcriber(speech)
            t.begin(0)
            t.pcm(pcm(ms = 1_000), 48_000, 1)
            advanceUntilIdle()
            t.reset()
            t.end(0)
            advanceUntilIdle()
            val stream = speech.streams.single()
            assertTrue(stream.closed)
            assertFalse(stream.finished)
            assertTrue(t.lines.value.isEmpty())
        }

    @Test
    fun aBurstCutOffByTheSessionsEndIsDroppedAndItsStreamClosed() =
        runTest {
            val speech = FakeSpeech()
            val t = transcriber(speech)
            t.begin(0)
            t.pcm(pcm(ms = 1_000), 48_000, 1)
            t.abandon()
            // Its end never comes now; if it did, it would find nothing open.
            t.end(0)
            advanceUntilIdle()
            assertTrue(t.lines.value.isEmpty())
            assertTrue(speech.streams.all { it.closed && !it.finished })
        }

    @Test
    fun aSpokenBurstIsTranscribedFromTheMicBesideOneHeardUnderTheSameNumber() =
        runTest {
            val speech = FakeSpeech()
            val t = transcriber(speech)
            t.begin(0)
            t.pcm(pcm(ms = 1_000), 48_000, 1)
            // Talking holds the playback mid-burst: both are open at once, and each has its own stream.
            t.begin(0, mine = true)
            t.mic.pcm(pcm(ms = 2_000, rate = 16_000), 16_000, 1)
            t.end(0, mine = true)
            t.end(0)
            advanceUntilIdle()
            assertEquals(2, speech.streams.size)
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
            assertEquals(TranscriptLine.State.Listening, t.line().state)
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

    @Test
    fun aLookIsDueEveryIntervalOrAsMuchAudioAsTheLastTookToMake() {
        val gate = PassGate(intervalMs = 500)
        assertFalse(gate.fed(400.0))
        assertTrue(gate.fed(100.0))
        // A look that took 2 s: the next waits for 2 s of audio.
        gate.took(2_000)
        assertFalse(gate.fed(1_900.0))
        assertTrue(gate.fed(100.0))
        // Never past ten intervals, however slow the last look was.
        gate.took(60_000)
        assertFalse(gate.fed(4_999.0))
        assertTrue(gate.fed(1.0))
    }

    @Test
    fun heardTextJoinsWhatIsSettledAndWhatIsLive() {
        assertEquals("meet me at the gate", Heard("meet me", " at the gate ").text)
        assertEquals("at the gate", Heard("", "at the gate").text)
        assertEquals("", Heard(" ", "").text)
    }

    // [ms] of a quiet tone at [rate], as the player's tap (48 kHz) or the recorder's (16 kHz) hands it on.
    @Test
    fun withClipsOnAFinishedBurstIsKeptWholeWithItsFinalLine() =
        runTest {
            val clips = FakeClips()
            val t = transcriber(clips = clips)
            t.begin(3)
            t.pcm(pcm(ms = 1_000), 48_000, 1)
            advanceUntilIdle()
            t.pcm(pcm(ms = 500), 48_000, 1)
            t.end(3)
            advanceUntilIdle()
            val clip = clips.kept.single()
            assertEquals(24_000, clip.samples.size)
            assertEquals(TranscriptLine.State.Text("words 24000"), clip.line.state)
            assertEquals(3, clip.line.burst)
        }

    @Test
    fun withClipsOnASpokenBurstIsKeptAsMine() =
        runTest {
            val clips = FakeClips()
            val t = transcriber(clips = clips)
            t.begin(0, mine = true)
            t.mic.pcm(pcm(ms = 1_000, rate = 16_000), 16_000, 1)
            t.end(0, mine = true)
            advanceUntilIdle()
            val clip = clips.kept.single()
            assertTrue(clip.line.mine)
            assertEquals(16_000, clip.samples.size)
        }

    @Test
    fun withClipsOffNothingIsKept() =
        runTest {
            val clips = FakeClips(on = false)
            val t = transcriber(clips = clips)
            t.begin(0)
            t.pcm(pcm(ms = 1_000), 48_000, 1)
            t.end(0)
            advanceUntilIdle()
            assertEquals(TranscriptLine.State.Text("words 16000"), t.line().state)
            assertTrue(clips.kept.isEmpty())
        }

    @Test
    fun aBurstHeardWithNoModelIsStillKeptWithAllItsAudio() =
        runTest {
            modelReady = false
            val clips = FakeClips()
            val t = transcriber(clips = clips)
            t.begin(0)
            t.pcm(pcm(ms = 1_000), 48_000, 1)
            advanceUntilIdle()
            t.end(0)
            advanceUntilIdle()
            val clip = clips.kept.single()
            assertEquals(TranscriptLine.State.NoModel, clip.line.state)
            assertEquals(16_000, clip.samples.size)
        }

    @Test
    fun aSlippedThumbADroppedBurstAndALastSessionsBurstAreNotKept() =
        runTest {
            val clips = FakeClips()
            val gate = CompletableDeferred<Unit>()
            val t = transcriber(FakeSpeech(finishGate = gate), clips = clips)
            t.begin(0)
            t.pcm(pcm(ms = 100), 48_000, 1)
            t.end(0)
            t.begin(1, mine = true)
            t.mic.pcm(pcm(ms = 1_000, rate = 16_000), 16_000, 1)
            t.abandon(mine = true)
            t.begin(2)
            t.pcm(pcm(ms = 1_000), 48_000, 1)
            t.end(2)
            advanceUntilIdle()
            // Burst 2 is with the model when the session ends.
            t.reset()
            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(clips.kept.isEmpty())
        }

    @Test
    fun aClipIsACanonicalWavOfSixteenBitSamples() {
        val wav = wavOf(floatArrayOf(0f, 0.5f, -1f, 2f), rate = 16_000)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44 + 8, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(wav.size - 8, b.getInt(4))
        assertEquals("WAVEfmt ", String(wav, 8, 8))
        assertEquals(1, b.getShort(22).toInt())
        assertEquals(16_000, b.getInt(24))
        assertEquals(32_000, b.getInt(28))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4))
        assertEquals(8, b.getInt(40))
        assertEquals(listOf(0, 16_383, -32_767, 32_767), (0 until 4).map { b.getShort(44 + it * 2).toInt() })
    }

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
