package app.getknit.knit.mesh.protocol

import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.SeenSet
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine
import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine.OpenOutcome
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions.ResetCause
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertTrue

/**
 * Stage 3b of `ratchet-v1.json`'s `dm` transcripts (companion change A9): the ctl plane and the unsigned door
 * ([ctlPlane], replayed through `InboundPipeline` by `InboundPipelineTest`), the both-initiate races, resets and
 * their drain windows, and the re-seal pair. Appended to [RatchetDmVectors.scenarios] after stage 3a's six, which do
 * not move. Every one records `floorsBefore` on each step: the in-memory floors `RatchetSessions` decides resets and
 * replacements by. New draws take seeds 70 and up; a scenario that replays 3a's opening reuses its seeds (60-64), so
 * those steps are byte-identical to 3a's.
 */
internal class RatchetSessionVectors(
    private val alice: VectorParty,
    private val bob: VectorParty,
    private val aliceSpk1: VectorPrekey,
    private val bobSpk1: VectorPrekey,
    private val t0: Long,
) {
    private fun transcript(
        name: String,
        note: String,
        alicePlan: List<Draw>,
        bobPlan: List<Draw>,
    ) = Transcript(
        name,
        note,
        listOf(
            TranscriptParty("alice", alice, listOf(aliceSpk1), alicePlan),
            TranscriptParty("bob", bob, listOf(bobSpk1), bobPlan),
        ),
        recordFloors = true,
    ).apply {
        pin("alice", "bob", bobSpk1)
        pin("bob", "alice", aliceSpk1)
    }

    suspend fun scenarios(): List<JsonObject> =
        listOf(
            ctlPlane(),
            raceWinnerArrivesFirst(),
            raceLoserArrivesFirst(),
            lateLoserOpening(),
            reRootReset(),
            confirmEra(),
            resetPlans(),
            resealNewFirst(),
            resealOldFirst(),
        )

    // ---- the ctl plane and the unsigned door --------------------------------------------------------------

    private suspend fun ctlPlane(): JsonObject {
        val bobSent = (1..5).map { Transcript.frameIdFor("ctlPlane/bob-sent/$it") }
        return transcript(
            "ctlPlane",
            "Alice's machinery to Bob, all before she ever opens a frame of his (so every frame still carries her init, " +
                "and a pipeline replay as Bob reaches each one): a sealed CTL_RECEIPT with one ack and one with an acks " +
                "batch (docs/ENCRYPTED_RECEIPTS_REACTIONS.md, ADR 018), a CTL_REACTION and its retraction, a sealed " +
                "CTL_PROFILE (docs/SEALED_PROFILE_UPDATES.md), a plain DM carrying inline acks (CAP_INLINE_ACK, ADR 054), " +
                "the unsigned v3 live-link tick (ADR 059, accepted) and an unsigned plain DM (refused by the pipeline " +
                "before commit, UNSIGNED_REFUSED) whose signed twin then opens. The ids Alice acks and reacts to are " +
                "Bob's own DMs to her (fixtures.bobSent); `applies` on each open is what InboundPipeline does with it, " +
                "and InboundPipelineTest replays the scenario to check it. Bob's own receipts for her plain DMs are his " +
                "outbound frames, sealed from his own randomness, and are not listed.",
            RatchetDmVectors.OPENING_ALICE,
            emptyList(),
        ).apply {
            field("pipelineReplay", JsonPrimitive(true))
            field(
                "fixtures",
                buildJsonObject {
                    putJsonArray("bobSent") {
                        for (id in bobSent) {
                            add(
                                buildJsonObject {
                                    put("id", id)
                                    put("sender", "bob")
                                    put("recipient", "alice")
                                },
                            )
                        }
                    }
                },
            )
            val first = seal("alice", "bob", "Hi Bob", t0, v3 = false)
            open("bob", first, t0 + 5 * SECOND, OPENED, applies = message(first))
            val second = seal("alice", "bob", "Are you there?", t0 + SECOND, v3 = true)
            open("bob", second, t0 + 6 * SECOND, OPENED, applies = message(second))
            ctlFrames(bobSent)
            unsignedDoor(bobSent)
        }.json()
    }

    private suspend fun Transcript.ctlFrames(bobSent: List<String>) {
        // InboundPipeline.sealDmReceipt / MeshManager.sealDeliveryTickEnvelope: one id rides `ack`, a batch `acks`.
        val single = ctl(MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, ack = bobSent[0]), 10)
        open("bob", single, t0 + 11 * SECOND, OPENED, applies = receipts(bobSent[0]))
        val batch = ctl(MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, acks = bobSent.subList(1, 3)), 20)
        open("bob", batch, t0 + 21 * SECOND, OPENED, applies = receipts(bobSent[1], bobSent[2]))
        // MeshManager.sealReaction: the DM form; a retraction is the same payload with no emoji.
        val reaction = ctl(MessageContent(body = "", ctl = MessageContent.CTL_REACTION, rp = ReactionPayload(bobSent[0], THUMBS_UP)), 30)
        open("bob", reaction, t0 + 31 * SECOND, OPENED, applies = reaction(bobSent[0], THUMBS_UP, reaction.env.sentAt))
        val retraction = ctl(MessageContent(body = "", ctl = MessageContent.CTL_REACTION, rp = ReactionPayload(bobSent[0], null)), 40)
        open("bob", retraction, t0 + 41 * SECOND, OPENED, applies = reaction(bobSent[0], null, retraction.env.sentAt))
        // MeshManager.sendCtlDm: the avatar hash (none here) also rides the cleartext ChatContent.attachmentHash.
        val pr = ProfilePayload(name = "Alice", status = "Sealed and delivered", version = t0 + 50 * SECOND, openToChat = true)
        val profile = ctl(MessageContent(body = "", ctl = MessageContent.CTL_PROFILE, pr = pr), 50)
        open("bob", profile, t0 + 51 * SECOND, OPENED, applies = profile(pr))
        // MeshManager.sendChat with inline acks (ADR 054): a plain DM whose `acks` the receiver applies as receipts.
        val inline = sealContent("alice", "bob", MessageContent(body = "Got them", acks = listOf(bobSent[3])), t0 + 60 * SECOND, v3 = true)
        open(
            "bob",
            inline,
            t0 + 61 * SECOND,
            OPENED,
            applies = message(inline, receipts = listOf(bobSent[3])),
            note = "the receiver applies the inline acks as receipts in the same commit as the row, then acks the DM itself",
        )
    }

    private suspend fun Transcript.unsignedDoor(bobSent: List<String>) {
        // MeshManager.flushTick: a v3 tick to a peer on a live link goes unsigned (`unsigned(tick.env)`).
        val tick =
            sealContent(
                "alice",
                "bob",
                MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, ack = bobSent[4]),
                t0 + 70 * SECOND,
                v3 = true,
                SealOptions(signed = false, note = "the unsigned live-link tick: relay = false, an empty sig"),
            )
        open("bob", tick, t0 + 71 * SECOND, OPENED, applies = receipts(bobSent[4]))
        val plain =
            sealContent(
                "alice",
                "bob",
                MessageContent(body = "Not a tick"),
                t0 + 80 * SECOND,
                v3 = true,
                SealOptions(signed = false, note = "a plain DM in the unsigned form (a stripped signature)"),
            )
        refusedByPipeline("bob", plain, t0 + 81 * SECOND, DropReason.UNSIGNED_REFUSED) { bob ->
            // The session layer opens it: the pipeline's refusal comes after the peek and before the commit
            // (InboundPipeline.unsignedButNotATick), so the chain index stays unspent for the signed twin.
            val enc = plain.enc
            val aad = MessageCrypto.header(plain.env.id, plain.env.senderId, plain.env.sentAt, plain.env.recipientId.orEmpty())
            val peek = bob.sessions.peekOpen(bob.nodeId, alice.nodeId, alice.bundle.dhPublicKey(), enc.r!!, null, enc.ct, aad, t0)
            assertTrue("the unsigned plain DM opens at the session layer", peek is OpenOutcome.Opened)
        }
        val twin = plain.signedCopy()
        open(
            "bob",
            twin,
            t0 + 81 * SECOND + SeenSet.DEFAULT_TTL_MS + SECOND,
            OPENED,
            applies = message(twin),
            note =
                "the signed twin opens on the unspent index. It arrives past SeenSet.DEFAULT_TTL_MS after the refused " +
                    "copy: the router marked the id seen before the pipeline refused it (an UNSIGNED_REFUSED does not " +
                    "reopen it), so a twin inside that window is absorbed as deduped and never reaches the pipeline",
        )
    }

    private suspend fun Transcript.ctl(
        content: MessageContent,
        atSeconds: Long,
    ): TranscriptFrame = sealContent("alice", "bob", content, t0 + atSeconds * SECOND, v3 = true)

    // ---- both-initiate races (ADR 2026-09.pz9g; FSR §7) -----------------------------------------------------

    private val raceAlice = listOf(Draw.Key(70), Draw.Key(71), Draw.Iv(72), Draw.Key(73))
    private val raceBob = listOf(Draw.Key(74), Draw.Key(75), Draw.Iv(76), Draw.Key(77))

    private class RaceFrames(
        val a0: TranscriptFrame,
        val b0: TranscriptFrame,
    )

    /** Both sides initiate before either hears the other: Alice (the smaller node id, so the winner) and Bob. */
    private suspend fun Transcript.bothInitiate(): RaceFrames =
        RaceFrames(
            seal("alice", "bob", "Hi Bob", t0, v3 = false),
            seal("bob", "alice", "Hi Alice", t0 + SECOND / 2, v3 = false),
        )

    /** Bob answers under Alice's root, Alice confirms on it, and her next frame (no init) reaches Bob. */
    private suspend fun Transcript.settle(from: Long) {
        val b1 = seal("bob", "alice", "On your root now", from, v3 = true)
        open("alice", b1, from + SECOND, OPENED, note = "the loser's answer under the winner's root confirms the winner")
        val a1 = seal("alice", "bob", "Confirmed", from + 2 * SECOND, v3 = true)
        open("bob", a1, from + 3 * SECOND, OPENED)
    }

    private suspend fun raceWinnerArrivesFirst(): JsonObject =
        transcript(
            "raceWinnerArrivesFirst",
            "A both-initiate race, the winner's init first (ADR 2026-09.pz9g, FSR §7): the init whose initiator has the " +
                "smaller node id wins, never the earlier stamp. Alice's id sorts first. Bob, holding his own unconfirmed " +
                "init, opens hers and adopts her root (his own drains as prevRoot, his receive state is purged, and the " +
                "adoption starts his replacement floor: lastReplacementAt). Alice then opens Bob's opening DM under his " +
                "root as a read-only candidate: it opens, and her session stays unconfirmed on her own root.",
            raceAlice,
            raceBob,
        ).apply {
            val race = bothInitiate()
            open("bob", race.a0, t0 + 2 * SECOND, OPENED, note = "the loser adopts the winner's root")
            open("alice", race.b0, t0 + 3 * SECOND, OPENED, note = "the winner reads the loser's DM under the loser's root, read-only")
            settle(t0 + 4 * SECOND)
        }.json()

    private suspend fun raceLoserArrivesFirst(): JsonObject =
        transcript(
            "raceLoserArrivesFirst",
            "The same race, the loser's init first (ADR 2026-09.pz9g): Alice keeps her root and reads Bob's opening DM " +
                "read-only (it records his init's ephemeral, so a re-serve is inert), then Bob adopts her root on her " +
                "frame. The frames are raceWinnerArrivesFirst's, byte for byte; only the arrival order differs, and both " +
                "orders end on Alice's root.",
            raceAlice,
            raceBob,
        ).apply {
            val race = bothInitiate()
            open("alice", race.b0, t0 + 2 * SECOND, OPENED, note = "the winner keeps its root and reads the loser's DM read-only")
            open("bob", race.a0, t0 + 3 * SECOND, OPENED, note = "the loser adopts the winner's root")
            settle(t0 + 4 * SECOND)
        }.json()

    private suspend fun lateLoserOpening(): JsonObject =
        transcript(
            "lateLoserOpening",
            "#83: Bob lost the race and answered under Alice's root before his own opening DM reached her, so Alice " +
                "confirmed without ever resolving his init. His DM arrives late: the remnant guard refuses to adopt its " +
                "init (a confirmed initiator with no peer init resolved, the init from the side that loses the tiebreak, " +
                "no FLAG_RESET), and the loser's root is offered as a trailing read-only candidate. The DM opens and " +
                "records nothing — not even the init's ephemeral — and its re-serve is DUPLICATE on the row it stored.",
            raceAlice,
            raceBob,
        ).apply {
            val race = bothInitiate()
            open("bob", race.a0, t0 + 2 * SECOND, OPENED, note = "the loser adopts the winner's root")
            settle(t0 + 4 * SECOND)
            open(
                "alice",
                race.b0,
                t0 + 10 * SECOND,
                OPENED,
                note = "the late loser's opening DM, read under its own root and never anchored",
            )
            open(
                "alice",
                race.b0,
                t0 + 10 * SECOND + SeenSet.DEFAULT_TTL_MS + SECOND,
                DUPLICATE,
                note =
                    "a re-serve past SeenSet.DEFAULT_TTL_MS (inside it the router absorbs the id). Session layer only: on a " +
                        "whole node the row the first open wrote makes the exists-gate skip this copy; DUPLICATE is what " +
                        "RatchetSessions answers for a re-serve with no row (remnantCandidates' KDoc)",
            )
        }.json()

    // ---- resets (ADR 023, 027, 2026-09.qerd, 2026-10.gcrd, 2026-09.pz9g) ------------------------------------

    /** Stage 3a's `confirm` exchange, byte for byte (seeds 60-64): Alice in epoch 2 on Bob's epoch 1, both confirmed. */
    private suspend fun Transcript.confirmed() {
        val first = seal("alice", "bob", "Hi Bob", t0, v3 = false)
        val second = seal("alice", "bob", "Are you there?", t0 + SECOND, v3 = true)
        open("bob", first, t0 + 5 * SECOND, OPENED)
        open("bob", second, t0 + 6 * SECOND, OPENED)
        val reply = seal("bob", "alice", "Hi Alice", t0 + 10 * SECOND, v3 = true)
        open("alice", reply, t0 + 12 * SECOND, OPENED)
        val next = seal("alice", "bob", "Good to hear from you", t0 + 15 * SECOND, v3 = true)
        open("bob", next, t0 + 16 * SECOND, OPENED)
    }

    private val confirmAlice = RatchetDmVectors.OPENING_ALICE + Draw.Key(64)
    private val confirmBob = listOf(Draw.Key(63))

    private suspend fun reRootReset(): JsonObject =
        transcript(
            "reRootReset",
            "After confirm, Alice seals three more old-era frames (her epoch 2, n 1..3) that are slow to arrive, then " +
                "asks for a reset (ON_DEMAND): a fresh X3DH init flagged FLAG_RESET, her numbering restarted at epoch 1, " +
                "her own receive state purged, the old root kept as prevRoot (ADR 023, ADR 027). The reset seals v2 " +
                "though the pair reads v3 — resets stay v2 (ADR 059, FSR §5). Bob adopts it (his replacement floor " +
                "starts; his old root drains as prevRoot until prevRootExpiresAt = his open + PREV_ROOT_TTL_MS). n 1 " +
                "drains on the old root and stores its chain under the old root's tag; the new era's epoch 2 then opens " +
                "on a fresh chain beside it, though the number is the same (#135, ADR 2026-10.gcrd). n 2 opens 1 ms " +
                "before Bob's prevRootExpiresAt; n 3 at it no longer does (AEAD_FAIL).",
            confirmAlice + listOf(Draw.Key(78), Draw.Key(79), Draw.Iv(80), Draw.Key(82)),
            confirmBob + Draw.Key(81),
        ).apply {
            confirmed()
            val old = (1..3).map { seal("alice", "bob", "old era $it", t0 + (19 + it) * SECOND, v3 = true) }
            val reset = checkNotNull(reset("alice", "bob", t0 + 30 * SECOND, ResetCause.ON_DEMAND, REROOT, decidedBy = NO_FLOOR))
            val adoptedAt = t0 + 40 * SECOND
            open("bob", reset, adoptedAt, OPENED, note = "FLAG_RESET init adopted: no replacement from Alice in the last minute")
            open(
                "bob",
                old[0],
                t0 + 41 * SECOND,
                OPENED,
                note =
                    "an old-era frame drains under prevRoot. Re-deriving Alice's old epoch 2 after the purge banks a skipped " +
                        "key for idx 0, which Bob already consumed before the reset, so a row-less re-serve of that frame " +
                        "would open once more inside the drain window: the cost ADR 2026-10.gcrd accepts, not a bug",
            )
            val n1 = seal("bob", "alice", "new era", t0 + 50 * SECOND, v3 = true)
            open("alice", n1, t0 + 51 * SECOND, OPENED, note = "Bob answered under the new root: Alice confirms")
            val n2 = seal("alice", "bob", "new era, epoch 2", t0 + 52 * SECOND, v3 = true)
            open("bob", n2, t0 + 53 * SECOND, OPENED, note = "#135: epoch 2 again, under the new root, beside the old root's epoch-2 row")
            val expiresAt = adoptedAt + RatchetEngine.PREV_ROOT_TTL_MS
            open("bob", old[1], expiresAt - 1, OPENED, note = "1 ms inside the drain window")
            open("bob", old[2], expiresAt, AEAD_FAIL, note = "at prevRootExpiresAt the old root is no longer a candidate")
        }.json()

    private suspend fun confirmEra(): JsonObject =
        transcript(
            "confirmEra",
            "#87 (ADR 2026-09.pz9g): after confirm, Bob seals a frame on his epoch 2 (against Alice's old epoch 2) and " +
                "Alice resets before it arrives. It opens under her prevRoot — her old epoch 2's key survives the reset, " +
                "which re-minted only epoch 1 — but a frame drained under any root but the active one moves no era, so " +
                "her replacement session stays unconfirmed and her next frame still carries the reset's init (flags 0 " +
                "on a follow-up: only the reset frame itself is flagged). Bob adopts the reset, reads the follow-up on " +
                "the same init, and his first new-era frame confirms her.",
            confirmAlice + listOf(Draw.Key(84), Draw.Key(85), Draw.Iv(86)),
            confirmBob + listOf(Draw.Key(83), Draw.Key(87)),
        ).apply {
            confirmed()
            val before = seal("bob", "alice", "before your reset", t0 + 20 * SECOND, v3 = true)
            val reset = checkNotNull(reset("alice", "bob", t0 + 30 * SECOND, ResetCause.ON_DEMAND, REROOT, decidedBy = NO_FLOOR))
            open("alice", before, t0 + 35 * SECOND, OPENED, note = "#87: opens under prevRoot and leaves the new session unconfirmed")
            val followUp = seal("alice", "bob", "after my reset", t0 + 36 * SECOND, v3 = true)
            open("bob", reset, t0 + 40 * SECOND, OPENED)
            open("bob", followUp, t0 + 41 * SECOND, OPENED, note = "the same init again: resolved idempotently by its ephemeral")
            val answer = seal("bob", "alice", "new era", t0 + 45 * SECOND, v3 = true)
            open("alice", answer, t0 + 46 * SECOND, OPENED, note = "a fresh epoch under the active root: confirms")
        }.json()

    private suspend fun resetPlans(): JsonObject =
        transcript(
            "resetPlans",
            "RatchetSessions.sealResetDm never seals a reset the peer would refuse (ADR 2026-09.qerd, #85/#86). " +
                "Against Alice's own pending plain init it seals the Mark: the reset marker under the init she already " +
                "has out, its header flagged FLAG_RESET, no new root, stamping lastResetSentAt. A second ask inside " +
                "RESET_REPLACEMENT_MIN_INTERVAL_MS (1 min) of that stamp is declined. Once Bob's answer confirms her " +
                "init (confirmedAt), an UNREADABLE ask inside that minute is declined; at a minute it re-roots, and Bob " +
                "— with no replacement from her before — adopts it.",
            listOf(Draw.Key(88), Draw.Key(89), Draw.Iv(90), Draw.Iv(91), Draw.Key(93), Draw.Key(94), Draw.Iv(95)),
            listOf(Draw.Key(92)),
        ).apply {
            val a0 = seal("alice", "bob", "Hi Bob", t0, v3 = false)
            val mark =
                checkNotNull(
                    reset(
                        "alice",
                        "bob",
                        t0 + SECOND,
                        ResetCause.ON_DEMAND,
                        MARK,
                        decidedBy =
                            "state.session: our own init pending (weAreInitiator, !confirmed, initPkid = the pinned prekey) " +
                                "and plain (lastResetSentAt < establishedAt)",
                        note = "our own init is pending and plain (session.lastResetSentAt 0 < establishedAt): the Mark",
                    ),
                )
            reset(
                "alice",
                "bob",
                t0 + 11 * SECOND,
                ResetCause.ON_DEMAND,
                DECLINED,
                decidedBy = "state.session.lastResetSentAt (persisted): now - it < RESET_REPLACEMENT_MIN_INTERVAL_MS on a pending init",
                note = "10 s after the Mark: inside the 1 min floor",
            )
            open("bob", a0, t0 + 20 * SECOND, OPENED)
            open("bob", mark, t0 + 21 * SECOND, OPENED, note = "the Mark's init is the one Bob holds: idempotent, no purge")
            answeredInitPlans()
        }.json()

    /** resetPlans' second half: Bob answers Alice's init, and the answered-init floor decides her next two asks. */
    private suspend fun Transcript.answeredInitPlans() {
        val b1 = seal("bob", "alice", "Hi Alice", t0 + 30 * SECOND, v3 = true)
        val confirmedAt = t0 + 31 * SECOND
        open("alice", b1, confirmedAt, OPENED, note = "Bob answered our init: confirmedAt is stamped")
        reset(
            "alice",
            "bob",
            confirmedAt + 30 * SECOND,
            ResetCause.UNREADABLE,
            DECLINED,
            decidedBy = ANSWERED_FLOOR,
            note = "30 s after confirmedAt",
        )
        val reRoot =
            checkNotNull(
                reset(
                    "alice",
                    "bob",
                    confirmedAt + RatchetSessions.RESET_REPLACEMENT_MIN_INTERVAL_MS,
                    ResetCause.UNREADABLE,
                    REROOT,
                    decidedBy = ANSWERED_FLOOR,
                    note = "exactly 1 min after confirmedAt: outside the floor",
                ),
            )
        open("bob", reRoot, confirmedAt + 65 * SECOND, OPENED, note = "adopted: Bob has no replacement from Alice to floor it")
    }

    // ---- the re-seal pair (MeshManager.resealAndFlood; ADR 2026-09.qerd, #131) -------------------------------

    private val resealAlice = confirmAlice + Draw.Key(99)
    private val resealBob = confirmBob + listOf(Draw.Key(96), Draw.Key(97), Draw.Iv(98))

    private suspend fun Transcript.bobResetsAndAliceReseals(held: TranscriptFrame): TranscriptFrame {
        val reset =
            checkNotNull(
                reset(
                    "bob",
                    "alice",
                    t0 + 30 * SECOND,
                    ResetCause.ON_DEMAND,
                    REROOT,
                    decidedBy = "state.session: Bob responded (weAreInitiator false), so no pending init and no answered-init floor",
                ),
            )
        open(
            "alice",
            reset,
            t0 + 31 * SECOND,
            OPENED,
            note = "Alice adopts Bob's reset; its marker makes her re-seal what he has not acked",
        )
        return reseal(
            "alice",
            "bob",
            held,
            t0 + 32 * SECOND,
            v3 = true,
            note = "the same row re-sealed: its id and sentAt (so the same AAD) under the new session, new bytes",
        )
    }

    private suspend fun resealNewFirst(): JsonObject =
        transcript(
            "resealNewFirst",
            "The re-seal pair (MeshManager.resealAndFlood, ADR 2026-09.qerd): a DM Alice sealed in the old era is still " +
                "unread when Bob resets, so on his reset marker she re-seals it — same frame id, same sentAt, new bytes " +
                "under the new session. The re-sealed copy reaches Bob first and opens; the original arrives later, and " +
                "the exists-gate (a message row with its id) acknowledges it without decrypting. Decrypted, it would fail: " +
                "Bob's reset purged his receive state and re-minted his epoch 1, which it was sealed against.",
            resealAlice,
            resealBob,
        ).apply {
            confirmed()
            val original = seal("alice", "bob", "Will be re-sealed", t0 + 20 * SECOND, v3 = true)
            val copy = bobResetsAndAliceReseals(original)
            open("bob", copy, t0 + 33 * SECOND, OPENED)
            skip(
                "bob",
                original,
                t0 + 33 * SECOND + SeenSet.DEFAULT_TTL_MS + SECOND,
                "the original, after the copy's row exists: acknowledged, never decrypted. It arrives past " +
                    "SeenSet.DEFAULT_TTL_MS after the copy, because inside that window the router absorbs the same id as " +
                    "deduped before the pipeline sees it (a custody re-serve after the window is what reaches the gate)",
            )
        }.json()

    private suspend fun resealOldFirst(): JsonObject =
        transcript(
            "resealOldFirst",
            "The re-seal pair the other way round (ADR 2026-09.qerd): Bob opened the original before he reset, but his " +
                "receipt had not reached Alice, so she re-seals it anyway. The copy lands on the row the original wrote, " +
                "and the exists-gate acknowledges it without decrypting: no chain key is spent on it.",
            resealAlice,
            resealBob,
        ).apply {
            confirmed()
            val original = seal("alice", "bob", "Will be re-sealed", t0 + 20 * SECOND, v3 = true)
            open("bob", original, t0 + 25 * SECOND, OPENED)
            val copy = bobResetsAndAliceReseals(original)
            skip(
                "bob",
                copy,
                t0 + 25 * SECOND + SeenSet.DEFAULT_TTL_MS + SECOND,
                "the re-sealed copy, after the original's row exists: acknowledged, never decrypted. It arrives past " +
                    "SeenSet.DEFAULT_TTL_MS after the original, because inside that window the router absorbs the same id " +
                    "as deduped before the pipeline sees it (the original opened, so nothing reopens the id)",
            )
        }.json()

    // ---- what the pipeline applies ------------------------------------------------------------------------

    private fun message(
        frame: TranscriptFrame,
        receipts: List<String> = emptyList(),
    ) = buildJsonObject {
        putJsonObject("message") {
            put("id", frame.env.id)
            put("body", frame.body)
        }
        put("receipts", receiptList(receipts))
        put("acksBack", true)
    }

    private fun receipts(vararg ids: String) =
        buildJsonObject {
            put("receipts", receiptList(ids.toList()))
            put("acksBack", false)
        }

    private fun receiptList(ids: List<String>): JsonArray =
        buildJsonArray {
            for (id in ids) {
                add(
                    buildJsonObject {
                        put("id", id)
                        put("acker", "alice")
                    },
                )
            }
        }

    private fun reaction(
        messageId: String,
        emoji: String?,
        at: Long,
    ) = buildJsonObject {
        putJsonObject("reaction") {
            put("messageId", messageId)
            put("reactor", "alice")
            put("emoji", emoji)
            put("at", at)
        }
        put("receipts", JsonArray(emptyList()))
        put("acksBack", false)
    }

    private fun profile(pr: ProfilePayload) =
        buildJsonObject {
            putJsonObject("profile") {
                put("of", "alice")
                put("name", pr.name)
                put("status", pr.status)
                put("avatarHash", pr.avatarHash)
                put("version", pr.version)
                put("openToChat", pr.openToChat)
            }
            put("receipts", JsonArray(emptyList()))
            put("acksBack", false)
        }

    companion object {
        private const val SECOND = RatchetDmVectors.SECOND
        private const val OPENED = RatchetDmVectors.OPENED
        private const val DUPLICATE = RatchetDmVectors.DUPLICATE
        private const val AEAD_FAIL = RatchetDmVectors.AEAD_FAIL
        private const val REROOT = "REROOT"
        private const val MARK = "MARK"
        private const val DECLINED = "DECLINED"
        private const val NO_FLOOR =
            "state.session: confirmed, so no pending init; cause ON_DEMAND, so the answered-init floor does not apply"
        private const val ANSWERED_FLOOR =
            "floorsBefore.confirmedAt (cause UNREADABLE, our own init answered): declined while now - it < " +
                "RESET_REPLACEMENT_MIN_INTERVAL_MS"
        private const val THUMBS_UP = "👍"

        /** The rules a port needs to read stage 3b's steps, added beside stage 3a's `transcriptFormat` entries. */
        fun formatAdditions(): JsonObject =
            buildJsonObject {
                putJsonArray("moreOps") {
                    add(
                        "reset: actor asks RatchetSessions.sealResetDm(cause) toward peer at now; result is REROOT, MARK or " +
                            "DECLINED (with reason), and decidedBy names the field and floor that decided the plan; a " +
                            "sealed reset is a signed v2 chat frame whose plaintext is MessageContent(ctl = 1)",
                    )
                    add(
                        "skip: the frame reaches actor's InboundPipeline after a message row with its id exists; the " +
                            "exists-gate never decrypts it, so no receive key is spent and the receive state does not move, " +
                            "and acknowledges it (acksBack: for a DM to us, a sealed CTL_RECEIPT — the actor's own outbound " +
                            "seal, not listed)",
                    )
                }
                put(
                    "floorsBefore",
                    "on stage 3b steps: the in-memory floors the actor's RatchetSessions holds for its peer as the step " +
                        "begins (null = no entry; a restart forgets all four): lastReplacementAt (the last replacement or " +
                        "race adoption taken from the peer; an init flagged FLAG_RESET is adopted only " +
                        "RESET_REPLACEMENT_MIN_INTERVAL_MS after it, an unflagged one REPLACEMENT_MIN_INTERVAL_MS), " +
                        "confirmedAt (when our own init was answered; sealResetDm(UNREADABLE) declines inside " +
                        "RESET_REPLACEMENT_MIN_INTERVAL_MS of it), lastResetSentAtInMemory (the no-session fallback only the " +
                        "reset heuristic reads, as the max with the persisted stamp — sealResetDm's plan never reads it: its " +
                        "decline keys on the persisted state.session.lastResetSentAt, which survives a restart) and " +
                        "undecryptableIds (the reset heuristic's distinct-id count).",
                )
                put(
                    "applies",
                    "on an open: what InboundPipeline does with what opened — the message row (message), the receipts it " +
                        "records (receipts: acked id and acker), the reaction (LWW at the frame's sentAt), the sealed " +
                        "profile written to the sender's row, and whether it acks the frame back with a sealed CTL_RECEIPT " +
                        "(acksBack). Exactly these and nothing else: InboundPipelineTest checks the deltas.",
                )
                put(
                    "signed",
                    "false on a frame in the unsigned point-to-point form (relay = false, sig empty; ADR 059); absent means signed",
                )
                put(
                    "routerDedup",
                    "pipeline steps (layer pipeline, skip, applies) model InboundPipeline below MeshRouter's id dedup: a " +
                        "copy of an id the node took inside SeenSet.DEFAULT_TTL_MS is absorbed as deduped before them, " +
                        "unless a reset-triggering open failure reopened the id. So a same-id second copy in a 3b scenario " +
                        "arrives past that window. Engine-layer opens model RatchetSessions alone, router and exists-gate " +
                        "aside (stage 3a's DUPLICATE re-opens included). Neither layer lists the receiver's own ack-backs.",
                )
                put("fields", fieldsJson())
                putJsonObject("constants") {
                    put("RESET_REPLACEMENT_MIN_INTERVAL_MS", RatchetSessions.RESET_REPLACEMENT_MIN_INTERVAL_MS)
                    put("REPLACEMENT_MIN_INTERVAL_MS", RatchetSessions.REPLACEMENT_MIN_INTERVAL_MS)
                    put("RESET_MIN_INTERVAL_MS", RatchetSessions.RESET_MIN_INTERVAL_MS)
                    put("RESET_DISTINCT_FRAMES", RatchetSessions.RESET_DISTINCT_FRAMES)
                    put("PREV_ROOT_TTL_MS", RatchetEngine.PREV_ROOT_TTL_MS)
                    put("MAX_EPOCH_MESSAGES", RatchetEngine.MAX_EPOCH_MESSAGES)
                    put("MAX_EPOCH_AGE_MS", RatchetEngine.MAX_EPOCH_AGE_MS)
                    put("MAX_EPOCH_NUMBER", RatchetEngine.MAX_EPOCH_NUMBER)
                    put("MAX_EPOCH_JUMP", RatchetEngine.MAX_EPOCH_JUMP)
                }
            }

        /** What each 3b step field means, for `transcriptFormat.fields`. */
        private fun fieldsJson(): JsonObject =
            buildJsonObject {
                put("opens", "on an open or skip: the index of the step that sealed the frame (a mutation's or twin's source)")
                put(
                    "content",
                    "on a seal of machinery: the MessageContent fields beyond the body — ctl, ack, acks, rp " +
                        "{messageId, emoji (null = retraction)}, pr {name, status, avatarHash, version, openToChat, " +
                        "loraNode, loraKey} — as sealed in plaintext",
                )
                put("resealOf", "on a seal: the step whose frame it re-seals (same frame id and sentAt, so the same AAD, new bytes)")
                put(
                    "cleartextAttachmentHash",
                    "on a seal: ChatContent.attachmentHash, the cleartext avatar hint MeshManager.sendCtlDm sets",
                )
                put("SKIPPED_EXISTS", "a skip step's expect: the exists-gate took the frame, see moreOps")
                put("pipelineReplay", "on a scenario or negative case: InboundPipelineTest replays it through the real pipeline as Bob")
                put(
                    "fixtures",
                    "on a pipelineReplay scenario: rows the receiver holds before it starts (bobSent: Bob's own DMs to Alice)",
                )
            }
    }
}
