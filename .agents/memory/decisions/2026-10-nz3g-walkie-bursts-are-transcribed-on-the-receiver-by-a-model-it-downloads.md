---
id: "2026-10.nz3g"
slug: walkie-bursts-are-transcribed-on-the-receiver-by-a-model-it-downloads
title: "Walkie bursts are transcribed on the receiver by a model it downloads"
date: 2026-10-09
topics: [calls, voice, ml]
---

# ADR 2026-10.nz3g — Walkie bursts are transcribed on the receiver by a model it downloads

Status: Accepted (2026-10-09)

**What was observed.** A walkie burst (ADR 2026-10.79yb) plays once, from the loudspeaker. Someone who missed it,
who can't hear it in a loud place, or who can't hear at all is left with nothing, and the call screen had no way to
show what was said. The audio is all on the receiver already: `TalkPlayer` decodes it to PCM just before the track.

**What changed.** The receiver writes each burst out as text after it has played (`docs/VOICE_CALLS.md`
C-3.11-9). It is debug-only, as the walkie is, and nothing reaches the wire.

- **Model.** The model is Moonshine tiny EN v2 (int8 ORT, MIT, 44 MB), run through sherpa-onnx 1.13.8 as a
  `debugImplementation` from JitPack, with an exclusive filter for its one group.
  - Whisper tiny was the first thing tried, and it is the wrong shape for push-to-talk. It pads every input to
    30 s, so a 3 s burst costs as much as a 30 s one.
  - Moonshine's cost tracks the audio. On the x86_64 emulator a 3.8 s burst took 375 ms end to end, and that
    included building the recognizer. A 36 s stretch took 571 ms.
  - Accuracy at our 8 kbps Opus was the same as on clean audio for intelligible speech (desktop A/B on
    LibriSpeech-style clips).
- **Engine.** Running the model on the LiteRT runtime we already ship would have meant writing log-mel, decoding
  and the tokenizer in Kotlin, and Whisper exports often need the Flex delegate as well. sherpa-onnx carries all
  of that.
  - Its `.so` files are 16 KB-aligned, with RELRO filling the first RW LOAD. On a ps16k image,
    `pageSizeCompat` reads 0.
- **Download.** The model is not in the APK. It is downloaded on the user's tap.
  - It is fetched from getknit.app file by file. Each file is pinned by size and SHA-256 in `SttModel`, so the
    host is trusted with nothing.
  - The download runs through `Network.openConnection` on `AndroidInternetGate.currentNetwork()`, not okhttp.
    The host is fixed and serves pinned bytes, so it needs none of the preview fetcher's hostile-URL guards, and
    `rules/mesh.md` still has exactly two `okhttp3` importers.
  - `scripts/stt-model.py` derives the pins from the pinned upstream archive and stages the files for upload. It
    is the only place the pins come from.

**What it costs.**

- **Size and memory.** The debug APK is about 32 MB larger per ABI (uncompressed `.so`). While held, the recognizer takes about 60 MB of
  native heap (192 to 132 MB on unload, emulator-measured). It sits on a `ModelLease` that lets go after 2 idle
  minutes and is built under `ModelLoadGuard` as `stt`.
- **The ~9 s limit.** Moonshine v2 returns *nothing* for input longer than about 9 s (540 encoder frames). It
  fails silently: an empty string, not an error. `segments` therefore cuts a long burst at its quietest 50 ms
  between 5 s and 8 s, and the parts are transcribed separately.
  - Never raise `SttPolicy.SEGMENT_MAX_MS` past 9 s.
  - Never hand the engine a whole burst.
  - `BurstPcmTest` and `WalkieTranscriberTest` pin the cut.
- **A spent lease.** A lease whose load failed stays spent for the process. `SherpaSpeech` makes a fresh lease
  when the store's `generation` moves, so a reinstall gets its own try.
- **What it does not cover.** English only. Release builds have none of this. Our own outgoing bursts are not
  transcribed.
- **Hosting.** The files must be uploaded, unchanged, to `https://getknit.app/models/stt/moonshine-tiny-en-2026-02-27/`.
  Until they are, the download fails with "answered 404". The bridge's `STT --es op sideload` installs from pushed
  files through the same pins.
