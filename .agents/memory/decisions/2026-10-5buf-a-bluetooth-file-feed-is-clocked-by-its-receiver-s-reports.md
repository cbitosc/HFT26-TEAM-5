---
id: "2026-10.5buf"
slug: a-bluetooth-file-feed-is-clocked-by-its-receiver-s-reports
title: "A Bluetooth file feed is clocked by its receiver's reports"
date: 2026-10-07
topics: [ble, mesh, blobs]
---

# ADR 2026-10.5buf — A Bluetooth file feed is clocked by its receiver's reports

Status: Accepted (2026-10-07), dark in release behind `BuildConfig.BLE_FEED_WINDOW` until its device A/B. Builds ADR
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

1. **The feed is a window, not a pace (`mesh/link/FeedWindow`).**
   - **What it holds back:** the bytes written less the bytes the receiver reports holding. That is the stack's queue
     plus the air.
   - **When it writes:** only while that is under `QUEUE_MS` (500 ms) of the measured drain, from `MIN_WINDOW` (4 KiB)
     to `MAX_WINDOW` (32 KiB).
   - **The drain:** Δheld ÷ Δms, on the receiver's clock, over at least `MIN_SAMPLE_MS` (250 ms; `FeedMeter` keeps the
     same floor). A report sooner after the last sample moves the queue's anchor and takes none: a receiver's reader
     catching up a burst reports bytes ms apart, which would read MB/s and hold a 32 KiB window over an 8 KB/s link
     (the reviewer's third pass). The rate is the largest of the last four samples (about 2 s), up to a 64 KiB/s
     ceiling.
   - **Between reports:** the queue is drained at the latest sample, for at most 1.5 s past a report.
   - **A queue standing past two windows at a report clears the filter.** The air set that sample, and the larger
     ones before it are a link that slowed, which the filter would otherwise overfeed for 2 s.
   - **Faster links find themselves.** A link faster than the rate empties the queue between reports, and the report
     resets the estimate, so the writer refills the whole window and the next sample reads about twice the rate.
   - **Chunks:** about 250 ms of the rate each, 2–16 KiB.
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
4. **Reports every 500 ms while the window's switch is on** (`RxReports.WINDOWED_EVERY_MS`), about 240 B/s of reverse
   air per incoming file, **on the chunk that brings 8 KiB since the last report**, and **once more 500 ms after chunks
   no later report carried** (the trailing report, a backstop). The byte rule waits 100 ms after the last report. A windowed sender writes nothing more until it hears,
   so without these a fast pair's starting window landed inside one interval and the feed waited out its 3 s of
   silence on every stream (the bluetooth-reviewer's finding). The byte rule runs on the reader, awake for the chunk;
   the trailing report is a `delay` a suspending dark receiver may sleep through, and then the sender's next chunk
   wakes it, after the sender's 3 s silence. A stall still sends nothing. With the switch off, as in release, a receiver reports every 2 s of chunks, unchanged: `FeedMeter`'s settle
   (`STEADY_REPORTS`), smoothing and first-rate deadline count reports, and are tuned to two seconds of them. The
   receiver keys this on its own switch, so a trial sets the switch on both phones (`ble-drain-trial.py` and the A/B
   driver do). A windowed sender to a 2 s reporter (a phone left on `pace`, or a build with the switch off) gets its
   window back only at a 2 s report or its 3 s silence, so its feed runs in fits. Flipping a default cannot change
   builds already installed, so **`blobprog` (nsr7) must not ship in a release with this switch off** and then meet a
   later release that windows: either both ship together, or that release's receivers report at the window cadence
   whatever their own switch (with `FeedMeter`'s settle counted in time, not reports), or the window waits on a
   capability bit saying the peer reports for it. Undecided; it blocks a release that carries nsr7, not this branch.
5. **The rest of nsr7 reads the window.**
   - **ETAs:** the writer notes the window's rate as its pace, moving it only past a quarter, since a pace move resets
     the meter's rate.
   - **`fileFeed`:** reads a windowed feed without the boost's settle.
   - **`expectedFileRate`:** answers the link's last measured drain (`FeedMeter.lastRate`), shared among the feeds
     going beside it.
6. **A report, or a frame queued to go out, wakes a waiting writer** (`FramedLink.awaitReport`), so a window freed by a
   report fills at once and a frame waits for no window. The frame matters most when two windowed feeds cross on one
   link: each side's reports on the other's feed are frames, and held behind its own wait they would starve the other's
   window. The fallback's pace wait ends early on a report only (`awaitPaceOrReport`), never on a frame's wake, or the
   pace would follow the link's frames (the reviewer's second pass: 41 chunks where the pace allows 4, pinned by
   `FramedLinkTest.framesQueuedDuringAWindowedFeedsFallbackDoNotHurryItsPace`).

**The alternative a reader would reach for first:** put 28 KiB/s back, or raise the base. Every fixed number is a
guess at one link, and these links ran from 4.8 to more than 28 KB/s across one lab in one afternoon. The measured
drain is the only number that is right on each of them.

**Not taken.**

- **A BBR-style probing cycle** (pace gain 1.25 and 0.75). The report's reset of the estimate already probes, and
  the device data does not ask for more yet.
- **The window on Coded:** it waits on the walk trial.

**What it costs.**

- **Air:** two reports a second per incoming file while the switch is on, up from one every two seconds.
- **A text's wait:** a text sent behind a file waits up to the queue (about 0.5 s of drain, more on a slow link at the
  4 KiB floor) plus one chunk. A slow link's floor is about 0.8 s at 5 KB/s.
- **A late report:** a report queued behind the receiver's own outbound file overstates the queue, so the feed holds
  back, never overfeeds.
- **A lie:** a lying receiver can raise its own feed only to the ceiling and `MAX_WINDOW`, which is about 0.5 s of
  queue at 64 KiB/s. It can slow its own feed, which costs no one else.
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
