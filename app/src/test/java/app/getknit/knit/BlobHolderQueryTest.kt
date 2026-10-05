package app.getknit.knit

import app.getknit.knit.mesh.BlobExchange
import app.getknit.knit.mesh.BlobStore
import app.getknit.knit.mesh.BulkRoute
import app.getknit.knit.mesh.FakeLoopTransport
import app.getknit.knit.mesh.MeshRouter
import app.getknit.knit.mesh.PartialBlobs
import app.getknit.knit.mesh.protocol.BlobHasContent
import app.getknit.knit.mesh.protocol.BlobHasItem
import app.getknit.knit.mesh.protocol.BlobHaveContent
import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * The holder query (ADR 2026-10.6e5t): a neighbour whose link advertises [Protocol.CAP_BLOB_HAVE] is asked which blobs
 * it holds before anyone is asked for one, the best holder is asked alone, one that brings nothing is queried again
 * and then passed over, and the neighbours nobody can query keep ADR 2026-10.f7gg's blind round (pinned in
 * [BlobExchangeTest], whose fakes advertise nothing).
 */
@Suppress("LargeClass") // one query's contract, both ends of it
@OptIn(ExperimentalCoroutinesApi::class)
class BlobHolderQueryTest {
    /** In-memory, content-addressed [BlobStore]; [lying] answers a query as holding blobs it cannot serve. */
    private class Store(
        private val dir: File,
    ) : BlobStore {
        val blobs = ConcurrentHashMap<String, ByteArray>()
        val storingNow = ConcurrentHashMap.newKeySet<String>()
        val lying = ConcurrentHashMap<String, Long>()

        fun seed(bytes: ByteArray): String = sha256Hex(bytes).also { blobs[it] = bytes }

        override suspend fun has(hash: String) = blobs.containsKey(hash)

        override suspend fun fileFor(hash: String): File? = blobs[hash]?.let { b -> File(dir, "$hash.tx").apply { writeBytes(b) } }

        override suspend fun mimeFor(hash: String): String? = if (blobs.containsKey(hash)) "image/jpeg" else null

        override suspend fun saveIncoming(
            hash: String,
            mime: String,
            srcPath: String,
        ): File? {
            val bytes = File(srcPath).readBytes()
            if (sha256Hex(bytes) != hash) return null
            blobs[hash] = bytes
            return File(dir, "$hash.in").apply { writeBytes(bytes) }
        }

        override fun storing(hash: String): Boolean = hash in storingNow

        override suspend fun servable(hashes: List<String>): Map<String, Long> =
            hashes.mapNotNull { h -> (blobs[h]?.size?.toLong() ?: lying[h])?.let { h to it } }.toMap()
    }

    /** One node: its transport, store and exchange, a router that hands it the three blob types, and its counters. */
    private inner class Node(
        val id: String,
        scope: CoroutineScope,
        query: Boolean = true,
        answers: Boolean = true,
        val timers: CoroutineScope? = null,
        recheckMs: Long = BlobExchange.FETCHING_RECHECK_MS,
    ) {
        val transport = FakeLoopTransport(id).apply { if (answers) advertises = Protocol.CAP_BLOB_HAVE }
        val store = Store(Files.createTempDirectory("hq-$id").toFile())
        var now = 0L
        val partials = PartialBlobs(Files.createTempDirectory("hq-parts-$id").toFile(), minBytes = 1)
        val events = CopyOnWriteArrayList<BlobExchange.BlobEvent>()
        val exchange =
            BlobExchange(
                transport = transport,
                store = store,
                selfId = { id },
                onObtained = { _, _ -> },
                now = { now },
                partials = partials,
                holderQuery = BlobExchange.HolderQuery(enabled = query, fetchingRecheckMs = recheckMs),
                onEvent = { events += it },
                timers = { timers },
            )

        // Every blob frame this node received: type, sender, payload.
        val heard = CopyOnWriteArrayList<Triple<String, String, RelayEnvelope>>()

        private val router =
            MeshRouter(transport, scope) { _, env, from, _ ->
                heard += Triple(env.type, from, env)
                when (env.type) {
                    FrameType.BLOB_REQ -> {
                        WireCodec.decodePayload<BlobReqContent>(env.payload)?.let { exchange.onRequest(it.hash, from, it.offset) }
                    }

                    FrameType.BLOB_HAVE -> {
                        val query = WireCodec.decodePayload<BlobHaveContent>(env.payload)
                        if (answers && query != null) exchange.onHaveQuery(query.hashes, from, env.id)
                    }

                    FrameType.BLOB_HAS -> {
                        WireCodec.decodePayload<BlobHasContent>(env.payload)?.let { exchange.onHaveAnswer(it, from) }
                    }
                }
            }

        init {
            router.start()
            scope.launch {
                transport.incomingFiles.collect { exchange.onReceived(it.key, it.mime, it.path, it.resumedFrom, it.fromNodeId) }
            }
        }

        /** The asks for [hash] this node received, by sender. */
        fun asksFrom(hash: String): List<String> =
            heard
                .filter { (type, _, env) ->
                    type == FrameType.BLOB_REQ && WireCodec.decodePayload<BlobReqContent>(env.payload)?.hash == hash
                }.map { it.second }

        /** The queries this node received, by sender, each as the hashes it named. */
        fun queriesFrom(from: String): List<List<String>> =
            heard
                .filter { it.first == FrameType.BLOB_HAVE && it.second == from }
                .mapNotNull { WireCodec.decodePayload<BlobHaveContent>(it.third.payload)?.hashes }

        /** The answers this node received, by sender. */
        fun answersFrom(from: String): List<BlobHasContent> =
            heard
                .filter { it.first == FrameType.BLOB_HAS && it.second == from }
                .mapNotNull { WireCodec.decodePayload<BlobHasContent>(it.third.payload) }

        /** How many copies of [hash] this node served to [to]. */
        fun served(
            hash: String,
            to: String,
        ): Int = filesTo.count { it == "$to $hash" }

        val filesTo = CopyOnWriteArrayList<String>()
    }

