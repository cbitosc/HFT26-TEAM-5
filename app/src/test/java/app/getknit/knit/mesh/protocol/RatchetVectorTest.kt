package app.getknit.knit.mesh.protocol

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.crypto.AesGcm
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetCrypto
import app.getknit.knit.mesh.crypto.ratchet.RatchetCrypto
import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine
import com.google.crypto.tink.subtle.Hkdf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Ratchet vectors (`vectors/ratchet-v1.json`): the forward-secrecy ratchet's known answers, for the iOS port
 * (companion change A9). Alice and Bob are [KeyedVectorTest]'s identities and Carol is `ios-emitted-v1.json`'s;
 * every other key is a stated [fixtureBytes] draw recorded beside what it derives, so a port replays values
 * instead of re-deriving the seeding.
 *
 * The `kats` section is the primitive layer ([RatchetCrypto], [GroupRatchetCrypto], [AesGcm]) walked through one
 * coherent first exchange: Alice initiates to Bob's signed prekey, opens her first send epoch on it, and Bob
 * answers on Alice's epoch key. Everything in it is deterministic (HKDF, X25519, Tink's RFC 8032 Ed25519 and a
 * fixed-IV GCM seal), so it is rebuilt on every run and compared whole. Entries marked `normative: false`
 * (the export secrets) are API-only today; a port may skip them.
 */
class RatchetVectorTest {
    private val alice = VectorParty(fixtureBytes(32, 1), fixtureBytes(32, 2))
    private val bob = VectorParty(fixtureBytes(32, 3), fixtureBytes(32, 4))
    private val carol = VectorParty(fixtureBytes(32, 5), fixtureBytes(32, 6))

    private fun pair(seed: Int): RatchetCrypto.KeyPair {
        val priv = fixtureBytes(32, seed)
        return RatchetCrypto.KeyPair(priv, RatchetCrypto.publicFromPrivate(priv))
    }

    // The draws, by seed. Each is recorded in the file with its public half.
    private val bobSpk = pair(SEED_BOB_SPK)
    private val aliceSpk = pair(SEED_ALICE_SPK)
    private val aliceEph = pair(SEED_ALICE_EPH)
    private val aliceEpoch1 = pair(SEED_ALICE_EPOCH1)
    private val bobEpoch1 = pair(SEED_BOB_EPOCH1)

    private val root = RatchetCrypto.x3dhInitiate(alice.agreementScalar, aliceEph.priv, bob.bundle.dhPublicKey(), bobSpk.pub)

    // Alice's first send epoch: se 1 on Bob's prekey (pe 0), sent as the session's initiator.
    private val aliceDh = RatchetCrypto.dh(aliceEpoch1.priv, bobSpk.pub)
    private val aliceKeys = RatchetCrypto.deriveEpoch(root, aliceDh, senderIsInitiator = true, senderEpoch = 1, baseEpoch = 0)

    // Bob's first send epoch: se 1 on Alice's epoch-1 key (pe 1), sent as the responder.
    private val bobDh = RatchetCrypto.dh(bobEpoch1.priv, aliceEpoch1.pub)
    private val bobKeys = RatchetCrypto.deriveEpoch(root, bobDh, senderIsInitiator = false, senderEpoch = 1, baseEpoch = 1)

    private val frameId = FrameId.fromBytes(fixtureBytes(16, SEED_FRAME_ID))
    private val aad = MessageCrypto.header(frameId, alice.nodeId, SENT_AT, bob.nodeId)

    private fun initHeader(flags: Int) =
        RatchetEngine.FrameHeader(
            se = 1,
            ek = aliceEpoch1.pub,
            pe = 0,
            n = 0,
            init = RatchetEngine.InitPayload(eph = aliceEph.pub, pkid = BOB_SPK_ID, at = SESSION_AT),
            flags = flags,
        )

    private fun plainHeader(flags: Int) = RatchetEngine.FrameHeader(se = 1, ek = bobEpoch1.pub, pe = 1, n = 2, flags = flags)

    private val groupId = Conversations.groupIdFor(listOf(alice.nodeId, bob.nodeId, carol.nodeId))
    private val groupSeed = fixtureBytes(GroupRatchetCrypto.SEED_BYTES, SEED_GROUP)
    private val groupKeys = GroupRatchetCrypto.deriveEpoch(groupSeed, groupId, alice.nodeId, GROUP_EPOCH)

    private val file: JsonObject by lazy { VectorFiles.read(FILE) }

