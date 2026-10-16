package app.getknit.knit.mesh

import app.getknit.knit.data.settings.BluetoothDownloadLimit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one rule the fetch and the bubble ask (ADR 2026-10.js2p): over the limit by its declared size, and not tapped. */
class BluetoothFetchGateTest {
    private val oneMb = BluetoothDownloadLimit.MB_1.bytes

    @Test
    fun anAttachmentDeclaredOverTheLimitWaitsForTheTap() {
        assertTrue(BluetoothFetchGate.fastOnly(declaredSize = 1_000_001L, limitBytes = oneMb, approved = false, own = false))
        assertFalse("at the limit is under it", BluetoothFetchGate.fastOnly(1_000_000L, oneMb, approved = false, own = false))
        assertFalse(BluetoothFetchGate.fastOnly(5_000_000L, BluetoothDownloadLimit.MB_5.bytes, approved = false, own = false))
    }

    @Test
    fun theTapOurOwnSendNoLimitOrNoDeclaredSizeNeverWait() {
        assertFalse("tapped", BluetoothFetchGate.fastOnly(2_000_000L, oneMb, approved = true, own = false))
        assertFalse("our own", BluetoothFetchGate.fastOnly(2_000_000L, oneMb, approved = false, own = true))
        assertFalse("never ask", BluetoothFetchGate.fastOnly(2_000_000L, BluetoothDownloadLimit.NEVER.bytes, approved = false, own = false))
        assertFalse("an older sender declares nothing", BluetoothFetchGate.fastOnly(null, oneMb, approved = false, own = false))
    }

    @Test
    fun aFileAlwaysDeclaresItsSizeAndAPictureOnlyFromTheSmallestLimitUp() {
        assertTrue(BluetoothFetchGate.declaresSize(10L, named = true))
        assertFalse(BluetoothFetchGate.declaresSize(BluetoothFetchGate.DECLARE_SIZE_FLOOR - 1, named = false))
        assertTrue(BluetoothFetchGate.declaresSize(BluetoothFetchGate.DECLARE_SIZE_FLOOR, named = false))
        assertTrue(
            "every size a limit can gate is declared",
            BluetoothDownloadLimit.entries.mapNotNull { it.bytes }.all { it >= BluetoothFetchGate.DECLARE_SIZE_FLOOR },
        )
    }
}
