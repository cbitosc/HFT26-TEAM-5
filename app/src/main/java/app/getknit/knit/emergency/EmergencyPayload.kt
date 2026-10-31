package app.getknit.knit.emergency

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Base64

/** The two emergency events carried inside an ordinary authenticated chat message. */
@Serializable
enum class EmergencyKind {
    @SerialName("s")
    SOS,

    @SerialName("a")
    HUMAN_ACK,
}

/** User-selected urgency. It belongs to an SOS only, never to a human acknowledgement. */
@Serializable
enum class EmergencySeverity {
    @SerialName("c")
    CRITICAL,

    @SerialName("u")
    URGENT,

    @SerialName("a")
    ASSISTANCE,
}

/** A single location fix captured for an SOS; this is not a live-location stream. */
@Serializable
data class EmergencyLocation(
    @SerialName("a") val latitude: Double,
    @SerialName("o") val longitude: Double,
    @SerialName("r") val accuracyMeters: Float,
    @SerialName("t") val capturedAt: Long,
)

/**
 * Versioned TARNISHED emergency content. The existing encrypted DM/group message supplies the authenticated
 * sender; sender identity is deliberately not repeated inside the sealed payload.
 */
@Serializable
data class EmergencyPayload(
    @SerialName("v") val version: Int = CURRENT_VERSION,
    @SerialName("k") val kind: EmergencyKind,
    @SerialName("i") val emergencyId: String,
    @SerialName("r") val targetEmergencyId: String? = null,
    @SerialName("s") val severity: EmergencySeverity? = null,
    @SerialName("c") val createdAt: Long,
    @SerialName("e") val expiresAt: Long,
    @SerialName("m") val message: String,
    @SerialName("l") val location: EmergencyLocation? = null,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** Generates and validates the compact, unpadded Base64url form of a random 128-bit emergency id. */
object EmergencyIds {
    private const val ID_BYTES = 16
    private const val ENCODED_LENGTH = 22
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val random = SecureRandom()

    fun new(): String = ByteArray(ID_BYTES).also(random::nextBytes).let(encoder::encodeToString)

    fun isValid(id: String): Boolean {
        if (id.length != ENCODED_LENGTH || id.any { it !in BASE64URL_CHARS }) return false
        val decoded = runCatching { decoder.decode(id) }.getOrNull() ?: return false
        return decoded.size == ID_BYTES && encoder.encodeToString(decoded) == id
    }

    private const val BASE64URL_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
}
