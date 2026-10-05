---
id: "2026-10.mptm"
slug: an-attachment-keeps-the-identity-it-arrived-under
title: "An attachment keeps the identity it arrived under"
date: 2026-10-04
topics: [attachments, crypto, privacy, custody]
---

# ADR 2026-10.mptm — An attachment keeps the identity it arrived under

Status: Accepted (2026-10-04). No wire change: no field, no capability bit, no vector moved. DB v20 adds two
local-only `messages` columns. The iOS port needs no companion change to receive this. It goes on sealing every
send fresh until it adopts the rule, which costs bytes, never correctness.

**What was observed.** Every DM and group send sealed its attachment under a fresh random key
(`MeshManager.sealAttachment` → `AttachmentCrypto.seal`), so the same file sent N times was N ciphertext hashes.
Each one was pulled by every carrier that custodied its frame (`InboundPipeline.onCarriedFrame`, under the 128 MiB
`CARRIER_BLOB_BUDGET_BYTES`) and by every recipient, including one that already held the file. A 7.5 MB PDF passed
hand to hand through ten conversations cost a carrier 75 MB of its budget for one document, and pushed everything
else out of custody. Picking a saved photo also re-encoded it (`AttachmentStore.ingest` decodes and writes a new
JPEG: new bytes, a generation lost). Picking a saved GIF flattened it to a still: the saved file is `image/webp`,
and only `image/gif` took the animated path.

**What changed.** One rule: **a phone seals a given plaintext once.**

1. **A pick goes out as the identity the phone already holds it under** (`AttachmentIdentities.find`, called by
   `AttachmentStore.ingestPicked` and `ingestFile` before any decode). Only an identity whose blob is still held
   counts. A sealed DM or group copy is reused by its ciphertext hash and key. A room picture is reused by its own
   plaintext hash with **no key**. `MeshManager.sealAttachment` returns that identity instead of sealing, and falls
   back to a fresh seal if the blob went between the pick and the send. A hit stores the picked bytes unchanged, so
   a forwarded GIF keeps moving and a forwarded photo loses nothing. The room's block and a DM's confirmation behave
   as before, because a hit is screened like any fresh image.
2. **When both exist, the key-less (public) identity wins**, then the earliest. Once a file has been in the room,
   its public name is the one most phones nearby already hold.
3. **A key-less frame from someone who knows we hold the file is shown from our own copy**
   (`InboundPipeline.copyFromOwnStore`, called from `deliverChat` and `onCarriedFrame` before the want). When the
   frame names the plaintext of a sealed attachment held here *and its author was party to the conversation that copy
   came through* (its sender, the DM peer we got it from or sent it to, a member of its group:
   `MessageDao.sealedHolding`), the copy is decrypted out of the phone's own store. It enters through
   `MeshBlobStore.ingest`, so it is screened before it is held (#109), and then runs `onObtained`, as the spool path
   does. A DM or group picture posted to the room by one of its own people is never pulled by the others.

   The author rule is load-bearing. A copy made here is observable: the phone then serves bytes nobody saw it pull.
   The first cut hung it on `BlobExchange.want`, which any `blobreq` (unsigned) and any stranger's room post reach,
   so anyone holding a file could post it and watch which phones answered without asking. That is the convergent
   confirmation oracle again, through the back door. Only someone who already knows we hold the file can trigger it
   now; everyone else's post is pulled as ever.
4. **Where the plaintext name lives.** `messages.attachmentPlainHash` (indexed) holds the plaintext's SHA-256 for a
   sealed row:
   - the sender writes it at send;
   - a recipient writes it in `onObtained`, or in `deliverChat`'s already-held branch;
   - an older row learns it when the user saves it (`ChatViewModel.saveAttachment` / `saveAttachmentTo` already
     decrypted).

   `messages.attachmentSourceHash` holds the picker's own bytes for one of our sends when ingest changed them, so
   the same original or the same keyboard GIF picked again is found. Both are local only. They live and die with
   the row, so an identity goes when the last row naming it does.

**Rejected: convergent encryption** (key = H(file)). It deduplicates every copy, two independent sources included,
but anyone who holds the file can then compute its hash and scan carried frames for who is passing it to whom.
Evacuation notices and protest flyers look the same to the protocol, and that scan is exactly the threat this
messenger's users face. Reuse limits recognition to phones that were already in the chain.

**Rejected: a sealed plaintext hash on the wire.** It would let a recipient skip only its own last hop. Carriers,
who do most of the pulling, still could not see it. It would also need an iOS companion change and a custody
special case, for little gain.

**What it costs.**

- **Carriers see equality.** A carrier that sees two frames naming one hash learns they carry one attachment, and
  the envelope's cleartext sender and recipient say between whom.
- **Recipients can track forwards.** Anyone who received the key can recognise the hash on any later frame they
  carry.
- **A Nearby picture forwarded into a DM or group is public in a new way.** It names the room's plaintext hash, so
  every phone nearby that holds the post can see "this DM forwards that post". The DM's text stays sealed. A spool
  still seals the bytes under its scope key (`docs/SPOOL_PROTOCOL.md` §4.5).
- **A per-attachment key is no longer per send.** It belongs to one plaintext along one chain. It still opens only
  that file.

**What it does not cover.**

- **Independent sources stay separate.** Two people who each got the file outside Knit send two identities, and a
  recipient holding one still pulls the other.
- **A stranger's post of a file we hold sealed is pulled.** So is a room post that arrives before its sealed twin's
  plaintext name is recorded: the 60 s re-want asks the mesh, never our store, because it no longer knows which
  author to check.
- **A forward carries the bytes as they arrived.** A photo is not re-encoded on its way on, so whatever metadata
  its original sender's phone left in it travels with it. Knit's own ingest strips ours before the first send; a
  source match (our own original picked again) reuses that stripped copy.
- **No bulk backfill.** Filling plaintext names in bulk would decrypt the whole history at startup, so a row
  received before v20 learns its name only when it is saved.
- **Other apps' WebPs still flatten.** An animated WebP picked from another app (no identity) still goes through
  the bitmap path and comes out still.

**The trap.** `identityFor` skips a sealed row that has no plaintext name: its `COALESCE` would otherwise hand back
the ciphertext hash as the plaintext's. Keep that guard.

**Tests.**

- `AttachmentIdentitiesTest` (the real SQL, the author rule included: `aStrangerPostingTheFileLearnsNothingFromUs`)
- `AttachmentStoreIngestTest` (hits, the source match, GIFs kept animated, the fallbacks)
- `MeshManagerTest` (`aForwardGoesOutUnder…`, `aRoomPictureForwardedToADm…`, `anIdentityWhoseBlobWent…`)
- `InboundPipelineTest` (`aPulledSealedAttachmentLearns…`, `aKeylessForwardOfARoomPicture…`)
- `KnitDatabaseMigrationTest` (19 → 20)
- `AttachmentReuseLabTest`, which covers group → DM, room → DM to a holder, group → room copied locally by
  every sealed holder with no file sent, and a stranger's room post of the same picture, which is pulled
