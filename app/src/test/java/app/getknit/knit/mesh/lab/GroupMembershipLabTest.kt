package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A group's roster changing hands: the signed leave, the rekey it forces on the remainder, the rejoin by the
 * member's own frame (ADR 2026-09.v6fu — "leave a group and start it again with the same people" is the same
 * group id, and every other member held you departed for good), the seed that outruns the rejoin frame and
 * must park rather than be consumed (`8e041ba9`), and the leave and rejoin as last-writer-wins clocks held on the
 * group, which outlive the notice lines a sweep may take (`docs/GROUP_FORWARD_SECRECY.md` §6.1). A leaver deletes
 * the thread locally, so its message set legitimately differs from the remainder's: the remainder converges
 * through the oracle and the leaver rides along as a carrier, which still holds its custody, its metrics and its
 * peer rows to the same invariants.
 */
@RunWith(RobolectricTestRunner::class)
class GroupMembershipLabTest {
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

    /** Three members who all hold the group's first message; returns the group id. */
    private suspend fun triangleWithAGroup(
        alice: LabNode,
        bob: LabNode,
        carol: LabNode,
    ): String {
        lab.linkAll(alice to bob, bob to carol, alice to carol)
        lab.awaitAcquainted(alice, bob, carol)
        val groupId = alice.createGroup(bob, carol)
        assertTrue(alice.sendGroup(groupId, "founded"))
        lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }
        return groupId
    }

    /**
     * Alice leaves. Bob's next message is sealed under a fresh epoch that Alice never receives a seed for;
     * Bob and Carol converge on it, Alice holds nothing of the thread any more, and both remaining members
     * carry her as departed with the `left` notice in the thread.
     */
    @Test
    fun leaveRekeysTheRemainderAndTheLeaverReadsNothingMore() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            val groupId = triangleWithAGroup(alice, bob, carol)
            val seedsBefore = bob.metrics.groups().groupSeedsSent

            alice.leaveGroup(groupId)
            assertTrue(
                "bob never recorded the departure",
                lab.tryAwait(1) {
                    if (bob.groupShape(groupId)?.departed ==
                        setOf(alice.nodeId)
                    ) {
                        1
                    } else {
                        0
                    }
                },
            )
            assertTrue(bob.sendGroup(groupId, "after alice left"))

            lab.assertConverged(listOf(bob, carol), atLeast = 2, carriers = listOf(alice)) { groupId }
            assertTrue("bob's send after the leave minted no fresh seed", bob.metrics.groups().groupSeedsSent > seedsBefore)
            assertEquals("alice's row is a tombstone", true, alice.group(groupId)?.left)
            assertEquals("alice reads nothing of the thread after leaving", emptySet<Pair<String, String>>(), alice.decrypted(groupId))
            assertEquals(setOf(alice.nodeId), carol.groupShape(groupId)?.departed)
            assertTrue("the leave shows as a notice", MessageEntity.KIND_MEMBER_LEFT in bob.notices(groupId))
        }

    /**
     * ADR 2026-09.v6fu. After leaving, Alice "creates" the group with the same two people — the same id — and
     * writes into it. Her own signed frame lists her, so Bob and Carol re-add her (`rejoin:` after `left:`),
     * her message lands with both ticks, and a message Bob sends afterwards reaches her under the rekeyed
     * epoch. Before the fix she stayed departed everywhere and the message never arrived.
     */
    @Test
    fun leaveThenCreateTheSamePeopleAgainRejoinsByYourOwnFrame() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            val groupId = triangleWithAGroup(alice, bob, carol)
            alice.leaveGroup(groupId)
            assertTrue(lab.tryAwait(1) { if (carol.groupShape(groupId)?.departed == setOf(alice.nodeId)) 1 else 0 })
            assertTrue(bob.sendGroup(groupId, "while alice was out"))
            lab.assertConverged(listOf(bob, carol), atLeast = 2, carriers = listOf(alice)) { groupId }

            assertEquals("the same people are the same group", groupId, alice.createGroup(bob, carol))
            assertTrue(alice.sendGroup(groupId, "i am back"))
            val back = alice.ownMessageId(groupId, "i am back")
            lab.awaitReceipt(alice, back, bob)
            lab.awaitReceipt(alice, back, carol)
            assertTrue(bob.sendGroup(groupId, "welcome back"))
            assertTrue(
                "alice never read bob's post-rejoin message",
                lab.tryAwait(1) {
                    alice.decrypted(groupId).count {
                        it.second ==
                            "welcome back"
                    }
                },
            )

            lab.assertConverged(listOf(bob, carol), atLeast = 4, carriers = listOf(alice)) { groupId }
            assertEquals(emptySet<String>(), bob.groupShape(groupId)?.departed)
            assertTrue(alice.nodeId in bob.groupShape(groupId)!!.members)
            assertEquals(
                "left, then rejoined, in that order",
                listOf(MessageEntity.KIND_MEMBER_LEFT, MessageEntity.KIND_MEMBER_REJOINED),
                bob.notices(groupId).filter { it == MessageEntity.KIND_MEMBER_LEFT || it == MessageEntity.KIND_MEMBER_REJOINED },
            )
            assertEquals(
                "alice holds exactly what was sent since she came back",
                setOf(
                    "i am back",
                    "welcome back",
                ),
                alice
                    .decrypted(groupId)
                    .map {
                        it.second
                    }.toSet(),
            )
        }

    /**
     * `8e041ba9`: a rejoiner's fresh seed floods ahead of the frame that rejoins them. To Bob the seed comes
     * from a departed member; it used to park under "sender departed" exactly as "group unknown" does — a
     * seed the ratchet consumed is gone for good — and wait for the rejoin frame. Since work item #47 the
     * seed carries its founding roster, and that is Alice's own signed frame listing her: it rejoins her
     * itself and adopts on the same pass, so nothing parks. The hold delivers every DM (the seed among them)
     * before the group frame; the rejoin is then asserted before the frame is let through.
     */
    @Test
    fun aRejoinersSeedThatOutrunsTheRejoinFrameRejoinsThemItself() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            val groupId = alice.createGroup(bob)
            assertTrue(alice.sendGroup(groupId, "founded"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { groupId }
            alice.leaveGroup(groupId)
            assertTrue(lab.tryAwait(1) { if (bob.groupShape(groupId)?.departed == setOf(alice.nodeId)) 1 else 0 })

            alice.transport.hold(bob.transport)
            alice.createGroup(bob)
            assertTrue(alice.sendGroup(groupId, "i am back"))
            // Only the DMs (the seed among them) through first; the group frame stays held on the link.
            alice.transport.release(bob.transport) { batch -> batch.filterNot { it.isGroupFrame() } }
            assertTrue(
                "alice's seed never rejoined her on bob's phone",
                lab.tryAwait(1) { if (bob.groupShape(groupId)?.departed == emptySet<String>()) 1 else 0 },
            )
            assertEquals(0L, bob.metrics.groups().groupSeedsHeld)

            // The held group frame is gone with the release; a fresh link's digest exchange re-serves it from
            // Alice's custody, and it opens on its first pass under the chain the seed already adopted.
            lab.unlink(alice, bob)
            lab.link(alice, bob)
            lab.assertConverged(listOf(bob), atLeast = 2, carriers = listOf(alice)) { groupId }
            assertEquals(setOf("i am back"), alice.decrypted(groupId).map { it.second }.toSet())
            assertEquals(emptySet<String>(), bob.groupShape(groupId)?.departed)
        }

    /**
     * The leave is a clock. A frame Alice sealed *before* leaving, re-served to Bob after her leave
     * (custody does this routinely), lists her in the roster — and must not rejoin her: only a frame newer
     * than the leave can. The hold reverses the two so the stale roster lands second.
     */
    @Test
    fun aReServedPreLeaveFrameCannotUndoALeave() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            val groupId = alice.createGroup(bob)
            assertTrue(alice.sendGroup(groupId, "founded"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { groupId }

            alice.transport.hold(bob.transport)
            assertTrue(alice.sendGroup(groupId, "last words"))
            alice.leaveGroup(groupId)
            val released = alice.transport.release(bob.transport) { batch -> batch.sortedBy { it.isGroupFrame() } }
            assertTrue(
                "the batch carried the leave then the chat frame",
                released.any { it.isLeaveFrame() } && released.last().isGroupFrame(),
            )

            lab.assertConverged(listOf(bob), atLeast = 2, carriers = listOf(alice)) { groupId }
            assertEquals("the older roster did not rejoin alice", setOf(alice.nodeId), bob.groupShape(groupId)?.departed)
            assertEquals(
                listOf(MessageEntity.KIND_MEMBER_LEFT),
                bob.notices(groupId).filter {
                    it == MessageEntity.KIND_MEMBER_LEFT ||
                        it == MessageEntity.KIND_MEMBER_REJOINED
                },
            )
        }

    /**
     * Bob–Alice–Carol in a line, so Bob hears the group only through Alice; Bob trims a request thread to
     * [keep] rows and Carol is no one he talks to, so the group stays one. Returns the group id.
     */
    private suspend fun lineWithARequestGroup(
        alice: LabNode,
        bob: LabNode,
        carol: LabNode,
    ): String {
        lab.linkAll(bob to alice, alice to carol)
        lab.awaitAcquainted(alice, bob, carol)
        val groupId = alice.createGroup(bob, carol)
        assertTrue(alice.sendGroup(groupId, "founded"))
        lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }
        return groupId
    }

    /**
     * Carol posts [KEEP] lines and Bob's sweep trims his request thread down to them alone. She posts only once she
     * holds Bob's roster: the leave or rejoin she has not handled yet would kill the send chain her first line
     * seals under, and the re-mint's seed, same epoch, newer stamp, would supersede it on Bob's side. A roster
     * Carol already held before the change proves nothing, so [after] names the last frame Bob handled: Alice's
     * pipe to Carol is in order and her router handles one frame at a time, so reading it puts the change behind her.
     */
    private suspend fun pushOutBobsNotices(
        bob: LabNode,
        carol: LabNode,
        groupId: String,
        after: String? = null,
    ) {
        if (after != null) {
            assertTrue(
                "carol never read \"$after\"",
                lab.tryAwait(1) { carol.decrypted(groupId).count { it.second == after } },
            )
        } else {
            val departed = bob.groupShape(groupId)?.departed
            assertTrue(
                "carol never reached bob's roster $departed",
                lab.tryAwait(1) { if (carol.groupShape(groupId)?.departed == departed) 1 else 0 },
            )
        }
        val lines = (1..KEEP).map { "carol $it" }
        lines.forEach { assertTrue(carol.sendGroup(groupId, it)) }
        val read = lab.tryAwait(KEEP) { bob.decrypted(groupId).count { it.second in lines } }
        assertTrue("bob never read carol's lines: ${bob.decrypted(groupId)}\n${lab.report(listOf(bob, carol))}", read)
        bob.sweepLocalStorage()
        assertEquals("the sweep kept carol's lines alone", lines.toSet(), bob.decrypted(groupId).map { it.second }.toSet())
        assertEquals("no notice row survived the sweep", emptyList<Int>(), bob.notices(groupId))
    }

    /** Lets Alice's dropped frame [id] through to Bob and waits for the re-offer to hand it over and Bob to handle it. */
    private suspend fun reServeToBob(
        alice: LabNode,
        bob: LabNode,
        id: String,
    ) {
        alice.transport.lossy(bob.transport)
        assertTrue(
            "alice's custody never re-served the frame",
            lab.tryAwait(1) {
                // Bob's digest is what tells Alice the frame is missing on his side.
                bob.reoffer()
                if (id in bob.custodyIds()) 1 else 0
            },
        )
        bob.transport.awaitInboundDrained()
    }

    /**
     * The leave's clock outlives its line (IOS-6). Bob trims the request thread past the `leave:` notice, and a
     * frame Alice sealed before she left reaches him only then, from custody: it lists her, and it is older than
     * the leave held on the group, so she stays out. Before the clock moved onto the group it was the notice's
     * `sentAt`, gone with the row, and the stale roster read as a rejoin.
     */
    @Test
    fun aPreLeaveFrameReServedAfterTheSweepDroppedTheLeaveLineCannotUndoTheLeave() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob", LabLimits(maxPerPendingThread = KEEP)).apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            val groupId = lineWithARequestGroup(alice, bob, carol)

            alice.transport.lossy(bob.transport) { it.decoded()?.let { e -> e.senderId == alice.nodeId && e.group != null } == true }
            assertTrue(alice.sendGroup(groupId, "last words"))
            val lastWords = alice.lostTo()
            alice.leaveGroup(groupId)
            assertTrue(lab.tryAwait(1) { if (bob.groupShape(groupId)?.departed == setOf(alice.nodeId)) 1 else 0 })
            pushOutBobsNotices(bob, carol, groupId)

            reServeToBob(alice, bob, lastWords)
            assertEquals("the pre-leave roster did not rejoin alice", setOf(alice.nodeId), bob.groupShape(groupId)?.departed)
            assertEquals(setOf(bob.nodeId, carol.nodeId), bob.groupShape(groupId)?.members)
        }

    /**
     * The mirror: the rejoin's clock outlives its line. Alice leaves once where Bob cannot hear it and comes
     * back, then leaves and comes back again where he can. Bob trims the thread past the `rejoin:` notice, and
     * her first leave reaches him only then, from custody: it is older than the rejoin held on the group, so she
     * stays in.
     */
    @Test
    fun anOldLeaveReServedAfterTheSweepDroppedTheRejoinLineCannotEvictAgain() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob", LabLimits(maxPerPendingThread = KEEP)).apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            val groupId = lineWithARequestGroup(alice, bob, carol)

            alice.transport.lossy(bob.transport) { it.decoded()?.type == "groupleave" }
            alice.leaveGroup(groupId)
            val firstLeave = alice.lostTo()
            assertTrue(lab.tryAwait(1) { if (carol.groupShape(groupId)?.departed == setOf(alice.nodeId)) 1 else 0 })
            alice.transport.lossy(bob.transport) { it.decoded()?.id == firstLeave }
            alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(groupId, "back once"))
            assertTrue(lab.tryAwait(1) { bob.decrypted(groupId).count { it.second == "back once" } })
            assertTrue(lab.tryAwait(1) { if (carol.groupShape(groupId)?.departed?.isEmpty() == true) 1 else 0 })

            alice.leaveGroup(groupId)
            assertTrue(lab.tryAwait(1) { if (bob.groupShape(groupId)?.departed == setOf(alice.nodeId)) 1 else 0 })
            alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(groupId, "back twice"))
            assertTrue(lab.tryAwait(1) { bob.decrypted(groupId).count { it.second == "back twice" } })
            assertEquals("bob took alice back", emptySet<String>(), bob.groupShape(groupId)?.departed)
            pushOutBobsNotices(bob, carol, groupId, after = "back twice")

            reServeToBob(alice, bob, firstLeave)
            assertEquals("the old leave did not evict alice again", emptySet<String>(), bob.groupShape(groupId)?.departed)
            assertEquals(setOf(alice.nodeId, bob.nodeId, carol.nodeId), bob.groupShape(groupId)?.members)
        }

    /**
     * Revocation is eventual (§6.1): Carol is away when Alice leaves and Bob rekeys. When she returns she gets
     * the leave, the fresh seed and Bob's message from custody in whatever order the digest diff serves them,
     * and ends up holding what Bob holds — Alice departed, the message readable.
     */
    @Test
    fun aMemberWhoMissedTheLeaveConvergesOnceItReaches() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            val groupId = triangleWithAGroup(alice, bob, carol)

            lab.unlink(bob, carol)
            lab.unlink(alice, carol)
            alice.leaveGroup(groupId)
            assertTrue(lab.tryAwait(1) { if (bob.groupShape(groupId)?.departed == setOf(alice.nodeId)) 1 else 0 })
            assertTrue(bob.sendGroup(groupId, "while carol was out"))
            lab.assertConverged(listOf(bob), atLeast = 2, carriers = listOf(alice)) { groupId }

            lab.link(bob, carol)
            lab.assertConverged(listOf(bob, carol), atLeast = 2, carriers = listOf(alice)) { groupId }
            assertEquals(setOf(alice.nodeId), carol.groupShape(groupId)?.departed)
            assertTrue(MessageEntity.KIND_MEMBER_LEFT in carol.notices(groupId))
        }

    /**
     * Two renames while the three cannot hear each other; on release the later clock wins on every node,
     * whichever order each one heard them in.
     */
    @Test
    fun aRenameConvergesOnTheLaterClockWhateverOrderItArrivesIn() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            val groupId = triangleWithAGroup(alice, bob, carol)

            listOf(alice, carol).forEach { bob.transport.hold(it.transport) }
            listOf(alice, bob).forEach { carol.transport.hold(it.transport) }
            bob.renameGroup(groupId, "bob's name")
            carol.renameGroup(groupId, "carol's name")
            // Carol's, the later, reaches Alice first; Bob's stale rename lands everywhere after it.
            listOf(alice, bob).forEach { carol.transport.release(it.transport) }
            listOf(alice, carol).forEach { bob.transport.release(it.transport) }

            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }
            assertEquals("carol's name", alice.group(groupId)?.name)
            assertTrue(MessageEntity.KIND_GROUP_RENAMED in alice.notices(groupId))
        }

    private fun WireEnvelope.decoded() = WireCodec.decodeEnvelope(signed)

    /** The one frame this node's link has lost so far (a re-offer may have lost it twice). */
    private fun LabNode.lostTo(): String =
        transport.lost
            .mapNotNull { it.decoded()?.id }
            .distinct()
            .single()

    private fun WireEnvelope.isGroupFrame(): Boolean = WireCodec.decodeEnvelope(signed)?.group != null

    private fun WireEnvelope.isLeaveFrame(): Boolean = WireCodec.decodeEnvelope(signed)?.type == "groupleave"

    private companion object {
        /** The rows Bob keeps per request thread: Carol's two lines push every notice out. */
        const val KEEP = 2
    }
}
