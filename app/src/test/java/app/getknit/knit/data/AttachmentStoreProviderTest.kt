package app.getknit.knit.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.AttachmentStore.IngestResult
import app.getknit.knit.moderation.ImageScreeningService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import java.io.File
import java.io.FileNotFoundException

/**
 * What only a content provider tells [AttachmentStore] about a pick — its display name, its advertised size and its
 * type — which a file:// Uri ([AttachmentStoreIngestTest]'s door) never offers. An app package is refused by its type
 * or its name, and a file whose provider claims more than the cap is refused, both before a byte is read (a 2 GB pick
 * is not streamed into memory to be told no); and a pick is a video by its declared type, or, with none worth the
 * name, by its container's own signature.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentStoreProviderTest {
    /**
     * A document provider as the picker and the share sheet hand Knit one: a display name, an advertised size and a
     * type per Uri, each null when the provider offers none, and a count of every stream it opened.
     */
    class PickProvider : ContentProvider() {
        class Pick(
            val name: String?,
            val size: Long?,
            val type: String?,
            val bytes: ByteArray,
        )

        val picks = HashMap<Uri, Pick>()
        var opened = 0
        lateinit var dir: File

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            val pick = picks[uri] ?: return null
            val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            return MatrixCursor(columns).apply {
                addRow(
                    columns.map { column ->
                        when (column) {
                            OpenableColumns.DISPLAY_NAME -> pick.name
                            OpenableColumns.SIZE -> pick.size
                            else -> null
                        }
                    },
                )
            }
        }

        override fun getType(uri: Uri): String? = picks[uri]?.type

        override fun openFile(
            uri: Uri,
            mode: String,
        ): ParcelFileDescriptor {
            val pick = picks[uri] ?: throw FileNotFoundException(uri.toString())
            opened++
            val file = File.createTempFile("pick", ".bin", dir).apply { writeBytes(pick.bytes) }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val blobs = mockk<BlobRepository>(relaxed = true)
    private val identities = mockk<AttachmentIdentities>(relaxed = true)
    private val store = AttachmentStore(context, blobs, mockk<ImageScreeningService>(relaxed = true), identities)
    private val stored = HashMap<String, Pair<String, ByteArray>>()
    private val dir = File(context.cacheDir, "picks-${System.nanoTime()}").apply { mkdirs() }
    private val provider =
        Robolectric
            .buildContentProvider(PickProvider::class.java)
            .create(PICKS)
            .get()
            .also { it.dir = dir }

    init {
        coEvery { blobs.insert(any(), any(), any()) } answers { stored[firstArg()] = secondArg<String>() to thirdArg() }
        // Nothing is held under an identity: a relaxed mock would hand back a mock identity.
        coEvery { identities.find(any()) } returns null
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun picked(
        name: String?,
        bytes: ByteArray,
        type: String? = null,
        size: Long? = bytes.size.toLong(),
    ): Uri {
        val uri = Uri.parse("content://$PICKS/${provider.picks.size}")
        provider.picks[uri] = PickProvider.Pick(name, size, type, bytes)
        return uri
    }

    private fun success(result: IngestResult): IngestResult.Success {
        assertTrue("expected a stored attachment, got $result", result is IngestResult.Success)
        return result as IngestResult.Success
    }

    /** The head of an MP4 (`ftyp` box, `isom` brand) and nothing after it: enough for the container's signature. */
    private val mp4Head = byteArrayOf(0, 0, 0, 0x18) + "ftypisom".toByteArray() + ByteArray(16)

    private val pdf = "%PDF-1.4\n1 0 obj << >> endobj\n%%EOF\n".toByteArray()

    @Test
    fun anAppPackageNamedByItsTypeIsRefusedUnread() =
        runTest {
            val uri = picked("game.zip", "PK".toByteArray() + ByteArray(64), type = "application/vnd.android.package-archive")

            assertEquals(IngestResult.Failed(IngestResult.Reason.Installable), store.ingestFile(uri))
            assertTrue(stored.isEmpty())
            assertEquals("the package was read before it was refused", 0, provider.opened)
        }

    @Test
    fun anAppPackageNamedByItsExtensionIsRefusedWhateverTypeItClaims() =
        runTest {
            // The provider's type is advisory; the name the user would save it under is not, in any case.
            for (name in listOf("Knit.apk", "bundle.XAPK", "split.apks")) {
                val uri = picked(name, "PK".toByteArray() + ByteArray(64), type = DEFAULT_MIME)
                assertEquals(name, IngestResult.Failed(IngestResult.Reason.Installable), store.ingestFile(uri))
            }
            assertTrue(stored.isEmpty())
            assertEquals(0, provider.opened)
        }

    @Test
    fun aFileWhoseProviderClaimsMoreThanTheCapIsRefusedBeforeItIsRead() =
        runTest {
            // The bytes themselves are small: only the advertised size can have refused it.
            val uri = picked("scan.pdf", pdf, type = "application/pdf", size = MAX_BYTES + 1L)

            assertEquals(IngestResult.Failed(IngestResult.Reason.TooLarge), store.ingestFile(uri))
            assertEquals("the pick was streamed in to be told no", 0, provider.opened)
            assertTrue(stored.isEmpty())
        }

    @Test
    fun aFileWhoseProviderClaimsTheCapExactlyIsReadAndKeepsItsNameAndType() =
        runTest {
            val uri = picked("Site plan.pdf", pdf, type = "application/pdf", size = MAX_BYTES.toLong())

            val result = success(store.ingestFile(uri))

            assertEquals(1, provider.opened)
            assertEquals("Site plan.pdf", result.ingested.name)
            assertEquals("application/pdf", result.ingested.mime)
            val (mime, bytes) = stored.getValue(result.ingested.hash)
            assertEquals("application/pdf", mime)
            assertArrayEquals(pdf, bytes)
        }

    @Test
    fun aPickDeclaredAVideoIsOneWithoutBeingRead() =
        runTest {
            assertTrue(store.isVideo(picked("clip", ByteArray(32), type = "video/mp4")))
            assertEquals(0, provider.opened)
        }

    @Test
    fun aPickDeclaredAnImageIsNotAVideoWhateverItsBytes() =
        runTest {
            assertFalse(store.isVideo(picked("clip", mp4Head, type = "image/jpeg")))
            assertEquals(0, provider.opened)
        }

    @Test
    fun aPickWithNoUsefulTypeIsAVideoByItsContainersSignature() =
        runTest {
            assertTrue(store.isVideo(picked("clip", mp4Head, type = null)))
            assertTrue(store.isVideo(picked("clip", mp4Head, type = DEFAULT_MIME)))
            // The neighbour: the same untyped door with a document's bytes behind it.
            assertFalse(store.isVideo(picked("notes", pdf, type = DEFAULT_MIME)))
            assertEquals(3, provider.opened)
        }

    /** [AttachmentStore]'s private bounds, restated: a change there should be a deliberate change here. */
    private companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val DEFAULT_MIME = "application/octet-stream"
        const val PICKS = "app.getknit.knit.test.picks"
    }
}
