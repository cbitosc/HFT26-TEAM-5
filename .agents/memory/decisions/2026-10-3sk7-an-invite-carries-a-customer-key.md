---
id: "2026-10.3sk7"
slug: an-invite-carries-a-customer-key
title: "An invite carries a customer key, and the app fetches its own ticket"
date: 2026-10-13
topics: [calls, spool, tickets, relays]
---

# ADR 2026-10.3sk7 — An invite carries a customer key, and the app fetches its own ticket

Status: Accepted (2026-10-13). Experimental and debug-only, like ADR 2026-10.3tee. Amends ADR 2026-09.tmbq (the
invite gains two fields the phone never mints) and ADR 2026-10.xkp4 ("No storefront … Nothing renews a ticket"). The
client half of knit-spool-coordinator ADR 2026-10.ukvm, specified in `docs/VOICE_CALLS.md` §2.6 (C-7.6-27 to
C-7.6-29) and `docs/RELAY_INVITE.md` §2.

**What was observed.** A tester on a ticketed spool added the relay from one invite and then ran an adb line for
each day's ticket (`…debug.SPOOL --es ticket`), printed by knit-spool's `ticket-mint` or the coordinator's
`account create`. Nothing renewed it: a ticket is good for 25 h, so every phone went back to free calls a day after
its last paste (xkp4's own "Consequences"). The coordinator has since built the key swap, `POST /api/v1/ticket`
(knit-spool-coordinator ADR 2026-10.ukvm): a customer key `kak1_…` buys the day's ticket for the account's relay, and says when the account
ends. What was missing was a way for the key to reach the phone and a client that uses it.

**What changed.**

- **The invite carries the key.** `RelayInvite.Body` gains `i` (the issuer's origin) and `k` (the customer key),
  declared after `n`, so the CBOR key order is `v, u, c, n, i, k`. They come both or neither: a lone one, a `k` off
  the pattern, or an `i` that is not a bare `https://host[:port]` origin (`http://` only in a debug build, as `ws://`)
  refuses the whole invite with `BAD_KEY` or `BAD_ISSUER`, as a wrong-length `c` refuses with `BAD_SECRET`. `v`
  stays 1 and `MAX_BYTES` stays 512, because a released app refuses either change; under `ignoreUnknownKeys` a
  released app reading a keyed invite adds the relay and drops the key. The new `ticketInvite` vector pins the bytes,
  built in the test with its own CBOR and computed separately by a throwaway script, since the phone cannot mint it.
- **The phone never mints `i` or `k`.** `mint` has no parameter for them, so the relay row's Share and Copy keep
  writing `u`, `c` and `n` whatever key the phone holds. A forwarded key lends out its holder's quota; only the
  issuer's operator or storefront hands one out. The alternative, a share toggle for the key, would make the person
  who taps it the one who decides to give away a paid account.
- **The sheet and the applier.** The sheet names the issuer's host and says the phone will contact it about once a
  day to fetch the relay's tickets; no price, no product name, no link. Confirming holds the key for the relay's host
  name in the same apply sequence (`RelayInviteApplier.TicketKey`). The same key is a no-op that keeps the
  refresher's schedule; a different one replaces the held key, resets its state (which lifts an `unknown-key` park)
  and the sheet says so ("Save key" when that is all the tap does); an invite without a key keeps a held one, as an
  untokened invite never downgrades a token.
- **A third Internet file.** The key swap is a third destination, so it sits behind a pure seam, `TicketIssuer`,
  with one OkHttp file, `mesh/spool/OkHttpTicketIssuer.kt`, added to detekt's `ForbiddenImport` excludes and to
  `rules/mesh.md`. It **binds to the `Network` `AndroidInternetGate` hands out**, as the link-preview fetch does,
  rather than following the spool dialer, which dials on the default network unbound: an ask is a one-shot request
  that must never ride the Wi-Fi Aware link, and binding costs one line. Unlike the preview fetch it applies **no
  private-address guard**: the origin is one the person confirmed on the sheet, like the relay beside it, which the
  dialer does not guard either, and a debug bench issuer on a LAN or behind `adb reverse` needs exactly that. It
  follows **no redirect**: OkHttp would carry `Authorization` to a same-host redirect target, and the key belongs to
  the origin it arrived with. Short timeouts (10 s connect and read, 20 s call), no retries, no cache, and an 8 KiB
  body cap. The answer is read by a pure `IssuerAnswer.read`: a 401 or 403 counts only with its own `error` word,
  because a proxy or firewall in front of the issuer answers those statuses too.
- **The store.** `data/settings/TicketKeyStore`, a separate journal over the settings DataStore rather than more of
  `SettingsStore`, keyed by relay host name (the `aud` its tickets carry): `spool_key:<host>` holds the issuer and the
  key; `spool_key_state:<host>` holds the last answer (`ok`, `unknown-key`, `ended`, or none), `endsAt`, the next-ask
  floor and the failure count. It sits beside the relay list and its `?k=` tokens under ADR 2026-09.66cw's
  protection. 66cw named what would change its calculus: a second credential in the DataStore that is a
  confidentiality key. A customer key is not one; like the token it is a resource credential that buys tickets and
  decrypts nothing. **The key rides a Knit backup, as the token does** (a restore is a move, ADR 2026-09.6mj7); its
  state and the ticket do not (`spool_key_state` joins `SettingsKeys.TRANSIENT_PREFIXES` beside `spool_ticket`), so
  a restored phone asks at once. Removing a relay forgets the host's key, state and ticket in one edit
  (`forgetHost`), unless another row still names the same host. The ticket's key names moved from `SettingsStore`'s
  companion to `SettingsKeys` so that edit can reach them.
- **The schedule** (`TicketRefreshPolicy`, pure). A key that just arrived is asked at once. After a verdict, a host is
  asked when its ticket is missing, not presentable, or less than 6 h from `exp`, but never before a floor: 1 h after
  a 200 (an account whose `exp` is capped at its end would otherwise ask again the moment the answer landed), 24 h
  after a 403 (`account extend` can revive it). A 401 parks the key until a different one arrives. Anything else
  keeps the last verdict and backs off 1 min, doubling, to 1 h.
- **The refresher** (`TicketRefresher`), one coroutine on the mesh session started beside `ScopeSync`. "While the
  plane runs" is `SettingsStore.activeSpoolUrls`: empty with the plane off, and a parked relay is not asked for. It
  sleeps until the earliest due ask, at most 15 min so a jumped wall clock is re-read (a `delay` does not count a
  phone's sleep, and the 6 h margin absorbs that), and wakes early on a key, ticket or relay change, or a new route
  (watched only while a relay is in use). Asks wait for `InternetGate.isOnline`. A fetched ticket goes through
  `SpoolTickets.import` for the relay's URL, so the host rule holds and `ScopeSync`'s store watch presents it on the
  live connection at once. The app has no WorkManager and this adds none.

**How the release build stays out.** Tickets buy TURN and casts, and only a build with calls has those, so the key's
effect hangs on `CALLS_WIRED` (`di/CallWiring`, false in `src/release`): the applier gets no `TicketKeyJournal` and
ignores `i` and `k` without a word, as it ignores the room with the commons dark; `MeshManager` gets no journal and no
issuer, so no refresher is built. The codec, the sheet's lines, the store and the OkHttp file ship in `main` but are
unreachable there. Removing a relay forgets a ticket in every build.

**What it costs, and what it does not cover.**

- **A key on an IPv6-literal relay is ignored**, as its ticket could never be held (xkp4: a daemon's audience is a
  host name).
- **A revoked account's last ticket runs out on its own**, up to 25 h (ukvm). The phone learns of the end only at its
  next ask.
- **Two relays on one host share one key and one ticket**, as they share an audience.
- **No user-facing text beyond the sheet** (lifted by the amendment below). "Anywhere: N days left" and the rest only
  read what the store keeps (`endsAt`, the last answer).
- **Not built:** a QR code, the storefront, the in-app scanner reading invites, a spool token in the key swap.
- **What the iOS port must copy:** `i` and `k` in that order after `n`, both or neither, the origin rule and the key
  pattern (`docs/RELAY_INVITE.md` §2) and the `ticketInvite` vector; never minting them; the sheet's line naming the
  issuer's host; the key held per relay host name, replaced by a different one, kept by a keyless invite; the key
  only in `Authorization`, only to its origin, no redirects; the schedule (at once on arrival, 6 h before `exp`, the
  1 h floor, the 24 h retry after `ended`, the 401 park, 1 min to 1 h backoff); the import through the host rule;
  and forgetting key, state and ticket with the relay.
- **The trap:** the key is a bearer credential beside a relay token that is one too, and both are easy to print.
  `HeldKey`, `RelayInviteApplier.TicketKey`, `IssuerAnswer.Issued` and `RelayInvite.Parsed.Invite` all name the
  issuer or the host in `toString` and never the key or the ticket, and the refresher logs hosts, words and times
  only. Kept true by `TicketRefresherTest.noLogLineCarriesTheKeyOrTheTicket`,
  `RelayInviteTest.theParsedInviteNeverPrintsItsCredentials`, `TicketKeyStoreTest.nothingItRendersNamesTheKey` and
  `RelayInviteApplierTest.aKeyedPreviewNeverPrintsTheKey`. `OkHttpTicketIssuerTest` copies the route's three
  answers from knit-spool-coordinator `main` @ `889bca6` and pins the request's shape. Device trial NOT run: the
  deployed issuer has no domain yet.

## Amendment (2026-10-13): the relay row shows what a relay offers calls

A tester on `lab.lax` could see a relay's calls only through `…debug.SPOOL`. Each relay row on the Internet relays
screen now carries up to three lines under its status, in a build with calls only:

- **What its hello offers** — `Calls · TURN, walkie, 1 STUN server` — from `SpoolStatus.turn`, `cast` and `stun`,
  facts about the live connection, so none while it is down. `SpoolStatus` gained `turn` and `cast` for it (the
  debug bridge's dump too).
- **Its ticket**, on a relay that takes them (`ent`): the connection's own answer (good until a time, with the
  grants its flags carry; presenting; refused with the spool's word), and, with nothing presented, why, from the
  stored verdict (refused for good, or past its `exp`). Without one: "No ticket — TURN here needs one".
- **The key** held for its host: the issuer's host and its last answer (account ends, not recognised, ended, not
  answering). Not a fact about the connection, so it shows while the relay is down.

The one seam is the `SpoolTicketJournal` the DI hands `InternetRelayViewModel`, only where `CALLS_WIRED`: without it
no row says anything about calls, so release shows nothing. The row names the issuer's host and the ticket's claims,
never the key, the ticket or a STUN URI. Pure mapping in `relayCalls` / `relayTicket` / `relayAccount`
(`InternetRelayViewModelTest`); lines in `InternetRelayScreenContentTest` and the `InternetRelayScreenCalls`
preview. Device check owed: open the screen on a phone connected to `lab.lax`.
