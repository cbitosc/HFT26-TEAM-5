package app.getknit.knit.mesh

/**
 * The words a message notification says in place of a body it cannot show: an attachment-only message's stand-in, a
 * shared position, and a message content filtering hides. [file] takes the file's name as its one argument. The
 * pipeline holds no `Context` (`rules/mesh.md`), so the app hands it these in the reader's own language
 * (`notificationLabels(context)`, the same strings the chat list's preview reads).
 */
data class NotificationLabels(
    val photo: String,
    val gif: String,
    val voice: String,
    val link: String,
    val location: String,
    val file: String,
    val hidden: String,
    val video: String = "🎬 Video",
) {
    companion object {
        /** The English labels — the test rigs' default; the app hands the pipeline the reader's own. */
        val ENGLISH =
            NotificationLabels(
                photo = "📷 Photo",
                gif = "🎞️ GIF",
                voice = "🎤 Voice message",
                link = "🔗 Link",
                location = "📍 Location",
                file = $$"📎 %1$s",
                hidden = "Message hidden",
                video = "🎬 Video",
            )
    }
}
