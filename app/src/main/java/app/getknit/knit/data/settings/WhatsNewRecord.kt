package app.getknit.knit.data.settings

/**
 * What this person has seen of the What's New announcements (`ui/whatsnew/WhatsNew`). [seenId] is the newest
 * announcement they closed or read; [shownId] the one the chat list's card has been offering since
 * [shownAt], across [opens] visits — the card's own clock, so it retires without being touched.
 * Carried by a backup: it is about the person, not the phone.
 */
data class WhatsNewRecord(
    val seenId: String? = null,
    val shownId: String? = null,
    val shownAt: Long = 0L,
    val opens: Int = 0,
)
