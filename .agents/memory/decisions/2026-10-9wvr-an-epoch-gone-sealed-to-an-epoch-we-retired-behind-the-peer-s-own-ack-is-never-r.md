---
id: "2026-10.9wvr"
slug: an-epoch-gone-sealed-to-an-epoch-we-retired-behind-the-peer-s-own-ack-is-never-r
title: "An EPOCH_GONE sealed to an epoch we retired behind the peer's own ack is never reset evidence"
date: 2026-10-16
topics: [crypto, pfs, recovery]
---

# ADR 2026-10.9wvr — An EPOCH_GONE sealed to an epoch we retired behind the peer's own ack is never reset evidence

Status: Draft (2026-10-16; amends ADR 027's accepted residue and narrows ADR 024's evidence population —
no wire field, no schema change, no vector). **Accepts a delivery regression**, stated under "What it costs".

**What was observed.** A Pixel 9 restarted (a crash loop, then a reinstall) and a neighbour re-served its custody
to it. Frames from three peers dropped as `EPOCH_GONE` — `se=88 pe=45`, `se=89 pe=46` from one, `pe=96–98` (frames
~51 h old) from another, `pe=18–24` from a third — while the phone held only epochs 65–80 for the first: the 16-row
cap (`MAX_LOCAL_EPOCHS`) full. Same root, no re-root in between: in-era frames sealed against epochs retention had
long retired. The phone was the initiator on sessions over 48 h old, so ADR 026's escape
(`now - establishedAt >= STRANDED_TAIL_MS`) opened the era gate, three distinct ids reached `noteUndecryptable`, and
three healthy sessions re-rooted within 9 s. The peer that was out of range never answered, so that session stayed
unconfirmed, its DM scope left the relay's table, and the reset could not travel over the relay to fix itself.

This is not rare. The healing rule mints an epoch on every turnaround, so a chatty pair churns the 16-row cap in
hours, not days; any frame that arrives late through custody (a carrier, a restart's lost seen set, a re-served ctl
whose receive row the 48 h sweep took) lands on a swept base. ADR 027 accepted "one extra reset per historically
wedged pair"; the responder side never needed the 48 h escape to pay it.

**What changed.** `InboundPipeline.maybeRequestReset` gates an `EPOCH_GONE` ahead of `noteUndecryptable`, beside the
pre-era gate and with its own `reset gate: … retired epoch` log line, on `RatchetSessions.retiredBase`: one locked
read of the session and `RatchetStore.lowestLocalEpoch`, judged by the pure `RatchetSessions.isRetiredBase`. The
frame's `pe` is retired when it is **below the lowest local epoch number we still hold** for the peer **and below
`highestPeAcked`**.

- *Below the lowest held:* the key is gone and nothing brings it back; a reset strands the frame the same. A `pe` at
  or above it that is missing (a hole) or past our newest is the split-era case the heuristic exists for, and
  counts. The lowest *number*, not the oldest mint (ADR 027): after our own reset a dead era's high rows sit beside
  the live era's low ones, and the minimum only drops, which narrows the rule.
- *Below `highestPeAcked`:* the brief's rule alone would swallow a live failure. A peer that has not heard us for a
  while stays *based* on our epoch while we keep minting (aged epochs, healing on its traffic); once the cap evicts
  that epoch, every new frame of the peer fails `EPOCH_GONE` with `pe` below our lowest. There, `pe` equals the
  newest of our epochs it has sealed against (`highestPeAcked`, recorded only on a fresh derivation under the active
  root — ADR 2026-09.pz9g), never less. The peer's DH base for us only moves forward within our numbering, so
  `pe < highestPeAcked` means the frame is from the peer's past and the channel has worked since. Our own reset
  zeroes the ack with its fresh initiation, so the rule is shut until the peer seals under the new root.
