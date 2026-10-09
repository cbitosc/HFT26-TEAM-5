package app.getknit.knit.mesh.lab

import app.getknit.knit.call.TalkChunk
import app.getknit.knit.call.TalkWire
import app.getknit.knit.mesh.protocol.FrameType
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
 * thread, no row in any custody — and that a live neighbour takes alone, with no hop left to relay them on. The call
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

    private companion object {
        const val TAG = 0x5A17
        const val CHUNKS = 6

        // The send log names a peer by this much of its node id (`LabTransport.NODE_ID_CHARS`).
        const val NODE_ID_CHARS = 6
    }
}
