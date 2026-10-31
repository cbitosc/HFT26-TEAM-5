@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package app.getknit.knit.emergency

import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import java.util.Base64

sealed interface EmergencyDecodeResult {
    data class Success(
        val payload: EmergencyPayload,
    ) : EmergencyDecodeResult

    data object NotEmergency : EmergencyDecodeResult

    data class UnsupportedVersion(
        val version: Int,
    ) : EmergencyDecodeResult

    data class Invalid(
        val reasons: Set<EmergencyValidationError>,
    ) : EmergencyDecodeResult

    data object Malformed : EmergencyDecodeResult
}

object EmergencyCodec {
    const val PREFIX = "tarnished:sos:v1:"
    private const val MARKER_PREFIX = "tarnished:sos:v"
    private val marker = Regex("^tarnished:sos:v([0-9]+):")
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val cbor =
        Cbor {
            encodeDefaults = false
            ignoreUnknownKeys = true
        }

    /** Encodes only a locally valid, unexpired payload. */
    fun encode(
        payload: EmergencyPayload,
        nowMillis: Long = System.currentTimeMillis(),
    ): String {
        val initial = EmergencyValidation.validate(payload, nowMillis)
        require(initial.isEmpty()) { "Invalid emergency payload: ${initial.joinToString()}" }
        val bytes = cbor.encodeToByteArray(payload)
        val encoded = PREFIX + encoder.encodeToString(bytes)
        val bounded = EmergencyValidation.validate(payload, nowMillis, bytes.size, encoded.encodeToByteArray().size)
        require(bounded.isEmpty()) { "Invalid emergency payload: ${bounded.joinToString()}" }
        return encoded
    }

    /** Decodes and validates a decrypted chat body without throwing on untrusted input. */
    fun decode(
        body: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): EmergencyDecodeResult {
        if (!body.startsWith(MARKER_PREFIX)) return EmergencyDecodeResult.NotEmergency
        val match = marker.find(body) ?: return EmergencyDecodeResult.Malformed
        val markerVersion = match.groupValues[1].toIntOrNull() ?: return EmergencyDecodeResult.Malformed
        if (markerVersion != EmergencyPayload.CURRENT_VERSION) return EmergencyDecodeResult.UnsupportedVersion(markerVersion)
        if (body.encodeToByteArray().size > EmergencyValidation.MAX_ENCODED_BYTES) {
            return EmergencyDecodeResult.Invalid(setOf(EmergencyValidationError.PAYLOAD_TOO_LARGE))
        }
        val encoded = body.substring(match.range.last + 1)
        if (encoded.isEmpty()) return EmergencyDecodeResult.Malformed
        val bytes = runCatching { decoder.decode(encoded) }.getOrNull() ?: return EmergencyDecodeResult.Malformed
        if (bytes.size > EmergencyValidation.MAX_CBOR_BYTES) {
            return EmergencyDecodeResult.Invalid(setOf(EmergencyValidationError.PAYLOAD_TOO_LARGE))
        }
        val payload =
            runCatching { cbor.decodeFromByteArray<EmergencyPayload>(bytes) }.getOrNull()
                ?: return EmergencyDecodeResult.Malformed
        if (payload.version != markerVersion) return EmergencyDecodeResult.UnsupportedVersion(payload.version)
        val reasons = EmergencyValidation.validate(payload, nowMillis, bytes.size, body.encodeToByteArray().size)
        return if (reasons.isEmpty()) EmergencyDecodeResult.Success(payload) else EmergencyDecodeResult.Invalid(reasons)
    }
}
