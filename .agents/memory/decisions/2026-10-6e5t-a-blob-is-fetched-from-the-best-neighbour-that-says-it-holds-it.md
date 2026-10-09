---
id: "2026-10.6e5t"
slug: a-blob-is-fetched-from-the-best-neighbour-that-says-it-holds-it
title: "A blob is fetched from the best neighbour that says it holds it"
date: 2026-10-05
topics: [mesh, wire, blobs]
---

# ADR 2026-10.6e5t — A blob is fetched from the best neighbour that says it holds it

Status: Accepted (2026-10-05). Amends ADR 2026-10.f7gg, whose round is now the tier for neighbours that cannot be
queried, and ADR 2026-09.4tx5 and ADR 2026-10.nxfb, whose reads are unchanged. Additive wire change: two new
unsigned point-to-point types and the capability bit `CAP_BLOB_HAVE` (0x2000). No DB change. Asking is behind
`BuildConfig.BLOB_QUERY`, on in release since 2026-10-08 ahead of its device trial; answering is on in every build. The iOS port follows
as a companion change; it needs nothing to keep working, since it is asked blind as before.

**What was observed.** The #136 device trial (2026-10-05, main 512b72bc, four Pixels) passed: one copy per asker in
every scenario. It also showed what a blind first ask costs:

- In S3 the author was out of reach. Pixel 7 and Pixel 8 each asked the other first, a Wi-Fi Aware-reachable
  neighbour that lacked the picture, rather than the Bluetooth hop that held it. The next round repeated the same
  pick, so each took about 60 s more than it needed.
- In S4 the Pixel 9 opened a new round 2 s after the stream ahead of its copy ended, 1.4 s before that copy's header
  arrived. f7gg's "grace restarts while the holder streams" restarted at the last *step* that saw the stream, and the
  timer was armed for exactly one grace, so the first step after the stream's end always found the grace spent. A
  round younger than `ROUND_MS` would have widened to every holder, which is #121's duplicate.
- The first ask's grace barely covered a holder's own Wi-Fi Aware wait: 14.1 s of 15.

Both ADRs before this one named the cure and set it aside: an explicit answer from a neighbour about what it holds.

**What changed.** Before it asks anyone, a fetch *queries* each linked neighbour whose link advertises
`CAP_BLOB_HAVE`.

The **query** is a `blobhave` (`BlobHaveContent`, at most 32 hashes). The **answer** is a `blobhas`
(`BlobHasContent`): one item per hash with a `state`, a `size` with HAVE and SENDING, the responder's `load` (how many
files it is feeding), and `re`, the query's envelope id. The states:

| State | Meaning |
|---|---|
| NONE 0 | not held, not coming |
| HAVE 1 | a `blobreq` now is served |
| SENDING 2 | a copy is already queued or streaming to you, or was enqueued inside the serve memo: wait |
| FETCHING 3 | on its way to me: query again shortly |
| PARTIAL 4 | reserved |

**The responder** answers in `BlobExchange.onHaveQuery`. Its reads run the bytes' path backwards, as #138 does: the
link (`fileInFlightTo`, then a *peek* at the serve memo), then `arrivingFiles` and `storing`, then the store through
`BlobStore.servable`. That last read is one Room query of lengths. It is **not** `fileFor`, which reads a whole blob
and writes plaintext to disk. A query is not an ask: it serves nothing, pulls nothing, stamps no memo, and a per-peer
token bucket (16 deep, one per second) bounds an unsigned flood of them.

**The asker** (`decide`) works in cycles of `CYCLE_MS` (45 s, the round):

1. **Choose.** It waits for every queried neighbour's answer or `QUERY_WAIT_MS` (5 s), whichever comes first. It
   doesn't wait when the best HAVE so far rides a live Wi-Fi Aware link.
   - A SENDING holder is waited on.
   - Otherwise the best HAVE is asked alone. The planes rank: a live Wi-Fi Aware link; a reachable one for a blob
     whose remaining bytes clear the composite's arm gate (and only then is the link armed); a full-speed Bluetooth
     link streaming nothing to us; a Coded or busy link. Ties break on the lower `load`, then the node id.
2. **Fall back.** A holder whose grace runs out is queried once more. Still sending, it gets another grace. Saying
   HAVE to a query sent after our ask, it never got the ask and is asked once more. Anything else, or silence, and
   the next holder is asked. A holder whose link goes is passed over at once, and so is the sender of a refused
   splice.
