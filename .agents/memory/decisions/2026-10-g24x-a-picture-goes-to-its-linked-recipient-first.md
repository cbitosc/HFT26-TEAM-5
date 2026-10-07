---
id: "2026-10.g24x"
slug: a-picture-goes-to-its-linked-recipient-first
title: "A picture goes to its linked recipient first, one Bluetooth copy at a time"
date: 2026-10-06
topics: [ble, mesh, blobs]
---

# ADR 2026-10.g24x — A picture goes to its linked recipient first, one Bluetooth copy at a time

Status: Accepted (2026-10-06). Amends ADR 2026-10.6e5t (the choice), ADR 2026-10.8jwn and ADR 2026-10.2da4 (the
pace), and the carrier pull `InboundPipeline.onCarriedFrame` has made since ADR 035. No wire change, Android only.

**What was observed.** On 2026-10-06 (main a294fe30, Wi-Fi Aware switched off on the Pixel 9 by `…debug.NANOFF`) the
Pixel 9 sent the Pixel 7 a 394 KB photo in a DM. All five phones were linked over Bluetooth. Four of them pulled it
from the Pixel 9 at once: the Pixel 7, the recipient, and the Pixel 3, the Pixel 8 and the lab iPhone, carriers that
had custodied the frame and eager-pulled its blob. The iPhone link never earns the boost, so ADR 2026-10.2da4's
"every feeding link earned" rule held all four feeds to the 4 KiB/s base, split four ways: `bt file pace budget=4096
feeding=4 share=1024`. 394 KB at 1 KiB/s is about 6.5 min. A 222 KB file the Pixel 3 pulled alongside it logged
`in 173085ms @1024`, and the Pixel 3's link had 430 KB of older files queued ahead of the photo. The Pixel 7 lost a
further 5 s before it asked: the author answered its holder query HAVE in 124 ms, and the choice waited on a
carrier's NONE (5092 ms).

It looked like the boost not working. It was fan-out: three of the four copies were carriers' copies, and one link
that could not earn the boost dragged the other three down with it.

**What changed.**

1. **A carrier holds a DM's picture back while the recipient is linked to it** (`CarrierPullPolicy`, read by
   `onCarriedFrame` and by the carrier half of `MeshManager.rewantMissingBlobs`). A pull waits while every carried
   frame naming the blob is a DM (`recipientId` set), its recipient is a live neighbour, and the frame was carried
   less than `DEFER_MS` (10 min) ago. That makes it a delay, never a veto. The 60 s tick and every link-up re-read
   custody (`ForwardStore.carriedAttachments`), so the hold lapses on its own when the recipient's link goes or the
   window passes. By then the recipient holds the picture and is as good a source as the author. A sealed receipt is
   opaque to a carrier and purges nothing (a cleartext one still purges the frame, and the want with it), so the
   window is what ends the hold. A recipient linked to the carrier but not to the author still gets its picture
   at once, because its own ask reaches the carrier and `BlobExchange.onRequest` pulls it through, which this never
   holds back. Group and room frames never wait: there is no one recipient, and every member pulls anyway.
2. **The author's HAVE decides the fetch** (`BlobExchange.answersIn`). A querying receiver whose best holder so far is
   the frame's author asks it as soon as it answers, without waiting for the other answers, just as it already did
   for a holder on a live Wi-Fi Aware link. The author is the holder the rest would only relay for. The exception is
   a neighbour still to answer that could ride a better plane than the author's (`bestTierFor` its route): an author
   on a Coded link waits for it (`BlobHaveLabTest`'s two-holder scenario caught the first draft asking it at once).
3. **Each Bluetooth link earns its own budget** (`BluetoothMeshTransport.paceFor`). A link feeds at the boosted budget
   while it has earned the boost itself, and at the base otherwise. The split over feeding links is ADR 8jwn's and
   unchanged. An unearned link (an iPhone, a Coded downgrade, one never heard) now holds only itself to the base.
   The `bt file pace` line is written per link, with `→ <node>` appended after `le`, so every parse of the line
   before it holds.
4. **A holder feeds one shared-budget copy of a blob at a time** (`BlobExchange.parkBehindFeed`, `serveParked`). An
   ask whose copy would ride a Bluetooth link on 1M/2M while another copy of the same blob is being fed on one to
   someone else (`MeshTransport.fileFeedingTo`: streaming now, or queued on a link with nothing else streaming;
   never after its end, unlike `fileInFlightTo`) is parked, and the asker's holder query reads SENDING. Only a feed
   that should end within the bound counts: `FramedLink.feedsFile(key, withinMs)` reads what is left of the stream
   against its pace, and a stream whose last write is older than the bound (a full stack buffer blocks the write) no
   longer counts. An unearned link's base share (an iPhone's) or a stalled one is not waited behind; otherwise an
   earned asker would idle its own budget for the whole bound. The feed's end serves the oldest parked ask at once,
   from the writer the write woke (`FramedLink.onFileFed` → `MeshTransport.fileFeedsEnded` → `BlobExchange.feedEnded`).
   A 1 s loop on the session is the net under it: that loop is a `delay`, which stops while the CPU sleeps. One copy at a time lands the first in a quarter of
   the time four would take, and its receiver then holds a second source. A parked ask is served alongside the other
   feed once it has waited `PARK_MAX_MS` (30 s, about a 500 KB photo at the boosted pace), so a feed to a stalled
   link never holds everyone else. It is dropped when its asker unlinks or stops asking for `SERVE_MEMO_MS`; by then
   the asker has moved on to another holder (ADR 6e5t's hold to the floor). A copy riding Wi-Fi Aware (a live link,
   or a large blob the composite will raise one for) or Coded is never parked, and with no session to serve it later
   nothing is parked at all.

