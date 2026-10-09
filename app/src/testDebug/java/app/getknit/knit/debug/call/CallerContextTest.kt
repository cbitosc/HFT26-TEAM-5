package app.getknit.knit.debug.call

import org.junit.Assert.assertEquals
import org.junit.Test

class CallerContextTest {
    private val stranger = Relationship(contact = false, verified = false, blocked = false, groupsTogether = 0, anyMessages = false)

    @Test
    fun theRelationshipLineCountsGroupsAndNamesNone() {
        assertEquals("Not a contact · no messages yet", relationshipText(stranger))
        assertEquals("Not a contact", relationshipText(stranger.copy(anyMessages = true)))
        assertEquals("Contact", relationshipText(stranger.copy(contact = true, anyMessages = true)))
        assertEquals("Contact · 1 group together", relationshipText(stranger.copy(contact = true, groupsTogether = 1)))
        assertEquals(
            "Verified contact · 2 groups together",
            relationshipText(stranger.copy(contact = true, verified = true, groupsTogether = 2)),
        )
        assertEquals("Blocked", relationshipText(stranger.copy(blocked = true, verified = true, anyMessages = true)))
    }
}
