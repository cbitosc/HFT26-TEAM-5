package app.getknit.knit.transfer

import java.util.concurrent.atomic.AtomicReference

/**
 * The one Wi-Fi Direct radio, shared by whoever needs a group — a file transfer, or a call between neighbours
 * (`call/DirectCallPath`) — and owned by one of them at a time. [radio] has a single channel and a global
 * [DirectWifi.release]: it removes whatever group is up and hands the radio back to the mesh, and the transfer's
 * manager calls it on every end path, whether or not it ever hosted. So nobody uses [radio] directly; each user
 * holds the view [leasedTo] returns, which takes the lease on its first host or join and gives it back on its own
 * release. A view whose owner does not hold the lease is refused ([TransferRefusal.Busy]) and releases nothing.
 */
class DirectWifiLease(
    private val radio: DirectWifi,
) {
    private val holder = AtomicReference<String?>(null)

    /** Who holds the radio right now, or null when it is free. */
    val owner: String? get() = holder.get()

    /** The radio as [owner] sees it: [owner]'s groups only, and nothing of anyone else's. */
    fun leasedTo(owner: String): DirectWifi = Leased(owner)

    private inner class Leased(
        private val owner: String,
    ) : DirectWifi {
        private fun heldByOther(): Boolean = holder.get().let { it != null && it != owner }

        private fun take() {
            if (!holder.compareAndSet(null, owner) && holder.get() != owner) {
                throw DirectWifiException("Wi-Fi Direct is in use (${holder.get()})", TransferRefusal.Busy)
            }
        }

        override fun refusal(): TransferRefusal? = if (heldByOther()) TransferRefusal.Busy else radio.refusal()

        override suspend fun host(
            credentials: GroupCredentials,
            timeoutMs: Long,
        ): HostedGroup {
            take()
            return radio.host(credentials, timeoutMs)
        }

        override suspend fun join(
            credentials: GroupCredentials,
            attemptMs: Long,
            ipv4: Boolean,
        ): JoinedGroup {
            take()
            return radio.join(credentials, attemptMs, ipv4)
        }

        override suspend fun release() {
            if (holder.get() != owner) return
            try {
                radio.release()
            } finally {
                holder.compareAndSet(owner, null)
            }
        }

        override suspend fun sweep() {
            // A held radio has a live group of ours on it; the sweep is for one nobody holds.
            if (holder.get() == null) radio.sweep()
        }
    }

    companion object {
        /** The owner name the file transfer holds the radio under; a call holds it under its call id. */
        const val TRANSFER = "transfer"
    }
}
