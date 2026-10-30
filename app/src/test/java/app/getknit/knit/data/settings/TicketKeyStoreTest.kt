package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.getknit.knit.data.backup.SettingsSnapshot
import app.getknit.knit.mesh.spool.DeviceKey
import app.getknit.knit.mesh.spool.DeviceKeys
import app.getknit.knit.mesh.spool.FakeDeviceKeystore
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
    fun aCustomerKeyReplacingAFreeOneForgetsTheFreeTicketAndAnotherCustomerKeyKeepsItsOwn() =
        runTest {
            val (keys, settings) = stores()
            val tickets = SpoolTickets(settings)
            val url = "wss://$HOST/spool/v1"
            keys.putKey(HOST, free)
            tickets.import(url, SpoolTicketsTest.TEXT)
            // Its `am` must not size the customer's photos while the customer's own ticket is fetched.
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            assertNull(tickets.view(url))
            tickets.import(url, SpoolTicketsTest.TEXT)
            keys.putKey(HOST, HeldKey(ISSUER, OTHER_KEY))
            assertTrue(tickets.view(url) != null)
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

    // ── device keys (docs/VOICE_CALLS.md C-7.6-39, C-7.6-46, ADR 2026-10.wk3m) ─────────────────────────────────────

    private fun TestScope.signing(keystore: FakeDeviceKeystore): TicketKeyStore {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "settings.preferences_pb") }
        return TicketKeyStore(dataStore, keystore = keystore)
    }

    private fun FakeDeviceKeystore.slots(): Set<String?> = pairs.keys.map { DeviceKeys.slotOfAlias(it) }.toSet()

    @Test
    fun aCustomerKeyGetsItsPairWhenItArrives() =
        runTest {
            val keystore = FakeDeviceKeystore()
            val keys = signing(keystore)
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            assertEquals(setOf(DeviceKeys.slotOf(HOST, KEY)), keystore.slots())
            // The same key again makes nothing new.
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            assertEquals(1, keystore.generated)
        }

    @Test
    fun aReplacedKeysPairGoesAndTheNewOneGetsItsOwn() =
        runTest {
            val keystore = FakeDeviceKeystore()
            val keys = signing(keystore)
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            keys.putKey(HOST, HeldKey(ISSUER, OTHER_KEY))
            assertEquals(listOf(DeviceKeys.slotOf(HOST, KEY)), keystore.deleted.map { DeviceKeys.slotOfAlias(it) })
            assertEquals(setOf(DeviceKeys.slotOf(HOST, OTHER_KEY)), keystore.slots())
        }

    @Test
    fun removingTheRelayDeletesItsPairAndNoOther() =
        runTest {
            val keystore = FakeDeviceKeystore()
            val keys = signing(keystore)
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            keys.putKey("other.example", HeldKey(ISSUER, OTHER_KEY))
            keys.forgetHost(HOST)
            assertEquals(listOf(DeviceKeys.slotOf(HOST, KEY)), keystore.deleted.map { DeviceKeys.slotOfAlias(it) })
            assertEquals(setOf(DeviceKeys.slotOf("other.example", OTHER_KEY)), keystore.slots())
        }

    @Test
    fun aFreeKeyNeverGetsAPairAndDisplacesTheCustomerKeys() =
        runTest {
            val keystore = FakeDeviceKeystore()
            val keys = signing(keystore)
            keys.putKey(HOST, HeldKey(FREE_ISSUER, OTHER_KEY, free = true))
            assertEquals(0, keystore.generated)
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            // A free key made after the account ended takes the slot, and the customer key's pair goes with it.
            keys.putKey(HOST, HeldKey(FREE_ISSUER, OTHER_KEY, free = true))
            assertEquals(1, keystore.generated)
            assertTrue(keystore.pairs.isEmpty())
        }

    @Test
    fun anAcceptedPairIsRememberedAndNeverRidesABackup() =
        runTest {
            val keys = signing(FakeDeviceKeystore())
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            val pair = keys.devices!!.forKey(HOST, KEY) as DeviceKey.Ready
            keys.devices.accepted(HOST, KEY, pair.id)
            assertEquals(mapOf(DeviceKeys.slotOf(HOST, KEY) to pair.id), keys.accepted())
            val parked =
                KeyState(
                    KeyAnswer.BOUND_ELSEWHERE,
                    endsAt = 7,
                    movableAt = 1_800_000_000_000L,
                    parkedUnsigned = true,
                    parkedBy = setOf("a"),
                )
            keys.putKeyState(HOST, HeldKey(ISSUER, KEY), parked)
            assertEquals(parked, keys.keyStates.first()[HOST])
            assertTrue(SettingsSnapshot.isTransient(SettingsKeys.spoolKeyStateKey(HOST).name))
            assertTrue(SettingsSnapshot.isTransient(SettingsKeys.spoolDeviceKey(DeviceKeys.slotOf(HOST, KEY)).name))
            assertFalse(SettingsSnapshot.isTransient(SettingsKeys.spoolKeyKey(HOST).name))
            // Forgetting the relay forgets which pair was accepted.
            keys.forgetHost(HOST)
            assertTrue(keys.accepted().isEmpty())
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
