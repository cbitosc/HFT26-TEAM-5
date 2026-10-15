package app.getknit.knit.mesh.protocol

import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.InboundPipeline
import app.getknit.knit.mesh.crypto.AesGcm
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageContentV2
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.ratchet.RatchetCrypto
import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine
import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine.OpenOutcome
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions
import app.getknit.knit.mesh.crypto.ratchet.RatchetStore
import app.getknit.knit.mesh.crypto.sealBytes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.security.MessageDigest

/*
 * The machinery behind `ratchet-v1.json`'s `dm` and `negative` transcripts (companion change A9): two parties, each a
 * real [RatchetSessions] over an in-memory [RatchetStore], whose engines draw every key pair and v2 IV from a stated
 * plan of [fixtureBytes] seeds. Frames are built the way `MeshManager.sealEnvelopeFor` builds a DM — the row's content
 * through `sealBytes`, `RatchetSessions.sealDm`, a `chat` frame signed by the sender — and opened the way
 * `InboundPipeline.decryptAndDeliverV2` opens one: from the signed wire, peek then commit. So every byte is rebuilt
 * on every run, and the file can state only what the code does.
 */

/** One injected draw, in the order an engine consumes it. */
internal sealed class Draw(
    val seed: Int,
) {
    /** An X25519 pair: `priv = fixtureBytes(32, seed)`. */
    class Key(
        seed: Int,
    ) : Draw(seed)

    /** A v2 AES-GCM IV: `fixtureBytes(12, seed)`. */
    class Iv(
        seed: Int,
    ) : Draw(seed)
}

internal fun keyPairFrom(seed: Int): RatchetCrypto.KeyPair {
    val priv = fixtureBytes(RatchetCrypto.KEY_BYTES, seed)
    return RatchetCrypto.KeyPair(priv, RatchetCrypto.publicFromPrivate(priv))
}

/**
 * Pops [plan] in order and fails loudly on a draw of the wrong kind or a plan left unspent, so a change to the
 * engine's draw order cannot pass silently, in write mode as much as check mode.
 */
internal class DrawSource(
    private val owner: String,
    private val plan: List<Draw>,
) {
    private var taken = 0

    val count: Int get() = taken

    fun keyPair(): RatchetCrypto.KeyPair {
        val draw = next()
        check(draw is Draw.Key) { "$owner drew a key pair where its plan has an IV (seed ${draw.seed})" }
        return keyPairFrom(draw.seed)
    }

    fun iv(): ByteArray {
        val draw = next()
        check(draw is Draw.Iv) { "$owner drew an IV where its plan has a key pair (seed ${draw.seed})" }
        return fixtureBytes(AesGcm.IV_BYTES, draw.seed)
    }

    private fun next(): Draw {
        check(taken < plan.size) { "$owner drew more than its plan's ${plan.size} draws" }
        return plan[taken++]
    }

    fun assertSpent() = check(taken == plan.size) { "$owner consumed $taken of its ${plan.size} planned draws" }

    fun json(): JsonArray =
        buildJsonArray {
            plan.forEachIndexed { index, draw ->
                add(
                    buildJsonObject {
                        put("index", index)
                        put("seed", draw.seed)
                        when (draw) {
                            is Draw.Key -> {
                                val pair = keyPairFrom(draw.seed)
                                put("kind", "keyPair")
                                put("priv", pair.priv.toHex())
                                put("pub", pair.pub.toHex())
                            }

                            is Draw.Iv -> {
                                put("kind", "iv")
                                put("iv", fixtureBytes(AesGcm.IV_BYTES, draw.seed).toHex())
                            }
                        }
                    },
                )
            }
        }
}

/**
 * [RatchetStore] in memory, with `RatchetRepository`'s row semantics over `RatchetDao`: sessions, local epochs and
 * receive chains upsert (REPLACE), skipped keys insert-or-ignore on `(epoch, idx, rootTag)`, a consumed skipped key
 * goes by its own root's tag only, and local epochs list most recently minted first.
 */
internal class MemoryRatchetStore : RatchetStore {
    private val sessions = LinkedHashMap<String, RatchetEngine.SessionState>()
    private val localEpochs = LinkedHashMap<Pair<String, Int>, RatchetEngine.LocalEpoch>()
    private val recv = LinkedHashMap<Triple<String, Int, Long>, RatchetEngine.RecvEpoch>()
    private val skipped = LinkedHashMap<SkippedKeyId, RatchetEngine.SkippedKey>()

