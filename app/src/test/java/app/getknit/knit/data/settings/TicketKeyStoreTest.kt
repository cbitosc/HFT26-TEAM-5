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

    // ── the free half (docs/VOICE_CALLS.md C-7.6-33 to C-7.6-38) ─────────────────────────────────────────

    private val free = HeldKey(FREE_ISSUER, OTHER_KEY, free = true)

    @Test
    fun aFreeKeyHasTheHostsOneSlotAndACustomerKeyReplacesIt() =
        runTest {
            val (keys, _) = stores()
            keys.putKey(HOST, free)
            assertEquals(free, keys.keys.first()[HOST])
            keys.putKeyState(HOST, free, KeyState(KeyAnswer.OK))
            // A customer key from an invite takes the slot, with a fresh state.
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            assertEquals(HeldKey(ISSUER, KEY), keys.keys.first()[HOST])
            assertNull(keys.keyStates.first()[HOST])
            // And a free account made after the customer's ended takes it back.
            keys.putKey(HOST, free)
            assertEquals(free, keys.keys.first()[HOST])
        }

    @Test
    fun aFreeKeyNeverRidesABackupAndACustomerKeyStillDoes() {
        // C-7.6-37: every free-half key is backup-transient; the customer key's slot is not.
        assertTrue(SettingsKeys.TRANSIENT_PREFIXES.any { SettingsKeys.spoolFreeKeyKey(HOST).name.startsWith(it) })
        assertTrue(SettingsKeys.TRANSIENT_PREFIXES.any { SettingsKeys.spoolFreeStateKey(HOST).name.startsWith(it) })
        assertTrue(SettingsKeys.TRANSIENT_PREFIXES.any { SettingsKeys.spoolFreeConsentKey(HOST).name.startsWith(it) })
        assertFalse(SettingsKeys.TRANSIENT_PREFIXES.any { SettingsKeys.spoolKeyKey(HOST).name.startsWith(it) })
    }

    @Test
    fun decliningForgetsAFreeKeyAndItsTicketButNeverACustomersKey() =
        runTest {
            val (keys, settings) = stores()
            val tickets = SpoolTickets(settings)
            keys.putKey(HOST, free)
            tickets.import("wss://$HOST/spool/v1", SpoolTicketsTest.TEXT)
            keys.setFreeConsent(HOST, false)
            assertNull(keys.keys.first()[HOST])
            assertNull(tickets.view("wss://$HOST/spool/v1"))
            assertEquals(false, keys.freeConsent.first()[HOST])
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            keys.setFreeConsent(HOST, false)
            assertEquals(HeldKey(ISSUER, KEY), keys.keys.first()[HOST])
            keys.setFreeConsent(HOST, null)
            assertNull(keys.freeConsent.first()[HOST])
        }

    @Test
    fun aForgottenFreeKeyLeavesTheNextAccountsFloorAndOnlyIfItIsStillHeld() =
        runTest {
            val (keys, _) = stores()
            keys.putKey(HOST, free)
            keys.forgetFreeKey(HOST, HeldKey(FREE_ISSUER, KEY, free = true), notBefore = 5)
            assertEquals(free, keys.keys.first()[HOST])
            keys.forgetFreeKey(HOST, free, notBefore = 9)
            assertNull(keys.keys.first()[HOST])
            assertEquals(9L, keys.freeStates.first()[HOST]?.notBefore)
        }

    @Test
    fun theSeededIssuersAndADebugOneAreRead() =
        runTest {
            val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "free.preferences_pb") }
            val keys = TicketKeyStore(dataStore, mapOf(HOST to FREE_ISSUER))
            assertEquals(mapOf(HOST to FREE_ISSUER), keys.freeIssuers.first())
            keys.setFreeIssuer("other.example", "http://10.0.2.2:3000")
            keys.setFreeIssuer(HOST, "https://bench.example")
            assertEquals(mapOf(HOST to "https://bench.example", "other.example" to "http://10.0.2.2:3000"), keys.freeIssuers.first())
            keys.setFreeIssuer(HOST, null)
            assertEquals(FREE_ISSUER, keys.freeIssuers.first()[HOST])
        }

    private companion object {
        // The vector ticket's audience, so the forget test can hold a real ticket for the host.
        const val HOST = "lab.example"
        const val ISSUER = "https://anywhere.example.org"
        const val KEY = "kak1_FBsiKTA3PkVMU1phaG92fYSLkpmgp661vMPK0djf5u0"
        const val OTHER_KEY = "kak1_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val FREE_ISSUER = "https://free.example.org"
    }
}
