package app.getknit.knit.debug.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.webrtc.NetworkChangeDetector.ConnectionType
import org.webrtc.PeerConnection.AdapterType

class DefaultPathTest {
    @Test
    fun aNetworkIsPreferredByTheTypeLibwebrtcItselfGivesIt() {
        assertEquals(AdapterType.WIFI, DefaultPath.adapterFor(ConnectionType.CONNECTION_WIFI))
        // libwebrtc names a cellular network by its generation, and the preference must match it exactly.
        assertEquals(AdapterType.CELLULAR_4G, DefaultPath.adapterFor(ConnectionType.CONNECTION_4G))
        assertEquals(AdapterType.CELLULAR_5G, DefaultPath.adapterFor(ConnectionType.CONNECTION_5G))
        assertEquals(AdapterType.CELLULAR, DefaultPath.adapterFor(ConnectionType.CONNECTION_UNKNOWN_CELLULAR))
    }

    @Test
    fun aVpnIsPreferredByTheNetworkUnderItAndNothingUnknownIsPreferred() {
        assertEquals(AdapterType.WIFI, DefaultPath.adapterFor(ConnectionType.CONNECTION_VPN, ConnectionType.CONNECTION_WIFI))
        assertNull(DefaultPath.adapterFor(ConnectionType.CONNECTION_VPN, ConnectionType.CONNECTION_VPN))
        assertNull(DefaultPath.adapterFor(ConnectionType.CONNECTION_NONE))
        assertNull(DefaultPath.adapterFor(ConnectionType.CONNECTION_BLUETOOTH))
    }
}
