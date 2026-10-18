package app.getknit.knit.call

import app.getknit.knit.fieldlog.FieldEvent
import app.getknit.knit.fieldlog.FieldRecorder
import app.getknit.knit.mesh.protocol.CallPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * What the calls hand the field log (ADR 2026-10.a239's amendment): every word turned into a constant the line can
 * carry, and a live call's media read on [CallMediaSampler]'s cadence, in virtual time, while it is up and only then.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallFieldLogTest {
    @Test
    fun everyWireReasonHasItsOwnConstantAndAnUnknownOneIsOther() {
        assertEquals(CallEndReason.USER, CallEndReason.of(CallPayload.REASON_USER))
        assertEquals(CallEndReason.BUSY, CallEndReason.of(CallPayload.REASON_BUSY))
        assertEquals(CallEndReason.TIMEOUT, CallEndReason.of(CallPayload.REASON_TIMEOUT))
        assertEquals(CallEndReason.NO_ROUTE, CallEndReason.of(CallPayload.REASON_NO_ROUTE))
        assertEquals(CallEndReason.MEDIA, CallEndReason.of(CallPayload.REASON_MEDIA))
        assertEquals(CallEndReason.ERROR, CallEndReason.of(CallPayload.REASON_ERROR))
        assertEquals(CallEndReason.OTHER, CallEndReason.of(99))
        assertEquals(CallEndReason.OTHER, CallEndReason.of(null))
    }

    @Test
    fun aCandidatesWordsBecomeConstantsAndNothingBecomesNull() {
        assertEquals(IceType.SRFLX, IceType.of("srflx"))
        assertEquals(IceType.RELAY, IceType.of("RELAY"))
        assertEquals(IceType.OTHER, IceType.of("?"))
        assertNull(IceType.of(null))
        assertEquals(IceProtocol.TLS, IceProtocol.of("tls"))
        assertEquals(IceProtocol.OTHER, IceProtocol.of("quic"))
        assertNull(IceProtocol.of(null))
        assertEquals(IceProtocol.UDP, IceProtocol.ofLine("candidate:1 1 udp 2122260223 192.0.2.1 50000 typ host"))
        assertEquals(IceProtocol.TCP, IceProtocol.ofLine("a=candidate:2 1 tcp 1518280447 192.0.2.1 9 typ host tcptype active"))
        // A stats report's network types, and libwebrtc's adapter types by name.
        assertEquals(IceNetwork.WIFI, IceNetwork.of("wifi"))
        assertEquals(IceNetwork.CELLULAR, IceNetwork.of("cellular"))
        assertEquals(IceNetwork.CELLULAR, IceNetwork.of("CELLULAR_5G"))
        assertEquals(IceNetwork.ETHERNET, IceNetwork.of("ETHERNET"))
        assertEquals(IceNetwork.UNKNOWN, IceNetwork.of("ADAPTER_TYPE_ANY"))
        assertEquals(IceNetwork.OTHER, IceNetwork.of("wimax"))
        assertNull(IceNetwork.of(null))
    }

    @Test
    fun aWalkieSessionsTotalsAreKeptInWholeMilliseconds() {
        val stats =
            WalkieStats(
                transitMs = 412.5,
                jitterMs = 35.2,
                handMs = 11.0,
                frameOut = 310,
                frameIn = 305,
                chunksOut = 18,
                chunksFailed = 1,
                chunksIn = 11,
                chunksLost = 2,
                burstsOut = 3,
                burstsIn = 2,
            )
        assertEquals(
            FieldEvent.WalkieEnd(
                burstsOut = 3,
                burstsIn = 2,
                chunksOut = 18,
                chunksFailed = 1,
                chunksIn = 11,
                chunksLost = 2,
                transitMs = 413,
                jitterMs = 35,
                handMs = 11,
                frameOut = 310,
                frameIn = 305,
            ),
            stats.fieldEvent(),
        )
        assertEquals(FieldEvent.WalkieEnd(0, 0, 0, 0, 0, 0), WalkieStats().fieldEvent())
    }

    private class Rig(
        val state: MutableStateFlow<CallState?>,
        val lines: MutableList<FieldEvent>,
        val reads: MutableList<Boolean>,
    )

    /** A sampler over [state], reading a bare sample each time and failing the reads [fail] says to. */
    private fun TestScope.rig(fail: (Int) -> Boolean = { false }): Rig {
        val state = MutableStateFlow<CallState?>(null)
        val lines = Collections.synchronizedList(mutableListOf<FieldEvent>())
        val reads = Collections.synchronizedList(mutableListOf<Boolean>())
        CallMediaSampler(
            calls = state,
            read = { reconnecting ->
                reads += reconnecting
                check(!fail(reads.size)) { "no report" }
                FieldEvent.CallSample(reconnecting)
            },
            field = FieldRecorder { lines += it },
            scope = backgroundScope,
        ).start()
        runCurrent()
        return Rig(state, lines, reads)
    }

    private fun call(
        phase: CallPhase,
        walkie: Boolean = false,
        id: String = "c1",
    ) = CallState(id = id, peerId = "bob", outgoing = true, phase = phase, startedAt = 0L, walkie = walkie)

    @Test
    fun aLiveCallIsReadOnceItConnectsThenOnItsCadenceThroughAReconnectUntilItEnds() =
        runTest {
            val rig = rig()
            rig.state.value = call(CallPhase.Connecting)
            runCurrent()
            advanceTimeBy(HOUR_MS)
            runCurrent()
            assertTrue("nothing to read before the media is up", rig.lines.isEmpty())

            rig.state.value = call(CallPhase.Connected)
            runCurrent()
            advanceTimeBy(CallMediaSampler.FIRST_MS - 1)
            runCurrent()
            assertTrue(rig.lines.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf<FieldEvent>(FieldEvent.CallSample(reconnecting = false)), rig.lines.toList())

            // The window of §3.8 is the same call: the cadence goes on, not over, and the reading says so.
            rig.state.value = call(CallPhase.Reconnecting)
            runCurrent()
            advanceTimeBy(CallMediaSampler.EVERY_MS - 1)
            runCurrent()
            assertEquals(1, rig.reads.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(false, true), rig.reads.toList())

            rig.state.value = call(CallPhase.Ended)
            runCurrent()
            advanceTimeBy(HOUR_MS)
            runCurrent()
            assertEquals(2, rig.lines.size)
        }

    @Test
    fun aWalkieSessionHasNoMediaToRead() =
        runTest {
            val rig = rig()
            rig.state.value = call(CallPhase.Connected, walkie = true)
            runCurrent()
            advanceTimeBy(HOUR_MS)
            runCurrent()
            assertTrue(rig.reads.isEmpty())
        }

    @Test
    fun aReadingThatFailsCostsOnlyItselfAndTheNextComesOnTime() =
        runTest {
            val rig = rig(fail = { it == 1 })
            rig.state.value = call(CallPhase.Connected)
            runCurrent()
            advanceTimeBy(CallMediaSampler.FIRST_MS + CallMediaSampler.EVERY_MS)
            runCurrent()
            assertEquals(2, rig.reads.size)
            assertEquals(1, rig.lines.size)
        }

    @Test
    fun aNewCallStartsItsOwnCadence() =
        runTest {
            val rig = rig()
            rig.state.value = call(CallPhase.Connected)
            runCurrent()
            advanceTimeBy(CallMediaSampler.FIRST_MS - 1)
            // The next call connects before the first reading of this one was due: its clock starts over.
            rig.state.value = call(CallPhase.Connected, id = "c2")
            runCurrent()
            advanceTimeBy(CallMediaSampler.FIRST_MS - 1)
            runCurrent()
            assertTrue(rig.reads.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, rig.reads.size)
        }

    private companion object {
        const val HOUR_MS = 60 * 60_000L
    }
}
