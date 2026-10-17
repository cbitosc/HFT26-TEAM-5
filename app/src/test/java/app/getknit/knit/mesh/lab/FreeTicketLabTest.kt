package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.spool.AttachmentDeferPolicy
import app.getknit.knit.mesh.spool.FakeSpool
import app.getknit.knit.mesh.spool.SpoolTicket
import app.getknit.knit.mesh.spool.SpoolUploads
import app.getknit.knit.mesh.spool.SpoolUrl
import kotlinx.coroutines.flow.first
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
import kotlin.random.Random

/**
 * Free tickets between real stacks (docs/VOICE_CALLS.md §2.6, ADR 2026-10.s3ep): the real `ScopeSync` and the real
 * `TicketRefresher` on a node whose relay gates files, beside a [LabFreeIssuer] that answers the account route and the
 * key swap as the coordinator's do. Slow for [InternetPlaneLabTest]'s reasons, hence [MeshLab.SPOOL_AWAIT_MS].
 */
@RunWith(RobolectricTestRunner::class)
class FreeTicketLabTest {
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

    private fun LabNode.uploads(): SpoolUploads? = manager.spoolStatus().singleOrNull { it.connected }?.uploads

    /**
     * docs/VOICE_CALLS.md C-7.6-27 amended, C-7.6-32, C-7.6-35: a phone with no ticket on a files-gated relay, whose
     * person allowed free tickets, sends two photos. The small one waits locked, wakes the refresher, which makes a free
     * account, swaps its key and imports a ticket with `am`; the relay then takes the small photo and the peer fetches
     * it. The large one is over the ticket's `am` and is never `aput`: it still goes nearby once the two meet.
     */
    @Test
    fun anUnticketedPhoneGetsAFreeTicketAndItsSmallPhotoReachesThePeerByRelay() =
        runBlocking {
            val spool = FakeSpool(ent = true, entGates = SpoolTicket.FL_FILES)
            val host = checkNotNull(SpoolUrl.hostname(MeshLab.SPOOL_URL))
            val issuer = LabFreeIssuer(aud = host, clock = lab.clock.forNode("issuer"))
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool, freeIssuer = issuer).apply { setDisplayName("Bob") }
            bob.ticketKeys.setFreeConsent(host, true)
            lab.meetOnTheRelay(alice, bob)
            assertEquals(SpoolUploads.Locked(SpoolUploads.MISSING), bob.uploads())

            // 4 KiB fits the free ticket's 5 chunks; 300 000 B (7 chunks) does not.
            assertTrue(bob.sendImage(Random(93).nextBytes(4_096), "small", to = alice))
            assertTrue(bob.sendImage(Random(94).nextBytes(300_000), "large", to = alice))
            val small = checkNotNull(bob.attachmentHash(bob.dmWith(alice), bob.ownMessageId(bob.dmWith(alice), "small")))
            val large = checkNotNull(bob.attachmentHash(bob.dmWith(alice), bob.ownMessageId(bob.dmWith(alice), "large")))

            // The locked upload asks: one account, one ticket, and the connection takes uploads under it.
            assertTrue(
                "bob's locked upload never got a free ticket\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { if (bob.uploads() == SpoolUploads.Open) 1 else 0 },
            )
            assertEquals(1, issuer.accounts.get())
            assertEquals(1, issuer.tickets.get())
            assertTrue(checkNotNull(bob.ticketKeys.keys.first()[host]).free)

            // The radio sighting's deferral (ADR 021) lapses, and the small photo goes up and across.
            lab.clock.advance(AttachmentDeferPolicy.RADIO_WINDOW_MS + 60_000L)
            assertTrue(
                "alice never got bob's small photo over the relay\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { if (alice.blobs.exists(small)) 1 else 0 },
            )
            // The large one never went up, nor was it refused: it was never tried.
            assertFalse("the large photo reached alice by relay", alice.blobs.exists(large))
            assertEquals(
                "one attachment went up: ${spool.aputs}",
                1,
                spool.aputs
                    .map { it.substringBefore(':') }
                    .distinct()
                    .size,
            )
            assertTrue("the spool refused an upload: ${spool.aputRefusals}", spool.aputRefusals.isEmpty())
            // Nothing more waits, so nothing more is asked of the issuer.
            assertEquals(1, issuer.tickets.get())

            // Alice holds the large one's message (frames are never gated), so her want is born before the re-link's re-ask.
            assertTrue(
                "alice never got the large photo's message\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    alice.decrypted(alice.dmWith(bob)).count { it.second == "large" }
                },
            )
            // In range again, the radio carries the one the relay would not take.
            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 4) { alice.dmThreadWith(bob)(it) }
            assertTrue(
                "the large photo never crossed the radio\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1, timeoutMs = MeshLab.AWAIT_MS) { if (alice.blobs.exists(large)) 1 else 0 },
            )
            assertTrue(spool.aputRefusals.isEmpty())
        }
}
