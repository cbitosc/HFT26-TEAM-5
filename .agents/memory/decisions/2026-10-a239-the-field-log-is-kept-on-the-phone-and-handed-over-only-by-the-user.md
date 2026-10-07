---
id: "2026-10.a239"
slug: the-field-log-is-kept-on-the-phone-and-handed-over-only-by-the-user
title: "The field log is kept on the phone and handed over only by the user"
date: 2026-10-07
topics: [privacy, diagnostics, bluetooth]
---

# ADR 2026-10.a239 — The field log is kept on the phone and handed over only by the user

Status: Accepted (2026-10-07)

**What was observed.** Every open radio question is a question about phones in the field, and the lab cannot
answer any of them. Which link RSSI should step a link to Coded (yvn6)? How long does a drop take to relink?
What does a 1M link drain on a crowded phone (2da4, 8jwn)? Do the receiver's progress reports hold up (nsr7)?
Nothing in the app keeps the answers past the process. `MeshMetrics` is in memory and resets on restart. Link
up/down, PHY steps, relink times and drain are logcat lines in a 256 KiB ring that rolls over before a walk
log is read (yvn6). The richest per-link state, `LinkTuneDiag` and the NAN snapshots, is bound in debug builds
only. The users who want to help have no way to hand over what their phone saw.

**What changed.** Every build can keep a **field log**: a bounded record of how the radios behaved, kept in
`noBackupFilesDir/fieldlog/`. The user, and only the user, shares it from Diagnostics → Field log as one
`.jsonl` file. The share stages a copy under `cacheDir/fieldlog/`, the same route crash reports take (ADR 028).
It is **off by default** (2026-10-07: recording is the user's choice, made on the Field log screen); switching it
off again deletes what was kept. The cost: a log switched on after the walk that mattered holds nothing of it.

- **What it records** (`fieldlog/FieldEvent`, schema in `docs/FIELD_LOG.md`):
  - each mesh session's start and stop, power edges and radio status edges;
  - the movement of `MeshMetrics`' radio, file and plane counters every 15 min, as deltas, so a restart costs
    nothing;
  - each Bluetooth link's up (dial time, relink time), down (reason, lifetime, last PHY and link RSSI), failed
    dial and PHY step;
  - every 5 min, a Bluetooth sample (each link's PHY, RSSI, interval, MTU and earned budget; the four
    strongest sightings with each PHY's RSSI and `codedLag`) and a LoRa sample (signal, queue, airtime);
  - each Wi-Fi Aware data path's up and down, and each session cycle;
  - at export, per-plane message-latency percentiles read from the database.
- **Redaction by construction.** `FieldWriter` has no string field. A value is a number, a boolean, an enum
  constant or a peer, and a peer is written as `p` + 8 hex of HMAC-SHA256(salt, node id). The salt is random,
  lives beside the journal and is replaced by Clear. So a log can follow one peer across restarts, but two
  people's logs cannot be joined, and a Clear starts over. The export also withholds any line holding a
  node-id-shaped token and counts it in the header. It rebases `t` to the first line, so the calendar appears
  only as the header's hour.
- **Bounds.** Two append-only segments of 2 MiB (`current` rolls to `prev`, so 4 MiB at most), and nothing
  older than 72 h is exported. A torn tail is closed off and skipped. Events are queued in memory (5,000 at
  most) and reach disk on the session's minute tick and at stop.

**The alternative a reader reaches for first is a telemetry build that uploads.** It was discussed and set
aside:

- It breaks the README's "no analytics or telemetry" and the store text.
- F-Droid would flag it. The GitHub Release APK is also F-Droid's byte-compared reference
  (`context/distribution.md`), so the uploading build would have to be a second APK with its own name.
- Knit's offline app-share would carry that build to people who never chose it.

A file the user hands over has none of these costs. It ships in every channel unchanged, and it lets the
schema settle before anyone builds an uploader.

**No contact-name pass.** The crash report's phase 2 matches known names. Here every token on a line is code
vocabulary, so a name match could only be a contact called "Max" or "Radio" wiping out the latency or radio
lines. It could never catch a leak.

**What it costs.**

- Someone holding the unlocked phone and a node id can test a pseudonym against the salt on disk. The salt
  sits beside the journal on purpose, so pseudonyms survive restarts.
- The journal is plaintext, not SQLCipher. It is app-private, holds no ids or names, and is out of every
  backup.
- `IN` latency carries the clock difference between the author's phone and ours, and the header says so.
- The sample reads only what the Bluetooth transport already holds. PHY and RSSI therefore appear only on a
  link with a PHY handle (the Coded experiment). Interval and MTU appear only once a tuner exists (a boosted
  file). Elsewhere they are absent, never guessed.
- A process death loses at most the last minute.
- The nsr7 feed-meter hooks (`feed_end`, `rx_end`, park waits, ETA promises) are owed: they land once nsr7
  does.

**The trap.** A new `FieldEvent` must write only through `FieldWriter`. Its golden line goes in
`FieldEventSchemaTest`, and its fields go in `docs/FIELD_LOG.md`. `scripts/fieldlog.py` reads them. Never add
a string method to `FieldWriter`. A sampler (`FieldRecorder.sampler`) runs on the log's tick and must only
read. The Bluetooth one asks no handle for anything. Tests: `FieldEventSchemaTest`, `FieldJournalTest`,
`FieldLogTest`, `FieldLogDocumentTest`, `MessageDaoTest`'s latency case.
