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

## Amendment 2026-10-18 — the calls write into it too

**What was asked.** An alpha with calls switched on goes to testers, and the questions about calls are field
questions, like the radio ones: why a call ended, how long ICE took, which pair it rode, what it lost, whether the
microphone was dead, how a Wi-Fi Direct leg fared, what a walkie burst cost. Until now the answers were logcat lines
and the call screen's diagnostics card, which keeps nothing.

**What changed.** A build with calls (`-Pcalls=true`, ADR 2026-10.swvp) writes seven more kinds, under the same rules:
no string field, a peer as its pseudonym, no call id, SDP, candidate address, TURN credential or caption. Fields are
in `docs/FIELD_LOG.md`.

- `call_start` and `call_end` come from `CallManager`. There is one end line for every call this phone saw, including
  an offer refused on arrival (busy, a kind switched off, media it cannot play), one that never rang, and a glare
  loser's own offer. So a start line with no end line means the process died during the call. The end line gives the
  reason and the phase the call ended from, and its times run from the call's start: ring, answer, connected, talk.
  It also counts restarts and drops, says whether a Wi-Fi Direct group was offered and taken, and counts the TURN
  servers and STUN URLs the media gathered from. The line is written beside the thread's record (§3.12), never in
  its place.
- `call_route` comes from the WebRTC session, once per selected pair: each end's type, the local transport and
  network, and whether the pair rides the group.
- `call_media` comes from `CallMediaSampler`: one reading 5 s after the media connects, then one every 30 s while it
  is up, through a reconnect too. It reads on a second `CallStatsReader` of the session's own, because a reader's
  rates and recent loss run from its previous report, and sharing one would cut the diagnostics card's short.
- `call_direct` comes from `DirectCallPath` as a leg gives its radio back. It records how the leg ended and how long
  the group and the lane took to come up. When the call ended the leg, the end is the call's, whatever the lane saw
  as its sockets closed.
- `talk` (one per walkie burst each way) and `walkie_end` (a session's totals from `WalkieMeter`) come from
  `WalkieTalk`.

The call graph resolves the field log at each record, never as the graph builds (`CallWiring.fieldLog`), as it does
every mesh seam. The words a call's code and libwebrtc spell become constants in `call/CallFieldLog.kt`
(`CallEndReason`, `IceType`, `IceProtocol`, `IceNetwork`, `DirectLegEnd`).

**Lining two logs up needs no notes.** `scripts/fieldlog.py pair` puts two testers' logs on one clock from what both
phones logged at the same moments. A call's two sides begin, connect and end together, and a link between the two
phones comes up and goes down on both at once. Each phone's one pseudonym for the other then follows. The search
needs no call id and no tester's note. It never matches a pseudonym by value: the base text's "two people's logs
cannot be joined" holds for the pseudonyms, not for timing, since anyone holding both logs can line them up. That is
what the alpha needs, and it adds nothing a single log reveals. `docs/FIELD_LOG.md` gives the tolerances, the weak
flag and the clock fallback; `scripts/test_fieldlog.py` is in both CIs.

**What it costs.** About 120 `call_media` lines an hour of calling, each about 400 bytes: some 50 KiB of the 4 MiB.
`walkie_end`'s `transit` carries the two clocks' skew, as `IN` latency does. A `call_media` reading taken while
libwebrtc's report has no selected pair yet leaves the pair fields out. Tests: `FieldEventSchemaTest`'s call and
walkie lines, `CallManagerTest`'s field-log cases, `CallFieldLogTest`, `DirectCallPathTest`'s leg lines, and
`CallStatsReaderTest.theFieldLogKeepsAReadingInWholeUnitsAndConstants`.

## Amendment 2026-10-18 — the LoRa board link and the spools write into it too

**What was asked.** The LoRa plane wrote only a 5-minute sample, and the Internet plane wrote nothing but the spool
counters' quarter-hour movement. A tester's log could not say when the board link dropped or why, how long a relink
took, when the gateway role flipped, or which relay refused us, when, and with what.

**What changed.** Eleven more kinds under the same rules, with fields in `docs/FIELD_LOG.md`:

- **LoRa.** `lora_up`, `lora_down`, `lora_fail` and `lora_role` come from a pure follower of the plane's published
  status (`mesh/lora/LoraFieldLog.kt`), registered through a new seam, `FieldRecorder.follower`. `FieldLog` collects
  each registered stream on the mesh session and resolves it at the session's start, as the samplers resolve theirs
  at each tick. Each reason string the board session writes becomes a `LoraLinkEnd` constant. The follower counts
  the tries before an up itself, because the session's streak carries over unhealthy sessions. The sample widens to
  the election's inputs and the board's own battery and duty-cycle readings. The follower is the first reader that
  takes the order of the status snapshots as the record, so `publishStatus` is now serialised: a pacer's stale Ready
  could otherwise land over the drop's Disconnected. A link ready for only milliseconds can still be conflated away,
  and the doc says so.
- **Spools.** `ScopeSync`'s worker writes `spool_up` at a completed hello, `spool_down` at the end of such a session,
  `spool_fail` for a dial that never reached one, and `spool_err` for each new `err` code in a session. That last is
  an edge, not every refusal: the counter counts those. `MeshManager` registers a 5-minute `spool` sample per relay.
  A line's reason is how that connection ended: our fault, else the spool's close. It is never the status row's
  `lastError`, which can be an older refusal still standing. The words become `SpoolVerdict` constants, with a close
  code or an HTTP status as a number.
- **The route.** `net` writes `InternetGate`'s route kind and Data Saver at each session's start and each change, a
  Wi-Fi to cellular hand-over included. Every spool line is read against it.

**A relay is a pseudonym too.** `FieldWriter.relay` writes `r` + 8 hex of HMAC-SHA256(salt, `relay` NUL + the URL
without its `?k=` token). The input has its own domain, so a relay's name never equals a peer's, and no host, token
or scope id or label reaches the journal. `scripts/fieldlog.py check` refuses a relay field that is not one.

**What it costs.** The sample adds one `spool` line per relay every 5 minutes, about 200 bytes each: some 170 KiB over
three days with three relays, inside the 4 MiB. A flapping relay writes a `spool_fail` per dial, but its backoff
(fifteen minutes at the ceiling) bounds that. Tests: `FieldEventSchemaTest`'s LoRa, Internet and spool lines,
`FieldLogTest`'s follower and LoRa relink cases, `NetEdgesTest`, `SpoolFieldLogTest`, `ScopeSyncTest`'s field-log
cases, `LoraFieldLogTest`, and `scripts/test_fieldlog.py`'s `CheckTest`.