    private fun picture(size: Int = 2_000) = Random(size).nextBytes(size)

    /** Records every file [holder] streams to anyone, as "to hash". */
    private fun CoroutineScope.recordServes(
        holder: Node,
        vararg to: Node,
    ) {
        for (n in to) launch { n.transport.incomingFiles.collect { if (it.fromNodeId == holder.id) holder.filesTo += "${n.id} ${it.key}" } }
    }

    @Test
    fun onlyTheNeighbourThatSaysItHoldsTheBlobIsAsked() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            backgroundScope.recordServes(b, a)
            val hash = b.store.seed(picture())

            a.exchange.want(hash, author = "c")

            assertEquals("each capable neighbour is queried once", listOf(listOf(hash)), b.queriesFrom("a"))
            assertEquals(listOf(listOf(hash)), c.queriesFrom("a"))
            assertEquals("the holder alone is asked — not the author, who said it lacks it", listOf("a"), b.asksFrom(hash))
            assertTrue(c.asksFrom(hash).isEmpty())
            assertEquals(1, b.served(hash, "a"))
            assertTrue(a.store.has(hash))
            assertEquals(1, a.events.count { it == BlobExchange.BlobEvent.Fetch })
        }

    @Test
    fun aQueryServesNothingPullsNothingAndStampsNoMemo() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope, query = false)
            val b = Node("b", backgroundScope)
            a.transport.connect(b.transport)
            val held = b.store.seed(picture())
            val absent = sha256Hex(picture(3_000))

            b.exchange.onHaveQuery(listOf(held, absent), "a", "q1")

            assertTrue("a query serves nothing", b.transport.filesSent.isEmpty())
            assertTrue("and pulls nothing: no ask goes out for the hash it lacks", a.asksFrom(absent).isEmpty())
            val answer = a.answersFrom("b").single()
            assertEquals("q1", answer.re)
            assertEquals(listOf(BlobHasItem.HAVE, BlobHasItem.NONE), answer.items.map { it.state })
            b.exchange.onRequest(held, "a")
            assertEquals("no memo was stamped: a fresh ask is served at once", 1, b.transport.filesSent.size)
            b.exchange.onHaveQuery(listOf(held), "a", "q2")
            val after =
                a
                    .answersFrom("b")
                    .last()
                    .items
                    .single()
            assertEquals("and now the copy is enqueued, the answer says so", BlobHasItem.SENDING, after.state)
        }

    @Test
    fun theAnswerNamesEachStateTheSizeAndTheLoad() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope, query = false)
            val b = Node("b", backgroundScope)
            a.transport.connect(b.transport)
            val have = b.store.seed(picture(1_000))
            val sending = b.store.seed(picture(1_200))
            val arriving = sha256Hex(picture(1_300))
            val storing = sha256Hex(picture(1_400))
            val none = sha256Hex(picture(1_500))
            b.transport.inFlight += "a" to sending
            b.transport.arriving += arriving
            b.store.storingNow += storing
            b.transport.feeding = 3

            b.exchange.onHaveQuery(listOf(have, sending, arriving, storing, none), "a", "q1")

            val answer = a.answersFrom("b").single()
            assertEquals(
                listOf(
                    BlobHasItem(have, BlobHasItem.HAVE, 1_000L),
                    BlobHasItem(sending, BlobHasItem.SENDING, 1_200L),
                    BlobHasItem(arriving, BlobHasItem.FETCHING),
                    BlobHasItem(storing, BlobHasItem.FETCHING),
                    BlobHasItem(none, BlobHasItem.NONE),
                ),
                answer.items,
            )
            assertEquals(3, answer.load)
        }

    @Test
    fun aQueryIsCappedAndAFloodIsAnsweredWithinItsBudget() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope, query = false)
            val b = Node("b", backgroundScope)
            a.transport.connect(b.transport)
            val many = (1..40).map { sha256Hex(picture(100 + it)) }

            b.exchange.onHaveQuery(many, "a", "q0")
            assertEquals(
                "the first 32 hashes are answered, the rest ignored",
                32,
                a
                    .answersFrom("b")
                    .single()
                    .items.size,
            )
            repeat(30) { b.exchange.onHaveQuery(listOf(many[0]), "a", "q${it + 1}") }

            assertEquals("a burst of 16, then nothing until the budget refills", 16, a.answersFrom("b").size)
            assertEquals(15, b.events.count { it == BlobExchange.BlobEvent.QueryRefused })
            b.now += 2_000
            b.exchange.onHaveQuery(listOf(many[0]), "a", "q99")
            assertEquals(17, a.answersFrom("b").size)
        }

    @Test
    fun queriesAreBatchedOneFramePerNeighbourAtMostThirtyTwoHashes() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            a.transport.connect(b.transport)
            val wanted = (1..40).map { sha256Hex(picture(200 + it)) to null }

            a.exchange.wantAll(wanted)

            assertEquals(listOf(32, 8), b.queriesFrom("a").map { it.size })
            assertEquals(wanted.map { it.first }.toSet(), b.queriesFrom("a").flatten().toSet())
        }

    @Test
    fun timersArmedTogetherWakeTogetherAndQueryInOneFramePerNeighbour() =
        runTest(UnconfinedTestDispatcher()) {
            // Forty hashes b is fetching itself: each arms its recheck at once, and the rechecks must not come due one
            // hash at a time — forty one-hash queries would trip b's own flood budget (the bluetooth-reviewer's finding).
            val a = Node("a", backgroundScope, timers = backgroundScope)
            val b = Node("b", backgroundScope)
            a.transport.connect(b.transport)
            val wanted = (1..40).map { sha256Hex(picture(300 + it)) }
            b.transport.arriving += wanted

            a.exchange.wantAll(wanted.map { it to null })
            assertEquals(listOf(32, 8), b.queriesFrom("a").map { it.size })
            a.now += BlobExchange.FETCHING_RECHECK_MS
            advanceTimeBy(BlobExchange.FETCHING_RECHECK_MS + 1)
            runCurrent()

            assertEquals("the rechecks woke as one batch", listOf(32, 8, 32, 8), b.queriesFrom("a").map { it.size })
            assertTrue(b.events.none { it == BlobExchange.BlobEvent.QueryRefused })
            a.now += BlobExchange.CYCLE_MS
            a.exchange.reask() // the tick opens the next cycle: every hash, one frame per neighbour again
            assertEquals(listOf(32, 8, 32, 8, 32, 8), b.queriesFrom("a").map { it.size })
            assertTrue(b.events.none { it == BlobExchange.BlobEvent.QueryRefused })
        }

    @Test
    fun theBestPlaneIsAskedAndALargeBlobArmsTheFastLink() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            val d = Node("d", backgroundScope)
            listOf(b, c, d).forEach { a.transport.connect(it.transport) }
            val big = picture(140_000)
            val hash = b.store.seed(big)
            c.store.seed(big)
            d.store.seed(big)
            a.transport.bulkRoutes["c"] = BulkRoute.Reachable
            a.transport.slowLinks += "b"

            a.exchange.want(hash)

            assertEquals("a holder Wi-Fi Aware can reach, for a blob big enough to raise it", listOf("a"), c.asksFrom(hash))
            assertEquals(listOf("c"), a.transport.bulkArmed)
            assertTrue(b.asksFrom(hash).isEmpty())
            assertTrue(d.asksFrom(hash).isEmpty())
        }

    @Test
    fun aSmallBlobIsNotArmedAndTheLessLoadedLinkWins() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            val d = Node("d", backgroundScope)
            listOf(b, c, d).forEach { a.transport.connect(it.transport) }
            val small = picture(4_000)
            val hash = b.store.seed(small)
            c.store.seed(small)
            d.store.seed(small)
            a.transport.bulkRoutes["b"] = BulkRoute.Reachable // reachable, but a small blob never raises the link
            b.transport.feeding = 2
            c.transport.feeding = 1
            d.transport.feeding = 0
            a.transport.arrivingFrom += "d" // d streams something else to us: its link is busy

            a.exchange.want(hash)

            assertTrue("nothing armed for a small blob", a.transport.bulkArmed.isEmpty())
            assertEquals("the free link with the lighter load", listOf("a"), c.asksFrom(hash))
            assertTrue(b.asksFrom(hash).isEmpty())
            assertTrue(d.asksFrom(hash).isEmpty())
        }

    @Test
    fun theChoiceWaitsForEveryAnswerUnlessTheBestRidesALiveFastLink() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope, answers = false) // capable on the wire, but never answers
            c.transport.advertises = Protocol.CAP_BLOB_HAVE
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val hash = b.store.seed(picture())

            a.exchange.want(hash)
            assertTrue("c's answer is still due: nobody is asked yet", b.asksFrom(hash).isEmpty())
            a.now += BlobExchange.QUERY_WAIT_MS
            a.exchange.reask()
            assertEquals("past the wait, the holder that answered is asked", listOf("a"), b.asksFrom(hash))
            assertTrue("and the silent one is not asked blind past a confirmed holder", c.asksFrom(hash).isEmpty())

            val a2 = Node("a2", backgroundScope)
            val b2 = Node("b2", backgroundScope)
            val c2 = Node("c2", backgroundScope, answers = false).apply { transport.advertises = Protocol.CAP_BLOB_HAVE }
            a2.transport.connect(b2.transport)
            a2.transport.connect(c2.transport)
            a2.transport.bulkRoutes["b2"] = BulkRoute.Linked
            val hash2 = b2.store.seed(picture(2_500))
            a2.exchange.want(hash2)
            assertEquals("a holder on a live fast link is asked at once", listOf("a2"), b2.asksFrom(hash2))
        }

    @Test
    fun aHolderThatBringsNothingIsQueriedAgainThenTheNextIsAsked() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val bytes = picture()
            val hash = sha256Hex(bytes)
            b.store.lying[hash] = bytes.size.toLong() // b says it holds it, then cannot serve it
            c.store.seed(bytes)
            c.transport.feeding = 1 // so b, unloaded, is the better choice

            a.exchange.want(hash)
            assertEquals(listOf("a"), b.asksFrom(hash))
            assertTrue(c.asksFrom(hash).isEmpty())

            b.store.lying.clear() // by the confirm, b knows it lacks it
            a.now += BlobExchange.FIRST_ASK_GRACE_MS
            a.exchange.reask()
            assertEquals("its grace up, b is queried again", 2, b.queriesFrom("a").size)
            assertEquals("and saying it lacks it, the next holder is asked", listOf("a"), c.asksFrom(hash))
            assertTrue(a.store.has(hash))
            assertEquals(1, a.events.count { it == BlobExchange.BlobEvent.Fallback })
        }

    @Test
    fun aHolderWhoseAskWasLostIsAskedAgainOnceThenPassedOver() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val bytes = picture()
            val hash = sha256Hex(bytes)
            b.store.lying[hash] = bytes.size.toLong() // a holder whose serves never arrive
            c.store.seed(bytes)
            c.transport.feeding = 1

            a.exchange.want(hash)
            a.now += BlobExchange.FIRST_ASK_GRACE_MS
            a.exchange.reask() // the confirm: still HAVE, to a query newer than our ask — the ask was lost
            assertEquals("asked again once", listOf("a", "a"), b.asksFrom(hash))
            a.now += BlobExchange.FIRST_ASK_GRACE_MS
            a.exchange.reask() // confirm again
            assertEquals("not a third time", 2, b.asksFrom(hash).size)
            assertEquals("the next holder", listOf("a"), c.asksFrom(hash))
            assertTrue(a.store.has(hash))
        }

    @Test
    fun aHolderAlreadySendingUsTheBlobIsWaitedOnAndNobodyElseIsAsked() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val bytes = picture()
            val hash = b.store.seed(bytes)
            c.store.seed(bytes)
            b.transport.inFlight += "a" to hash // b's copy for us is already queued on the link

            a.exchange.want(hash)

            assertTrue("no ask at all: our copy is coming", b.asksFrom(hash).isEmpty() && c.asksFrom(hash).isEmpty())
            a.now += BlobExchange.FIRST_ASK_GRACE_MS
            a.exchange.reask() // confirm: still sending
            assertTrue(c.asksFrom(hash).isEmpty())
        }

    @Test
    fun theGraceCountsFromTheEndOfTheHoldersStreamNotItsLastLook() =
        runTest(UnconfinedTestDispatcher()) {
            // The blind round's first ask (f7gg), the trial's shape: b streams something else to us, our copy behind it.
            val a = Node("a", backgroundScope, query = false)
            val b = Node("b", backgroundScope, answers = false)
            val c = Node("c", backgroundScope, answers = false)
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val hash = sha256Hex(picture())

            a.exchange.want(hash, author = "b")
            assertEquals(listOf("a"), b.asksFrom(hash))
            a.transport.arrivingFrom += "b"
            a.now = 4_000
            a.exchange.reask() // the last look that sees b streaming
            a.transport.arrivingFrom -= "b" // its stream ends at 4.5 s
            a.now = 9_000
            a.exchange.reask() // a whole grace after that last look, 4.5 s after the end
            assertTrue("the grace starts at the first quiet look, not the last streaming one", c.asksFrom(hash).isEmpty())
            a.now = 14_000
            a.exchange.reask()
            assertEquals(listOf("a"), c.asksFrom(hash))
        }

    @Test
    fun theStreamPollIsTimedOnTheSession() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope, query = false, timers = backgroundScope)
            val b = Node("b", backgroundScope, answers = false)
            val c = Node("c", backgroundScope, answers = false)
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val hash = sha256Hex(picture())
            a.transport.arrivingFrom += "b"

            a.exchange.want(hash, author = "b")
            advanceTimeBy(BlobExchange.FIRST_ASK_GRACE_MS + 1)
            runCurrent()
            assertTrue("b still streams to us: the round waits", c.asksFrom(hash).isEmpty())
            a.transport.arrivingFrom -= "b"
            a.now += BlobExchange.STREAM_POLL_MS
            advanceTimeBy(BlobExchange.STREAM_POLL_MS + 1)
            runCurrent() // the poll's first quiet look starts the grace
            assertTrue(c.asksFrom(hash).isEmpty())
            a.now += BlobExchange.FIRST_ASK_GRACE_MS
            advanceTimeBy(BlobExchange.FIRST_ASK_GRACE_MS + 1)
            runCurrent()
            assertEquals("widened a whole grace after the stream's end", listOf("a"), c.asksFrom(hash))
        }

    @Test
    fun aNeighbourThatCannotBeQueriedIsAskedBlindOnceTheAnswersAreIn() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val u = Node("u", backgroundScope, answers = false) // an iPhone, or an older build
            a.transport.connect(b.transport)
            a.transport.connect(u.transport)
            val hash = u.store.seed(picture())

            a.exchange.want(hash)

            assertTrue("never queried", u.queriesFrom("a").isEmpty())
            assertEquals("b said it lacks it, so the blind round runs at once", listOf("a"), u.asksFrom(hash))
            assertTrue(a.store.has(hash))
            assertEquals(1, a.events.count { it == BlobExchange.BlobEvent.BlindAsk })
        }

    @Test
    fun aConfirmedHolderBeatsAnAuthorThatCannotBeQueried() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val u = Node("u", backgroundScope, answers = false)
            a.transport.connect(b.transport)
            a.transport.connect(u.transport)
            val bytes = picture()
            val hash = b.store.seed(bytes)
            u.store.seed(bytes)

            a.exchange.want(hash, author = "u")

            assertEquals(listOf("a"), b.asksFrom(hash))
            assertTrue("the author is asked blind only once no holder said so", u.asksFrom(hash).isEmpty())
        }

    @Test
    fun aCapableNeighbourStillSilentPastTheWaitIsAskedBlind() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val s = Node("s", backgroundScope, answers = false).apply { transport.advertises = Protocol.CAP_BLOB_HAVE }
            a.transport.connect(s.transport)
            val hash = s.store.seed(picture())

            a.exchange.want(hash)
            assertEquals(1, s.queriesFrom("a").size)
            assertTrue(s.asksFrom(hash).isEmpty())
            a.now += BlobExchange.QUERY_WAIT_MS
            a.exchange.reask()
            assertEquals("a bit that answers nothing degrades to today's round", listOf("a"), s.asksFrom(hash))
        }

    @Test
    fun anAnswerNobodyAskedForIsIgnored() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            a.transport.connect(b.transport)
            val hash = sha256Hex(picture())

            a.exchange.onHaveAnswer(BlobHasContent(listOf(BlobHasItem(hash, BlobHasItem.HAVE, 10))), "b")
            assertTrue("not wanted: nothing asked", b.asksFrom(hash).isEmpty())
            assertEquals(listOf(BlobExchange.BlobEvent.AnswerIgnored), a.events.filter { it == BlobExchange.BlobEvent.AnswerIgnored })

            val c = Node("c", backgroundScope, answers = false) // never queried: no CAP
            a.transport.connect(c.transport)
            a.exchange.want(hash)
            a.events.clear()
            a.exchange.onHaveAnswer(BlobHasContent(listOf(BlobHasItem(hash, BlobHasItem.HAVE, 10))), "c")
            assertEquals("an answer from a neighbour we never queried", listOf(BlobExchange.BlobEvent.AnswerIgnored), a.events.toList())
        }

    @Test
    fun aStateThisBuildDoesNotKnowIsNeverTakenForHave() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope, answers = false).apply { transport.advertises = Protocol.CAP_BLOB_HAVE }
            a.transport.connect(b.transport)
            val hash = sha256Hex(picture())

            a.exchange.want(hash)
            val query = b.heard.single { it.first == FrameType.BLOB_HAVE }.third
            a.exchange.onHaveAnswer(
                BlobHasContent(listOf(BlobHasItem(hash, BlobHasItem.PARTIAL, size = 2_000, held = 400)), re = query.id),
                "b",
            )

            // A PARTIAL holder is not asked for the whole: it lacks it, so it is asked to pull it through.
            assertEquals(1, b.asksFrom(hash).size)
            assertEquals(1, a.events.count { it == BlobExchange.BlobEvent.PullThrough })
            assertEquals(0, a.events.count { it == BlobExchange.BlobEvent.Fetch })
        }

    @Test
    fun nobodyHoldsItSoTheHopPullsItThroughThenEveryoneNextCycle() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            val d = Node("d", backgroundScope)
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            b.transport.connect(d.transport) // d, the holder, is two hops from a, behind b
            backgroundScope.recordServes(d, b)
            backgroundScope.recordServes(b, a)
            val hash = d.store.seed(picture())

            a.exchange.want(hash, via = "b")

            assertEquals("the hop alone is asked to pull it through", listOf("a"), b.asksFrom(hash))
            assertTrue(c.asksFrom(hash).isEmpty())
            assertEquals("b pulled it from d", 1, d.served(hash, "b"))
            assertTrue(b.store.has(hash))
            b.now += BlobExchange.CYCLE_MS
            a.now += BlobExchange.CYCLE_MS
            a.exchange.reask() // the next cycle queries again: b holds it now
            assertEquals(1, b.served(hash, "a"))
            assertTrue(a.store.has(hash))
            assertTrue("c was never asked", c.asksFrom(hash).isEmpty())
        }

    @Test
    fun withNoHopAmongThemEveryNeighbourThatLacksItPullsItThrough() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val hash = sha256Hex(picture())

            a.exchange.want(hash)

            assertEquals(listOf("a"), b.asksFrom(hash))
            assertEquals(listOf("a"), c.asksFrom(hash))
            assertEquals(1, a.events.count { it == BlobExchange.BlobEvent.PullThrough })
        }

    @Test
    fun aNeighbourFetchingItHoldsThePullAndIsQueriedAgain() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            a.transport.connect(b.transport)
            val bytes = picture()
            val hash = sha256Hex(bytes)
            b.transport.arriving += hash // b's own copy is streaming in

            a.exchange.want(hash, via = "b")
            assertTrue("b is fetching it: no pull-through", b.asksFrom(hash).isEmpty())
            b.transport.arriving -= hash
            b.store.seed(bytes)
            a.now += BlobExchange.FETCHING_RECHECK_MS
            a.exchange.reask()
            assertEquals(2, b.queriesFrom("a").size)
            assertEquals("its copy landed: asked for it", listOf("a"), b.asksFrom(hash))
            assertTrue(a.store.has(hash))
        }

    @Test
    fun theHopAskedToPullItThroughIsQueriedAgainShortlyAndAskedOnceItHoldsIt() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val d = Node("d", backgroundScope)
            a.transport.connect(b.transport)
            backgroundScope.recordServes(b, a)
            val hash = d.store.seed(picture())

            a.exchange.want(hash, via = "b")
            assertEquals("b lacks it, so it is asked to pull it through", listOf("a"), b.asksFrom(hash))
            a.now += BlobExchange.FETCHING_RECHECK_MS / 2
            a.exchange.reask()
            assertEquals("its recheck is not due yet", 1, b.queriesFrom("a").size)

            b.transport.connect(d.transport) // b meets the holder, and its pull for a lands
            b.exchange.linked(listOf("d"))
            b.exchange.reask()
            assertTrue(b.store.has(hash))

            a.now = BlobExchange.FETCHING_RECHECK_MS
            a.exchange.reask() // a third of the way into the cycle: the recheck finds it, not the next cycle
            assertEquals(2, b.queriesFrom("a").size)
            assertEquals("the pull, then the fetch", listOf("a", "a"), b.asksFrom(hash))
            assertEquals(1, b.served(hash, "a"))
            assertTrue(a.store.has(hash))
        }

    @Test
    fun aNeighbourAskedToPullItThroughIsQueriedAgainOnlyAFewTimesACycle() =
        runTest(UnconfinedTestDispatcher()) {
            val recheck = 5_000L
            val a = Node("a", backgroundScope, recheckMs = recheck)
            val b = Node("b", backgroundScope)
            a.transport.connect(b.transport)
            val hash = sha256Hex(picture()) // nobody holds it

            a.exchange.want(hash, via = "b")
            repeat(BlobExchange.PULL_RECHECKS + 3) {
                a.now += recheck
                a.exchange.reask()
            }

            assertTrue("still inside the first cycle", a.now < BlobExchange.CYCLE_MS)
            assertEquals("the query, then its rechecks", 1 + BlobExchange.PULL_RECHECKS, b.queriesFrom("a").size)
            assertEquals("and one pull", listOf("a"), b.asksFrom(hash))
        }

    @Test
    fun aNeighbourAskedToPullItThroughIsNotRecheckedOnceTheBlobHasBeenDeadTwoCyclesInARow() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            a.transport.connect(b.transport)
            val hash = sha256Hex(picture()) // nobody holds it

            a.exchange.want(hash, via = "b")
            for (t in 10_000L..700_000L step 10_000L) { // a step every 10 s: every recheck that is owed goes out
                listOf(a, b).forEach { it.now = t }
                a.exchange.reask()
            }

            // Cycles open at 0, 50, 140, 320 and 680 s. The first two each query b and recheck it four times; the
            // three dead ones after them query it once.
            assertEquals(5, a.events.count { it == BlobExchange.BlobEvent.PullThrough })
            assertEquals(2 * (1 + BlobExchange.PULL_RECHECKS) + 3, b.queriesFrom("a").size)
        }

    @Test
    fun rechecksDueWithinASecondOfEachOtherShareOneFrame() =
        runTest(UnconfinedTestDispatcher()) {
            // Two dead hashes pulled 0.5 s apart rechecked in a frame each: the timer stepped the later one early, and
            // its recheck was not due yet (the 2026-10-05 re-run, on P8).
            val a = Node("a", backgroundScope, timers = backgroundScope)
            val b = Node("b", backgroundScope)
            a.transport.connect(b.transport)
            val first = sha256Hex(picture(401))
            val second = sha256Hex(picture(402)) // nobody holds either

            a.exchange.want(first, via = "b")
            a.now += 500
            a.exchange.want(second, via = "b")
            assertEquals(listOf(listOf(first), listOf(second)), b.queriesFrom("a"))
            assertEquals(listOf("a"), b.asksFrom(first))
            assertEquals(listOf("a"), b.asksFrom(second))
            a.now = BlobExchange.FETCHING_RECHECK_MS // the first's recheck is due, the second's half a second off
            advanceTimeBy(BlobExchange.FETCHING_RECHECK_MS + 1)
            runCurrent()

            val rechecks = b.queriesFrom("a").drop(2)
            assertEquals("one frame for both rechecks: $rechecks", setOf(first, second), rechecks.single().toSet())
        }

    @Test
    fun aPullLateInACycleIsFollowedByItsRechecksNotASecondPull() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope, answers = false) // asked blind first: the pull waits for that round
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val hash = sha256Hex(picture()) // nobody holds it

            a.exchange.want(hash, via = "b")
            assertEquals("c, blind", listOf("a"), c.asksFrom(hash))
            a.now = 40_000L // the first step past c's grace: the pull comes 40 s into the cycle
            a.exchange.reask()
            assertEquals(listOf("a"), b.asksFrom(hash))
            for (t in 50_000L..80_000L step 10_000L) {
                a.now = t
                a.exchange.reask()
            }

            assertEquals("its rechecks", 1 + BlobExchange.PULL_RECHECKS, b.queriesFrom("a").size)
            assertEquals("one pull", listOf("a"), b.asksFrom(hash))
            assertEquals("and no second blind round: the cycle stands a whole one from its pull", listOf("a"), c.asksFrom(hash))
        }

    @Test
    fun cyclesInWhichEveryNeighbourLacksItBackOffAndANewcomerIsStillQueriedAtOnce() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val bytes = picture()
            val hash = sha256Hex(bytes) // nobody in reach holds it

            a.exchange.want(hash, via = "b")
            val pulledIn = mutableListOf(0L)
            for (minute in 1L..25L) { // the 60 s tick, for 25 minutes
                listOf(a, b, c).forEach { it.now = minute * 60_000L }
                val before = a.events.count { it == BlobExchange.BlobEvent.PullThrough }
                a.exchange.reask()
                if (a.events.count { it == BlobExchange.BlobEvent.PullThrough } > before) pulledIn += minute
            }
            // 45 s, 90 s, 3 min, then 6 min between cycles, each opened by the tick: seven where every tick was one.
            assertEquals(listOf(0L, 1L, 3L, 6L, 12L, 18L, 24L), pulledIn)
            // A query a cycle, and one recheck on minute 2 of the second; the dead ones after it are not rechecked.
            assertEquals(7 + 1, b.queriesFrom("a").size)
            assertEquals(7 + 1, c.queriesFrom("a").size)

            val e = Node("e", backgroundScope)
            e.store.seed(bytes)
            backgroundScope.recordServes(e, a)
            a.transport.connect(e.transport)
            a.exchange.linked(listOf("e"))
            a.exchange.reask() // mid-backoff, a minute into a six-minute cycle
            // Its answer ends the backoff without opening the next cycle: b and c are not queried again for it.
            assertEquals("a newcomer is queried at once, and once", listOf(listOf(hash)), e.queriesFrom("a"))
            assertEquals(1, e.served(hash, "a"))
            assertTrue(a.store.has(hash))
        }

    @Test
    fun aCycleThatAskedANeighbourBlindIsNotDeadAndKeepsItsPace() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope, answers = false) // the iOS port today: asked blind
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val hash = sha256Hex(picture()) // nobody holds it, but c cannot say so

            a.exchange.want(hash)
            for (minute in 1L..5L) {
                a.now = minute * 60_000L
                a.exchange.reask()
            }

            assertEquals("ADR 2026-10.f7gg's round every tick: c may hold it", 6, c.asksFrom(hash).size)
        }

    @Test
    fun aNeighbourWhoseAskOpensTheNextCycleIsNotQueriedInIt() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            a.transport.connect(b.transport)
            val hash = sha256Hex(picture()) // nobody holds it

            a.exchange.want(hash) // b says it lacks it, and is asked to pull it through
            assertEquals(1, b.queriesFrom("a").size)
            a.now += BlobExchange.CYCLE_MS
            a.exchange.onRequest(hash, "b") // b's own ask comes due first: it lacks it too

            assertEquals("b is not queried back in the cycle its ask opened", 1, b.queriesFrom("a").size)
        }

    @Test
    fun theNeighbourWePullForIsNeitherQueriedNorAskedThatCycle() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val hash = c.store.seed(picture())

            a.exchange.onRequest(hash, "b") // b asks us for a blob we lack: we pull it for b

            assertTrue("b lacks it: not queried", b.queriesFrom("a").isEmpty())
            assertEquals(listOf("a"), c.asksFrom(hash))
            assertTrue(a.store.has(hash))
        }

    @Test
    fun aNeighbourThatLinksAgainIsQueriedAgainAtOnce() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope, answers = false).apply { transport.advertises = Protocol.CAP_BLOB_HAVE }
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val hash = sha256Hex(picture())

            a.exchange.want(hash) // c's answer is due; b says it lacks it
            a.transport.disconnect(b.transport)
            a.transport.connect(b.transport)
            a.exchange.linked(listOf("b"))
            a.exchange.reask()

            assertEquals("its old answer died with the link", 2, b.queriesFrom("a").size)
        }

    @Test
    fun withQueryingOffNoQueryIsSentAndTheRoundIsBlind() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope, query = false)
            val b = Node("b", backgroundScope)
            a.transport.connect(b.transport)
            val hash = b.store.seed(picture())

            a.exchange.want(hash, author = "b")

            assertTrue(b.queriesFrom("a").isEmpty())
            assertEquals(listOf("a"), b.asksFrom(hash))
            assertTrue(a.store.has(hash))
        }

    @Test
    fun aRefusedSpliceIsNotAskedOfTheHolderThatSentIt() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            a.transport.connect(b.transport)
            a.transport.connect(c.transport)
            val bytes = picture()
            val hash = b.store.seed(bytes)
            c.store.seed(bytes)
            c.transport.feeding = 1 // b would be the first choice
            val keptPrefix = Files.createTempFile("cut", ".bin").toFile().apply { writeBytes(ByteArray(100)) }
            assertTrue(a.partials.keep(hash, keptPrefix, basedOn = null))
            val bad = Files.createTempFile("bad", ".bin").toFile().apply { writeBytes(ByteArray(bytes.size)) }

            a.exchange.onReceived(hash, "image/jpeg", bad.absolutePath, resumedFrom = 100, from = "b")

            assertTrue("b sent the bad tail: the from-0 ask goes elsewhere", b.asksFrom(hash).isEmpty())
            assertEquals(listOf("a"), c.asksFrom(hash))
            assertTrue(a.store.has(hash))
        }
}
