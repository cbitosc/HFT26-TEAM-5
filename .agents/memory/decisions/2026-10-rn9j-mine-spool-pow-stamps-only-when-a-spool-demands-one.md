---
id: "2026-10.rn9j"
slug: mine-spool-pow-stamps-only-when-a-spool-demands-one
title: "Mine spool PoW stamps only when a spool demands one"
date: 2026-10-12
topics: [spool, pow]
---

# ADR 2026-10.rn9j — Mine spool PoW stamps only when a spool demands one

Status: Accepted (2026-10-12)

**What was observed.** A walkie call over the spool on 2026-10-12, three minutes after midnight UTC, came with
half a minute of 200 MB garbage collections on both phones every two seconds, the main thread stalled 108 ms
on the collector. An allocation profile put 98% of it under `SpoolPow.stamp`, reached from
`ScopeSync.subscribe` → `stampFor`. It looked like the call's media, and it was not: the client mined a 20-bit
stamp (about a million SHA-256s) for every scope on every SUB to a spool with `powBits` set, reusing it only
within one UTC day and only for one worker's life. So every scope was re-mined at each process start, each mesh
start or pause, each relay parked or re-added, and on both phones at once at every UTC midnight — for spools
that already held every one of those scopes and would never have asked.

**What changed.** A stamp goes out only after the spool asked for one. A request carries none; an `err pow` on a
request that carried none marks the scope `stampDemanded` and wakes the worker, and the next round sends a
stamp, unparked and with no `lastError`. A SUB's refusal names no request (§7.2), so `stampedSubs` records
which scopes' latest SUB carried a stamp; a PUSH or APUT knows its own. A refused stamp, or a demand the miner
could not meet within its budget, is the ordinary refusal: the stamp is dropped and the scope parked
(C-9.1-5). A digest for the scope is the spool holding it, and clears the demand. The spec gains C-8-4. This
lands on top of the allocation-free miner and the stamp reuse across S-8-1's window (4fd03d3a), which still
govern a stamp once one is demanded.

The alternative a reader reaches for first is persisting the stamps, so restarts stop re-mining. It keeps the
cost where it was, every other day per scope, for stamps no spool reads: the daemon (`newScopeGates`) looks at
`pow` only for a scope `isUnknownScope` says it does not hold.

**What it costs.** A brand-new scope at a PoW spool takes one more round trip, and the daemon charges its
per-IP new-scope bucket (6 a minute, 24 burst by default) before the PoW check, so the bare attempt and the
stamped one take a token each. A phone creating more than a dozen scopes at one spool inside a minute, or a
household behind one address, could meet `err rate` sooner. A spool that sheds a scope (S-6.2-8) asks again,
and the cached stamp answers if it is still inside its window. The iOS client should match C-8-4; until it
does it simply mines more than it needs, which no spool refuses. Pinned by the four PoW scenarios in
`ScopeSyncTest` (asked bare then stamped and unparked; a spool holding the scope gets none; a reconnect sends
none and a shed scope is asked again; a refused stamp parks) over `FakeSpool`'s unknown-scope gate.
