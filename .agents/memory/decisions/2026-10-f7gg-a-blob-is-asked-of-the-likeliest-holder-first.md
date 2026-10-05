---
id: "2026-10.f7gg"
slug: a-blob-is-asked-of-the-likeliest-holder-first
title: "A blob is asked of the likeliest holder first, then of every neighbour"
date: 2026-10-04
topics: [mesh, attachments, blob-exchange, wifi-aware]
---

# ADR 2026-10.f7gg — A blob is asked of the likeliest holder first, then of every neighbour

Status: Accepted (2026-10-04). Work item knit/knit-next#136. Amends ADR 2026-09.4tx5, whose reads of the link and
serve rules are unchanged; it changes who the asker asks, and when. No wire change, no DB change. Amended by ADR
2026-10.6e5t (2026-10-05): a neighbour whose link advertises `CAP_BLOB_HAVE` is queried first and the best holder
asked; this round is now the tier for the neighbours that cannot be queried (and for every neighbour while
`BuildConfig.BLOB_QUERY` is off), unchanged but for one fix — a grace counts from the first look that finds the holder
no longer streaming to us, not from the last look that saw it streaming (the #136 trial's S4).

**What was observed.** In scenario 3 of the ADR 2026-10.mptm device trial (2026-10-04, every lab phone on
`eb1bdf9b`), the Pixel 3 posted a 101,691 B picture to the Nearby room. The Pixel 7 copied it from its own sealed
group copy at 20:20:39, so two phones held it before anyone else asked. The Pixel 9 logged two whole copies:
`rx ATTACHMENT/8a126e48… 101691B in 70520ms ← rs6wwt…` and `… in 76538ms ← 24sd5j…`. The Pixel 8 and the lab
iPhone each took one from both phones as well. That is six 70-second Bluetooth feeds where three would have done.

This is the design, not a bug. `BlobExchange.want` sent a `blobreq` to every neighbour, and every holder answers a
fresh ask. ADR 2026-09.4tx5 counted "one copy per asker per holder" from the holder's side: one holder linked to
several askers serves each of them. It never weighed the asker's side, where one asker pays for every holder in
range. ADR 2026-10.mptm makes that case common, because a phone that holds a picture sealed now holds the room's
copy the moment the post lands.

**What changed.** A want opens a *round* of asks, and a round has two steps:

1. **One ask, of the likeliest holder.** The ranking, best first:
   - the frame's author, the one holder we can name;
   - a neighbour linked over the high-throughput plane (Wi-Fi Aware), then one that plane can reach (the new
     `MeshTransport.bulkRouteTo`; the composite asks its high-throughput child);
   - the neighbour the frame came from, since a carrier pulls what it carries;
   - anyone else, by node id so the choice is stable.

   A first ask of a reachable but unlinked Wi-Fi Aware peer also arms our half of the link
   (`expectBulkTransfer`). Only the larger id of a pair initiates, so when that is us, our mark is what raises the
   link.
2. **Then every other neighbour at once,** once that ask's grace has passed with nothing arriving.
   - The grace is `FIRST_ASK_GRACE_MS` (5 s). For a reachable Wi-Fi Aware peer it is that plus the composite's
     `BULK_GRACE_MS` (10 s), the time a holder waits for the link before it falls back to Bluetooth.
   - While the first-asked neighbour is streaming any file to us, its grace restarts. Our copy may be queued
     behind that file (ADR 2026-10.nxfb). One whose link is gone can serve nothing on it, so its grace is up at
     once.
   - A neighbour that links during a widened round is asked when it links. One that links during the first ask's
     grace waits for the widening.
   - A neighbour that links *again* holds no ask of ours: one sent before may have died with the old link. The
     link-up (`BlobExchange.linked`, ahead of the re-arm) opens it to every round again, and a round whose first ask
     went to it starts that first ask over, which re-asks it at once. Before this change every link-up re-asked its
     newcomer, and this keeps that. Without it, a round kept counting an ask lost to a dropped link and asked nobody
     until the next round, up to a minute later (a chaos sweep of `AttachmentResumeLabTest` caught it about one run
     in fifty).

The next round opens no sooner than `ROUND_MS` (the 45 s serve memo, so its fresh ask always passes the memo), on
the 60 s tick or a link-up, and starts again with one ask. A neighbour we are pulling the blob for (`onRequest`)
lacks it, so the round it asked in skips it. A later round asks it, because by then it may be the only path the
bytes have to us; `InternetPlaneLabTest`'s relay case is exactly that. A neighbour asked before for this blob is not
asked again while a file from it is streaming in (nxfb's rule, now per blob and per round rather than per tick).
The grace is timed on the mesh session's scope, one timer per want, so a round widens without waiting for the
tick. `onNeighborAdded(peer)` became `reask()`: the peer was only ever the trigger, and every blob takes its own
step.

The want carries its hints: `author`, `via` and `asker`. `via` is the hop, threaded from `InboundFrame.fromNodeId`
through `deliverChat`, and through `ForwardSync.onSeen`'s `from` into `onCarriedFrame`. Custody runs before
delivery, so a carried frame's want is usually the first; without the hop there, the chaos sweep caught the first
ask falling to whichever neighbour had the lowest id. The relay-avatar pull passes the author, and a group photo's pull the member whose frame
named it. The database re-arm (`rewantMissingBlobs`: startup, every link-up, the tick, after the 30-min sweep) passes
the sender of the earliest row or carried frame naming the blob (`attachmentsNeedingFetch`). Without it, a re-armed want
asked the lowest node id first, and a link-up batch that re-armed a want before its frame's own want landed opened the
round with nobody ranked (the reviewer's sweep caught it). A grace timer is never armed on a stopped session's scope, and one cancelled with its
session clears its mark, so the next session's first step arms afresh. The grace is wall time, so the lab runs it at
20 s (`LabLimits.firstAskGraceMs`, below `ROUND_MS`) and widens a round by a clock jump and a tick: a scenario that
counts copies must not race a slow runner's stall (the `mesh-lab-reviewer`'s finding). The link-up batch steps the
blobs before its per-newcomer hooks, so the digest stays the batch's last send, the step the lab waits on.

The alternative the user asked about first is strictly one neighbour at a time. Without a negative answer on the
wire, every non-holder ahead of the holder costs a whole grace, and N of them stack. A first ask of the author is
right almost every time the author is linked, and the widening bounds the miss to one grace. One at a time becomes
cheap only with an additive "I lack it" reply. That reply is a new point-to-point type with a paired iOS change,
the `blobhave` route ADR 2026-09.4tx5 also set aside. Not taken here.

The issue's other options were not taken either. Cancelling the slower of two streams needs a refusal record on
the wire, and the bytes already in the controller's buffer are spent anyway. Ranking by RSSI needs a quality read
the exchange does not have. Ranking by plane is the part of that idea we can read today.

**What it costs.**

- **Latency.** A first choice that cannot serve, such as a carrier still pulling or a non-holder hop, delays the
  widening by one grace: 5 s, or 15 s for a reachable Wi-Fi Aware peer. A pull that walks hop by hop gets no
  slower: the next hop's re-ask was already the tick.
- **Duplicates can still happen.** If the first-asked holder is slower than its grace, for example a serve queued
  behind a long file to another peer or a Wi-Fi Aware link that comes up late, the widened ask can still buy a
  second copy. That is the old cost, now paid only on a miss.
- **Not covered:** a peer reachable only on Wi-Fi Aware and not in `neighbors`. A `blobreq` rides only live links,
  so such a peer is asked when its link comes up; `deliverChat`'s arm toward the author is still what raises it.

The trap for the next person: never narrow the widening to "the rest, one by one" without a negative answer, and
never ask the asker back in its own round. Both look like savings and both strand a blob.

Kept true by `BlobExchangeTest` (`aRoundAsksTheAuthorAloneThenEveryOtherNeighbourOnceItsGraceIsUp`,
`theFirstAskGoesToTheAuthorThenTheFastPlaneThenTheSourceHop`, `aReachableFirstChoiceGetsTheBulkGraceOnTopBeforeTheRoundWidens`,
`aNeighbourThatLinksWaitsOutAFirstAsksGraceButIsAskedAtOnceInAWidenedRound`,
`theGraceIsTimedOnTheSessionSoTheRoundWidensWithoutATick`, `aFirstChoiceWhoseLinkIsGoneWidensTheRoundAtOnce`, `aBlobPulledForANeighbourIsNotAskedOfItThatRoundButIsTheNext`,
`aPeerAskedBeforeIsNotAskedAgainNorPassedOverWhileItsFileStreamsIn`, `aTimerArmedOnAStoppedSessionDoesNotBlockTheNextOne`, `aNeighbourThatLinksAgainIsAskedAgainAtOnce`),
`CompositeMeshTransportTest.aFilesPlaneIsTheHighThroughputChildsLinkThenItsReachThenNone`,
`InboundPipelineTest.aDeliveredAttachmentIsWantedWithItsAuthorAndTheHopItCameFrom` and
`onCarriedFrameWantsAnAbsentAttachmentBlob` (the custody hook's author and hop), and `BlobFirstAskLabTest`: the trial's
shape (two holders, one asker, one copy from the author), and a first choice that cannot serve (the round widens after
its grace and the picture lands).

The device oracle is the asker's `blob ask <hash>… → <node> first (<why>), widen in <ms> ms` and `… widened` lines
(tag `MeshManager`) against the holders' `file route:` lines: one route per hash per asker. The iOS port needs nothing:
its asks are its own, and what an Android holder serves is unchanged.
