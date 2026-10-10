---
id: "2026-10.qjap"
slug: android-refuses-a-signed-envelope-whose-text-isn-t-utf-8
title: "Android refuses a signed envelope whose text isn't UTF-8, as iOS does"
date: 2026-10-10
topics: [wire, crypto, interop]
---

# ADR 2026-10.qjap — Android refuses a signed envelope whose text isn't UTF-8, as iOS does

Status: Accepted (2026-10-10; `mesh/protocol/CborText`, `WireCodec.decodeEnvelope` / `decodeChecked`,
`InboundPipeline.decodeCounted`; no byte moves, no vector moves)

**What was observed.** The 2026-10 interop audit (IOS-5) signed a frame whose `id` held a byte that isn't UTF-8.
Knit for iOS refuses it: its CBOR decoder validates every text string (`String(validating:as:)`,
`CBOR+Decoding.swift:98-103`). kotlinx-cbor 1.11.0 decodes text with `decodeToString()`, which swaps a bad sequence
for U+FFFD. The signature still verified, because it covers the raw `signed` bytes, so Android delivered, custodied
and relayed the frame under a repaired id that names nothing the signer sent. The two platforms would then hold
different sets for the same traffic, and their digests would never agree on that id. Only hostile input does this:
no Knit encoder writes invalid UTF-8.

**What changed.** `WireCodec.decodeEnvelope` refuses `signed` unless `CborText.validUtf8` passes: a walk over the raw
CBOR that checks every text string (map keys included) against the Unicode standard's strict UTF-8 and skips byte
strings unread. Every plane decodes `signed` there (the link, the NAN message plane, the BLE side channel, LoRa, the
spool, custody reads), so such a frame is never delivered, carried, relayed or listed in a digest, and it counts as
`DECODE_FAILED` wherever that plane already counts an envelope it can't decode (the framed link does; the others drop
it silently, as they drop any malformed envelope). Text inside the opaque `payload` is checked per type where the
pipeline reads it (`decodeCounted` → `WireCodec.decodeChecked`), counted as `PAYLOAD_DECODE_FAILED`; a carrier can't
check a sealed body and doesn't.

The alternatives a reader reaches for first don't work. Checking the decoded strings for U+FFFD can't tell a repaired
byte from a peer's honest U+FFFD. A strict `CharsetDecoder` (`REPORT`) ties the verdict to the runtime's charset, the
JDK's under the JVM tests and ICU's on ART, so the walk hand-writes Table 3-7 and gives one answer on both. A custom
kotlinx decoder would mean replacing the codec. An indefinite-length text string is joined before the check, as iOS
joins it, so a code point split across two chunks passes on both.

**What it costs.** One extra pass over `signed` (and over a payload the pipeline reads) per decode, bounded by the
frame's size and nesting (`CborText.MAX_DEPTH`). A custody row an older build stored with bad text no longer decodes,
so it's skipped on read and ages out at its TTL. A 2.8.0 carrier still holds such a frame and lists it in its digest,
so between an older node and this one the frame is offered and refused for up to its 24 h custody life, the divergence
ADR 2026-09.bts9 warns of, bounded here because only hostile input makes one. Tests: `CborTextTest`,
`EnvelopeUtf8Test`, `FramedLinkTest.frameWhoseSignedTextIsNotUtf8IsDroppedAsUndecodable`, `IosPayloadShapesTest`. The
trap: a new decode of `signed` outside `WireCodec.decodeEnvelope` skips the check. Route it through there.