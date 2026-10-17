package app.getknit.knit.mesh.spool

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Gathers the holder queries the §9.5 rounds ask for ([AttachmentDeferPolicy]'s `askHolder`, ADR 2026-10.vy27) and
 * hands them to [send] as one batch per peer per [windowMs]. A round passes up to 32 attachments, each its own ask, and
 * the recipient answers at most 16 frames from us at a burst — our own want queries included — so one frame per peer
 * is what keeps the proof inside that budget.
 *
 * The flush runs on the mesh session ([scope]), which a stop or a pause cancels at any moment. So the batch is never
 * keyed on "whoever found it empty": an ask launches a flush whenever none is live, and a flush cut off mid-window
 * leaves its asks for the next one rather than switching the batching off for the rest of the process. With no session
 * an ask is dropped — the next session's rounds ask again.
 */
class HolderAskBatch(
    private val scope: () -> CoroutineScope?,
    private val send: suspend (peer: String, hashes: List<String>) -> Unit,
    private val windowMs: Long = WINDOW_MS,
) {
    // peer -> the hashes to ask it about, in ask order. Guarded by itself, as is [flush].
    private val pending = LinkedHashMap<String, LinkedHashSet<String>>()
    private var flush: Job? = null

    /** Queues a holder query for [hash] to [peer], sent with the rest of this window's in one batch per peer. */
    fun ask(
        hash: String,
        peer: String,
    ) {
        synchronized(pending) {
            pending.getOrPut(peer) { LinkedHashSet() }.add(hash)
            if (flush?.isActive == true) return
            val session = scope()?.takeIf { it.isActive }
            if (session == null) {
                pending.clear()
                return
            }
            flush = session.launch { flushAfterWindow() }
        }
    }

    private suspend fun flushAfterWindow() {
        delay(windowMs)
        // Taken, and the flush let go, in one step: an ask from here on starts the next window.
        val batch =
            synchronized(pending) {
                flush = null
                pending.map { (peer, hashes) -> peer to hashes.toList() }.also { pending.clear() }
            }
        batch.forEach { (peer, hashes) -> send(peer, hashes) }
    }

    companion object {
        /** How long a round's asks gather before one batch per peer goes out. */
        const val WINDOW_MS = 200L
    }
}
