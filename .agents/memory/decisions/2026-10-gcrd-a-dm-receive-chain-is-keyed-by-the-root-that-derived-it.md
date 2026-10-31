---
id: "2026-10.gcrd"
slug: a-dm-receive-chain-is-keyed-by-the-root-that-derived-it
title: "A DM receive chain is keyed by the root that derived it"
date: 2026-10-05
topics: [crypto, pfs, data]
---

# ADR 2026-10.gcrd — A DM receive chain is keyed by the root that derived it

Status: Accepted (2026-10-05). Work item knit/knit-next#135. Amends ADR 027 (a reset's re-minted epoch number) and
the receive-state half of ADR 2026-09.pz9g (era effects only on a fresh derivation under the active root). DB v21;
no wire change; Android only (knit-ios has no DM ratchet).

**What was observed.** Twice in two days a pair's DMs stopped opening in one direction hours after a reset, and
each time recovered only when the receiver's reset heuristic fired:

- 2026-10-04, Pixel 3 → Pixel 7. The Pixel 3's restarted numbering reached epoch 4 at 20:11. The Pixel 7 dropped
  that epoch's n=0 as `DUPLICATE` and n=1 to 4 as `AEAD_FAIL`, then reset at 20:19. One of the stranded DMs arrived
  31 minutes after it was sent.
- 2026-10-05, Pixel 9 → Pixel 8. The new era reached epoch 13, and n=0, 1 and 2 all dropped as `AEAD_FAIL`. The
  n=0 frame was a direct transfer's `CTL_TRANSFER` READY, which no reset re-seals, so the transfer timed out.

It looked like the rows a reset leaves behind. It was not: the replacement purged every receive row, as it should.
The rows came back afterwards. Custody re-served old-era frames that have no message row (receipts, transfer
control), so `messages.exists` did not stop them. They opened under the `prevRoot` the receiver keeps for 48 h and
stored their chains under `(peer, epoch)`, plus skipped keys under `(peer, epoch, idx)` when they sat mid-epoch. The
field logs show it: on the Pixel 7, old-era frames for epochs 4 to 7 read `DUPLICATE` at 18:08 and 20:02, numbers
the new era had not reached yet. When the resetter's numbering reached a re-planted number, `RatchetEngine.open`
judged the new frame against the old era's chain. Below its cursor it read `DUPLICATE`, at or above it the AEAD
failed, and a stale skipped key at `(se, n)` failed outright, because that rung returned its own result. The
heal-reset re-roots the other side, so it sets up the same trap in the other direction.

**What changed.** Every receive chain and skipped key carries `rootTag`, the first eight bytes of
`HKDF(root, "knit/dm/v2/root-tag")`, last in its primary key: `(peerId, epoch, rootTag)` and
`(peerId, epoch, idx, rootTag)`. The store returns every era's rows for a number, and each root candidate in
`RatchetEngine.open` reads only rows tagged with its own root. The ladder makes three passes, and each candidate
leaves at the first pass where it holds something of its own: its skipped key, else its live chain, else a fresh
derivation. When nothing opens, the verdict ranks DUPLICATE above AEAD_FAIL, BAD_HEADER and EPOCH_GONE. A genuine
re-serve of a consumed frame also fails under every other candidate's fresh derivation, and that failure must not
walk the reset heuristic. DUPLICATE does not stop the ladder, though: a consumed index under one root is exactly
what #135 showed the new era. The group ratchet solved the same problem earlier with `mintedAt` in its keys
(`GroupRatchetEngine.open`). This change mirrors it, except for the verdict, because the DM ladder also
fresh-derives.

Rejected:

- **Purge on every root change and never store a row opened under `prevRoot`.** Each drained old-era frame would
  then cost a DH, and every re-serve of a frame with no message row would open and deliver again.
- **Continue the resetter's numbering past its old highest epoch.** That moves the reset's meaning on the wire and
  in the iOS reference (`docs/FORWARD_SECRECY_RATCHET.md` §7) to fix a local storage key.

The purges stay. In storage they are hygiene now. In the engine they are still load-bearing: a purging resolution
reads nothing stored, or a late race remnant's skipped key would open a flagged reset on a rung that records no era
effects.

**What it costs.** While a `prevRoot` is held, a genuine duplicate or an old-era frame with no stored chain pays one
X25519, one HKDF and one AEAD attempt per row-less candidate. That is sub-millisecond, and only the peek runs it,
never the commit. The tag is one-way, so a row still never holds a root, and a superseded root vanishes with its
drain window as before.

The v20→v21 migration recreates both tables empty. SQL cannot compute the tag, and a stale row's root is the one
thing #135 shows is unknowable; stamping existing rows with the active root's tag would keep the stale ones live for
two days. Sessions and our epoch privs stay, so every frame still opens by fresh derivation. A re-served frame with no
message row opens once more, as after any replacement purge.

The trap: don't key a row by the number alone again, and don't let DUPLICATE short-circuit the other candidates.
Pinned by:

- `RatchetEngineTest`'s #135 cases
- `RatchetSessionsResetTest` (the P3→P7 and P9→P8 shapes, over Room)
- `RatchetSoakTest` (a seeded mid-run reset; a frame sealed under the recipient's active root must open on first
  delivery, and a fake with untagged storage fails it)
- `RatchetRepositoryTest`, `KnitDatabaseMigrationTest`
- `SessionLabTest.oldEraDmsReadAfterTheResetNeverStrandTheNewEraAtTheirEpochNumber`
