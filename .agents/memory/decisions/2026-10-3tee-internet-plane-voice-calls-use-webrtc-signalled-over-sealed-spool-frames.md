---
id: "2026-10.3tee"
slug: internet-plane-voice-calls-use-webrtc-signalled-over-sealed-spool-frames
title: "Internet-plane voice calls use WebRTC signalled over sealed Spool frames"
date: 2026-10-07
topics: [voip, spool, webrtc, privacy]
---

# ADR 2026-10.3tee — Internet-plane voice calls use WebRTC signalled over sealed Spool frames

Status: Proposed (2026-10-07)

**What was observed.** Knit has no voice calling. A call has four parts. Knit already covers two:
signaling, through the sealed frames of a DM scope, and authenticating the media keys, through the
identities those frames are sealed under. It lacks the other two: moving audio between two phones that
are both behind NAT, and waking the callee to ring. The scope here is the Internet plane only. A call
over Wi-Fi Aware is a separate question, and this ADR does not settle it. Every option was weighed
against the same constraints: no accounts, no server-side identity (`docs/SPOOL_PROTOCOL.md` §1.4), no
GMS (ADR 003), and a Spool that sees only scope ids, sizes and timing (§10.1). The obvious move, adopting
an existing calling stack (Matrix/Element Call, Jitsi, LiveKit), assumes accounts and a media server
that can hear the call unless SFrame E2EE is layered on top.

**What changed.** If accepted, a 1:1 audio call works like this:

- **Media** is libwebrtc (BSD, so GPL-compatible) carrying Opus over ICE/UDP, peer to peer where it can
  be and through TURN where it cannot.
- **Signaling** is the SDP offer, the answer, ICE candidates and hang-up, each an ordinary sealed frame
  in the pair's existing DM scope. The Spool's live `event` fan-out makes this fast enough when both
  sides are connected, and the Spool learns nothing beyond a burst of small blobs in a scope it already
  holds.
- **Authentication:** each side's DTLS certificate fingerprint rides inside its sealed offer or answer.
  DTLS-SRTP is therefore authenticated by Knit identity, a TURN server cannot man-in-the-middle the
  call, and the ephemeral ECDHE handshake gives per-call forward secrecy.
- **Expiry:** a callee rings only for an offer whose signed envelope `sentAt` is within 60 s of now, the
  same staleness test `CTL_TRANSFER` uses. Without it, a store-and-forward offer could ring a phone hours
  later; a stale one becomes a missed call. The signaling sketch is `docs/VOICE_CALLS.md`.
- **Relay:** coturn runs next to the Spool, issuing short-lived credentials under its shared-secret REST
  scheme. A new Spool record hands them out, gated by the same PoW or token that gates scope creation.
  The relay is a separate service, so on Knit Hosted it is an add-on, and any billing stays out of
  knit-spool's `daemon/`.
- **Privacy toggle:** a "relay all calls" setting forces TURN, so a peer never learns the other side's
  IP. TURN then sees both IPs and no content.
- **Platform:** Android ships first, with the system call UI through `ConnectionService`/Telecom.

Alternatives rejected:

- **Relaying audio through the Spool itself**, as a new non-storing pipe record. It would keep the
  blinding model and need no TURN, but the Spool's single WebSocket is TCP, and head-of-line blocking
  makes audible stalls on lossy mobile links. It stays a possible fallback for WebTransport/QUIC
  datagrams later.
- **A hand-built stack** (Opus over our own UDP with ice4j/libnice). It would rebuild jitter buffering,
  echo cancellation, congestion control and NAT traversal for a smaller APK and nothing else.

**What it costs.** About 10 MB of APK per ABI for libwebrtc. TURN is a new server role, and its relay
bandwidth must be paid for. TURN also sees both endpoints' IPs and the length of every relayed call,
which a Spool never sees about anyone, so the §10 claims need a sibling section for it. Ringing on
Android works only while the callee holds a live Spool connection, which the mesh foreground service
keeps at some battery cost. UnifiedPush is an optional later wake path, since FCM is excluded.

What it does not cover, and why the status is Proposed:

- **iOS background ringing.** It needs PushKit VoIP pushes and CallKit, so it needs APNs. That means a
  server holding device tokens keyed to recipients, which contradicts §1.4's ban on server-side identity.
  Until it is decided, iOS rings in the foreground only, or makes outbound calls only.
- **Whether TURN alongside the Spool is acceptable** at all, given what it observes.
- **Where signaling lives:** reuse DM scopes with a sealed `expiresAt` as above, or define a per-pair
  call scope with a short `ttlMs` in the §11 extension register.