**Not taken.**

- **Cancel a carrier's pull when the recipient acks.** Carriers cannot read a sealed receipt, which is the receipt
  every Android peer sends. That would take a cleartext hint on the wire.
- **The receiver queries no one when the author is linked, and asks it blind.** The query is one small frame, and
  it is what tells a receiver the author has evicted the blob. Asking on the author's HAVE keeps that and costs one
  round trip, 124 ms in the trace.
- **Drop the split.** ADR 8jwn measured three feeds at 28 KiB/s each as worse for every text behind them, and #120's
  fan-out cell under HIGH has not been run. One copy at a time is how this lowers the number of feeds.

**What it costs.** A carrier that is linked to the recipient takes its copy up to 10 min late. A late joiner who
meets it in that window still gets the picture, because its own ask pulls it through. A second asker waits up to
30 s behind the first copy, longer than a parallel feed would take when the two links really drain independently.
The single-link drain trial says they do not, but the fan-out cell is still owed. The hold reads `transport.neighbors`,
so a recipient seen only over LoRa never holds anything back.

**Counters.** `MeshMetrics.files()` carries `blobAsksParked` and `carrierPullsDeferred`, and so does the debug bridge's
STATE. The lab awaits them, because custody holds a frame before its pull is decided and a parked ask leaves no file.

**Tests.** `FramedLinkTest.aFileIsFedOnly…`, `CarrierPullPolicyTest`, `ForwardDaoTest.carriedAttachments…`,
`InboundPipelineTest.onCarriedFrameHoldsBack…`, `BlobHolderQueryTest` (the author's HAVE and its Coded exception, park
and serve, the feed-end edge, the parked ask's bound, a feed too slow to wait behind, a copy that shares no budget, a
dropped park), and `PictureFanoutLabTest` (a carrier leaving the
picture to the recipient and taking its copy later, a second ask behind a feed, a park beside a feed that never
ends). `AttachmentLabTest`'s #79 scenario now asserts that Carol carries without pulling. The lab parks for
`LabLimits.parkMaxMs` (2 s by default): a held pipe is a feed that never ends.

**Device trial owed** (the bluetooth-reviewer's three cells).

- **The trace's shape.** P9 sends with NANOFF and the boost on, screen on. P7 is the recipient, and P3, P8 and the lab
  iPhone (foreground) are linked. Send a fresh ~400 KB DM photo P9→P7, five reps. Read the P9's
  `bt file pace … → <node>`, `blob park` and `blob unpark`, and the P7's `rx … in <ms>ms`. Pass: the P7 holds it in
  under 60 s every rep and is never parked behind the iPhone's feed, and each Android carrier's pull waits about
  10 min. One more rep with the iPhone backgrounded before the send: any park should release by about 30 s.
- **A dark holder.** The same set with the P9's screen off and network adb disconnected; a group photo that the P7 and
  the P8 both ask for. Pass: each `blob unpark … → <second>` follows the first feed's `file … in` line within about 2 s.
- **Release defaults.** Two Androids with `BLE_LINK_BOOST` off, and a room photo two peers pull. Pass: the second
  asker's `rx` lands no later than a parallel feed at budget ÷ 2 would deliver it.
