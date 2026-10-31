---
id: "2026-10.xgwj"
slug: a-forced-group-rekey-retires-the-send-chain
title: "A forced group rekey retires the send chain, never reuses its epoch"
date: 2026-10-10
topics: [crypto, groups]
---

# ADR 2026-10.xgwj — A forced group rekey retires the send chain, never reuses its epoch

Status: Accepted (2026-10-10; `GroupRatchetStore.retireSendChains` / `purgeGroup`, `GroupRatchetEngine.SendChain.retired` /
`isRetired`, `GroupRatchetRepository.sendChains` and its send-chain sweep, `GroupRepository.recordDeparture` /
`recordRejoin` / `leave` / `delete`; `GroupRatchetRepositoryTest`,
`GroupRatchetEngineTest.aRetiredChainMintsPastItSoBothSeedsAdoptInEitherOrder`,
`GroupMembershipLabTest.aLeaveRekeysSeedThatOvertakesTheEarlierOneLosesNothing` and
`aRecreatedGroupsSeedThatOvertakesThePreLeaveOneLosesNothing`). Amends the "deleted" wording of ADR 017's leave-rekey
and ADR 2026-09.v6fu's rejoin rekey; GFS §4 rules 4 and 5 and §6.1 carry the rule.

**What was observed** (GitLab #167). A member who posts, handles another member's leave, and posts again sends two
group seeds. The leave rekey deleted every `group_send_chains` row, so `GroupRatchetSessions.sealGroup` minted with
`prevEpoch = 0`: the second seed was epoch 1 again, under a newer `mintedAt`. A receiver tells two mints of one epoch
apart by `mintedAt` alone (`GroupRatchetEngine.adoptSeed`), the rule that lets a wiped sender's re-mint supersede its
old era. So when custody, a held link or a spool handed the receiver the second seed first, the first came in as
`Stale`, and the frame sealed under it failed `GROUP_RATCHET_AEAD_FAIL` for good: a §7 key request could not help,
because the sender had deleted that seed. The lab scenario reproduces it exactly on the old code (Bob holds
`[founded, after the leave]`, `GROUP_RATCHET_AEAD_FAIL=1`). It had been papered over in
`GroupMembershipLabTest.pushOutBobsNotices`, which waited for Carol to settle the roster before she posted. That
looked like a lab-ordering fix, but it was hiding this bug.

A local leave or delete had the same hole (found by the crypto review of the first cut). `purgeGroup` deleted every
send-chain row, and the same people put together again (`ContactsViewModel.createGroup`) are the same group id: the
others record the rejoin, both seeds pass the roster gate, and the re-created group's first mint was epoch 1 again.
The second lab scenario reproduces that on the old purge (Carol holds `[founded, back again]`,
`GROUP_RATCHET_AEAD_FAIL=1`). GFS §4 already said only a wipe restarts numbering. The code did not match the spec.

**What changed.** A forced rekey (a departure, a rejoin), a local leave and a delete all *retire* the chain instead
of deleting it. In the same transaction as the roster change, every send-chain row for the group goes, and the newest
epoch comes back as a key-less row: `seed`, `chainKey` and `export` empty, `count = MAX_EPOCH_MESSAGES`, epoch and
`mintedAt` kept. The purge still deletes receive chains, skipped keys and the outbox. `sealGroup` is unchanged:
`needsNewEpoch` fires on the count, `mint` numbers past the retired epoch, and `seal` would refuse the row anyway. The
two seeds now land on different `(sender, epoch)` rows and adopt in either order. No wire field, vector or schema
moves. Receivers already take 2, 3, … from the count and age rules, and the extra adoption fits the same ≤4-a-day
budget the deleted-chain re-mint used.

A retired row is **never re-served**. `GroupRatchetStore.sendChains` feeds `currentSeeds`, which feeds the
key-request answer, the re-send flush and the root gossip, and it filters retired rows by their empty seed. It does
not filter by count: a rule-2 chain at 200 keeps its seed and must still be served for the frames sealed under it.
The filter sits in the repository, not the DAO's query, because the sweep reads the DAO's full list.

A retired row is **never dropped while it is the newest**, however old. The sweep's ordinary rule handles it: the
newest row survives, and once a mint follows it, it is the previous row and drains 48 h past that mint. So
**numbering only rises, except after a wipe** (GFS §4 rule 5), which takes the seed outbox with it. A restore does too:
`BackupTables.TRANSIENT` carries neither table. Because numbers only rise, the outbox's epoch-counted acks always
compare within one numbering. A retired row that is no longer the newest cannot reach the outbox: the mint that
superseded it numbers above it, its `markKeySent` and every ack compare upward from there, and a late ack of the
retired number lands below the new `sentEpoch`, so the seed stays due.

**Alternatives not taken.**

- *Adopt an older mint of a held epoch on the receive side*, as a draining row instead of refusing it as stale.
  It widens what a receiver accepts. It turns a peer-supplied `mintedAt` into a way to plant extra chains, which is
  attack surface. It needs the ≤4-a-day adoption limit to count those rows too. And it mends only builds that update,
  where the sender-side fix needs nobody's cooperation.
- *Let the sweep drop a retired newest row after a bound*, so a group left for good keeps nothing and numbering may
  restart at 1 once no frame sealed under the old epoch can reach anyone (worked out as 120 h 5 min from the mint:
  24 h of sealing + 24 h of custody + 5 min of skew + 48 h of spool from arrival + 24 h of margin). Built, then
  withdrawn after review found two defects in the seed outbox, which counts acks by epoch:
  1. **A stale ack row.** A group quiet for days and then rekeyed retires a chain already past the bound, the next
     hourly sweep drops it, and the next send mints epoch 1. Every member's recorded ack of N reads epoch 1 as held, so
     `flushPendingGroupKeysFor` (`ackedEpoch < epoch`) never re-seeds a member who missed that distribution. Clearing
     the outbox with the drop fixed this one.
  2. **A late ack in flight.** A `CTL_GROUP_KEY_ACK` for the dropped number N can still arrive after the restart's
     `markKeySent(1)`, from custody, the spool, or an `AlreadyKnown` re-ack of a late seed-N copy. `markKeyAcked` then
     writes `ackedEpoch = N` on the fresh row, and the member is never re-seeded for epochs 1..N. The bound ran from
     the mint, but seed DMs and acks are bounded from the *last distribution*, which can be any time while the
     chain is the newest.

  Each round on the drop found a new defect. The thing it saves, one key-less row per group, is not worth a rule
  that has to reason about every in-flight ack.

**What it costs, and the traps.** One key-less row per group the device ever sealed in: a few bytes (two ints and a
long, three empty blobs), no key material. It lives until the next mint plus 48 h, and for a group left or deleted for
good, as long as the database. Forward secrecy is exactly what it was: no key byte outlives the transaction that
retires it. A sender on an older build still reuses epoch 1, so its pairs keep the bug until it updates. The receive
side was deliberately left alone. A side effect, also a fix: a re-mint at 1 under an outbox that had acked a higher
epoch looked already acked to `flushPendingGroupKeysFor`; numbers no longer go down, so that cannot happen.

Traps for the next reader. Anything that reads send chains to *serve* a seed must go through `sendChains` (or check
`isRetired`), never `sendChain`; the seal path is `sendChain`'s one reader, because it needs the retired number. Do
not add a sweep, a cap or a cleanup that deletes a retired newest row: the drop above is what that would bring back.
knit-ios implements GFS: if it ships the group ratchet, §4 rules 4 and 5 are its rules too. That is a paired-change
follow-up, not part of this change.
