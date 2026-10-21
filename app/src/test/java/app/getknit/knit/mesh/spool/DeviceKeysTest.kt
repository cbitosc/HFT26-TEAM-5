package app.getknit.knit.mesh.spool

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device keys' rules (docs/VOICE_CALLS.md C-7.6-39, C-7.6-45 and C-7.6-46, ADR 2026-10.wk3m), above all that no
 * Keystore failure locks the account out: nothing is ever made over a pair or deleted on a Keystore's word, a refusal
 * sends nothing, and the pair that signs is the one the issuer bound.
 */
class DeviceKeysTest {
    private var elapsed = 1_000L
    private var wall = 1_800_000_000_000L
    private var draws = 0
    private val keystore = FakeDeviceKeystore()
    private val marks = MemoryDeviceKeyMarks()
    private val held: MutableMap<String, String> = linkedMapOf(HOST to KEY)
    private var heldReadable = true

    private fun keys(heldRead: suspend () -> Map<String, String>? = { held.toMap().takeIf { heldReadable } }) =
        DeviceKeys(
            keystore,
            marks,
            heldRead,
            elapsed = { elapsed },
            io = Dispatchers.Unconfined,
            random = { draws++ },
            wallClock = { wall },
        )

    private val keys = keys()
    private val slot = DeviceKeys.slotOf(HOST, KEY)

    private fun forKey(avoid: Set<String> = emptySet()) = runBlocking { keys.forKey(HOST, KEY, avoid) }

    private fun ready(avoid: Set<String> = emptySet()) = forKey(avoid) as DeviceKey.Ready

    private fun slotAliases() = keystore.pairs.keys.filter { it.startsWith(DeviceKeys.slotPrefix(slot)) }

    // A second pair made later, as a blip would make one.
    private fun secondPair(): String {
        wall += 60_000L
        keystore.hidden += slotAliases()
        val made = ready().id
        keystore.hidden.clear()
        return made
    }

    @Test
    fun aNewKeyGetsOnePairAndKeepsIt() {
        val first = ready()
        assertEquals(1, keystore.generated)
        assertEquals(first.id, ready().id)
        assertEquals(1, keystore.generated)
        assertTrue(slotAliases().single().startsWith(DeviceKeys.ALIAS_PREFIX))
    }

    @Test
    fun theSlotNamesTheHostAndTheKeyAndShowsNeither() {
        assertNotEquals(slot, DeviceKeys.slotOf(HOST, OTHER_KEY))
        assertNotEquals(slot, DeviceKeys.slotOf("other.example.org", KEY))
        assertFalse(slot.contains(KEY) || slot.contains(HOST))
        ready()
        assertEquals(slot, DeviceKeys.slotOfAlias(keystore.pairs.keys.single()))
    }

    @Test
    fun aRefusedListingMakesNothingAndSendsNothing() {
        keystore.listRefused = true
        assertSame(DeviceKey.Refused, forKey())
        assertEquals(0, keystore.generated)
    }

    @Test
    fun aRefusedLookupOfTheOnlyPairMakesNothingAndDeletesNothing() {
        val id = ready().id
        keystore.lookAs[slotAliases().single()] = DeviceKeystore.Look.Refused
        assertSame(DeviceKey.Refused, forKey())
        assertEquals(1, keystore.generated)
        assertTrue(keystore.deleted.isEmpty())
        keystore.lookAs.clear()
        assertEquals(id, ready().id)
    }

    @Test
    fun aPairThatReadsAbsentIsNeverMadeOverAndSignsAgainOnceItReads() {
        val bound = ready().id
        val alias = slotAliases().single()
        // The legacy keystore reads a dead daemon as "no key": the pair is there, only unreadable.
        keystore.lookAs[alias] = DeviceKeystore.Look.Absent
        wall += 60_000L
        val beside = ready().id
        assertNotEquals(bound, beside)
        assertFalse(keystore.overwrote)
        assertTrue(alias in keystore.pairs)
        // It reads again: the older pair signs, as the issuer bound it.
        keystore.lookAs.clear()
        assertEquals(bound, ready().id)
    }

    @Test
    fun theOldestSignsWhateverDigitsItsTimeHas() {
        // 1_800_000_000_000 is `mywpiww0` in base 36; eleven minutes on, `mywpx25c` holds an x. The bound pair is the first.
        val bound = ready().id
        wall = 1_800_000_660_000L
        keystore.hidden += slotAliases()
        val later = ready().id
        keystore.hidden.clear()
        assertNotEquals(bound, later)
        assertEquals(bound, ready().id)
    }

    @Test
    fun aListingThatLeavesThePairOutMakesOneBesideItAndTheOldestSignsAfter() {
        // Also a restore over the same install: the Keystore kept the pair, the marks are gone, the oldest signs.
        val bound = ready().id
        val beside = secondPair()
        assertNotEquals(bound, beside)
        assertFalse(keystore.overwrote)
        assertEquals(2, slotAliases().size)
        assertEquals(bound, ready().id)
    }

