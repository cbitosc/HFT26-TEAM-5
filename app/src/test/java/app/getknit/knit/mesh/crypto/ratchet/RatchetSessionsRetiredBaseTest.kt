package app.getknit.knit.mesh.crypto.ratchet

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.ratchet.RatchetRepository
import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine.OpenOutcome
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions.ResetCause
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions.ResetSeal
import app.getknit.knit.mesh.protocol.EncEnvelope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The retired-base rule (ADR 2026-10.9wvr): an `EPOCH_GONE` sealed against one of our local epochs that retention
 * already retired, behind an epoch the peer has since sealed against, is doomed ciphertext and never reset evidence.
 *
 * The rule's table runs on the pure function; the pair cases run two real session services over the real Room SQL,
 * so the frame that fails is one the peer really sealed, held back while the pair kept talking, and judged after the
 * real retention sweep — the field's shape (2026-10-16), where a restart's custody re-serve re-rooted three healthy
 * sessions.
 */
@RunWith(AndroidJUnit4::class)
class RatchetSessionsRetiredBaseTest {
    private val sides = mutableListOf<Side>()

    private inner class Side(
        val nodeId: String,
    ) {
        val ik = RatchetCrypto.generateKeyPair()
        val spk = RatchetCrypto.generateKeyPair()
        val db: KnitDatabase =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KnitDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val sessions =
            RatchetSessions(
                store = RatchetRepository(db.ratchetDao(), clock = { T }),
                dhIdentityPriv = { ik.priv },
                spkPrivFor = { if (it == SPK_ID) spk.priv else null },
            )
        lateinit var peer: Side

        private fun prekey() = RatchetEngine.PeerPrekey(id = SPK_ID, pub = peer.spk.pub)

        suspend fun seal(
            text: String,
            now: Long,
        ): EncEnvelope = checkNotNull(sessions.sealDm(peer.nodeId, peer.ik.pub, prekey(), text.toByteArray(), AAD, now))

        /** Opens and, on success, commits — the pipeline's two phases back to back. */
        suspend fun open(
            env: EncEnvelope,
            now: Long,
        ): OpenOutcome {
            val r = checkNotNull(env.r)
            val outcome = sessions.peekOpen(nodeId, peer.nodeId, peer.ik.pub, r, env.nonce, env.ct, AAD, now)
            if (outcome is OpenOutcome.Opened) {
                assertTrue(
                    "commits what it peeked",
                    sessions.commitOpen(nodeId, peer.nodeId, peer.ik.pub, r, env.nonce, env.ct, AAD, now) {},
                )
            }
            return outcome
        }

        suspend fun session() = checkNotNull(sessions.sessionFor(peer.nodeId))

        suspend fun retiredBase(pe: Int) = sessions.retiredBase(peer.nodeId, pe)
    }

    private fun pair(): Pair<Side, Side> {
        val alice = Side("a-alice")
        val bob = Side("b-bob")
        alice.peer = bob
        bob.peer = alice
        sides += alice
        sides += bob
        return alice to bob
    }

    @After
    fun tearDown() = sides.forEach { it.db.close() }

    /** One DM each way, each opened: a turnaround moves each side's send epoch by one (the healing rule). */
    private suspend fun turn(
        alice: Side,
        bob: Side,
        now: Long,
    ) {
        assertTrue(bob.open(alice.seal("alice at $now", now), now) is OpenOutcome.Opened)
        assertTrue(alice.open(bob.seal("bob at $now", now), now) is OpenOutcome.Opened)
    }

    /**
     * The field's shape on Alice's side: Bob seals [Held.frame] as the first frame of a fresh epoch, based on Alice's
     * newest; it never reaches her, while his next epoch does (his first one ages out a day on), and enough turnarounds
     * follow for the 16-row cap to retire the epoch it was based on. Returns the frame and the epoch it names.
     */
    private suspend fun heldPastTheCap(
        alice: Side,
        bob: Side,
    ): Held {
        var now = T
        repeat(2) {
            turn(alice, bob, now)
            now += MINUTE_MS
        }
        assertTrue(bob.open(alice.seal("alice before the hold", now), now) is OpenOutcome.Opened)
        val held = bob.seal("held by a carrier", now)
        val base = checkNotNull(held.r).pe
        assertEquals("the held frame is based on Alice's newest epoch", alice.session().sendEpoch, base)
        now += RatchetEngine.MAX_EPOCH_AGE_MS
        // A day on, Bob's epoch has aged out: his next seal opens another, so Alice never derives the held one's.
        val after = bob.seal("bob a day later", now)
        assertEquals(checkNotNull(held.r).se + 1, checkNotNull(after.r).se)
        assertTrue(alice.open(after, now) is OpenOutcome.Opened)
        repeat(RatchetRepository.MAX_LOCAL_EPOCHS) {
            now += MINUTE_MS
            turn(alice, bob, now)
        }
        alice.sessions.sweep(now)
        assertNull("the cap retired the held frame's base", alice.db.ratchetDao().localEpochPriv(bob.nodeId, base))
        return Held(held, base, now)
    }

