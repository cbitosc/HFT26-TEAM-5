---
id: "2026-10.79yb"
slug: a-call-with-no-live-path-is-a-walkie-talkie-over-the-mesh
title: "A call with no live path is a walkie-talkie over the mesh"
date: 2026-10-08
topics: [calls, voice]
---

# ADR 2026-10.79yb — A call with no live path is a walkie-talkie over the mesh

Status: Accepted (2026-10-08)

**What was observed.** A live call (ADR 2026-10.3tee) needs an IP path for its Opus: the spool's TURN or STUN, a
shared LAN, or a Wi-Fi Direct group between neighbours (ADR 2026-10.kxnw). The mesh's own radios carry none of
those. kxnw records why Wi-Fi Aware's data path cannot, and BLE moves a file at 4–18 KiB/s. Two phones a few hops
apart, or alone together with no Internet, could signal a call over the mesh and then sat in Connecting until it
ended `no_route`. Voice notes (ADR 034) already cross that same mesh. What is missing is a screen that makes them
feel like a call.

**What changed.** The call screen became a walkie-talkie when there is no live path (`docs/VOICE_CALLS.md` §3.11).

- **A walkie session is a call without media.** `CallPayload.m` gains `MEDIA_WALKIE = 4` (2 stays video's).
  - An OFFER with that bit alone and no `sdp` rings like any call, with the same staleness, glare, busy and
    tombstones. Answering connects it at once: no ICE servers, no group, no connect timer.
  - A build without the walkie declines it for its media, because the audio bit is absent.
  - Its profile carries `Protocol.CAP_WALKIE = 0x8000`, OR-ed in through `extraCapabilities` like `CAP_CALL` and
    never in `LOCAL_CAPABILITIES`. A caller offers a walkie, or the drop to one, only toward that bit.
- **The Call button picks the kind; there is no Walkie button.** `CallManager.start` runs the `CallWalkie` seam: a
  peer that runs the walkie, when no live path looks possible, gets a walkie offer. "Looks possible" means either
  of:
  - this phone has a validated Internet route (`InternetGate.isOnline`);
  - the peer is a mesh neighbour with `CAP_DIRECT_TRANSFER` and direct calls on.

  Anything else is a live call as before. The debug bridge forces either kind (`start --es kind`).
- **A live call whose path is lost, or never came, drops to a walkie instead of ending.**
  - A live OFFER carries `AUDIO|WALKIE` when the caller can drop. The callee echoes the bit on its ANSWER when it can
    too.
  - Only then do the three `no_route` endings (the connect timer, ICE `Failed`, the disconnect grace) close the media,
    end the direct leg and connect a walkie (`degraded`) instead.
  - **No frame is sent.** Each side runs its own window, and the side that drops first closes its media, which the
    other's ICE sees as a lost path, so both land in the walkie.
  - A peer whose ANSWER lacks the bit, an older build of this branch for instance, makes the call end exactly as
    before.
- **A burst is an ordinary sealed DM voice note.** `debug/call/WalkieTalk` handles the bursts:
  - **Talking.** Hold to talk: `VoiceRecorder` at 16 kbps, half a voice note's rate. Release to send through
    `MeshController.sendChat`. Under 500 ms is a slipped thumb, and 60 s releases itself, about 120 KB, which stays
    under the 128 KiB bulk-link threshold and rides whatever link is up.
  - **Hearing.** While the session is connected, the peer's voice notes play by themselves, one after another, by
    `WalkiePolicy.verdict`:
    - from the peer and not a named file;
    - arrived (receiver's clock) after the session connected and not played yet;
    - no more than 60 s between its `sentAt` and its arrival, and no more than 90 s from arrival to its clip landing.

    Anything else stays a voice note in the thread, which is the session's transcript either way.
  - **Side effects.** A played burst moves the read watermark. The session holds the notifier's visible-conversation
    slot so bursts post no notification, and ten quiet minutes hang it up.

Rejected:

- **A separate Walkie button.** Simplest, but it makes the user guess the path, and the Call button already knows
  more than the user does.
- **A new frame or MIME for bursts.** Nothing on the receiving side needs to tell a burst from a voice note: the
  session decides. Every build already plays `audio/aac`, and the iOS port may compare the MIME exactly.
- **Opus or Codec2, and streaming chunks while the button is held.** That is the v2: it needs an encoder and a
  container every receiver plays, and the chunks need a small inline frame instead of a blob ask.

**What it costs.**

- **It is a debug-build experiment, like calls.** `BuildConfig.WALKIE` (`-Pwalkie=`, on in debug and off in release)
  is read once, as `di/CallWiring.WALKIE_WIRED`. Off, `CallManager` gets `CallWalkie.None`, no `WalkieTalk` is built
  and the profile has no `CAP_WALKIE`, so every call is a live one. Release wires no calls, so its value is inert
  until calls ship.
- **Bit 0x8000 is claimed on this branch.** Main must not reuse it before this merges. The WIRE_COMPAT precedent
  entry for `CAP_WALKIE` and `MEDIA_WALKIE` is owed at merge (left out here to keep the long-lived branch off shared
  docs).
- **Not real time.** A burst lands after its whole clip has been pulled: seconds over Wi-Fi Aware, more over BLE
  hops.
- **The visible-conversation slot is single.** Opening the peer's chat mid-session takes it, and leaving that chat
  clears it, so later bursts notify as well as play.
- **There is no `mesh/lab/` scenario.** The lab cannot run calls (3tee's amendment), and a burst is a voice note the
  lab already converges.
- **The guards:**
  - `CallManagerTest`'s §3.11 cases: the auto pick, the media-less session, the decline from a build without it,
    both degrade paths with no frame, the un-echoed bit, and glare.
  - `WalkiePolicyTest`: every autoplay clause.
