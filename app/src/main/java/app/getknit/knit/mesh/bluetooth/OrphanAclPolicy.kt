package app.getknit.knit.mesh.bluetooth

/**
 * Finds the LE connections a Bluetooth channel left up when it closed, lets each one go, and says what it found (#142,
 * ADR 2026-10.b8yf).
 *
 * An LE ACL can outlive its last L2CAP channel (Knit's CoC). On Android 12 the ATT fixed channel never idles out
 * unless a local GATT client held the link and left. On Android 16/17 the side that dialed never gets the no-app idle
 * timeout (the stack's `gatt_set_idle_timeout … status=false` at connect), so its ACL stays too. Such an orphan sits
 * idle for half an hour and takes a share of the controller's buffers from every other link, which halved a phone's
 * file drain. The
 * transport arms every ACL a channel opened on (a
 * GATT client attaches and leaves, [BluetoothMeshTransport]'s `armAcl`): at link-up, and when a channel closes without
 * becoming a link (a refused dialer, a failed HELLO, a dial that connected and then failed). The stack then drops the ACL
 * about 4 s after the channel closes on Android 16/17 (1–4 s on Android 12, by source). This is the net under those arms,
 * and the field log's record of where an arm missed.
 *
 * **The oracle is the ACL broadcasts** ([onAcl]), never `getConnectedDevices(GATT)`, which shows another device's LE
 * connection only while some app's GATT server is registered. An ended channel's address is watched only while its
 * connect edge was seen and no disconnect edge has followed, so a missed edge can make the policy miss an orphan, never
 * flag a connection that is gone. A link up on the address again (a relink over the same ACL) forgets it ([onLinkUp]).
 *
 * **The rules**, read on the transport's minute tick ([check]):
 * - An ACL still up [graceMs] after its channel ended, or after its last [Release], is an orphan. The arm drops it in
 *   about 4 s (1–4 s on Android 12), so the grace is the arm's margin.
 * - An orphan is released (attached and left again) at most [maxTries] times, then reported once as kept. One that goes
 *   down is reported once as cleared, with the tries it took. Exactly one [Report] per orphan, never one for an ACL that
 *   went down inside the grace.
 * - A disconnect is believed only once the address has stayed down for [graceMs]: Android 12 re-dials every dropped LE
 *   peer from its controller's accept list within 2–3 s, and that re-made ACL carries no channel. It is the same orphan
 *   back, so its tries carry over, and an entry that came back that way ([REDIALED_TRIES]) gets one try, not
 *   [maxTries]: each release it gets only invites the next re-dial. A peer's RPA rotation stops further re-dials but
 *   does not end an ACL already up, so a re-made orphan the tries did not clear stays until it times out, the adapter
 *   cycles, or a Knit dial reuses it (and arms it at link-up).
 * - An address in `busy` (a live link, a dial in flight) is left alone that round.
 * - With the arm switched off (`…debug.LINKTUNE --ez arm false`) nothing is released and an orphan is reported with
 *   `tries = 0`.
 * - At most [maxEntries] addresses are watched; the oldest goes first.
 * - At [stop], an ACL still up whose arm never attached, nor is still trying, is handed back once ([Unarmed]): nothing
 *   watches it after the transport stops.
 *
 * Pure and thread-safe: the edges arrive on the main thread, link ends on any, the tick on the transport's scope.
 */
