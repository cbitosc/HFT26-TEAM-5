package app.getknit.knit.ui.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.webrtc.PeerConnection.AdapterType
import org.webrtc.PeerConnection.ContinualGatheringPolicy
import org.webrtc.PeerConnection.IceTransportsType
import org.webrtc.PeerConnection.PortPrunePolicy

class CallConfigurationTest {
    @Test
    fun aCallPrunesItsTurnPortsByPriorityAndStillGathersContinually() {
        val config = callConfiguration(emptyList(), relayOnly = false, direct = false, directAsEthernet = false, prune = true)
        // C-7.6-26: the best relay on each network is kept, the lower ones released.
        assertEquals(PortPrunePolicy.PRUNE_BASED_ON_PRIORITY, config.turnPortPrunePolicy)
        // The deprecated switch would override the policy, so it stays off.
        @Suppress("DEPRECATION")
        assertFalse(config.pruneTurnPorts)
        // Commit 930fe3ca: a first call of a process gathers on networks the monitor reports late.
        assertEquals(ContinualGatheringPolicy.GATHER_CONTINUALLY, config.continualGatheringPolicy)
        assertEquals(IceTransportsType.ALL, config.iceTransportsType)
    }

    @Test
    fun theMeasurementSwitchKeepsEveryAllocationAndChangesNothingElse() {
        val pruned = callConfiguration(emptyList(), relayOnly = true, direct = true, directAsEthernet = true, prune = true)
        val kept = callConfiguration(emptyList(), relayOnly = true, direct = true, directAsEthernet = true, prune = false)
        assertEquals(PortPrunePolicy.NO_PRUNE, kept.turnPortPrunePolicy)
        for (config in listOf(pruned, kept)) {
            // C-3.7-3 and §3.10 stay as they were.
            assertEquals(IceTransportsType.RELAY, config.iceTransportsType)
            assertEquals(AdapterType.ETHERNET, config.networkPreference)
            assertEquals(ContinualGatheringPolicy.GATHER_CONTINUALLY, config.continualGatheringPolicy)
        }
    }
}