    private data class SkippedKeyId(
        val peerId: String,
        val epoch: Int,
        val idx: Int,
        val rootTag: Long,
    )

    override suspend fun session(peerId: String) = sessions[peerId]

    override suspend fun sessionPeerIds() = sessions.keys.toList()

    override suspend fun upsertSession(state: RatchetEngine.SessionState) {
        sessions[state.peerId] = state
    }

    override suspend fun localEpochPriv(
        peerId: String,
        epoch: Int,
    ): ByteArray? = localEpochs[peerId to epoch]?.priv

    override suspend fun lowestLocalEpoch(peerId: String): Int? = localEpochs.keys.filter { it.first == peerId }.minOfOrNull { it.second }

    override suspend fun recvEpochs(
        peerId: String,
        epoch: Int,
    ) = recv.filterKeys { it.first == peerId && it.second == epoch }.values.toList()

    override suspend fun skippedKeys(
        peerId: String,
        epoch: Int,
        idx: Int,
    ) = skipped.filterKeys { it.peerId == peerId && it.epoch == epoch && it.idx == idx }.values.toList()

    override suspend fun applyOpen(
        peerId: String,
        delta: RatchetEngine.OpenDelta,
        headerSe: Int,
        headerN: Int,
    ) {
        if (delta.purgePeerRecvState) purgePeerRecvState(peerId)
        sessions[peerId] = delta.session
        delta.recvEpoch?.let { recv[Triple(peerId, it.epoch, it.rootTag)] = it }
        for (key in delta.skippedInserts) skipped.putIfAbsent(SkippedKeyId(peerId, key.epoch, key.idx, key.rootTag), key)
        delta.consumedSkippedTag?.let { skipped.remove(SkippedKeyId(peerId, headerSe, headerN, it)) }
    }

    override suspend fun commitSend(
        state: RatchetEngine.SessionState,
        newLocalEpoch: RatchetEngine.LocalEpoch?,
    ) {
        sessions[state.peerId] = state
        newLocalEpoch?.let { localEpochs[state.peerId to it.epoch] = it }
    }

    override suspend fun purgePeerRecvState(peerId: String) {
        recv.keys.removeAll { it.first == peerId }
        skipped.keys.removeAll { it.peerId == peerId }
    }

    override suspend fun deletePeer(peerId: String) {
        sessions.remove(peerId)
        localEpochs.keys.removeAll { it.first == peerId }
        purgePeerRecvState(peerId)
    }

    override suspend fun debugLocalEpochs(peerId: String): List<Pair<Int, Long>> = localEpochsOf(peerId).map { it.epoch to it.createdAt }

    // Retention is a stage-3b transcript of its own (the pruned prekey and epoch cases need it); until then no
    // transcript may lean on a sweep this fake does not perform.
    override suspend fun sweep(now: Long): Unit = throw UnsupportedOperationException("no transcript sweeps yet")

    fun sessionOf(peerId: String): RatchetEngine.SessionState? = sessions[peerId]

    fun localEpochsOf(peerId: String): List<RatchetEngine.LocalEpoch> =
        localEpochs
            .filterKeys { it.first == peerId }
            .values
            .sortedWith(compareByDescending<RatchetEngine.LocalEpoch> { it.createdAt }.thenByDescending { it.epoch })

    fun recvOf(peerId: String): List<RatchetEngine.RecvEpoch> =
        recv.filterKeys { it.first == peerId }.values.sortedWith(compareBy({ it.epoch }, { it.rootTag }))

    fun skippedOf(peerId: String): List<RatchetEngine.SkippedKey> =
        skipped
            .filterKeys { it.peerId == peerId }
            .values
            .sortedWith(compareBy({ it.epoch }, { it.rootTag }, { it.idx }))
}

/** A signed prekey a transcript party holds (its private half) or pins for its peer (its public half). */
internal class VectorPrekey(
    val name: String,
    val id: Int,
    val keys: RatchetCrypto.KeyPair,
)

/** One side of a transcript: an identity, the prekeys it holds, its draw plan and its own session service. */
internal class TranscriptParty(
    val name: String,
    val identity: VectorParty,
    val holds: List<VectorPrekey>,
    plan: List<Draw>,
    /** The `identities` entry [identity] is. */
    val identityName: String = name,
) {
    val draws = DrawSource(name, plan)
    val store = MemoryRatchetStore()
    val sessions =
        RatchetSessions(
            store = store,
            dhIdentityPriv = { identity.agreementScalar },
            spkPrivFor = { id -> holds.firstOrNull { it.id == id }?.keys?.priv },
            engine = RatchetEngine(newKeyPair = draws::keyPair, newIv = draws::iv),
        )
    val nodeId: String get() = identity.nodeId
}

