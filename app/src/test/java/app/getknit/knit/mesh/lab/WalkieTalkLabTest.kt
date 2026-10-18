package app.getknit.knit.mesh.lab

import app.getknit.knit.call.TalkChunk
import app.getknit.knit.call.TalkWire
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.spool.FakeSpool
import app.getknit.knit.mesh.spool.SpoolCastInfo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A walkie burst's chunks between real stacks (docs/VOICE_CALLS.md §3.11, ADR 2026-10.79yb): unsigned, compactly sealed
 * `talk` frames that cross a relay to a peer two hops away and open in its call layer and nowhere else — no row in a
 * thread, no row in any custody — and that a live neighbour takes alone, with no hop left to relay them on. With no
 * radio path at all they are cast through a relay instead (§2.7), signed, and stored by nobody there either. The call
 * itself is not run here (the lab has no call layer); the chunks are what two nodes exchange.
 */
@RunWith(RobolectricTestRunner::class)
class WalkieTalkLabTest {
    private lateinit var lab: MeshLab

    @get:Rule
    val chaos = LabChaos.rule()

    @Before
    fun setUp() {
        lab = MeshLab()
    }

    @After
    fun tearDown() {
        lab.close()
    }

    /** [a] and [b] trade a DM each, as a call's signals would: a confirmed session, and each other's accepted contact. */
    private suspend fun trade(
        a: LabNode,
        b: LabNode,
        carriers: List<LabNode>,
    ) {
        assertTrue(a.sendDm(b, "${a.name} opens"))
        lab.assertConverged(listOf(a, b), atLeast = 1, carriers = carriers) { it.dmWith(if (it === a) b else a) }
        assertTrue(b.sendDm(a, "${b.name} answers"))
        lab.assertConverged(listOf(a, b), atLeast = 2, carriers = carriers) { it.dmWith(if (it === a) b else a) }
    }

    private suspend fun burst(
        from: LabNode,
        to: LabNode,
    ) = (0 until CHUNKS).forEach { q ->
        val chunk = TalkChunk(b = 0, q = q, a = byteArrayOf(q.toByte()), e = q == CHUNKS - 1)
        assertTrue(from.sendTalk(to, TalkWire.encode(TAG, chunk)))
    }

    private fun LabNode.chunksHeard(): Set<Int> =
        talks
            .mapNotNull {
                TalkWire
                    .decode(it)
                    ?.takeIf { (tag, _) -> tag == TAG }
                    ?.second
                    ?.q
            }.toSet()

    // The talk frames this node's transport handed a peer, as `<peer> <id>`, from its send log (`#n peer type id …`).
    private fun LabNode.talkSends(): List<Pair<String, String>> =
        transport.sent
            .map { it.split(" ") }
            .filter { it[2] == FrameType.TALK }
            .map { it[1] to it[3] }

    @Test
    fun aBurstCrossesARelayIntoTheCallLayerAndIntoNoThreadOrCustody() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val relay = lab.node("relay").apply { setDisplayName("Relay") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.linkAll(alice to relay, relay to bob)
            lab.awaitAcquainted(alice, relay, bob)
            trade(alice, bob, carriers = listOf(relay))

            burst(alice, bob)
            // The relay's jitter may reorder them: the set is what the mesh promises, and the player orders it.
            lab.await(CHUNKS) { bob.chunksHeard().size }
            assertEquals((0 until CHUNKS).toSet(), bob.chunksHeard())
            assertTrue(relay.chunksHeard().isEmpty())

            // Nothing reached a thread: each side still holds the two DMs it traded, and nothing else.
            lab.assertConverged(listOf(alice, bob), atLeast = 2, carriers = listOf(relay)) { it.dmWith(if (it === alice) bob else alice) }
            assertEquals(2, alice.decrypted(alice.dmWith(bob)).size)
            assertEquals(2, bob.decrypted(bob.dmWith(alice)).size)
            // Nor any custody: no store anywhere holds a talk frame, the relay's included.
            val talk = alice.talkSends().map { it.second }.toSet()
            assertEquals(CHUNKS, talk.size)
            for (node in listOf(alice, relay, bob)) {
                assertTrue("${node.name} custodies a talk frame", node.custodyIds().none { it in talk })
            }
        }

    @Test
    fun aNeighbourTakesTheBurstAloneAndRelaysItToNobody() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            val dave = lab.node("dave").apply { setDisplayName("Dave") }
            lab.linkAll(alice to bob, bob to carol, alice to dave)
            lab.awaitAcquainted(alice, bob, carol, dave)
            trade(alice, bob, carriers = listOf(carol, dave))

            burst(alice, bob)
            lab.await(CHUNKS) { bob.chunksHeard().size }
            // Alice handed each chunk to Bob alone, never to her other neighbour.
            val prefix = bob.nodeId.take(NODE_ID_CHARS)
            assertEquals(CHUNKS, alice.talkSends().size)
            assertTrue("alice sent talk past bob: ${alice.talkSends()}", alice.talkSends().all { it.first == prefix })