    @Test
    fun theAcceptedPairSignsFirstAndTheOthersGo() =
        runBlocking {
            val old = ready().id
            val newer = secondPair()
            keys.accepted(HOST, KEY, newer)
            assertEquals(newer, ready().id)
            assertEquals(listOf(DeviceKeys.slotPrefix(slot) + old), keystore.deleted)
        }

    @Test
    fun avoidSteersPastARefusedPairWhileAnotherIsUsable() {
        val old = ready().id
        val newer = secondPair()
        assertEquals(newer, ready(avoid = setOf(old)).id)
        // Every pair refused: the preferred one, so the caller sees nothing new to try.
        assertEquals(old, ready(avoid = setOf(old, newer)).id)
    }

    @Test
    fun aMissingCertificateIsARefusalNeverADelete() {
        // keystore2 answers a busy backend's certificate read with null: the bound pair may be intact.
        val bound = ready().id
        val alias = slotAliases().single()
        keystore.lookAs[alias] = DeviceKeystore.Look.Broken
        assertSame(DeviceKey.Refused, forKey())
        assertTrue(keystore.deleted.isEmpty())
        assertEquals(1, keystore.generated)
        keystore.lookAs.clear()
        assertEquals(bound, ready().id)
    }

    @Test
    fun aHuskThatStaysCertificateLessIsSetAsideAndANewPairMadeBesideIt() {
        val husk = DeviceKeys.slotPrefix(slot) + "husk"
        keystore.lookAs[husk] = DeviceKeystore.Look.Broken
        assertSame(DeviceKey.Refused, forKey())
        elapsed += DeviceKeys.SET_ASIDE_AFTER_MS
        assertSame(DeviceKey.Refused, forKey())
        val made = ready()
        assertNotEquals("husk", made.id)
        assertTrue(keystore.deleted.isEmpty())
    }

    @Test
    fun whileThePreferredPairIsUnreadableNoOtherPairSignsInItsPlace() {
        // A stray beside the bound (oldest) pair: while the bound one cannot be read, the stray must not sign — its 409
        // would park the key, and a move signed with it would rebind the account to it.
        val bound = ready().id
        val stray = secondPair()
        keystore.lookAs[DeviceKeys.slotPrefix(slot) + bound] = DeviceKeystore.Look.Refused
        assertSame(DeviceKey.Refused, forKey())
        // Once a 409 has refused the bound pair (another phone holds the account), the stray may be tried.
        assertEquals(stray, ready(avoid = setOf(bound)).id)
        keystore.lookAs.clear()
        assertEquals(bound, ready().id)
    }

    @Test
    fun anEmptyListingForAnAcceptedKeyIsARefusalForAnHour() =
        runBlocking {
            val bound = ready().id
            keys.accepted(HOST, KEY, bound)
            keystore.hidden += slotAliases()
            assertSame(DeviceKey.Refused, forKey())
            elapsed += DeviceKeys.SET_ASIDE_AFTER_MS - 1
            assertSame(DeviceKey.Refused, forKey())
            assertEquals(1, keystore.generated)
            // An hour of it: a pair is made beside the one the listing cannot see, never over it.
            elapsed += 1
            assertTrue(forKey() is DeviceKey.Ready)
            assertEquals(2, keystore.generated)
            assertFalse(keystore.overwrote)
        }

    @Test
    fun aRefusedGenerationIsARefusalNotAnUnsignedRequest() {
        keystore.makes += DeviceKeystore.Made.Refused
        assertSame(DeviceKey.Refused, forKey())
        assertTrue(forKey() is DeviceKey.Ready)
    }

    @Test
    fun onlyAPhoneHoldingNoPairThatCannotMakeOneSendsUnsigned() {
        keystore.makes += DeviceKeystore.Made.Unsupported
        assertSame(DeviceKey.Unmade, forKey())
        assertSame(DeviceKey.Unmade, forKey())
        assertEquals(0, keystore.generated)
    }

    @Test
    fun aRefusedSignatureIsNullAndLeavesThePair() =
        runBlocking {
            val signer = ready().signer
            keystore.signs = { DeviceKeystore.Signed.Refused }
            assertNull(signer.sign(byteArrayOf(1)))
            assertTrue(keystore.deleted.isEmpty())
            assertEquals(1, keystore.generated)
        }