    private class Held(
        val frame: EncEnvelope,
        val base: Int,
        val now: Long,
    )

    @Test
    fun theRuleRetiresOnlyAnEpochBelowBothTheLowestHeldAndThePeersNewest() {
        assertTrue(
            "below both: swept, and the peer has sealed past it",
            RatchetSessions.isRetiredBase(pe = 5, highestPeAcked = 20, lowestRetained = 12),
        )
        assertFalse(
            "the lowest held epoch is not retired",
            RatchetSessions.isRetiredBase(pe = 12, highestPeAcked = 20, lowestRetained = 12),
        )
        assertFalse(
            "a hole above the lowest held is the split-era case",
            RatchetSessions.isRetiredBase(pe = 15, highestPeAcked = 20, lowestRetained = 12),
        )
        assertFalse(
            "past our newest is another era's numbering",
            RatchetSessions.isRetiredBase(pe = 25, highestPeAcked = 20, lowestRetained = 12),
        )
        assertFalse(
            "a peer still based on an epoch the cap evicted is live: its newest seal against us is that epoch",
            RatchetSessions.isRetiredBase(pe = 5, highestPeAcked = 5, lowestRetained = 12),
        )
        assertFalse("the peer never sealed past it", RatchetSessions.isRetiredBase(pe = 5, highestPeAcked = 3, lowestRetained = 12))
        assertFalse(
            "pe 0 is our signed prekey, never a retired epoch",
            RatchetSessions.isRetiredBase(pe = 0, highestPeAcked = 20, lowestRetained = 12),
        )
    }

    @Test
    fun aFrameSealedAgainstAnEpochTheCapRetiredIsDoomedNotEvidence() =
        runTest {
            val (alice, bob) = pair()
            val held = heldPastTheCap(alice, bob)

            assertEquals(OpenOutcome.Failed.EPOCH_GONE, alice.open(held.frame, held.now))
            val verdict = checkNotNull(alice.retiredBase(held.base)) { "the held frame read as evidence" }
            assertTrue("below every epoch Alice still holds", held.base < verdict.lowestRetained)
            assertTrue("and behind an epoch Bob has sealed against since", held.base < verdict.highestPeAcked)

            // The session it would have re-rooted is working: Bob's next frame opens.
            assertTrue(alice.open(bob.seal("still talking", held.now), held.now) is OpenOutcome.Opened)
        }

    @Test
    fun anEpochAtOrAboveTheLowestHeldStillCounts() =
        runTest {
            val (alice, bob) = pair()
            val held = heldPastTheCap(alice, bob)
            val lowest = checkNotNull(alice.db.ratchetDao().lowestLocalEpoch(bob.nodeId))
            val newest = alice.session().sendEpoch

            alice.db.ratchetDao().deleteLocalEpochs(bob.nodeId, listOf(lowest + 1))
            assertNull("a hole above the lowest held epoch is not ours to explain away", alice.retiredBase(lowest + 1))
            assertNull("an epoch we never minted in this numbering", alice.retiredBase(newest + 1))
            assertNull("the signed prekey", alice.retiredBase(0))
            assertNotNull("while the held frame's base still reads as retired", alice.retiredBase(held.base))
        }

    @Test
    fun ourOwnResetShutsTheRuleUntilThePeerSealsUnderTheNewRoot() =
        runTest {
            val (alice, bob) = pair()
            val held = heldPastTheCap(alice, bob)

            val reset =
                alice.sessions.sealResetDm(
                    bob.nodeId,
                    bob.ik.pub,
                    RatchetEngine.PeerPrekey(SPK_ID, bob.spk.pub),
                    RESET,
                    AAD,
                    held.now,
                    ResetCause.ON_DEMAND,
                )
            assertTrue("alice re-rooted", reset is ResetSeal.Sealed && reset.reRooted)
            // A reset restarts our numbering at 1 and its fresh initiation knows of no ack: nothing we can still read
            // in this numbering says the peer has moved past an epoch, so every EPOCH_GONE counts again.
            assertEquals(0, alice.session().highestPeAcked)
            assertNull(alice.retiredBase(held.base))
        }

    @Test
    fun noSessionIsNeverRetired() =
        runTest {
            val (alice, _) = pair()
            assertNull("no session, nothing to protect: a reset is the only answer", alice.retiredBase(3))
        }

    private companion object {
        const val T = 10L * 60 * 60_000L
        const val MINUTE_MS = 60_000L
        const val SPK_ID = 1
        val AAD = "aad".toByteArray()
        val RESET = "reset".toByteArray()
    }
}
