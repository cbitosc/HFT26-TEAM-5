package app.getknit.knit.data.relay

import app.getknit.knit.BuildConfig
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.spool.SpoolStatus
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** [RelayStatusRepository.turnAvailable]: what the Calls screen's "Relay all calls" is offered on (C-3.7-3). */
class RelayStatusRepositoryTest {
    private fun spool(
        url: String,
        connected: Boolean,
        turnForUs: Boolean,
    ) = SpoolStatus(url = url, connected = connected, powBits = 0, lastError = null, scopes = emptyList(), turnForUs = turnForUs)

    private suspend fun read(vararg spools: SpoolStatus): Boolean {
        val mesh = mockk<MeshController> { every { spoolStatus() } returns spools.toList() }
        return RelayStatusRepository(mockk(relaxed = true), mesh).turnAvailable.first()
    }

    /** One connected relay that would issue this phone TURN is enough; a disconnected one, or one that would not, is not. */
    @Test
    fun oneConnectedRelayIssuingTurnIsEnough() =
        runTest {
            assumeTrue(BuildConfig.INTERNET_PLANE)
            val down = spool("ws://down.test/spool/v1", connected = false, turnForUs = true)
            val gated = spool("ws://gated.test/spool/v1", connected = true, turnForUs = false)
            val open = spool("ws://open.test/spool/v1", connected = true, turnForUs = true)
            assertFalse(read())
            assertFalse(read(down, gated))
            assertTrue(read(down, gated, open))
        }
}