    @Test
    fun `the primitive known answers rebuild exactly`() {
        assumeFalse(VectorFiles.writing)
        assertEquals("ratchet vector section 'kats' drifted; regenerate with KNIT_WRITE_VECTORS=1", katsJson(), file.getValue("kats"))
        assertEquals(identitiesJson(), file.getValue("identities"))
    }

    @Test
    fun `the known answers agree with themselves`() {
        val responderRoot = RatchetCrypto.x3dhRespond(bob.agreementScalar, bobSpk.priv, alice.bundle.dhPublicKey(), aliceEph.pub)
        assertArrayEquals("both sides derive one root", root, responderRoot)
        assertArrayEquals("both ends of a DH agree", aliceDh, RatchetCrypto.dh(bobSpk.priv, aliceEpoch1.pub))
        assertArrayEquals(bobDh, RatchetCrypto.dh(aliceEpoch1.priv, bobEpoch1.pub))
        for (prekey in listOf(Prekey(alice, ALICE_SPK_ID, aliceSpk), Prekey(bob, BOB_SPK_ID, bobSpk))) {
            assertTrue(MessageCrypto.verify(prekey.owner.bundle, prekey.sig(), prekey.signingBytes()))
        }
        // The spelled-out infos are what the private labels build: HKDF over them reproduces each output.
        assertArrayEquals(aliceKeys.chainKey + aliceKeys.export, Hkdf.computeHkdf("HMACSHA256", aliceDh, root, epochInfo('i', 1, 0), 64))
        assertArrayEquals(bobKeys.chainKey + bobKeys.export, Hkdf.computeHkdf("HMACSHA256", bobDh, root, epochInfo('r', 1, 1), 64))
        assertArrayEquals(
            groupKeys.chainKey + groupKeys.export,
            Hkdf.computeHkdf("HMACSHA256", groupSeed, ByteArray(32), groupInfo(), 64),
        )
        val (iv, sealed) = gcmSeal()
        assertArrayEquals(GCM_PLAIN.encodeToByteArray(), AesGcm.decrypt(fixtureBytes(32, SEED_GCM_KEY), iv, sealed, gcmAad()))
    }

    @Test
    fun `KNIT_WRITE_VECTORS=1 rewrites the ratchet vectors`() {
        assumeTrue(VectorFiles.writing)
        VectorFiles.write(
            FILE,
            buildJsonObject {
                put("about", ABOUT)
                put("identities", identitiesJson())
                put("kats", katsJson())
            },
        )
    }

    private fun identitiesJson(): JsonObject =
        buildJsonObject {
            put("alice", alice.toJson())
            put("bob", bob.toJson())
            put("carol", carol.toJson())
        }

    private inner class Prekey(
        val owner: VectorParty,
        val id: Int,
        val keys: RatchetCrypto.KeyPair,
    ) {
        fun signingBytes(): ByteArray = RatchetCrypto.spkSigningBytes(id, keys.pub)

        fun sig(): ByteArray = owner.sign(signingBytes())
    }

    private fun katsJson(): JsonObject =
        buildJsonObject {
            putJsonObject("draws") {
                draw("bobSpk", SEED_BOB_SPK, bobSpk)
                draw("aliceSpk", SEED_ALICE_SPK, aliceSpk)
                draw("aliceEph", SEED_ALICE_EPH, aliceEph)
                draw("aliceEpoch1", SEED_ALICE_EPOCH1, aliceEpoch1)
                draw("bobEpoch1", SEED_BOB_EPOCH1, bobEpoch1)
            }
            put("x3dh", x3dhJson())
            putJsonArray("deriveEpoch") {
                add(epochJson("alice's first epoch, on bob's prekey", aliceEpoch1, bobSpk.pub, aliceDh, 'i', 1, 0, aliceKeys))
                add(epochJson("bob's first epoch, on alice's epoch-1 key", bobEpoch1, aliceEpoch1.pub, bobDh, 'r', 1, 1, bobKeys))
            }
            putJsonArray("chain") {
                add(chainJson("alice", 1, aliceKeys.chainKey, RatchetCrypto::messageKey, RatchetCrypto::nextChainKey))
                add(chainJson("bob", 1, bobKeys.chainKey, RatchetCrypto::messageKey, RatchetCrypto::nextChainKey))
            }
            put("messageNonce", nonceJson())
            putJsonObject("headerBindingBytes") {
                put("initFlags0", headerJson(initHeader(0)))
                put("initFlags1", headerJson(initHeader(1)))
                put("noInitFlags0", headerJson(plainHeader(0)))
                put("noInitFlags1", headerJson(plainHeader(1)))
            }
            putJsonArray("spkSigningBytes") {
                add(prekeyJson("alice", Prekey(alice, ALICE_SPK_ID, aliceSpk)))
                add(prekeyJson("bob", Prekey(bob, BOB_SPK_ID, bobSpk)))
            }
            put("rootTag", rootTagJson())
            put("group", groupJson())
            put("aesGcm", gcmJson())
            put("export", exportJson())
        }

