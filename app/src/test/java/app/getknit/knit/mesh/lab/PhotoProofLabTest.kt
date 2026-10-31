package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.mesh.BlobExchange
import app.getknit.knit.mesh.spool.AttachmentDeferPolicy
import app.getknit.knit.mesh.spool.FakeSpool
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * A DM photo its recipient provably holds never goes up to a spool (ADR 2026-10.vy27, spec C-9.5-6's exception and
 * C-9.5-8's proof of bytes), between real stacks over one in-process [FakeSpool]. Its sibling, the photo whose ack the
 * radio carried but whose bytes it did not, uploading once the peers part, is
 * `InternetPlaneLabTest.aPhotoTheRadioCarriedIsNotUploadedUntilThePeersPart`.
 */
@RunWith(RobolectricTestRunner::class)
class PhotoProofLabTest {
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

    private fun LabNode.dmThreadWith(other: LabNode): (LabNode) -> String = { if (it === this) dmWith(other) else it.dmWith(this) }

    // Passes this process has settled on proof (per process: a restart starts it at 0).
    private fun LabNode.proved(): Int = metrics.spool().spoolAttachProved.toInt()

    /** For a scenario whose step is a clock jump the next timed round must notice (see [LabLimits.spoolTickMs]). */
    private val rounds = LabLimits().calendarRounds()

    /**
     * ADR 2026-10.vy27: a DM photo its recipient provably holds never goes up — not while the peers are together, not
     * after they part, not after a restart. Bob pulls the bytes off the radio link, so his own record says he holds
     * them; Alice asks him over the link with the holder query, and his yes is hers. Then they part, the sighting
     * lapses, and Alice relaunches with no sighting at all: ADR 021's deferral is gone on both counts, and only the
     * persisted proof stands between the photo and the relay. A round that judged it after the restart is the
     * evidence the question was asked again ([MeshMetrics] is per process), so the empty relay is an answer, not a
     * round that never ran.
     */
    @Test
    fun aPhotoItsRecipientHoldsNeverGoesUpEvenAfterThePeersPart() =
        runBlocking {
            val spool = FakeSpool()
            val alice = lab.node("alice", rounds, spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", rounds, spool = spool).apply { setDisplayName("Bob") }
            lab.meetOnTheRelay(alice, bob)
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)

            // Bob is off the relay while the message crosses, so his ack can only come back over the radio and Alice's
            // deferral rests on it and the sighting, not on the send's 60 s grace (wall time), however slow the proof is.
            lab.leaveRelay(bob)
            val picture = Random(27).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "held over the radio", to = bob))
            val sentId = alice.ownMessageId(alice.dmWith(bob), "held over the radio")
            assertTrue(
                "bob's ack never came back over the radio",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    if (alice.receivedVia(alice.dmWith(bob), sentId) == DeliveryPlane.Nearby) 1 else 0
                },
            )
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { alice.dmThreadWith(bob)(it) }
            // Bob holds the bytes now. A query of Alice's that reached him before his store did was answered "fetching"
            // and floors the next for `PROOF_FLOOR_MS`; the jump lets her next deferred round ask again at once, well
            // inside the sighting window.
            lab.clock.advance(BlobExchange.PROOF_FLOOR_MS)
            // Alice settles on Bob's answer; Bob, back on the relay so his rounds run, on his own radio arrival.
            assertTrue(
                "alice never settled the photo on proof\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { alice.proved() },
            )
            lab.rejoinRelay(bob)
            assertTrue(
                "bob never settled the photo on proof\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { bob.proved() },
            )
            assertTrue("chunks went up while the peers were together: ${spool.chunksPut}", spool.chunksPut.isEmpty())

            // Parted, past the sighting window, and relaunched: nothing but the record is left to hold it back.
            lab.unlink(alice, bob)
            lab.clock.advance(AttachmentDeferPolicy.RADIO_WINDOW_MS + 60_000L)
            alice.restart()
            assertTrue(
                "alice never judged the photo after the restart\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { alice.proved() },
            )
            assertTrue(
                "a photo bob provably holds went up: ${spool.chunksPut}; " +
                    "alice pushed=${alice.metrics.spool().spoolAttachPushed} bob pushed=${bob.metrics.spool().spoolAttachPushed}",
                spool.chunksPut.isEmpty(),
            )
        }

    /**
     * The device run's slipped photo (ADR 2026-10.vy27, revision 2). Alice's photo goes out while the relay carries
     * frames only, as the public one does, and Bob's receipt reaches her over that relay first: his frames to her are
     * parked on the link until it has, so her row says Internet and there is no radio ack to defer on. Bob still gets the
     * bytes over the radio. Then the relay grows attachment support, past the send's grace. Alice's first pass on the
     * photo has nothing left to defer on and, before revision 2, pushed in the very pass that asked Bob for proof, the
     * answer a round behind it. Now it holds that one round ([AttachmentDeferPolicy.PROOF_WAIT_MS]) while Bob, who holds
     * the bytes, answers, and the photo never goes up.
     */
    @Test
    fun aPhotoWhoseReceiptCameOverTheRelayWaitsOneProofRoundAndNeverGoesUp() =
        runBlocking {
            val spool = FakeSpool(attachments = false) // frames only, until the photo has crossed the radio
            val alice = lab.node("alice", rounds, spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", rounds, spool = spool).apply { setDisplayName("Bob") }
            lab.meetOnTheRelay(alice, bob)
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)

            bob.transport.hold(alice.transport)
            val picture = Random(195).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "receipt over the relay", to = bob))
            val sentId = alice.ownMessageId(alice.dmWith(bob), "receipt over the relay")
            assertTrue(
                "bob's receipt never came back over the relay",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    if (alice.receivedVia(alice.dmWith(bob), sentId) == DeliveryPlane.Internet) 1 else 0
                },
            )
            // His asks for the bytes were parked with the rest; released, they cross the radio and he holds the photo.
            bob.transport.release(alice.transport)
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { alice.dmThreadWith(bob)(it) }

            // The grace runs out with no relay that takes the photo, so no pass has asked anything yet.
            lab.clock.advance(AttachmentDeferPolicy.ACK_GRACE_MS + 1)
            spool.attachments = true // read per dial
            spool.dropSockets()
            alice.heal()
            bob.heal()
            assertTrue(
                "alice never settled the photo on proof\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { alice.proved() },
            )
            assertTrue(
                "alice's first pass did not hold for the proof round",
                alice.metrics.spool().spoolAttachDeferred >= 1,
            )
            assertTrue(
                "the photo went up ahead of bob's answer: ${spool.chunksPut}; " +
                    "alice pushed=${alice.metrics.spool().spoolAttachPushed} bob pushed=${bob.metrics.spool().spoolAttachPushed}",
                spool.chunksPut.isEmpty(),
            )
        }
}
