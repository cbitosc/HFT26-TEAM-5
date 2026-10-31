package app.getknit.knit.mesh.protocol

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * The DM ratchet transcripts of `ratchet-v1.json` (companion change A9, stage 3a): `dm`, whole exchanges between
 * Alice and Bob, and `negative`, single frames a receiver refuses. Each is self-contained — a fresh pair of
 * [TranscriptParty]s, its own draw plan — so a port replays one without the others; two that share an opening share
 * its seeds and therefore its bytes. Draws take [fixtureBytes] seeds from 60 up (1-8 are identities, 21-23 and 41-52
 * the earlier sections'); the prekeys are `profiles.prekeys`' `aliceSpk1` and `bobSpk1`.
 *
 * Stage 3b's scenarios (ctl frames, the unsigned tick, races, resets, the re-seal pair) slot in as further entries
 * of [scenarios] and [negatives], with any new step kind added to [Transcript].
 */
internal class RatchetDmVectors(
    private val alice: VectorParty,
    private val bob: VectorParty,
    private val carol: VectorParty,
    private val aliceSpk1: VectorPrekey,
    private val bobSpk1: VectorPrekey,
    /** Alice's first seal: the session's `establishedAt` and the first epoch's start. */
    private val t0: Long,
) {
    private fun parties(
        alicePlan: List<Draw>,
        bobPlan: List<Draw>,
        bobHolds: List<VectorPrekey> = listOf(bobSpk1),
    ): List<TranscriptParty> =
        listOf(
            TranscriptParty("alice", alice, listOf(aliceSpk1), alicePlan),
            TranscriptParty("bob", bob, bobHolds, bobPlan),
        )

    private fun transcript(
        name: String,
        note: String,
        parties: List<TranscriptParty>,
    ) = Transcript(name, note, parties).apply {
        pin("alice", "bob", bobSpk1)
        pin("bob", "alice", aliceSpk1)
    }

    /** Alice's first two frames: v2 with the init, then v3 still carrying it. */
    private suspend fun Transcript.aliceOpens(): Pair<TranscriptFrame, TranscriptFrame> =
        seal("alice", "bob", "Hi Bob", t0, v3 = false) to seal("alice", "bob", "Are you there?", t0 + SECOND, v3 = true)

    /** [aliceOpens], and Bob opening both in order: first contact. */
    private suspend fun Transcript.opening(): Pair<TranscriptFrame, TranscriptFrame> {
        val (first, second) = aliceOpens()
        open("bob", first, t0 + 5 * SECOND, OPENED)
        open("bob", second, t0 + 6 * SECOND, OPENED)
        return first to second
    }

    /** [opening], Bob's reply, and Alice's turnaround frame — the first without the init. */
    private suspend fun Transcript.confirmed() {
        opening()
        val reply = seal("bob", "alice", "Hi Alice", t0 + 10 * SECOND, v3 = true)
        open("alice", reply, t0 + 12 * SECOND, OPENED)
        val next = seal("alice", "bob", "Good to hear from you", t0 + 15 * SECOND, v3 = true)
        open("bob", next, t0 + 16 * SECOND, OPENED)
    }

    suspend fun scenarios(): JsonArray =
        buildJsonArray {
            add(firstContact())
            add(confirm())
            add(turnaround())
            add(epochCap())
            add(epochAge())
            add(outOfOrder())
            // Stage 3b, appended: stage 3a's six above do not move.
            for (scenario in RatchetSessionVectors(alice, bob, aliceSpk1, bobSpk1, t0).scenarios()) add(scenario)
        }

    private suspend fun firstContact(): JsonObject =
        transcript(
            "firstContact",
            "Alice initiates to Bob's signed prekey (X3DH) and opens send epoch 1 on it (pe 0): a v2 frame, then a v3 " +
                "one, both carrying the init because the session is unconfirmed. Bob, holding nothing, responds from " +
                "the first frame's init and opens both; a responder's session is confirmed from the start.",
            parties(OPENING_ALICE, emptyList()),
        ).apply { opening() }.json()

    private suspend fun confirm(): JsonObject =
        transcript(
            "confirm",
            "firstContact, then Bob's first frame: his epoch 1, DHed on Alice's epoch-1 key (pe 1). Opening it confirms " +
                "Alice's session (the peer sealed against an epoch of hers), so her next frame drops the init — and, " +
                "her peer's base having moved past her epoch's, opens her epoch 2 on Bob's epoch 1.",
            parties(OPENING_ALICE + Draw.Key(64), listOf(Draw.Key(63))),
        ).apply { confirmed() }.json()

    private suspend fun turnaround(): JsonObject =
        transcript(
            "turnaround",
            "confirm, then the healing rule both ways: a second Alice frame stays in her epoch 2 (Bob has not moved), " +
                "Bob's answer opens his epoch 2 on her epoch 2 — sealed v2, so it draws an IV — and Alice's answer to " +
                "that opens her epoch 3. Every conversational turnaround is a fresh DH.",
            parties(OPENING_ALICE + Draw.Key(64) + Draw.Key(67), listOf(Draw.Key(63), Draw.Key(65), Draw.Iv(66))),
        ).apply {
            confirmed()
            val same = seal("alice", "bob", "Still epoch 2", t0 + 17 * SECOND, v3 = true)
            open("bob", same, t0 + 18 * SECOND, OPENED)
            val bobsTurn = seal("bob", "alice", "My turn", t0 + 20 * SECOND, v3 = false)
            open("alice", bobsTurn, t0 + 21 * SECOND, OPENED)
            val alicesTurn = seal("alice", "bob", "And mine", t0 + 25 * SECOND, v3 = true)
            open("bob", alicesTurn, t0 + 26 * SECOND, OPENED)
        }.json()

    private suspend fun epochCap(): JsonObject =
        transcript(
            "epochCap",
            "MAX_EPOCH_MESSAGES = 200. firstContact leaves Alice's epoch 1 at n 2; an advance step seals n 2..197 " +
                "unlisted (v3, no draws), and only the boundary frames are listed: n 198, n 199, and the next seal, " +
                "which the full epoch moves to epoch 2 (still pe 0 on Bob's prekey, still with the init). Bob opens " +
                "n 198 first, so n 2..197 become skipped keys, then n 199 and epoch 2's n 0.",
            parties(OPENING_ALICE + Draw.Key(68), emptyList()),
        ).apply {
            opening()
            advance("alice", "bob", RatchetEngine.MAX_EPOCH_MESSAGES - 4, t0 + 7 * SECOND)
            val n198 = seal("alice", "bob", "n 198", t0 + 8 * SECOND, v3 = true)
            val n199 = seal("alice", "bob", "n 199", t0 + 9 * SECOND, v3 = true)
            val next = seal("alice", "bob", "epoch 2", t0 + 10 * SECOND, v3 = true)
            open("bob", n198, t0 + 11 * SECOND, OPENED)
            open("bob", n199, t0 + 12 * SECOND, OPENED)
            open("bob", next, t0 + 13 * SECOND, OPENED)
        }.json()

    private suspend fun epochAge(): JsonObject =
        transcript(
            "epochAge",
            "MAX_EPOCH_AGE_MS = 24 h, against the epoch's sendEpochStartedAt (Alice's first seal): a seal 1 ms short of " +
                "it stays in epoch 1, a seal at it opens epoch 2.",
            parties(OPENING_ALICE + Draw.Key(69), emptyList()),
        ).apply {
            opening()
            val young = seal("alice", "bob", "just in time", t0 + RatchetEngine.MAX_EPOCH_AGE_MS - 1, v3 = true)
            val aged = seal("alice", "bob", "a new day", t0 + RatchetEngine.MAX_EPOCH_AGE_MS, v3 = true)
            open("bob", young, t0 + RatchetEngine.MAX_EPOCH_AGE_MS + SECOND, OPENED)
            open("bob", aged, t0 + RatchetEngine.MAX_EPOCH_AGE_MS + 2 * SECOND, OPENED)
        }.json()

    private suspend fun outOfOrder(): JsonObject =
        transcript(
            "outOfOrder",
            "Alice seals n 0 (v2) to n 3; Bob's first frame is n 3, which establishes his session from its init and " +
                "stores n 0..2 as skipped keys. n 1 and n 2 open on theirs, then n 0 (v2), and re-opening n 1 or n 3 " +
                "finds the index consumed: DUPLICATE, which never triggers a reset.",
            parties(OPENING_ALICE, emptyList()),
        ).apply {
            val (n0, n1) = aliceOpens()
            val n2 = seal("alice", "bob", "two", t0 + 2 * SECOND, v3 = true)
            val n3 = seal("alice", "bob", "three", t0 + 3 * SECOND, v3 = true)
            open("bob", n3, t0 + 5 * SECOND, OPENED)
            open("bob", n1, t0 + 6 * SECOND, OPENED)
            open("bob", n2, t0 + 7 * SECOND, OPENED)
            open("bob", n0, t0 + 8 * SECOND, OPENED)
            open("bob", n1, t0 + 9 * SECOND, DUPLICATE)
            open("bob", n3, t0 + 10 * SECOND, DUPLICATE)
        }.json()

    // ---- negative: one frame each, refused ------------------------------------------------------------------

    /**
     * A case on a fresh Bob: Alice seals her two opening frames and Bob opens [build]'s frame (built from them),
     * nothing else. [replay] says whether `InboundPipelineTest` replays it (only a case with no setup on Bob's side).
     */
    private suspend fun negative(
        name: String,
        note: String,
        bobHolds: List<VectorPrekey> = listOf(bobSpk1),
        replay: Boolean = true,
        build: suspend Transcript.(TranscriptFrame, TranscriptFrame) -> Unit,
    ): JsonObject =
        transcript(name, note, parties(OPENING_ALICE, emptyList(), bobHolds))
            .apply {
                field("pipelineReplay", JsonPrimitive(replay))
                val (first, second) = aliceOpens()
                build(first, second)
            }.json()

    private fun TranscriptFrame.withHeader(
        description: String,
        change: (RatchetHeader) -> RatchetHeader,
    ): TranscriptFrame = mutated(description, enc.copyWith(r = change(checkNotNull(enc.r))))

    private fun RatchetHeader.copy(
        se: Int = this.se,
        ek: ByteArray = this.ek,
        pe: Int = this.pe,
        n: Int = this.n,
        init: RatchetInit? = this.init,
    ) = RatchetHeader(se = se, ek = ek, pe = pe, n = n, init = init, flags = flags)

    private fun EncEnvelope.copyWith(
        nonce: ByteArray = this.nonce,
        ct: ByteArray = this.ct,
        r: RatchetHeader? = this.r,
    ) = EncEnvelope(v = v, nonce = nonce, ct = ct, keys = keys, r = r, g = g)

    /** Every header-bound case: alice's v2 opening frame with one field out of bounds, refused before any key work. */
    private suspend fun headerBound(
        name: String,
        note: String,
        description: String,
        expect: String = BAD_HEADER,
        change: (RatchetHeader) -> RatchetHeader,
    ): JsonObject = negative(name, note) { first, _ -> open("bob", first.withHeader(description, change), t0 + 5 * SECOND, expect) }

    suspend fun negatives(): JsonArray =
        buildJsonArray {
            for (case in headerBounds()) add(case)
            for (case in keyRefusals()) add(case)
            for (case in pipelineRefusals()) add(case)
        }

    private suspend fun headerBounds(): List<JsonObject> {
        val ceiling = RatchetEngine.MAX_EPOCH_NUMBER
        val cap = RatchetEngine.MAX_EPOCH_MESSAGES
        return listOf(
            headerBound("seZero", "se is 1..MAX_EPOCH_NUMBER", "r.se = 0") { it.copy(se = 0) },
            headerBound("seOverCeiling", "MAX_EPOCH_NUMBER = 2^24", "r.se = ${ceiling + 1}") { it.copy(se = ceiling + 1) },
            headerBound(
                "seAtCeiling",
                "se = MAX_EPOCH_NUMBER is in bounds: the frame reaches the key work, derives a key Alice never sealed " +
                    "under, and fails its AEAD — reset-triggering, unlike every out-of-bounds case",
                "r.se = $ceiling",
                expect = AEAD_FAIL,
            ) { it.copy(se = ceiling) },
            headerBound("nAtCap", "n is 0 until MAX_EPOCH_MESSAGES (200)", "r.n = $cap") { it.copy(n = cap) },
            headerBound("nNegative", "n is 0 until MAX_EPOCH_MESSAGES", "r.n = -1") { it.copy(n = -1) },
            headerBound("peNegativeWithInit", "pe is 0..MAX_EPOCH_NUMBER, init or not", "r.pe = -1") { it.copy(pe = -1) },
            headerBound("peOverCeiling", "pe is 0..MAX_EPOCH_NUMBER", "r.pe = ${ceiling + 1}") { it.copy(pe = ceiling + 1) },
            headerBound("peZeroWithoutInit", "pe 0 names our signed prekey, legal only with an init", "r.init = null") {
                it.copy(init = null)
            },
            headerBound("ekShort", "ek is 32 bytes", "r.ek = its first 31 bytes") { it.copy(ek = it.ek.copyOf(31)) },
        )
    }

    private suspend fun keyRefusals(): List<JsonObject> =
        listOf(
            negative(
                "ekLowOrder",
                "an ek of small order: X25519 refuses it, and the engine reports BAD_HEADER (never a missing key)",
            ) { first, _ ->
                open("bob", first.withHeader("r.ek = $LOW_ORDER_A") { it.copy(ek = LOW_ORDER_A.fromHex()) }, t0 + 5 * SECOND, BAD_HEADER)
            },
            negative(
                "prekeyGone",
                "the init names a prekey Bob no longer holds (pruned): EPOCH_GONE, reset-triggering",
                bobHolds = emptyList(),
            ) { first, _ -> open("bob", first, t0 + 5 * SECOND, EPOCH_GONE) },
            negative(
                "noInitNoSession",
                "a frame with no init (pe 1, so in bounds) to a Bob holding no session: NO_SESSION, reset-triggering",
            ) { first, _ ->
                open("bob", first.withHeader("r.init = null, r.pe = 1") { it.copy(init = null, pe = 1) }, t0 + 5 * SECOND, NO_SESSION)
            },
            negative(
                "ownEpochGone",
                "Bob holds the session (he opened the first frame), and a frame opens a new epoch (se 2) on an epoch of " +
                    "his he never minted (pe 5): EPOCH_GONE before any AEAD",
                replay = false,
            ) { first, second ->
                open("bob", first, t0 + 5 * SECOND, OPENED)
                open("bob", second.withHeader("r.se = 2, r.pe = 5") { it.copy(se = 2, pe = 5) }, t0 + 6 * SECOND, EPOCH_GONE)
            },
            negative(
                "ctTampered",
                "the v3 frame with its last ciphertext byte flipped: every key resolves and the AEAD refuses. Nothing commits " +
                    "— Bob still holds no session",
            ) { _, second ->
                val ct =
                    second.enc.ct
                        .copyOf()
                        .also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                open("bob", second.mutated("ct's last byte xor 1", second.enc.copyWith(ct = ct)), t0 + 5 * SECOND, AEAD_FAIL)
            },
        )

    private suspend fun pipelineRefusals(): List<JsonObject> =
        listOf(
            negative(
                "initEphLowOrder",
                "an init whose eph is of small order. The engine does not type this: X25519 throws inside the X3DH " +
                    "respond, out of RatchetEngine.open, and InboundPipeline's runCatching drops it as DECRYPT_FAILED — " +
                    "before onRatchetFailure, so it never reaches the reset heuristic, and nothing commits",
            ) { first, _ ->
                val bad =
                    first.withHeader("r.init.eph = $LOW_ORDER_B") {
                        val init = checkNotNull(it.init)
                        it.copy(init = RatchetInit(eph = LOW_ORDER_B.fromHex(), pkid = init.pkid, at = init.at))
                    }
                refusedByPipeline(
                    "bob",
                    bad,
                    t0 + 5 * SECOND,
                    DropReason.DECRYPT_FAILED,
                    dropNormative = false,
                    dropNote = LOW_ORDER_EPH_NOTE,
                ) { bob ->
                    val r = checkNotNull(bad.enc.r)
                    val aad = MessageCrypto.header(bad.env.id, bad.env.senderId, bad.env.sentAt, bad.env.recipientId.orEmpty())
                    val thrown =
                        runCatching {
                            bob.sessions.peekOpen(
                                bob.nodeId,
                                alice.nodeId,
                                alice.bundle.dhPublicKey(),
                                r,
                                bad.enc.nonce,
                                bad.enc.ct,
                                aad,
                                t0,
                            )
                        }
                    assertTrue("the X3DH respond throws on a small-order eph", thrown.isFailure)
                    assertNull("nothing committed", bob.store.session(alice.nodeId))
                }
            },
            negative(
                "v3NonEmptyNonce",
                "a v3 envelope carries its nonce field empty (it is derived); a 12-byte one is malformed. Refused by " +
                    "InboundPipeline.nonceShapeValid before the engine",
            ) { _, second ->
                val bad = second.mutated("nonce = 12 zero bytes", second.enc.copyWith(nonce = ByteArray(12)))
                refusedByPipeline("bob", bad, t0 + 5 * SECOND, DropReason.RATCHET_BAD_HEADER)
            },
            negative(
                "v2EmptyNonce",
                "the mirror: a v2 envelope carries a 12-byte IV, so an empty one is malformed (nonceShapeValid)",
            ) { first, _ ->
                val bad = first.mutated("nonce = empty", first.enc.copyWith(nonce = ByteArray(0)))
                refusedByPipeline("bob", bad, t0 + 5 * SECOND, DropReason.RATCHET_BAD_HEADER)
            },
            v3ToGroup(),
        )

    private suspend fun v3ToGroup(): JsonObject {
        val members = listOf(alice.nodeId, bob.nodeId, carol.nodeId)
        val group = GroupInfo(id = Conversations.groupIdFor(members), members = members, createdBy = alice.nodeId)
        return negative(
            "v3ToGroup",
            "v3 is the DM form by rule: Alice's v3 frame re-addressed to the group of alice, bob and carol (recipientId " +
                "absent) routes to the DM arm and is refused there, before any group or reset logic. The receiver holds " +
                "the group (its members are in groupMembers)",
        ) { _, second ->
            field(
                "groupMembers",
                buildJsonArray {
                    add(JsonPrimitive("alice"))
                    add(JsonPrimitive("bob"))
                    add(JsonPrimitive("carol"))
                },
            )
            refusedByPipeline(
                "bob",
                second.mutated("addressed to the group", second.enc, group),
                t0 + 5 * SECOND,
                DropReason.RATCHET_BAD_HEADER,
            )
        }
    }

    /** What the file says about its own layout, once, for a port reading it cold. */
    fun formatJson(): JsonObject =
        buildJsonObject {
            put("note", FORMAT_NOTE)
            putJsonArray("ops") {
                add(JsonPrimitive("seal: actor seals contentBody to peer at now (= sentAt), against pins[peer]"))
                add(JsonPrimitive("open: actor opens the frame in wire (signed by peer); expect is the outcome"))
                add(JsonPrimitive("advance: actor seals count v3 frames of any content to peer and discards them"))
            }
            // Stage 3b's rules, after stage 3a's entries (which do not move).
            for ((key, value) in RatchetSessionVectors.formatAdditions()) put(key, value)
            // Stage 4's group section, after every earlier key (which do not move).
            put("group", RatchetGroupVectors.formatJson())
            // Stage 5's carol section, after every earlier key (which do not move).
            put("carol", RatchetCarolVectors.formatJson())
        }

    companion object {
        const val SECOND = 1_000L
        const val OPENED = "OPENED"
        const val DUPLICATE = "DUPLICATE"
        const val BAD_HEADER = "BAD_HEADER"
        const val AEAD_FAIL = "AEAD_FAIL"
        const val EPOCH_GONE = "EPOCH_GONE"
        const val NO_SESSION = "NO_SESSION"

        /** Alice's opening draws: the X3DH ephemeral, her epoch-1 pair, the first (v2) frame's IV. */
        val OPENING_ALICE: List<Draw> = listOf(Draw.Key(60), Draw.Key(61), Draw.Iv(62))

        /** Two Curve25519 points of order 8 (RFC 7748 §6.1's small-order set), as X25519 public keys. */
        const val LOW_ORDER_A = "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800"
        const val LOW_ORDER_B = "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157"

        const val LOW_ORDER_EPH_NOTE =
            "not normative: a port must refuse this frame with nothing committed and without feeding the reset " +
                "heuristic, but may name the drop BAD_HEADER or DECRYPT_FAILED (Kotlin's engine throws here and the " +
                "pipeline's catch records DECRYPT_FAILED)"

        const val FORMAT_NOTE =
            "dm and negative are lists of self-contained transcripts. parties: each party's identity (in identities), " +
                "the signed prekeys it holds privately (profiles.prekeys names) and pins (the peer prekey it seals " +
                "against). draws: every injected random value per party, in the order its engine consumes them — " +
                "keyPair = X25519 priv fixtureBytes(32, seed), iv = the v2 AES-GCM IV fixtureBytes(12, seed); v3 draws " +
                "no IV. steps run in order; each seal lists the draws it used (drawsUsed). A frame is a chat " +
                "RelayEnvelope (recipientId = the peer) whose ChatContent.enc is encEnvelope, signed by the sender " +
                "into wire. aad = aadText's bytes (MessageCrypto.header: id|sender|sentAt|recipient); v3 appends " +
                "headerBindingBytes(header) and derives its nonce (kats). plaintext is MessageContent.encode() for v2 and " +
                "MessageContentV2 for v3. An open uses the receiver's own now; expect is RatchetEngine.OpenOutcome's name " +
                "(OPENED on success) when layer is engine, REFUSED when InboundPipeline refuses it before or around the " +
                "engine; drop is the DropReason the pipeline records, and resetTriggering whether it feeds the reset " +
                "heuristic; dropNormative (true everywhere but where a case says otherwise, with a dropNote) says whether a port " +
                "must record that exact drop or only refuse the frame the same way. An open models the session layer only — " +
                "RatchetSessions.peekOpen then commitOpen — and nothing a production receiver does around it: IntroSync's init " +
                "answer, which a responder sends on first contact, is not listed. state is the actor's ratchet state for that " +
                "peer after the step: secrets as sha256 hex, " +
                "rootTag as the 16-hex local storage tag, skipped keys as contiguous index runs. Every step is rebuilt " +
                "and checked by RatchetVectorTest; pipelineReplay cases are replayed through InboundPipeline by " +
                "InboundPipelineTest, whose ratchet clock is the wall clock, so only a case whose outcome does not depend on " +
                "now may set pipelineReplay."
    }
}
