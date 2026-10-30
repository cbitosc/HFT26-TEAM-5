package app.getknit.knit.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** A call's length reads as a phone app writes it (§3.12). */
class CallDurationTest {
    @Test
    fun minutesAndSecondsThenHoursPastTheHour() {
        assertEquals("0:00", callDuration(0L))
        assertEquals("0:09", callDuration(9_999L))
        assertEquals("3:42", callDuration(222_500L))
        assertEquals("59:59", callDuration(3_599_000L))
        assertEquals("1:00:00", callDuration(3_600_000L))
        assertEquals("2:05:07", callDuration((2 * 3_600 + 5 * 60 + 7) * 1_000L))
    }
}
