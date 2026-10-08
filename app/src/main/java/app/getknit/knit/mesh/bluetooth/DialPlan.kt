package app.getknit.knit.mesh.bluetooth

/**
 * The connection engine's glue between the sighted peers and its policies ([PromotionPolicy], [LonelyDialPolicy],
 * [ScanDemandPolicy]): which peers each one is handed, how their answers combine, and where a dial goes. Pure, free of
 * Android and of a clock, so [BluetoothMeshTransport]'s choices are asserted on the JVM.
 */
internal object DialPlan {
    /** The sighted peers the tie-break has [localId] dial: a smaller id, not [linked], no dial to it [inFlight]. */
    fun ordinaryCandidates(
        localId: String,
        snaps: List<BlePresenceTracker.Snapshot>,
        linked: Set<String>,
        inFlight: Set<String>,
    ): List<BlePresenceTracker.Snapshot> = snaps.filter { localId > it.nodeId && it.nodeId !in linked && it.nodeId !in inFlight }

    /**
     * Whether a sighted [nodeId] at [smoothedRssi] gives the scan promotion work: one [localId] would dial (a smaller
     * id), not [linked], at or above [rssiFloorDbm] so it can promote, and not [backingOff] (read last: it takes the
     * transport's lock). The scan's floor reads the same gate over every sighting ([scanCandidates]) as a fresh
     * sighting's boost does over one.
     */
    @Suppress("LongParameterList") // the gate's inputs, each a different source in the transport
    fun promotable(
        localId: String,
        nodeId: String,
        smoothedRssi: Double?,
        linked: Set<String>,
        rssiFloorDbm: Double,
        backingOff: (String) -> Boolean,
    ): Boolean = localId > nodeId && nodeId !in linked && smoothedRssi != null && smoothedRssi >= rssiFloorDbm && !backingOff(nodeId)

    /** The sighted peers [promotable] keeps: the scan's promotion work. */
    fun scanCandidates(
        localId: String,
        snaps: List<BlePresenceTracker.Snapshot>,
        linked: Set<String>,
        backoff: Set<String>,
        rssiFloorDbm: Double,
    ): Set<String> =
        snaps
            .filter { promotable(localId, it.nodeId, it.smoothedRssi, linked, rssiFloorDbm) { id -> id in backoff } }
            .mapTo(HashSet()) { it.nodeId }

    /**
     * The sighted peers [LonelyDialPolicy] may pick from: not [linked], advertising a PSM, [dialable] (an address
     * known), each marked whether it is in [backoff]. The policy applies the id order and its other gates.
     */
    fun lonelyCandidates(
        snaps: List<BlePresenceTracker.Snapshot>,
        linked: Set<String>,
        backoff: Set<String>,
        dialable: (String) -> Boolean,
    ): List<LonelyDialPolicy.Candidate> =
        snaps
            .filter { it.nodeId !in linked && it.psm != 0 && dialable(it.nodeId) }
            .map { LonelyDialPolicy.Candidate(it.nodeId, it.smoothedRssi, it.dwellMs, it.nodeId in backoff) }

    /** Whether a dial to a larger id than [localId] is [inFlight]: the tie-break never dials one, so it is a lonely dial. */
    fun lonelyDialInFlight(
        localId: String,
        inFlight: Set<String>,
    ): Boolean = inFlight.any { it > localId }

    /**
     * The promotion [ordinary] decided, with [lonely]'s dial added (ADR 2026-09.hj4a), then held to a debug [cap] if one
     * is set: a dial still in flight holds a slot too, so back-to-back ticks cannot overshoot it, and each eviction
     * frees one. [inFlightCount] is read only under a cap: it takes the transport's lock.
     */
    fun decision(
        ordinary: PromotionPolicy.Decision,
        lonely: LonelyDialPolicy.Candidate?,
        cap: Int?,
        linkCount: Int,
        inFlightCount: () -> Int,
    ): PromotionPolicy.Decision {
        val decided = if (lonely == null) ordinary else ordinary.copy(promote = ordinary.promote + lonely.nodeId)
        if (cap == null) return decided
        val room = cap - linkCount - inFlightCount() + decided.evict.size
        return decided.copy(promote = decided.promote.take(room.coerceAtLeast(0)))
    }

    /**
     * Where a dial goes, and whether it is the Coded address: a peer heard on Coded alone ([viaCoded]) is dialed at its
     * [coded] address, any other at its [oneM] one, else its Coded one. Null when the one it needs is not known.
     */
    fun <T : Any> target(
        viaCoded: Boolean,
        oneM: T?,
        coded: T?,
    ): Pair<T, Boolean>? {
        val device = if (viaCoded) coded else oneM ?: coded
        return device?.let { it to viaCoded }
    }

    /** The `via=` a dial's `bt initiating to` line carries while the Coded experiment runs ([codedOn]); empty when off. */
    fun via(
        codedOn: Boolean,
        viaCoded: Boolean,
    ): String =
        when {
            !codedOn -> ""
            viaCoded -> " via=coded"
            else -> " via=1m"
        }
}
