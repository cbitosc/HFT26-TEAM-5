package app.getknit.knit.wear

import app.getknit.knit.wearstatus.MeshState
import app.getknit.knit.wearstatus.Plane
import app.getknit.knit.wearstatus.WearExtra
import app.getknit.knit.wearstatus.WearLink
import app.getknit.knit.wearstatus.WearStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class RingCodeTest {
    private fun status(
        links: List<Int>,
        state: MeshState = MeshState.Linked,
    ) = WearStatus(
        state = state,
        nearby = links.size,
        ble = Plane.Live,
        nan = Plane.Live,
        lora = Plane.Live,
        spool = Plane.Live,
        relayed = 0,
        stampSec = 0,
        extra = WearExtra(carrying = 0, far = 0, links = links),
    )

    private fun code(vararg links: Int) = RingCode.of(Shown(status(links.toList())))

    private val unlitRound = "0".repeat(48)

    /** A run of [n] dashes for one plane character: all lit but the last. */
    private fun run(
        n: Int,
        c: Char,
    ) = c.toString().repeat(n - 1) + "0"

    @Test
    fun `three links close the outer round after the cast-on dash`() {
        // Pinned: scripts/gen-watchface.py's face reads exactly this layout (ADR 2026-10.3y2s). 47 dashes in
        // thirds as even as whole dashes allow: 15, 16, 16.
        assertEquals(
            "L" + "0" + run(15, '1') + "121212121212121" + "0" + run(16, '2') + unlitRound,
            code(WearLink.BLE, WearLink.BLE or WearLink.NAN, WearLink.NAN),
        )
    }

    @Test
    fun `one and two links fill a third and two thirds`() {
        assertEquals("L" + "0" + run(15, '1') + "0".repeat(32) + unlitRound, code(WearLink.BLE))
        assertEquals("L" + "0" + run(15, '1') + run(16, '2') + "0".repeat(16) + unlitRound, code(WearLink.BLE, WearLink.NAN))
    }

    @Test
    fun `dash 0 of each round is the cast-on point and never lit`() {
        val c = code(*IntArray(8) { WearLink.BLE or WearLink.NAN or WearLink.LORA or WearLink.SPOOL })
        assertEquals('0', c[1])
        assertEquals('0', c[1 + 48])
    }

    @Test
    fun `a fourth link starts the inner round`() {
        val c = code(WearLink.BLE, WearLink.BLE, WearLink.BLE, WearLink.NAN)
        assertEquals("0" + run(15, '1') + run(16, '1') + run(16, '1'), c.substring(1, 49))
        assertEquals("0" + run(15, '2') + "0".repeat(32), c.substring(49))
    }

    @Test
    fun `six links close both rounds`() {
        val round = "0" + run(15, '1') + run(16, '1') + run(16, '1')
        assertEquals("L$round$round", code(*IntArray(6) { WearLink.BLE }))
    }

    @Test
    fun `seven and eight links share the inner round in runs as even as whole dashes allow`() {
        val eight = code(*IntArray(6) { WearLink.BLE }, WearLink.LORA, WearLink.SPOOL)
        // 47 in fifths: 9, 9, 10, 9, 10.
        assertEquals("0" + run(9, '1') + run(9, '1') + run(10, '1') + run(9, '3') + run(10, '4'), eight.substring(49))
        val seven = code(*IntArray(6) { WearLink.BLE }, WearLink.LORA)
        // 47 in quarters: 11, 12, 12, 12.
        assertEquals("0" + run(11, '1') + run(12, '1') + run(12, '1') + run(12, '3'), seven.substring(49))
    }

    @Test
    fun `a link on more planes stripes through them in plane order`() {
        val all = WearLink.BLE or WearLink.NAN or WearLink.LORA or WearLink.SPOOL
        assertEquals("12341234123412", code(all).substring(2, 16))
        assertEquals("34343434343434", code(WearLink.SPOOL or WearLink.LORA).substring(2, 16))
    }

    @Test
    fun `every code is one state character and two rounds of dashes`() {
        for (c in listOf(code(), code(WearLink.BLE), RingCode.of(null), code(*IntArray(8) { WearLink.NAN }))) {
            assertEquals(RingCode.LENGTH, c.length)
        }
        assertEquals(97, RingCode.LENGTH)
    }

    @Test
    fun `no links or no extension leave the ring unlit but live`() {
        assertEquals("L" + "0".repeat(96), code())
        assertEquals("L" + "0".repeat(96), RingCode.of(Shown(status(emptyList()).copy(extra = null))))
    }

    @Test
    fun `an aged reading keeps its colours and says so`() {
        val links = listOf(WearLink.BLE, WearLink.NAN, WearLink.LORA, WearLink.SPOOL)
        val live = RingCode.of(Shown(status(links)))
        val aged = RingCode.of(Shown(status(links), agedSinceMs = 1L))
        assertEquals('A', aged[0])
        assertEquals(live.substring(1), aged.substring(1))
    }

    @Test
    fun `a resting mesh or an unreachable phone shows no link`() {
        val links = listOf(WearLink.BLE, WearLink.NAN)
        for (state in listOf(MeshState.Off, MeshState.Paused, MeshState.NoRadio)) {
            assertEquals(state.name, "R" + "0".repeat(96), RingCode.of(Shown(status(links, state))))
        }
        assertEquals("X" + "0".repeat(96), RingCode.of(null))
    }
}
