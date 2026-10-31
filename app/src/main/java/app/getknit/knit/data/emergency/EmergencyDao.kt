package app.getknit.knit.data.emergency

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EmergencyDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: EmergencyEntity): Long

    @Query("SELECT * FROM emergencies WHERE emergencyId = :emergencyId")
    suspend fun findByEmergencyId(emergencyId: String): EmergencyEntity?

    @Query("SELECT * FROM emergencies WHERE messageId = :messageId")
    suspend fun findByMessageId(messageId: String): EmergencyEntity?

    @Query("SELECT * FROM emergencies")
    fun observeAll(): Flow<List<EmergencyEntity>>

    @Query(
        "SELECT * FROM emergencies WHERE kind = 0 AND expiresAt > :nowMillis " +
            "ORDER BY severity ASC, createdAt DESC, emergencyId ASC",
    )
    suspend fun activeSos(nowMillis: Long): List<EmergencyEntity>

    @Query("SELECT * FROM emergencies WHERE kind = 0 ORDER BY createdAt DESC, emergencyId ASC")
    suspend fun sosHistory(): List<EmergencyEntity>

    @Query(
        "SELECT * FROM emergencies WHERE kind = 1 AND targetEmergencyId = :emergencyId " +
            "ORDER BY createdAt ASC, emergencyId ASC",
    )
    suspend fun acknowledgementsFor(emergencyId: String): List<EmergencyEntity>

    @Query("SELECT * FROM emergencies WHERE kind = 1 AND targetEmergencyId = :targetEmergencyId AND senderId = :senderId")
    suspend fun acknowledgementBySender(
        targetEmergencyId: String,
        senderId: String,
    ): EmergencyEntity?

    @Query("DELETE FROM emergencies WHERE messageId NOT IN (SELECT id FROM messages)")
    suspend fun deleteOrphans(): Int
}
