package app.getknit.knit.ui.call

import android.content.Context
import android.graphics.Bitmap
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.notifications.NotificationAvatars
import app.getknit.knit.ui.theme.ThemePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Who a call notification is with: the peer's label and the face the chat list draws for them. */
internal class CallFace(
    val name: String,
    bitmap: Bitmap,
) {
    // Adaptive, as the message notifications' people are: the system masks it to its own avatar shape.
    val icon: IconCompat = IconCompat.createWithAdaptiveBitmap(bitmap)

    val person: Person
        get() =
            Person
                .Builder()
                .setName(name)
                .setIcon(icon)
                .setImportant(true)
                .build()
}

/**
 * The faces the call notifications show (experimental, `CALLS_WIRED`): the peer's avatar photo when one is held, else
 * their initial on their own tint, both painted by the message notifications' [NotificationAvatars] so a caller looks
 * the same in a call as in a thread (ADR 2026-09.j8c7). The repositories are late-bound, like [CallController]'s, so
 * building this at process start opens no database.
 */
internal class CallFaces(
    private val context: Context,
    private val peers: () -> PeerRepository,
    private val blobs: () -> BlobRepository,
    private val theme: () -> ThemePreferences,
) {
    private val avatars by lazy { NotificationAvatars(context, theme()) }

    suspend fun of(peerId: String): CallFace {
        val repository = peers()
        val peer = repository.find(peerId)
        val name = repository.labelIndex().labelFor(peerId, peer?.name).text
        val bytes = peer?.avatarHash?.let { blobs().bytes(it) }
        // A peer's photo is decoded bounded, but still decoded: never on the service's main-thread collector.
        val bitmap = withContext(Dispatchers.Default) { avatars.bitmapFor(bytes) ?: avatars.letterAvatar(name, key = peerId) }
        return CallFace(name, bitmap)
    }
}
