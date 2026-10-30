package app.getknit.knit.mesh.bluetooth

/**
 * The peers this phone is hunting: each one a link of ours dropped at range, on the side that dials back
 * ([CodedPhyPolicy.huntAfterDrop]), held until it links again or its hold runs out (ADR 2026-10.yvn6, the 2026-10-04
 * amendment). It is the dialing side's twin of [CodedAdvertPace]: for the same drop, the far peer advertises fast and
 * this phone listens for it.
 *
 * The hold depends on whether Knit is in use. A phone in a pocket hunts for [PhyTuning.fastHoldMs], the same window the
 * far peer's advert stays fast. A phone with Knit open on a lit screen keeps hunting for [PhyTuning.huntInUseHoldMs]
 * from the drop, because the person is waiting on that peer. The drop time is kept, not a deadline, so a phone that
 * opens Knit two minutes after a drop picks the hunt up again, and one that closes it ten minutes in stops at once.
 *
 * A peer can also be held **cold** ([onCold], the 2026-10-20 amendment): an unlinked peer heard on Coded alone with no
 * drop behind it ([CodedPhyPolicy.coldHunt]). A cold hold is the hold [ColdSightings] granted, never the in-use bound —
 * a lit, in-use Knit hunts on its own already — and it ends early when the peer backs off or leaves presence
 * ([cancelCold]). Cold holds are kept apart from drops, so neither replaces the other's clock.
 *
 * Pure and thread-safe: the transport's link edges and its scan loop both touch it.
 */
internal class RangeHunt {
    private val droppedAt = HashMap<String, Long>()

    // A cold hold's end, by peer.
    private val coldUntil = HashMap<String, Long>()

    /** [peerNodeId]'s link just dropped at range at [now]. */
    @Synchronized
    fun onDrop(
        peerNodeId: String,
        now: Long,
    ) {
        droppedAt[peerNodeId] = now
    }

    /** [peerNodeId] linked again: it is no longer hunted. */
    @Synchronized
    fun onLinkUp(peerNodeId: String) {
        droppedAt.remove(peerNodeId)
        coldUntil.remove(peerNodeId)
    }

    /** [peerNodeId] was sighted cold at [now]: hunt it for [holdMs], whether Knit is in use or not. */
    @Synchronized
    fun onCold(
        peerNodeId: String,
        now: Long,
        holdMs: Long,
    ) {
        coldUntil[peerNodeId] = now + holdMs
    }

    /** The peers held cold at [now]. */
    @Synchronized
    fun cold(now: Long): Set<String> {
        coldUntil.values.removeAll { it <= now }
        return coldUntil.keys.toSet()
    }

    /** Ends [peerNodeId]'s cold hold, leaving a drop's alone. Returns whether one was held. */
    @Synchronized
    fun cancelCold(peerNodeId: String): Boolean = coldUntil.remove(peerNodeId) != null

    /**
     * The peers hunted at [now]: each for [holdMs] after its drop, or for [inUseHoldMs] while [inUse]. A peer past the
     * longer of the two is forgotten, so a later [inUse] edge cannot bring back a drop that old. Every peer held cold,
     * until its own hold's end, in use or not.
     */
    @Synchronized
    fun wanted(
        now: Long,
        inUse: Boolean,
        holdMs: Long,
        inUseHoldMs: Long,
    ): Set<String> {
        val longest = maxOf(holdMs, inUseHoldMs)
        droppedAt.values.removeAll { now - it >= longest }
        val hold = if (inUse) longest else holdMs
        return droppedAt.filterValues { now - it < hold }.keys + cold(now)
    }

    /** Forgets every peer: the transport stopped. */
    @Synchronized
    fun clear() {
        droppedAt.clear()
        coldUntil.clear()
    }
}
