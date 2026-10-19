---
id: "2026-10.2drc"
slug: a-pull-through-carries-a-hop-budget-and-a-deadline
title: "A pull-through carries a hop budget and a deadline"
date: 2026-10-17
topics: [mesh, wire, blobs]
---

# ADR 2026-10.2drc — A pull-through carries a hop budget and a deadline

Status: Accepted (2026-10-17), work item #195. Amends ADR 2026-10.6e5t (step 4, the pull-through, and its backoff)
and ADR 2026-10.f7gg (a cycle that asked blind no longer keeps its pace for good). Additive wire change: two optional
fields on `BlobReqContent`, no capability bit, two vectors added and none moved. No DB migration (the fetch rows only
project two more columns). Android only for now; the iOS companion is owed (below). Device trial owed.

**What was observed.** The Pixel 9 held a DM card, `92134a58…`, from a relay-only contact, sent 2026-10-15. Its bytes
never arrived. Its row re-armed the want every 60 s tick (`MeshManager.rewantMissingBlobs` → `wantAll`, no age bound),
which refreshed `wantedAt`, so the 30-min sweep never reclaimed it. Every dead cycle ended in 6e5t's step 4: each
neighbour that said NONE was asked to pull it through. The Pixel 7 and the Moto G, which held no row and no custody row
for it, each made a want of their own, an *echo*, and queried and pulled their own neighbours. The backoff stops at
6 min, so all three repeated the round every 6 min for good.

Three rounds of review then found four loops of the same shape, each a phone kept alive by the asks of phones that
were only echoing it:

1. **Echo to echo.** A pull ask refreshes `wantedAt`, and each cycle forgets whom it pulls for, so two echoes on linked
   phones pulled each other, and the want they echoed, for good, with the root's row gone.
2. **An ex-root.** A want that was once ours (a carrier whose custody expired, a recipient whose row was deleted) was
   pulled by its own echoes for good.
3. **A link that re-forms.** Every Wi-Fi Aware sync and Bluetooth redial makes a newcomer, and a newcomer is asked to
   pull, so two echoes kept each other alive across a link that kept re-forming.
4. **A stream of newcomers.** Each newcomer's own early cycles asked the echo back, which reset its clock and, under
   the second pass's rule, vouched for it.

The local rules tried in review each closed one loop and opened another: a sticky own flag (loop 2), an own window
and an echo cycle count (loop 3), an `askedBy` set (stranded a picture only an iPhone that had asked back could bring),
a vouch from a querying neighbour (loop 4, since an echo's pull vouches as well as a root's). They shared a cause. A
phone cannot tell a root's ask from an echo's by anything it holds, so every one of those rules was a guess. The fix
puts the distinction on the wire.

**What changed.**

- **The wire.** `BlobReqContent` gains `pull: Int?` and `until: Long?`, CBOR text keys `pull` and `until`, null and
  omitted by default, so a plain ask is the bytes it always was (`blobReqContent` did not move).
  - `pull` is how many phones deep the ask may still go, the receiver counted. A want of our own asks with
    `BlobExchange.PULL_BUDGET`; every ask carries its want's budget, a fetch, a blind ask and a pull alike.
  - A receiver that lacks the blob and is asked with `n` wants it with `n − 1` (`budgetOf`, `n` capped at
    `PULL_BUDGET`). At 1 or more it may ask its own neighbours to pull, with what it holds. At 0 it only queries and
    fetches. Asked with 0, it serves the blob if it holds it and makes no want (`blob no-echo … (fetch only)`,
    `blobEchoesDeclined`).
  - An absent `pull` reads as 1: a fetch only, never a pull further. That is what an older Android build's ask and the
    iOS port's today get.
  - `until` is the root's deadline for pulling through, epoch ms on the frame's own clock, compared with the
    receiver's clock as custody's TTL is. Echoes inherit it into their own asks. Past it nobody pulls the blob through
    or makes an echo of it (`blob no-echo … (past its deadline)`); a holder still serves it.
