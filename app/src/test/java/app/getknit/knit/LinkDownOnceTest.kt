package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.LinkDownOnce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * [LinkDownOnce]: what `BluetoothMeshTransport.LinkEvents.onLinkDown` asks, so the read loop's and the write loop's
 * reports of one link's end tear it down and count as a flap once (#205).
 */
class LinkDownOnceTest {
    @Test
    fun twoReportsOfOneLinkTearDownOnceAndFlapOnce() {
        val down = LinkDownOnce()
        val teardowns = AtomicInteger()
        val replacedReads = AtomicInteger()
        val teardown = {
            teardowns.incrementAndGet()
            false // the #205 case: nothing left to release
        }
        val replaced = {
            replacedReads.incrementAndGet()
            false // and no replacement yet
        }

        assertTrue("the reader's report", down.flapped(teardown, replaced))
        assertFalse("the writer's report", down.flapped(teardown, replaced))
        assertEquals(1, teardowns.get())
        assertEquals(1, replacedReads.get())
    }

    @Test
    fun anUnreleasedLinkWithAReplacementDoesNotFlap() {
        assertFalse(LinkDownOnce().flapped(teardown = { false }, replaced = { true }))
    }

    @Test
    fun aReleasedLinkFlaps() {
        assertTrue(LinkDownOnce().flapped(teardown = { true }, replaced = { true }))
    }

    @Test
    fun replacedIsReadAfterTheTeardown() {
        val calls = mutableListOf<String>()
        LinkDownOnce().flapped(
            teardown = {
                calls += "teardown"
                false
            },
            replaced = {
                calls += "replaced"
                false
            },
        )
        assertEquals(listOf("teardown", "replaced"), calls)
    }

    @Test
    fun anOwnCloseIsAskedOnlyWhenNothingWasReleasedAndDoesNotFlap() {
        val asked = AtomicInteger()
        val ownClose = {
            asked.incrementAndGet()
            true
        }
        assertFalse(LinkDownOnce().flapped(teardown = { false }, replaced = { false }, ownClose = ownClose))
        assertEquals(1, asked.get())
        assertTrue(LinkDownOnce().flapped(teardown = { true }, replaced = { false }, ownClose = ownClose))
        assertEquals("not asked after a release", 1, asked.get())
    }

    @Test
    fun eachInstanceLetsItsOwnFirstReportThrough() {
        val first = LinkDownOnce()
        assertTrue(first.flapped(teardown = { true }, replaced = { false }))
        assertTrue(LinkDownOnce().flapped(teardown = { true }, replaced = { false }))
    }

    @Test
    fun aReportArrivingDuringTheFirstTeardownCallsNothing() {
        val down = LinkDownOnce()
        val inTeardown = CountDownLatch(1)
        val secondDone = CountDownLatch(1)
        val teardowns = AtomicInteger()
        val waited = AtomicBoolean(false)
        var firstResult: Boolean? = null
        val reader =
            thread(name = "read-loop") {
                firstResult =
                    down.flapped(
                        teardown = {
                            teardowns.incrementAndGet()
                            inTeardown.countDown()
                            waited.set(secondDone.await(5, SECONDS))
                            true
                        },
                        replaced = { false },
                    )
            }
        assertTrue("the first report never reached its teardown", inTeardown.await(5, SECONDS))
        val secondTeardowns = AtomicInteger()
        val second =
            down.flapped(
                teardown = {
                    secondTeardowns.incrementAndGet()
                    true
                },
                replaced = { false },
            )
        secondDone.countDown()
        reader.join(SECONDS.toMillis(5))

        assertFalse("the reader is still running", reader.isAlive)
        assertTrue("the first teardown never saw the second report return", waited.get())
        assertFalse("the second report", second)
        assertEquals(0, secondTeardowns.get())
        assertEquals(1, teardowns.get())
        assertEquals(true, firstResult)
    }
}
