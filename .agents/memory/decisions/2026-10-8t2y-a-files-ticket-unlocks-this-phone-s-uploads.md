---
id: "2026-10.8t2y"
slug: a-files-ticket-unlocks-this-phone-s-uploads
title: "A files ticket unlocks this phone's uploads, and each gate is read from its own bit"
date: 2026-10-13
topics: [calls, spool, tickets, attachments]
---

# ADR 2026-10.8t2y — A files ticket unlocks this phone's uploads, and each gate is read from its own bit

Status: Accepted (2026-10-13). Amends ADR 2026-10.xkp4 ("on an `ent` spool a `turnreq` needs a ticket"). The client
half of knit-spool ADR 2026-10.h6pt (tickets gate verbs per connection, and only an uploader's ticket pays for
attachments), specified in `docs/VOICE_CALLS.md` §2.6 (C-7.6-24 revised, C-7.6-30) and `docs/SPOOL_PROTOCOL.md`
§7.2 and §7.3. The issuer's half is knit-spool-coordinator ADR 2026-10.ukvm, as amended by knit-spool-coordinator
ADR 2026-10.mrnm: its 200 names `flags: { turn, files, cast }`, and new accounts get files by default.

**What was observed.** knit-spool's `exp/turn` (`17a6b84` to `d0ae0a3`) gates `aput` behind a files ticket (`fl` bit 1,
`FL_FILES = 2`) when the operator lists `files` in `SPOOL_TICKET_GATES`, and says which verbs it gates in a new HELLO
key, `entGates`. A client built before this read `ent` as "every gate is on", so on a spool gating only files it would
have skipped TURN it could have had, and it read nothing about uploads: it sent `aput` for every attachment, collected
`err ticket`, and retried every round, while the chat said the photo was relayed.

**What changed.**

- **Each gate from its own bit (C-7.6-30).** `SpoolHello.ticketGates` mirrors knit-spool `Records.kt`'s exactly: no
  `ent`, 0, whatever `entGates` says; `ent` without `entGates`, the legacy 9 (TURN and cast); otherwise `entGates`.
  `SpoolConnection.gates` holds it for the connection's life. `turnCredential` and `ScopeSync`'s `turnAsk` gate on bit
  0, `canCast` on bit 3, uploads on bit 1. A free phone on a files-only spool asks it for TURN like any unticketed
  spool, and a TURN ticket still counts there as `ticketedTurn`, since h6pt keeps it waiving the subscription gate.
- **Who may upload (C-7.6-24).** `SpoolConnection.uploads()` answers `SpoolUploads.Open` where bit 1 is not gated or
  the live accepted ticket grants files; otherwise `Locked` with `missing`, `expired` (it lapsed on our clock), `flags`
  (it lacks bit 1) or `pending` (a files ticket is on its way — the spool judges an `aput` after the `ent` before it,
  and that answer may be a refusal). `ScopeSync`'s worker narrows it with what the spool said: an `aput` refused
  `missing`, `expired` or `flags` locks that connection until a files ticket is next accepted, and `budget` marks the
  ticket's pseudonym spent (`Spent`).
- **Locked means settled, not retried.** In `healAttachments`, an attachment of ours on a connection that may not
  upload is settled locally (`LocalPass.LOCKED`, counted as `spoolAttachLocked`): no `ahave`, no `aput`, out of the
  32-scan window, no 15 s re-look. The fetch branch is untouched, so the same phone still receives every attachment
  through that relay (S-7.6-23). When `ticketAnswered` accepts a files ticket the refusal lock lifts, and unless its
  pseudonym is the one spent there, the attachments settled as locked are un-settled and the worker marked dirty, so
  they go up on the next round rather than an hour later. The answer lands on the connection's reader while a round
  runs, so `settleLocked` re-reads the upload state under the settled set's lock and settles nothing if it opened:
  `reopenUploads` moves the state before it takes that lock, so a lock read just before the acceptance never strands
  an entry until the ten-minute recheck. `putChunk` reads `err ticket` before `survivable`, which frame PUSH shares and
  which stays as it was; a ticket refusal is no error on the relay row, since the upload state says it.
- **The spent mark lives in memory, per (relay host, `ps`).** `ScopeSync.spentUploads` keys the relay's host name
  (the `aud` form) to the one pseudonym spent there. It is on `ScopeSync`, not the worker, whose per-dial state goes
  with every session, so it survives a reconnect and a re-presented ticket with the same `ps` — the refresher's next
  fetch on the same day is one. An accepted files ticket with another `ps` clears it. It is never written to the
  verdict journal: `SpoolTicketPolicy.afterAnswer` would read a stored word as "never present again" and take TURN and
  casts from a ticket whose only fault is a spent upload day. A process restart forgets it, which costs one refused
  chunk, and the daemon's budgets are memory only too.
