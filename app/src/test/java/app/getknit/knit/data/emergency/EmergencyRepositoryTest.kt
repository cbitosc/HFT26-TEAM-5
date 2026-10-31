package app.getknit.knit.data.emergency

import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.emergency.EmergencyCodec
import app.getknit.knit.emergency.EmergencyIds
import app.getknit.knit.emergency.EmergencyKind
import app.getknit.knit.emergency.EmergencyLocation
import app.getknit.knit.emergency.EmergencyPayload
import app.getknit.knit.emergency.EmergencySeverity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyRepositoryTest : RoomDbTest() {
    private val now = 1_800_000_000_000L

    @Test
    fun `fresh database stores and retrieves an SOS projection`() =
        runTest {
            val payload = sos(location = true)
            seed("m-sos", "alice", payload)

            assertEquals(EmergencyInsertResult.Inserted, repository().indexAuthenticatedMessage("m-sos", "alice", now))
            val stored = repository().activeSos(now).single()
            assertEquals(payload.emergencyId, stored.emergencyId)
            assertEquals("m-sos", stored.messageId)
            assertEquals("alice", stored.senderId)
            assertEquals(EmergencyEntity.SEVERITY_CRITICAL, stored.severity)
            assertEquals(17.385, stored.latitude!!, 0.0)
            assertFalse(stored.isExpired(now))
        }

    @Test
    fun `acknowledgement may arrive before its SOS and is found once target is indexed`() =
        runTest {
            val target = EmergencyIds.new()
            val ack = acknowledgement(target)
            seed("m-ack", "bob", ack)
            assertEquals(EmergencyInsertResult.Inserted, repository().indexAuthenticatedMessage("m-ack", "bob", now))
            assertEquals(ack.emergencyId, repository().acknowledgementsFor(target).single().emergencyId)

            val sos = sos(id = target)
            seed("m-sos", "alice", sos)
            assertEquals(EmergencyInsertResult.Inserted, repository().indexAuthenticatedMessage("m-sos", "alice", now))
            assertEquals("bob", repository().acknowledgementsFor(target).single().senderId)
        }

    @Test
    fun `same emergency id cannot overwrite the first accepted projection`() =
        runTest {
            val id = EmergencyIds.new()
            seed("m-first", "alice", sos(id = id, text = "first"))
            seed("m-second", "mallory", sos(id = id, text = "replacement"))
            assertEquals(EmergencyInsertResult.Inserted, repository().indexAuthenticatedMessage("m-first", "alice", now))
            assertEquals(
                EmergencyInsertResult.DuplicateEmergencyId,
                repository().indexAuthenticatedMessage("m-second", "mallory", now),
            )
            assertEquals("m-first", repository().activeSos(now).single().messageId)
        }

    @Test
    fun `same sender cannot acknowledge one SOS twice`() =
        runTest {
            val target = EmergencyIds.new()
            seed("m-ack-1", "bob", acknowledgement(target))
            seed("m-ack-2", "bob", acknowledgement(target))
            assertEquals(EmergencyInsertResult.Inserted, repository().indexAuthenticatedMessage("m-ack-1", "bob", now))
            assertEquals(
                EmergencyInsertResult.DuplicateAcknowledgement,
                repository().indexAuthenticatedMessage("m-ack-2", "bob", now),
            )
            assertEquals(1, repository().acknowledgementsFor(target).size)
        }

    @Test
    fun `missing message and mismatched authenticated sender are rejected`() =
        runTest {
            assertEquals(EmergencyInsertResult.MessageMissing, repository().indexAuthenticatedMessage("absent", "alice", now))
            seed("m-sos", "alice", sos())
            assertEquals(EmergencyInsertResult.SenderMismatch, repository().indexAuthenticatedMessage("m-sos", "mallory", now))
            assertTrue(repository().activeSos(now).isEmpty())
        }

    @Test
    fun `expired events leave the active list but remain marked in history`() =
        runTest {
            seed("m-sos", "alice", sos(expiresAt = now + 1_000))
            assertEquals(EmergencyInsertResult.Inserted, repository().indexAuthenticatedMessage("m-sos", "alice", now))
            assertTrue(repository().activeSos(now + 2_000).isEmpty())
            assertTrue(repository().sosHistory(now + 2_000).single().expired)
        }

    @Test
    fun `orphan cleanup removes only projections whose authoritative message is gone`() =
        runTest {
            seed("m-keep", "alice", sos())
            seed("m-delete", "bob", sos())
            repository().indexAuthenticatedMessage("m-keep", "alice", now)
            repository().indexAuthenticatedMessage("m-delete", "bob", now)
            db.messageDao().deleteById("m-delete")

            assertEquals(1, repository().cleanOrphanedProjections())
            assertEquals(listOf("m-keep"), repository().activeSos(now).map { it.messageId })
        }

    private fun repository() = EmergencyRepository(db)

    private suspend fun seed(
        messageId: String,
        senderId: String,
        payload: EmergencyPayload,
    ) {
        db.messageDao().upsert(
            MessageEntity(
                id = messageId,
                senderId = senderId,
                body = EmergencyCodec.encode(payload, now),
                sentAt = payload.createdAt,
            ),
        )
    }

    private fun sos(
        id: String = EmergencyIds.new(),
        text: String = "Need help",
        expiresAt: Long = now + 60_000,
        location: Boolean = false,
    ) = EmergencyPayload(
        kind = EmergencyKind.SOS,
        emergencyId = id,
        severity = EmergencySeverity.CRITICAL,
        createdAt = now - 1_000,
        expiresAt = expiresAt,
        message = text,
        location = if (location) EmergencyLocation(17.385, 78.4867, 12f, now - 1_000) else null,
    )

    private fun acknowledgement(target: String) =
        EmergencyPayload(
            kind = EmergencyKind.HUMAN_ACK,
            emergencyId = EmergencyIds.new(),
            targetEmergencyId = target,
            createdAt = now - 500,
            expiresAt = now + 60_000,
            message = "I am responding",
        )
}
