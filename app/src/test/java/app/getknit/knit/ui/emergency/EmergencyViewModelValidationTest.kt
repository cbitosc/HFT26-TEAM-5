package app.getknit.knit.ui.emergency

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.emergency.EmergencyValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyViewModelValidationTest {
    @Test
    fun `SOS destinations include encrypted DMs and groups but exclude plaintext rooms`() {
        assertTrue(isSecureEmergencyDestination("peer-node-id"))
        assertTrue(isSecureEmergencyDestination("g-private-group"))
        assertFalse(isSecureEmergencyDestination(Conversations.NEARBY))
        assertFalse(isSecureEmergencyDestination(Conversations.MESHTASTIC))
        assertFalse(isSecureEmergencyDestination("c-private-commons"))
    }

    @Test
    fun `blank emergency description names the missing field`() {
        assertEquals(EmergencyFieldError.MessageMissing, messageError("   "))
    }

    @Test
    fun `description at the character limit is valid`() {
        assertNull(messageError("a".repeat(EmergencyValidation.MAX_MESSAGE_CHARS)))
    }

    @Test
    fun `description over the character limit is rejected`() {
        assertEquals(
            EmergencyFieldError.MessageTooLong,
            messageError("a".repeat(EmergencyValidation.MAX_MESSAGE_CHARS + 1)),
        )
    }

    @Test
    fun `multibyte description over the byte limit is rejected`() {
        assertEquals(EmergencyFieldError.MessageTooLong, messageError("🚨".repeat(280)))
    }
}