3. **Ask blind.** Neighbours without the bit (the iOS port today, older Android builds, everyone while the flag is
   off), and a queried neighbour still silent past the wait, get **f7gg's round, word for word, log lines included**.
   That round runs only once no confirmed holder is left, so a holder that said so beats an author that cannot say.
4. **Pull through.** When nobody in reach holds it, a neighbour FETCHING it is queried again after 10 s. Otherwise
   the source hop that said NONE is asked to pull it through (`onRequest` → `want(asker)`, as before), and every
   NONE neighbour from the next empty cycle on, or at once when the hop is not among them. Reach is never worse
   than f7gg's widen. A neighbour asked to pull it is queried again 10 s after the pull, and 10 s after each answer
   that it still lacks it, at most four times (`PULL_RECHECKS`): it holds the blob the moment its own fetch lands,
   and the next cycle may be minutes off. These rechecks run only in the first two cycles of a dead streak
   (`PULL_RECHECK_DEAD_CYCLES`), where a pull through more than one hop lands. A cycle that pulled stands one whole
   cycle from its first pull (`Want.cycleDue`), so the rechecks run out before the next cycle can open and pull again.
5. **Start over.** A new cycle forgets the answers and queries everyone again: phones move. It opens on the 60 s
   tick or a link-up, never on a timer of its own, so every hash's queries share one frame per neighbour. A neighbour
   that links again is queried again at once, and a fetch that went to it starts over.
6. **Back off a dead blob.** A cycle is *dead* when every neighbour asked said it lacks the blob: at least one
   answered, none held it or was fetching it, and nobody was asked blind. Each dead cycle in a row doubles the wait
   for the next, from 45 s through 90 s and 3 min to 6 min at most (`Want.cycleGap`). The second cycle, which widens
   the pull-through, keeps its pace. A lead (HAVE, SENDING or FETCHING) in a backed-off cycle ends the backoff and
   restarts that cycle from the lead, its answers kept. A neighbour that links is still queried at once. A cycle that
   asked anyone blind is never dead, so f7gg's round, and every cycle while the flag is off, keeps its pace.

Timers are the session's, earliest deadline wins, and one that fires steps every want due within 1 s of it before a
single flush. A recheck due within that window goes in the same frame, so wants armed together query together. A timer per hash once came due milliseconds apart and
sent one-hash queries that tripped the holder's budget (the bluetooth-reviewer's finding on this change).

Any wait on a holder counts its grace from the first look that finds it no longer streaming to us. While it
streams, the asker looks every 2 s (`STREAM_POLL_MS`); the timer is now "earliest deadline wins". This applies to
the blind round too, so it ships in release with the flag off. It only ever lengthens a wait.

A pull is not a fresh cycle for the neighbour that asked. The ask that would open the next cycle on the puller
opens it *before* the asker is marked, so the asker is never queried back in it; marked first, the mark died with the
cycle it was made in, and a dead blob's pulls went back and forth between the two.

Queries are batched: `wantAll` (the database re-arm at every link-up) and every entry point flush one frame per
neighbour, 32 hashes at most. Both types are unsigned and `relay = false`, ride live links only, are taken only
when `!relay` and from the link's own peer, never spend a key request (`NO_KEY_REQUEST_TYPES`), never count as
presence evidence (`FrameType.isUnsignedPointToPoint` in `HeardFrom` and `RelayedPresence`; the bypass lets a
garbage signature through), never ring an iPhone (`DoorbellPolicy.UPKEEP`), and never ride the frozen fast codecs.

The alternatives:

- **Strict one at a time with no answer.** f7gg rejected it: every non-holder ahead costs a whole grace.
- **A push notice when a blob lands.** 4tx5's `blobhave` was a *push* and was rejected; this one is a *pull* query.
  The serving side still never pushes, and still serves only a fresh `blobreq`.
- **Gating answering behind the flag too.** That makes `LOCAL_CAPABILITIES` build-dependent for no gain. A mixed
  fleet should answer whoever asks.
- **Querying unlinked Wi-Fi Aware peers over the message plane.** Deferred. A peer linked over Bluetooth but only
  reachable over Wi-Fi Aware is still found, and armed only for a blob worth it.

