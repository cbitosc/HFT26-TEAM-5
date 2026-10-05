package app.getknit.knit.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.AttachmentStore.IngestResult
import app.getknit.knit.mesh.sha256Hex
import app.getknit.knit.moderation.ImageScreeningService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The image and file doors, with real codecs (native graphics). The property a user relies on without ever
 * seeing it: **a photo leaves the phone with none of its metadata** — no GPS position, no camera make — because
 * the pipeline decodes and re-encodes rather than passing the source bytes on, and it is upright, because the
 * EXIF orientation is applied before the tag is dropped. A JPEG offered as an opaque file takes the same door,
 * since screening skips by MIME. Then the size and emptiness refusals, and the opaque store for a real non-image.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentStoreIngestTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val blobs = mockk<BlobRepository>(relaxed = true)
    private val screening = mockk<ImageScreeningService>(relaxed = true)
    private val identities = mockk<AttachmentIdentities>(relaxed = true)
    private val store = AttachmentStore(context, blobs, screening, identities)
    private val stored = HashMap<String, Pair<String, ByteArray>>()
    private val dir = File(context.cacheDir, "ingest-${System.nanoTime()}").apply { mkdirs() }

    init {
        coEvery { blobs.insert(any(), any(), any()) } answers { stored[firstArg()] = secondArg<String>() to thirdArg() }
        // Nothing is held under an identity unless a test says so: a relaxed mock would hand back a mock identity.
        coEvery { identities.find(any()) } returns null
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** A 2000×1000 landscape JPEG that says it was shot rotated 90°, somewhere specific, on a named camera. */
    private fun geotaggedJpeg(): ByteArray {
        val file = File(dir, "photo.jpg")
        val bitmap = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 120, 200)) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.absolutePath).apply {
            setLatLong(37.4219983, -122.084)
            setAttribute(ExifInterface.TAG_MAKE, "Google")
            setAttribute(ExifInterface.TAG_MODEL, "Pixel 8")
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        return file.readBytes().also {
            // The fixture itself must carry what the pipeline is meant to drop, or the test proves nothing.
            assertTrue(ExifInterface(ByteArrayInputStream(it)).latLong != null)
        }
    }

    private fun fileUri(
        name: String,
        bytes: ByteArray,
    ): Uri = Uri.fromFile(File(dir, name).apply { writeBytes(bytes) })

    private fun assertNoMetadata(bytes: ByteArray) {
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        assertNull("GPS survived the re-encode", exif.latLong)
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(exif.getAttribute(ExifInterface.TAG_MODEL))
    }

    private fun success(result: IngestResult): IngestResult.Success {
        assertTrue("expected a stored attachment, got $result", result is IngestResult.Success)
        return result as IngestResult.Success
    }

    @Test
    fun aCapturedPhotoIsStoredUprightBoundedAndWithoutItsMetadata() =
        runTest {
            val result = success(store.ingest(geotaggedJpeg(), "image/jpeg"))

            val (mime, bytes) = stored.getValue(result.ingested.hash)
            assertEquals("image/jpeg", mime)
            assertEquals(sha256Hex(bytes), result.ingested.hash)
            assertNoMetadata(bytes)
            // Decoded with no orientation left to apply, so what comes back is the stored pixels' own shape.
            val stored = checkNotNull(decodeOrientedBounded(bytes, MAX_DIMENSION * 2))
            assertTrue("rotated upright: ${stored.width}x${stored.height}", stored.height > stored.width)
            assertTrue(maxOf(stored.width, stored.height) <= MAX_DIMENSION)
            assertFalse(result.flagged)
        }

    @Test
    fun aJpegOfferedAsAnOpaqueFileTakesTheImageDoor() =
        runTest {
            val result = success(store.ingestFile(fileUri("blob.bin", geotaggedJpeg())))

            val (mime, bytes) = stored.getValue(result.ingested.hash)
            assertEquals("image/jpeg", mime)
            assertNoMetadata(bytes)
            coVerify(exactly = 1) { screening.isImageExplicit(bytes) }
        }

    @Test
    fun theScreenersVerdictRidesTheResult() =
        runTest {
            coEvery { screening.isImageExplicit(any()) } returns true
            assertTrue(success(store.ingest(geotaggedJpeg(), "image/jpeg")).flagged)
        }

    @Test
    fun aTransparentImageKeepsItsAlphaAsWebp() =
        runTest {
            val png =
                ByteArrayOutputStream().use { out ->
                    Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }.compress(
                        Bitmap.CompressFormat.PNG,
                        100,
                        out,
                    )
                    out.toByteArray()
                }
            val result = success(store.ingest(png, "image/png"))
            assertEquals("image/webp", result.ingested.mime)
        }

    @Test
    fun bytesThatDoNotDecodeAreUnreadable() =
        runTest {
            val result = store.ingest(byteArrayOf(1, 2, 3, 4, 5), "image/jpeg")
            assertEquals(IngestResult.Failed(IngestResult.Reason.Unreadable), result)
            assertTrue(stored.isEmpty())
        }

    @Test
    fun aNonImageFileIsStoredAsItIsAndNeverScreened() =
        runTest {
            val pdf = "%PDF-1.4\n%âãÏÓ\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF\n".toByteArray()
            val result = success(store.ingestFile(fileUri("report.pdf", pdf)))

            val (mime, bytes) = stored.getValue(result.ingested.hash)
            assertEquals(DEFAULT_MIME, mime)
            assertArrayEquals(pdf, bytes)
            assertEquals(pdf.size, result.ingested.sizeBytes)
            assertFalse(result.flagged)
            coVerify(exactly = 0) { screening.isImageExplicit(any()) }
        }

    @Test
    fun aFilePastTheCapIsRefusedOnTheStreamItself() =
        runTest {
            // A file:// Uri advertises no size, so only the bounded read can catch this one.
            val result = store.ingestFile(fileUri("big.bin", ByteArray(MAX_BYTES + 1)))
            assertEquals(IngestResult.Failed(IngestResult.Reason.TooLarge), result)
            assertTrue(stored.isEmpty())
        }

    @Test
    fun anEmptyFileIsUnreadable() =
        runTest {
            assertEquals(IngestResult.Failed(IngestResult.Reason.Unreadable), store.ingestFile(fileUri("empty.txt", ByteArray(0))))
        }

    @Test
    fun anEmptyVoiceNoteIsRefused() =
        runTest {
            assertEquals(IngestResult.Failed(IngestResult.Reason.Unreadable), store.ingestVoice(ByteArray(0)))
            assertTrue(stored.isEmpty())
        }

    // --- A pick this phone already holds (ADR 2026-10.mptm) ---

    /** An animated WebP as the ingest stores a GIF: the VP8X chunk with its animation flag. Nothing decodes it. */
    private val animatedWebp =
        "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBPVP8X".toByteArray() + byteArrayOf(10, 0, 0, 0, 0x02, 0, 0, 0)

    private fun held(
        plainHash: String,
        mime: String = "image/jpeg",
        key: String? = "a2V5",
        animated: Boolean = false,
    ) = AttachmentIdentities.Held(hash = "c".repeat(64), key = key, mime = mime, animated = animated, plainHash = plainHash)

    @Test
    fun aPictureThePhoneHoldsGoesOutAsItsIdentityWithItsBytesUnchanged() =
        runTest {
            val photo = geotaggedJpeg()
            val identity = held(plainHash = sha256Hex(photo))
            coEvery { identities.find(sha256Hex(photo)) } returns identity

            val result = success(store.ingest(fileUri("saved.jpg", photo)))

            assertEquals(identity, result.ingested.reuse)
            assertEquals(sha256Hex(photo), result.ingested.hash)
            assertNull("the bytes are the plaintext: nothing was re-encoded", result.ingested.sourceHash)
            // Byte for byte what was picked: no second JPEG generation, and no new identity for the mesh to carry.
            assertArrayEquals(photo, stored.getValue(sha256Hex(photo)).second)
            coVerify(exactly = 0) { identities.plaintextOf(any()) }
            // Screened as a fresh pick would be, so the room's block and a DM's confirmation are unchanged.
            coVerify(exactly = 1) { screening.isImageExplicit(photo) }
        }

    @Test
    fun aForwardedGifStaysAnimatedBecauseItIsNeverDecoded() =
        runTest {
            val identity = held(plainHash = sha256Hex(animatedWebp), mime = "image/webp", key = null, animated = true)
            coEvery { identities.find(sha256Hex(animatedWebp)) } returns identity

            val result = success(store.ingest(fileUri("saved.webp", animatedWebp)))

            assertTrue(result.ingested.animated)
            assertEquals("image/webp", result.ingested.mime)
            assertArrayEquals(animatedWebp, stored.getValue(result.ingested.hash).second)
        }

    @Test
    fun theOriginalOfAPhotoWeSentFindsTheIdentityItWentOutUnder() =
        runTest {
            val original = geotaggedJpeg()
            val sent = "the re-encoded plaintext".toByteArray()
            val identity = held(plainHash = sha256Hex(sent))
            coEvery { identities.find(sha256Hex(original)) } returns identity
            coEvery { identities.plaintextOf(identity) } returns sent

            val result = success(store.ingest(fileUri("original.jpg", original)))

            assertEquals(sha256Hex(sent), result.ingested.hash)
            assertEquals(sha256Hex(original), result.ingested.sourceHash)
            assertArrayEquals(sent, stored.getValue(sha256Hex(sent)).second)
        }

    @Test
    fun anIdentityWhoseBlobWillNotOpenFallsBackToAFreshIngest() =
        runTest {
            val original = geotaggedJpeg()
            coEvery { identities.find(sha256Hex(original)) } returns held(plainHash = "f".repeat(64))
            coEvery { identities.plaintextOf(any()) } returns null

            val result = success(store.ingest(fileUri("original.jpg", original)))

            assertNull(result.ingested.reuse)
            assertNoMetadata(stored.getValue(result.ingested.hash).second)
        }

    @Test
    fun aFilePassedAlongKeepsItsIdentityAndThePicksOwnNameAndSize() =
        runTest {
            val pdf = "%PDF-1.4\n1 0 obj << >> endobj\n%%EOF\n".toByteArray()
            val identity = held(plainHash = sha256Hex(pdf), mime = "application/pdf")
            coEvery { identities.find(sha256Hex(pdf)) } returns identity

            val result = success(store.ingestFile(fileUri("evacuation_procedure.pdf", pdf)))

            assertEquals(identity, result.ingested.reuse)
            assertEquals("application/pdf", result.ingested.mime)
            // The name is the pick's own, as on a fresh ingest (a file:// Uri offers none under Robolectric).
            assertEquals(success(store.ingestFile(fileUri("other.pdf", "%PDF-other".toByteArray()))).ingested.name, result.ingested.name)
            assertEquals(pdf.size, result.ingested.sizeBytes)
            assertFalse(result.flagged)
            coVerify(exactly = 0) { screening.isImageExplicit(any()) }
        }

    @Test
    fun aPictureFoundThroughTheFileDoorCarriesNoNameAsAnyImageFromThere() =
        runTest {
            val photo = geotaggedJpeg()
            coEvery { identities.find(sha256Hex(photo)) } returns held(plainHash = sha256Hex(photo))

            val result = success(store.ingestFile(fileUri("IMG_0001.jpg", photo)))

            assertNull(result.ingested.name)
            assertEquals(0, result.ingested.sizeBytes)
        }

    @Test
    fun aFreshPickRemembersTheBytesItCameFromWhenTheIngestChangedThem() =
        runTest {
            val photo = geotaggedJpeg()

            val result = success(store.ingest(fileUri("photo.jpg", photo)))

            assertNull(result.ingested.reuse)
            assertEquals(sha256Hex(photo), result.ingested.sourceHash)
            assertTrue(result.ingested.hash != sha256Hex(photo))
        }

    /** [AttachmentStore]'s private bounds, restated: a change there should be a deliberate change here. */
    private companion object {
        const val MAX_DIMENSION = 1280
        const val MAX_BYTES = 8 * 1024 * 1024
        const val DEFAULT_MIME = "application/octet-stream"
    }
}
