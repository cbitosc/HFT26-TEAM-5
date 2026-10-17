package app.getknit.knit.data.relay

import app.getknit.knit.mesh.spool.FreeKeyJournal
import app.getknit.knit.mesh.spool.HeldKey
import app.getknit.knit.mesh.spool.IssuerOrigin
import app.getknit.knit.mesh.spool.KeyAnswer
import app.getknit.knit.mesh.spool.KeyState
import app.getknit.knit.mesh.spool.SpoolStatus
import app.getknit.knit.mesh.spool.SpoolTicket
import app.getknit.knit.mesh.spool.SpoolUploads
import app.getknit.knit.mesh.spool.SpoolUrl
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
 * switch shows, and the one write both make. Built only where tickets are wired (`CALLS_WIRED`); null elsewhere, and
 * then nothing asks.
 */
class FreeUploads(
    statuses: Flow<List<SpoolStatus>>,
    private val free: FreeKeyJournal,
    keys: TicketKeyJournal,
) {
    val offers: Flow<List<FreeUploadOffer>> =
        combine(statuses, free.freeIssuers, free.freeConsent, keys.keys, keys.keyStates, ::freeUploadOffers)
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

private val WAITING = setOf(SpoolUploads.MISSING, SpoolUploads.EXPIRED)
