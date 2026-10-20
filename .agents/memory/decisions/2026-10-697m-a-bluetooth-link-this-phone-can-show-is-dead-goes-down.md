---
id: "2026-10.697m"
slug: a-bluetooth-link-this-phone-can-show-is-dead-goes-down
title: "A Bluetooth link this phone can show is dead goes down, and a peer that has sent a KEEPALIVE answers one"
date: 2026-10-19
topics: [ble, transport, wire]
---

# ADR 2026-10.697m — A Bluetooth link this phone can show is dead goes down, and a peer that has sent a KEEPALIVE answers one

Status: Draft (2026-10-19), JVM-tested (`LinkSilenceTest`, `SocketStampsTest`, `FramedLinkTest`'s keepalive cases).
Device trial of 2026-10-19 (P3 and P8 on `bf93901f`, the P7 on 2.10.0-alpha.0 and the P9 on alpha.1 as older peers):
frozen-peer probes passed both ways (silent close at 150 and 152 s, the ACL gone within about 2 s, a relink after
`kill -CONT` with no restart), and the unanswered-read rule failed and was dropped (Rejected, below). GitLab #203. One cross-platform rule (below); the knit-ios half is a notice, not code, because
iPhone links are exempt.

**What was observed.** Two Redmi 13Cs (MediaTek MT6769, Android 15, 2.10.0-alpha.0), field logs of 2026-10-19 lined up
by `scripts/fieldlog.py pair`. Phone B wrote `bt_down` for A, `reason EOF`, at 17:07:39. Phone A never got the close:
it kept the link in `links` for 36 min, until its process died at 17:43:30. A's link-RSSI samples froze at −73 from
17:10, its writer stopped draining (`digestsReplaced:4` at 17:35), and three walkie calls through the link never rang.
After the restart A still counted `le:1` against B's `le:0`. B's process and radio worked throughout. So A's stack lost
the disconnect and kept a connection nothing used.

Nothing in Knit read the silence. `FramedLink` has no receive deadline. `LinkFraming.Type.KEEPALIVE` was read and
ignored, and no build had ever sent one (knit-ios's `LinkRecord.swift`: "Android never sends one"). A write that never
returns is unbounded. `LinkFade` only steers the walkie's cast-beside.

**What changed.** One pure policy per link, `mesh/bluetooth/LinkSilence`, fed by two pass-throughs under the socket's
streams (`SocketStamps`: when a byte last came in, and since when the writer has sat in one `write()`), and by the
link's PHY handle. One transport loop (`BluetoothMeshTransport.silenceLoop`) steps every link and sleeps on
`elapsedWait.receiveWithin` until the earliest is due. A link is shown dead three ways:

1. **Probes, toward a peer that answers.** Each side writes one empty `KEEPALIVE` right after `framed.start()` (the
   announce). No shipped build sends one, so receiving one proves the peer runs this rule, and the link is marked
   "answers". On such a link, no byte in for 90 s writes a probe; no byte in for 30 s after its write returned writes a
   second; 30 s after that with still no byte, and at least 90 s since the last, the link closes. That is about 150 s
   from the last byte, on this side's elapsed clock. A live peer's answer is L2CAP data, which wakes this CPU, so the
   wait runs on the elapsed clock soundly.
2. **Its own controller, on a link with a PHY handle** (`BlePhyControl`). No byte in for 90 s, and the handle's GATT
   client was told the connection ended after the last byte (`lostAt`, a `STATE_DISCONNECTED` while it held the link).
   Our controller's word, so a dozing peer cannot trip it, and it covers a peer on an older build. Unanswered link-RSSI
   reads are no part of it (Rejected, below). It never reads `LinkFade.fading()`: its
   −95 dBm half fades weak but live links on purpose, and its `attached = false` path hides the disconnect. **Only toward
   a peer that does not answer does it close.** Toward one that answers, the reading is ignored: the probes run on the quiet
   alone, and only they close the link (the bluetooth-reviewer's finding). A PHY client's loss still probes early there,
   through the edge path (`onLost` → `onLinkEdge`). Our stack can be wrong: AOSP's ATT response timeout (`gatt_rsp_timeout` → `gatt_disconnect`)
   reports `STATE_DISCONNECTED` to every client on the connection while the CoC keeps the ACL up. Closing on it would flap
   every relinked Coded link to a dozing peer after each 90 s of quiet.
3. **A write that never returns.** A non-doorbell link whose writer has sat in one `write()` for 120 s. A segment is at
   most 8 KiB (`BluetoothSocketLink`), and a feed is paced or windowed between writes, never inside one.

An LE ACL disconnect for a live link's address, or its PHY client's loss, is logged (`bt link edge`) and probes an
answering link at once. It never closes a link on its own: the edge names no handle, and #142 saw two ACLs to one peer.
A close still waits for 90 s of silence.

**The cross-platform rule.** *A peer that has sent a `KEEPALIVE` answers each `KEEPALIVE` it receives with one, except
one that meets an announce or probe of its own still waiting for a `KEEPALIVE` back.* Each side counts its announces and
probes not yet matched. A keepalive that comes in while that count is above zero is the answer to one of them, or a
probe that crossed it: the count drops by one and nothing is written. Otherwise this side answers. So two probes that
cross are each other's answer, an answer is never answered, and every exchange ends.

The design pass proposed a time rule instead: answer unless this side wrote a `KEEPALIVE` within the last 30 s. That
rule answers an answer whose legs each took over 15 s, which a keepalive queued behind a full stack buffer can do. The
pair then bounce for good, one record per crossing. It also left a probe unmatched for ever when a second probe went out
while the first one's answer was still crossing. `LinkSilenceTest.twoAnsweringSidesEndEveryExchangeWhetherProbesCrossOrNot`
drives two policies against each other over delays from 5 ms to 25 s and checks that every exchange ends.

The record is the existing type 5, empty. No HELLO bit, no new record type and no `Protocol` constant: every build since
the BLE plane reads and ignores it, so the announce is additive (`docs/WIRE_COMPAT.md`).

**iPhone links are exempt.** A `CAP_DOORBELL` link gets no announce, no probe, no answer and no stall close. iOS
suspends a backgrounded app with its link up and silent by design, and resumes it for a GATT write but not for L2CAP data
(ADR 2026-09.dqvb). Its bytes are what they were. An iPhone design (ring, then wait) would be a later paired change.
iPhone links never get a PHY handle either, since CoreBluetooth has no Coded PHY.

**The teardown.** A silent close is the ordinary `teardownLink(…, "silent", only = link)`. It updates `neighbors` and
`reachable` and records the field event (`bt_down reason SILENT`) first, then closes the socket on an IO coroutine that
nothing waits for (`closeOffThread`). If that close has not returned within 5 s on the elapsed clock, it logs
`bt link close slow`. A writer blocked in a write the stack will never finish holds up only its own thread. The link
goes into a weak set (`closedSilent`) so its reader's end bumps no backoff streak and writes no `(eof) streak=` line,
which `scripts/ble-link-trial.py` would count as a drop. The ACL arm (ADR 2026-10.b8yf) then releases its ACL as it does
for any link's end. When the arm reports a closed-on-local-evidence link's ACL as `kept`, and this phone still holds
more LE connections than links, the transport logs `bt stack suspect <addr> (…)`. It follows `LOST` closes alone, the
one close left on local evidence: probe and stall closes are not, since a frozen peer's controller keeps the ACL up too,
and a `LOST` close happens only on a link to a peer that does not answer. It is a soft signal: the LE count comes from
`getConnectedDevices(GATT)`, which ADR 2026-10.b8yf (#142) says is not an oracle (it lists another device's connection
only while some GATT server is registered). #204 must weigh it, never take it as a verdict. The user-facing verdict is
issue #204.

**Rejected.**

- **A receive deadline on the 60 s digest re-offer alone** (the issue's option 1). The re-offer stretches while the
  peer's CPU sleeps (pj9w measured a median 110 s), and an iPhone's link goes silent by design, so any bound short
  enough to matter closes healthy links.
- **Taking a fading link out of `CallReach`** (option 4). Out of scope: with the dead link closed, the case mostly goes.
- **Releasing a previous process's ACLs at start** (option 5). Out of scope, and it waits for the trial to show whether
  the orphan outlives the close.
- **Closing on unanswered link-RSSI reads** (the first cut: no byte for 90 s and three reads of the current unanswered
  run after it, toward a peer that does not answer). The device trial of 2026-10-19 showed unanswered reads say nothing
  of the connection: after the P8's process was frozen (`kill -STOP`) for 4 min and resumed, its `BlePhyControl` reads
  on its two healthy links to older peers (the P7, the P9) timed out continuously for over 4 min, up to 27 in a row at
  about 6 s apart (`rssi read unanswered (27 in a row, timeout)`), with no `client lost` and traffic flowing throughout.
  The rule then reduces to "quiet for 90 s", which a dozing older peer passes routinely, so it would close healthy links
  over and over, and OEM freezers (HyperOS, the field case's phones) freeze apps much like this. `LinkFade` still counts
  unanswered reads for the walkie's hedge, which casts beside a link and never closes one; the
  `bt phy … rssi read unanswered` line stays.
- ADR 2026-10.79yb's ninth amendment rejected "a keepalive on the Bluetooth link to detect silence" for the walkie's
  hedge, on the grounds that it would need a new record. The record existed. This ADR uses it to find a dead link, not
  to hedge a weak one, and leaves the walkie's hedge as that amendment set it.

**What it costs.**

- **Airtime.** None on an awake idle pair: every shipped build writes the custody digest to every link each 60 s
  (`MeshManager.reofferToNeighbors`, which calls `ForwardSync.onNeighborAdded` even with empty custody), well inside
  the 90 s. A dozing pair spends one 5-byte record each way per silence past 90 s. Toward an older build: one record at
  link-up, nothing after.
- **Wakes.** One more loop in the transport, on `elapsedWait`, so it looks at the clock every 2 s of awake time while a
  link is held (pj9w's cost, a comparison per look), and waits on its channel alone with no link. No tick per link and
  no poke per byte. Pokes come from a keepalive in or written, a PHY client's loss, an edge, and a link coming up.
- **The bound is not everywhere.** Toward a peer on this build: about 150 s. Toward an older Android peer: 90 s after its
  PHY client's loss, where the link has a handle and the stack reports the loss, else only the 120 s write stall, and
  only once something is written and sits. **#203's own field case falls back to the write stall** toward an older
  peer: its stack lost the disconnect, so no client loss came, and its writer blocked 15–30 min after the peer's EOF —
  the close would have come 120 s after the first blocked write, not 150 s after the last byte. An older idle peer whose
  stack lost a disconnect still holds the link until the next write sits.

**The trap.** A keepalive is not data. It must never touch `FramedLink.lastActivityAt` (the Wi-Fi Aware quiescence
supervisor reads it) or `outAt`, and the stamps live in `BluetoothSocketLink`, not in `FramedLink`. Wi-Fi Aware links never
call `sendKeepalive`, so they read and write exactly what they did. The stamps sit beneath the buffering: the HELLO was
read through the buffered input, so wrapping it again would strand those bytes. A new rule that closes a link on a
reading must say which reading it compares and must hold for a dozing peer, Coded at range, a file in flight and an
iPhone. The test list is `LinkSilenceTest`.

**The knit-ios notice.** iOS sends no `KEEPALIVE` and is exempt here, so nothing changes for it today. The rule binds a
future iOS sender: never send a `KEEPALIVE` on a link without answering the peer's by the rule above. The announce counts
as an initiator: it is one of the side's own keepalives awaiting a match, exactly as a probe is, so the peer's announce
that crosses it is not answered. The comment at
`Sources/KnitWire/Link/LinkRecord.swift:13` ("Android never sends one and ignores it") goes stale. Android now sends one at
link-up on every non-doorbell link and answers one, though never on a link to an iPhone.
