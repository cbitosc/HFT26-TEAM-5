package app.getknit.knit.data.relay

import app.getknit.knit.mesh.spool.HeldKey
import app.getknit.knit.mesh.spool.KeyAnswer
import app.getknit.knit.mesh.spool.KeyState
import app.getknit.knit.mesh.spool.SpoolStatus
import app.getknit.knit.mesh.spool.SpoolTicket
import app.getknit.knit.mesh.spool.SpoolTicketsTest
import app.getknit.knit.mesh.spool.SpoolUploads
import app.getknit.knit.mesh.spool.StoredSpoolTicket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which relays the free-ticket notice asks about (docs/VOICE_CALLS.md C-7.6-34): a connected relay whose files gate
 * holds this phone's uploads for want of a ticket, with a free issuer the person has not answered for, and no key
 * that could fetch one instead.
 */
class FreeUploadsTest {
    private val url = "wss://lax.spool.getknit.app/spool/v1"
    private val issuers = mapOf("lax.spool.getknit.app" to "https://anywhere.getknit.app")

    private fun status(
        uploads: SpoolUploads = SpoolUploads.Locked(SpoolUploads.MISSING),
        gates: Long = SpoolTicket.FL_FILES,
        connected: Boolean = true,
        limit: Int? = null,
    ) = SpoolStatus(
        url,
        connected,
        0,
        null,
        emptyList(),
        maxAttachBytes = 16 shl 20,
        ent = true,
        entGates = gates,
        uploads = uploads,
        uploadLimitChunks = limit,
    )

    private fun offers(
        status: SpoolStatus = status(),
        consent: Map<String, Boolean> = emptyMap(),
        keys: Map<String, HeldKey> = emptyMap(),
        states: Map<String, KeyState> = emptyMap(),
    ) = freeUploadOffers(listOf(status), issuers, consent, keys, states)

    private val offer = listOf(FreeUploadOffer("lax.spool.getknit.app", "anywhere.getknit.app"))

    @Test
    fun aLockedUploadOnARelayWithAFreeIssuerIsOffered() {
        assertEquals(offer, offers())
        assertEquals(offer, offers(status(uploads = SpoolUploads.Locked(SpoolUploads.EXPIRED))))
    }

    @Test
    fun nothingIsOfferedOnceThePersonAnswered() {
        assertEquals(emptyList<FreeUploadOffer>(), offers(consent = mapOf("lax.spool.getknit.app" to true)))
        assertEquals(emptyList<FreeUploadOffer>(), offers(consent = mapOf("lax.spool.getknit.app" to false)))
    }

    @Test
    fun nothingIsOfferedWhereUploadsAreOpenSpentOrWaitingOnATicket() {
        assertEquals(emptyList<FreeUploadOffer>(), offers(status(uploads = SpoolUploads.Open)))
        assertEquals(emptyList<FreeUploadOffer>(), offers(status(uploads = SpoolUploads.Spent)))
        assertEquals(emptyList<FreeUploadOffer>(), offers(status(uploads = SpoolUploads.Locked(SpoolUploads.PENDING))))
        assertEquals(emptyList<FreeUploadOffer>(), offers(status(uploads = SpoolUploads.Locked(SpoolUploads.FLAGS))))
        assertEquals(emptyList<FreeUploadOffer>(), offers(status(connected = false)))
        assertEquals(emptyList<FreeUploadOffer>(), offers(status(gates = SpoolTicket.FL_TURN)))
    }

