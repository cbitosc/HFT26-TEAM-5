---
id: "2026-10.5buf"
slug: a-bluetooth-file-feed-is-clocked-by-its-receiver-s-reports
title: "A Bluetooth file feed is clocked by its receiver's reports"
date: 2026-10-07
topics: [ble, mesh, blobs]
---

# ADR 2026-10.5buf — A Bluetooth file feed is clocked by its receiver's reports

Status: Accepted (2026-10-07), dark in release behind `BuildConfig.BLE_FEED_WINDOW` until its device A/B, and on in
the first release that carries nsr7's `blobprog`. Builds ADR
2026-10.nsr7's phase 2. For the links it covers, it replaces ADR 2026-10.8jwn's budget and its split, and ADR
2026-10.2da4's earned budgets; the boost's interval ask stays. No wire change.

**What was observed.** On 2026-10-07 an A/B ran v2.8.0 against HEAD 3a6f5d08 on the P3, P8 and P9, Wi-Fi Aware on and
off, two reps per cell. It showed that a fixed pace is wrong both ways:

- **v2.8.0's 28 KiB/s overfeeds a slow pair.** 8jwn measured 1M drains of 4.8–11 KB/s, and texts waited 50–280 s
  behind a photo.
- **Some pairs drain more than that.** In the A/B's T8 cell, where every phone was dark and the P8 sent, the same
  28 KiB/s delivered a 341 KB photo in about 12 s.
- **HEAD's 4 KiB/s base, the safe floor for chat, is slow.** It took 111 s for that cell. The boost's 18 KiB/s rescues
  only a link that a lit side earns, and release keeps the boost dark (`BLE_LINK_BOOST`). A release cut from HEAD
  would feed every Bluetooth file at the base.

The receiver's progress reports (`blobprog`, nsr7) already measure what the air drains.

**What changed.**

1. **The feed is a window, not a pace (`mesh/link/FeedWindow`), sized as BBR sizes one.**
   - **What it holds back:** the bytes written and not yet reported held: the stack's queue, the air, and what landed
     since the last report. It writes while that is under `rate × (rtt + QUEUE_MS)`, `QUEUE_MS` being 250 ms, from
     `MIN_WINDOW` (4 KiB) to `MAX_WINDOW` (64 KiB); 8 KiB before a rate. Nothing but a report frees it, so a sender whose
     CPU sleeps (a dark phone) is woken by the reports it waits on.
   - **The drain (`rate`):** Δheld ÷ Δms between reports, on the receiver's clock, over at least `MIN_SAMPLE_MS`
     (250 ms; a reader catching up a burst reports bytes ms apart, which would read MB/s). The largest of the last
     twelve samples, a few seconds of them, up to a 64 KiB/s ceiling. Four, about a second, let a feed that was briefly
     the limit (a dark sender waking late for a report) drag its own window down in a spiral (device sweep 6: 20 → 4.5
     KB/s on a link that drained 15). A link that really slowed is read from the round trip: a chunk back after half
     again what it should have taken (the round trip, the queue, and the bytes ahead of it at the drain) waited behind
     a growing queue, and the filter keeps only what it measures next. Judged by the bytes ahead, not the round trip
     alone, which a faster earlier file can leave short (the reviewer's fifth pass).
   - **The round trip (`rttMs`):** from a chunk's write to the first report holding it, on the sender's clock; the
     smallest in the last 10 s (`RTT_WINDOW_MS`), compared raw so a sample clamped to the 3 s ceiling never renews it.
     A sample taken behind the standing queue counts the queue, so the smallest is the one that does not; when none
     smaller has come for 10 s, the window drops to one smallest chunk's room for a round (`probingRtt`) and the first
     chunk back, with at most one chunk ahead of it, is the round trip whatever it reads. That is how the estimate rises
     when the link slows mid-file, at about 12% of a 900 ms link's drain. The round trip is the link's, kept across its
     files: a file fed in full leaves its tail in the stack's queue (ADR 2026-10.nxfb), and taken fresh each stream the
     estimate grew with every file sent back to back until it locked at 3 s. A PHY step forgets it (`forgetRtt`). It
     can lag a rise between files (a peer's screen going off lets the link boost go, with no PHY step) until the probe.
   - **Why the round trip, and not the gap between reports:** a window covering only the queue and the report gap
     (W4–W6, below) gave a lit sender to a dark receiver, whose reports came back slowly, half the link (device sweep 4:
     2.9 and 4.0 KB/s on a link that drained 5.3 and 8.8). A window over a measured round trip without the minimum and
     the probe (a shelved rewrite) grew by the standing queue every round, to a 3 s queue at 10 KB/s.
   - **A queue standing past two windows at a report also clears the drain's filter**, keeping the latest sample: the writer
     stops at one window, so it fires when the window shrinks under a queue sized for the old one. One window fired on
     nearly every report of a lit sender, since what a report finds unheld is the queue plus the round trip's bytes
     plus the writer's last chunk (the reviewer's port: 60–80% of its drain).
   - **Chunks:** about 250 ms of the rate each, 2–16 KiB.
   - **A receiver that reports every two seconds** (its window switch off, or a build that predates the window) never
     reports the starting window, and a window waiting on it ran in three-second fits below the paced base. Two report
     gaps of 1.9 s or more in a row, each covering more than one chunk, pace the stream to its end. A windowed receiver
     reports every chunk, so its gaps are the chunks' own, long on a slow link or across a probe; read by time alone
     they paced a slow link's window away (the reviewer's fourth pass).
