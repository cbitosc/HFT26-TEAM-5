package app.getknit.knit.ui.call

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

    @Test
    fun offTheLockScreenTheLineNamesTheGroupsAndCountsPastTwo() {
        val contact = stranger.copy(contact = true, anyMessages = true, groupsTogether = 3)
        assertEquals("Contact · in Climbing", relationshipText(contact.copy(groupsTogether = 1), listOf("Climbing")))
        assertEquals("Contact · in Climbing, Lab", relationshipText(contact.copy(groupsTogether = 2), listOf("Climbing", "Lab")))
        assertEquals("Contact · in Climbing, Lab +1", relationshipText(contact, listOf("Climbing", "Lab", "Zine")))
    }

    @Test
    fun theLockedLineNeverNamesAGroup() {
        val caller = CallerContext(relationship = "Contact · 2 groups together", namedRelationship = "Contact · in Climbing, Lab")
        assertEquals("Contact · 2 groups together", caller.relationshipShown(locked = true))
        assertEquals("Contact · in Climbing, Lab", caller.relationshipShown(locked = false))
        assertEquals("Contact", CallerContext(relationship = "Contact").relationshipShown(locked = false))
    }
}
