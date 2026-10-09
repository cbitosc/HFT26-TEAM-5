# Crypto plane — which decision governs which symbol

The symbol → ADR routing for Knit's end-to-end crypto: the DM epoch ratchet and the group sender-key ratchet
(`mesh/crypto/ratchet/`, `data/ratchet/`), the envelope, sealing and signing (`mesh/crypto/` outside `scope/`),
session resets, group seeds and the roster they are sealed to (`mesh/PendingGroupKeys.kt`, the seed and roster
paths of `InboundPipeline` and `MeshManager`), and the intro answer (`mesh/IntroSync.kt`). The `crypto-owner`
reads it before it writes and the `crypto-reviewer` before it judges (both in `.agents/personas/`); `AGENTS.md`
points here instead of carrying these lines. Each entry names the symbols that trigger it, the ADRs to read
(`.agents/memory/decisions.md` maps an id to its file, amendments included) and, where there is one, the section of
`docs/FORWARD_SECRECY_RATCHET.md` (FSR) or `docs/GROUP_FORWARD_SECRECY.md` (GFS) that is the normative spec. The
mechanism in prose is `.agents/context/e2e-encryption.md`. An entry is an index into its ADRs, not a substitute:
where they disagree, the ADR wins and the entry is fixed.

Crypto-adjacent routing that stays in `AGENTS.md`, because another subsystem owns it: at-rest secrets (`data/crypto/`
— `DatabaseKey`, `SqlCipherKey`, `KeystoreSecret`, `IdentityKeyStore`'s load and mint; ADRs 2026-09.uzkm,
2026-10.47rw), the spool plane's scope crypto (`mesh/crypto/scope/`, `docs/SPOOL_PROTOCOL.md`), backup and restore
(ADR 2026-09.6mj7 — the reset set it hands this plane is qerd's, below), clone detection (2026-09.ypcc), the
carrier's kept profiles and key serving (`KeyExchange`, 2026-09.g64k), attachment identities (2026-10.mptm), the
✓✓'s way home (`AckSync`'s ride hold, 2026-09.y5f3), and `XeddsaVerify`, which checks Meshtastic signatures for the
LoRa plane (2026-09.ggq4).

## Entries

- **When touching `CryptoScheme`, `EncEnvelope.v`, `Protocol.CAP_RATCHET` / `CAP_CRYPTO_V3`, `RatchetCrypto.messageNonce`,
  `MessageContentV2`, `MessageCrypto`, or `InboundPipeline.verifyInbound` / `canCarry`:** READ ADR 005, ADR 016,
  ADR 059, FSR §5 and `docs/WIRE_COMPAT.md`. One Ed25519 signature authenticates every flooded frame, verified
  byte-exact over the received `signed` bytes before any decrypt; the one unsigned shape is v3's live-link tick
  (`relay = false`, DM-form chat to us from a pinned sender), checked before the plaintext branch and the exists-gate,
  delivered only if it opens as a `CTL_RECEIPT`, and never fed to the reset heuristic. The scheme is chosen per peer
  from the pinned profile's bits; v3 sends `nonce` and `keys` empty, never absent, because `canCarry` decodes the
  payload and every fielded build must still carry the frame. Resets, seeds and the group form stay v2. Inbound v1 is
  accepted forever. A changed byte is a vectors change (`vectors/README.md`, `KNIT_WRITE_VECTORS=1`).
- **When touching `decryptAndDeliver` / `decryptAndDeliverV2`, the pre-decrypt exists-gate, `peekOpen` /
  `commitOpen`, `SessionTransactor`, the `ratchetMutex` qualifier, or any `locked()` block in `RatchetSessions` /
  `GroupRatchetSessions`:** READ ADR 016, ADR 017 and `SessionTransactor`'s KDoc (commit 051284eb). Decrypt is
  two-phase — a lock-free peek decides (moderation, the seed park), then a re-open commits atomically with the message
  row — and the order is transaction outer, mutex inner, enforced inside both facades, never left to callers; one
  mutex serves both facades because seed adoption runs inside a DM commit. Deleting a used message key is safe only
  because a persisted frame never reaches decrypt again; a frame with no message row (a ctl, a tick) has no such
  gate. A decrypt or verify failure never throws out of the inbound handler: `onDeliver` runs before the relay is
  scheduled.
