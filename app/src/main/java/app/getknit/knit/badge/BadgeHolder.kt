package app.getknit.knit.badge

import app.getknit.knit.mesh.protocol.BadgeEntry
import app.getknit.knit.mesh.spool.DeviceSigner
import app.getknit.knit.mesh.spool.IssuerAnswer
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * One badge this phone holds for itself (docs/SUPPORTER_BADGE.md §5): the [wrapper] the issuer signed and the [r] this
 * phone drew for the request that earned it. Kept per badge, out of every backup (C-5-6). Equal by its bytes; its
 * [toString] names neither, since `r` is what keeps the issuer from naming the holder.
 */
class HeldBadge(
    val wrapper: ByteArray,
    val r: ByteArray,
) {
    /** What a sealed profile carries for it. */
    fun entry(): BadgeEntry = BadgeEntry(w = wrapper, r = r)

    override fun equals(other: Any?): Boolean = other is HeldBadge && wrapper.contentEquals(other.wrapper) && r.contentEquals(other.r)

    override fun hashCode(): Int = 31 * wrapper.contentHashCode() + r.contentHashCode()

    override fun toString(): String = "HeldBadge(…)"
}

/** When the refresher may ask next ([notBefore], epoch millis) and how many asks in a row got no verdict. Nothing secret. */
data class BadgeAskState(
    val notBefore: Long = 0,
    val failures: Int = 0,
)

/**
 * Where the holder's side keeps its state (implemented by `data/settings/BadgeStore`, in the settings DataStore): the
 * "Show my supporter badge" choice, which rides a backup like any setting, and the held badges with the ask state,
 * which never do (C-5-6).
 */
interface BadgeJournal {
    /** "Show my supporter badge": off until the person turns it on (C-5-1). */
    val show: Flow<Boolean>

    /** The badges held, in display order. */
    val held: Flow<List<HeldBadge>>

    val askState: Flow<BadgeAskState>

    suspend fun setShow(on: Boolean)

    /** Replaces the held badges and the ask state in one edit. */
    suspend fun putHeld(
        badges: List<HeldBadge>,
        state: BadgeAskState,
    )

    suspend fun putAskState(state: BadgeAskState)

    /**
     * The profile version the last badge-only change was published under (0 before the first), and the fingerprint of
     * the badge list it published (null before the first). A badge change moves only this version, never the cleartext
     * profile's, so a carrier holding our cleartext frame never sees a version move with no field changed (ADR
     * 2026-10.r99s). Both ride a backup, so a restored phone never re-uses a version its peers have seen.
     */
    val sealedVersion: Flow<Long>
    val publishedFingerprint: Flow<String?>

    suspend fun putSealed(
        version: Long,
        fingerprint: String,
    )
}

/**
 * The HTTP seam under [BadgeRefresher]: one `POST <origin>/api/v1/badge` with the customer key in the `Authorization`
 * header and `{"sub": …}` as the body. Implemented beside the ticket swap (`mesh/spool/OkHttpTicketIssuer`).
 */
interface BadgeIssuer {
    /** Asks [origin] (already accepted by `IssuerOrigin`) to sign [sub] (32 bytes) with [key]. Never throws. */
    suspend fun issueBadge(
        origin: String,
        key: String,
        sub: ByteArray,
        device: DeviceSigner? = null,
    ): BadgeAnswer
}

/** The issuer's answers (C-5-8). Only [Issued] carries a credential, and nothing carries the key or `sub`. */
sealed interface BadgeAnswer {
    /** 200: every entry's `knit-badge:v1:` text, still to be verified and filtered to the ids this build knows. */
    class Issued(
        val badges: List<String>,
    ) : BadgeAnswer {
        override fun toString(): String = "Issued(${badges.size})"
    }

    /** 401 `unknown-key`. */
    data object UnknownKey : BadgeAnswer

    /** 403 `ended`, at [endsAt] (epoch millis) when the answer said. */
    data class Ended(
        val endsAt: Long?,
    ) : BadgeAnswer

    /** 503 `unavailable`: the issuer signs no badges right now. */
    data object Unavailable : BadgeAnswer

    /** 409 `bound-elsewhere` (C-5-8, docs/VOICE_CALLS.md C-7.6-42): another phone holds the account; movable from [movableAt]. */
    data class BoundElsewhere(
        val movableAt: Long?,
    ) : BadgeAnswer

    /** 426 `upgrade-required`: backed off from as no verdict. */
    data object UpgradeRequired : BadgeAnswer

    /** 400 `bad-proof`, after the one re-sign docs/VOICE_CALLS.md C-7.6-43 allows: backed off from as no verdict. */
    data object BadProof : BadgeAnswer

    /** Anything else, `bad-sub` included: no verdict. [reason] is a short word for the log, never the body. */
    data class NoVerdict(
        val reason: String,
    ) : BadgeAnswer

    companion object {
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_UNAVAILABLE = 503

        /** Reads a response. Like the ticket swap's reader, a 400/401/403/409/426/503 counts only with its own `error` word. */
        fun read(
            status: Int,
            body: ByteArray,
        ): BadgeAnswer {
            val parsed = runCatching { json.decodeFromString<Answer>(body.decodeToString()) }.getOrNull()
            return when {
                parsed == null -> NoVerdict(if (status == HTTP_OK) "unparseable" else "http $status")
                status == HTTP_OK -> parsed.badges?.let { list -> Issued(list.mapNotNull { it.badge }) } ?: NoVerdict("unparseable")
                else -> refusal(status, parsed) ?: NoVerdict("http $status")
            }
        }

        // A refusal the issuer named with its own `error` word, or null for one it did not (`bad-sub` included).
        private fun refusal(
            status: Int,
            parsed: Answer,
        ): BadgeAnswer? =
            when (status to parsed.error) {
                HTTP_UNAUTHORIZED to "unknown-key" -> UnknownKey
                HTTP_FORBIDDEN to "ended" -> Ended(parsed.endsAt?.let(::instant))
                HTTP_UNAVAILABLE to "unavailable" -> Unavailable
                IssuerAnswer.HTTP_CONFLICT to IssuerAnswer.BOUND_ELSEWHERE -> BoundElsewhere(parsed.movableAt?.let(::instant))
                IssuerAnswer.HTTP_UPGRADE_REQUIRED to IssuerAnswer.UPGRADE_REQUIRED -> UpgradeRequired
                IssuerAnswer.HTTP_BAD_REQUEST to IssuerAnswer.BAD_PROOF -> BadProof
                else -> null
            }

        private fun instant(text: String): Long? =
            try {
                Instant.parse(text).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }

        private val json = Json { ignoreUnknownKeys = true }
    }
}

// The JSON copies of b/nbf/exp beside each entry are not read: the app acts on the claims inside the credential.
@Serializable
private class Answer(
    val badges: List<Entry>? = null,
    val error: String? = null,
    val endsAt: String? = null,
    val movableAt: String? = null,
)

@Serializable
private class Entry(
    val badge: String? = null,
)