    private fun x3dhJson() =
        buildJsonObject {
            put("initiator", "alice")
            put("responder", "bob")
            putJsonObject("initiate") {
                put("ikPriv", alice.agreementScalar.toHex())
                put("ekPriv", aliceEph.priv.toHex())
                put("peerIkPub", bob.bundle.dhPublicKey().toHex())
                put("peerSpkPub", bobSpk.pub.toHex())
            }
            putJsonObject("respond") {
                put("ikPriv", bob.agreementScalar.toHex())
                put("spkPriv", bobSpk.priv.toHex())
                put("peerIkPub", alice.bundle.dhPublicKey().toHex())
                put("peerEkPub", aliceEph.pub.toHex())
            }
            put("root", root.toHex())
        }

    private fun nonceJson() =
        buildJsonObject {
            val mk = RatchetCrypto.messageKey(aliceKeys.chainKey)
            val boundAad = aad + RatchetCrypto.headerBindingBytes(initHeader(flags = 0))
            put("note", "alice's first frame (se 1, n 0, with init): boundAad = aad || headerBindingBytes(header)")
            put("mk", mk.toHex())
            put("aadText", aad.decodeToString())
            put("aad", aad.toHex())
            put("headerBinding", "initFlags0")
            put("boundAad", boundAad.toHex())
            put("nonce", RatchetCrypto.messageNonce(mk, boundAad).toHex())
        }

    private fun prekeyJson(
        owner: String,
        prekey: Prekey,
    ) = buildJsonObject {
        put("owner", owner)
        put("id", prekey.id)
        put("pub", prekey.keys.pub.toHex())
        put("bytes", prekey.signingBytes().toHex())
        put("sig", prekey.sig().toHex())
        put("sigNote", "Ed25519 by the owner's signing key; Tink's is RFC 8032 deterministic, but verify, never compare")
    }

    private fun rootTagJson() =
        buildJsonObject {
            val tag = RatchetCrypto.rootTag(root)
            put("note", "local storage only, never on the wire: the first 8 HKDF bytes, read big-endian as a signed Int64")
            put("root", root.toHex())
            put("bytes", "%016x".format(tag))
            put("int64", tag)
        }

    private fun groupJson() =
        buildJsonObject {
            put("groupId", groupId)
            putJsonArray("groupMembers") {
                add("alice")
                add("bob")
                add("carol")
            }
            put("senderId", alice.nodeId)
            put("sender", "alice")
            put("epoch", GROUP_EPOCH)
            put("seedSeed", SEED_GROUP)
            put("seed", groupSeed.toHex())
            put("info", groupInfo().toHex())
            put("chainKey", groupKeys.chainKey.toHex())
            put("export", groupKeys.export.toHex())
            put(
                "chain",
                chainJson("alice", GROUP_EPOCH, groupKeys.chainKey, GroupRatchetCrypto::messageKey, GroupRatchetCrypto::nextChainKey),
            )
        }

    private fun gcmJson() =
        buildJsonObject {
            val (iv, sealed) = gcmSeal()
            put("key", fixtureBytes(32, SEED_GCM_KEY).toHex())
            put("iv", iv.toHex())
            put("aad", gcmAad().toHex())
            put("plaintext", GCM_PLAIN.encodeToByteArray().toHex())
            put("sealed", sealed.toHex())
            put("sealedNote", "ciphertext || 16-byte tag")
        }

    private fun exportJson() =
        buildJsonObject {
            put("normative", false)
            put("note", "API-only today (the spool's pairwiseRoot and a reserved sealv 2); a port may skip these")
            put("exportRoot", RatchetCrypto.exportRoot(root).toHex())
            put("aliceEpoch1ExportSeal", RatchetCrypto.exportEpochSeal(aliceKeys.export).toHex())
            put("groupExportSeal", GroupRatchetCrypto.exportEpochSeal(groupKeys.export).toHex())
        }

    private fun JsonObjectBuilder.draw(
        name: String,
        seed: Int,
        keys: RatchetCrypto.KeyPair,
    ) = putJsonObject(name) {
        put("seed", seed)
        put("priv", keys.priv.toHex())
        put("pub", keys.pub.toHex())
    }

