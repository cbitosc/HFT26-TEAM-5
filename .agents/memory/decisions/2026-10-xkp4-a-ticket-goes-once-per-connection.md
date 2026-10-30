---
id: "2026-10.xkp4"
slug: a-ticket-goes-once-per-connection
title: "a ticket goes once per connection, only to its own host, and STUN rides hello"
date: 2026-10-08
topics: [calls, spool, turn, tickets]
---

# ADR 2026-10.xkp4 — a ticket goes once per connection, only to its own host, and STUN rides hello

Status: Accepted (2026-10-08). Experimental and debug-only, like ADR 2026-10.3tee: the client half of the trial
subset of knit-spool ADR 2026-10.bxsg, specified in `docs/VOICE_CALLS.md` §2.6 (C-7.6-19 to C-7.6-21).

**Context:** Knit Anywhere's step 2 tests one claim: one paying person makes calls connect for free friends too.
knit-spool's `exp/turn` (commits `0891e58` to `db70c9b`) now tells a payer from anyone else by an Ed25519 **ticket**
presented once per connection (`ent`), gates `turnreq` on it instead of on a subscription, and advertises
credential-free **STUN** in `hello.stun` to every client. Before this the client had no `ent`, asked for TURN only
from spools that had answered the DM scope's SUB, and knew STUN only by deriving it from TURN credentials it already
held (`CallManager.stunOnly`). A free phone that never got a credential therefore had no STUN at all: it offered
only private host addresses, coturn keys a relay's permissions on IP alone, so the payer's relay could not permit
it, and the call failed for that reason alone (`anywhere-call-sequence.md` in knit-spool). The missing STUN, not
the ticket, decides the claim.

**Decision:** five rules, each one place in the code.

