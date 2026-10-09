package app.getknit.knit.debug.call

import android.content.Context
import app.getknit.knit.call.DirectLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.webrtc.NetworkChangeDetector
import org.webrtc.NetworkMonitorAutoDetect
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

/**
 * libwebrtc's network list plus the call's Wi-Fi Direct group (docs/VOICE_CALLS.md §3.10, ADR 2026-10.kxnw).
 * Installed as the network monitor's detector factory, so the group is one more network the call gathers on.
 *
 * **Not `NetworkMonitorAutoDetect.setIncludeWifiDirect`.** Its delegate opens a `WifiP2pManager` channel every time
 * the monitor starts — every call, direct or not — and never closes it; an open channel keeps the P2P interface up
 * for ~150 s and blocks Wi-Fi Aware from coming back (`AndroidDirectWifi`). This reports the group from what the
 * call's own leg already knows ([link]: its interface, read here for its addresses), and opens nothing.
 *
 * **The group is reported as Ethernet** ([reportAs]), handle 0 as upstream's own Wi-Fi Direct delegate reports it.
 * libwebrtc ranks a candidate pair by its network cost before its priority — Ethernet 0, Wi-Fi 10, cellular 900 — so
 * the group's pair beats a LAN, IPv6-global, server-reflexive or relayed one, which all stay up as ICE's fallback.
 * Reported as Wi-Fi it would tie the phone's own Wi-Fi and lose to an IPv6 host pair on priority.
 */
internal class DirectNetworkDetector(
    private val observer: NetworkChangeDetector.Observer,
    context: Context,
    link: StateFlow<DirectLink?>,
    scope: CoroutineScope,
    private val log: (String) -> Unit,
) : NetworkChangeDetector {
    private val base = NetworkMonitorAutoDetect(observer, context)
    private val lock = Any()
    private var reported: NetworkChangeDetector.NetworkInformation? = null
    private val watch = scope.launch { link.collect { onLink(it) } }

    init {
        current = this
    }

    /** libwebrtc's own list of the phone's networks, for [DefaultPath] to name the default one as libwebrtc does. */
    fun networks(): List<NetworkChangeDetector.NetworkInformation> = base.activeNetworkList.orEmpty()

    override fun getCurrentConnectionType(): NetworkChangeDetector.ConnectionType = base.currentConnectionType

    override fun supportNetworkCallback(): Boolean = base.supportNetworkCallback()

    override fun getActiveNetworkList(): List<NetworkChangeDetector.NetworkInformation>? {
        val group = synchronized(lock) { reported } ?: return base.activeNetworkList
        return base.activeNetworkList.orEmpty() + group
    }

    override fun destroy() {
        if (current === this) current = null
        watch.cancel()
        base.destroy()
    }

    private fun onLink(link: DirectLink?) {
        val info = link?.let(::infoFor)
        val old =
            synchronized(lock) {
                reported.also { reported = info }
            }
        if (old != null) observer.onNetworkDisconnect(GROUP_HANDLE)
        if (info != null) {
            log("webrtc: the group is a network (${info.name}, ${info.type}, ${info.ipAddresses.size} address(es))")
            observer.onNetworkConnect(info)
        }
    }

    /** The group interface as libwebrtc sees a network; null while it carries no address libwebrtc would gather on. */
    private fun infoFor(link: DirectLink): NetworkChangeDetector.NetworkInformation? {
        val name = link.interfaceName ?: return null
        val addresses =
            runCatching { NetworkInterface.getByName(name)?.inetAddresses?.toList() }
                .getOrNull()
                .orEmpty()
                // libwebrtc ignores an IPv6 link-local: it carries no scope id through its own address type.
                .filter { it is Inet4Address || (it is Inet6Address && !it.isLinkLocalAddress) }
                .map { NetworkChangeDetector.IPAddress(it.address) }
        if (addresses.isEmpty()) return null
        return NetworkChangeDetector.NetworkInformation(
            name,
            reportAs,
            NetworkChangeDetector.ConnectionType.CONNECTION_NONE,
            GROUP_HANDLE,
            addresses.toTypedArray(),
            NetworkChangeDetector.NetworkSlice.NO_SLICE,
        )
    }

    companion object {
        /** Upstream's Wi-Fi Direct handle: no `android.net.Network` stands behind the group. */
        private const val GROUP_HANDLE = 0L

        /** The detector of the monitor running now (one per monitor start), or null while none runs. */
        @Volatile
        var current: DirectNetworkDetector? = null
            private set

        /**
         * What the group is reported as: Ethernet, so its pair wins on cost. A debug-bridge knob (`call op=nettype`)
         * for the trial that checks the steering is what wins, not the priority.
         */
        @Volatile
        var reportAs: NetworkChangeDetector.ConnectionType = NetworkChangeDetector.ConnectionType.CONNECTION_ETHERNET
    }
}
