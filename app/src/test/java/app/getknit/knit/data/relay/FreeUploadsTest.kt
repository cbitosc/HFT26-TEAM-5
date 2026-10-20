package app.getknit.knit.data.relay

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.data.settings.TicketKeyStore
import app.getknit.knit.mesh.spool.HeldKey
import app.getknit.knit.mesh.spool.KeyAnswer
import app.getknit.knit.mesh.spool.KeyState
import app.getknit.knit.mesh.spool.SpoolStatus
import app.getknit.knit.mesh.spool.SpoolTicket
import app.getknit.knit.mesh.spool.SpoolTicketsTest
import app.getknit.knit.mesh.spool.SpoolUploads
import app.getknit.knit.mesh.spool.StoredSpoolTicket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Which relays the free-ticket notice tells about (docs/VOICE_CALLS.md C-7.6-34): a connected relay behind a files gate,
 * with a free issuer the person has not answered for, where a free key is held or uploads wait for want of a ticket
 * with no key that could fetch one instead. Then the size a photo is fitted to (C-7.6-32), and the class that serves both to the
 * chat's notice and the relay rows, over the real key and ticket stores.
 */
class FreeUploadsTest {
    @get:Rule
    val tmp = TemporaryFolder()

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
    }

    @Test
    fun aFreeKeyAlreadyHeldKeepsTheNoticeUpUntilItIsAcknowledged() {
        // On by default, the account can be made before the notice is read: it must not vanish when the ticket opens
        // the uploads.
        val free = mapOf("lax.spool.getknit.app" to HeldKey("https://anywhere.getknit.app", "kak1_x", free = true))
        val held = listOf(FreeUploadOffer("lax.spool.getknit.app", "anywhere.getknit.app", freeKeyHeld = true))
        assertEquals(held, offers(keys = free))
        assertEquals(held, offers(status(uploads = SpoolUploads.Open), keys = free))
        assertEquals(emptyList<FreeUploadOffer>(), offers(keys = free, consent = mapOf("lax.spool.getknit.app" to true)))
        assertEquals(emptyList<FreeUploadOffer>(), offers(status(connected = false), keys = free))
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

    // ── a photo staged while the relay is down (the device run's step 5) ───────────────────────────────

    // What `ScopeSync.status` says of a relay it dials but has no hello from: nothing about its gates or limits.
    private val down = SpoolStatus(url, false, 0, "unreachable", emptyList())

    private val freeKey = HeldKey("https://anywhere.getknit.app", "kak1_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", free = true)

    @Test
    fun aRelayThatIsDownFitsToTheLastFreeTicketHeldThere() {
        val lapsed = SpoolTicketsTest.ticket(aud = "lax.spool.getknit.app", fl = SpoolTicket.FL_FILES, am = 3L * 49_152)
        val held = mapOf("lax.spool.getknit.app" to StoredSpoolTicket(lapsed.text, null))
        assertEquals(3, chunks(down, held = held, keys = mapOf("lax.spool.getknit.app" to freeKey)))
    }

    @Test
    fun aRelayThatIsDownWithNoKeyFitsToTheHintWhereAFreeAccountCouldBeMade() {
        assertEquals(5, chunks(down))
        assertEquals(5, chunks(down, consent = mapOf("lax.spool.getknit.app" to true)))
        // Declined or turned off: no free ticket will come, so nothing is fitted for it.
        assertNull(chunks(down, consent = mapOf("lax.spool.getknit.app" to false)))
    }

    @Test
    fun aRelayThatIsDownUnderACustomerKeyIsNotFitted() {
        val customer = HeldKey("https://anywhere.getknit.app", "kak1_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        assertNull(chunks(down, keys = mapOf("lax.spool.getknit.app" to customer)))
    }

    @Test
    fun aRelayThatIsDownIsJudgedByItsLastHello() {
        // Last seen with no attachments (lax today): it takes no photo, so it sizes nothing — up or down, the same.
        assertNull(chunks(down.copyDown(seenAttachments = false, seenGates = 0L)))
        // Last seen with files ungated: it takes the photo whole.
        assertNull(chunks(down.copyDown(seenAttachments = true, seenGates = 0L)))
        // Last seen files-gated: judged as a locked one, here the hint.
        assertEquals(5, chunks(down.copyDown(seenAttachments = true, seenGates = SpoolTicket.FL_FILES)))
        // Never heard from in this process: today's guess.
        assertEquals(5, chunks(down))
    }

    private fun SpoolStatus.copyDown(
        seenAttachments: Boolean,
        seenGates: Long,
    ) = SpoolStatus(url, false, 0, lastError, emptyList(), seenAttachments = seenAttachments, seenGates = seenGates)

    @Test
    fun aConnectedRelayThatTakesThePhotoWholeStillWinsOverOneThatIsDown() {
        val other = SpoolStatus("wss://open.example/spool/v1", true, 0, null, emptyList(), maxAttachBytes = 16 shl 20)
        assertNull(photoChunks(listOf(down, other), emptyMap(), issuers, emptyMap(), emptyMap(), expected))
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

    /** A connected, files-gated relay at [at] — the [status] helper's shape at another address. */
    private fun relay(
        at: String,
        uploads: SpoolUploads = SpoolUploads.Locked(SpoolUploads.MISSING),
        limit: Int? = null,
        maxAttachBytes: Int? = 16 shl 20,
    ) = SpoolStatus(
        at,
        true,
        0,
        null,
        emptyList(),
        maxAttachBytes = maxAttachBytes,
        ent = true,
        entGates = SpoolTicket.FL_FILES,
        uploads = uploads,
        uploadLimitChunks = limit,
    )

    @Test
    fun aRelayWhoseUrlNamesNoHostIsNeitherOfferedNorSized() {
        val hostless = relay("wss:///spool/v1")
        assertEquals(emptyList<FreeUploadOffer>(), freeUploadOffers(listOf(hostless), issuers, emptyMap(), emptyMap(), emptyMap()))
        assertNull(photoChunks(listOf(hostless), emptyMap(), issuers, emptyMap(), emptyMap(), expected))
    }

    @Test
    fun aLockedRelayWithNoFreeIssuerAndNoKeySizesNothing() {
        // No ticket will come there: no key to ask with, and no issuer to make an account.
        assertNull(photoChunks(listOf(status()), emptyMap(), emptyMap(), emptyMap(), emptyMap(), expected))
    }

    @Test
    fun aTicketHeldForAnotherHostIsNotThisRelaysLimit() {
        // Stored under this host but issued for another (`aud`): it says nothing about this relay, so the hint stands.
        val stray = SpoolTicketsTest.ticket(aud = "sfo.spool.getknit.app", fl = SpoolTicket.FL_FILES, am = 2L * 49_152)
        val held = mapOf("lax.spool.getknit.app" to StoredSpoolTicket(stray.text, null))
        assertEquals(5, chunks(status(), held = held))
    }

    @Test
    fun theSmallestLimitAmongTheRelaysThatSizeAPhotoIsTheOneItIsFittedTo() {
        val tighter = relay("wss://sfo.example/spool/v1", uploads = SpoolUploads.Open, limit = 3)
        assertEquals(3, photoChunks(listOf(status(), tighter), emptyMap(), issuers, emptyMap(), emptyMap(), expected))
        // A connected relay that carries no attachments takes no photo — though its uploads read open — so it sizes
        // nothing and wins nothing.
        val noFiles = relay("wss://text.example/spool/v1", uploads = SpoolUploads.Open, maxAttachBytes = null)
        assertEquals(5, photoChunks(listOf(status(), noFiles), emptyMap(), issuers, emptyMap(), emptyMap(), expected))
    }

    // ── the class the notice and the relay rows read, over the real stores ─────────────────────────────

    private val host = "lax.spool.getknit.app"

    private class Rig(
        val statuses: MutableStateFlow<List<SpoolStatus>>,
        val keys: TicketKeyStore,
        val settings: SettingsStore,
        val uploads: FreeUploads,
    )

    private fun TestScope.rig(initial: SpoolStatus): Rig {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "settings.preferences_pb") }
        val keys = TicketKeyStore(dataStore, seededIssuers = issuers)
        val settings = SettingsStore(dataStore)
        val statuses = MutableStateFlow(listOf(initial))
        return Rig(statuses, keys, settings, FreeUploads(statuses, keys, keys, settings, expected))
    }

    @Test
    fun theNoticeShowsUntilThePersonAnswersAndTheSwitchShowsTheAnswer() =
        runTest {
            val rig = rig(status())
            assertEquals(offer, rig.uploads.offers.first())
            assertEquals(mapOf(host to FreeUploadChoice("anywhere.getknit.app", null)), rig.uploads.choices.first())

            rig.uploads.answer(host, true)

            assertEquals(emptyList<FreeUploadOffer>(), rig.uploads.offers.first())
            assertEquals(mapOf(host to FreeUploadChoice("anywhere.getknit.app", true)), rig.uploads.choices.first())
        }

    @Test
    fun turningFreeUploadsOffForgetsTheFreeKeyAndStopsSizingForThatRelay() =
        runTest {
            val rig = rig(status())
            rig.keys.putKey(host, freeKey)
            assertEquals(5, rig.uploads.photoChunks.first())

            rig.uploads.answer(host, false)

            assertNull("a declined free key was kept", rig.keys.keys.first()[host])
            assertEquals(mapOf(host to FreeUploadChoice("anywhere.getknit.app", false)), rig.uploads.choices.first())
            assertNull(rig.uploads.photoChunks.first())
        }

    @Test
    fun thePhotoLimitFollowsTheTicketHeldAndTheRelaysState() =
        runTest {
            val rig = rig(status())
            rig.keys.putKey(host, freeKey)
            assertEquals(5, rig.uploads.photoChunks.first())

            val ticket = SpoolTicketsTest.ticket(aud = host, fl = SpoolTicket.FL_FILES, am = 2L * 49_152)
            rig.settings.putSpoolTicket(host, ticket.text)
            assertEquals(2, rig.uploads.photoChunks.first())

            // Uploads open with no `am` on the live ticket: the relay takes the photo as it is. The free key's notice
            // stays until it is acknowledged, then goes.
            rig.statuses.value = listOf(status(uploads = SpoolUploads.Open))
            assertNull(rig.uploads.photoChunks.first())
            assertEquals(listOf(FreeUploadOffer(host, "anywhere.getknit.app", freeKeyHeld = true)), rig.uploads.offers.first())
            rig.uploads.answer(host, true)
            assertEquals(emptyList<FreeUploadOffer>(), rig.uploads.offers.first())
        }
}
