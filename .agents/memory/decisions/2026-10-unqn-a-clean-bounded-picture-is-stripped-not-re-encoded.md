---
id: "2026-10.unqn"
slug: a-clean-bounded-picture-is-stripped-not-re-encoded
title: "A clean, bounded picture is stripped, not re-encoded"
date: 2026-10-05
topics: [attachments, privacy]
---

# ADR 2026-10.unqn — A clean, bounded picture is stripped, not re-encoded

Status: Accepted (2026-10-05). No wire change, no DB change, no vector moved; the iOS port's own pick pipeline is
its own business (#134).

**What was observed.** Every still picture picked in the composer was decoded and re-encoded
(`AttachmentStore.ingest`: EXIF orientation applied, bounded to 1280 px, JPEG q85, or lossy WebP q85 with alpha).
That is the right first pass for a camera photo — it bounds the bytes and drops GPS and camera metadata — but
lossy re-compression costs a generation every time. ADR 2026-10.mptm removed the common repeat (a pick whose
bytes this phone already holds goes out as that identity), but a Knit picture saved before DB v20, one that
reached the phone another way, or a small clean screenshot still paid a generation and minted a new identity,
which also made the mesh carry a second copy of a picture many phones already held. The loss compounds along a
chain of people who save and re-send.

**What changed.** A picture already within the bound, upright, and no sharper than the re-encode would make it
goes out as **its own bytes with the metadata taken out** (`data/ImageStrip`, called from
`AttachmentStore.passThrough` ahead of the decode). The strip is an allow-list:

- JPEG keeps SOI, DQT/DHT/DRI, one SOF0/1/2 frame at 8 bits with 1 or 3 components, every scan with its
  entropy-coded data, EOI, and three segments that change how the pixels decode without saying anything about
  the photographer: a thumbnail-less `JFIF` APP0, the `ICC_PROFILE` APP2 chunks (a Display P3 photo shifts colour
  without them) and the 12-byte `Adobe` APP14 (its transform flag). EXIF, XMP, MPF, IPTC, comments and every
  other APPn go, and so does everything after the primary EOI (an Ultra HDR gain map, a Motion Photo's video, a
  vendor trailer).
- WebP keeps a simple lossy file whole; an extended one keeps `VP8X` (EXIF/XMP bits cleared), `ICCP`, `ALPH` and
  its one `VP8 ` frame. Lossless and animated WebP take the long way.

Anything the walk does not fully understand — arithmetic or lossless coding, 12-bit, CMYK, two frames, a DQT
that does not tile, a truncation — answers null and the picture is re-encoded as before. So a surprise can cost
only the generation this saves, never a GPS position. The pass-through also requires: long edge ≤
`MAX_DIMENSION`; an EXIF orientation that parses and is normal or absent (a rotated picture needs pixels moved);
for a JPEG, a quality estimated from its luminance table (libjpeg's scaling of Annex K, inverted from the
table's sum) of at most `JPEG_QUALITY` + 2; for a WebP, at most half a byte per pixel; and a decode at the size
the header declares. A camera original (q90 and up) therefore still takes one re-encode, and our own output never
takes another: the strip is idempotent, so a re-picked picture keeps its bytes and its hash.

The alternatives were the issue's: backfilling plaintext names so every held picture is found by its bytes (only
helps pictures this phone holds, and costs a decrypt per attachment, which mptm declined at startup), and raising
`JPEG_QUALITY` (smaller loss per pass, never none, and every photo gets bigger). A pass-through with no quality
guard was rejected too: a q100 picture at 1280 px is three to four times our re-encode's bytes on a Bluetooth
link.

**What it costs.** A passed-through JPEG keeps its encoder's quantization tables, which can say which app or
camera made it; the quality guard keeps camera originals out, so this is low-quality app output only. The
metadata invariant now rests on `ImageStrip`'s allow-list: widen it only for a segment a decoder needs, and only
with a case in `ImageStripTest`. `AttachmentStoreIngestTest` pins the pixels (the scan bytes survive a strip
byte for byte), the metadata (no GPS, make or model either way), the rotated and over-bound cases, the q100
original taking one generation and no more, and a transparent WebP's hash surviving a re-pick.