Out of scope: video, group calls (P2P mesh is enough up to about four participants; beyond that needs an
SFU with SFrame) and calls over the radios. Nothing is built yet, so no test guards this decision. When
it is built, the guards are a wire vector for the signaling frames, a stale-`expiresAt` drop test, a test
that a DTLS fingerprint absent from the sealed offer fails the call, and a knit-spool conformance case
for the TURN credential record.

## Amendment (2026-10-07): a minimal build for internal testing, on `feat/voip-calling`

A long-lived experimental branch builds the smallest client that places, rings, answers and hangs up a 1:1 audio
call, direct or through TURN, against the knit-spool `exp/turn` daemon on a private lab spool. It follows
`docs/VOICE_CALLS.md` §2–§3 with these choices:

- **Debug builds only.** `io.github.webrtc-sdk:android` is `debugImplementation`, and every `org.webrtc` import, the
  call screen, the ring and the microphone service live in `src/debug` behind `di/CallWiring` (the `DemoWiring`
  pattern). Release builds carry the additive wire field and the dormant pure `call/` package, but no WebRTC, no
  call UI and no `CAP_CALL`: the bit is OR-ed into our own profile only by a build that wires calls
  (`MeshManager.extraCapabilities`), and `custodyPresentsOtherThan` republishes the profile under a fresh stamp when
  the bits change, since a reused stamp would hide the new bit for up to 12 h.
- **No Telecom yet.** Our own `CallActivity` and a CallStyle notification with a full-screen intent, instead of
  `ConnectionService`; Bluetooth headset routing and the system call log wait for it.
- **The signaling hook never waits.** `CallManager.onSignal` runs on the inbound path — a spool connection's only
  reader — so it decides, sends RINGING or a busy DECLINE, and returns; the TURN fetch and every media step run on
  the app scope. Inline, the callee's TURN prefetch would wait on a `turncred` its own blocked reader had to read.
- **TURN asks are short and strike-free.** At most two spools carrying the peer's DM scope, 3 s each, and a silence
  does not count toward `SpoolConnection`'s unresponsive strikes.
- **Not built:** ICE restart and network handover (§3.8 — a lost path ends the call after 10 s), a user-facing
  "relay all calls" setting (a debug-bridge toggle, `…debug.CALL op=relay`), tombstones that survive a process
  death, call history (a missed call is a notification only), a `mesh/lab/` scenario, and the `CallPayload` golden
  vector. Each is owed before the feature leaves the branch.

