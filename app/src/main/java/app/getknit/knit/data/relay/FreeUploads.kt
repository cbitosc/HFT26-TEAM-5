package app.getknit.knit.data.relay

import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.spool.FreeKeyJournal
import app.getknit.knit.mesh.spool.HeldKey
import app.getknit.knit.mesh.spool.HeldTicket
import app.getknit.knit.mesh.spool.IssuerOrigin
import app.getknit.knit.mesh.spool.KeyAnswer
import app.getknit.knit.mesh.spool.KeyState
import app.getknit.knit.mesh.spool.SpoolStatus
import app.getknit.knit.mesh.spool.SpoolTicket
import app.getknit.knit.mesh.spool.SpoolTicketJournal
import app.getknit.knit.mesh.spool.SpoolUploads
import app.getknit.knit.mesh.spool.SpoolUrl
import app.getknit.knit.mesh.spool.StoredSpoolTicket
import app.getknit.knit.mesh.spool.TicketKeyJournal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * A connected relay this phone could send files through with a free account (docs/VOICE_CALLS.md C-7.6-33 to
 * C-7.6-35), waiting on the person's answer to the notice (C-7.6-34): its host, and the free issuer's host the notice
 * names.
 */
data class FreeUploadOffer(
    val relayHost: String,
    val issuerHost: String,
)

/**
 * What the relay row's switch shows for a relay with a free issuer: the issuer's host, and the person's answer —
 * null while they have not been asked.
 */
data class FreeUploadChoice(
    val issuerHost: String,
    val allowed: Boolean?,
)

/**
 * The person's side of free accounts (C-7.6-34): the offers the chat's notice asks about, the choice each relay row's
 * switch shows, and the one write both make. Built only where Knit Anywhere is wired (`ANYWHERE_WIRED`); null elsewhere, and
 * then nothing asks.
 */
class FreeUploads(
    statuses: Flow<List<SpoolStatus>>,
    private val free: FreeKeyJournal,
    keys: TicketKeyJournal,
    tickets: SpoolTicketJournal,
    // Issuer origin → the `am` a free ticket from it is expected to carry (`res/values/spools.xml`), for the photo a
    // phone sends before it has ever held one.
    expectedAm: Map<String, Long> = emptyMap(),
) {
    val offers: Flow<List<FreeUploadOffer>> =
        combine(statuses, free.freeIssuers, free.freeConsent, keys.keys, keys.keyStates, ::freeUploadOffers)
            .distinctUntilChanged()

    /**
     * The most chunks a photo staged now should take, to go up through the connected relays ([photoChunks]), or null
     * to leave it as it is. What the composer hands `AttachmentStore` as the size a photo must fit (C-7.6-32).
     */
    val photoChunks: Flow<Int?> =
        combine(
            statuses,
            tickets.spoolTickets,
            combine(free.freeIssuers, free.freeConsent, keys.keys, ::Triple),
        ) { live, held, (issuers, consent, keyed) -> photoChunks(live, held, issuers, consent, keyed, expectedAm) }
            .distinctUntilChanged()

    val choices: Flow<Map<String, FreeUploadChoice>> =
        combine(free.freeIssuers, free.freeConsent) { issuers, consent ->
            issuers.mapValues { (host, origin) -> FreeUploadChoice(IssuerOrigin.host(origin), consent[host]) }
        }.distinctUntilChanged()

    /** Records the person's answer for [relayHost]; turning it off forgets the free key held there. */
    suspend fun answer(
        relayHost: String,
        allowed: Boolean,
    ) = free.setFreeConsent(relayHost, allowed)
}

/**
 * The offers the notice asks about: each connected relay whose files gate holds this phone's uploads for want of a
 * ticket (`missing` or `expired`, C-7.6-24), whose host has a free issuer the person has not answered for, and where
 * no key could fetch one instead — none held, or a customer key whose account ended (C-7.6-28). Sorted by host, so
 * the notice is stable.
 */
internal fun freeUploadOffers(
    statuses: List<SpoolStatus>,
    issuers: Map<String, String>,
    consent: Map<String, Boolean>,
    keys: Map<String, HeldKey>,
    keyStates: Map<String, KeyState>,
): List<FreeUploadOffer> =
    statuses
        .asSequence()
        .filter { it.connected && it.entGates and SpoolTicket.FL_FILES != 0L }
        .filter { (it.uploads as? SpoolUploads.Locked)?.reason in WAITING }
        .mapNotNull { status ->
            val host = SpoolUrl.hostname(status.url) ?: return@mapNotNull null
            val origin = issuers[host] ?: return@mapNotNull null
            if (host in consent) return@mapNotNull null
            val key = keys[host]
            if (key != null && (key.free || keyStates[host]?.answer != KeyAnswer.ENDED)) return@mapNotNull null
            FreeUploadOffer(host, IssuerOrigin.host(origin))
        }.distinct()
        .sortedBy { it.relayHost }
        .toList()