/** A DM frame as it went on the air: its signed wire bytes, and what built them. */
internal class TranscriptFrame(
    val step: Int,
    val sender: TranscriptParty,
    val env: RelayEnvelope,
    val enc: EncEnvelope,
    /** The content it carries, or null for a frame a test built by hand (a mutation). */
    val body: String?,
    /** What a mutation changed, for the file; null for a frame exactly as the sender sealed it. */
    val mutation: String? = null,
) {
    val wire: ByteArray = sender.identity.signedWire(env)

    /** The same frame with [enc] swapped in and re-signed by the sender — a buggy (never a forging) peer. */
    fun mutated(
        description: String,
        newEnc: EncEnvelope,
        group: GroupInfo? = null,
    ): TranscriptFrame {
        val content = WireCodec.decodePayload<ChatContent>(env.payload)!!.copy(enc = newEnc)
        val reAddressed =
            RelayEnvelope(
                type = env.type,
                id = env.id,
                senderId = env.senderId,
                sentAt = env.sentAt,
                recipientId = if (group == null) env.recipientId else null,
                group = group,
                payload = WireCodec.encodePayload(content),
            )
        return TranscriptFrame(step, sender, reAddressed, newEnc, body = null, mutation = description)
    }
}

/** Where a refusal happens, and what the pipeline records for it. */
internal enum class Layer { ENGINE, PIPELINE }

/**
 * One recorded transcript: the steps run as they are declared, each leaving a digest of its actor's state, and
 * [json] lays it out. A scenario that adds a step kind (a reset, a wipe, a ctl) adds a function here, and the
 * scenarios in `RatchetDmVectors` stay a list of calls.
 */