internal class OrphanAclPolicy<D>(
    private val graceMs: Long = GRACE_MS,
    private val maxTries: Int = MAX_TRIES,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    /** What [check] asks of the transport for one watched address. */
    sealed interface Action<out D> {
        val address: String
        val nodeId: String
    }

    /** Attach a GATT client to the orphan's ACL and leave again: try [tries]. */
    data class Release<D>(
        override val address: String,
        override val nodeId: String,
        val device: D,
        val tries: Int,
        val endedAgoMs: Long,
    ) : Action<D>

    /**
     * One orphan's record: whether its channel ended a link ([linked]) or never became one, whether its arm attached,
     * how many releases it had, whether it went down, and how long it outlived its channel (to the disconnect when
     * [cleared], else to the report).
     */
    data class Report(
        override val address: String,
        override val nodeId: String,
        val linked: Boolean,
        val armed: Boolean,
        val tries: Int,
        val cleared: Boolean,
        val ageMs: Long,
    ) : Action<Nothing>

    /** An ACL still up at [stop] whose arm never attached: armed once more by the transport, and recorded. */
    data class Unarmed<D>(
        val address: String,
        val nodeId: String,
        val device: D,
        val linked: Boolean,
        val endedAgoMs: Long,
    )

    /** Where an address's arm stands: none made, one still trying, or one that attached or gave up. */
    enum class Arm { NONE, PENDING, ATTACHED, FAILED }

    private class Entry<D>(
        val nodeId: String,
        val device: D,
        val linked: Boolean,
        val endedAt: Long,
        var arm: Arm,
    ) {
        var up = true

        // When the grace started: the channel's end, the ACL's last connect edge, or the last release.
        var since = endedAt
        var downAt = 0L
        var tries = 0
        var orphan = false
        var reported = false

        // Came back up inside the grace after going down: the stack re-dialed it (Android 12's accept list).
        var redialed = false
    }

    // Addresses whose connect edge was seen and no disconnect since, oldest first.
    private val aclUp = LinkedHashSet<String>()

    // Where each address's latest arm stands, until its channel ends.
    private val arms = LinkedHashMap<String, Arm>()
    private val entries = LinkedHashMap<String, Entry<D>>()

    /** Releases asked since the transport was made. */
    @Volatile
    var released = 0
        private set

    /** Orphans reported since the transport was made. */
    @Volatile
    var reported = 0
        private set

    /** Ended channels' addresses being watched now. */
    val watching: Int @Synchronized get() = entries.size

    /** An ACL edge for [address] (`ACTION_ACL_CONNECTED` when [up]). */
    @Synchronized
    fun onAcl(
        address: String,
        up: Boolean,
        now: Long,
    ) {
        aclUp.remove(address)
        if (up) {
            aclUp.add(address)
            trim(aclUp, MAX_ACLS)
        }
        val entry = entries[address] ?: return
        if (up && !entry.up) {
            if (now - entry.downAt < graceMs) entry.redialed = true
            entry.up = true
            entry.since = now
        } else if (!up && entry.up) {
            if (now - entry.since >= graceMs) entry.orphan = true
            entry.up = false
            entry.downAt = now
        }
    }

    /** Whether [address]'s connect edge was seen and no disconnect edge since. */
    @Synchronized
    fun aclUp(address: String): Boolean = address in aclUp

    /** A link came up on [address]: whatever was watched there is that link's ACL now. */
    @Synchronized
    fun onLinkUp(address: String) {
        entries.remove(address)
        arms.remove(address)
    }

    /** An arm on [address] began. */
    @Synchronized
    fun onArming(address: String) = setArm(address, Arm.PENDING)

    /** An arm on [address] ended, having [attached] or not; a doorbell's attach reports here too. */
    @Synchronized
    fun onArmed(
        address: String,
        attached: Boolean,
    ) = setArm(address, if (attached) Arm.ATTACHED else Arm.FAILED)

    /**
     * A channel to [nodeId] on [address] ended at [now] — a link's ([linked]), or one that never became a link — and its
     * ACL is watched if it is known to be up.
     */
    @Synchronized
    fun onLinkEnded(
        address: String,
        nodeId: String,
        device: D,
        now: Long,
        linked: Boolean = true,
    ) {
        val arm = arms.remove(address) ?: Arm.NONE
        entries.remove(address)
        if (address !in aclUp) return
        entries[address] = Entry(nodeId, device, linked, now, arm)
        trim(entries.keys, maxEntries)
    }

    /** One round: what to release and what to report now. [busy] addresses (live links, dials) are skipped. */
    @Synchronized
    fun check(
        now: Long,
        busy: Set<String>,
        armOn: Boolean,
    ): List<Action<D>> {
        val out = ArrayList<Action<D>>()
        val gone = ArrayList<String>()
        for ((address, entry) in entries) {
            if (address in busy) continue
            val action = if (entry.up) whileUp(address, entry, now, armOn) else whileDown(address, entry, now, gone)
            action?.let(out::add)
        }
        gone.forEach(entries::remove)
        return out
    }

    /**
     * The transport is stopping and nothing will watch these ACLs again: hands back, once, each one still up whose arm
     * neither attached nor is still trying (and not already reported), counting it as reported, then forgets everything.
     */
    @Synchronized
    fun stop(now: Long): List<Unarmed<D>> {
        val out =
            entries
                .filter { (_, e) -> e.up && !e.reported && e.arm != Arm.ATTACHED && e.arm != Arm.PENDING }
                .map { (address, e) -> Unarmed(address, e.nodeId, e.device, e.linked, now - e.endedAt) }
        reported += out.size
        clear()
        return out
    }

    /** Forgets every edge and every watched address: the adapter went off or on, or the transport stopped. */
    @Synchronized
    fun clear() {
        aclUp.clear()
        arms.clear()
        entries.clear()
    }

    private fun setArm(
        address: String,
        arm: Arm,
    ) {
        arms[address] = arm
        trim(arms.keys, MAX_ACLS)
        entries[address]?.arm = arm
    }

    // Past the grace an ACL still up is an orphan: released while tries remain and the arm is on, else reported once.
    private fun whileUp(
        address: String,
        entry: Entry<D>,
        now: Long,
        armOn: Boolean,
    ): Action<D>? {
        if (now - entry.since < graceMs) return null
        entry.orphan = true
        val tries = if (entry.redialed) minOf(maxTries, REDIALED_TRIES) else maxTries
        return when {
            entry.reported -> {
                null
            }

            armOn && entry.tries < tries -> {
                entry.tries++
                entry.since = now
                released++
                Release(address, entry.nodeId, entry.device, entry.tries, now - entry.endedAt)
            }

            else -> {
                report(address, entry, cleared = false, ageMs = now - entry.endedAt)
            }
        }
    }

    // Down for a grace, an ACL is gone for good (Android 12's accept list re-dials within seconds): an orphan that went is
    // reported as cleared, unless its report already went.
    private fun whileDown(
        address: String,
        entry: Entry<D>,
        now: Long,
        gone: MutableList<String>,
    ): Action<D>? {
        if (now - entry.downAt < graceMs) return null
        gone += address
        return if (entry.orphan && !entry.reported) report(address, entry, cleared = true, ageMs = entry.downAt - entry.endedAt) else null
    }

    private fun report(
        address: String,
        entry: Entry<D>,
        cleared: Boolean,
        ageMs: Long,
    ): Report {
        entry.reported = true
        reported++
        return Report(address, entry.nodeId, entry.linked, entry.arm == Arm.ATTACHED, entry.tries, cleared, ageMs)
    }

    private fun trim(
        keys: MutableSet<String>,
        max: Int,
    ) {
        val it = keys.iterator()
        while (keys.size > max && it.hasNext()) {
            it.next()
            it.remove()
        }
    }

    companion object {
        /** How long an ACL may outlive its channel before it counts as an orphan: the arm drops one in about 4 s. */
        const val GRACE_MS = 15_000L

        /** Releases per orphan before it is reported as kept: bounds Android 12's accept-list re-dial churn. */
        const val MAX_TRIES = 2

        /** Releases for an orphan the stack already re-dialed once: a release only invites the next re-dial. */
        const val REDIALED_TRIES = 1

        /** Ended channels watched at once: a phone holds at most eight LE connections. */
        const val MAX_ENTRIES = 16

        // Connected addresses remembered at once; a missed disconnect edge must not grow the set without bound.
        private const val MAX_ACLS = 64
    }
}