/**
 * The chunk limit a photo is fitted to (C-7.6-32), over the relays this phone dials, connected or not: null — no
 * re-encode — when any of them would take it as it is (a connected one with no files gate, or an open one whose ticket
 * sets no `am`, or one under a customer key), since one relay that takes it is enough (§9.1's union rule); else the
 * smallest limit among the rest. A connected, open relay's is its live ticket's. One locked for want of a ticket, and
 * one that is down (a photo staged offline goes up when it is back), count where a ticket could come — a key held, or
 * a free issuer the person has not turned down — at the `am` of the ticket last held there, live or lapsed, or with
 * none yet, the `am` a free ticket from its issuer is expected to carry. A connected relay that carries no attachments,
 * and a spent, pending or refused one, take nothing today and do not count.
 */
@Suppress("LongParameterList") // the facts one rule reads; a holder would only rename them
internal fun photoChunks(
    statuses: List<SpoolStatus>,
    held: Map<String, StoredSpoolTicket>,
    issuers: Map<String, String>,
    consent: Map<String, Boolean>,
    keys: Map<String, HeldKey>,
    expectedAm: Map<String, Long>,
): Int? {
    val facts = FitFacts(held, issuers, consent, keys, expectedAm)
    val fits = statuses.filter { !it.connected || it.maxAttachBytes != null }.map { relayFit(it, facts) }
    if (fits.any { it == Fit.Whole }) return null
    return fits.filterIsInstance<Fit.Chunks>().minOfOrNull { it.chunks }
}

/** What one relay asks of a photo: nothing ([Whole]), a chunk limit ([Chunks]), or nothing today ([None]). */
private sealed interface Fit {
    /** It takes the photo as it is. */
    data object Whole : Fit

    /** It takes one of at most [chunks] chunks. */
    data class Chunks(
        val chunks: Int,
    ) : Fit

    /** It takes no upload of this phone's today, so it sizes nothing. */
    data object None : Fit
}

/** [photoChunks]' stores, read once per pass. */
private class FitFacts(
    val held: Map<String, StoredSpoolTicket>,
    val issuers: Map<String, String>,
    val consent: Map<String, Boolean>,
    val keys: Map<String, HeldKey>,
    val expectedAm: Map<String, Long>,
)

private fun relayFit(
    status: SpoolStatus,
    facts: FitFacts,
): Fit {
    val host = SpoolUrl.hostname(status.url)
    val uploads = status.uploads
    return when {
        host == null -> Fit.None
        !status.connected -> downFit(status, host, facts)
        status.entGates and SpoolTicket.FL_FILES == 0L -> Fit.Whole
        uploads == SpoolUploads.Open -> status.uploadLimitChunks?.let(Fit::Chunks) ?: Fit.Whole
        uploads is SpoolUploads.Locked && uploads.reason in WAITING -> lockedFit(host, facts)
        else -> Fit.None
    }
}

/**
 * A relay that is down, by what its last hello in this process said: one that carried no attachments takes nothing,
 * one that left files ungated takes the photo whole, and a files-gated one, or one never heard from, is judged as a
 * locked one ([lockedFit]).
 */
private fun downFit(
    status: SpoolStatus,
    host: String,
    facts: FitFacts,
): Fit =
    when {
        status.seenAttachments == false -> Fit.None
        status.seenGates?.let { it and SpoolTicket.FL_FILES == 0L } == true -> Fit.Whole
        else -> lockedFit(host, facts)
    }

/**
 * A relay locked for want of a ticket, or down: the last held ticket's `am`; else, under a customer key, any size (its
 * ticket's `am` is unknown, and the hint is a free ticket's, contract revision 4); else, with a free key or none and a
 * free account not turned down, the hint; else nothing.
 */
private fun lockedFit(
    host: String,
    facts: FitFacts,
): Fit {
    val key = facts.keys[host]
    val lastAm =
        facts.held[host]
            ?.let { HeldTicket.parse(it.text) }
            ?.takeIf { it.claims.aud == host }
            ?.claims
            ?.am
    val origin = facts.issuers[host]
    val hint = origin?.takeIf { key != null || facts.consent[host] != false }?.let(facts.expectedAm::get)
    return when {
        lastAm != null -> Fit.Chunks(SpoolTicket.chunksOf(lastAm))
        key != null && !key.free -> Fit.Whole
        hint != null -> Fit.Chunks(SpoolTicket.chunksOf(hint))
        else -> Fit.None
    }
}

/**
 * The most plaintext bytes a photo may be to fit [chunks] chunks once sealed end to end (`AttachmentCrypto`: a 12 B
 * nonce and a 16 B tag ride with every blob, docs/VOICE_CALLS.md §2.6), or 0 when even an empty one would not.
 */
fun photoFitBytes(chunks: Int): Int =
    (chunks.toLong() * ScopeCrypto.ATTACH_CHUNK_BYTES - SEAL_OVERHEAD).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

private const val SEAL_OVERHEAD = 12 + 16

private val WAITING = setOf(SpoolUploads.MISSING, SpoolUploads.EXPIRED)
