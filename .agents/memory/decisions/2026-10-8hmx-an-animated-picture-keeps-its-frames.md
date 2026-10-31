---
id: "2026-10.8hmx"
slug: an-animated-picture-keeps-its-frames
title: "An animated picture keeps its frames"
date: 2026-10-05
topics: [attachments, privacy]
---

# ADR 2026-10.8hmx — An animated picture keeps its frames

Status: Accepted (2026-10-05). No wire change, no DB change, no vector moved; the iOS port's pick pipeline is its
own business (#133).

**What was observed.** The image ingest took the animated path only when the provider *said* `image/gif`
(`AttachmentStore.ingest`). Everything else went down the still door — decoded, one frame, JPEG or lossy WebP —
so an animated WebP (stickers, keyboard output, a saved reaction image) or an APNG picked from outside Knit went
out as its first frame, and so did a real GIF whose provider reported no type or a generic one (a `file://` Uri, a
file manager, some share intents: the mptm device trial sent a 1,936 B GIF as `image/jpeg` that way). The
recipient side already treated all three as animatable; only the send side lost the frames. And since ADR
2026-10.mptm, a flattened first send was sticky: picking the same original again matched the earlier send's
`attachmentSourceHash` and reused the still.

**What changed.**

- **The bytes pick the door.** When the source is in memory, `FileTypes.imageMimeOf` decides, not the provider's
  type; only a source too large to read whole still goes by the declared type.
- **An animated WebP within the sticker bound passes through stripped.** `ImageStrip.animatedWebp` keeps `VP8X`
  (EXIF/XMP bits cleared), `ICCP`, `ANIM` and every `ANMF` whose payload is only `ALPH` + `VP8 ` / `VP8L`; it drops
  `EXIF`, `XMP ` and unknown top-level chunks, and answers null for an `ANMF` holding anything else rather than
  rebuild one — unqn's allow-list rule, extended. The bound is a 512 px canvas (the sticker canvas: at the GIF
  door's 480 every sticker would be resampled for 32 px), 200 frames and 1 MiB (the Bluetooth cost).
- **Past the bound, and an APNG always, is composited and resampled** into an animated WebP at the GIF door's
  bounds (480 px, 15 fps, q70). `data/webp/AnimatedFrames` (pure, JVM-tested) demuxes either container into frames
  with their rectangle, time, blend and disposal, each rebuilt as a still the platform decoder reads;
  `WebpTranscode.shrinkAnimated` draws them onto one canvas *at the output size*, each decoded through
  `decodeBoundedFromBytes` no larger than it lands (ADR 051), so memory follows the output, not the source canvas.
  A stripped WebP the resample would not beat goes out stripped, so a WebP is never regressed; an animation neither
  route takes keeps one frame, as before, and the log says so.
- **The flattened identity stops winning.** `AttachmentStore.reused` refuses a *source* match whose identity is a
  still when the picked bytes animate (a still identity for animated source bytes can only be an old flatten), and
  `MessageDao.identityFor` orders `attachmentAnimated DESC` first, so the animated send found next time is the one
  reused. Rows naming one plaintext always agree on the flag, so the order decides nothing else.

The alternatives were the issue's. Transcoding every animated WebP (option 2 alone) would cost the common sticker
a generation and a new identity on every forward; refusing to flatten (option 3) gives up the feature. Sending an
APNG as itself was rejected because Android's `ImageDecoder` / `AnimatedImageDrawable`, which Coil plays through,
animates GIF and WebP but not APNG: it would arrive as a still on every Android phone. Drawing an
`AnimatedImageDrawable` on a clock to resample was rejected for want of a frame seek; the demux is deterministic
and testable.

**What it costs.** A passed-through sticker keeps its encoder's choices (frame count, timing, quality) within the
bound; past it the resample costs one generation. Screening reads the first frame, as it does for a GIF's WebP. An
animation over `MAX_BYTES` is not read whole and still flattens. Widen `ImageStrip.animatedWebp`'s allow-list only
for a chunk a decoder needs, and only with a case in `ImageStripTest`. Tests: `ImageStripTest` (the animated
allow-list, refusals, truncations), `AnimatedFramesTest` (both demuxes, the APNG still's chunks and CRCs, a default
image outside the animation, refusals), `AttachmentStoreIngestTest` (sticker byte-exact and metadata-free, an
oversized WebP resampled to 480, an APNG to animated WebP, a type-less GIF, the flattened identity refused),
`AttachmentIdentitiesTest` (the animated row wins).
