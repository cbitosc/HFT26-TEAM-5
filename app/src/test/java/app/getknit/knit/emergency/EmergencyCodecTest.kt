package app.getknit.knit.emergency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class EmergencyCodecTest {
    @Test
    fun validSosRoundTripsDeterministically() {
        val payload = sos(location = EmergencyLocation(17.385, 78.4867, 12.5f, NOW - 1_000))

        val first = EmergencyCodec.encode(payload, NOW)
        val second = EmergencyCodec.encode(payload, NOW)

        assertEquals(first, second)
        assertEquals(EmergencyDecodeResult.Success(payload), EmergencyCodec.decode(first, NOW))
    }

    @Test
    fun validHumanAcknowledgementRoundTrips() {
        val payload =
            EmergencyPayload(
                kind = EmergencyKind.HUMAN_ACK,
                emergencyId = ID_TWO,
                targetEmergencyId = ID_ONE,
                createdAt = NOW,
                expiresAt = NOW + HOUR,
                message = "I have seen this SOS and am responding",
            )

        assertEquals(EmergencyDecodeResult.Success(payload), EmergencyCodec.decode(EmergencyCodec.encode(payload, NOW), NOW))
    }

    @Test
    fun emergencyIdsAreRandom128BitCanonicalValues() {
        val first = EmergencyIds.new()
        val second = EmergencyIds.new()

        assertTrue(EmergencyIds.isValid(first))
        assertTrue(EmergencyIds.isValid(second))
        assertEquals(16, Base64.getUrlDecoder().decode(first).size)
        assertNotEquals(first, second)
    }

    @Test
    fun invalidIdsAreRejected() {
        val result = EmergencyValidation.validate(sos().copy(emergencyId = "not-an-id"), NOW)

        assertTrue(EmergencyValidationError.INVALID_ID in result)
    }

    @Test
    fun expiredMessagesAreRejected() {
        val result = EmergencyValidation.validate(sos().copy(createdAt = NOW - HOUR, expiresAt = NOW), NOW)

        assertTrue(EmergencyValidationError.EXPIRED in result)
    }

    @Test
    fun lifetimesOverSixHoursAreRejected() {
        val result = EmergencyValidation.validate(sos().copy(expiresAt = NOW + EmergencyValidation.MAX_LIFETIME_MS + 1), NOW)

        assertTrue(EmergencyValidationError.LIFETIME_TOO_LONG in result)
    }

    @Test
    fun invalidCoordinatesAndAccuracyAreRejected() {
        val invalid = EmergencyLocation(Double.NaN, 181.0, -1f, NOW)

        assertTrue(EmergencyValidationError.INVALID_LOCATION in EmergencyValidation.validate(sos(location = invalid), NOW))
    }

    @Test
    fun oversizedMessagesAndEncodedPayloadsAreRejected() {
        val messageResult = EmergencyValidation.validate(sos().copy(message = "x".repeat(281)), NOW)
        val oversizedBody = EmergencyCodec.PREFIX + "A".repeat(EmergencyValidation.MAX_ENCODED_BYTES)

        assertTrue(EmergencyValidationError.MESSAGE_TOO_LONG in messageResult)
        assertEquals(
            EmergencyDecodeResult.Invalid(setOf(EmergencyValidationError.PAYLOAD_TOO_LARGE)),
            EmergencyCodec.decode(oversizedBody, NOW),
        )
    }

    @Test
    fun invalidKindFieldCombinationsAreRejected() {
        val sosWithoutSeverity = sos().copy(severity = null)
        val ackWithSeverity =
            sos().copy(
                kind = EmergencyKind.HUMAN_ACK,
                targetEmergencyId = ID_TWO,
            )

        assertTrue(EmergencyValidationError.INVALID_KIND_FIELDS in EmergencyValidation.validate(sosWithoutSeverity, NOW))
        assertTrue(EmergencyValidationError.INVALID_KIND_FIELDS in EmergencyValidation.validate(ackWithSeverity, NOW))
    }

    @Test
    fun invalidAcknowledgementTargetsAreRejected() {
        val missing = sos().copy(kind = EmergencyKind.HUMAN_ACK, severity = null, targetEmergencyId = null)
        val self = missing.copy(targetEmergencyId = ID_ONE)

        assertTrue(EmergencyValidationError.INVALID_ACK_TARGET in EmergencyValidation.validate(missing, NOW))
        assertTrue(EmergencyValidationError.INVALID_ACK_TARGET in EmergencyValidation.validate(self, NOW))
    }

    @Test
    fun unknownMarkerVersionsAreReportedWithoutDecoding() {
        assertEquals(EmergencyDecodeResult.UnsupportedVersion(2), EmergencyCodec.decode("tarnished:sos:v2:anything", NOW))
    }

    @Test
    fun malformedEncodingIsRejectedWithoutThrowing() {
        assertEquals(EmergencyDecodeResult.Malformed, EmergencyCodec.decode(EmergencyCodec.PREFIX + "%%%", NOW))
        assertEquals(EmergencyDecodeResult.Malformed, EmergencyCodec.decode(EmergencyCodec.PREFIX, NOW))
        assertEquals(EmergencyDecodeResult.NotEmergency, EmergencyCodec.decode("ordinary nearby message", NOW))
    }

    @Test
    fun duplicateResolutionAlwaysKeepsTheFirstValidEvent() {
        val existing = sos()
        val altered = existing.copy(message = "altered duplicate")

        assertSame(existing, EmergencyDuplicatePolicy.resolve(existing, altered))
        try {
            EmergencyDuplicatePolicy.resolve(existing, altered.copy(emergencyId = ID_TWO))
            fail("Different emergency ids must not be resolved as duplicates")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun sos(location: EmergencyLocation? = null) =
        EmergencyPayload(
            kind = EmergencyKind.SOS,
            emergencyId = ID_ONE,
            severity = EmergencySeverity.CRITICAL,
            createdAt = NOW,
            expiresAt = NOW + HOUR,
            message = "Need medical assistance",
            location = location,
        )

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val HOUR = 60 * 60_000L
        const val ID_ONE = "AAAAAAAAAAAAAAAAAAAAAA"
        const val ID_TWO = "AQEBAQEBAQEBAQEBAQEBAQ"
    }
}
