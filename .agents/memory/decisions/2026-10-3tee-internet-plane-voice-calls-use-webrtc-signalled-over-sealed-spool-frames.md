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
