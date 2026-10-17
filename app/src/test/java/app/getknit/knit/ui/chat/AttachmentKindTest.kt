package app.getknit.knit.ui.chat

import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentKindTest {
    @Test
    fun aCardIsKnownByItsOneMimeAndSitsBeforeThePhotoArm() {
        assertEquals(AttachmentKind.Link, attachmentKindOf(LinkPreviewBlob.MIME, name = null))
        assertEquals(AttachmentKind.Photo, attachmentKindOf("image/webp", name = null))
        assertEquals(AttachmentKind.Photo, attachmentKindOf(null, name = null))
        assertEquals(AttachmentKind.Voice, attachmentKindOf("audio/aac", name = null))
        assertEquals(AttachmentKind.File, attachmentKindOf("application/pdf", name = null))
    }

    @Test
    fun aNameStillWinsOverEveryMimeIncludingTheCards() {
        // A named attachment claiming the card MIME is a file that lies about its type, not a card.
        assertEquals(AttachmentKind.File, attachmentKindOf(LinkPreviewBlob.MIME, name = "card.bin"))
        assertEquals(AttachmentKind.File, attachmentKindOf("image/webp", name = "photo.webp"))
    }

    @Test
    fun aVideoIsItsDescriptionOnAVideoMimeAndAnMp4FileStaysAFile() {
        assertEquals(AttachmentKind.Video, attachmentKindOf("video/mp4", name = "video.mp4", video = true))
        assertEquals(AttachmentKind.File, attachmentKindOf("video/mp4", name = "holiday.mp4", video = false))
        assertEquals(AttachmentKind.File, attachmentKindOf("application/pdf", name = "a.pdf", video = true))
    }
}
