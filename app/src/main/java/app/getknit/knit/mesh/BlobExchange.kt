package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.BlobHasContent
import app.getknit.knit.mesh.protocol.BlobHasItem
import app.getknit.knit.mesh.protocol.BlobHaveContent
import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

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
 * feeding that peer (#121). A file stays arriving past its end until its consumer has taken it
 * ([MeshTransport.fileTaken], here in [onReceived]), and every read takes the bytes' path backwards — the link, the
 * store's own mark, then the store — so a blob that moves on between two reads is caught by the later one (#138).
 *
 * The `blobreq` that drives [onRequest] is **unsigned** (see `MeshManager.verifyInbound`), so, like
 * [KeyExchange]/[PendingInbound], the bookkeeping is **bounded**: [fetching] is capped (oldest-first
 * eviction) and TTL-swept ([sweepExpired]); the [recentlyServed] serve-memo keeps its 45 s TTL plus a size
 * cap. A peer flooding requests for hashes we don't hold therefore costs bounded memory and work.
 *
 * **Who is asked (ADR 2026-10.6e5t).** A neighbour whose link advertises [Protocol.CAP_BLOB_HAVE] is first *queried*
 * — a `blobhave` it answers with a `blobhas` ([onHaveQuery]): which of the blobs it holds, is already sending us, or is
 * fetching itself. Once every queried neighbour has answered, or [HolderQuery.waitMs] has passed, the best holder by
 * plane and load is asked alone ([tierOf]); one that brings nothing within its grace is queried again once, and then
 * the next best is asked. Answers live for one cycle ([CYCLE_MS]); the next cycle queries everyone again, since
 * phones move. A query is not an ask: it serves nothing, pulls nothing, and stamps no memo.
 *
 * Every neighbour without the bit — the iOS port today, an older Android build, every neighbour while
 * [HolderQuery.enabled] is off — and a queried one still silent past the wait gets the round of ADR 2026-10.f7gg,
 * unchanged: one blind ask of the likeliest holder (author, then the Wi-Fi Aware plane, then the hop the frame came
 * from), and every other at once once its grace has passed, never one at a time without a negative answer. When
 * nobody in reach holds it or is fetching it, the source hop is asked to pull it through, and every neighbour that
 * said it lacks it from the next empty cycle on; a neighbour asked to pull it is queried again shortly, a few times,
 * since it holds the blob the moment its own fetch lands. A cycle in which every neighbour said it lacks the blob
 * doubles the wait for the next one ([Want.cycleGap]): a blob nobody in reach holds costs a query and a pull a few
 * times an hour, not every tick, while a neighbour that links is still queried at once.
 *
 * Any wait on a holder counts its grace from the first look that finds it no longer streaming to us: our copy may sit
 * behind that stream in the stack's buffer (#121), so a grace that ran during the stream would ask past it.
 *
 * A transfer a link drop cut off resumes rather than restarts (work item #116, ADR 2026-10.wtyc): the link keeps
 * the prefix in [PartialBlobs], every ask names its length as the `blobreq` offset, a holder streams only the
 * rest ([onRequest]), and a spliced file that fails its hash check drops the prefix and asks from byte 0
 * ([onReceived]). A holder that predates the field serves the whole file, which lands as before.
 *
 * Pure (no Android/Room): the transport, blob storage, and identity are injected, so the recursion
 * can be unit-tested with [FakeLoopTransport] and a fake [BlobStore].
 */
@Suppress("TooManyFunctions", "LargeClass", "LongParameterList") // one fetch state machine and its two wire doors
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
    // How long an ask has to bring a header in before the next holder is tried ([FIRST_ASK_GRACE_MS]). Injectable so
    // `mesh/lab` can hold one open past any wall-clock stall of its own; production takes the default.
    private val firstAskGraceMs: Long = FIRST_ASK_GRACE_MS,
    // Whether this build queries neighbours for the holders of a blob (ADR 2026-10.6e5t; `BuildConfig.BLOB_QUERY`),
    // and how long it waits for their answers. Off, every neighbour gets ADR 2026-10.f7gg's blind round. Answering is
    // never gated: [onHaveQuery] runs on every build.
    private val holderQuery: HolderQuery = HolderQuery(),
    // What happened, for the owner's counters (`MeshMetrics.onBlobEvent`).
    private val onEvent: (BlobEvent) -> Unit = {},
    // Where an ask's grace is timed (ADR 2026-10.f7gg): the mesh session's scope, read at each arm. Null (no
    // session, or a rig) leaves the next step to the next want, answer, link-up or tick.
    private val timers: () -> CoroutineScope? = { null },
    // Injected logger (the class stays Android-free): DI passes android.util.Log; tests default silent.
    private val log: (String) -> Unit = {},
) {
    /**
     * Whether this node queries capable neighbours before it asks ([enabled]), how long it waits for their answers, and
     * when it queries again a neighbour that said it is fetching the blob, or that it asked to pull it through. The
     * waits run on wall time, so `mesh/lab` lengthens them past any stall of its own and moves them with a clock jump;
     * production takes the defaults.
     */
    data class HolderQuery(
        val enabled: Boolean = false,
        val waitMs: Long = QUERY_WAIT_MS,
        val fetchingRecheckMs: Long = FETCHING_RECHECK_MS,
    )

    // Guards fetching + recentlyServed + the query books (all mutated across coroutines); held only for short map
    // ops, never across a suspend send (the send-outside-the-lock methods below).
    private val lock = Any()

    // hash -> the want and where its fetch stands ([Want]) — dedups outbound requests and orders eviction.
    // Insertion-ordered so eviction is oldest-wanted-first; TTL-swept on the last want so a never-arriving fetch is
    // reclaimed. Guarded by [lock].
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

    // Queries a step decided on and the next [flushQueries] sends: peer -> hashes, so one frame per peer carries every
    // hash a batch of steps queued for it. Empty between entry points. Guarded by [lock].
    private val outbox = LinkedHashMap<String, LinkedHashSet<String>>()

    // Queries sent, by envelope id: whom, when, and which hashes — an answer counts only for a hash its query named
    // (`re`). Capped and pruned at a cycle's age. Guarded by [lock].
    private val sentQueries =
        object : LinkedHashMap<String, SentQuery>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SentQuery>): Boolean = size > MAX_SENT_QUERIES
        }

    // Per-peer answer budget: the bound on an unsigned query flood ([onHaveQuery]). Guarded by [lock].
    private val answerBudget =
        object : LinkedHashMap<String, Bucket>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bucket>): Boolean = size > MAX_ANSWER_BUCKETS
        }

    /**
     * Asks for [hash] unless we already hold it or its bytes are streaming in or landing on a link or going into the
     * store right now. A repeat want moves the same fetch on, never a second one. An arriving hash is not even marked:
     * if the transfer dies the database re-arms it on the next tick (`rewantMissingBlobs`), and the memo is never the
     * source.
     *
     * [author] is who posted the frame that names the blob, the one holder we can name; [via] is the neighbour that
     * frame came from; both are ranking hints for neighbours that cannot be queried, kept from the first want that
     * gave them, and [via] is the first asked to pull it through when nobody in reach holds it. [asker] is a neighbour
     * we are pulling it for ([onRequest]): it lacks the bytes, so this cycle neither queries nor asks it, but a later
     * one does — it may be the one path the bytes have to us by then.
     */
    suspend fun want(
        hash: String,
        author: String? = null,
        via: String? = null,
        asker: String? = null,
    ) {
        if (onTheWayOrHeld(hash)) return
        recordWant(hash, now(), author, via, asker)
        step(hash, linkedNow())
        flushQueries()
    }

    /**
     * [want] for each of [wanted] (hash → author), stepped together so their queries share one frame per neighbour.
     * The database re-arm (`MeshManager.rewantMissingBlobs`) runs at every link-up, ahead of [reask].
     */
    suspend fun wantAll(wanted: List<Pair<String, String?>>) {
        if (wanted.isEmpty()) return
        val linked = linkedNow()
        for ((hash, author) in wanted) {
            if (onTheWayOrHeld(hash)) continue
            recordWant(hash, now(), author, via = null, asker = null)
            step(hash, linked)
        }
        flushQueries()
    }

    /**
     * A neighbor linked, or the 60 s re-offer tick came round: every blob we still lack takes its next step — the
     * query owed to a neighbour new to this cycle, the next holder once an ask's grace is up, the widened blind ask, or
     * the next cycle once [CYCLE_MS] has passed (handles late-joining holders, and is the re-ask that makes the
     * hop-by-hop walk move).
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
        flushQueries()
    }

    /**
     * Neighbours that just linked, or linked again: none of them holds an ask or a query of ours, since one sent
     * before may have died with the old link. Each is open to every cycle again — queried at once if it can be, its
     * old answer and failure forgotten — and a fetch or blind first ask that went to one of them starts over. The
     * next [want] or [reask] queries or asks them — before #136 every link-up re-asked its newcomer at once, and this
     * keeps that (ADR 2026-10.f7gg). Call it ahead of the link-up's wants and re-ask.
     */
    fun linked(nodeIds: Collection<String>) {
        if (nodeIds.isEmpty()) return
        val ids = nodeIds.toSet()
        synchronized(lock) {
            fetching.values.forEach { want ->
                want.pullers.removeAll(ids)
                want.blindAsked.removeAll(ids)
                want.pulled.removeAll(ids)
                want.pullRechecks.keys.removeAll(ids)
                want.failed.removeAll(ids)
                want.reasked.removeAll(ids)
                ids.forEach {
                    want.queried.remove(it)
                    want.answers.remove(it)
                }
                if (want.fetch?.peer in ids) want.fetch = null
            }
        }
    }

    /**
     * One step of [hash]'s fetch against the [linked] neighbours: the queries [decide] owes go into the outbox (the
     * caller flushes them), its asks go out, and its next wake is timed so a grace, a wait for answers or a stream's
     * end is acted on without waiting for the tick.
     */
    private suspend fun step(
        hash: String,
        linked: List<Candidate>,
    ) {
        if (onTheWay(hash)) return
        if (store.has(hash)) {
            clearFetching(hash)
            partials.drop(hash) // obtained off-mesh: nothing left to resume
            return
        }
        val kept = partials.length(hash)
        val plan = synchronized(lock) { fetching[hash]?.let { decide(it, linked, kept, now()) }?.also { queue(hash, it) } } ?: return
        if (plan.asks.isNotEmpty()) {
            val req = blobRequest(selfId(), hash)
            for (ask in plan.asks) {
                // A holder serving a large attachment arms the Wi-Fi Aware link itself; only the larger node id of a
                // pair initiates, so our half of the mark is what brings it up when that is us.
                if (ask.arm) transport.expectBulkTransfer(ask.to.nodeId)
                transport.send(req, ask.to) // outside the lock
            }
            log(askLine(hash, plan))
        }
        plan.events.forEach(onEvent)
        plan.wake?.let { armTimer(hash, it) }
    }

    /** Moves the queries [plan] owes into the outbox. Under [lock]. */
    private fun queue(
        hash: String,
        plan: Plan,
    ) = plan.query.forEach { outbox.getOrPut(it) { LinkedHashSet() } += hash }

    /**
     * Where [w]'s fetch stands at [t], against the [linked] neighbours, and the one mutation that moves it on — the
     * queries and asks it decides on are marked here, under [lock], so two steps racing never send the same one twice.
     * Pure: no sends, no suspends. [kept] is the prefix we hold of the blob, so the plane is chosen by what is left.
     */
    private fun decide(
        w: Want,
        linked: List<Candidate>,
        kept: Long,
        t: Long,
    ): Plan {
        val p = Plan()
        w.fetch?.let { f ->
            // Queries go out mid-fetch too (a newcomer, a fetching neighbour's recheck): fresh answers for the fallback.
            owedQueries(w, capableOf(linked.filter { it.id !in w.pullers }), t, p)
            fetchWait(w, f, linked.firstOrNull { it.id == f.peer }, t, p)?.let { return p.waitFor(it) }
        }
        // A cycle closes only with no ask outstanding, so an ask's grace always runs out inside the cycle it began in.
        if (w.cycleDue(t)) openCycle(w, t)
        val live = linked.filter { it.id !in w.pullers }
        val capable = capableOf(live)
        owedQueries(w, capable, t, p)
        if (askHolder(w, capable, kept, t, p)) return p
        if (askBlind(w, live, capable, t, p)) return p
        pullThrough(w, capable, t, p)
        return p
    }

    /** The neighbours among [live] this build queries: those whose link says they answer, while querying is on. */
    private fun capableOf(live: List<Candidate>): List<Candidate> = if (holderQuery.enabled) live.filter { it.capable } else emptyList()

    /**
     * The queries [w] owes this cycle: each capable neighbour once, and again once its recheck is due ([recheckAt]) — a
     * recheck of one we asked to pull it through is counted against [PULL_RECHECKS].
     */
    private fun owedQueries(
        w: Want,
        capable: List<Candidate>,
        t: Long,
        p: Plan,
    ) {
        // A recheck due within the timer's coalescing window goes now: the timer stepped it early to share this frame.
        val owed = capable.filter { c -> c.id !in w.queried || recheckAt(w, c.id)?.let { t + TIMER_COALESCE_MS >= it } == true }
        for (c in owed) {
            val recheck = c.id in w.queried
            if (recheck && w.answers[c.id]?.state != BlobHasItem.FETCHING) w.pullRechecks.merge(c.id, 1, Int::plus)
            w.queried[c.id] = t
            p.query += c.id
        }
    }

    /**
     * When neighbour [id] is owed another query this cycle, or null if it is not: one that said it is fetching the blob
     * [HolderQuery.fetchingRecheckMs] after it said so, and one we asked to pull it through, that has not said it holds
     * it, as long after the pull or its last query, at most [PULL_RECHECKS] times — it holds the blob the moment its own
     * fetch lands, and the next cycle may be minutes off ([Want.cycleGap]). A trial's line waited 36 s on the tick. Only
     * in the first [PULL_RECHECK_DEAD_CYCLES] cycles of a dead streak, where a pull through more than one hop lands: past
     * them the blob is one nobody reaches, and its rechecks were most of what it cost (the 2026-10-05 re-run).
     */
    private fun recheckAt(
        w: Want,
        id: String,
    ): Long? {
        val at = w.queried[id] ?: return null
        val state = w.answers[id]?.state
        val pulling =
            id in w.pulled && id !in w.failed && state !in HELD && w.deadCycles < PULL_RECHECK_DEAD_CYCLES &&
                w.pullRechecks.getOrDefault(id, 0) < PULL_RECHECKS
        return when {
            state == BlobHasItem.FETCHING -> at + holderQuery.fetchingRecheckMs
            pulling -> maxOf(at, w.askedAt[id] ?: at) + holderQuery.fetchingRecheckMs
            else -> null
        }
    }

    /**
     * How long the outstanding fetch [f] still has, or null once it is over: failed, or cleared so the holder is asked
     * again. [c] is its holder as linked now, null once the link is gone (it can serve nothing on it). While the holder
     * streams anything to us, our copy may be queued behind it, so the grace counts from the first quiet look — never
     * from the last look during the stream, which once asked past a header 2 s from landing. A queried holder whose
     * grace is up is queried once more: still sending, it gets another grace; answering it holds the blob to a query
     * sent after our ask, our ask never reached it and it is asked again, once; otherwise the next holder is tried.
     */
    private fun fetchWait(
        w: Want,
        f: Fetch,
        c: Candidate?,
        t: Long,
        p: Plan,
    ): Long? {
        if (c == null) return w.over(f, "link gone")
        if (c.streaming) {
            f.quietSince = null
            return minOf(STREAM_POLL_MS, f.graceMs)
        }
        val quiet = f.quietSince ?: t.also { f.quietSince = it }
        (quiet + f.graceMs - t).takeIf { it > 0 }?.let { return it }
        return when {
            f.tier == Tier.Blind -> w.over(f, null)
            !c.capable || !holderQuery.enabled || f.confirms >= MAX_CONFIRMS -> w.over(f, "grace")
            else -> confirmWait(w, f, c, t, p)
        }
    }

    /** [fetchWait] once [f]'s grace is up: query its holder again, or read what it answered — see there. */
    private fun confirmWait(
        w: Want,
        f: Fetch,
        c: Candidate,
        t: Long,
        p: Plan,
    ): Long? {
        val sent =
            f.confirmAt ?: run {
                f.confirmAt = t
                f.confirms++
                w.answers.remove(c.id)
                w.queried[c.id] = t
                p.query += c.id
                p.events += BlobEvent.Confirm
                log("blob confirm ${f.hash8}… → ${c.id}")
                return holderQuery.waitMs
            }
        val a = w.answers[c.id]
        return when {
            a == null && t - sent < holderQuery.waitMs -> {
                sent + holderQuery.waitMs - t
            }

            a?.state == BlobHasItem.SENDING -> {
                f.quietSince = t
                f.confirmAt = null
                f.graceMs
            }

            a?.state == BlobHasItem.HAVE && c.id !in w.reasked -> {
                w.reasked += c.id
                w.fetch = null
                null
            }

            else -> {
                w.over(f, "confirm ${a?.let { stateLabel(it.state) } ?: "silent"}")
            }
        }
    }

    /**
     * Asks the best capable neighbour that said it holds the blob, or waits on one already sending it — true when [w]
     * is decided for this step (asked, or waiting for answers still due, a holder's memo floor, or a blind widening's
     * grace). The choice waits for every answer unless the best so far rides a live Wi-Fi Aware link.
     */
    private fun askHolder(
        w: Want,
        capable: List<Candidate>,
        kept: Long,
        t: Long,
        p: Plan,
    ): Boolean {
        val holders = capable.filter { it.id !in w.failed && w.answers[it.id]?.state in HELD }
        holders.firstOrNull { w.answers.getValue(it.id).state == BlobHasItem.SENDING }?.let { c ->
            val f = Fetch(c.id, w.hash8, Tier.Sending, t, graceFor(w, c, kept))
            w.fetch = f
            log("blob wait ${w.hash8}… on ${c.id} (sending), grace ${f.graceMs} ms")
            p.waitFor(f.graceMs)
            return true
        }
        val answering = capable.filter { it.id !in w.answers && t - w.queried.getValue(it.id) < holderQuery.waitMs }
        val widenGate = w.blindWidenedAt?.let { it + firstAskGraceMs - t }?.takeIf { it > 0 }
        val best = holders.filter { askable(w, it, t) }.minWithOrNull(byTier(w, kept))
        val answersIn = best != null && (answering.isEmpty() || tierOf(w, best, kept) == Tier.FastLink)
        if (best != null && widenGate == null && answersIn) {
            val tier = tierOf(w, best, kept)
            w.fetch = Fetch(best.id, w.hash8, tier, t, graceFor(w, best, kept))
            w.askedAt[best.id] = t
            p.ask(best.peer, arm = tier == Tier.FastArm, line = "fetch")
            p.why = "${tier.label}, load ${w.answers.getValue(best.id).load}"
            p.events += BlobEvent.Fetch
            p.waitFor(w.fetch!!.graceMs)
            return true
        }
        if (holders.isEmpty() && answering.isEmpty()) return false
        val floors = holders.mapNotNull { c -> w.askedAt[c.id]?.let { it + SERVE_MEMO_MS - t } }
        val answersDue = answering.map { w.queried.getValue(it.id) + holderQuery.waitMs - t }
        (floors + answersDue + listOfNotNull(widenGate)).filter { it > 0 }.minOrNull()?.let { p.waitFor(it) }
        return true
    }

    /**
     * ADR 2026-10.f7gg's round over the neighbours nobody can query — no [Protocol.CAP_BLOB_HAVE], or queried and still
     * silent past the wait: one blind ask of the likeliest, then every other at once once its grace is up. Never one by
     * one: without a negative answer, every non-holder ahead of a holder would cost a whole grace. True when it asked.
     */
    private fun askBlind(
        w: Want,
        live: List<Candidate>,
        capable: List<Candidate>,
        t: Long,
        p: Plan,
    ): Boolean {
        // A capable neighbour that answered said where it stands; one still silent here is past its wait.
        val answered = capable.mapTo(HashSet()) { it.id }.filterTo(HashSet()) { it in w.answers }
        val blind = live.filter { it.id !in answered && it.id !in w.blindAsked && !(it.streaming && it.id in w.askedAt) }
        if (blind.isEmpty()) return false
        if (!w.blindWidened) {
            val pick = blind.minWith(compareBy<Candidate>({ rankOf(w, it) }, { it.id }))
            // A holder that can raise the Wi-Fi Aware link waits for it before it falls back to Bluetooth.
            val grace = firstAskGraceMs + if (pick.route == BulkRoute.Reachable) CompositeMeshTransport.BULK_GRACE_MS else 0L
            w.blindAsked += pick.id
            w.askedAt[pick.id] = t
            w.fetch = Fetch(pick.id, w.hash8, Tier.Blind, t, grace)
            p.ask(pick.peer, arm = pick.route == BulkRoute.Reachable, line = "first")
            p.why = rankOf(w, pick).label
            p.events += BlobEvent.BlindAsk
            p.waitFor(grace)
            return true
        }
        w.blindWidenedAt = t
        blind.forEach {
            w.blindAsked += it.id
            w.askedAt[it.id] = t
            p.ask(it.peer, arm = false, line = "widened")
        }
        p.events += BlobEvent.BlindAsk
        return true
    }

    /**
     * Nobody in reach holds it. Unless a neighbour is fetching it, the neighbours that said they lack it are asked to
     * pull it through — the hop the frame came from first, every one of them from the next empty cycle on, or at once
     * when the hop is not among them. Either is queried again shortly ([recheckAt]), and the next cycle (the tick's)
     * queries everyone again.
     */
    private fun pullThrough(
        w: Want,
        capable: List<Candidate>,
        t: Long,
        p: Plan,
    ) {
        val fetching = capable.any { w.answers[it.id]?.state == BlobHasItem.FETCHING }
        val lacking = capable.filter { c -> w.answers[c.id]?.state.let { it != null && it !in HELD && it != BlobHasItem.FETCHING } }
        if (!fetching && w.pulled.isEmpty() && lacking.isNotEmpty()) {
            val hop = lacking.firstOrNull { it.id == w.via }
            val to = if (hop != null && w.emptyCycles == 0) listOf(hop) else lacking
            if (w.pulledAt == null) w.pulledAt = t // the cycle's first pull: its rechecks run out inside the cycle
            to.forEach {
                w.pulled += it.id
                w.askedAt[it.id] = t
                p.ask(it.peer, arm = false, line = "pull")
            }
            p.why = if (to.size == 1 && to[0] === hop) "via hop" else "wide"
            p.events += BlobEvent.PullThrough
        }
        capable.mapNotNull { recheckAt(w, it.id) }.minOrNull()?.let { p.waitFor((it - t).coerceAtLeast(1)) }
        // No timer for the next cycle: the 60 s tick ([reask]) or a link-up opens it, every hash in one step and its
        // queries in one frame per neighbour. A timer per hash came due a few ms apart and sent one-hash queries that
        // tripped the holder's budget (the bluetooth-reviewer's finding).
    }

    /** Whether [c] may be asked now: never before, or its answer is newer than our last ask, or the memo has lapsed. */
    private fun askable(
        w: Want,
        c: Candidate,
        t: Long,
    ): Boolean {
        val at = w.askedAt[c.id] ?: return true
        return w.answers.getValue(c.id).querySentAt > at || t - at >= SERVE_MEMO_MS
    }

    /**
     * The plane a holder's copy would come over, best first: a live Wi-Fi Aware link, one we can raise for a blob big
     * enough to be worth it (what is left of it past our kept prefix, against the composite's own arm gate), a
     * full-speed Bluetooth link with nothing streaming to us, then a slow (Coded) or busy one.
     */
    private fun tierOf(
        w: Want,
        c: Candidate,
        kept: Long,
    ): Tier {
        val left = w.answers[c.id]?.size?.let { it - kept }
        return when {
            c.route == BulkRoute.Linked -> Tier.FastLink
            c.route == BulkRoute.Reachable && left != null && left >= CompositeMeshTransport.BULK_MIN_BYTES -> Tier.FastArm
            !c.slow && !c.streaming -> Tier.Link
            else -> Tier.SlowLink
        }
    }

    /** Best holder first: by plane, then by how many files it is feeding (a Bluetooth holder splits its pace), then id. */
    private fun byTier(
        w: Want,
        kept: Long,
    ): Comparator<Candidate> = compareBy({ tierOf(w, it, kept).ordinal }, { w.answers[it.id]?.load ?: 0 }, { it.id })

    /** An ask's grace: the plain one, plus the composite's link wait when the holder may raise Wi-Fi Aware for it. */
    private fun graceFor(
        w: Want,
        c: Candidate,
        kept: Long,
    ): Long {
        val left = w.answers[c.id]?.size?.let { it - kept }
        val linkWait = c.route == BulkRoute.Reachable && (left == null || left >= CompositeMeshTransport.BULK_MIN_BYTES)
        return firstAskGraceMs + if (linkWait) CompositeMeshTransport.BULK_GRACE_MS else 0L
    }

    /**
     * How likely [c] is to hold [want]'s blob when it cannot say, best first: its author (the one holder we can name), a
     * neighbour whose copy would ride the high-throughput plane (linked over it, then reachable on it), the neighbour the
     * frame came from (a carrier pulls what it carries), then anyone.
     */
    private fun rankOf(
        want: Want,
        c: Candidate,
    ): Rank =
        when {
            c.id == want.author -> Rank.Author
            c.route == BulkRoute.Linked -> Rank.FastLink
            c.route == BulkRoute.Reachable -> Rank.FastReach
            c.id == want.via -> Rank.SourceHop
            else -> Rank.Neighbour
        }

    /** The log line for [plan]'s asks of [hash]; ADR 2026-10.f7gg's two lines are kept word for word for the blind round. */
    private fun askLine(
        hash: String,
        plan: Plan,
    ): String {
        val h = "${hash.take(LOG_HASH_CHARS)}…"
        val to = plan.asks.joinToString { it.to.nodeId }
        return when (plan.asks.first().line) {
            "first" -> "blob ask $h → $to first (${plan.why}), widen in ${plan.wake} ms"
            "widened" -> "blob ask $h → $to widened"
            "pull" -> "blob pull $h → $to (${plan.why})"
            else -> "blob fetch $h → $to (${plan.why}), grace ${plan.wake} ms${if (plan.asks.first().arm) ", armed" else ""}"
        }
    }

    /**
     * The bytes of [hash] are coming: streaming in or landing on a link ([MeshTransport.arrivingFiles]), or going into
     * the store ([BlobStore.storing]). Read in that order, the path's own backwards — a file joins the next stage
     * before it leaves the last, so a blob moving on between the two reads is seen by the second (#138).
     */
    private suspend fun onTheWay(hash: String): Boolean = hash in transport.arrivingFiles() || store.storing(hash)

    /** [onTheWay], or already held: read last, for the same reason. */
    private suspend fun onTheWayOrHeld(hash: String): Boolean = onTheWay(hash) || store.has(hash)

    /**
     * The linked neighbours as a step reads them: each one's plane for a file, whether it is streaming to us, whether
     * its link says it answers a query, and whether that link is a slow one.
     */
    private fun linkedNow(): List<Candidate> =
        transport.neighbors.value.map {
            Candidate(
                peer = it,
                route = transport.bulkRouteTo(it.nodeId),
                streaming = transport.fileArrivingFrom(it.nodeId),
                capable = it.capabilities and Protocol.CAP_BLOB_HAVE != 0L,
                slow = transport.slowLinkTo(it.nodeId),
            )
        }

    /**
     * Steps [hash] again after [ms]: when an ask's grace is up, answers are due, or a holder's stream may have ended.
     * The earliest wake wins — a later step that needs a sooner one (a stream to poll, a wait for answers) arms it, and
     * a superseded timer does nothing. A timer that fires steps every want due within [TIMER_COALESCE_MS] of it and
     * flushes once, so wants armed together (a batch's FETCHING rechecks) query together, one frame per neighbour. With
     * no session there is none, and the next want, answer, link-up or tick steps it instead.
     */
    private fun armTimer(
        hash: String,
        ms: Long,
    ) {
        // A stopped session's scope would never run the timer's body; the next want, link-up or tick steps it instead.
        val scope = timers()?.takeIf { it.isActive } ?: return
        val at = System.nanoTime() / NANOS_PER_MS + ms
        val token =
            synchronized(lock) {
                val want = fetching[hash] ?: return
                if (want.timerDue <= at) return
                want.timerDue = at
                ++want.timerToken
            }
        scope
            .launch {
                delay(ms)
                val due = takeDue(hash, token, at) // cleared before the steps, which may arm the next
                if (due.isEmpty()) return@launch
                val linked = linkedNow()
                due.forEach { step(it, linked) }
                flushQueries()
            }.invokeOnCompletion { cause ->
                // Cancelled with the session it ran on: the next session's first step arms afresh.
                if (cause != null) clearTimer(hash, token)
            }
    }

    private fun clearTimer(
        hash: String,
        token: Int,
    ) = synchronized(lock) {
        fetching[hash]?.takeIf { it.timerToken == token }?.timerDue = Long.MAX_VALUE
    }

    /**
     * The wants a timer for [hash] (armed as [token], due [at]) steps: none when a sooner timer superseded it, else every
     * want due within [TIMER_COALESCE_MS] of it — their own timers are disarmed (a new token), so each is stepped once.
     * Due times are compared with each other, never with the clock, so this holds on a virtual one too.
     */
    private fun takeDue(
        hash: String,
        token: Int,
        at: Long,
    ): List<String> =
        synchronized(lock) {
            if (fetching[hash]?.timerToken != token) return@synchronized emptyList()
            fetching.entries
                .filter { it.value.timerDue <= at + TIMER_COALESCE_MS }
                .map { (key, w) ->
                    w.timerDue = Long.MAX_VALUE
                    w.timerToken++
                    key
                }
        }

    /** Sends the queries the steps since the last flush queued: one frame per neighbour, at most [MAX_QUERY_HASHES] each. */
    private suspend fun flushQueries() {
        val batches =
            synchronized(lock) {
                if (outbox.isEmpty()) return
                outbox.map { (peer, hashes) -> peer to hashes.toList() }.also { outbox.clear() }
            }
        val me = selfId()
        for ((peer, hashes) in batches) {
            for (chunk in hashes.chunked(MAX_QUERY_HASHES)) {
                val id = newRequestId()
                val t = now()
                synchronized(lock) {
                    sentQueries.entries.removeAll { t - it.value.at >= CYCLE_MS }
                    sentQueries[id] = SentQuery(peer, t, chunk.toSet())
                }
                transport.send(holderQueryFrame(me, id, chunk), Peer(peer))
                onEvent(BlobEvent.QuerySent)
                log("blob query → $peer ${chunk.size} hashes #${id.take(LOG_ID_CHARS)} [${chunk.joinToString { it.take(LOG_HASH_CHARS) }}]")
            }
        }
    }

    /**
     * A neighbour asked which of [hashes] we hold ([FrameType.BLOB_HAVE], ADR 2026-10.6e5t): answer each on the link it
     * came in on. Read once, with no side effect — nothing is served, pulled or remembered, so a query costs a store
     * lookup and one small frame, and the per-peer budget bounds an unsigned flood of them.
     *
     * Each hash is [BlobHasItem.SENDING] when a copy is already on the link to [from] or was enqueued to it inside the
     * serve memo (a `blobreq` now would be refused: wait), [BlobHasItem.HAVE] when one would be served, with its length,
     * [BlobHasItem.FETCHING] when its bytes are on their way to us, and [BlobHasItem.NONE] otherwise. The reads run the
     * bytes' path backwards — the link, the store's mark, the store — so a blob landing mid-answer is never NONE (#138).
     */
    suspend fun onHaveQuery(
        hashes: List<String>,
        from: String,
        queryId: String,
    ) {
        try {
            val asked =
                hashes
                    .asSequence()
                    .filter { it.isNotEmpty() && it.length <= MAX_HASH_CHARS }
                    .distinct()
                    .take(MAX_QUERY_HASHES)
                    .toList()
            if (asked.isEmpty()) return
            if (!admitQuery(from)) {
                onEvent(BlobEvent.QueryRefused)
                return
            }
            val sending = asked.filterTo(HashSet()) { transport.fileInFlightTo(from, it) || memoHolds(it, from) }
            val arriving = transport.arrivingFiles().keys
            val storing = asked.filterTo(HashSet()) { store.storing(it) }
            val held = store.servable(asked)
            val items =
                asked.map { h ->
                    held[h]?.let { size -> BlobHasItem(h, if (h in sending) BlobHasItem.SENDING else BlobHasItem.HAVE, size) }
                        ?: BlobHasItem(h, if (h in arriving || h in storing) BlobHasItem.FETCHING else BlobHasItem.NONE)
                }
            val load = transport.feedingLoad(from)
            transport.send(holderAnswerFrame(selfId(), BlobHasContent(items, load, queryId)), Peer(from))
            onEvent(BlobEvent.QueryAnswered)
            val shown = items.filter { it.state != BlobHasItem.NONE }.joinToString { itemLabel(it) }
            val listed = if (shown.isEmpty()) "" else ": $shown"
            log("blob has → $from #${queryId.take(LOG_ID_CHARS)} ${held.size}/${items.size} held$listed load=$load")
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception, // an inbound handler never throws (rules/mesh.md)
        ) {
            log("blob has → $from failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * [from] answered a query of ours ([FrameType.BLOB_HAS]). Only a hash we still want, from a neighbour queried for it
     * this cycle, and named by the query the answer names, counts; an answer to a query older than the latest one sent
     * that neighbour for it is stale. Every hash answered takes its next step at once, so a late answer still counts.
     */
    suspend fun onHaveAnswer(
        content: BlobHasContent,
        from: String,
    ) {
        val t = now()
        val (touched, latency) = synchronized(lock) { recordAnswers(content, from, t) }
        if (touched.isEmpty()) {
            onEvent(BlobEvent.AnswerIgnored)
            return
        }
        onEvent(BlobEvent.AnswerTaken)
        val idPart = content.re?.let { " #${it.take(LOG_ID_CHARS)}" } ?: ""
        val inMs = latency?.let { " in $it ms" } ?: ""
        log("blob answer ← $from$idPart$inMs: ${touched.joinToString { itemLabel(it) }} load=${content.load ?: 0}")
        val linked = linkedNow()
        touched.forEach { step(it.hash, linked) }
        flushQueries()
    }

    /** [onHaveAnswer]'s bookkeeping, under [lock]: the items that count, and how long their query took to answer. */
    private fun recordAnswers(
        content: BlobHasContent,
        from: String,
        t: Long,
    ): Pair<List<BlobHasItem>, Long?> {
        val sent = content.re?.let { sentQueries[it] }?.takeIf { it.peer == from }
        val touched =
            content.items.take(MAX_QUERY_HASHES).filter { item ->
                val w = fetching[item.hash] ?: return@filter false
                val queriedAt = w.queried[from] ?: return@filter false
                // Named by the query it answers, and that query no older than the latest we queued this neighbour.
                if (sent != null && (item.hash !in sent.hashes || sent.at < queriedAt)) return@filter false
                w.answers[from] =
                    Answer(item.state, item.size?.takeIf { it > 0 }, (content.load ?: 0).coerceAtLeast(0), sent?.at ?: queriedAt)
                if (item.state in HELD || item.state == BlobHasItem.FETCHING) w.sawLead(t)
                true
            }
        return touched to sent?.let { t - it.at }
    }

    /** Whether [from] may have another answer now: a token bucket per neighbour, [ANSWER_BURST] deep. */
    private fun admitQuery(from: String): Boolean =
        synchronized(lock) {
            val t = now()
            val b = answerBudget.getOrPut(from) { Bucket(ANSWER_BURST.toDouble(), t) }
            b.tokens = minOf(ANSWER_BURST.toDouble(), b.tokens + (t - b.at).coerceAtLeast(0) / ANSWER_REFILL_MS.toDouble())
            b.at = t
            if (b.tokens < 1.0) return@synchronized false
            b.tokens -= 1.0
            true
        }

    /** Whether [hash] was enqueued to [peer] inside the serve memo — a peek: nothing stamped, nothing pruned. */
    private fun memoHolds(
        hash: String,
        peer: String,
    ): Boolean = synchronized(lock) { recentlyServed["$hash|$peer"]?.let { now() - it < SERVE_MEMO_MS } == true }

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
        return unsignedPointToPoint(FrameType.BLOB_REQ, newRequestId(), me, WireCodec.encodePayload(BlobReqContent(hash, offset)))
    }

    /** A holder query for [hashes], under the envelope id [id] the answer will name. Unsigned and point-to-point. */
    private fun holderQueryFrame(
        me: String,
        id: String,
        hashes: List<String>,
    ): WireEnvelope = unsignedPointToPoint(FrameType.BLOB_HAVE, id, me, WireCodec.encodePayload(BlobHaveContent(hashes)))

    /** The answer to a holder query, under a fresh id of its own (the router's seen-set dedups by id). */
    private fun holderAnswerFrame(
        me: String,
        content: BlobHasContent,
    ): WireEnvelope = unsignedPointToPoint(FrameType.BLOB_HAS, newRequestId(), me, WireCodec.encodePayload(content))

    private fun unsignedPointToPoint(
        type: String,
        id: String,
        me: String,
        payload: ByteArray,
    ): WireEnvelope {
        val env = RelayEnvelope(type = type, id = id, senderId = me, payload = payload)
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
     * when the link spliced it onto a prefix we kept (#116). [from] is the neighbour whose link delivered it: once the
     * store has answered, the file is taken off that link's arriving ones ([MeshTransport.fileTaken]) — held now, or
     * refused, and a refused splice's own landing must not keep its from-0 ask quiet.
     */
    suspend fun onReceived(
        hash: String,
        mime: String,
        srcPath: String,
        resumedFrom: Long = 0,
        from: String? = null,
    ) {
        val stored = store.saveIncoming(hash, mime, srcPath)
        from?.let { transport.fileTaken(it, hash) }
        if (stored == null) {
            // A whole file that fails its hash is the holder's bytes alone: a kept prefix stays, and the tick asks
            // again as it always has. A spliced one may have failed on the prefix.
            if (resumedFrom > 0) reaskFromZero(hash, from)
            return
        }
        partials.drop(hash) // nothing left to resume
        clearFetching(hash)
        onObtained(hash, stored.absolutePath)
    }

    /**
     * A file spliced onto [hash]'s kept prefix failed its hash check: drop the prefix (it may be what was wrong) and
     * open a new cycle for the whole blob now, rather than resume onto it again (#116). Only when the drop removed a
     * live prefix — so a peer cannot trigger the ask without first planting one — and never while the bytes are already
     * arriving on a link (ADR 2026-09.4tx5). The holder that sent the tail, [from], is passed over this cycle: its
     * serve memo still stands, and other holders answer at once.
     */
    private suspend fun reaskFromZero(
        hash: String,
        from: String?,
    ) {
        onSpliceRefused()
        if (!partials.drop(hash)) return
        if (onTheWayOrHeld(hash)) return
        val t = now()
        synchronized(lock) {
            val w = fetching.getOrPut(hash) { Want(hash, t, null, null, null) }
            w.wantedAt = t
            w.openCycle(t, natural = false)
            from?.let { w.failed += it }
        }
        step(hash, linkedNow())
        flushQueries()
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
            fetching[hash] = Want(hash, nowMs, author, via, asker)
        } else {
            want.wantedAt = nowMs // mutated in place, so insertion order (the eviction order) is kept
            if (want.author == null) want.author = author
            if (want.via == null) want.via = via
            asker?.let {
                // It lacks the bytes: not this cycle. Its ask may be what opens the next one, so open it first — opened
                // after, it would forget the mark and query the asker back, and a dead blob's pulls would ping-pong.
                if (want.cycleDue(nowMs)) openCycle(want, nowMs)
                want.pullers += it
            }
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

    /** Opens [w]'s next cycle at [t] ([CYCLE_MS] or more since the last), and logs it when the last one was dead. */
    private fun openCycle(
        w: Want,
        t: Long,
    ) {
        w.openCycle(t, natural = true)
        if (w.deadCycles > 0) log("blob cycle ${w.hash8}… after ${w.deadCycles} dead, next if dead in ${w.cycleGap(dead = true)} ms")
    }

    /** Ends [f] — the holder brought nothing — and logs why; a blind first ask's end widens its round instead. */
    private fun Want.over(
        f: Fetch,
        why: String?,
    ): Long? {
        fetch = null
        if (f.tier == Tier.Blind) {
            blindWidened = true
            return null
        }
        failed += f.peer
        onEvent(BlobEvent.Fallback)
        log("blob fallback ${f.hash8}… ${f.peer} (${why ?: "grace"}) → next")
        return null
    }

    /**
     * A blob we lack and how far its fetch has got (ADR 2026-10.6e5t). A *cycle* queries every capable neighbour once
     * ([queried], [answers]); one ask at a time is outstanding ([fetch]); a holder that brought nothing is [failed] for
     * the cycle. The neighbours nobody can query get ADR 2026-10.f7gg's round ([blindAsked], [blindWidened]). A cycle
     * ends no sooner than [cycleGap], and only with no ask outstanding. Mutated under [lock] only.
     */
    private class Want(
        hash: String,
        var wantedAt: Long,
        var author: String?,
        var via: String?,
        puller: String?,
    ) {
        val hash8 = hash.take(LOG_HASH_CHARS)
        var cycleAt = wantedAt

        // Whether this cycle heard of a holder or a fetcher; cycles in a row that did not widen the pull-through.
        var cycleSawLead = false
        var emptyCycles = 0

        // Dead cycles in a row before this one ([dead]): each doubles the wait for the next ([cycleGap]).
        var deadCycles = 0

        // Neighbours we are pulling it for: they lack it, so this cycle neither queries nor asks them.
        val pullers = HashSet<String>().apply { puller?.let(::add) }

        // Capable neighbour -> when its query for this blob was queued, and its latest answer, this cycle.
        val queried = HashMap<String, Long>()
        val answers = HashMap<String, Answer>()

        // Holders that brought nothing this cycle, and those asked again on a confirm that said they hold it.
        val failed = HashSet<String>()
        val reasked = HashSet<String>()
        var fetch: Fetch? = null

        // Every ask, any cycle: the memo floor on asking a holder again, and #121's streaming rule.
        val askedAt = HashMap<String, Long>()

        // ADR 2026-10.f7gg's round over the neighbours nobody can query, and the neighbours asked to pull it through.
        val blindAsked = HashSet<String>()
        var blindWidened = false
        var blindWidenedAt: Long? = null
        val pulled = HashSet<String>()

        // How many times each neighbour asked to pull it through was queried again since ([PULL_RECHECKS]), and when
        // this cycle first asked one to.
        val pullRechecks = HashMap<String, Int>()
        var pulledAt: Long? = null

        // The armed timer's due time (monotonic ms) and token: the earliest wake wins.
        var timerDue = Long.MAX_VALUE
        var timerToken = 0

        /**
         * Whether this cycle is dead: every neighbour asked said it lacks the blob — at least one answered, none said it
         * holds or is fetching it, and nobody was asked blind (that one may hold it: ADR 2026-10.f7gg's round, and every
         * cycle with querying off, keeps its pace).
         */
        fun dead(): Boolean = !cycleSawLead && answers.isNotEmpty() && blindAsked.isEmpty()

        /**
         * How long this cycle stands before the next opens: [CYCLE_MS], doubled for each dead cycle in a row before
         * this one while this one is dead too, at most [MAX_BACKOFF_SHIFT] times (45 s, 90 s, 3 min, then 6 min). The
         * second cycle, which widens the pull-through, keeps its pace.
         */
        fun cycleGap(dead: Boolean = dead()): Long = if (dead) CYCLE_MS shl minOf(deadCycles, MAX_BACKOFF_SHIFT) else CYCLE_MS

        /**
         * Whether the next cycle may open at [t]: no ask outstanding, and [cycleGap] since the cycle opened — or since its
         * first pull, when it pulled. A pull late in a cycle (behind a blind round, a slow answer) once let its own
         * recheck open the next cycle 10 s on and pull again; counted from the pull, its rechecks run out first. Only the
         * first pull counts, so a link that keeps flapping (a newcomer is pulled again) cannot hold a cycle open.
         */
        fun cycleDue(t: Long): Boolean = fetch == null && t - maxOf(cycleAt, pulledAt ?: cycleAt) >= cycleGap()

        /**
         * A neighbour said it holds the blob or is fetching it. A backed-off cycle starts over from [t], its answers kept:
         * the backoff is over, and the next cycle is due one cycle after the lead — not at once, which would forget it.
         */
        fun sawLead(t: Long) {
            if (!cycleSawLead && deadCycles > 0) {
                deadCycles = 0
                cycleAt = t
            }
            cycleSawLead = true
        }

        fun openCycle(
            t: Long,
            natural: Boolean,
        ) {
            if (natural) {
                deadCycles = if (dead()) deadCycles + 1 else 0
                emptyCycles = if (cycleSawLead) 0 else emptyCycles + 1
            }
            cycleAt = t
            fetch = null // a natural close has none; a forced one (a refused splice) starts its ask over
            cycleSawLead = false
            pullers.clear()
            queried.clear()
            answers.clear()
            failed.clear()
            reasked.clear()
            blindAsked.clear()
            blindWidened = false
            blindWidenedAt = null
            pulled.clear()
            pullRechecks.clear()
            pulledAt = null
        }
    }

    /** A neighbour's answer for one blob: its state, the size it gave, its load, and when the query it answers went. */
    private class Answer(
        val state: Int,
        val size: Long?,
        val load: Int,
        val querySentAt: Long,
    )

    /**
     * The one ask outstanding for a blob — to [peer], on [tier]'s plane — or a wait on a holder already sending it. Its
     * grace counts from [quietSince], the first look that found [peer] streaming nothing to us; [confirmAt] is when it
     * was queried again once that grace ran out.
     */
    private class Fetch(
        val peer: String,
        val hash8: String,
        val tier: Tier,
        at: Long,
        val graceMs: Long,
    ) {
        var quietSince: Long? = at
        var confirms = 0
        var confirmAt: Long? = null
    }

    /** A query on its way: to whom, when, and which hashes it named. */
    private class SentQuery(
        val peer: String,
        val at: Long,
        val hashes: Set<String>,
    )

    /** One neighbour's answer budget. */
    private class Bucket(
        var tokens: Double,
        var at: Long,
    )

    /** A linked neighbour as a step reads it ([linkedNow]). */
    private class Candidate(
        val peer: Peer,
        val route: BulkRoute,
        val streaming: Boolean,
        val capable: Boolean,
        val slow: Boolean,
    ) {
        val id: String get() = peer.nodeId
    }

    /** What a step decided: the queries it owes, the asks it makes, and when it wants to be stepped again. */
    private class Plan {
        val query = mutableListOf<String>()
        val asks = mutableListOf<Ask>()
        var wake: Long? = null
        var why: String = ""
        val events = mutableListOf<BlobEvent>()

        fun ask(
            to: Peer,
            arm: Boolean,
            line: String,
        ) {
            asks += Ask(to, arm, line)
        }

        fun waitFor(ms: Long): Plan {
            if (ms > 0) wake = wake?.let { minOf(it, ms) } ?: ms
            return this
        }
    }

    private class Ask(
        val to: Peer,
        val arm: Boolean,
        val line: String,
    )

    /** The plane a holder's copy would ride, best first ([tierOf]); the [label] is the log's. */
    private enum class Tier(
        val label: String,
    ) {
        FastLink("fast link"),
        FastArm("fast reach"),
        Link("link"),
        SlowLink("slow link"),
        Sending("sending"),
        Blind("blind"),
    }

    /** What put a neighbour first in a blind round ([rankOf]), best first; the [label] is the log's. */
    private enum class Rank(
        val label: String,
    ) {
        Author("author"),
        FastLink("fast link"),
        FastReach("fast reach"),
        SourceHop("source hop"),
        Neighbour("neighbour"),
    }

    /** What a step or a query did, for the owner's counters. */
    enum class BlobEvent {
        QuerySent,
        QueryAnswered,
        QueryRefused,
        AnswerTaken,
        AnswerIgnored,
        Fetch,
        Confirm,
        Fallback,
        BlindAsk,
        PullThrough,
    }

    companion object {
        // How long an ask has to bring a header in before the next holder is tried. A holder over a live link answers
        // well inside it: the ask is one frame, and the serve reads the blob from the store and queues it. A holder that
        // can raise the Wi-Fi Aware link waits up to CompositeMeshTransport.BULK_GRACE_MS for it before it falls back to
        // Bluetooth, so an ask of a reachable one gets that on top.
        const val FIRST_ASK_GRACE_MS = 5_000L

        // How long a fetch waits for the queried neighbours' answers before it chooses among those in, and before the
        // neighbours still silent are asked blind. An answer is one small frame, but a link feeding a file interleaves
        // it only between chunks (FramedLink, ~2 s of feed each) and behind the stack's buffer (#121). A late answer
        // still counts. The 2026-10-05 trial's `blob answer … in <ms> ms` lines: p50 0.25 s, p90 2.0 s, 12 of 197
        // past 3 s and 4 past 5 s, every slow one from a holder feeding two files; at 3 s, three were asked blind.
        const val QUERY_WAIT_MS = 5_000L

        // While the holder we wait on streams another file to us, how often we look for that stream's end.
        const val STREAM_POLL_MS = 2_000L

        // A neighbour that said it is fetching the blob itself is queried again this long after, and one asked to pull
        // it through this long after the pull, at most PULL_RECHECKS times a cycle.
        const val FETCHING_RECHECK_MS = 10_000L
        const val PULL_RECHECKS = 4

        // A neighbour asked to pull is rechecked only in this many cycles of a dead streak (Want.deadCycles).
        const val PULL_RECHECK_DEAD_CYCLES = 2

        // How many times a dead cycle in a row doubles the wait for the next (Want.cycleGap): 6 min at most.
        private const val MAX_BACKOFF_SHIFT = 3

        // How many times a holder whose grace ran out is queried again before the next holder is asked.
        const val MAX_CONFIRMS = 2

        // How many hashes one query names, and how long a hash may be; the rest of a longer query is ignored.
        const val MAX_QUERY_HASHES = 32
        private const val MAX_HASH_CHARS = 128

        // A neighbour's answer budget: this many at once, then one per refill.
        private const val ANSWER_BURST = 16
        private const val ANSWER_REFILL_MS = 1_000L
        private const val MAX_ANSWER_BUCKETS = 64
        private const val MAX_SENT_QUERIES = 256

        // How much of a hash, and of a query id, a log line names.
        private const val LOG_HASH_CHARS = 8
        private const val LOG_ID_CHARS = 6
        private const val NANOS_PER_MS = 1_000_000L

        // A firing timer also steps the wants due this soon after it, and a recheck due this soon goes in its frame, so
        // a batch armed together wakes and queries together. Two hashes pulled 0.5 s apart rechecked in a frame each.
        private const val TIMER_COALESCE_MS = 1_000L

        // Shorter than MeshManager's 60 s neighbor re-offer, so the periodic re-ask always passes the memo
        // and a serve whose bytes were lost (link died mid-stream) is retried on the next round.
        const val SERVE_MEMO_MS = 45_000L

        // The shortest gap between two rounds of asks for one blob. The serve memo's length, so the next round's
        // fresh ask always passes it; the 60 s tick opens a round when one is due.
        const val ROUND_MS = SERVE_MEMO_MS

        // How long a cycle's answers stand before every capable neighbour is queried again — phones move. One round.
        const val CYCLE_MS = ROUND_MS

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

        // The states that say a neighbour holds the whole blob.
        private val HELD = setOf(BlobHasItem.HAVE, BlobHasItem.SENDING)

        private fun stateLabel(state: Int): String =
            when (state) {
                BlobHasItem.NONE -> "NONE"
                BlobHasItem.HAVE -> "HAVE"
                BlobHasItem.SENDING -> "SENDING"
                BlobHasItem.FETCHING -> "FETCHING"
                else -> "state $state"
            }

        private fun itemLabel(item: BlobHasItem): String =
            "${item.hash.take(LOG_HASH_CHARS)} ${stateLabel(item.state)}${item.size?.let { " ${it}B" } ?: ""}"
    }
}