- **When touching `RatchetEngine.touch`, `Ladder.openFresh`, `resolveSession` / `resolveRace` / `isRemnantOf` /
  `remnantCandidates`, `peerInitEphPub`, `allowReplacement`, `REPLACEMENT_MIN_INTERVAL_MS` /
  `RESET_REPLACEMENT_MIN_INTERVAL_MS`, or `IntroSync.onPeerFrameOpened`:** READ ADR 2026-09.pz9g, ADR 023 and FSR §7.
  The race winner is the init whose initiator has the smaller node id, never the earlier stamp; a resolved init is
  remembered by its ephemeral, so a re-served copy is inert. A frame moves the era — DH-base adoption,
  `highestPeAcked`, an initiator's confirmation — only when it derives a fresh epoch under the active root (the ADR's
  `openNewEpoch` is today's `Ladder.openFresh`); a late race loser's root is a trailing read-only candidate that
  records nothing and is never anchored; `FLAG_RESET` is adopted or refused, never read without adoption. Whoever
  abandons a root era drops the receive state tied to it, at every site an era changes. An init is answered once
  per init, not once per peer per hour. Regression: `RatchetEngineTest`'s pz9g cases, `IntroSyncTest`,
  `SessionLabTest`, `CustodyLabTest.bothSidesSendWhileApartAndMerge`.
