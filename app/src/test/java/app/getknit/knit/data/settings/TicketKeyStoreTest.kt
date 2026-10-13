package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.getknit.knit.mesh.spool.HeldKey
import app.getknit.knit.mesh.spool.KeyAnswer
import app.getknit.knit.mesh.spool.KeyState
import app.getknit.knit.mesh.spool.SpoolTickets
import app.getknit.knit.mesh.spool.SpoolTicketsTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The customer-key store over a real Preferences DataStore (the [SettingsStoreLoraTest] rig, ADR 2026-10.3sk7): a
 * different key resets the state and the same one does not, an answer for a replaced key never lands, forgetting a
 * host takes its key, state and ticket in one edit and nothing else, and nothing it renders names the key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TicketKeyStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.stores(): Pair<TicketKeyStore, SettingsStore> {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "settings.preferences_pb") }
        return TicketKeyStore(dataStore) to SettingsStore(dataStore)
    }

    @Test
    fun aDifferentKeyResetsTheStateAndTheSameOneDoesNot() =
        runTest {
            val (keys, _) = stores()
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            keys.putKeyState(HOST, HeldKey(ISSUER, KEY), KeyState(KeyAnswer.UNKNOWN_KEY, endsAt = 7, notBefore = 9, failures = 2))
            assertEquals(KeyState(KeyAnswer.UNKNOWN_KEY, 7, 9, 2), keys.keyStates.first()[HOST])
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            assertEquals(KeyAnswer.UNKNOWN_KEY, keys.keyStates.first()[HOST]?.answer)
            keys.putKey(HOST, HeldKey(ISSUER, OTHER_KEY))
            assertEquals(HeldKey(ISSUER, OTHER_KEY), keys.keys.first()[HOST])
            assertNull(keys.keyStates.first()[HOST])
        }

    @Test
    fun anAnswerForAReplacedKeyNeverLands() =
        runTest {
            val (keys, _) = stores()
            keys.putKey(HOST, HeldKey(ISSUER, OTHER_KEY))
            keys.putKeyState(HOST, HeldKey(ISSUER, KEY), KeyState(KeyAnswer.OK))
            assertNull(keys.keyStates.first()[HOST])
        }

    @Test
    fun forgettingAHostTakesItsKeyStateAndTicketAndNothingElse() =
        runTest {
            val (keys, settings) = stores()
            val tickets = SpoolTickets(settings) { 1_800_000_100_000L }
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            keys.putKey("other.example", HeldKey(ISSUER, OTHER_KEY))
            keys.putKeyState(HOST, HeldKey(ISSUER, KEY), KeyState(KeyAnswer.OK))
            tickets.import("wss://lab.example/spool/v1", SpoolTicketsTest.TEXT)
            settings.addSpoolUrl("wss://lab.example/spool/v1")
            assertTrue(tickets.view("wss://lab.example/spool/v1") != null)
            keys.forgetHost(HOST)
            assertNull(keys.keys.first()[HOST])
            assertNull(keys.keyStates.first()[HOST])
            assertNull(tickets.view("wss://lab.example/spool/v1"))
            assertEquals(HeldKey(ISSUER, OTHER_KEY), keys.keys.first()["other.example"])
            assertEquals(setOf("wss://lab.example/spool/v1"), settings.spoolUrls.first())
        }

    @Test
    fun nothingItRendersNamesTheKey() =
        runTest {
            val (keys, _) = stores()
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            val secret = KEY.removePrefix("kak1_").take(12)
            assertFalse(keys.toString().contains(secret))
            assertFalse(
                keys.keys
                    .first()
                    .toString()
                    .contains(secret),
            )
            assertTrue(
                keys.keys
                    .first()
                    .toString()
                    .contains(ISSUER),
            )
        }

    private companion object {
        // The vector ticket's audience, so the forget test can hold a real ticket for the host.
        const val HOST = "lab.example"
        const val ISSUER = "https://anywhere.example.org"
        const val KEY = "kak1_FBsiKTA3PkVMU1phaG92fYSLkpmgp661vMPK0djf5u0"
        const val OTHER_KEY = "kak1_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
