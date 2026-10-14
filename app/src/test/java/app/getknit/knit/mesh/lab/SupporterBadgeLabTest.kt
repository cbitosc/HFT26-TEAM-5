package app.getknit.knit.mesh.lab

import app.getknit.knit.badge.BadgeAskState
import app.getknit.knit.badge.BadgeFixtures
import app.getknit.knit.badge.HeldBadge
import app.getknit.knit.data.message.Conversations
import kotlinx.coroutines.flow.first
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
 * The supporter badge end to end (docs/SUPPORTER_BADGE.md, ADR 2026-10.r99s): it rides every sealed profile the holder
 * sends, so a contact sees it and so does a room stranger the holder shares a session with (the maintainer's call,
 * 2026-10-14: showing it is opt-in); turning it off clears it on both; no cleartext profile, newer or not, brings it
 * back or takes it away; and a badge copied into another identity's profile verifies for no one.
 */
@RunWith(RobolectricTestRunner::class)
class SupporterBadgeLabTest {
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

    /** A badge the RFC 8032 TEST 1 key signs for [holder], valid for 30 days from an hour ago on its clock. */
    private fun badgeFor(holder: LabNode): HeldBadge {
        val r = ByteArray(16) { (it * 7).toByte() }
        val nbf = holder.now() / 1_000 - 3_600
        val bc = BadgeFixtures.bc(sub = BadgeFixtures.sub(holder.nodeId, r), nbf = nbf, exp = nbf + 30 * 86_400)
        return HeldBadge(BadgeFixtures.wrapper(bc), r)
    }

    private suspend fun awaitShown(
        viewer: LabNode,
        holder: LabNode,
        shown: Boolean,
    ) = assertTrue(
        "${viewer.name} ${if (shown) "never showed" else "still shows"} ${holder.name}'s badge",
        lab.tryAwait(1) { if (viewer.badgesShownFor(holder).isNotEmpty() == shown) 1 else 0 },
    )

    @Test
    fun everyPeerTheSealedProfileReachesSeesTheBadgeAndTurningItOffClearsIt() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            assertTrue(alice.sendDm(bob, "hi bob"))
            assertTrue(bob.sendDm(alice, "hi alice"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }

            val before = alice.settings.profileVersion.first()
            alice.badges.putHeld(listOf(badgeFor(alice)), BadgeAskState())
            alice.badges.setShow(true)
            awaitShown(bob, alice, shown = true)
            assertEquals(listOf("supporter"), bob.badgesShownFor(alice))
            // A badge change moves the sealed path's version alone: no carrier sees Alice's cleartext version move.
            assertEquals("the cleartext profile version never moved", before, alice.settings.profileVersion.first())

            // Carol only shares the room with Alice, but the room's sealed ticks give the two a session, so Alice's sealed
            // profile reaches her too and she draws the badge: every recipient gets the same bytes.
            assertTrue(alice.sendRoom("hello room"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { Conversations.NEARBY }
            awaitShown(carol, alice, shown = true)

            // A rename republishes both layouts; the cleartext copy must not clear what the sealed one carries.
            alice.setDisplayName("Alice Again")
            assertTrue(lab.tryAwait(1) { if (bob.peer(alice)?.name == "Alice Again") 1 else 0 })
            assertEquals(listOf("supporter"), bob.badgesShownFor(alice))

            alice.badges.setShow(false)
            awaitShown(bob, alice, shown = false)
            awaitShown(carol, alice, shown = false)
            assertTrue(alice.sendDm(bob, "still here"))
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { it.dmWith(if (it === alice) bob else alice) }
            assertTrue(bob.badgesShownFor(alice).isEmpty())
            assertTrue(carol.badgesShownFor(alice).isEmpty())
        }

    /**
     * Turned off while Alice's mesh was stopped: the first start after it still publishes a version of its own, so Bob
     * never holds two badge lists under one version, and his badge for Alice clears.
     */
    @Test
    fun turningItOffWhileStoppedStillClearsIt() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendDm(bob, "hi bob"))
            assertTrue(bob.sendDm(alice, "hi alice"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            alice.badges.putHeld(listOf(badgeFor(alice)), BadgeAskState())
            alice.badges.setShow(true)
            awaitShown(bob, alice, shown = true)
            val sealedOn = alice.badges.sealedVersion.first()

            alice.restart { badges.setShow(false) }
            lab.link(alice, bob)
            awaitShown(bob, alice, shown = false)
            assertTrue("a fresh version carried the change", alice.badges.sealedVersion.first() > sealedOn)
            assertTrue(alice.sendDm(bob, "back"))
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { it.dmWith(if (it === alice) bob else alice) }
            assertTrue(bob.badgesShownFor(alice).isEmpty())
        }

    @Test
    fun aBadgeCopiedOntoAnotherHolderVerifiesForNoOne() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            assertTrue(carol.sendDm(bob, "hi bob"))
            assertTrue(bob.sendDm(carol, "hi carol"))
            lab.assertConverged(listOf(bob, carol), atLeast = 2) { it.dmWith(if (it === bob) carol else bob) }

            // Carol shows Alice's credential, r and all: it commits to Alice's id, so Bob draws nothing for Carol.
            carol.badges.putHeld(listOf(badgeFor(alice)), BadgeAskState())
            carol.badges.setShow(true)
            assertTrue("bob never stored the copied badge", lab.tryAwait(1) { bob.badgeRowsFor(carol) })
            assertTrue(carol.sendDm(bob, "look"))
            lab.assertConverged(listOf(bob, carol), atLeast = 3) { it.dmWith(if (it === bob) carol else bob) }
            assertTrue(bob.badgesShownFor(carol).isEmpty())
        }
}
