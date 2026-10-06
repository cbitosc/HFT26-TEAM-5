package app.getknit.knit.mesh.bluetooth

/** The connection priority a link is asked for: AOSP's HIGH (11.25–15 ms), BALANCED (30–50 ms), LOW_POWER. */
internal enum class TunePriority { HIGH, BALANCED, LOW_POWER }

/** The PHYs a link is asked for, both ways: 1M alone, 2M alone, or either, leaving the pick to the controller. */
internal enum class TunePhy { ONE_M, TWO_M, ONE_M_2M }

/**
 * One `…debug.LINKTUNE` ask (#120): the links whose node id starts with [node] (`all` for every link), and what to ask
 * each for, in this order — a [phy], a [priority], an ATT [mtu] — then [read] the PHY and link RSSI back. [close] lets
 * the links' tuners go instead, which frees their GATT registrations.
 */
internal data class LinkTuneRequest(
    val node: String,
    val priority: TunePriority? = null,
    val phy: TunePhy? = null,
    val mtu: Int? = null,
    val read: Boolean = false,
    val close: Boolean = false,
) {
    /** Whether this asks anything of a link; a bare report makes no tuner. */
    val asks: Boolean get() = priority != null || phy != null || mtu != null || read
}

/**
 * One link as its tuner last heard it. [params] arrive only when the parameters change after the client registers.
 * [mtu] is what the exchange settled on and [mtuAsked] what was asked: from Android 14 the first ask on an ACL
 * exchanges 517 whatever it names, and a later one exchanges nothing. [refused] says why an ask was not made: an iPhone
 * link, or a PHY asked while the Coded experiment runs on this phone. [codedOwned]: a Coded handle steps this link.
 */
internal data class LinkTuneStatus(
    val nodeId: String,
    val address: String?,
    val attached: Boolean,
    val txPhy: LinkPhy? = null,
    val rxPhy: LinkPhy? = null,
    val params: LinkParams? = null,
    val paramsStatus: Int? = null,
    val mtu: Int? = null,
    val mtuAsked: Int? = null,
    val rssi: Int? = null,
    val codedOwned: Boolean = false,
    val refused: String? = null,
)

/** What `…debug.LINKTUNE` reports: the links it asked, and how many links feed a file right now. */
internal data class LinkTuneReport(
    val feeding: Int,
    val links: List<LinkTuneStatus>,
)

/**
 * The debug bridge's handle on per-link tuning (#120), bound by [BluetoothMeshTransport] while it runs, and only on a
 * debug build: the same shape as [CodedPhyDiag], so the bridge needs no `android.bluetooth` import or transport
 * reference. A measuring tool for the drain trial; nothing in the mesh asks a link for anything through it.
 */
internal object LinkTuneDiag {
    @Volatile var tune: (suspend (LinkTuneRequest) -> LinkTuneReport)? = null
}
