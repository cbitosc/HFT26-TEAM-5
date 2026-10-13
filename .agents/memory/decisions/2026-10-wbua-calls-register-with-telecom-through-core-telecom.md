---
id: "2026-10.wbua"
slug: calls-register-with-telecom-through-core-telecom
title: "Calls register with Telecom through core-telecom, which owns their audio route"
date: 2026-10-12
topics: [calls, audio]
---

# ADR 2026-10.wbua — Calls register with Telecom through core-telecom, which owns their audio route

Status: Accepted (2026-10-12)

**What was observed.** A call's audio went where the phone happened to be left. A live call set the communication mode
and offered one toggle, earpiece or loudspeaker (`debug/call/CallAudio`), and a Bluetooth headset never heard it,
because nothing started the headset's call link (HFP or LE Audio). A walkie burst played on the media usage, pinned to
the loudspeaker unless a headset was on (79yb's fourth amendment), and was always spoken into the phone, so a headset
heard the burst over A2DP but its microphone was never used. ADR 2026-10.3tee had deferred Bluetooth routing to Telecom.

**What changed.** Every call, live or walkie, is a self-managed Telecom call through Jetpack core-telecom, and Telecom
owns its audio route (`docs/VOICE_CALLS.md` §3.13).

- **The seam is pure.** `call/SystemCalls` holds `SystemCalls`, `SystemCall` and `SystemCallSync`, and
  `call/CallAudioRoute` holds `CallAudioPolicy`. All of it is JVM-tested (`SystemCallSyncTest`, `CallAudioPolicyTest`).
  `debug/call/TelecomCalls` is the one `androidx.core.telecom` importer, and core-telecom is `debugImplementation`
  like WebRTC.
- **The sync follows `CallManager.state` on the app scope.** It is never on the inbound path.
  - An outgoing call is added as it dials and set active when it connects.
  - An incoming call is added as it alerts and answered when this phone answers it. When the system's own answer
    took it (a headset's button, a watch, a car), the sync does not answer the system a second time.
  - A call that ends is disconnected with the cause `SystemCallSync.endOf` reads off it. `CallState` gains
    `endedByPeer` and `missed` so that it can.
  - Glare's surviving id with the same peer keeps the system's call.
- **A walkie differs in one way: it starts on the loudspeaker.** A headset takes either kind, both ways, as on a phone
  call. `CallAudioPolicy.startRoute` asks for the loudspeaker through `preferredStartingCallEndpoint` when no headset
  is there. `CallAudioPolicy.walkieCorrection` asks for it again when a walkie's route falls to the earpiece without
  the user picking it, for instance when the headset is unplugged or Telecom did not honour the start.
- **Walkie playback and capture follow the call's route.** `TalkPlayer` plays on the voice-communication usage, with
  no pinned device and no focus of its own. `TalkRecorder` records from `VOICE_COMMUNICATION` while a headset has the
  call, and from the phone's own `VOICE_RECOGNITION` otherwise. `CallAudio` is gone: Telecom's guide forbids
  `setCommunicationDevice` and `startBluetoothSco` beside it.
- **No hold.** The call carries no `SUPPORTS_SET_INACTIVE`, so a phone call answered over ours ends it.
- **Refusals are refused here too.** A call the system refuses, or every call on a phone that would not register, is
  declined `busy` while it alerts and otherwise hung up. An outgoing one has by then sent its OFFER, so a CANCEL
  follows it.
- **The pin is 1.1.0-rc01.** It is a recorded exception to the stable-only rule (`.agents/context/toolchain.md`).
  1.0.1, the newest stable release, has two faults that hit these calls:
  - it reverts a switch to the loudspeaker made during call setup, which is where a walkie starts;
  - it can start an audio call on the loudspeaker on Android 14–16.

  Releases 1.1.0-alpha05 and alpha06 fixed both.

Rejected: our own router over `AudioManager`'s communication-device APIs (`setCommunicationDevice` on 31+,
`startBluetoothSco` below). It needs no dependency, and Signal ships one. But it would have to rebuild Telecom's
Bluetooth state machine, it gives no headset, watch or car integration, and a phone call would fight it for the audio.
Also rejected: a walkie on A2DP playback with the phone's microphone. A user wearing a headset expects to talk into it,
and A2DP clips the first syllable of a burst after a quiet spell.

**What it costs.**

- **A headset's call link stays up for a whole walkie session.** eSCO then shares the Bluetooth controller with the
  BLE and Coded links that carry the session's chunks. Over 1M PHY that cost nothing in the trial. Over Coded PHY the
  phone holding the eSCO link received 2 of each 9 chunks (6 bursts), against 27 of 27 with the link down; its own
  sends all arrived (`docs/VOICE_CALLS.md` §3.13's known limit). `BluetoothAudioMonitor` only attributes, so the fix
  is a PHY rule in the Bluetooth plane, through `bluetooth-owner`: not built.
- **The microphone service from the background.** A call answered from a headset or a watch with Knit off screen
  connected with its microphone silenced: only the call screen started the service. `CallService.startAnswered` now
  starts it from inside Telecom's answer (C-3.13-10), which Android allowed in the background on the trial phone.
- **Mute is one-way.** A car's or a watch's mute reaches the call through `SystemCall.muted`. Ours does not reach
  Telecom.
- **What a car or watch sees.** It shows the peer's label (`callerName`) as the call's name, and its address is
  `knit:<node id>`.
- **Nothing else may set the route while a call is up.** A future `AudioManager` route call beside Telecom breaks the
  call's audio.

**Device trial, 2026-10-12** (P9 Pro XL and P7, both API 37; a Pixel Watch 3 stood in for the earbuds as the
P9's Bluetooth route). Both phones registered (`SelfManaged TransactOps OptOutPremium`). A live call started on the
earpiece with Telecom holding focus and mode, moved to the speaker, the watch (`bt_sco`, the mic from
`VOICE_COMMUNICATION`) and back. It ended `LOCAL` on the phone that hung up and `REMOTE` on the other. A walkie
started on the speaker at both ends: on the P9, Telecom first entered the earpiece, and the correction moved it to the
speaker within 20 ms, before any audio. Bursts on the watch played out over `bluetooth sco` and were recorded from
source 7 there. A user's earpiece pick was kept. A headset-hook key press answered a backgrounded call and hung it up.
Owed: earbuds unplugged mid-walkie (C-3.13-6), and the Pixel 3's `ConnectionService` path.
