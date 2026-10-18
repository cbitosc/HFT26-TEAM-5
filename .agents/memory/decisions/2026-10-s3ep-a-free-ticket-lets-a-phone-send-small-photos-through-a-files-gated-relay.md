---
id: "2026-10.s3ep"
slug: a-free-ticket-lets-a-phone-send-small-photos-through-a-files-gated-relay
title: "A free ticket lets a phone send small photos through a files-gated relay"
date: 2026-10-17
topics: [calls, spool, tickets, attachments]
---

# ADR 2026-10.s3ep — A free ticket lets a phone send small photos through a files-gated relay

Status: Accepted (2026-10-17). Experimental and debug-only, like ADR 2026-10.3tee: everything here sits behind
`CALLS_WIRED` and ships when calls do. Amends ADR 2026-10.3sk7 (a free key beside the customer key; a waiting upload
is a reason to ask) and ADR 2026-10.8t2y (`size`, and the per-attachment limit in the sender's reach). The client half
of the Knit Anywhere change `free-tickets`: specified in `docs/SPOOL_PROTOCOL.md` §7.6 (`am`, S-7.6-31, C-7.6-32) and
`docs/VOICE_CALLS.md` §2.6 (C-7.6-27 and C-7.6-28 amended, C-7.6-33 to C-7.6-38). The spool's half — the claim, the
`size` gate and the version `0.4.0-dev.3` that reads it — is knit-spool ADR 2026-10.h4ps (`feat/free-tickets` @
`061bad2`, amending h6pt and bxsg); the issuer's account route, free-tier row and values are knit-spool-coordinator ADR
2026-10.3aqb (`feat/free-tickets` @ `5883035`).

**What was observed.** A files-gated relay took attachments only from a phone with a paid ticket (8t2y), so every
other phone's photos read "Nearby only" there. The brief changed on 2026-10-16: free users send small photos over the
Internet. A spool ignores unknown `fl` bits and unknown ticket keys, so a tier marker could not make an old spool
refuse anything; what an issuer can do is sign a smaller ticket. And a free phone had no issuer at all: the app learnt
an issuer only from an invite's `i`.

**What changed.**

- **The ticket's `am`** (`SpoolTicket.am`, `maxChunks`): the largest `|A|` it may `aput`, in bytes, rounded down to
  whole 48 KiB chunks. `SpoolConnection.uploadLimitChunks` reads it off the live accepted files ticket where `aput` is
  gated, and `SpoolStatus.uploadLimitChunks` carries it out.
- **Never sent over it** (C-7.6-32). `ScopeSync.passLocally` settles an attachment of ours whose `total` is over the
  limit as `LocalPass.TOO_LARGE` before the `ahave`, and `pushAttachment` checks again (a ticket accepted since). A
  `size` refusal (`ChunkPut.TOO_LARGE`) settles that one attachment and remembers its hash for the connection: no lock,
  no spent mark, TURN and casts untouched. An accepted files ticket un-settles both kinds, since its `am` may differ.
- **The sender's marker** (`RelayFacts.uploadRooms`, `UploadRoom.takes`): one room per connected relay that takes this
  phone's uploads, holding its budget and its chunk limit together, because the two must hold on the same relay; a
  single max over each would call a 6-chunk photo relayable when one relay's budget and another's missing `am` each
  admit it. `TooLarge` now covers either bound; precedence stays Unsupported, Locked, TooLarge.
- **A waiting upload asks** (C-7.6-27 amended). `ScopeSync.settleLocked` calls `onUploadWanted` for a lock of
  `missing` or `expired`, which `TicketRefresher.uploadWanted` turns into a host mark (`WANT_TTL_MS`, an hour; the
  plane re-marks every 10 min while it stays locked) and a wake. A customer key's schedule already asks for a missing
  ticket, so for it the mark is only the wake.
- **A free key asks for nothing else** (`TicketRefreshPolicy.freeDueAt`): only while an upload waits and no ticket is
  presentable — a held free ticket is used until its `exp` — never on the 6 h margin, and never inside the hour floor
  after a 200 (contract revision 1). A 200 whose ticket was never held now waits that floor too, for every key: before,
  it backed off a minute, and a free key would have looped on a ticket refused on import or at `ent`. So the issuer
  hears from a free phone only on a day it sends something through that relay, and its daily cap counts senders.
- **The free account** (C-7.6-33 to C-7.6-36). The app knows a free issuer only from configuration
  (`res/values/spools.xml`'s `free_issuers`: `lax.spool.getknit.app https://anywhere.getknit.app`; a debug build's
  bridge sets one for any host). With no key, a waiting upload, the issuer and the person's leave, the refresher `GET`s
  the stamp's parameters, mines `SHA-256("knit/issuer/v1/account" ‖ u64be(day) ‖ nonce ‖ u64be(n))` off the main
  thread (`AccountStamp`, on `PowMiner`, the allocation-free loop §8's stamps use; at most `MAX_BITS` = 26), `POST`s,
  and swaps the 201's key at once. A 400 re-mines once with what it named; a second, a 503 and an `aud` that is not the
  host back off (`FreeAccountPolicy`, C-7.6-38: 429 its `retry-after`; 503 its `retry-after` or 1 h doubling to 24 h;
  no answer 1 min doubling to 1 h, contract revision 2). A free key's swap 503 waits its `retry-after` (the cap's next
  UTC midnight, contract revision 3). The route goes through `OkHttpTicketIssuer`'s one `call` — bound, no redirect,
  capped body — with no `Authorization` header.
- **The free key's life** (C-7.6-37). `TicketKeyStore` keeps it in `spool_free_key:<host>`, the host's one slot read
  together with `spool_key:<host>`: a write to either clears the other, so an invite's customer key replaces it and a
  free account made after a customer's `ended` replaces that. Everything under `spool_free_` is backup-transient: a
  restored phone makes its own key and asks the person again. A free key answered 401 or 403 is forgotten
  (`forgetFreeKey`), and the next account waits for the next UTC day. A free key never asks for a badge
  (`BadgeRefresher.issuerKey`) and never offers the badge row, and the relay row shows it as "Free tickets from
  <issuer>", with no account end.
- **The notice and the switch** (C-7.6-34). `FreeUploads.offers` lists connected relays locked `missing`/`expired`
  behind the files gate, with a free issuer the person has not answered for and no key that could fetch one instead.
  The chat (`ChatViewModel.freeUploadOffer`, `FreeUploadsNotice`) asks once something sent there reads Locked: Allow or
  Not now are answers, a tap outside only puts it off for the visit. The relay row's `FreeUploadsSwitch` turns it on
  or off later; off forgets the free key and its ticket. Nothing reaches an issuer without a yes. Copy went through the
  product-language-editor skill: no product, price, shop, "Get" or "Upgrade".
- **Fitting a photo** (`AttachmentStore.fitted`, `FreeUploads.photoChunks`, `photoFitBytes`). A free key asks only once
  an upload waits, so a phone's first photo is staged before it has held a ticket; re-encoding later would change the
  hash its sent frame names. So a still photo is fitted at staging to the smallest chunk limit among the relays it goes
  to, connected or not (a relay that is down is judged by its last hello in this process — `SpoolStatus.seenAttachments`
  / `seenGates`: one that carried no attachments does not count, one that left files ungated takes the photo whole — and
  otherwise, or never heard from, by what this phone holds for its host, so a photo staged offline is fitted too: the
  device run's step 5, 2026-10-17; a parked relay is no relay the photo goes to), unless one would take it as it is: the
  live ticket's `am`, else the last held ticket's (even lapsed), else the seeded hint `free_issuer_am`
  (`https://anywhere.getknit.app 262144`), only where the phone holds a free key or none and a free account could be
  made (contract revision 4) — never under a customer key, which goes to the same origin. The ladder (`FIT_STEPS`) is
  quality first, then size: 1280 px q75, q65, q55, then 1024 px q60, 800 px q55, 640 px q50. Measured with libjpeg on
  five Pixel camera originals: 219–361 KB at the default 1280 px q85 (four over the 245,732 B a five-chunk `am` admits),
  162–272 KB at q75, 134–227 KB at q65. A photo no step fits keeps its best and goes nearby; GIFs, animations and videos
  are never squeezed for `am`. A customer key that replaces a free one forgets the free ticket
  (`TicketKeyStore.putKey`), so its `am` never sizes a customer's photo.

**What it costs, and what it does not cover.**

- **One copy of a free-tier value in the app**: the hint. If the issuer lowers `am` by CLI, a phone's first photo may
  be too large there; it stays `TooLarge` and goes nearby, and changing the hint takes a release.
- **The issuer can link a day's `ps` to an account** (`docs/SPOOL_PROTOCOL.md` §10.1); one operator runs both. Policy
  keeps them apart (coordinator 3aqb); blind issuance stays possible later (§11).
- **A spool restart, or an eviction from its budget table, refills a free budget**, as for any ticket (knit-spool).
- **Not built:** a per-thread choice of relay for a photo; a metric for attachments settled too large (the log line
  and `uploadLimitChunks` say it); radio-delivered DM photos staying off the spool (order `radio-delivered-photos`).
- **The trap:** renewing a free key on a schedule "to be ready". It makes every free phone count against the issuer's
  daily cap every day and turns away the people who send. Another: fitting a photo to the hint under a customer key.
  Kept true by `FreeTicketRefresherTest` (no upload, no ask; a waiting upload asks once; a refused ticket asks again no
  sooner than an hour after its 200; every answer of both routes; the forgotten key's next day; the customer fallback),
  `FreeTicketsTest` (the stamp vectors copied from knit-spool-coordinator `feat/free-tickets` @ `05b7ec4`, every answer,
  the backoff), `SpoolTicketRecordsTest` (`tkFilesAm`, `sigFilesAm`, `tkAmOnly`, `sigAmOnly`, copied from knit-spool
  `feat/free-tickets` @ `061bad2`), `OkHttpTicketIssuerTest` (the route on a socket), `ScopeSyncTest` (never `aput` over
  `am`; `size` locks nothing; the wake; the whole loop across a reconnect), `RelayReachTest`, `FreeUploadsTest` (the
  offers and the fit, revision 4's three cases), `TicketKeyStoreTest`, `AttachmentStoreIngestTest` and
  `FreeTicketLabTest`'s `anUnticketedPhoneGetsAFreeTicketAndItsSmallPhotoReachesThePeerByRelay` (a `LabFreeIssuer`
  beside a files-gated `FakeSpool`, which now refuses `size` in the spool's order).
- **What the iOS port must copy:** the C-7.6-32 check before any `ahave`, `size` as no lock; the waiting-upload ask and
  the free key's rule (no margin, the floor kept); the account exchange and its backoff; the one slot per host, the
  free key out of every backup; the notice before any request; fitting the first photo.
- **Device trial owed** (the order's part three): the P7 with no key on `lab.lax` at `0.4.0-dev.3`, the free tier on
  for the lab: notice, `asking` → `held`, a ticket with `fl` 2, `ab` 1310720 and `am` 262144, the photo across,
  a ~1 MB PDF that stays "Nearby only" with no `aput`, `budget` after about five, an offline photo that goes up after
  reconnect, and the P9's paid ticket unchanged. Mining time at 22 bits on the P7 and the P9 (`…debug.SPOOL --ei mine
  22`).
