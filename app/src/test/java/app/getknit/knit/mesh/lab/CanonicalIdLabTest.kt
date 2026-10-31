package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.DropReason
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A frame filed under a twin of another frame's id (#178). Android's database binds every id as UTF-16, and SQLite
 * drops one leading U+FEFF there, so `"﻿" + X` passes every in-memory dedup and then lands on `X`'s custody and
 * message rows: the real `X` is refused when it comes, and the digest names `X`, so nobody hands it over again. Every
 * door refuses an id whose shape no build mints (`FrameId.admits`), so the twin is dropped before anything stores it.
 */
@RunWith(RobolectricTestRunner::class)
class CanonicalIdLabTest {
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

    /**
     * Alice posts in the room while she has no links. Mallory, who knows the post's id, floods Bob a post of her own,
     * properly signed, under that id with a byte-order mark in front, and it reaches him first. Bob refuses it at the
     * door; when Alice meets the two of them, her post reaches both and the three converge, custody included, with the
     * twin held nowhere.
     */
    @Test
    fun aTwinUnderAMarkedIdIsRefusedAndTheRealPostStillReachesEveryone() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val mallory = lab.node("mallory").apply { setDisplayName("Mallory") }
            lab.linkAll(bob to mallory)
            lab.awaitAcquainted(bob, mallory)

            assertTrue(alice.sendRoom("hello room"))
            val real = alice.ownMessageId(Conversations.NEARBY, "hello room")
            // Nothing honest is refused, so the count the await below waits for can only be the twin's.
            assertEquals(0L, bob.drops(DropReason.NONCANONICAL_ID))
            mallory.floodRoomPostUnder("﻿" + real, "squatted")
            lab.await(1) { bob.drops(DropReason.NONCANONICAL_ID).toInt() }

            // A triangle: a room tick toward an author who is not a live neighbour ride-holds (ADR 2026-09.aa27), so
            // the oracle's tick check needs every author in range of every reader.
            lab.linkAll(alice to bob, alice to mallory)
            lab.awaitAcquainted(alice, bob, mallory)
            lab.assertConverged(listOf(alice, bob, mallory), atLeast = 1) { Conversations.NEARBY }

            for (node in listOf(alice, bob, mallory)) {
                assertEquals("${node.name}'s room", mapOf(alice.nodeId to setOf("hello room")), node.roomPosts())
                assertTrue("${node.name} custodies the real post", real in node.custodyIds())
                assertNull("${node.name} holds a marked id", node.custodyIds().firstOrNull { it.startsWith("﻿") })
            }
        }
}
