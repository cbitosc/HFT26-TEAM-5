package app.getknit.knit.data.emergency

import androidx.room3.withWriteTransaction
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.emergency.EmergencyCodec
import app.getknit.knit.emergency.EmergencyDecodeResult
import app.getknit.knit.emergency.EmergencyKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Indexes validated emergency content only after its signed Knit message has been accepted and stored. */
class EmergencyRepository(
    private val db: KnitDatabase,
) {
    private val emergencies get() = db.emergencyDao()
    private val messages get() = db.messageDao()

    suspend fun indexAuthenticatedMessage(
        messageId: String,
        authenticatedSenderId: String,
        indexedAt: Long = System.currentTimeMillis(),
    ): EmergencyInsertResult =
        db.withWriteTransaction {
            val message = messages.findById(messageId) ?: return@withWriteTransaction EmergencyInsertResult.MessageMissing
            if (message.senderId != authenticatedSenderId) return@withWriteTransaction EmergencyInsertResult.SenderMismatch
            val payload =
                when (val decoded = EmergencyCodec.decode(message.body, indexedAt)) {
                    is EmergencyDecodeResult.Success -> decoded.payload
                    EmergencyDecodeResult.NotEmergency -> return@withWriteTransaction EmergencyInsertResult.NotEmergency
                    else -> return@withWriteTransaction EmergencyInsertResult.InvalidPayload
                }
            if (emergencies.findByMessageId(messageId) != null) return@withWriteTransaction EmergencyInsertResult.AlreadyIndexed
            if (emergencies.findByEmergencyId(payload.emergencyId) != null) {
                return@withWriteTransaction EmergencyInsertResult.DuplicateEmergencyId
            }
            if (payload.kind == EmergencyKind.HUMAN_ACK) {
                val target = checkNotNull(payload.targetEmergencyId)
                if (emergencies.acknowledgementBySender(target, authenticatedSenderId) != null) {
                    return@withWriteTransaction EmergencyInsertResult.DuplicateAcknowledgement
                }
            }
            val inserted = emergencies.insert(EmergencyEntity.from(messageId, authenticatedSenderId, payload, indexedAt))
            if (inserted != -1L) EmergencyInsertResult.Inserted else EmergencyInsertResult.Conflict
        }

    suspend fun activeSos(nowMillis: Long = System.currentTimeMillis()): List<EmergencyEntity> = emergencies.activeSos(nowMillis)

    suspend fun sosHistory(nowMillis: Long = System.currentTimeMillis()): List<EmergencyPresentation> =
        emergencies.sosHistory().map { EmergencyPresentation(it, it.isExpired(nowMillis)) }

    suspend fun acknowledgementsFor(emergencyId: String): List<EmergencyEntity> = emergencies.acknowledgementsFor(emergencyId)

    /** Message ids accepted from the authenticated inbound path (or authored and indexed locally). */
    fun observeAuthenticatedMessages(): Flow<Map<String, Long>> =
        emergencies.observeAll().map { rows -> rows.associate { it.messageId to it.indexedAt } }

    suspend fun cleanOrphanedProjections(): Int = db.withWriteTransaction { emergencies.deleteOrphans() }
}

data class EmergencyPresentation(
    val event: EmergencyEntity,
    val expired: Boolean,
)

enum class EmergencyInsertResult {
    Inserted,
    AlreadyIndexed,
    DuplicateEmergencyId,
    DuplicateAcknowledgement,
    MessageMissing,
    SenderMismatch,
    NotEmergency,
    InvalidPayload,
    Conflict,
}
