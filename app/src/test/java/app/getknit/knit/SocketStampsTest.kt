package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.SocketStamps
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * [SocketStamps] (#203): the byte stamps a Bluetooth link's silence is judged by. Bytes, not records, and a write that
 * sits in the stack is seen while it sits.
 */
class SocketStampsTest {
    private val clock = AtomicLong(1_000)
    private val stamps = SocketStamps(clock::get)

    @Test
    fun eachReadThatReturnsABytesStampsItAndTheEndDoesNot() {
        assertEquals("born stamped: the HELLO is read through it", 1_000L, stamps.lastByteAt)
        val input = stamps.input(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        clock.set(2_000)
        assertEquals(1, input.read())
        assertEquals(2_000L, stamps.lastByteAt)
        clock.set(3_000)
        val buf = ByteArray(8)
        assertEquals(2, input.read(buf, 0, 8))
        assertEquals(3_000L, stamps.lastByteAt)
        clock.set(4_000)
        assertEquals(-1, input.read(buf, 0, 8))
        assertEquals(-1, input.read())
        assertEquals("an end is not a byte", 3_000L, stamps.lastByteAt)
    }

    @Test
    fun aWriteIsSeenBlockedForAsLongAsItSitsAndPassesItsBytesWhole() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sink = ByteArrayOutputStream()
        val stuck =
            object : OutputStream() {
                override fun write(b: Int) = sink.write(b)

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    sink.write(b, off, len)
                }
            }
        val out = stamps.output(stuck)
        assertNull(stamps.writeBlockedSince)
        clock.set(5_000)
        val writer = thread { out.write(byteArrayOf(9, 8, 7), 0, 3) }
        assertTrue("writer entered", entered.await(5, TimeUnit.SECONDS))
        assertEquals(5_000L, stamps.writeBlockedSince)
        release.countDown()
        writer.join(5_000)
        assertNull("cleared when the write returns", stamps.writeBlockedSince)
        assertArrayEquals("one call, every byte", byteArrayOf(9, 8, 7), sink.toByteArray())
    }

    @Test
    fun aWriteThatThrowsIsNoLongerBlocked() {
        val broken =
            object : OutputStream() {
                override fun write(b: Int): Unit = throw IOException("gone")
            }
        assertThrows(IOException::class.java) { stamps.output(broken).write(1) }
        assertNull(stamps.writeBlockedSince)
    }
}