    @Suppress("LongParameterList") // one row of the vector, each argument a column
    private fun epochJson(
        note: String,
        sender: RatchetCrypto.KeyPair,
        basePub: ByteArray,
        dh: ByteArray,
        dir: Char,
        se: Int,
        pe: Int,
        keys: RatchetCrypto.EpochKeys,
    ) = buildJsonObject {
        put("note", note)
        put("sessionRoot", root.toHex())
        put("senderEpochPriv", sender.priv.toHex())
        put("senderEpochPub", sender.pub.toHex())
        put("basePub", basePub.toHex())
        put("dhShared", dh.toHex())
        put("dir", dir.toString())
        put("senderIsInitiator", dir == 'i')
        put("se", se)
        put("pe", pe)
        put("info", epochInfo(dir, se, pe).toHex())
        put("okm", (keys.chainKey + keys.export).toHex())
        put("chainKey", keys.chainKey.toHex())
        put("export", keys.export.toHex())
    }

    private fun chainJson(
        sender: String,
        epoch: Int,
        ck0: ByteArray,
        messageKey: (ByteArray) -> ByteArray,
        nextChainKey: (ByteArray) -> ByteArray,
    ) = buildJsonObject {
        put("sender", sender)
        put("epoch", epoch)
        put("ck0", ck0.toHex())
        putJsonArray("steps") {
            var ck = ck0
            for (n in 0 until CHAIN_STEPS) {
                val next = nextChainKey(ck)
                add(
                    buildJsonObject {
                        put("n", n)
                        put("ck", ck.toHex())
                        put("mk", messageKey(ck).toHex())
                        put("nextCk", next.toHex())
                    },
                )
                ck = next
            }
        }
    }

    private fun headerJson(header: RatchetEngine.FrameHeader) =
        buildJsonObject {
            put("se", header.se)
            put("ek", header.ek.toHex())
            put("pe", header.pe)
            put("n", header.n)
            put("flags", header.flags)
            val init = header.init
            if (init != null) {
                putJsonObject("init") {
                    put("eph", init.eph.toHex())
                    put("pkid", init.pkid)
                    put("at", init.at)
                }
            }
            put("bytes", RatchetCrypto.headerBindingBytes(header).toHex())
        }

    // The HKDF infos, spelled out so a port can check its own before the output: they are the KDF's only framing.
    private fun epochInfo(
        dir: Char,
        se: Int,
        pe: Int,
    ): ByteArray = "knit/dm/v2/epoch".encodeToByteArray() + byteArrayOf(dir.code.toByte()) + u32be(se) + u32be(pe)

    private fun groupInfo(): ByteArray =
        "knit/group/v1/epoch".encodeToByteArray() + "$groupId|${alice.nodeId}|".encodeToByteArray() + u32be(GROUP_EPOCH)

    private fun u32be(value: Int): ByteArray = ByteArray(Int.SIZE_BYTES) { i -> (value ushr (24 - 8 * i)).toByte() }

    private fun gcmAad(): ByteArray = fixtureBytes(GCM_AAD_BYTES, SEED_GCM_AAD)

    private fun gcmSeal(): Pair<ByteArray, ByteArray> =
        AesGcm.encrypt(
            fixtureBytes(32, SEED_GCM_KEY),
            GCM_PLAIN.encodeToByteArray(),
            gcmAad(),
            fixtureBytes(AesGcm.IV_BYTES, SEED_GCM_IV),
        )

    private companion object {
        const val FILE = "ratchet-v1.json"
        const val ABOUT =
            "Ratchet vectors for the forward-secrecy ratchet (companion change A9 for the iOS port): the primitive " +
                "known answers walked through one first exchange. RatchetVectorTest rebuilds and checks them. See vectors/README.md."

        // fixtureBytes seeds. 1-8 are identities (dave takes 7-8), 21-23 keyed-v1's frame ids and file key.
        const val SEED_BOB_SPK = 41
        const val SEED_ALICE_SPK = 42
        const val SEED_ALICE_EPH = 43
        const val SEED_ALICE_EPOCH1 = 44
        const val SEED_BOB_EPOCH1 = 45
        const val SEED_FRAME_ID = 46
        const val SEED_GROUP = 47
        const val SEED_GCM_KEY = 48
        const val SEED_GCM_IV = 49
        const val SEED_GCM_AAD = 50

        const val BOB_SPK_ID = 1
        const val ALICE_SPK_ID = 1
        const val GROUP_EPOCH = 1
        const val CHAIN_STEPS = 4
        const val GCM_AAD_BYTES = 20
        const val GCM_PLAIN = "Hi Bob"

        /** keyed-v1's clock: Alice's profile stamp. The session is established on it, the frame sent 3 s later. */
        const val SESSION_AT = 1_756_100_000_000L
        const val SENT_AT = SESSION_AT + 3_000L
    }
}
