package app.getknit.knit.mesh.bluetooth

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * When a Bluetooth link's socket last gave up a byte, and since when its writer has sat in one `write()` (#203): what
 * [LinkSilence] judges a link by. Pure pass-throughs over the raw socket streams, beneath any buffering, so the HELLO
 * read through the link's buffered input is stamped as it is read, and every write the link makes (a frame, a digest,
 * a file's header, chunks and end, a keepalive) passes the output stamp whatever wrote it.
 *
 * Bytes, never records, and Bluetooth's alone: `FramedLink.lastActivityAt` is the Wi-Fi Aware supervisor's, and a record
 * stamp would miss a long chunk still coming in. Stamped on [now] (`elapsedRealtime`), the clock [LinkSilence] reads.
 */
internal class SocketStamps(
    private val now: () -> Long,
) {
    /** When the input last returned a byte: the stamps' birth until the first (the HELLO is read through it). */
    @Volatile
    var lastByteAt: Long = now()
        private set

    /** When the write the writer sits in began, or null between writes. */
    @Volatile
    var writeBlockedSince: Long? = null
        private set

    /** [raw], stamping [lastByteAt] on each read that returns a byte. */
    fun input(raw: InputStream): InputStream =
        object : FilterInputStream(raw) {
            override fun read(): Int = super.read().also { if (it >= 0) lastByteAt = now() }

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int = super.read(b, off, len).also { if (it > 0) lastByteAt = now() }
        }

    /** [raw], with [writeBlockedSince] set for as long as each call into it lasts. */
    fun output(raw: OutputStream): OutputStream =
        object : FilterOutputStream(raw) {
            override fun write(b: Int) = blocking { out.write(b) }

            // FilterOutputStream's own writes the bytes one at a time: straight through, as one call.
            override fun write(
                b: ByteArray,
                off: Int,
                len: Int,
            ) = blocking { out.write(b, off, len) }

            override fun flush() = blocking { out.flush() }

            override fun close() = out.close()
        }

    private inline fun blocking(write: () -> Unit) {
        writeBlockedSince = now()
        try {
            write()
        } finally {
            writeBlockedSince = null
        }
    }
}