    @Test
    fun aCustomerKeyIsOfferedOnlyOnceItsAccountEnded() {
        val key = HeldKey("https://anywhere.getknit.app", "kak1_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        val held = mapOf("lax.spool.getknit.app" to key)
        assertEquals(emptyList<FreeUploadOffer>(), offers(keys = held))
        assertEquals(offer, offers(keys = held, states = mapOf("lax.spool.getknit.app" to KeyState(KeyAnswer.ENDED))))
        assertEquals(emptyList<FreeUploadOffer>(), offers(keys = mapOf("lax.spool.getknit.app" to key.copy(free = true))))
    }

    // ── the size a photo is fitted to (C-7.6-32) ───────────────────────────────────────────────────────

    private val expected = mapOf("https://anywhere.getknit.app" to 262_144L)

    private fun chunks(
        status: SpoolStatus,
        held: Map<String, StoredSpoolTicket> = emptyMap(),
        consent: Map<String, Boolean> = emptyMap(),
        keys: Map<String, HeldKey> = emptyMap(),
    ) = photoChunks(listOf(status), held, issuers, consent, keys, expected)

    @Test
    fun anOpenRelaysTicketSetsTheLimitAndOneWithoutAmSetsNone() {
        assertEquals(5, chunks(status(uploads = SpoolUploads.Open, limit = 5)))
        assertNull(chunks(status(uploads = SpoolUploads.Open)))
        // An ungated relay takes anything: one is enough (the union rule).
        assertNull(chunks(status(gates = SpoolTicket.FL_TURN)))
    }

    @Test
    fun aLockedRelayWithAFreeIssuerFitsTheExpectedAmBeforeAnyTicketAndTheLastTicketsAfter() {
        assertEquals(5, chunks(status()))
        val lapsed = SpoolTicketsTest.ticket(aud = "lax.spool.getknit.app", fl = SpoolTicket.FL_FILES, am = 3L * 49_152)
        val held = mapOf("lax.spool.getknit.app" to StoredSpoolTicket(lapsed.text, null))
        assertEquals(3, chunks(status(uploads = SpoolUploads.Locked(SpoolUploads.EXPIRED)), held = held))
        // Turned down: nothing will come, so nothing is fitted for it.
        assertNull(chunks(status(), consent = mapOf("lax.spool.getknit.app" to false)))
        // Pending, spent or refused for its flags: no upload today, nothing fitted.
        assertNull(chunks(status(uploads = SpoolUploads.Locked(SpoolUploads.PENDING))))
        assertNull(chunks(status(uploads = SpoolUploads.Spent)))
    }

    @Test
    fun aCustomersTicketWithoutAmIsNeverFittedToTheFreeExpectation() {
        val key = HeldKey("https://anywhere.getknit.app", "kak1_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        val customer = mapOf("lax.spool.getknit.app" to key)
        assertNull(chunks(status(uploads = SpoolUploads.Locked(SpoolUploads.EXPIRED)), keys = customer))
    }

    @Test
    fun aCustomerKeyWithNoTicketYetIsNotFittedToTheHint() {
        // Contract revision 4: a customer key goes to the same issuer origin, so the hint would squeeze a paying
        // customer's first photo for nothing.
        val key = HeldKey("https://anywhere.getknit.app", "kak1_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        assertNull(chunks(status(), keys = mapOf("lax.spool.getknit.app" to key)))
        assertNull(chunks(status(), keys = mapOf("lax.spool.getknit.app" to key), consent = mapOf("lax.spool.getknit.app" to true)))
    }

    @Test
    fun declinedOrTurnedOffFreeTicketsAreNotFittedToTheHint() {
        assertNull(chunks(status(), consent = mapOf("lax.spool.getknit.app" to false)))
        assertNull(chunks(status(uploads = SpoolUploads.Locked(SpoolUploads.EXPIRED)), consent = mapOf("lax.spool.getknit.app" to false)))
    }

    @Test
    fun aHeldTicketsAmBeatsTheHint() {
        val free = HeldKey("https://anywhere.getknit.app", "kak1_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", free = true)
        val ticket = SpoolTicketsTest.ticket(aud = "lax.spool.getknit.app", fl = SpoolTicket.FL_FILES, am = 2L * 49_152)
        val held = mapOf("lax.spool.getknit.app" to StoredSpoolTicket(ticket.text, null))
        assertEquals(2, chunks(status(), held = held, keys = mapOf("lax.spool.getknit.app" to free)))
        // And with a free key but no ticket ever held, the hint.
        assertEquals(5, chunks(status(), keys = mapOf("lax.spool.getknit.app" to free)))
    }

    @Test
    fun aPhotosFitIsItsChunksLessTheSeal() {
        assertEquals(245_732, photoFitBytes(5))
        assertEquals(0, photoFitBytes(0))
    }

    @Test
    fun aRelayWithNoFreeIssuerIsNeverOffered() {
        assertEquals(emptyList<FreeUploadOffer>(), freeUploadOffers(listOf(status()), emptyMap(), emptyMap(), emptyMap(), emptyMap()))
    }
}
