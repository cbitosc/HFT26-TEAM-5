package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Demand-driven, content-addressed image fetch over the mesh. A node that needs a blob it lacks asks
 * its direct neighbors ([want]); a neighbor that holds it serves it back over the file channel, and
 * one that doesn't pulls it on the asker's behalf ([onRequest] → [want]) so the asker's *next* ask finds
 * a holder one hop closer. The blob therefore walks hop-by-hop to any requester using only direct-neighbor
 * file transfer — no file-relay-by-destination is needed. The re-ask is the 60 s neighbor re-offer
 * ([reask], re-armed from the database by `MeshManager.rewantMissingBlobs`, ADR 2026-09.ptv8);
 * nothing is ever pushed to a peer that did not just ask, because the serving side cannot tell a peer
 * that still lacks the bytes from one whose copy is already on the way from somebody else — a clique
 * that pushed bought every recipient a copy per neighbor (work item #79, ADR 2026-09.4tx5).
 *
 * A blob whose bytes are already streaming in on a link (a key of [MeshTransport.arrivingFiles]) is neither
 * wanted nor re-asked for; a re-ask for one already queued toward that peer ([MeshTransport.fileInFlightTo])
 * ships nothing. Both are reads of the link, not memos here — a torn link clears its own state. The Bluetooth
 * stack's buffer sits between those two reads, so a peer asked before is not asked again while its file is
 * streaming in ([MeshTransport.fileArrivingFrom]), and a holder counts a fed file as in flight while its link goes on
 * feeding that peer (#121).
 *
 * The `blobreq` that drives [onRequest] is **unsigned** (see `MeshManager.verifyInbound`), so, like
 * [KeyExchange]/[PendingInbound], the bookkeeping is **bounded**: [fetching] is capped (oldest-first
 * eviction) and TTL-swept ([sweepExpired]); the [recentlyServed] serve-memo keeps its 45 s TTL plus a size
 * cap. A peer flooding requests for hashes we don't hold therefore costs bounded memory and work.
 *
 * A round of asks opens with one neighbour, the likeliest holder, and widens to every other neighbour only once that
 * ask's grace has passed with nothing arriving (work item #136, ADR 2026-10.f7gg). Every holder answers a fresh ask,
 * so asking them all at once bought one whole copy from each holder in range. The likeliest is the frame's author,
 * then a neighbour on the high-throughput plane ([MeshTransport.bulkRouteTo]: Wi-Fi Aware linked, then reachable),
 * then the neighbour the frame came from, then the rest. There is no negative answer on the wire, so a non-holder
 * asked first costs one grace and no more: the widened ask is what still reaches a holder ranked lower. A
 * neighbour that links during a widened round is asked when it links, and the next round opens on the tick no
 * sooner than [ROUND_MS] after this one, with one ask again.
 *
 * A transfer a link drop cut off resumes rather than restarts (work item #116, ADR 2026-10.wtyc): the link keeps
 * the prefix in [PartialBlobs], every ask names its length as the `blobreq` offset, a holder streams only the
 * rest ([onRequest]), and a spliced file that fails its hash check drops the prefix and asks from byte 0
 * ([onReceived]). A holder that predates the field serves the whole file, which lands as before.
 *
 * Pure (no Android/Room): the transport, blob storage, and identity are injected, so the recursion
 * can be unit-tested with [FakeLoopTransport] and a fake [BlobStore].
 */
class BlobExchange(
    private val transport: MeshTransport,
    private val store: BlobStore,
    private val selfId: suspend () -> String,
    private val onObtained: suspend (hash: String, path: String) -> Unit,
    // The prefixes of transfers a link drop cut off (#116): an ask names how many bytes of the blob we hold.
    private val partials: PartialBlobs,
    // A file spliced onto a kept prefix failed its hash check (the owner counts it).
    private val onSpliceRefused: () -> Unit = {},
    private val newRequestId: () -> String = { FrameId.new() },
    private val now: () -> Long = System::currentTimeMillis,
    // Bounds (overridable so tests can exercise eviction with small values).
    private val maxFetching: Int = MAX_FETCHING,
    private val maxServeMemo: Int = MAX_SERVE_MEMO,
    private val fetchTtlMs: Long = FETCH_TTL_MS,
    // How long a round's first ask has before the rest are asked ([FIRST_ASK_GRACE_MS]). Injectable so `mesh/lab`
    // can hold a round open past any wall-clock stall of its own; production takes the default.
    private val firstAskGraceMs: Long = FIRST_ASK_GRACE_MS,
    // Where a first ask's grace is timed (ADR 2026-10.f7gg): the mesh session's scope, read at each arm. Null (no
    // session, or a rig) leaves the widening to the next want, link-up or tick.
    private val timers: () -> CoroutineScope? = { null },
    // Injected logger (the class stays Android-free): DI passes android.util.Log; tests default silent.
    private val log: (String) -> Unit = {},
) {
    // Guards fetching + recentlyServed (both mutated across coroutines); held only for short map ops, never
    // across a suspend send (the send-outside-the-lock methods below).
    private val lock = Any()

    // hash -> the want and its round of asks ([Want]) — dedups outbound requests and orders eviction. Insertion-ordered
    // so eviction is oldest-wanted-first; TTL-swept on the last want so a never-arriving fetch is reclaimed. Guarded
    // by [lock].
    private val fetching =
        object : LinkedHashMap<String, Want>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Want>): Boolean = size > maxFetching
        }

    // "hash|nodeId" -> when we last enqueued that blob to that peer. The bound on an unsigned request flood
    // for a blob we hold, and the cover for the composite's fast-link grace, when a serve is held for the
    // NDP and sits on no link yet. A copy already queued or streaming on the link is refused by
    // [MeshTransport.fileInFlightTo] instead, whatever its age. The memo TTL is deliberately shorter than
    // the 60 s re-offer so a serve whose bytes were lost (link died mid-stream) still retries on the next
    // round; a size cap bounds it against a request flood between prunes. Guarded by [lock].
    private val recentlyServed =
        object : LinkedHashMap<String, Long>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > maxServeMemo
        }

    /**
     * Asks for [hash] unless we already hold it or its bytes are streaming in on a link or going into the store right
     * now. The first ask goes to the likeliest holder alone and the rest follow its grace ([step]); a repeat want
     * moves the same round on, never a second one. An arriving hash is not even marked: if the transfer dies the
     * database re-arms it on the next tick (`rewantMissingBlobs`), and the memo is never the source.
     *
     * [author] is who posted the frame that names the blob, the one holder we can name; [via] is the neighbour that
     * frame came from; both are ranking hints, kept from the first want that gave them. [asker] is a neighbour we are
     * pulling it for ([onRequest]): it lacks the bytes, so this round does not ask it, but a later one does — it may
     * be the one path the bytes have to us by then.
     */
    suspend fun want(
        hash: String,
        author: String? = null,
        via: String? = null,
        asker: String? = null,
    ) {
        if (store.has(hash) || store.storing(hash) || hash in transport.arrivingFiles()) return
        recordWant(hash, now(), author, via, asker)
        step(hash, linkedNow())
    }

    /**
     * A neighbor linked, or the 60 s re-offer tick came round: every blob we still lack takes its next step — the
     * widened ask once the first one's grace is up, an ask of a neighbour new to this round, or the next round once
     * [ROUND_MS] has passed (handles late-joining holders, and is the re-ask that makes the hop-by-hop walk move).
     *
     * A neighbour streaming a file in to us is not asked again for a blob it was asked for before (#121). A link
     * delivers in order, so whatever that peer has already fed us sits behind that file in the stack's buffer, and a
     * holder whose feed outpaces the drain can have fed a whole photo there: it no longer counts that serve as in
     * flight, so a second ask buys a second copy. Every serve it would make now queues behind that stream anyway.
     */
    suspend fun reask() {
        // Drop the marks whose bytes we have since obtained, then step the rest. [onReceived] clears its own, but the
        // mesh is not the only plane that can satisfy a want: the spool saves an attachment and fires
        // `onAttachmentObtained` (`ScopeSync.fetchAttachment`), and a direct avatar push is ingested by
        // `InboundPipeline.onAvatarReceived` — neither routes through [onReceived], so without this the blob is
        // re-requested on every link-up for the whole [FETCH_TTL_MS] window and the neighbor re-serves bytes we
        // already hold. A hash whose bytes are on the way stays in the memo (the transfer may still die) but is not
        // asked for: the re-ask against a slow BLE transfer is what bought a second copy from every holder (#79).
        // "On the way" includes the store's own last step: past the link's end, a blob is screened before it is held
        // (#109), and a tick in that classification would otherwise ask for it again.
        val wanted = snapshotFetching()
        if (wanted.isEmpty()) return
        val linked = linkedNow()
        wanted.forEach { step(it, linked) }
    }

    /**
     * Neighbours that just linked, or linked again: none of them holds an ask of ours, since one sent before may have
     * died with the old link. Each is open to every round again, and a round whose first ask went to one of them
     * starts that first ask over. The next [want] or [reask] asks them — before #136 every link-up re-asked its
     * newcomer at once, and this keeps that (ADR 2026-10.f7gg). Call it ahead of the link-up's wants and re-ask.
     */
    fun linked(nodeIds: Collection<String>) {
        if (nodeIds.isEmpty()) return
        val ids = nodeIds.toSet()
        synchronized(lock) {
            fetching.values.forEach { want ->
                want.asked.removeAll(ids)
                if (!want.widened && want.first in ids) want.first = null
            }
        }
    }

    /**
     * One step of [hash]'s round of asks against the [linked] neighbours: the asks [nextAsk] decides on go out, and a
     * first ask's grace is timed so the widened ask follows it without waiting for the tick.
     */
    private suspend fun step(
        hash: String,
        linked: List<Candidate>,
    ) {
        if (store.has(hash)) {
            clearFetching(hash)
            partials.drop(hash) // obtained off-mesh: nothing left to resume
            return
        }
        if (store.storing(hash) || hash in transport.arrivingFiles()) return
        when (val next = synchronized(lock) { fetching[hash]?.let { nextAsk(it, linked, now()) } } ?: return) {
            is Step.Wait -> {
                armTimer(hash, next.ms)
            }

            is Step.First -> {
                val peer = next.to.peer
                log("blob ask ${hash.take(LOG_HASH_CHARS)}… → ${peer.nodeId} first (${next.why.label}), widen in ${next.graceMs} ms")
                // A holder serving a large attachment arms the Wi-Fi Aware link itself; only the larger node id of a
                // pair initiates, so our half of the mark is what brings it up when that is us.
                if (next.to.route == BulkRoute.Reachable) transport.expectBulkTransfer(peer.nodeId)
                transport.send(blobRequest(selfId(), hash), peer)
                armTimer(hash, next.graceMs)
            }

            is Step.Widen -> {
                log("blob ask ${hash.take(LOG_HASH_CHARS)}… → ${next.to.joinToString { it.peer.nodeId }} widened")
                val req = blobRequest(selfId(), hash)
                next.to.forEach { transport.send(req, it.peer) } // outside the lock
            }

            Step.Idle -> {}
        }
    }

    /**
     * Where [want]'s round stands at [t], against the [linked] neighbours, and the one mutation that moves it on —
     * the asks it decides on are marked here, under [lock], so two steps racing never send the same ask twice. Pure:
     * no sends, no suspends.
     */
    private fun nextAsk(
        want: Want,
        linked: List<Candidate>,
        t: Long,
    ): Step {
        firstAskWait(want, linked, t)?.let { return it }
        if ((want.widened || want.first == null) && t - want.roundAt >= ROUND_MS) want.openRound(t)
        val open = linked.filter { it.peer.nodeId !in want.asked && !(it.streaming && it.peer.nodeId in want.askedEver) }
        return when {
            open.isEmpty() -> {
                Step.Idle
            }

            want.widened -> {
                open.forEach { want.mark(it.peer.nodeId) }
                Step.Widen(open)
            }

            else -> {
                openRoundWith(want, open, t)
            }
        }
    }

    /**
     * The wait while a round's first ask still has its grace, or null once it is up — and then the round is widened.
     * The first holder may still be feeding us what sits ahead of our copy on its link (#121): its grace starts
     * again when that stream ends. One whose link is gone can serve nothing on it, so its grace is up at once.
     */
    private fun firstAskWait(
        want: Want,
        linked: List<Candidate>,
        t: Long,
    ): Step.Wait? {
        val first = want.first
        if (first == null || want.widened) return null
        val holder = linked.firstOrNull { it.peer.nodeId == first }
        val left =
            when {
                holder == null -> {
                    0L
                }

                holder.streaming -> {
                    want.firstAt = t
                    want.graceMs
                }

                else -> {
                    want.firstAt + want.graceMs - t
                }
            }
        if (left > 0) return Step.Wait(left)
        want.widened = true
        return null
    }

    /** A round opens with the likeliest holder of the [open] neighbours alone. */
    private fun openRoundWith(
        want: Want,
        open: List<Candidate>,
        t: Long,
    ): Step.First {
        val pick = open.minWith(compareBy<Candidate>({ rankOf(want, it) }, { it.peer.nodeId }))
        want.mark(pick.peer.nodeId)
        want.first = pick.peer.nodeId
        want.firstAt = t
        // A holder that can raise the Wi-Fi Aware link waits for it before it falls back to Bluetooth.
        val linkWait = if (pick.route == BulkRoute.Reachable) CompositeMeshTransport.BULK_GRACE_MS else 0L
        want.graceMs = firstAskGraceMs + linkWait
        return Step.First(pick, want.graceMs, rankOf(want, pick))
    }

    /**
     * How likely [c] is to hold [want]'s blob, best first: its author (the one holder we can name), a neighbour whose
     * copy would ride the high-throughput plane (linked over it, then reachable on it), the neighbour the frame came
     * from (a carrier pulls what it carries), then anyone.
     */
    private fun rankOf(
        want: Want,
        c: Candidate,
    ): Rank =
        when {
            c.peer.nodeId == want.author -> Rank.Author
            c.route == BulkRoute.Linked -> Rank.FastLink
            c.route == BulkRoute.Reachable -> Rank.FastReach
            c.peer.nodeId == want.via -> Rank.SourceHop
            else -> Rank.Neighbour
        }

    /** The linked neighbours as a round reads them: each one's plane for a file, and whether it is streaming to us. */
    private fun linkedNow(): List<Candidate> =
        transport.neighbors.value.map { Candidate(it, transport.bulkRouteTo(it.nodeId), transport.fileArrivingFrom(it.nodeId)) }

    /**
     * Steps [hash] again after [ms], when a first ask's grace is up or its holder's stream may have ended. One timer per
     * want; with no session there is none, and the next want, link-up or tick steps it instead.
     */
    private fun armTimer(
        hash: String,
        ms: Long,
    ) {
        // A stopped session's scope would never run the timer's body; the next want, link-up or tick steps it instead.
        val scope = timers()?.takeIf { it.isActive } ?: return
        val arm =
            synchronized(lock) {
                val want = fetching[hash]
                if (want == null || want.timerArmed) return@synchronized false
                want.timerArmed = true
                true
            }
        if (!arm) return
        scope
            .launch {
                delay(ms)
                synchronized(lock) { fetching[hash]?.timerArmed = false } // before the step, which may arm the next
                step(hash, linkedNow())
            }.invokeOnCompletion { cause ->
                // Cancelled with the session it ran on: the next session's first step arms afresh.
                if (cause != null) synchronized(lock) { fetching[hash]?.timerArmed = false }
            }
    }

    /**
     * A point-to-point, unsigned blob request wrapped for the transport: `relay = false` so the router
     * never floods it (it propagates hop-by-hop via [onRequest]), and an empty signature (blob requests
     * are unsigned by design — see `MeshManager.verifyInbound`).
     */
    private fun blobRequest(
        me: String,
        hash: String,
    ): WireEnvelope {
        // A cut transfer's kept prefix makes the ask a resume: a holder that knows the field streams only the rest,
        // and one that doesn't sends the whole file, which lands as it always has (#116).
        val offset = partials.length(hash).takeIf { it > 0 }
        val env =
            RelayEnvelope(
                type = FrameType.BLOB_REQ,
                id = newRequestId(),
                senderId = me,
                payload = WireCodec.encodePayload(BlobReqContent(hash, offset)),
            )
        return WireEnvelope(relay = false, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env))
    }

    /**
     * A neighbor asked us for [hash]: serve it if held, else pull it ourselves so its next ask finds us
     * holding it. Nothing is remembered about the asker — it asks again on its own 60 s tick while it
     * still lacks the bytes, and stops the moment they arrive from anyone. [offset] is how many bytes of it the
     * asker kept from a cut transfer; only the rest is streamed (#116).
     */
    suspend fun onRequest(
        hash: String,
        fromNodeId: String,
        offset: Long? = null,
    ) {
        val peer = Peer(fromNodeId)
        val file = store.fileFor(hash)
        if (file != null) {
            // Holding the bytes is what decides a serve; a missing mime row only costs us the type.
            val mime = store.mimeFor(hash) ?: FALLBACK_MIME
            // Its copy is still on the link (queued or streaming) — an older build's 60 s re-ask, or one
            // whose serve waits behind a multi-minute blob to the same peer — or was enqueued inside the
            // memo: don't ship a second.
            if (transport.fileInFlightTo(fromNodeId, hash) || servedRecently(hash, fromNodeId)) return
            // A resume streams from the asker's offset: up to the whole length, so a prefix that is already complete
            // gets an empty tail and its end. Anything else (the peer's claim) is a whole file; the link checks the
            // offset again against the bytes it opens (FramedLink.streamFile).
            val from = offset?.takeIf { it in 1..file.length() } ?: 0L
            if (!transport.sendFile(file, peer, FileMeta(FileKind.ATTACHMENT, hash, mime, offset = from))) {
                forgetServed(hash, fromNodeId) // nothing went out — let the next ask retry at once
            }
            return
        }
        want(hash, asker = fromNodeId)
    }

    /**
     * A blob we wanted arrived over a link: persist it and notify. [resumedFrom] is the byte its stream started at
     * when the link spliced it onto a prefix we kept (#116).
     */
    suspend fun onReceived(
        hash: String,
        mime: String,
        srcPath: String,
        resumedFrom: Long = 0,
    ) {
        val stored = store.saveIncoming(hash, mime, srcPath)
        if (stored == null) {
            // A whole file that fails its hash is the holder's bytes alone: a kept prefix stays, and the tick asks
            // again as it always has. A spliced one may have failed on the prefix.
            if (resumedFrom > 0) reaskFromZero(hash)
            return
        }
        partials.drop(hash) // nothing left to resume
        clearFetching(hash)
        onObtained(hash, stored.absolutePath)
    }

    /**
     * A file spliced onto [hash]'s kept prefix failed its hash check: drop the prefix (it may be what was wrong) and
     * open a new round of asks for the whole blob now, rather than resume onto it again (#116). Only when the drop removed
     * a live prefix — so a peer cannot trigger the ask without first planting one — and never while the bytes are
     * already arriving on a link (ADR 2026-09.4tx5). The holder that sent the tail answers on its next tick: its
     * serve memo is unchanged, and other holders answer at once.
     */
    private suspend fun reaskFromZero(hash: String) {
        onSpliceRefused()
        if (!partials.drop(hash)) return
        if (store.has(hash) || store.storing(hash) || hash in transport.arrivingFiles()) return
        val t = now()
        synchronized(lock) { fetching.getOrPut(hash) { Want(t, null, null, null) }.apply { wantedAt = t }.openRound(t) }
        step(hash, linkedNow())
    }

    /** Drops fetches whose last-want time has aged past the TTL — a never-arriving blob is reclaimed and
     *  re-armed from the database on the next tick. The cap, not this, is the security bound. Returns the
     *  number reclaimed. */
    fun sweepExpired(): Int =
        synchronized(lock) {
            val cutoff = now() - fetchTtlMs
            val iterator = fetching.values.iterator()
            var removed = 0
            while (iterator.hasNext()) {
                if (iterator.next().wantedAt < cutoff) {
                    iterator.remove()
                    removed++
                }
            }
            removed
        }

    // --- Guarded bookkeeping (short, non-suspend; the suspend methods above send outside these) ---

    /** Records a want for [hash] (insert, or refresh its time and fill in hints it lacked; oldest-first eviction). */
    private fun recordWant(
        hash: String,
        nowMs: Long,
        author: String?,
        via: String?,
        asker: String?,
    ) = synchronized(lock) {
        val want = fetching[hash]
        if (want == null) {
            fetching[hash] = Want(nowMs, author, via, asker)
        } else {
            want.wantedAt = nowMs // mutated in place, so insertion order (the eviction order) is kept
            if (want.author == null) want.author = author
            if (want.via == null) want.via = via
            asker?.let { want.asked += it } // it lacks the bytes: not this round
        }
    }

    /** Oldest-wanted-first snapshot of the fetching hashes, copied under the lock so callers iterate safely. */
    private fun snapshotFetching(): List<String> = synchronized(lock) { fetching.keys.toList() }

    /** Removes [hash] from the fetching set (its blob arrived). */
    private fun clearFetching(hash: String) = synchronized(lock) { fetching.remove(hash) }

    /**
     * True if we already enqueued [hash] to [nodeId] within [SERVE_MEMO_MS]; otherwise stamps the pair and
     * returns false (the caller serves). Prunes expired pairs first; the map's size cap bounds it against a
     * flood between prunes.
     */
    private fun servedRecently(
        hash: String,
        nodeId: String,
    ): Boolean =
        synchronized(lock) {
            val t = now()
            recentlyServed.entries.removeAll { t - it.value >= SERVE_MEMO_MS }
            val key = "$hash|$nodeId"
            if (recentlyServed.containsKey(key)) return@synchronized true
            recentlyServed[key] = t // triggers the size-cap eviction if over maxServeMemo
            false
        }

    /** Forgets a serve stamp (its send failed) so the next ask retries at once. */
    private fun forgetServed(
        hash: String,
        nodeId: String,
    ) = synchronized(lock) { recentlyServed.remove("$hash|$nodeId") }

    /**
     * A blob we lack and how far this round of asks for it has got (ADR 2026-10.f7gg). A round opens with one ask of
     * the likeliest holder ([first]); once its grace has passed with nothing arriving, every other neighbour is asked
     * at once ([widened]), and one that links later in that round is asked when it links. Mutated under [lock] only.
     */
    private class Want(
        var wantedAt: Long,
        var author: String?,
        var via: String?,
        asker: String?,
    ) {
        // Asked this round, or known to lack the bytes this round: the neighbour we are pulling them for.
        val asked = HashSet<String>().apply { asker?.let(::add) }

        // Asked in any round: a file streaming in from one of these may have our copy behind it (#121).
        val askedEver = HashSet<String>()
        var roundAt = wantedAt
        var first: String? = null
        var firstAt = 0L
        var graceMs = 0L
        var widened = false
        var timerArmed = false

        fun mark(nodeId: String) {
            asked += nodeId
            askedEver += nodeId
        }

        fun openRound(t: Long) {
            asked.clear()
            roundAt = t
            first = null
            widened = false
        }
    }

    /** A linked neighbour as a round reads it ([linkedNow]). */
    private class Candidate(
        val peer: Peer,
        val route: BulkRoute,
        val streaming: Boolean,
    )

    /** What put a neighbour first in a round ([rankOf]), best first; the [label] is the log's. */
    private enum class Rank(
        val label: String,
    ) {
        Author("author"),
        FastLink("fast link"),
        FastReach("fast reach"),
        SourceHop("source hop"),
        Neighbour("neighbour"),
    }

    /** What [nextAsk] decided. */
    private sealed interface Step {
        /** Nothing to do until [ms] from now: a first ask's grace is running. */
        data class Wait(
            val ms: Long,
        ) : Step

        /** Open the round: ask [to] alone and widen after [graceMs]; [why] is what ranked it first, for the log. */
        data class First(
            val to: Candidate,
            val graceMs: Long,
            val why: Rank,
        ) : Step

        /** Ask every one of [to] at once. */
        data class Widen(
            val to: List<Candidate>,
        ) : Step

        /** Everyone this round has been asked; the tick opens the next. */
        data object Idle : Step
    }

    companion object {
        // How long a first ask has to bring a header in before every other neighbour is asked too. A holder over a live
        // link answers well inside it: the ask is one frame, and the serve reads the blob from the store and queues
        // it. A holder that can raise the Wi-Fi Aware link waits up to CompositeMeshTransport.BULK_GRACE_MS for it
        // before it falls back to Bluetooth, so a first ask of a reachable one gets that on top.
        const val FIRST_ASK_GRACE_MS = 5_000L

        // How much of a hash the ask log names.
        private const val LOG_HASH_CHARS = 8

        // Shorter than MeshManager's 60 s neighbor re-offer, so the periodic re-ask always passes the memo
        // and a serve whose bytes were lost (link died mid-stream) is retried on the next round.
        const val SERVE_MEMO_MS = 45_000L

        // The shortest gap between two rounds of asks for one blob. The serve memo's length, so the next round's
        // fresh ask always passes it; the 60 s tick opens a round when one is due.
        const val ROUND_MS = SERVE_MEMO_MS

        // Cap on distinct outstanding fetches — the memory bound on unsigned-blobreq-driven growth.
        private const val MAX_FETCHING = 256

        // Size cap on the in-flight serve memo (on top of its 45 s TTL).
        private const val MAX_SERVE_MEMO = 512

        // A never-arriving fetch ages out of [fetching] after this (hygiene; the cap is the bound). Generous
        // so a slow-but-live transfer isn't reclaimed, since blobs can be large.
        private const val FETCH_TTL_MS = 30 * 60_000L

        // What a served blob is called when our own store names no type for it. Matches MeshBlobStore.fileFor,
        // which materializes the temp file under the same fallback.
        private const val FALLBACK_MIME = "image/jpeg"
    }
}
