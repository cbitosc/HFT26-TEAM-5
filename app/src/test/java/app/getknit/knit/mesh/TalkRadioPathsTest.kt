package app.getknit.knit.mesh

import app.getknit.knit.mesh.spool.ScopeSync
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The walkie's radio evidence (docs/VOICE_CALLS.md C-3.11-6, ADR 2026-10.79yb's tenth amendment). */
class TalkRadioPathsTest {
    private var now = 1_000_000L
    private val windowMs = 120_000L
    private val paths = TalkRadioPaths(clock = { now }, windowMs = windowMs)

    @Test
    fun aPeerHeardThroughALinkWeStillHoldHasAPathUntilTheWindowLapses() {
        paths.note("peer", hopId = "relay", kind = TransportKind.Bluetooth, sentAt = now)
        assertTrue(paths.pathTo("peer", setOf("relay")))
        now += 120_000
        assertTrue(paths.pathTo("peer", setOf("relay")))
        now += 1
        assertFalse(paths.pathTo("peer", setOf("relay")))
    }

    /** The field case of 2026-10-18: the hop that once carried the peer is still linked, but that is not enough alone. */
    @Test
    fun aPathIsThroughTheLinkThatCarriedTheFrameAndNoOther() {
        paths.note("peer", hopId = "relay", kind = TransportKind.WifiAware, sentAt = now)
        assertFalse("the hop is gone", paths.pathTo("peer", setOf("someone-else")))
        assertFalse("no links at all", paths.pathTo("peer", emptySet()))
        // A newer frame through another link moves the path there.
        paths.note("peer", hopId = "someone-else", kind = TransportKind.Bluetooth, sentAt = now)
        assertTrue(paths.pathTo("peer", setOf("someone-else")))
        assertFalse(paths.pathTo("peer", setOf("relay")))
    }

    @Test
    fun anOldFrameARelayReServesSaysNothingAboutNow() {
        // Custody hands a peer's frames to every new link for hours after the peer left.
        paths.note("peer", hopId = "carrier", kind = TransportKind.Bluetooth, sentAt = now - windowMs - 1)
        assertFalse(paths.pathTo("peer", setOf("carrier")))
        // One stamped exactly a window ago still counts.
        paths.note("peer", hopId = "carrier", kind = TransportKind.Bluetooth, sentAt = now - windowMs)
        assertTrue(paths.pathTo("peer", setOf("carrier")))
    }

    @Test
    fun aSpoolOrLoraIsNeverARadioPath() {
        paths.note("peer", hopId = ScopeSync.SPOOL_SOURCE_PREFIX + "relay", kind = TransportKind.Other, sentAt = now)
        paths.note("peer2", hopId = "board", kind = TransportKind.LoRa, sentAt = now)
        assertFalse(paths.pathTo("peer", setOf(ScopeSync.SPOOL_SOURCE_PREFIX + "relay")))
        assertFalse(paths.pathTo("peer2", setOf("board")))
    }

    @Test
    fun clearForgetsAndTheCapDropsTheStalest() {
        val small = TalkRadioPaths(clock = { now }, windowMs = 120_000, cap = 2)
        small.note("a", "h", TransportKind.Bluetooth, now)
        small.note("b", "h", TransportKind.Bluetooth, now)
        small.note("a", "h", TransportKind.Bluetooth, now)
        small.note("c", "h", TransportKind.Bluetooth, now)
        assertFalse("b was the stalest", small.pathTo("b", setOf("h")))
        assertTrue(small.pathTo("a", setOf("h")) && small.pathTo("c", setOf("h")))
        small.clear()
        assertFalse(small.pathTo("a", setOf("h")))
    }
}