internal class Transcript(
    private val name: String,
    private val note: String,
    parties: List<TranscriptParty>,
) {
    private val parties = parties.associateBy { it.name }
    private val pins = HashMap<Pair<String, String>, VectorPrekey>()
    private val steps = mutableListOf<JsonObject>()
    private val caseFields = LinkedHashMap<String, JsonElement>()

    fun party(name: String): TranscriptParty = parties.getValue(name)

    /** [who] seals to [peer] against [prekey], the peer's pinned prekey (passed on every seal, as production does). */
    fun pin(
        who: String,
        peer: String,
        prekey: VectorPrekey,
    ) {
        pins[who to peer] = prekey
    }

    /** A case-level field for the file (the negative cases' summary). */
    fun field(
        key: String,
        value: JsonElement,
    ) {
        caseFields[key] = value
    }

    /** Seals [body] from [actor] to [peer] at [at] (the sender's clock: `sentAt` and the seal's `now` alike). */
    suspend fun seal(
        actor: String,
        peer: String,
        body: String,
        at: Long,
        v3: Boolean,
    ): TranscriptFrame {
        val from = party(actor)
        val to = party(peer)
        val id = frameIdFor("$name/${steps.size}")
        val aad = MessageCrypto.header(id, from.nodeId, at, to.nodeId)
        // MeshManager.sealEnvelopeFor: the row's sealed content, no inline acks, laid out for the peer's scheme.
        val (plaintext, scheme) = MessageContent(body = body).copy(acks = null).sealBytes(v3)
        val drawsBefore = from.draws.count
        val prekey = pins[actor to peer]
        val enc =
            checkNotNull(
                from.sessions.sealDm(
                    peerId = to.nodeId,
                    peerIkPub = to.identity.bundle.dhPublicKey(),
                    peerSpk = prekey?.let { RatchetEngine.PeerPrekey(it.id, it.keys.pub) },
                    plaintext = plaintext,
                    aad = aad,
                    now = at,
                    scheme = scheme,
                ),
            ) { "$name: $actor could not seal to $peer" }
        val env =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = from.nodeId,
                sentAt = at,
                recipientId = to.nodeId,
                payload = WireCodec.encodePayload(ChatContent(enc = enc)),
            )
        val frame = TranscriptFrame(steps.size, from, env, enc, body)
        steps +=
            buildJsonObject {
                put("step", steps.size)
                put("actor", actor)
                put("op", "seal")
                put("peer", peer)
                put("now", at)
                put("peerPrekey", prekey?.name)
                putJsonArray("drawsUsed") { for (i in drawsBefore until from.draws.count) add(i) }
                put("contentBody", body)
                put("plaintextSchema", if (scheme == EncEnvelope.VERSION_DM_V3) "MessageContentV2" else "MessageContent")
                put("plaintext", plaintext.toHex())
                frameFields(frame)
                put("state", stateJson(from, to))
            }
        return frame
    }

    /**
     * Opens [frame] as [actor], the way `InboundPipeline.decryptAndDeliverV2` does — from the signed wire, the v1
     * header as AAD, the nonce dropped for v3, a lock-free peek and, when it opens, the commit — and asserts the
     * outcome is [expect] (an `OpenOutcome` name, `OPENED` for success).
     */
    suspend fun open(
        actor: String,
        frame: TranscriptFrame,
        at: Long,
        expect: String,
    ) {
        val me = party(actor)
        val sender = frame.sender
        val wire = checkNotNull(WireCodec.decodeWire(frame.wire))
        assertTrue("$name: the frame verifies", MessageCrypto.verify(sender.identity.bundle, wire.sig, wire.signed))
        val env = checkNotNull(WireCodec.decodeEnvelope(wire.signed))
        val enc = checkNotNull(WireCodec.decodePayload<ChatContent>(env.payload)?.enc)
        val header = checkNotNull(enc.r)
        val aad = MessageCrypto.header(env.id, env.senderId, env.sentAt, env.recipientId.orEmpty())
        val v3 = enc.v == EncEnvelope.VERSION_DM_V3
        val nonce = enc.nonce.takeIf { !v3 }
        val peerIk = sender.identity.bundle.dhPublicKey()
        val outcome = me.sessions.peekOpen(me.nodeId, env.senderId, peerIk, header, nonce, enc.ct, aad, at)
        var plaintext: ByteArray? = null
        if (outcome is OpenOutcome.Opened) {
            assertTrue(
                "$name: commits what it peeked",
                me.sessions.commitOpen(me.nodeId, env.senderId, peerIk, header, nonce, enc.ct, aad, at) {},
            )
            plaintext = outcome.plaintext
            val decoded = if (v3) MessageContentV2.decode(outcome.plaintext) else MessageContent.decode(outcome.plaintext)
            frame.body?.let { assertEquals("$name: the body survives", it, decoded?.body) }
        }
        val name = outcomeName(outcome)
        assertEquals("$name step ${steps.size}: the stated outcome", expect, name)
        steps +=
            openStep(actor, sender.name, frame, at, Layer.ENGINE, name, dropFor(outcome)) {
                put("plaintext", plaintext?.toHex())
                put("state", stateJson(me, sender))
            }
    }

    /**
     * A frame refused above the engine, by `InboundPipeline` (a nonce of the wrong shape for its version, a v3 frame
     * addressed to a group, a key the X25519 layer refuses to use). The file states the drop the pipeline records;
     * `InboundPipelineTest` replays it there. [atSessions] checks what the session layer itself does with it, if
     * anything is worth checking, and the actor's state is recorded after it.
     */
    suspend fun refusedByPipeline(
        actor: String,
        frame: TranscriptFrame,
        at: Long,
        drop: DropReason,
        // False where a port may name the drop differently (the refusal itself — no commit, no reset — is normative).
        dropNormative: Boolean = true,
        dropNote: String? = null,
        atSessions: (suspend (TranscriptParty) -> Unit)? = null,
    ) {
        val me = party(actor)
        atSessions?.invoke(me)
        steps +=
            openStep(actor, frame.sender.name, frame, at, Layer.PIPELINE, "REFUSED", drop, dropNormative) {
                dropNote?.let { put("dropNote", it) }
                put("plaintext", JsonNull)
                put("state", stateJson(me, frame.sender))
            }
    }

    /**
     * [actor] seals [count] v3 frames to [peer] at [at] that the file does not list: the chain advances and nothing
     * else moves (v3 draws nothing, and the AAD reaches only the ciphertext). A port replays it as [count] seals
     * of any content.
     */
    suspend fun advance(
        actor: String,
        peer: String,
        count: Int,
        at: Long,
    ) {
        val from = party(actor)
        val to = party(peer)
        val drawsBefore = from.draws.count
        val startN = from.store.session(to.nodeId)?.sendCount
        repeat(count) { i ->
            val id = frameIdFor("$name/${steps.size}/$i")
            val (plaintext, scheme) = MessageContent(body = "filler $i").sealBytes(v3 = true)
            check(scheme == EncEnvelope.VERSION_DM_V3)
            checkNotNull(
                from.sessions.sealDm(
                    to.nodeId,
                    to.identity.bundle.dhPublicKey(),
                    pins[actor to peer]?.let { RatchetEngine.PeerPrekey(it.id, it.keys.pub) },
                    plaintext,
                    MessageCrypto.header(id, from.nodeId, at, to.nodeId),
                    at,
                    scheme,
                ),
            )
        }
        check(from.draws.count == drawsBefore) { "$name: an advance must not draw (it would start an epoch unlisted)" }
        steps +=
            buildJsonObject {
                put("step", steps.size)
                put("actor", actor)
                put("op", "advance")
                put("peer", peer)
                put("now", at)
                put("count", count)
                put("fromN", startN)
                put("scheme", "v3")
                put(
                    "note",
                    "seal $count v3 frames of any content and discard them: they advance the send chain from n $startN " +
                        "and move nothing else",
                )
                put("state", stateJson(from, to))
            }
    }

    private fun openStep(
        actor: String,
        peer: String,
        frame: TranscriptFrame,
        at: Long,
        layer: Layer,
        outcome: String,
        drop: DropReason?,
        dropNormative: Boolean = true,
        rest: JsonObjectBuilder.() -> Unit,
    ): JsonObject =
        buildJsonObject {
            put("step", steps.size)
            put("actor", actor)
            put("op", "open")
            put("peer", peer)
            put("now", at)
            put("opens", frame.step)
            put("mutation", frame.mutation)
            put("layer", layer.name.lowercase())
            put("expect", outcome)
            put("drop", drop?.name)
            put("dropNormative", dropNormative)
            put("resetTriggering", drop != null && drop in InboundPipeline.RESET_TRIGGERING_DROPS)
            frameFields(frame)
            rest()
        }

    fun json(): JsonObject {
        for (p in parties.values) p.draws.assertSpent()
        return buildJsonObject {
            put("name", name)
            put("note", note)
            for ((key, value) in caseFields) put(key, value)
            putJsonObject("parties") {
                for (p in parties.values) {
                    putJsonObject(p.name) {
                        put("identity", p.identityName)
                        put("nodeId", p.nodeId)
                        putJsonArray("holdsPrekeys") { p.holds.forEach { add(it.name) } }
                        putJsonObject("pins") {
                            for ((key, prekey) in pins) if (key.first == p.name) put(key.second, prekey.name)
                        }
                    }
                }
            }
            putJsonObject("draws") { for (p in parties.values) put(p.name, p.draws.json()) }
            put("steps", JsonArray(steps))
        }
    }

    private fun JsonObjectBuilder.frameFields(frame: TranscriptFrame) {
        val enc = frame.enc
        put("frameId", frame.env.id)
        put("sentAt", frame.env.sentAt)
        val thread = frame.env.recipientId.orEmpty()
        val aadText = "${frame.env.id}|${frame.env.senderId}|${frame.env.sentAt}|$thread"
        put("aadText", aadText)
        put("aad", aadText.encodeToByteArray().toHex())
        put("scheme", if (enc.v == EncEnvelope.VERSION_DM_V3) "v3" else "v${enc.v}")
        put("v", enc.v)
        enc.r?.let { put("header", headerJson(it)) }
        put("nonce", enc.nonce.toHex())
        put("ct", enc.ct.toHex())
        put("encEnvelope", WireCodec.encodePayload(enc).toHex())
        frame.env.group?.let { put("groupId", it.id) }
        put("wire", frame.wire.toHex())
    }

    private fun headerJson(r: RatchetHeader) =
        buildJsonObject {
            put("se", r.se)
            put("ek", r.ek.toHex())
            put("pe", r.pe)
            put("n", r.n)
            put("flags", r.flags)
            val init = r.init
            if (init == null) {
                put("init", JsonNull)
            } else {
                putJsonObject("init") {
                    put("eph", init.eph.toHex())
                    put("pkid", init.pkid)
                    put("at", init.at)
                }
            }
        }

    private fun stateJson(
        who: TranscriptParty,
        peer: TranscriptParty,
    ): JsonObject =
        buildJsonObject {
            put("of", who.name)
            put("peer", peer.name)
            put("session", who.store.sessionOf(peer.nodeId)?.let(::sessionJson) ?: JsonNull)
            putJsonArray("recvEpochs") {
                for (row in who.store.recvOf(peer.nodeId)) {
                    add(
                        buildJsonObject {
                            put("epoch", row.epoch)
                            put("rootTag", "%016x".format(row.rootTag))
                            put("next", row.next)
                            put("chainKeySha256", sha256(row.chainKey))
                            put("lastUsedAt", row.lastUsedAt)
                        },
                    )
                }
            }
            val skipped = who.store.skippedOf(peer.nodeId)
            put("skippedKeyCount", skipped.size)
            put("skippedKeys", skippedRuns(skipped))
            putJsonArray("localEpochs") {
                for (epoch in who.store.localEpochsOf(peer.nodeId)) {
                    add(
                        buildJsonObject {
                            put("epoch", epoch.epoch)
                            put("createdAt", epoch.createdAt)
                            put("pub", epoch.pub.toHex())
                        },
                    )
                }
            }
        }

    companion object {
        /** A frame id as `FrameId.new` mints one (16 bytes, base64url), drawn from a hash of [label]. */
        fun frameIdFor(label: String): String =
            FrameId.fromBytes(MessageDigest.getInstance("SHA-256").digest("ratchet-v1/$label".encodeToByteArray()).copyOf(16))

        fun sha256(bytes: ByteArray?): String? = bytes?.let { MessageDigest.getInstance("SHA-256").digest(it).toHex() }

        fun outcomeName(outcome: OpenOutcome): String =
            when (outcome) {
                is OpenOutcome.Opened -> "OPENED"
                is OpenOutcome.Failed -> outcome.name
            }

        /** `InboundPipeline.onRatchetFailure`'s mapping; null for a frame that opened. */
        fun dropFor(outcome: OpenOutcome): DropReason? =
            when (outcome) {
                is OpenOutcome.Opened -> null
                OpenOutcome.Failed.NO_SESSION -> DropReason.RATCHET_NO_SESSION
                OpenOutcome.Failed.EPOCH_GONE -> DropReason.RATCHET_EPOCH_GONE
                OpenOutcome.Failed.DUPLICATE -> DropReason.RATCHET_DUPLICATE
                OpenOutcome.Failed.BAD_HEADER -> DropReason.RATCHET_BAD_HEADER
                OpenOutcome.Failed.AEAD_FAIL -> DropReason.RATCHET_AEAD_FAIL
            }

        private fun sessionJson(s: RatchetEngine.SessionState) =
            buildJsonObject {
                put("rootSha256", sha256(s.root))
                put("confirmed", s.confirmed)
                put("weAreInitiator", s.weAreInitiator)
                put("establishedAt", s.establishedAt)
                put("initEphPub", s.initEphPub?.toHex())
                put("initPkid", s.initPkid)
                put("peerInitEphPub", s.peerInitEphPub?.toHex())
                put("peerBasePub", s.peerBasePub?.toHex())
                put("peerBaseEpoch", s.peerBaseEpoch)
                put("sendEpoch", s.sendEpoch)
                put("sendEpochPub", s.sendEpochPub?.toHex())
                put("sendChainKeySha256", sha256(s.sendChainKey))
                put("sendCount", s.sendCount)
                put("sendEpochStartedAt", s.sendEpochStartedAt)
                put("sendEpochBaseEpoch", s.sendEpochBaseEpoch)
                put("sendEpochExportSha256", sha256(s.sendEpochExport))
                put("highestPeAcked", s.highestPeAcked)
                put("prevRootSha256", sha256(s.prevRoot))
                put("prevRootWeAreInitiator", s.prevRootWeAreInitiator)
                put("prevRootExpiresAt", s.prevRootExpiresAt)
                put("lastResetSentAt", s.lastResetSentAt)
            }

        /** Skipped keys as contiguous runs per `(epoch, rootTag)`: a 196-key gap is one entry, not 196. */
        private fun skippedRuns(keys: List<RatchetEngine.SkippedKey>): JsonArray =
            buildJsonArray {
                for ((group, members) in keys.groupBy { it.epoch to it.rootTag }) {
                    var start = members.first().idx
                    var last = start

                    fun flush() =
                        add(
                            buildJsonObject {
                                put("epoch", group.first)
                                put("rootTag", "%016x".format(group.second))
                                put("fromIdx", start)
                                put("toIdx", last)
                            },
                        )
                    for (key in members.drop(1)) {
                        if (key.idx != last + 1) {
                            flush()
                            start = key.idx
                        }
                        last = key.idx
                    }
                    flush()
                }
            }
    }
}