- **Per connection, right after the hello.** A spool binds an accepted ticket to the connection that presented it
  (S-7.6-15), so `ScopeSync`'s worker presents its host's ticket after the hello and before the first SUB, and again
  the moment a *different* ticket is imported (a store watch for the session's life). The `ent` is not awaited: its
  answer moves `SpoolConnection.ticketState()` on the reader, and a TURN ask waits for one still on its way inside
  its own 3 s. A ticket on every `turnreq` was the alternative, and the spec already rejects it: a signature check
  per record buys no unlinkability on a connection that ties every scope together anyway.
- **Only to its own host.** A ticket carries the holder's daily pseudonym, so it goes only to the spool whose host
  name (`SpoolUrl.hostname`: no port, no token, lowercased, the shape of the daemon's `SPOOL_TICKET_AUDIENCE`) equals
  its `aud`. That is a privacy rule, checked three times: the import refuses a mismatch, `SpoolTickets.presentable`
  re-reads it, and `SpoolConnection.present` checks it against the URL it dialled, so no caller can get past it.
  Tickets are stored one per host name, beside the relay list and its `?k=` tokens in the settings DataStore (the
  protection ADR 2026-09.66cw settled for a bearer token), and kept out of a Knit backup by
  `SettingsKeys.TRANSIENT_PREFIXES`: a ticket is a day's grant to this phone. The client decodes `tk` only for `aud`,
  `nbf`, `exp` and `fl` — `SpoolTicket` declares no `kid` or `ps`, so the pseudonym is never in a field a rendering
  could reach — and never checks `sig`.
- **A refusal is a verdict on those bytes.** C-7.6-19 forbids retrying a refused ticket every round, so the answer is
  written to the stored ticket (`SpoolTicketPolicy`): `expired` drops it; `early` earns one more presentation, on the
  next connection, because the clocks may simply disagree; `malformed`, `key`, `signature`, `audience` and any word
  this build does not know keep the bytes but never present them again. Only an import of different bytes or a
  clear resets a verdict; re-importing the same bytes does not. A `rate` or `internal` answer is no verdict and
  changes nothing. A refused `ent` is never a strike and never aborts, as S-7.6-15 says for the spool.
- **TURN: ticketed first, and never without one where a ticket is needed.** On an `ent` spool a `turnreq` without a
  live ticket that grants TURN (bit 0 of `fl`) is certain to be refused, and it spends the spool's per-IP bucket that
  a ticketed ask from the same NAT may need, so `SpoolConnection.turnCredential` sends none and
  `ScopeSync.turnCredentials` skips that spool with a log line. A connection holding such a ticket, accepted or on its
  way, is asked on every call whether or not the peer's scope is answered there (C-7.6-21), and sorts ahead of the
  subscribed ones before `MAX_TURN_SPOOLS` cuts the list: it is the payer's relay, and the cap of two must not crowd
  it out. A spool's `err ticket` to a `turnreq` forgets the connection's accepted state, whatever our clock says.
- **STUN from every hello on every call.** `CallManager.servers` adds the connected spools' `stun` URIs to whatever
  TURN was issued, or to the cached fallback, as one credential-free server: deduped against the fallback's derived
  `stun:` URLs, capped at four in all (`StunServers.MAX`; each URL is a binding request on every network the phone
  has, which is why the cap tempers C-7.6-20's "every"). STUN rides the hello rather than the peer's OFFER because a
  free *caller* gathers before any answer arrives.

The STUN URL rule (`call/StunServers`) is narrower than the spec's `^stuns?:[^\s,]+$`. libwebrtc parses every ICE
URL when a call builds its peer connection, and one it refuses — a query (`?transport=`), `user@host`, a port outside
1..65535 — fails the whole configuration and so the call (`pc/ice_server_parsing.cc`). Only `stun[s]:host[:port]`
passes, with a DNS-shaped name, an IPv4 literal or a bracketed IPv6 one, both when a hello is read and when a call
gathers. `stuns:` passes because the spec allows it, but libwebrtc has no STUN over TLS: it sends a plain UDP binding
to that address, to port 3478 when none is named. An operator should advertise `stun:`.

`TurnSource` stopped being a `fun interface`. STUN is a second question with a different shape — no peer, no wait,
nothing issued, only what the hellos already said — so it is a second abstract method, `stunUrls()`. A default for it
would have kept the SAM call sites compiling and let an implementation that forgot it give every call no STUN,
silently, which is the failure this change exists to close. Returning both from one method was the other option; it
would have tied a synchronous read to a suspending ask that can wait 3 s, and made every caller unpack a pair.

**Consequences / limits:**

- **What the iOS port must copy:** the `ent` record and the hello's `ent` and `stun`; the host rule with the same
  host-name derivation; one ticket per host name held out of backups; presentation after the hello and on import; the
  refusal policy and its `retry:early` verdict; no `turnreq` on an `ent` spool without a TURN-flagged ticket;
  ticketed spools first under the two-spool cap; and hello STUN on every call, with the narrowed URL rule and the cap
  of four.
- **No storefront.** Tickets arrive only through `…debug.SPOOL --es url … --es ticket …` (knit-spool's `ticket-mint`
  prints one). Nothing renews a ticket: when the held one lapses on our clock, nothing is presented until the next
  import. C-7.6-19's "again before the ticket expires" is met by presenting each import at once, so a storefront that
  fetches the next ticket before `exp` is all renewal needs.
- **One ticket per host name.** Two spools on one host but different ports share it, as they would share an
  audience. An IPv6-literal relay can never hold one, because the daemon's audience is a lowercase host name.
- **An `ent` nobody answers** stays pending for the connection's life; a TURN ask waits for it only its own 3 s.
- **Guards:** `SpoolTicketRecordsTest` pins the `ent`, `tk`, `sig` and hello vectors to knit-spool's
  `TicketVectorTest`, and `SpoolTicketsTest` pins the `knit-ticket:v1:` text, the host rule and the policy table.
  `SpoolConnectionTest` covers when `ent` goes out, refusals without strikes, a mid-connection replacement, and the
  `turnreq` gate (none without a ticket, a wait for a pending one, a local lapse). `ScopeSyncTest` checks that a
  ticketed spool is asked with no SUB answered and first, that an unticketed `ent` spool is skipped, the policy across
  reconnects, and that no log line carries a ticket. `CallManagerTest` covers STUN with TURN, without it, beside the
  cached fallback, the dedupe, the cap and the URL rule. `SettingsSnapshotTest` keeps tickets out of a backup.