    @Test
    fun aPairRefusingForAnHourIsSetAsideAndANewOneMadeBesideItNeverOverIt() =
        runBlocking {
            val first = ready()
            keystore.signs = { DeviceKeystore.Signed.Refused }
            assertNull(first.signer.sign(byteArrayOf(1)))
            elapsed += DeviceKeys.SET_ASIDE_AFTER_MS - 1
            assertNull(first.signer.sign(byteArrayOf(1)))
            assertEquals(first.id, ready().id)
            elapsed += 1
            assertNull(first.signer.sign(byteArrayOf(1)))
            keystore.signs = { null }
            wall += 60_000L
            val beside = ready()
            assertNotEquals(first.id, beside.id)
            assertFalse(keystore.overwrote)
            assertTrue(keystore.deleted.isEmpty())
            // Once the set-aside ends, the old pair is tried first again.
            elapsed += DeviceKeys.SET_ASIDE_MS
            assertEquals(first.id, ready().id)
        }

    @Test
    fun aSignatureThatWorksEndsTheRunOfRefusals() =
        runBlocking {
            val first = ready()
            keystore.signs = { DeviceKeystore.Signed.Refused }
            first.signer.sign(byteArrayOf(1))
            keystore.signs = { null }
            assertNotNull(first.signer.sign(byteArrayOf(1)))
            elapsed += DeviceKeys.SET_ASIDE_AFTER_MS
            keystore.signs = { DeviceKeystore.Signed.Refused }
            first.signer.sign(byteArrayOf(1))
            assertEquals(first.id, ready().id)
        }

    @Test
    fun anInvalidatedPairIsSetAsideNotDeleted() =
        runBlocking {
            val first = ready()
            keystore.signs = { DeviceKeystore.Signed.Lost }
            assertNull(first.signer.sign(byteArrayOf(1)))
            keystore.signs = { null }
            wall += 60_000L
            assertNotEquals(first.id, ready().id)
            assertTrue(keystore.deleted.isEmpty())
        }

    @Test
    fun aKeyHoldsNoMoreThanAFewPairs() =
        runBlocking {
            repeat(DeviceKeys.MAX_PAIRS) {
                wall += 60_000L
                val pair = ready()
                keystore.lookAs[DeviceKeys.slotPrefix(slot) + pair.id] = DeviceKeystore.Look.Absent
            }
            assertSame(DeviceKey.Refused, forKey())
            assertEquals(DeviceKeys.MAX_PAIRS, keystore.generated)
        }

    @Test
    fun aMarksStoreThatThrowsCostsAGuessNeverACrash() =
        runBlocking {
            marks.broken = true
            val pair = ready()
            keys.accepted(HOST, KEY, pair.id)
            keys.retain()
            assertEquals(pair.id, ready().id)
        }

    @Test
    fun retainDeletesOnlyPairsNoHeldKeyNames() =
        runBlocking {
            keys.forKey(HOST, KEY)
            keys.forKey(HOST, OTHER_KEY)
            keys.retain()
            assertEquals(1, keystore.deleted.size)
            assertEquals(DeviceKeys.slotOf(HOST, OTHER_KEY), DeviceKeys.slotOfAlias(keystore.deleted.single()))
            assertEquals(1, slotAliases().size)
        }

    @Test
    fun retainDeletesNothingWhileAHeldKeyCannotBeRead() =
        runBlocking {
            keys.forKey(HOST, KEY)
            heldReadable = false
            held.clear()
            keys.retain()
            assertTrue(keystore.deleted.isEmpty())
        }

    @Test
    fun aRefusedListDeletesNothing() =
        runBlocking {
            keys.forKey(HOST, OTHER_KEY)
            keystore.listRefused = true
            keys.retain()
            assertTrue(keystore.deleted.isEmpty())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aSweepNeverDeletesAPairMadeForAKeyItHadNotSeen() =
        runTest {
            // The sweep reads the held keys, then is held there; meanwhile the store writes a new key and makes its pair.
            val gate = CompletableDeferred<Unit>()
            val racing =
                keys {
                    val snapshot = held.toMap()
                    gate.await()
                    snapshot
                }
            val unconfined = UnconfinedTestDispatcher(testScheduler)
            val sweep = async(unconfined) { racing.retain() }
            held[HOST] = OTHER_KEY
            val made = async(unconfined) { racing.forKey(HOST, OTHER_KEY) }
            gate.complete(Unit)
            sweep.await()
            made.await()
            // The pair is made after the sweep lets go of the lock, so the sweep never saw it to delete.
            assertTrue(keystore.pairs.keys.any { DeviceKeys.slotOfAlias(it) == DeviceKeys.slotOf(HOST, OTHER_KEY) })
            assertTrue(keystore.deleted.isEmpty())
        }

    @Test
    fun nothingItRendersNamesAPair() =
        runBlocking {
            val pair = ready()
            val spki = DeviceProof.b64(pair.signer.publicKey()!!)
            listOf(pair.toString(), pair.signer.toString()).forEach {
                assertFalse(it.contains(pair.id) || it.contains(spki.take(16)) || it.contains(DeviceKeys.ALIAS_PREFIX))
            }
        }

    companion object {
        const val HOST = "relay.example.org"
        const val KEY = "kak1_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        const val OTHER_KEY = "kak1_BBECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    }
}
