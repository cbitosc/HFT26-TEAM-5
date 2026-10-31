package app.getknit.knit.mesh.spool

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The §9.5 rounds' holder queries, batched per peer (ADR 2026-10.vy27). The case that matters is the one a stop makes:
 * the flush runs on the mesh session, and one cancelled mid-window must not switch the asking off for good.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HolderAskBatchTest {
    private val sent = mutableListOf<Pair<String, List<String>>>()

    private fun TestScope.session(): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())

    private fun TestScope.flushed() {
        advanceTimeBy(HolderAskBatch.WINDOW_MS + 1)
        runCurrent()
    }

    @Test
    fun `a window's asks go out as one batch per peer`() =
        runTest {
            val batch = HolderAskBatch(scope = { backgroundScope }, send = { peer, hashes -> sent += peer to hashes })
            batch.ask("h1", "bob")
            batch.ask("h2", "carol")
            batch.ask("h3", "bob")
            batch.ask("h1", "bob")
            runCurrent()
            assertEquals("nothing before the window closes", emptyList<Pair<String, List<String>>>(), sent)

            flushed()
            assertEquals(listOf("bob" to listOf("h1", "h3"), "carol" to listOf("h2")), sent)

            sent.clear()
            batch.ask("h4", "bob")
            flushed()
            assertEquals("the next ask opens the next window", listOf("bob" to listOf("h4")), sent)
        }

    @Test
    fun `a flush a stop cancels leaves its asks for the next session`() =
        runTest {
            var current: CoroutineScope? = session()
            val batch = HolderAskBatch(scope = { current }, send = { peer, hashes -> sent += peer to hashes })
            batch.ask("h1", "bob")
            runCurrent()
            current?.cancel() // the mesh stops inside the window
            current = backgroundScope // and starts again
            runCurrent()

            batch.ask("h2", "bob")
            flushed()
            assertEquals("the asking is not switched off", listOf("bob" to listOf("h1", "h2")), sent)
        }

    @Test
    fun `with no session an ask is dropped, and the next session asks afresh`() =
        runTest {
            var current: CoroutineScope? = null
            val batch = HolderAskBatch(scope = { current }, send = { peer, hashes -> sent += peer to hashes })
            batch.ask("h1", "bob")

            current = backgroundScope
            batch.ask("h2", "bob")
            flushed()
            assertEquals(listOf("bob" to listOf("h2")), sent)
        }
}
