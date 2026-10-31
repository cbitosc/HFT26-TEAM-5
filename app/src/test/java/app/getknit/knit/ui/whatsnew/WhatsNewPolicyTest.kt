package app.getknit.knit.ui.whatsnew

import app.getknit.knit.R
import app.getknit.knit.data.settings.WhatsNewRecord
import app.getknit.knit.ui.releaseNotesUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewPolicyTest {
    private val latest = WhatsNewEntry("b", "2.9", "2.9.0", R.string.whats_new_title, R.string.whats_new_title, emptyList())
    private val now = 1_000_000_000L

    @Test
    fun `an unseen announcement shows, even over an older one that was read`() {
        assertEquals(latest, WhatsNewPolicy.cardFor(latest, WhatsNewRecord(), now))
        assertEquals(latest, WhatsNewPolicy.cardFor(latest, WhatsNewRecord(seenId = "a"), now))
    }

    @Test
    fun `a read announcement or an empty catalog shows nothing`() {
        assertNull(WhatsNewPolicy.cardFor(latest, WhatsNewRecord(seenId = "b"), now))
        assertNull(WhatsNewPolicy.cardFor(null, WhatsNewRecord(), now))
    }

    @Test
    fun `the card retires itself after a week up`() {
        val shown = WhatsNewRecord(shownId = "b", shownAt = now - WhatsNewPolicy.SHOW_FOR_MS + 1, opens = 1)
        assertEquals(latest, WhatsNewPolicy.cardFor(latest, shown, now))
        assertNull(WhatsNewPolicy.cardFor(latest, shown.copy(shownAt = now - WhatsNewPolicy.SHOW_FOR_MS), now))
    }

    @Test
    fun `the card retires itself after enough visits`() {
        val shown = WhatsNewRecord(shownId = "b", shownAt = now, opens = WhatsNewPolicy.SHOW_FOR_OPENS)
        assertEquals(latest, WhatsNewPolicy.cardFor(latest, shown, now))
        assertNull(WhatsNewPolicy.cardFor(latest, shown.copy(opens = WhatsNewPolicy.SHOW_FOR_OPENS + 1), now))
    }

    @Test
    fun `an older announcement's clock does not retire a new one`() {
        val old = WhatsNewRecord(shownId = "a", shownAt = 0L, opens = 99)
        assertEquals(latest, WhatsNewPolicy.cardFor(latest, old, now))
    }

    @Test
    fun `a clock set backwards keeps the card rather than retiring it`() {
        val future = WhatsNewRecord(shownId = "b", shownAt = now + WhatsNewPolicy.SHOW_FOR_MS * 2, opens = 1)
        assertEquals(latest, WhatsNewPolicy.cardFor(latest, future, now))
    }

    @Test
    fun `catalog ids are unique and every entry has something to say`() {
        val ids = WhatsNew.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(WhatsNew.entries.all { it.items.isNotEmpty() })
    }

    @Test
    fun `each entry links its release's notes by the full version`() {
        assertEquals("https://whatsnew.fyi/product/knit/releases/2.9.0", releaseNotesUrl(latest.release))
        assertTrue(WhatsNew.entries.all { Regex("""\d+\.\d+\.\d+""").matches(it.release) })
        assertTrue(WhatsNew.entries.all { it.release.startsWith(it.version + ".") })
    }
}
