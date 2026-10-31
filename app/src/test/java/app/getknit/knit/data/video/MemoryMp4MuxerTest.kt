package app.getknit.knit.data.video

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4LocationData
import androidx.media3.container.Mp4TimestampData
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.Muxer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer

/**
 * The muxer writes the MP4 into memory with the source's metadata dropped: "no GPS leaves the phone" holds for a video
 * the way `ImageStrip` holds it for a picture. Transformer forwards every entry the source carried to
 * `addMetadataEntry`; none of them may reach the file.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class MemoryMp4MuxerTest {
    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    private fun ByteArray.indexOf(needle: ByteArray): Int =
        (0..size - needle.size).firstOrNull { i -> needle.indices.all { this[i + it] == needle[it] } } ?: -1

    private fun muxed(stock: Boolean = false): ByteArray {
        val factory = MemoryMp4Muxer.Factory(maxBytes = 1 shl 20)
        val stockOutput = MemoryMuxerOutput(maxBytes = 1 shl 20)
        val muxer: Muxer = if (stock) Mp4Muxer.Builder(stockOutput).build() else factory.create("/dev/null")
        val audio =
            muxer.addTrack(
                Format
                    .Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_AAC)
                    .setSampleRate(48_000)
                    .setChannelCount(2)
                    .setInitializationData(listOf(byteArrayOf(0x11, 0x90.toByte())))
                    .build(),
            )
        muxer.addMetadataEntry(Mp4LocationData(37.4219f, -122.084f))
        muxer.addMetadataEntry(Mp4TimestampData(3_000_000_000L, 3_000_000_000L))
        repeat(3) { i ->
            muxer.writeSampleData(
                audio,
                ByteBuffer.wrap(ByteArray(64) { 1 }),
                BufferInfo(i * 21_333L, 64, C.BUFFER_FLAG_KEY_FRAME),
            )
        }
        muxer.close()
        return if (stock) stockOutput.toByteArray() else checkNotNull(factory.bytes())
    }

    @Test
    fun theSourcesLocationAndTimestampNeverReachTheFile() {
        val bytes = muxed()
        assertEquals("an MP4", 4, bytes.indexOf(ascii("ftyp")))
        // The ISO location atom is ©xyz (0xA9 'x' 'y' 'z'); its coordinates as text would be "+37.4219".
        assertEquals(-1, bytes.indexOf(byteArrayOf(0xA9.toByte()) + ascii("xyz")))
        assertEquals(-1, bytes.indexOf(ascii("+37.42")))
    }

    @Test
    fun theStockMuxerWouldHaveWrittenTheLocation() {
        // The control: the needle above is the one Media3 writes, so its absence means something.
        assertTrue(muxed(stock = true).indexOf(byteArrayOf(0xA9.toByte()) + ascii("xyz")) > 0)
    }

    @Test
    fun nothingIsPaddedNorAnyBoxButTheThreeAPlayerNeeds() {
        val bytes = muxed()
        val boxes =
            generateSequence(0) { at -> (at + bytes.boxSize(at)).takeIf { it < bytes.size } }
                .map { String(bytes, it + 4, 4, Charsets.US_ASCII) }
                .toList()
        assertEquals(listOf("ftyp", "mdat", "moov"), boxes)
        assertFalse(bytes.isEmpty())
    }

    /** The size of the box at [at]: its 32-bit size, the 64-bit one after the type when that is 1, or to the end at 0. */
    private fun ByteArray.boxSize(at: Int): Int {
        val small =
            java.nio.ByteBuffer
                .wrap(this, at, 4)
                .int
        return when (small) {
            0 -> {
                size - at
            }

            1 -> {
                java.nio.ByteBuffer
                    .wrap(this, at + 8, 8)
                    .long
                    .toInt()
            }

            else -> {
                small
            }
        }
    }
}
