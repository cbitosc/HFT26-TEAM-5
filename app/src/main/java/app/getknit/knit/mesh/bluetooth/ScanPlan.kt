package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.mesh.Peer

/**
 * The presence scan's glue between what it heard and its policies ([CodedPhyPolicy], [ScanDemandPolicy]): which peers
 * count as far, when Coded-only windows run and why, and what the transport publishes as reachable. Pure, so
 * [BluetoothMeshTransport]'s choices are asserted on the JVM.
 */
internal object ScanPlan {
    /** The sighted peers not [linked] that are heard on Coded alone ([CodedPhyPolicy.codedOnly]): the far ones. */
    fun far(
        snaps: List<BlePresenceTracker.Snapshot>,
        linked: Set<String>,
    ): List<String> = snaps.filter { it.nodeId !in linked && CodedPhyPolicy.codedOnly(it.codedLagMs) }.map { it.nodeId }

    /**
     * Whether the scan runs Coded-only windows at all ([CodedPhyPolicy.scanPhys] alternates them): only with Coded [on],
     * and then while this phone is [alone], hears a [far] peer, [hunt]s, or [chased] a peer another plane sees.
     */
    fun codedWindows(
        on: Boolean,
        alone: Boolean,
        far: List<String>,
        hunt: Boolean,
        chased: Boolean,
    ): Boolean = on && (alone || far.isNotEmpty() || hunt || chased)

    /** Why Coded-only windows came on, for their edge's log line: the first of [codedWindows]' reasons, else the chase. */
    fun codedWindowsWhy(
        alone: Boolean,
        hunt: Boolean,
        far: List<String>,
        chasedCoded: () -> List<String>,
    ): String =
        when {
            alone -> "alone"
            hunt -> "hunt"
            far.isNotEmpty() -> "codedOnly=$far"
            else -> "chase=${chasedCoded()}"
        }

    /**
     * What the transport publishes as reachable: every sighted peer in [snaps], and every peer it holds a link to
     * ([linkPeers]), so a linked peer stays nearby while the throttled scan has not re-sighted it. A link's [Peer] wins
     * over a sighting's on the same node id.
     */
    fun reachable(
        snaps: List<BlePresenceTracker.Snapshot>,
        linkPeers: Collection<Peer>,
    ): Set<Peer> {
        val byId = HashMap<String, Peer>()
        snaps.forEach { byId[it.nodeId] = Peer(it.nodeId, it.protoVersion, it.capabilities) }
        linkPeers.forEach { byId[it.nodeId] = it }
        return byId.values.toSet()
    }
}
