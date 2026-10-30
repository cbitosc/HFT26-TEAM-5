---
id: "2026-10.kxnw"
slug: a-call-between-neighbours-carries-its-media-over-a-wi-fi-direct-group
title: "A call between neighbours carries its media over a Wi-Fi Direct group"
date: 2026-10-08
topics: [calls, wifi-direct, transfer]
---

# ADR 2026-10.kxnw — A call between neighbours carries its media over a Wi-Fi Direct group

Status: Accepted (2026-10-08)

**What was observed.** A call (ADR 2026-10.3tee and its minimal build) finds its media path through ICE, and on
this branch ICE has only what the spool's TURN credential and its STUN fallback give it, plus a shared LAN. Two phones
side by side with no spool and no STUN can *signal* a call — the sealed `CTL_CALL` frames ride the mesh — but have no
route for the audio unless they share a Wi-Fi network, and with an Internet path they relay through a TURN server
two metres of air could have replaced. 3tee left "calls over the radios" out of scope. The radio that can carry Opus
is not the mesh's Wi-Fi Aware data path: `WifiAwareTransport` never exposes its `Network`, caps a link at 30 s, and
libwebrtc neither sees a network without `NET_CAPABILITY_INTERNET` nor gathers on an IPv6 link-local. It is the
Wi-Fi Direct group the direct transfer already raises by credentials alone (ADR 2026-09.wtmz).

**What changed.** A call to a mesh neighbour (`MeshController.neighbors`, the transfer's own gate) whose profile
carries `CAP_DIRECT_TRANSFER` rides a Wi-Fi Direct group the two phones raise, as one more network ICE prefers.
`docs/VOICE_CALLS.md` §3.10 is the protocol; in short:

- **The caller offers, the callee commits, the caller hosts.** The OFFER carries an optional `CallPayload.dw`
  (group name, passphrase, lane port, lane secret — single-use, sealed like a transfer's READY). A callee that can
  join answers `dx = 1` on its RINGING and joins while it rings, after a 500 ms grace that lets the RINGING leave
  before the radio does; the caller hosts on the first *fresh* `dx` (signed `sentAt` within 8 s — custody can deliver
  a RINGING the hand-over lost long after the join gave up). An older build ignores `dw`, never says `dx`, and
  nothing is raised. Where a join may not start off screen (API 29-32) or the debug mode says so, the callee commits
  on its ANSWER instead. Glare's auto-answer carries `dx` too, so only the surviving call hosts.
- **The callee joins with an IPv4 lease** (`DirectWifi.join(ipv4 = true)`, which also waits for the lease):
  libwebrtc drops an IPv6 link-local, so the transfer's IPv6 link-local provisioning would leave nothing to gather.
- **The group reaches libwebrtc through our own detector** (`debug/call/DirectNetworkDetector`, installed with
  `NetworkMonitor.setNetworkChangeDetectorFactory`), which wraps `NetworkMonitorAutoDetect` and adds the group as an
  **Ethernet**-typed network, handle 0. Not upstream's `setIncludeWifiDirect`: its delegate calls
  `WifiP2pManager.initialize` on every monitor start and never closes the channel (read off the 150.7871.01 AAR),
  and an open channel keeps the P2P interface up for ~150 s and blocks Aware from coming back. Ethernet because
  libwebrtc ranks a pair by network cost before priority (Ethernet 0, Wi-Fi 10, cellular 900): the group pair beats
  a LAN, IPv6-global, server-reflexive or relayed one, which all stay up as backups (pinged every 5 s, not 25).
  Reported as Wi-Fi it would tie the phone's own Wi-Fi and lose to an IPv6 host pair on priority. This replaced
  munging the remote `network-cost`, which a peer-reflexive candidate bypasses.
- **A sealed lane rides the group** (`call/CallLane`, `call/DirectCallPath`): one TCP connection, admitted by the
  transfer's client proof under a call-only key (`knit/call/v1/lane-proof`), then AES-256-GCM per direction keyed
  from the call's secret and a per-connection salt, counter nonces, the call id as AAD. It carries every call signal
  beside the mesh — queued from the leg's start, so what the mesh carried while the radio changed hands is replayed —
  plus the group's candidates (`MediaEvent.DirectCandidates`, lane-only, so C-3.6-2's single mesh CANDS stands) and a
  ping. Without it a pair whose only mesh link was Wi-Fi Aware, with no spool, could not hear ANSWER or END once
  Aware is paused for the group. `onSignal`'s `id/ph/gen` key already makes the second copy a no-op.
- **One radio, one holder.** `transfer/DirectWifiLease` wraps `AndroidDirectWifi`; the transfer holds the view
  `leasedTo("transfer")`, a call the one leased to its id. A view that does not hold the lease is refused `Busy` and
  releases nothing — `TransferManager.finish` releases on every end path, hosted or not, and would otherwise remove a
  call's group.
- **Every end gives the radio back once**: the terminal frame goes down the lane first (≤ 300 ms), the sockets close,
  the leg's job is joined, then `release()`. A lane silent for 5 s ends the leg; ICE fails over or the 10 s
  disconnect grace ends the call. While a leg is still coming up, ICE's `Failed` does not end the call (with no other
  route it fails before the group exists) and the connect timer is 30 s, not 20.
- **Relay-only goes direct nowhere**, on either side: with `iceTransportsType = ALL` the real address would leave in
  connectivity checks as a peer-reflexive candidate, whatever the SDP says.
- Two fixes the longer-lived group needed in shared code: `AndroidDirectWifi` takes back a `connect`/`createGroup`
  on cancellation (a group could form after `release()` closed the channel), and `WifiAwareTransport.stop()` no longer
  clears `paused` — a mesh restart mid-call (a notification Pause, Stop then Start) re-attached Aware into a BUSY
  radio; `resume()` on a stopped transport only lifts the hold.

Alternatives rejected: the Wi-Fi Aware NDP (above); raising the group only after the answer (5-7 s of Connecting
with no other route, chosen against with the user — the debug `answer` mode keeps it for trials); gating direct on
"a signaling path other than Aware exists" instead of the lane (fails the no-spool case it exists for); our own
Opus-over-UDP stack (3tee's reason).

**What it costs.** Wi-Fi Aware is dark on both phones for the ring and the whole direct call — even a declined
one — and the mesh rides Bluetooth meanwhile. The group's name (`DIRECT-kn-…`) is visible on air, as a transfer's
is. `GROUP_OWNER_BAND_AUTO` can put the group on another channel than the phone's Wi-Fi and time-slice the radio;
that is jitter for a call, not lost throughput — measure before pinning a frequency. A lost group is not raised
again (3tee's §3.8 ICE restart is still unbuilt). iOS has no Wi-Fi Direct and ignores `dw`.

Debug-only like the rest of calls (the WebRTC media is `debugImplementation`; release wires no `CallManager`), and
the device trial is owed before anything else rests on it: the checklist is the plan's Phase 0 (does a call connect
with zero ICE servers, does the Ethernet typing win, does ICE switch and fail over) and T1-T12 in §3.10. Guards:
`CallManagerTest`'s §3.10 cases, `DirectCallPathTest` (loopback lanes, release-once, a held radio), `CallLaneTest`
(tamper, replay, reorder, direction and salt keys, the proof's domain), `DirectWifiLeaseTest`, and the `dw`/`dx`
round trip in `MessageContentTest`.
