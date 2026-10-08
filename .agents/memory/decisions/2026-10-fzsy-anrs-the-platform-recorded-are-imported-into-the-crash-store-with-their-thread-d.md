---
id: "2026-10.fzsy"
slug: anrs-the-platform-recorded-are-imported-into-the-crash-store-with-their-thread-d
title: "ANRs the platform recorded are imported into the crash store with their thread dumps"
date: 2026-10-08
topics: [crash-reports, reliability, android]
---

# ADR 2026-10.fzsy — ANRs the platform recorded are imported into the crash store with their thread dumps

Status: Accepted (2026-10-08)

**What was observed.** Play reports two ANRs on a realme Note 60x (Unisoc, Android 16), 2.7.0 and 2.8.0, one
user, issue `d910d6214724421fbb58346b7fd3cb89`: *"Executing service app.getknit.knit/.mesh.MeshService"*, cause
*"Native method - android.os.MessageQueue.nativePollOnce"*. Neither the Console nor the Reporting API's
`errorReports:search` returns any report text. The main thread was idle when it was sampled, so the stack names
nothing. Either the work that held the 20 s executing-service window had finished by then, or the process could
not run at all (a freezer — AOSP's or an OEM's — holding the start message). It is not the graph build ADR
2026-09.vztn moved off the main thread: that fix shipped in 2.7.0 and both reports are on or after it. With the
evidence Play gives, no fix can be chosen.

**What changed.** At every process start, five seconds in and on `Dispatchers.IO`, `crash/AnrCapture.importAnrs`
reads `getHistoricalProcessExitReasons` (API 30+) and copies each `REASON_ANR` record not seen before into the ADR
028 crash store, with the dump `getTraceInputStream()` keeps. A background ANR kills the process, so the record
is waiting at the next start. `AnrTrace.condense` makes the dump read like a crash report:
`ApplicationNotResponding: <subject>` stands where the exception class would, then every thread, `main` first.
So `CrashStore`'s summary, the issue form and `crash-report.yml`'s signature all work unchanged (the signature is
the main thread's top frames). What it keeps is an allow-list, and for each part the reason:

- The subject, `Cmd line`, ABI, the `Rss*Kb` / `VmSwapKb` memory lines, and any line naming *frozen*. These are
  short and they answer the question.
- Every thread block of our own process section. With `main` idle, the other threads are the diagnosis.
- The `Waiting Channels` section. Each thread's kernel wait is what shows a frozen process (`do_freezer_trap`)
  when every Java stack looks idle.
- Not kept: ART's runtime statistics (noise), the build fingerprint (the header has it), and Android 14+'s
  `CriticalEventLog`, which names other apps' recent ANRs and is not ours to copy.

Native frames (`native: #NN pc …`) are now frames to `CrashRedactor`, passed verbatim, with the per-install APK
directory collapsed to `[app]/` and any other on-device path to `[path]` before anything is written. A thread
header (`"name" daemon prio=N tid=N State`) has only its name scrubbed: ART's long state names
(`WaitingInMainDebuggerLoop`) otherwise read as base64. Everything else goes through the same structural redaction
as a crash message.

**What a frozen process looks like** (emulator, API 36, `am freeze` then two taps, then Close app on the dialog).
debuggerd cannot reach a frozen process, so the dump has no thread section at all. It has a `----- dumping pid`
line, `libdebuggerd_client: failed to read status response from tombstoned: timeout reached?`, and a `Waiting
Channels` section with `do_freezer_trap` on every thread, retried once ten seconds later. The ANR dump also took
12 s instead of the usual one. So a frozen ANR does **not** produce Play's `nativePollOnce` stack, which needs a
main thread that could be dumped. On this evidence the realme reports read as a process that was running but had
finished whatever held the window, or had been thawed before the dump. The importer keeps both shapes
(`AnrTraceTest`'s frozen case is that dump, cut down). A real ART thread dump (`SIGQUIT`, 26 threads, 85 KB)
condenses to 72 KB with every thread kept.

An ANR stamped before the running build's `lastUpdateTime` ran an earlier build. Its header says
`app: unknown - recorded before Knit was updated to …`, a value `crash-report.yml`'s app-line parser rejects on
purpose, so nobody is pointed at the wrong release's `mapping.txt`.

A watermark file beside the reports (`anr-seen`, the newest imported timestamp; not a `crash-` file, so `list`,
`prune` and `clear` never touch it) makes each ANR import once. A restart, a pruned report or the user's Delete
all never brings one back. Only the five newest unseen are read.

The alternatives:

- **An in-process ANR watchdog** (a thread pinging the main looper). It does not work here: it sees a blocked
  looper, and this ANR's looper was idle or frozen. A frozen process also runs no watchdog.
- **A crash SDK.** Ruled out by ADR 028.
- **Reading the dump only when Diagnostics opens.** That would lose it: the platform keeps traces only a few days.

**What it costs and does not cover.** An ANR report may run to 120,000 characters, twice a crash's cap, so the
directory's ceiling rises to about 600 KB. A phone with nothing new pays one binder call per start. Not covered:
API 29 (no exit-info API); an ANR the user waited out from the dialog, since the process lives and no exit is
recorded; and the dump's own retention (an ANR first read after the platform dropped its trace is stored with its
one-line description only). No `Frozen:` preamble line appeared on the emulator. The keep rule for one is a
guess at an OEM's shape; the waiting channels carry the evidence either way. Tests:
`AnrTraceTest` (order, allow-list, path collapse, redaction round trip) and `CrashStoreTest`'s import cases
(watermark across Delete all, five-newest cap, the earlier-build header, an unreadable dump).

The trap: `AnrTrace` is the privacy boundary for native frames. `CrashRedactor` trusts any `native: #` line
verbatim, so a new kept section must collapse its paths first, or be left out.