Guards: `CallManagerTest` (two managers over a fake signal net: ringing, decline, ring-out, busy, stale offer,
cancel-before-offer, duplicates, glare, connect timeout, relay-only, a non-blocking `onSignal`), `SdpGuardTest`,
`SpoolTurnRecordsTest` (the daemon's own hex), the TURN cases in `SpoolConnectionTest` and `ScopeSyncTest`, and the
`CTL_CALL` cases in `MeshManagerTest` and `InboundPipelineTest`.

## Amendment (2026-10-12): call records in the thread, without a schema change

The first amendment left call history unbuilt: a missed call was a notification and nothing else. A call now leaves a
line in its DM thread (`docs/VOICE_CALLS.md` §3.12). Choices:

- **A notice kind, not a table.** The `messages` table already has a `kind` column for rows this phone writes about a
  thread, and a direct transfer stores its card as a JSON body under `KIND_FILE_TRANSFER`. A call is `KIND_CALL`
  (10) with a `CallRecord` body, so there is no migration, no new backup class and nothing new for the GC. An older
  build draws an unknown kind as a bubble rather than dropping it.
- **Facts stored, words derived.** The record keeps the reason, who ended it, the missed verdict and the two
  timestamps. `CallRecord.outcome` turns those into "Missed", "Declined", "No answer" and the rest when the line is
  drawn, so the wording can change without rewriting rows.
- **Written off the inbound path.** `CallManager` emits on `logged` from `finish` and from the stale-offer branch;
  `call/CallLog`, the one collector, saves the row on the app scope and resolves the repository lazily. A failed
  save loses that line only.
- **The chat list shows it, the badge does not.** A record speaks for its row, as a transfer does, but it never adds
  to the unread count: a missed call already raises a notification, and an answered one needs nothing.
- **Not built:** a tap on the line to call back (it would need the call button's microphone gate), and a record for
  an offer refused busy.

Guards: `CallRecordTest`, the record cases in `CallManagerTest`, `CallLogTest`, `CallDurationTest`, the call line in
`ChatScreenContentTest`, `ChatListTransferPreviewTest`'s call case, and `MessageKindTest` (the new value is distinct).

## Amendment (2026-10-12): a dropped call reconnects by ICE restart, and only the caller restarts

The first amendment ended a call 10 s after its path was lost. A connected call now reconnects (`docs/VOICE_CALLS.md`
§3.8) under the same call id, the restart §3.8 always described. Choices:

- **One mechanism.** A path lost mid-call is won back by ICE restart and by nothing else: no redial under a new id,
  and §3.11's drop to a walkie stays parked. A redial would ring again or need auto-answer rules, glare with the
  user's own redial and leave two records for one conversation. A restart keeps the call's id, its DTLS session and
  its record. A dead peer, a reset ratchet session and a dead network then end the same way: the window runs out.
- **The caller alone restarts.** The callee only answers, so two restarts never cross and need no glare rule. A
  callee-side network change costs the seconds until the caller's ICE notices. A callee offline when the restart goes
  out pulls it from the spool when it comes back, inside the window.
- **20 s, two restarts.** `CallPhase.Reconnecting` lasts `CallTimings.reconnectWindowMs` from the loss. The caller
  restarts after 2 s (a blip ICE heals on its own costs no frame) and once more 8 s later; each restart is a ratchet
  step, a spool blob and a flood, the cost C-3.6 counts. A Failed while reconnecting ends nothing.
- **Fresh ICE servers per restart.** A long call's TURN credential may have run out, so the restart fetches again
  and the session takes them through `setConfiguration`.
- **A dropped call is its own outcome.** `CallOutcome.Dropped` is derived from the record (connected, then
  `no_route`), so no field is added. The call screen of a dropped call offers Call back; the thread line does not yet.
- **Not built:** a CANDS for a restart (it carries what 1.5 s of gathering found, and the second restart is the
  second chance), raising a lost Wi-Fi Direct group again, and a route-change trigger.

No wire change: `CallPayload.gen` was always there, and an older build ignores a `gen > 0` OFFER, so its call ends at
the window as before.

Guards: the reconnect cases in `CallManagerTest` (a restart answered under the same call, a blip that costs no frame,
two restarts then Dropped, the callee's window and its renewal, a restart that does not fit, a superseded answer,
relay-only), `CallRecordTest`'s Dropped case and the Dropped line in `ChatCallLineTest`.

## Amendment (2026-10-12): calls register with Telecom

The minimal build's "No Telecom yet" no longer holds. Every call is a self-managed Telecom call through Jetpack
core-telecom, which owns its audio route: the earpiece, the loudspeaker, and a wired or Bluetooth headset, both ways.
A headset, a watch or a car can answer the call and hang it up. Our own `CallActivity` and CallStyle notifications
stay, because a self-managed call draws its own. ADR 2026-10.wbua records it, and `docs/VOICE_CALLS.md` §3.13
specifies it. The system call log is still not used: a call's line in its thread is §3.12's.

## Amendment (2026-10-13): what the minimal build owed

The first amendment named six things owed before the feature leaves the branch. Call history and ICE restart came with
the second and third amendments. The other four are built now:

- **Tombstones outlive the process** (C-3.4-3). `CallManager` writes each one it adds to a `call/CallTombJournal`,
  whole and off the inbound path, and reads the journal once as it starts. `onSignal` waits for that read before its
  first decision. The read is of a local file, once per process, and depends on nothing the inbound reader would have
  to read. `data/settings/CallTombStore` keeps the list in the settings DataStore, under one `call_tombs` key
  (`SettingsKeys.TRANSIENT_PREFIXES`, so never in a backup), for 48 h: the longer of a carrier's custody (24 h) and a
  spool's DM scope (48 h). A journal that cannot be read costs only its tombstones. Without it, a phone that heard a
  call end and then died rang for the call's offer, or logged a second missed call, when the offer arrived late.
- **"Relay all calls" is a setting** (C-3.7-3): `SettingsStore.callsRelayAll`, a Settings row shown where `CALLS_WIRED`
  and the Internet plane are, off by default. It rides a backup, since it is the person's choice. `CallManager` takes
  it as a function read at each call (`relayAll`) rather than a field the bridge sets. Until the store's first read
  lands, the wiring reads it as on: a call in that instant goes through TURN rather than showing the peer an address
  the person hid. `…debug.CALL op=relay` now writes the setting.
- **The golden vectors**: six `messageContentCall*` fixtures in `vectors/wire-v1.json`, on the named `MessageContent`
  form the call ctl rides (`MessageContentV2` refuses `cl`). Additive: no other byte moved. knit-ios pins the file's
  fixture count, so its next sync needs that count raised.
- **A lab scenario**: each `LabNode` boots a real `CallManager` over its `MeshManager`, with `LabCallMedia` for the
  media and its tombstones in the node's settings file. `CallLabTest` runs a call across a relay into the far call
  layer and no thread, and the restart case end to end.

Guards: the restart cases in `CallManagerTest`, `CallTombStoreTest`, `SettingsSnapshotTest`'s two keys,
`SettingsScreenContentTest` and `SettingsViewModelTest`'s relay rows, `GoldenVectorTest`, and `CallLabTest`.
