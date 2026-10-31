package app.getknit.knit.emergency

/** A stable reason an emergency payload was refused. */
enum class EmergencyValidationError {
    UNSUPPORTED_VERSION,
    INVALID_ID,
    INVALID_ACK_TARGET,
    INVALID_KIND_FIELDS,
    INVALID_TIMESTAMP,
    CREATED_IN_FUTURE,
    EXPIRED,
    LIFETIME_TOO_LONG,
    MESSAGE_EMPTY,
    MESSAGE_TOO_LONG,
    INVALID_LOCATION,
    PAYLOAD_TOO_LARGE,
}

object EmergencyValidation {
    const val MAX_LIFETIME_MS = 6 * 60 * 60_000L
    const val MAX_FUTURE_SKEW_MS = 5 * 60_000L
    const val MAX_MESSAGE_CHARS = 280
    const val MAX_MESSAGE_BYTES = 1_024
    const val MAX_CBOR_BYTES = 1_024
    const val MAX_ENCODED_BYTES = 1_600
    const val MAX_ACCURACY_METERS = 10_000f

    fun validate(
        payload: EmergencyPayload,
        nowMillis: Long,
        cborBytes: Int? = null,
        encodedBytes: Int? = null,
    ): Set<EmergencyValidationError> =
        buildSet {
            if (payload.version != EmergencyPayload.CURRENT_VERSION) add(EmergencyValidationError.UNSUPPORTED_VERSION)
            if (!EmergencyIds.isValid(payload.emergencyId)) add(EmergencyValidationError.INVALID_ID)
            validateKind(payload)
            validateTime(payload, nowMillis)
            validateMessage(payload.message)
            payload.location?.let { validateLocation(it, payload.createdAt) }
            if (cborBytes != null && cborBytes > MAX_CBOR_BYTES) add(EmergencyValidationError.PAYLOAD_TOO_LARGE)
            if (encodedBytes != null && encodedBytes > MAX_ENCODED_BYTES) add(EmergencyValidationError.PAYLOAD_TOO_LARGE)
        }

    private fun MutableSet<EmergencyValidationError>.validateKind(payload: EmergencyPayload) {
        when (payload.kind) {
            EmergencyKind.SOS -> {
                if (payload.severity == null || payload.targetEmergencyId != null) {
                    add(EmergencyValidationError.INVALID_KIND_FIELDS)
                }
            }

            EmergencyKind.HUMAN_ACK -> {
                if (payload.severity != null || payload.location != null) {
                    add(EmergencyValidationError.INVALID_KIND_FIELDS)
                }
                if (payload.targetEmergencyId == null || !EmergencyIds.isValid(payload.targetEmergencyId)) {
                    add(EmergencyValidationError.INVALID_ACK_TARGET)
                }
                if (payload.targetEmergencyId == payload.emergencyId) add(EmergencyValidationError.INVALID_ACK_TARGET)
            }
        }
    }

    private fun MutableSet<EmergencyValidationError>.validateTime(
        payload: EmergencyPayload,
        nowMillis: Long,
    ) {
        if (payload.createdAt <= 0L || payload.expiresAt <= payload.createdAt) {
            add(EmergencyValidationError.INVALID_TIMESTAMP)
            return
        }
        if (payload.createdAt > nowMillis + MAX_FUTURE_SKEW_MS) add(EmergencyValidationError.CREATED_IN_FUTURE)
        if (payload.expiresAt <= nowMillis) add(EmergencyValidationError.EXPIRED)
        if (payload.expiresAt - payload.createdAt > MAX_LIFETIME_MS) add(EmergencyValidationError.LIFETIME_TOO_LONG)
    }

    private fun MutableSet<EmergencyValidationError>.validateMessage(message: String) {
        if (message.isBlank()) add(EmergencyValidationError.MESSAGE_EMPTY)
        if (message.length > MAX_MESSAGE_CHARS || message.encodeToByteArray().size > MAX_MESSAGE_BYTES) {
            add(EmergencyValidationError.MESSAGE_TOO_LONG)
        }
    }

    private fun MutableSet<EmergencyValidationError>.validateLocation(
        location: EmergencyLocation,
        createdAt: Long,
    ) {
        val validCoordinates =
            location.latitude.isFinite() && location.latitude in -90.0..90.0 &&
                location.longitude.isFinite() && location.longitude in -180.0..180.0
        val validAccuracy =
            location.accuracyMeters.isFinite() &&
                location.accuracyMeters > 0f &&
                location.accuracyMeters <= MAX_ACCURACY_METERS
        val validCaptureTime = location.capturedAt > 0L && location.capturedAt <= createdAt + MAX_FUTURE_SKEW_MS
        if (!validCoordinates || !validAccuracy || !validCaptureTime) add(EmergencyValidationError.INVALID_LOCATION)
    }
}

/** First-valid-event-wins resolution for two events claiming the same emergency id. */
object EmergencyDuplicatePolicy {
    fun resolve(
        existing: EmergencyPayload,
        candidate: EmergencyPayload,
    ): EmergencyPayload {
        require(existing.emergencyId == candidate.emergencyId) { "Duplicate resolution requires matching emergency ids" }
        return existing
    }
}