**Partial fetch, designed for and not built.** A neighbour holding only part of a blob (a `PartialBlobs` prefix)
would answer PARTIAL with `held`, and an asker with no whole holder would take that range and resume the rest
elsewhere. That needs three things:

- a bound on the ask (`BlobReqContent.end`; an older holder ignores it and serves to the end);
- a holder able to serve its immutable `.part` file;
- a `FileHeaderWire` marker so `FileIntake` keeps the range as a prefix instead of landing it.

The gate must be the asker sending `end`, never the capability bit alone. An older receiver would land the short file,
fail the hash and drop its own prefix (#116). The one hook needed now is the rule that a state a build does not know
is never taken for HAVE, which `aStateThisBuildDoesNotKnowIsNeverTakenForHave` pins.

**What it costs.**

- **One round trip before the first ask.** Up to 5 s more while a slow answer is due on a busy link: an answer
  interleaves between ~2 s chunks and waits behind the stack's buffer. The 2026-10-05 trial's `blob answer … in
  <ms> ms` lines set it: p50 252 ms, p90 2.0 s, max 7.4 s. 12 of 197 answers took over 3 s and 4 over 5 s, all from
  a holder feeding two files. At 3 s, three asks went out blind.
- **A lone neighbour pays the query too.** That keeps one code path; asking it directly is an open optimisation.
- **Multi-hop through a neighbour other than the hop** waits for the second cycle, where f7gg widened after one grace.
- **An answer from a busy holder can miss the wait.** A Bluetooth link feeding a file drains a frame only between
  chunks, and its pace wait stretches in suspend (ADR 2026-10.pj9w). The busiest holders can look silent and fall to
  the blind tier: never worse than f7gg, but tune the wait on loaded links, not idle ones.
- **A capable neighbour that lies** costs a grace, a confirm and a fallback, never a blob: a silent one degrades to
  the blind round.
- **A dead blob is noticed late.** A neighbour that came to hold a blob after saying it lacked it is found at the
  next cycle, up to 6 min later, unless it was asked to pull it in the first two cycles (its rechecks) or links again.
  Without the backoff, two blobs nobody held cost each phone about 40 pull-throughs in 25 minutes. With it, the
  2026-10-05 re-run measured 8–10 per phone. Query frames stayed at 51–75 then, because every pull still brought four
  rechecks and two hashes due 0.5 s apart rechecked in separate frames. Hence the two-cycle limit and the 1 s
  coalescing (unit test: one query a cycle plus one recheck, over 25 minutes).
- **A pull costs up to four more queries per neighbour asked** in the first two cycles of a dead streak, batched across
  hashes on one timer.
- **A flapping link re-pulls.** A neighbour that links again is open to the cycle again and is asked to pull again;
  only the cycle's first pull holds the cycle open, so flapping cannot keep it from closing.

**Never**:

- let an answer serve, want or stamp anything;
- answer HAVE from anything but the serve predicate;
- make the blind tier one-by-one;
- let PARTIAL imply a serve.

The iOS companion owes:

- the exact responder semantics: SENDING with the memo peek, the read order, `re`, the caps, and the unknown-state rule;
- the three new vectors in `wire-v1.json`;
- the bit in its HELLO and profile only once it answers;
- both types in its doorbell `upkeepTypes`.

Kept true by:

- `BlobHolderQueryTest`: both ends of the query, the tiers, confirm and fallback, the blind tier, pull-through and
  its rechecks (none deep in backoff, one frame for rechecks due together, none opening a second pull), FETCHING, the
  dead-cycle backoff (and a blind ask keeping its pace), the puller kept out of the cycle its ask opens, the grace from
  the stream's end, the budget, batching and the reserved state;
- `BlobExchangeTest`: every f7gg case, now the blind tier;
- `BlobHaveLabTest`: the better link of two holders, a holder walking away for a neighbour that cannot answer, and
  waiting on a fetching neighbour;
- `GoldenVectorTest` (`blobHaveContent`, `blobHasContent`, `blobHasContentReserved`);
- `InboundPipelineTest`, `FrameTypeTest`, `HeardFromTest`, `RelayedPresenceTest`, `DoorbellPolicyTest`, `ProtocolTest`,
  `CompositeMeshTransportTest` and `MeshMetricsTest`.

The device oracle, tag `MeshManager`:

- asker: `blob query → …`, `blob answer ← … in <ms> ms`, `blob fetch <h8>… → <node> (<tier>, load n)`,
  `blob confirm`, `blob fallback`, `blob pull`, `blob cycle <h8>… after <n> dead` (a cycle opened after a dead one);
- holder: `blob has → …`;
- each (asker, hash) owes one `file ATTACHMENT/<hash>`, on the holder the `blob fetch` line named.

## Amendment 2026-10-06 — an ask to a holder stands until its link goes (#140)

**What was observed.** On 2026-10-06 the lab Pixels ran `fix/ble-file-drain-120` with the query on. The P3 took five
photos twice each, side by side from the P9 (the author) and the P7. The P3's own outgoing frames were backed up:
its queries reached the three holders 13, 29 and 44 s late. Every answer missed the 5 s wait, so the author was asked
blind. Its HAVE landed inside the grace, but its header did not. The blind fetch ended without marking it failed, the
P7's late HAVE stepped the want with no fetch outstanding, and `askHolder` asked the P7, because a holder asked inside
its floor is not `askable`. Nothing on the wire withdraws an ask, so both served. Step 2's "or silence, and the next
holder is asked" has the same shape: a silent confirm on a backed-up link reads lag as a no.

**What changed.** No wire change.

- **A blind first pick that said it holds the blob is confirmed, not widened.** When its grace runs out, the fetch is
  re-tiered as a holder's and queried once more (step 2), and the round counts as widened, so a fallback asks the next
  holder rather than a second first pick. A pick that never answered widens exactly as f7gg did.
- **Silence holds; only a no moves on.** A holder whose confirm is silent, or that is still sending after
  `MAX_CONFIRMS`, is *held* to its floor: our last ask of it plus `SERVE_MEMO_MS`. The hold ends early when its link
  goes or the bytes arrive, and at the floor the next holder is asked. A NONE, FETCHING or unknown answer still moves
  on at once. While the hold stands, `fetch` stays set, so no cycle opens under it. A stream from the held holder
  restarts what is left of the hold from the stream's end, as any grace does, since our copy may sit behind that
  stream (#121). The hold can then outlast the floor it logged.
- **A backstop in `askHolder`.** A holder asked inside its floor whose answer is older than the ask holds the choice,
  however the fetch came to be empty.
- **A departure steps the want.** `MeshManager.watchNeighbors` hands departed links to `BlobExchange.unlinked`, which
  steps the blobs whose ask or hold was on one. The next holder is asked at once, not at the next tick.
- **Waits sized by the round trip.** Each matched answer's `in <ms>` is kept per neighbour and forgotten when that
  neighbour links again. The wait for its answer, and the wait for a confirm, become that round trip plus
  `QUERY_WAIT_MS`. An ask's grace gains the round trip. Both are capped at `QUERY_WAIT_CAP_MS` (30 s), and the lab's
  longer `waitMs` still wins.

**What it costs.** A holder whose ask really died, on a link that stays up, keeps the blob waiting up to 45 s from
the ask rather than about 15 s. A neighbour that answered slowly once is waited on longer until its next answer
measures a shorter trip.

The device oracle adds `blob hold <h8>… on <node> (<why>), floor <ms> ms` on the asker and
`blob fallback <h8>… <node> (floor) → next` when a hold runs out.

Kept true by `BlobHolderQueryTest`, which covers:

- the trace, held until the link goes;
- the hold running out at its floor;
- a silent confirm on a holder fetch;
- the round-trip wait and its cap.

It is also covered by `BlobHaveLabTest.aSecondHolderThatAnswersLateIsNotAskedWhileTheFirstOnesCopyIsOnItsWay`, the
trace end to end with the asker's frames held on both links.

## Amendment 2026-10-06 — the author's HAVE decides, and a holder feeds one copy at a time (ADR 2026-10.g24x)

A querying asker whose best holder so far is the frame's author asks it on its HAVE, without waiting for the rest of
the answers, as it already did for a holder on a live Wi-Fi Aware link: a trace waited 5 s on a carrier's NONE
after the author answered in 124 ms. And `onHaveQuery` answers SENDING for an ask the holder has parked behind
another Bluetooth copy of the same blob, so the asker waits on it as it would on any copy already on the way. Both
are ADR 2026-10.g24x's.