- *`pe = 0` is never retired:* it names our signed prekey, whose retention is time-based (current + 4 weekly), not
  acked. A frame on a prekey we no longer hold is a peer still pinned to a rotated one (GitLab #174) and will keep
  minting inits we cannot open — live, and only our reset or our profile cures it. Custody cannot hold a frame five
  rotations old, so no stranded tail hides there.

Only this phone's own numbers enter (our epoch counter, echoed back in `pe`, and the acks we recorded): no clock, no
other device's counter. ADR 026's skew fix in `isLiveEvidence` is untouched.

**Considered and rejected.** Tagging local epochs with the root (as receive state is, ADR 2026-10.gcrd) would let the
rule read "under the current root" literally, but needs a schema change and buys nothing the ack condition does not:
our numbering is monotone across every root the peer hands us, and restarts only on our own reset, which zeroes the
ack. A frame-age bound (`sentAt` against our clock) was rejected as a two-clock comparison (ADR 026).

**What it costs — an accepted delivery regression.** A DM that arrives late behind live traffic, whose base the
16-row cap retired within hours, used to buy a reset, and the reset's re-seal (FSR §7: the peer re-seals its unacked
DMs of the last 24 h) delivered it. Now nothing does: the message is lost and its sender never sees ✓✓. That trade
is taken on purpose — the reset cost a healthy session its root, its relay scope until the peer answered, and a
day of re-seals, for a frame the channel had already outrun — but it is a loss, not a no-op. ADR 024's rule stands
(a reset repairs the channel, never the backlog), and this gate fires only where the channel has demonstrably
worked since.

The root cause is the retention sweep's `overCap` branch (`RatchetRepository.sweepLocalEpochs`) retiring a *young*
epoch: the ack rule waits until an epoch is superseded, acked and 48 h old, but the cap deletes whatever falls past
the sixteenth row by mint time, and on a chatty pair that can be an epoch minted an hour ago. **Open follow-up, not
done here:** have the cap prefer ack-retired rows (and old ones) before a young one, and count the young `overCap`
deletions in the metrics so the field shows how often this bites (GitLab #180). Either belongs with the retention
constants (ADR 027, FSR §10), which change together or not at all.

**Interactions.** GitLab #91 (retire dead-era local epochs after our own reset) only raises the lowest held number;
the ack is the new era's from 0, so the rule stays correct and stays shut until the peer seals under the new root.
The group path has the analogous frame (`GROUP_RATCHET_NO_KEY` on a swept receive chain) but a different remedy —
a key request that never re-roots, floored at an hour and age-gated at 48 h — and is unchanged. Residue: an old-era
frame whose `pe` is *above* our current newest still counts once the 48 h escape opens. More broadly, the field's
~51 h-old re-served frames show that ADR 026's premise — past `STRANDED_TAIL_MS` no tail survives anywhere — does
not hold under clock skew, since custody's TTL is the sender's `sentAt` judged on the carrier's clock: past the
escape, an over-48 h tail failing as `AEAD_FAIL` or `NO_SESSION`, or as `EPOCH_GONE` with `pe` above our newest,
can still re-root a healthy session, at most once per 6 h per peer. **Open follow-up, not done here:** amend ADR 026
(GitLab #179). The lab cannot reach that escape until the decrypt paths read the injected clock (GitLab #181).

Regression: `RatchetSessionsRetiredBaseTest` (the rule's table; a frame really held past the cap; a hole, a future
epoch and `pe = 0` still counting; our own reset shutting the rule),
`InboundPipelineTest.anEpochGoneOnAnEpochWeRetiredBehindThePeersAckIsNeverEvidence` (the field's initiator shape, and the LRU left clean), and
`SessionLabTest.framesSealedAgainstAnEpochTheCapRetiredNeverResetAWorkingSession` and
`…NeverResetTheInitiatorsSession` (the receiver as responder and as initiator; both fail on the parent with Bob's
reset stamp set), and `SessionTransactorOrderTest` for `retiredBase`'s lock order.