            // Bob had no hop to spend on them. A room post from Alice that Bob does relay is the control: once Carol
            // holds it, Bob has handled every chunk before it on their one link, and relayed none of them.
            assertTrue(alice.sendRoom("after the burst"))
            lab.await(1) { if (carol.roomPosts()[alice.nodeId]?.contains("after the burst") == true) 1 else 0 }
            assertTrue("bob relayed talk: ${bob.talkSends()}", bob.talkSends().isEmpty())
            assertTrue(carol.chunksHeard().isEmpty() && dave.chunksHeard().isEmpty())
        }

    @Test
    fun aPhoneWithNoLinkSealsNothing() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            trade(alice, bob, carriers = emptyList())
            lab.unlink(alice, bob)

            // No link to hand a chunk to: the send says so, and no frame was minted for nobody.
            val chunk = TalkWire.encode(TAG, TalkChunk(b = 0, q = 0, a = byteArrayOf(0), e = true))
            assertFalse(alice.sendTalk(bob, chunk))
            assertTrue(alice.talkSends().isEmpty())
        }

    /**
     * Two phones that met over a radio, traded a DM each (so their DM scope is on the relay), then parted. A burst
     * either way now has no mesh path at all, so it is cast through the relay, signed, and opens in the call layer
     * alone: no thread row, no custody row, nothing pushed into the scope, and nothing on any radio.
     */
    @Test
    fun aBurstWithNoRadioPathIsCastThroughTheRelayIntoTheCallLayerAlone() =
        runBlocking {
            val spool = FakeSpool(cast = CAST_BOUNDS)
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            trade(alice, bob, carriers = emptyList())
            lab.unlink(alice, bob)
            // The cast door needs a converged DM scope, not presence (stamped only on a pulled frame, which the
            // link phase may leave none of); converged also means every pair frame is on the relay already.
            lab.awaitDmScope(alice, bob)
            lab.awaitDmScope(bob, alice)
            val pushedBefore = spool.pushed.size
            val heldBefore = listOf(alice, bob).associateWith { it.custodyIds() }

            burst(alice, bob)
            lab.await(CHUNKS) { bob.chunksHeard().size }
            assertEquals((0 until CHUNKS).toSet(), bob.chunksHeard())
            burst(bob, alice)
            lab.await(CHUNKS) { alice.chunksHeard().size }

            assertTrue("no radio carried a chunk", alice.talkSends().isEmpty() && bob.talkSends().isEmpty())
            assertTrue("every chunk was cast: ${spool.casts.size}", spool.casts.size >= 2 * CHUNKS)
            // A cast is never pushed. The count is not pinned to zero, because a stray frame of the pair's (a late
            // receipt, a republished profile) may still push meanwhile; twelve chunks pushed would show here as twelve.
            val pushedDuring = spool.pushed.size - pushedBefore
            assertTrue("a cast was pushed: $pushedDuring pushes during ${2 * CHUNKS} chunks", pushedDuring < CHUNKS)
            // Nothing reached a thread, and no store holds a talk frame.
            assertEquals(2, alice.decrypted(alice.dmWith(bob)).size)
            assertEquals(2, bob.decrypted(bob.dmWith(alice)).size)
            for (node in listOf(alice, bob)) {
                val gained = node.custodyIds() - heldBefore.getValue(node)
                assertTrue("${node.name} custodies a talk frame: $gained", gained.none { it.length == FrameId.TALK_LENGTH })
            }
        }

    /** A neighbour has a mesh path, so nothing is cast to it even with a relay that would carry it. */
    @Test
    fun aNeighbourIsNeverCast() =
        runBlocking {
            val spool = FakeSpool(cast = CAST_BOUNDS)
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            trade(alice, bob, carriers = emptyList())
            // The cast door is open (a converged scope), so only the mesh path keeps it shut.
            lab.awaitDmScope(alice, bob)
            assertTrue("a cast was possible here", alice.manager.canCastTalk(bob.nodeId, peerTicketed = false))

            burst(alice, bob)
            lab.await(CHUNKS) { bob.chunksHeard().size }
            assertEquals(CHUNKS, alice.talkSends().size)
            assertTrue("a neighbour's chunks were cast: ${spool.casts}", spool.casts.isEmpty())
        }

    /**
     * A neighbour whose link is dying but not yet down (ADR 2026-10.79yb's ninth amendment): the link still counts as a
     * path, and every frame over it is lost, as for the ~20 s before a Bluetooth supervision timeout. Its transport says
     * the link fades, so each chunk is cast beside its radio copy, and the burst arrives through the relay. The other
     * way, over a link that does not fade, nothing is cast.
     */
    @Test
    fun aNeighbourWhoseLinkFadesIsCastBesideTheRadio() =
        runBlocking {
            val spool = FakeSpool(cast = CAST_BOUNDS)
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            trade(alice, bob, carriers = emptyList())
            lab.awaitDmScope(alice, bob)
            lab.awaitDmScope(bob, alice)

            // Bob's side of the link still hears him; his chunks go over it, and none is cast.
            burst(bob, alice)
            lab.await(CHUNKS) { alice.chunksHeard().size }
            assertTrue("a chunk over a sound link was cast: ${spool.casts}", spool.casts.isEmpty())

            alice.transport.fadingLinks += bob.nodeId
            alice.transport.lossy(bob.transport) { true }
            burst(alice, bob)
            lab.await(CHUNKS) { bob.chunksHeard().size }
            assertEquals((0 until CHUNKS).toSet(), bob.chunksHeard())
            // The send log is written at the far end, so a radio copy the air lost shows in `lost` instead.
            val talkLost = alice.transport.lost.count { WireCodec.decodeEnvelope(it.signed)?.type == FrameType.TALK }
            assertEquals("the radio copies still went", CHUNKS, talkLost)
            // The spool counts a cast before it fans it out, so Alice's are all in by now. Exactly hers: a cast of Bob's
            // over the sound link (which the empty check above may have run ahead of) would show here as more.
            assertEquals("Alice's chunks, and only hers, were cast", CHUNKS, spool.casts.size)
        }

    private companion object {
        const val TAG = 0x5A17

        // A relay that carries casts, at the rate an honest walkie needs (VOICE_CALLS.md §2.7).
        val CAST_BOUNDS = SpoolCastInfo(maxBytes = 2_048, rate = 4)

        const val CHUNKS = 6

        // The send log names a peer by this much of its node id (`LabTransport.NODE_ID_CHARS`).
        const val NODE_ID_CHARS = 6
    }
}