2. **Which links.** The window covers a 1M/2M link to an Android peer carrying `CAP_BLOB_HAVE`
   (`BluetoothMeshTransport.windowed`).
   - **Kept on today's `CodedPhyPolicy.pace`:** an iPhone (`DoorbellPolicy.serves`), since nothing reports to it and
     its own reports are not measured yet; a Coded link, which waits on its walk trial; and an older build. A build
     that carries `CAP_BLOB_HAVE` but predates `blobprog` (origin/main at 403cd861) costs only a 3 s stall on the
     first 8 KiB, then the pace.
   - **That pace is also the fallback:** a windowed feed whose receiver has been silent for `NO_REPORT_MS` (3 s) is
     paced, and goes back to the window at its next report.
3. **No split.** A windowed link ignores the 8jwn split and the boost budgets. One controller's shared air shows up
   as each link's own lower drain, which its window follows. A windowed feed is not counted in a paced feed's split
   either (`pacedFeedingLinks`): it takes none of the budget, and counted there it halved an iPhone's pull beside it.
   - **The boost's interval ask stays:** it is what raises a drain, and the window takes the rise by itself.
4. **Reports on every chunk while the window's switch is on**, once a sender's smallest chunk (2 KiB) has landed since
   the last report (`RxReports.WINDOWED_EVERY_BYTES`); also every 500 ms (`WINDOWED_EVERY_MS`), and **once more 500 ms
   after chunks no later report carried** (the trailing report, a backstop). A chunk is about a quarter second of the
   drain, so about four reports a second at any rate, about 500 B/s of reverse air. Between two dark phones only a
   Bluetooth packet wakes either CPU, so a report on the chunk itself is the one that always comes: an 8 KiB rule left a
   smaller window waiting out the sender's 3 s silence every few seconds (device sweep 1). A windowed sender writes nothing more until it hears,
   so without these a fast pair's starting window landed inside one interval and the feed waited out its 3 s of
   silence on every stream (the bluetooth-reviewer's finding). The byte rule runs on the reader, awake for the chunk;
   the trailing report is a `delay` a suspending dark receiver may sleep through, and then the sender's next chunk
   wakes it, after the sender's 3 s silence. A stall still sends nothing. With the switch off, as in release, a receiver reports every 2 s of chunks, unchanged: `FeedMeter`'s settle
   (`STEADY_REPORTS`), smoothing and first-rate deadline count reports, and are tuned to two seconds of them. The
   receiver keys this on its own switch, so a trial sets the switch on both phones (`ble-drain-trial.py` and the A/B
   driver do). A windowed sender to a 2 s reporter (a phone left on `pace`, or a build with the switch off) gets its
   window back only at a 2 s report or its 3 s silence, so its feed runs in fits. Flipping a default cannot change
   builds already installed, so **`blobprog` (nsr7) and the window ship in the same release** (decided 2026-10-07):
   the release that first carries nsr7 turns `BLE_FEED_WINDOW` on, every install of it reports at the window's
   cadence, and none reports every 2 s to a later sender that windows. The window's device A/B is therefore a gate on
   that release, not only on the flag.
5. **The rest of nsr7 reads the window.**
   - **ETAs:** the writer notes the window's rate as its pace, moving it only past a quarter, since a pace move resets
     the meter's rate.
   - **`fileFeed`:** reads a windowed feed without the boost's settle.
   - **`expectedFileRate`:** answers the link's last measured drain (`FeedMeter.lastRate`), shared among the feeds
     going beside it.
   - **`FeedMeter` samples about every two seconds, however often the receiver reports** (`MIN_SAMPLE_MS` = 2 s, on
     the receiver's clock). Its settle, smoothing and first-rate deadline count samples, tuned to nsr7's two-second
     reports; fed a windowed receiver's report on every chunk, a single quarter-second sample could set a deadline that
     lapses mid-file and serves a second copy beside it (a side note, 2026-10-07). A paced receiver's reports are two
     seconds apart already, so release reads them as before.
6. **A report, or a frame queued to go out, wakes a waiting writer** (`FramedLink.awaitReport`), so a window freed by a
   report fills at once and a frame waits for no window. The frame matters most when two windowed feeds cross on one
   link: each side's reports on the other's feed are frames, and held behind its own wait they would starve the other's
   window. The fallback's pace wait ends early on a report only (`awaitPaceOrReport`), never on a frame's wake, or the
   pace would follow the link's frames (the reviewer's second pass: 41 chunks where the pace allows 4, pinned by
   `FramedLinkTest.framesQueuedDuringAWindowedFeedsFallbackDoNotHurryItsPace`).

**Device sweeps (2026-10-07, P9 → P8, both screens off, boost off, a 400 KB random file, `ble-drain-trial.py`).**

- **Sweep 1, the first build** (a window of 500 ms of the drain alone, reports on 8 KiB): 4.1–4.7 KB/s against
  the 4 KiB/s pace's 4.1, and once 9.9. The sender's CPU slept, so it wrote only at a report; the window, refilled
  once a report, capped the feed at window ÷ gap and sank to its floor. A 250 or 1000 ms queue did no better.
- **Sweep 2, W3** (the report gap in the window, reports on every chunk): 9.4, 4.6 and 8.6 KB/s at 250 ms, 8.0 and
  6.5 at 500 ms, against the link's unbounded 9.0 and 6.2. The slow rep sat at the floor once the link stepped to a
  255 ms interval.
- **Sweep 3, W4** (the 8 KiB floor kept, and a growth rule that never fired; the standing check at one window),
  window and unbounded reps alternating: 9.2/9.8, 5.0/4.0, 5.1/4.6 and 8.0/8.6 KB/s. The window ran at about the
  link's drain in every pair, dark to dark.
- **Sweep 4, W6** (W4 with the standing check at two windows and the dead rule gone), the P9 lit → the P8 dark:
  2.9/5.3, 4.0/8.8 and 7.6/5.1 KB/s. The window covered the report gap but not the round trip, and a dark receiver's
  reports came back slowly. Both lit with the boost asked: 4.3/8.0; the boost was not earned.
- **Sweep 5, W7** (BBR's minimum round trip and probe, before the fourth review's fixes): the P9 lit → the P8 dark,
  7.7/7.9, 3.8/2.6 and 5.6/8.7 KB/s; both dark, 4.8/6.4 and 8.0/8.4. Texts behind the window took 2–5 s, behind
  the unbounded file 20–154 s.
- **Sweep 6, W9** (the fourth review's fixes; a four-sample drain filter), window/unbounded: the P9 lit → the P8
  dark 6.6/7.3 and 7.1/8.6; both dark 6.1/8.2 and 4.7/4.5; the P8 dark → the P9 lit, never run on a window build
  before, 9.1/15.2 and 17.7/22.3 KB/s. The 74% and 60% reps spiralled: the rate fell while the feed was the limit.
- **Sweep 7, W10** (this design: twelve samples, and the round trip's slowdown signal): both dark 4.7/4.9 and 3.9/2.5;
  the P8 dark → the P9 lit 12.0/12.6 and 11.2/13.5 KB/s, 83–154% of the link. Texts behind the window took about 2 s
  (once 6.8), behind the unbounded file 18–159 s. The lit → dark cell was not re-run on W10 (W9: 91% and 83%).
- **Not run yet:** a link slower than 1.5 KB/s, two feeds crossing on one link, files back to back, the boost earned
  (no rep earned it: `earned=false` throughout), and the full A/B against v2.8.0 on the P3, P8 and P9.
- **Text latency.** The P8 and P9 had the Internet (spool) plane on, so every DM text of sweeps 1–3 went by the relay
  in about 2 s and timed nothing (spoolPushed 75 on the P9). With it off, texts behind an unbounded file waited
  19–58 s, and behind W4's window about 2 s, the harness's own floor. `ble-drain-trial.py` now refuses to run with
  the plane on, and the A/B driver switches it off.
- **The simulation** (`FeedWindowTest`, with a receiver that reports as `RxReports` does and a sender that sleeps or
  not): a lit sender whose reports take 900 ms to come back gets 85% or more of 6, 10 and 20 KB/s, with the queue
  under 2 s; a dark one, 85% of 6 KB/s less the probe; a round trip rising 50 → 900 ms mid-file is followed.

**The alternative a reader would reach for first:** put 28 KiB/s back, or raise the base. Every fixed number is a
guess at one link, and these links ran from 4.8 to more than 28 KB/s across one lab in one afternoon. The measured
drain is the only number that is right on each of them.

**Not taken.**

- **A BBR-style probing cycle** (pace gain 1.25 and 0.75). The report's reset of the estimate already probes, and
  the device data does not ask for more yet.
- **The window on Coded:** it waits on the walk trial.

**What it costs.**

- **Air and wakes:** about four reports a second per incoming file while the switch is on (about 500 B/s of reverse
  air), up from one every two seconds; each wakes the sender's CPU.
- **A text's wait:** a text sent behind a file waits up to the window ahead of it, about a quarter second of the drain
  plus the report gap, or the 4 KiB floor on a slow link (about 0.8 s at 5 KB/s), plus one chunk. Measured on the
  phones: about 2 s, the harness's floor.
- **A late report:** a report queued behind the receiver's own outbound file overstates the queue, so the feed holds
  back, never overfeeds.
- **A lie:** a lying receiver can raise its own feed only to the ceiling and `MAX_WINDOW`, about a second of queue at
  64 KiB/s. It can slow its own feed, which costs no one else.
- **The trap:** a test clock that jumps past the writer's wait reads as the writer asleep (`FeedWindow.slept`), not the
  receiver silent. `FramedLinkTest`'s silent-receiver case runs on a real clock for that reason.

**Tests.**

- **Unit:** `FeedWindowTest` simulates a link draining at a set rate with lagged reports: steady at 20 KB/s, slow
  at 4.8 KB/s and fast at 40 KB/s, each found and kept within about a second of queue; a drain that falls to 6 KB/s;
  a report 900 ms late. It also pins the bounds: the starting window, silence, the writer's sleep, an overclaim, a
  reordered report, a resume's offset, the extrapolation bound, and chunk sizes.
- **`FramedLinkTest`:** `aWindowedFeedHoldsAtItsWindowUntilAReportFreesIt` and
  `aWindowedFeedWhoseReceiverIsSilentIsPacedUntilItReports`.

**Device A/B owed** (P3/P8/P9, two reps, the 2026-10-07 harness with its text probes). One HEAD debug build, switched
by `…debug.LINKTUNE --es feed window|pace` and `--ez boost`:

| Arm | Build | Feed | Boost |
|---|---|---|---|
| A | v2.8.0 | its pace | none |
| C | HEAD | the caps | off (a release cut today) |
| D | HEAD | the window | off (the candidate) |
| E | HEAD | the window | on |

- **Pass, D against A:** pictures held as fast or faster in every cell, T8 included. Texts behind a photo: median
  ≤ 5 s, max ≤ 15 s. No more copies per delivery.
- **Before the arms:** pick `QUEUE_MS` with `scripts/ble-drain-trial.py --feed window --window-queue-ms 250|500|1000`
  on one link, reading the receiver's `rx … in` line, the texts, and the `bt file window` line.
- **Flipping the release default** is a separate commit after the A/B.
