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

**Amendment (2026-10-06, evening): a feed is judged at the pace its boost settles into.** The first device run of
item 4 fed two copies at once. In a NANOFF three-Pixel clique the Pixel 8 authored a 245 KB group photo. The Pixel 7 and
the Pixel 9 both asked it off its HAVE, 49 ms apart (item 2 makes every receiver ask the author at once). The first
feed's first chunk was paced at the unearned base (`bt file pace budget=4096 feeding=1 … earned=false`). The link
earned the boost about 0.6 s later (`bt boost … earned interval=15000us`), but a chunk's pace moves only at the next
chunk, two seconds of the base share later. So the second ask read 245 KB at 4 KiB/s, about 60 s left, against the
30 s bound, and was served beside the first. The 265 KB and 328 KB photos that followed did the same. A 65 KB one
parked and was served on the feed's end, as designed.

`BluetoothMeshTransport.fileFeedingTo` now passes `FramedLink.feedsFile` a settling pace. Until a stream's second
chunk has been paced, a link with a boost that can earn is read at its boosted share, whether it has earned yet or
not. A boost can earn when the link is an Android peer, the flag and `LinkTuneDiag.boostOn` are on, its tuner has not
given up, and no Bluetooth audio streams.

- **Earned or not yet:** a 1 s park poll landed in the gap between the earn and the next chunk's pace. That was the
  bluetooth-reviewer's finding on the first draft, which dropped the settle on the earn.
- **Counted in chunks, not time:** the next pace is read after a `delay`, which stops while the CPU sleeps. A 5 s
  elapsed-clock window, the second draft, could end on a sleeping holder before the pace had moved. That was the
  reviewer's second finding.
- **A link that never earns:** a dark pair, where neither side asks, is read at its own pace from its second chunk.
  So a parked ask waits about one base chunk, two seconds of feeding, for the guess.

An iPhone link has no boost and is read at its base as before. With `BLE_LINK_BOOST` off, or `--ez boost false`,
nothing changes. That one exception to "an unearned link's base share is not waited behind" (item 4) is bounded by
the settle.

**Device run (2026-10-06, ~20:50–21:05, the time-window draft):**

- The P3 was the author, unlocked and lit; Wi-Fi Aware was off.
- **DM cell:** five reps of a 410 KB photo to the P7, with the P8 and the P9 linked carriers. The P7 held each in
  27–31 s, from one copy fed at 18 KiB/s in 23.8 s, and both carriers deferred.
- **Group cell, reps 25–27:** the P9 (a carrier, which pulls a group frame at once), the P7 and the P8 asked within
  about 1 s. The two later asks parked and stayed parked across the polls. Each `blob unpark` landed 3–4 ms after the
  previous feed's `file … in`.
- **The third asker:** it reached `PARK_MAX_MS`, which is counted from its park and not from its turn, about 7 s into
  the second feed, and was served beside it.
- **Rep 28:** the author's screen timed out and the boost released. The second parked ask was served beside the feed
  at the settle's end.
- **The P9 as a sender:** its secure keyguard sleeps 10 s after a `KEYCODE_WAKEUP`, which is not user activity. That
  releases the boost, so it cannot be the lit sender headlessly.

**Device cells owed for the amendment:**

- **The dark holder:** the author's screen off and network adb disconnected; two lit receivers ask for a 245–330 KB
  group photo; five reps. Pass: every `blob unpark … → <second>` lands within about 2 s after the first feed's
  `file … in`, never shortly after its `blob park` while that feed has more than 30 s left.
- **With `--ez boost false`:** the second ask is served within about 1–2 s of its park, or never parks.
- **With the boost on and both sides dark:** within about 3 s.