- **Custody's half reads live frames only.** `ForwardDao.attachmentsNeedingFetch(now)` and `carriedAttachments(now)`
  take `liveIds`' expiry predicate, so the 60 s tick never re-arms a carrier's full-budget want from a frame past its
  TTL that the prune has not swept yet.
- **The deadline.** `PullThroughGate.until` folds a want's rows as ADR 2026-10.js2p folds them. When every row naming
  the hash is a DM's, the deadline is the latest such row's `sentAt` + `ForwardRepository.DEFAULT_TTL_MS`. Otherwise
  (a group's row, a room's) there is none. `MessageDao.attachmentFetchRows` and `attachmentFetchRowsFor` project
  `sentAt` and `(recipientId IS NOT NULL) AS dm` for it. The re-arm passes the deadlines to `wantAll`; a delivery's
  `want` has none until the next tick re-derives it. A pull never needed a carrier (a phone in the middle fetches by
  hash from whoever says HAVE), so the deadline is not where a pull stops working. It is the DM's own: the window in
  which the mesh carries the DM itself.
- **The refresh rule** (`BlobExchange.noteAsker`). A want keeps `budget` and `until`.
  - A want of our own has `PULL_BUDGET`, re-derived on every own want, like `fastOnly`. Its deadline is set, none
    included, by a caller that read every row naming the hash (`Reach.rederived`): the re-arm (`wantAll`), a delivery
    (`deliveredFetch` folds the stored rows with the new one, and a row that is no DM's means none) and the tap. A
    caller that did not — a carrier's custody want, which names its frame's `sentAt` + TTL for a DM, an avatar's or a
    group photo's — sets one only when it names one, and otherwise keeps it. So a DM delivered or carried past its
    window never gets a step without its deadline, and a group post naming a picture an old DM named lifts that DM's
    deadline at once.
  - Any other site that makes a want gives it budget 0 (`Want.budget`'s default): a refused splice's want, made again
    after a sweep, asks with `pull = 0` until the next re-arm finds a row behind it. A parked ask keeps its `pull` and
    `until` (`ParkedAsk.ask`), so its unpark echoes as it asked.
  - An ask whose budget `b'` (that is, `n − 1`) is at least the want's own refreshes `wantedAt`. One with more raises
    the want's budget and takes the ask's deadline (no deadline is the widest). One with less refreshes nothing, and
    neither does one with no field at all; its sender is still a puller for the cycle.
  - A want pulls through, and asks a newcomer to pull at once (`pullNewcomers`), only while its budget is 1 or more and
    its deadline is ahead. A newcomer is one only in the cycle it linked in: `openCycle` and `unlinked` clear it. A cycle
    that withholds a pull counts dead and backs off (`blob no-pull … (fetch only | past its deadline)`,
    `blobPullsWithheld`).
- **What it gives.** Only a root mints budget, and every hop spends one. No ask an echo makes, back to the phone it
  echoes or to another echo, can carry as much as the want it would refresh, so none of the four loops can keep a want
  alive. A live root's first ring is refreshed by its pulls (`PULL_BUDGET − 1` against `PULL_BUDGET − 1`), the second by
  the first's, and so on down.
- **Removed:** the own window, the echo cycle count, `askedBy`, the vouch, and `freshLinks` as a spent echo's
  exception. Kept: the blind-round backoff (a cycle whose blind asks brought nothing is dead from the third lead-less
  cycle on, which amends f7gg), the long backoff past a day (`LONG_DEAD_MS`, `MAX_LONG_BACKOFF_SHIFT`), and the
  fast-only exemption from it.

**Why `PULL_BUDGET` is 3.** A root's ask reaches its neighbours with 3, so their echoes pull with 2, the next ring with
1, and the third ring only queries. A holder four links from the root is still fetched: the third ring queries it,
and the bytes walk back a hop per cycle. That covers the shapes the field has shown, where the holder is the author
behind the hop the frame came from and at most two phones that carry nothing for it. One root's echoes stay within
three rings. A larger budget buys reach the custody plane already covers inside the window, since every carrier holds
the frame and wants the blob itself, at a full budget. A smaller one cuts off the hop behind the hop.

**What it costs.**

- **Reach.** A holder five or more links from a root that nothing else carries for is not found by pulling. Each phone
  between them would need a want of its own, which a carrier inside the custody window has.
- **Reach to a DM's far end past a day.** Past the deadline nobody pulls it through: an author re-wanting its own
  picture after a wipe, a frame that arrived late, a recipient out of reach for a day. It is reached again once the two
  ends link directly, or once a neighbour comes to say HAVE.
- **A want outlives its root by a TTL per budget level.** Once a root's own re-arm stops, its echoes' asks back can
  make it again, but only with less budget. Each generation lives at most `FETCH_TTL_MS` past the last ask that kept
  it, so the lot ends within (`PULL_BUDGET` + 1) TTLs, about two hours.
- **Latency once a pair is a day dead.** The long cycle can be about 3.2 h. A newcomer is still pulled at once and
  queried at once.
- **A blind neighbour is asked at the backed-off pace** past the first two lead-less cycles.
- **A restart forgets the streak**, and so does a sweep after half an hour with no neighbour.
- **The mixed fleet loops until the iOS companion lands.** An older Android build and the iOS port today ask with no
  budget, and the iOS port refreshes its own want on any ask and echoes it without bound. One of our fetch-only wants
  still asks blind every neighbour that cannot answer a query, an iPhone included, and that ask keeps the iPhone's
  want alive; the iPhone's asks back make ours again. So an Android fetch-only want and an iPhone want keep each other
  going with no root. Android alone cannot end this. What it bounds is its own half: an ask with no `pull` field may
  make a want here when none exists, but never refreshes one (`Reach.fieldless`), so each Android generation lives one
  TTL at most against such a neighbour. The companion ends the loop.

**Alternatives.**

- **Local rules** (the three passes above): every one depended on telling a root from an echo, which a phone cannot
  do.
- **A capability bit.** Not needed. A holder that predates the fields serves as before and echoes as before. One that
  knows them reads an absent field as a fetch only, which is the conservative reading.
- **Giving up for good, with a UI and a column** (#195's option 3). Deferred to `.agents/memory/roadmap.md`. The cost
  is bounded without it: a row past its deadline costs one query per neighbour a few times a day.

**The iOS companion owes:**

- the two fields on its `BlobReqContent`, omitted when null;
- the absent-is-1 reading and the `pull = 0` serve-only rule;
- `n − 1` on its echoes, the refresh rule, and no pull or echo past `until`;
- the two vectors, `blobReqContentPull` and `blobReqContentPullSpent`;
- `PULL_BUDGET` = 3 on its own wants.

**Kept true by:**

- `PullThroughGateTest`;
- `WireSerializationTest`, `GoldenVectorTest`, and `FrameTranscoderTest`'s pass-through of the two keys;
- `BlobHolderQueryTest`:
  - past the deadline, no pull, with a newcomer that holds the blob still fetched;
  - the latest own want sets the deadline;
  - each ring spends a hop and the last only queries;
  - an ask carrying less never refreshes;
  - an own want whose re-arm stopped ends within its generations;
  - newcomers cannot keep an echo alive, and neither can a link that re-forms;
  - past the deadline, no echo is made and an echo stops pulling;
  - an absent field is a fetch only, and `pull = 0` is served only;
  - the blind round, the long backoff and the fast-only ceiling;
- `StrandedAttachmentLabTest`, each case ending in `assertConverged`: (a) past the window, (b) inside it, (c) echoes
  ending with their root, (d) two ex-carriers, (e) a blind neighbour, (f) a flapping link, (g) an echo bringing what an
  iPhone came to hold, (h) a long-dead root and a newcomer;
- `PullBudgetLabTest`, each case ending in `assertConverged`: a stream of newcomers, reach to the budget and no
  further, an older build's ask, a neighbour on the iOS port's rules (`LabLimits.pullBudgets` off) whose each of our
  wants lives one TTL at most, a DM delivered past its window pulled through by nobody from its first step, and a want
  a refused splice makes again asking with no budget, and a group post naming a picture whose DM is past its window,
  pulled through at once;
- `ForwardDaoTest`'s live-frames case, `MeshManagerTest`'s carrier and re-arm deadlines, and `BlobHolderQueryTest`'s
  newcomer pull, an own want restoring an echo's budget, and an unparked echo keeping its budget and deadline.

The device oracle adds `blob no-pull` and `blob no-echo`, and every ask line (`blob ask`, `blob fetch`, `blob pull`)
ends `[pull n]`, or `[pull n, until <ms>]` with a deadline. A `blob cycle … next if dead in` line past 360000 ms means
a want dead for a day.

## Amendment (2026-10-19): the deadline starts the long backoff too

**What was observed.** The Pixel 9, a lab phone reinstalled more than once a day, still held the #195 card's want,
`92134a58…`, whose deadline had passed on 2026-10-16 at 15:08. All day it cycled at the 6 min ceiling (`blob cycle
92134a58… after 92 dead, next if dead in 360000 ms`), querying three neighbours and asking one each time. The long
backoff waited on a day of the dead streak (`Want.deadSince`), which lives in memory only: a restart forgets it, and so
does the sweep after half an hour with no neighbour. A phone restarted more than daily never reaches it.

**The rule.** A dead streak is long dead (`Want.longDead`) once it has reached the 6 min ceiling
(`MAX_BACKOFF_SHIFT` dead cycles) and either it is `LONG_DEAD_MS` old, as before, or the want's own deadline
(`Want.until`, wall clock against the frame's clock, as `pullWithheldWhy` reads it) has passed. Each further dead cycle
of a long-dead streak doubles on (`longDeadCycles`), to `MAX_LONG_BACKOFF_SHIFT`. The deadline is re-derived from the
rows on every re-arm, so a restart cannot forget it. The ramp past a deadline is 45 s, 90 s, 3 min, 6 min, then 12, 24,
48, 96 min and about 3.2 h (11 520 000 ms), the 3.2 h cycle from the ninth. Requiring the ceiling keeps the first four
steps; the count is read before the cycle's own is added, so the 6 min cycle is kept too. A dead cycle that is not long
dead clears `longDeadCycles`, so a deadline a re-arm lifted (a group row naming the hash, a newer DM's row) and later
restored ramps from 12 min again rather than jumping back to hours. The day's branch is unchanged in practice: a
streak a day old has opened far more than three dead cycles (the first three take 5¼ min). Unchanged as well: any lead
ends the streak, a neighbour that links is queried at once, nothing is pulled or echoed past the deadline, and a
fast-only want keeps the 6 min ceiling. No wire, schema or iOS change; the rule is local pacing, not convergence.

**What it costs, amended.**

- **Latency once a pair is a day dead** now holds from the deadline as well: a DM's picture past its custody window
  whose holder stays linked but blind is asked at most every 3.2 h.
- **A restart forgets the streak** is no longer true of a deadline-bound want past its first four dead cycles after the
  restart: its gap is 12 min about 11 min after it, and 3.2 h about 3.2 h after it. A want with no deadline (a
  group's picture) still forgets it.
- **A blind neighbour is asked at the backed-off pace** sooner: from the fifth dead cycle past the deadline.
- **New: a neighbour linked the whole time that comes to hold the bytes after the deadline** (a spool fetch, an iPhone
  asked blind) is found only at the next cycle, up to about 3.2 h away. A newcomer is still queried at once.

**Kept true by:** `BlobHolderQueryTest`'s past-deadline ramp, deadline-ahead ceiling, lead reset and lifted-then-restored
deadline cases, the no-deadline day case and the past-deadline withheld-pull counts; `StrandedAttachmentLabTest`
unchanged. A `blob cycle … next if dead in` line past 360000 ms now means a want dead for a day **or** past its
deadline.
