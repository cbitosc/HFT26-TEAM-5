package app.getknit.knit.data.relay

import app.getknit.knit.mesh.spool.HeldKey
import app.getknit.knit.mesh.spool.KeyAnswer
import app.getknit.knit.mesh.spool.KeyState
import app.getknit.knit.mesh.spool.SpoolStatus
import app.getknit.knit.mesh.spool.SpoolTicket
import app.getknit.knit.mesh.spool.SpoolUploads
import org.junit.Assert.assertEquals
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
    ) = SpoolStatus(url, connected, 0, null, emptyList(), maxAttachBytes = 16 shl 20, ent = true, entGates = gates, uploads = uploads)

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

    @Test
    fun aRelayWithNoFreeIssuerIsNeverOffered() {
        assertEquals(emptyList<FreeUploadOffer>(), freeUploadOffers(listOf(status()), emptyMap(), emptyMap(), emptyMap(), emptyMap()))
    }
}
