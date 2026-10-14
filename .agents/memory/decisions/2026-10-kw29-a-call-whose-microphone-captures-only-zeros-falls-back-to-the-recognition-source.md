---
id: "2026-10.kw29"
slug: a-call-whose-microphone-captures-only-zeros-falls-back-to-the-recognition-source
title: "A call whose microphone captures only zeros falls back to the recognition source"
date: 2026-10-14
topics: [calls, audio, reliability]
---

# ADR 2026-10.kw29 — A call whose microphone captures only zeros falls back to the recognition source

Status: Accepted (2026-10-14)

**What was observed.** A live call from the lab's Moto G Power (a TrebleDroid GSI, API 34, over its Android 11
vendor; ADR 2026-10.w3dw) reached the Pixel 9 as silence, while the same phone's walkie bursts were heard. The
2026-10-08 logcat says why. The call's audio module records from `VOICE_COMMUNICATION` (libwebrtc's default) in
Telecom's communication mode, which the vendor's `msm8974` audio HAL maps to its VoIP record route:
`audio_route: unable to find path 'audio-record-voip'`, then `start_input_stream: cannot open device
'/dev/snd/pcmC0D6c'` four times a second. The framework fills the stream with zeros. libwebrtc saw nothing wrong
(`verifyAudioConfig: PASS`), and with no Opus DTX `bytesSent` rose the whole call. The walkie records from
`VOICE_RECOGNITION`, the ordinary route.

**What changed.** Two halves (docs/VOICE_CALLS.md C-3.13-12, C-3.13-13):

- **A profile per phone.** `call/CaptureHealth` picks `STANDARD` (`VOICE_COMMUNICATION` with the platform's echo
  canceller and noise suppressor) or `FALLBACK`. The fallback records from `VOICE_RECOGNITION` with those effects off,
  so libwebrtc reports no built-in canceller and runs AEC3, its noise suppressor and its gain itself. A generic image
  over an older vendor (`DeviceImage`) opens every call on the fallback, and so does a phone where a call has already
  gone silent (the verdict under the app version + OS build, `data/settings/CaptureStore`, out of backups).
  `WebRtcCallMedia` builds its audio module and factory per profile and rebuilds them between calls when the profile
  changes, never under an open session.
- **A watch on every call.** `ui/call/CaptureTap` is the module's `AudioBufferCallback` (the webrtc-sdk build's hook,
  called after every 10 ms read on the record thread). It also takes the module's record error and state callbacks,
  and feeds a pure `CaptureWatch`. Three seconds of digital silence while unmuted, or a record error, is a dead
  microphone. A real one's noise floor never reads exact zeros. The platform's mute (`isMicrophoneMute`) and a client
  the system silenced (`isClientSilenced`, the C-3.13-10 case) are excused, asked only as a run completes. A dead
  standard path switches mid-call: `setAudioRecordEnabled(false)` makes the module release its own `AudioRecord` and
  hand our callback an empty buffer. The tap then fills each buffer from a recorder of its own (`VOICE_RECOGNITION`,
  then `MIC`), whose blocking read paces the record thread. There is no renegotiation, and the next calls open on the
  fallback. Dead on the fallback too, or silenced, the call screen's note says the peer cannot hear this phone and why.
  The diagnostics card reads the `media-source` and `inbound-rtp` `audioLevel`s.

Alternatives: renegotiating a fresh peer connection on a new module mid-call (heavy, and ICE restart is unbuilt). A
second recorder beside the module's (on the GSI the policy opens one input stream at the highest-priority source, so it
would record the same zeros). Leaving the hardware effects on with the fallback source (they attach to the voice-call
session; the fallback would run with no echo canceller at all). A blanket `MIC` everywhere (it loses the platform's
canceller on every phone that has a working VoIP route).

**What it costs.** A switched call runs the rest of its length with no echo canceller. libwebrtc configured AEC3 off
when the module reported a hardware one, and that is fixed for the session. On the loudspeaker the peer may hear
itself back until the call ends. The fallback's source may not follow a Bluetooth headset's microphone the way
`VOICE_COMMUNICATION` does. A walkie with a headset still records from `VOICE_COMMUNICATION` (C-3.13-7), unchanged
here. The three-second window is a judgement: a route change's gap is shorter, a caller's patience longer. Tests:
`CaptureHealthTest` (policy, store, watch), `CallStatsReaderTest.theMicrophoneAndTheHeardLevelsAreRead`. Owed: the
Moto heard on a live call on the fallback, and a Pixel switching mid-call under `…debug.CALL --es op capture --es
fault silence`.