- **When touching `RESET_TRIGGERING_DROPS`, `InboundPipeline.onRatchetFailure` / `isLiveEvidence` /
  `maybeRequestReset`, `RatchetSessions.RESET_DISTINCT_FRAMES` / `RESET_MIN_INTERVAL_MS` / `STRANDED_TAIL_MS`,
  `headerSane`, or a ratchet `DropReason`:** READ ADR 023, ADR 024, ADR 026 and ADR 053. The heuristic counts three
  **distinct** frame ids of `NO_SESSION`, `EPOCH_GONE` or `AEAD_FAIL` from a pinned, signature-verified sender, each
  from the era it would abandon (exact when we responded; when we initiated, five minutes of skew, and past 48 h of
  session age the gate opens), and fires at most once per 6 h. `DUPLICATE` and `BAD_HEADER` never trigger:
  a consumed index proves the frame already opened, and a burst of duplicates is almost always a re-send cadence
  bug (053's tick). A reset repairs the channel, never the backlog.
- **When touching `RatchetDao.localEpochsNewestFirst` / `insertLocalEpoch`, the ratchet sweep, `PREV_ROOT_TTL_MS`,
  `MAX_EPOCH_*`, or a retention cap in `RatchetRepository` / `GroupRatchetRepository`:** READ ADR 027 and FSR §10 /
  GFS §10. Retention orders by mint time, never by epoch number (a reset restarts numbering at 1), and a re-minted
  number replaces the dead era's key. The convergence constants mirror custody's — 200 messages an epoch is the
  per-sender quota, 24 h the TTL, 48 h twice it — so they change together or not at all.
- **When touching `RatchetEngine.open`'s ladder (`Ladder`, `RootCandidate`), `RecvEpoch` / `SkippedKey`, the keys of
  `ratchet_recv_epochs` / `ratchet_skipped_keys`, or `RatchetCrypto.rootTag`:** READ ADR 2026-10.gcrd. A receive
  chain and a skipped key carry the tag of the root that derived it, and each root candidate reads only its own: a
  reset restarts the resetter's numbering, and an old-era frame drained under `prevRoot` after the replacement's
  purge used to plant a chain the new era's frames of that number were judged against (#135). When nothing opens,
  DUPLICATE outranks the rest but never stops the ladder early; a purging resolution still reads nothing stored.
  Regression: `RatchetEngineTest`'s #135 cases, `RatchetSessionsResetTest`, `RatchetSoakTest`'s reset,
  `SessionLabTest.oldEraDmsReadAfterTheReset…`.
- **When touching `RatchetSessions.sealResetDm`, `InboundPipeline.sendSessionReset` / `maybeRequestReset`,
  `MeshManager.seedSendFloorOpen`, or anything else that sends a DM session reset:** READ ADR 2026-09.qerd and
  `docs/FORWARD_SECRECY_RATCHET.md` §7. A reset the peer would refuse — a second root inside its one-minute
  floor — is never sealed: over our own unanswered init it *marks* that init (the reset ctl under it, v2,
  flagged) or declines, and on the heuristic's word over an init answered under a minute ago it declines.
  The group seed floor counts only sends under the pairwise root held now, never `force`. Regression:
  `RatchetSessionsResetTest`, and `RestoreLabTest` under `scripts/lab-chaos.sh`.
- **When touching `GroupRatchetEngine` / `GroupRatchetSessions`, `MeshManager.sealGroupRatchet` / `groupKeyPayload` /
  `currentSeeds`, the seed outbox (`group_key_sends`, `CTL_GROUP_KEY_ACK`), `InboundPipeline.flushPendingGroupKeysFor`
  / `adoptGroupSeeds` / `maybeRequestGroupKey`, `CTL_GROUP_KEY_REQ`, `MAX_ADOPTIONS_PER_DAY`, or
  `replayCustodiedGroupFrames` / `replayCustodiedSeedDms`:** READ ADR 017 and GFS §3, §4 and §7. A seed rides a v2 ctl
  DM, never v1; the outbox is the source of truth and custody only accelerates. A group frame that lands before its
  seed is custodied by its receiver and no peer will re-serve it, so adoption replays our own custody (commit
  0528b0de, and 074449d5 for a seed lost to a restart). A key request never advances an epoch; the re-distribution
  floor is 15 min per (group, member, pairwise root); a session reset forces the flush past stale acks (8a102baf).
  Eligibility is all-or-nothing per message, and a blocked member still gets seeds (ADR 010).
- **When touching a group's roster — `reconcileGroup`/`vetRoster`, `groupleave`, `GroupRepository.recordDeparture`
  / `recordRejoin`, `PendingGroupKeys`, `GroupKeyPayload.group` / `pinRosterFromSeed`, or how a member learns
  of a new group:** READ `docs/GROUP_FORWARD_SECRECY.md` §1 (the pinned founding roster) and §6.1
  (leave-rekey), then ADR 2026-09.v6fu and ADR 2026-09.mjaj. A group's id is the hash of its member set, so
  "the same people" is always the same group; membership shrinks only by your own signed leave and grows only
  by your own signed rejoin — nobody can add or remove anyone else. The seed carries the founding roster, so a
  member with no row pins the group from the seed through `reconcileGroup`'s one door (the relay-only case,
  #47); a seed without one (an older build's) is parked, never consumed.
- **When touching `CTL_RECEIPT` / `CTL_REACTION` / `CTL_PROFILE`, `AckSync.sealTick`, `MeshManager.sealDeliveryTick` /
  `sealDeliveryTickEnvelope`, `InboundPipeline.sealDmReceipt`, or the sealed profile's fan-out:** READ ADR 018, ADR
  020, ADR 033 and ADR 053, then `docs/ENCRYPTED_RECEIPTS_REACTIONS.md`. A ctl never takes the v1 wrap (a pre-ratchet
  build would persist an empty bubble): its fallback is the legacy cleartext frame. A tick is sealed once, at `owe()`,
  and re-sent verbatim on a backoff — re-sealing per retry burns chain keys and starves the receiver's skipped-key
  budget. The sealed profile carries presentation fields only, ordered by the profile version, never `pubKey` or
  `prekey`, and never inserts a peer row.
- **When touching `RatchetSessions.exportedRoots` / `rootChanges`, `RatchetCrypto`'s export functions, or what the
  spool's `ScopeRegistry` reads from the ratchet:** READ ADR 019 (FSR §8, GFS §8), ADR 032 and ADR 2026-09.dcah. Only
  `pairwiseRoot` exports leave the facade, under the ratchet mutex, confirmed sessions only — a raw root never does.
  A root change is an event the scope table derives on; don't fire it on an unchanged root.
- **When touching the self branch of `InboundPipeline.handleProfile` / `handleChat`, `PeerRepository.forgetSelf`,
  `RatchetSessions.forget`, or `GroupRepository.forgetSelf`:** a node never pins its own key and never holds a session
  with itself — a re-served copy of our own profile once pinned `peers[me]` and sealed every group's seed to self in a
  loop floored at 15 min (45763fb6), and the orphaned self session and seed rows outlived the fix (8333c858).
  `MeshLab.assertConverged` asserts no node holds either.
  <!-- doc-lint: allow openNewEpoch — the ADR's name for today's Ladder.openFresh -->

## Adding an entry

A new decision about this plane gets a bullet here — the symbols that trigger it, the ADR, and the invariant in a
sentence or two — never a bullet in `AGENTS.md`. A symbol listed nowhere is not exempt: in this plane the KDoc of
`RatchetEngine`, `RatchetSessions`, `GroupRatchetSessions`, `SessionTransactor` and `PendingGroupKeys` carries the
incident that shaped it.
