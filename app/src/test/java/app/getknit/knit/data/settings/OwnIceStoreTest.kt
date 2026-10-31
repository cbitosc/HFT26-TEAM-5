package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.getknit.knit.call.OwnIceServers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** §3.14: the own servers round-trip as typed, and a field that fails its rule reads as that kind off. */
class OwnIceStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun ownServersDefaultOffAndRoundTrip() =
        runTest {
            val file = File(tmp.root, "settings.preferences_pb")
            val store = OwnIceStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
            assertEquals(OwnIceServers(), store.ownIce.first())

            store.setStunUrls(listOf("stun:a.example.org", "stun:b.example.org:3478"))
            store.setTurn(url = "turn:t.example.org:3478?transport=udp", user = "ada", pass = "pw")
            store.setStunOn(true)
            store.setTurnOn(true)
            val ice = store.ownIce.first().effective()
            assertEquals(listOf("stun:a.example.org", "stun:b.example.org:3478"), ice.stun)
            assertEquals(listOf("turn:t.example.org:3478?transport=udp"), ice.turn?.urls)
            assertEquals("ada", ice.turn?.user)
            assertEquals("pw", ice.turn?.pass)

            // Switched off, the fields are kept; a call goes back to the relays.
            store.setTurnOn(false)
            assertEquals("pw", store.ownIce.first().turnPass)
            assertNull(
                store.ownIce
                    .first()
                    .effective()
                    .turn,
            )

            // A stored URL libwebrtc would refuse never reaches a call.
            store.setStunUrls(listOf("stun:user@a.example.org"))
            assertTrue(
                store.ownIce
                    .first()
                    .effective()
                    .stun
                    .isEmpty(),
            )
        }
}
