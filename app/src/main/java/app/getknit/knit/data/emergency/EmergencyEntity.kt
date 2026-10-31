package app.getknit.knit.data.emergency

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import app.getknit.knit.emergency.EmergencyKind
import app.getknit.knit.emergency.EmergencyPayload
import app.getknit.knit.emergency.EmergencySeverity

/** Local, rebuildable projection of an authenticated emergency chat message. */
@Entity(
    tableName = "emergencies",
    indices = [
        Index(value = ["messageId"], unique = true),
        Index(value = ["kind", "expiresAt"]),
        Index(value = ["targetEmergencyId"]),
        Index(value = ["targetEmergencyId", "senderId"], unique = true),
    ],
)
data class EmergencyEntity(
    @PrimaryKey val emergencyId: String,
    val messageId: String,
    val senderId: String,
    val kind: Int,
    val targetEmergencyId: String?,
    val severity: Int?,
    val createdAt: Long,
    val expiresAt: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    val locationCapturedAt: Long?,
    val indexedAt: Long,
) {
    fun isExpired(nowMillis: Long): Boolean = expiresAt <= nowMillis

    companion object {
        const val KIND_SOS = 0
        const val KIND_HUMAN_ACK = 1
        const val SEVERITY_CRITICAL = 0
        const val SEVERITY_URGENT = 1
        const val SEVERITY_ASSISTANCE = 2

        fun from(
            messageId: String,
            senderId: String,
            payload: EmergencyPayload,
            indexedAt: Long,
        ): EmergencyEntity =
            EmergencyEntity(
                emergencyId = payload.emergencyId,
                messageId = messageId,
                senderId = senderId,
                kind = if (payload.kind == EmergencyKind.SOS) KIND_SOS else KIND_HUMAN_ACK,
                targetEmergencyId = payload.targetEmergencyId,
                severity =
                    when (payload.severity) {
                        EmergencySeverity.CRITICAL -> SEVERITY_CRITICAL
                        EmergencySeverity.URGENT -> SEVERITY_URGENT
                        EmergencySeverity.ASSISTANCE -> SEVERITY_ASSISTANCE
                        null -> null
                    },
                createdAt = payload.createdAt,
                expiresAt = payload.expiresAt,
                latitude = payload.location?.latitude,
                longitude = payload.location?.longitude,
                accuracyMeters = payload.location?.accuracyMeters,
                locationCapturedAt = payload.location?.capturedAt,
                indexedAt = indexedAt,
            )
    }
}