- **The pseudonym, read apart.** `SpoolTicket` still declares no `ps`, so the claims can never print it.
  `HeldTicket.pseudonym` decodes it separately into a `TicketPseudonym`, equal by its bytes and printed as
  `TicketPseudonym(…)`; nothing outside `SpoolTickets.kt` reads the bytes, and neither the status, the bridge nor a log
  line carries it.
- **A late `err ticket` is no refused SUB.** `SpoolConnection.onErr` drops a `ticket` refusal whose `q` names nothing
  pending (an `aput` that already timed out): a SUB is never refused `ticket`, and reading it as one would unsubscribe
  the scope.
- **The sender's marker.** `RelayFacts.maxUploadBytes` is the max attachment budget over the connected spools whose
  `SpoolStatus.uploads` is `Open`; `maxAttachBytes` stays the download capability that the reader's placeholder reads.
  `AttachmentRelay.Locked` sits between `Unsupported` (no relay carries attachments) and `TooLarge` (none that takes
  ours holds this size), and draws the "Nearby only" marker, its dialog and the staged caption: "Your relays are not
  taking files from this phone". The copy names the relay's rule — files from phones with a ticket for sending them,
  up to a daily amount — never a product, a price or a way to buy one, and reads true for a spent day as well as for no
  ticket. It never calls the daily amount storage: h6pt's budget caps what is sent in a day.

**What ships where.** The gate reading, `SpoolUploads`, the upload lock and `AttachmentRelay.Locked` are main code in
every build. A release phone holds no ticket (the import doors and the refresher sit behind `CALLS_WIRED`, ADR
2026-10.3tee), so on a files-gated spool it is locked and says so, which is the behaviour h6pt asks of any client
without a ticket. The relay row's ticket readouts — `files` among the grants, "Today's uploads here are used up" — and
the debug bridge's `entGates`, `uploads` and per-flag ticket fields stay behind `CALLS_WIRED` and in the debug source
set, as before.

**What it costs, and what it does not cover.**

- **Uploads are one-way for a free member** on a conversation held only by files-gated spools: h6pt's product line,
  not this client's. A scope also held by an ungated spool still takes the free member's upload there.
- **One spent mark per relay host.** Two spools on one host share it, as they share a ticket and an audience.
- **The marker is per phone, not per relay.** It reads `Locked` only when no connected relay takes this phone's
  uploads; one that does is enough for `Relayable`, by the union rule (§9.1).
- **The trap:** "fixing" a locked upload by uploading while some other member's files ticket is live, as casts do. That
  is the sponsorship h6pt rejected. Another: writing the spent mark into the ticket journal so it survives a restart —
  it would kill the ticket's TURN and casts. Kept true by `SpoolConnectionTicketTest` (the gate table, each verb
  against its bit, the upload states, the late refusal), `ScopeSyncTest` (an unticketed member never `aput`s or
  `ahave`s its own attachment and still fetches; a files ticket accepted after a round locked the upload sends it on
  the next round; `budget` holds across a reconnect and a same-`ps` ticket and a new
  `ps` reopens; TURN on a files-only spool; a late refusal leaves the scope subscribed), `RelayReachTest`'s precedence,
  `ChatRelayIndicatorTest`'s Locked dialog, and `InternetPlaneLabTest`'s
  `aFilesTicketHolderSendsAPhotoThroughAGatedRelayAndTheOtherSideStillReceivesIt` on a files-gated `FakeSpool`.
  `SpoolTicketRecordsTest` pins `tkFiles`, `sigFiles` and `helloEntGates`, copied from knit-spool `exp/turn` @
  `17a6b84`.
- **What the iOS port must copy:** `ticketGates` as above; each verb on its own bit; no `aput` and no `ahave` for its own
  attachment on a connection locked by the files gate, with fetching untouched; the pending-ticket lock; the spent
  mark per (relay host, `ps`) in memory, cleared by another `ps`, never a stored verdict; the late-`ticket` rule; and
  the marker's precedence (Unsupported, Locked, TooLarge).
- **Device trial owed:** a P9 holding the account's files ticket and a P7 holding none, on the lab spool with
  `SPOOL_TICKET_GATES=turn,cast,files` (the order's part two): relay-only photo P9 → P7 arrives, P7 → P9 stays "Nearby
  only" with no `aput`, and a small-`ab` ticket hits `budget` once and uploads no more on that `ps` across a reconnect.
