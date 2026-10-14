# Internet voice calls

| | |
|--------------------|-------------------------------------------------------------------------------------------------------------------------------|
| Status             | **Draft sketch, not normative.** Nothing here is on the wire                                                                  |
| Decision           | ADR 2026-10.3tee (Proposed); §3.10, a call between neighbours over Wi-Fi Direct, is ADR 2026-10.kxnw; §3.11, the walkie-talkie, is ADR 2026-10.79yb; §3.12, call records, is 3tee's second amendment; §3.13, the audio route, is ADR 2026-10.wbua |
| Spool side         | §2.1–§2.6 are written to drop into `docs/SPOOL_PROTOCOL.md` as §7.6, and §2.7 as §7.7, each with §10.1 bullets, once accepted |
| Client side        | §3 is app protocol: a new `MessageContent.ctl` value, invisible to spools and relays                                          |

## 1. Overview

A 1:1 audio call has three paths:

```
           sealed CTL_CALL frames (existing DM scope, unchanged)
 caller ──────────────────── spool(s) / mesh ──────────────────── callee
   │  turnreq → turncred                         turnreq → turncred │
   │                                                                │
   └──────────── DTLS-SRTP (Opus) ── direct, or via TURN ───────────┘
```

- **Signaling** is ordinary sealed DM traffic. It needs **no spool change**: a `CTL_CALL` frame is a
  v2-sealed `chat` frame between the scope's two members, which C-4.4-5…7 already admit. It also rides
  the mesh and the §9.4 bridge like any other DM, so two phones on one LAN with no Internet can still
  signal a call.
- **Media** is WebRTC: Opus over DTLS-SRTP, peer to peer where ICE finds a path and through TURN where
  it does not. The DTLS fingerprint rides inside the sealed SDP, so Knit identity authenticates the
  media keys (§3.7).
- **TURN credentials** (§2) and **casts** (§2.7) are the only new spool-side behaviours. The spool hands out
  short-lived credentials for a TURN server its operator runs, and never carries a live call's media. It does carry a
  walkie's chunks, unstored, between two phones with no radio path, on a spool that sells that to the pair (§2.7).
- **Between neighbours** the media can skip the Internet altogether: the two phones raise a Wi-Fi Direct group and
  ICE prefers its pair to any other (§3.10). That needs no spool and no STUN, and with them it still wins.

## 2. Spool side: TURN provisioning

### 2.1 Advertisement

| ID          | Requirement                                                                                                                                                       |
|-------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **S-7.6-1** | A spool that issues TURN credentials advertises `turn: true` in HELLO. A spool that does not MUST omit the field; `false` is never sent. Presence is the signal. |
| **S-7.6-2** | A spool MUST NOT advertise `turn` unless it is configured with a TURN secret and at least one TURN URL.                                                           |
| **C-7.6-3** | A client MUST NOT send `turnreq` to a spool that omitted `turn`.                                                                                                  |

> **Why a gate, not a hint.** The §7.3 reasoning, reapplied: an unknown record is skipped (B-2-2), and a
> skipped request is never answered, so a `turnreq` to an older spool would hang until the client's
> request timeout, once per call.

### 2.2 Records

| Record     | Direction | Fields                                                            | Semantics                                                                     |
|------------|-----------|-------------------------------------------------------------------|-------------------------------------------------------------------------------|
| `turnreq`  | c→s       | `t, q: Long`                                                      | Ask for one TURN credential. Answered by `turncred`, or by `err`               |
| `turncred` | s→c       | `t, q: Long, urls: [String], user: String, pass: String, exp: Long` | The credential. `exp` is Unix **seconds**. `urls` are RFC 7065 `turn:`/`turns:` URIs |

`turncred` carries `q` and is itself terminal, like `ahas`. `not_subscribed`, `rate` and `internal`
cover every refusal, plus `ticket` on a spool that takes entitlement tickets (§2.6).

| ID           | Requirement                                                                                                                                                                                                                       |
|--------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **S-7.6-4**  | A spool MUST refuse `turnreq` with `not_subscribed` on a connection that holds no subscription. The request names **no scope**, and the spool MUST NOT ask for one. On a connection whose HELLO carried `ent`, S-7.6-16 replaces this gate. |
| **S-7.6-5**  | A spool MUST rate-limit `turnreq` per connection and per IP, refusing with `rate` + `retryMs`. Suggested: 4 per connection per 10 min, burst 2.                                                                                  |
| **S-7.6-6**  | `user = decimal(exp) ‖ ":" ‖ base32lower(random(10))`, and `pass = base64(HMAC-SHA1(turnSecret, UTF8(user)))`: the TURN REST shared-secret scheme that coturn's `use-auth-secret` verifies.                                      |
| **S-7.6-7**  | The random suffix MUST be fresh per issue. It MUST NOT be derived from a scope id, an IP, a token or any connection state. A ticketed issue (S-7.6-17) is the one exception.                                                       |
| **S-7.6-8**  | `exp = now + turnTtl`. Suggested default 5 min, at most 1 h.                                                                                                                                                                      |
| **S-7.6-9**  | A spool MUST NOT log or persist issued credentials. Metrics MAY count issues and refusals.                                                                                                                                       |
| **S-7.6-10** | The spool process MUST NOT relay media. TURN is a separate service that shares only the secret.                                                                                                                                  |
| **C-7.6-11** | A client SHOULD fetch a credential per call, or reuse a cached one only while `exp` is at least 1 h away. It MUST NOT send a credential to its peer or into any frame.                                                          |
| **C-7.6-12** | A client SHOULD request from every spool of the DM scope that advertises `turn` and is connected, and SHOULD offer relay candidates from at most two TURN servers, to bound ICE checks.                                          |
| **C-7.6-26** | A client SHOULD prune its TURN ports once one is ready: on each network, a relay that ranks below the best ready one (relay protocol first, then address family) is released. This refines C-7.6-12, which still asks up to two spools. With one spool, a pruned call holds about one allocation per network. With two, each spool whose best relay ties the best ready one keeps a relay there, so two spools that both answer over UDP each keep one, and the second stays a backup. |

> **Why no scope.** Asking for a credential "for scope S" would tell the spool which conversation is
> calling, a fact it otherwise never learns. Holding *some* subscription is the whole gate: it costs a
> squatter a PoW stamp (§8) and admits any member of any conversation the spool carries. On a private
> spool, the `k` token (S-7.1-3) already gates the connection, so TURN inherits that gate.
>
> **Why minutes, not hours.** coturn checks a REST credential's expiry only when a session first
> authenticates, which is its Allocate; refreshes reuse the cached key (4.18.0, `check_stun_auth`). So
> `exp` bounds when a credential can *start* relaying, not how long a call lasts, and a few minutes is
> enough for ICE to allocate. The flip side: an allocation refreshed past `exp` lives on, so what a
> leaked or farmed credential is worth is bounded by the TURN server's quotas (§2.4), not by `exp`.
>
> **Why HMAC-SHA1.** It is what coturn and every TURN server implementing the REST scheme verify. It is
> not a choice this protocol makes, and it carries nothing a short-lived credential needs beyond a MAC.
>
> **Why prune (C-7.6-26).** Unpruned, a phone keeps an allocation for every network, address family and TURN URL
> it tries, and keeps them all for the call (knit-spool ADR 2026-10.y2ph): two URLs on a phone with Wi-Fi and mobile
> data, each with IPv4 and IPv6, make about 8 before pruning, and the third URL S-7.6-25 drops made up to 12. Those
> count against the TURN server's per-user and total quotas (§2.4). libwebrtc's
> `PortPrunePolicy.PRUNE_BASED_ON_PRIORITY` ranks a network's relays (by interface, across every server) UDP over
> TCP over TLS to the server, then IPv6 over IPv4, and releases the lower ones. Ties are kept, which is why two spools
> answering over UDP both keep a relay; a second spool loses its relay on a network only where it ranks lower, as a
> TLS relay against the first's UDP one. A pruned port frees its allocation (a Refresh with lifetime 0) once it holds
> no candidate pair. On the caller that is at once, because pruning happens while it gathers, before the answer. The
> callee has the offer's candidates already, so a relay it prunes after its checks began lingers until the ICE agent
> drops those pairs, about a minute after the call connects.

**Test vector** (proposed §13 addition). `turnSecret = "knit-turn-test-secret"`, `exp = 1800000000`,
suffix bytes `fixture(10, 11)`:

```
user = 1800000000:bmjbsibhfy2tyq2k
pass = yV18C+LcL6biIfL7ZVOuV9xVuoU=
```

### 2.3 Daemon configuration

Environment variables, following the house rule that an invalid value refuses to start:

| Variable                 | Meaning                                                                                                  |
|--------------------------|----------------------------------------------------------------------------------------------------------|
| `SPOOL_TURN_SECRET`      | The shared secret, the same value as coturn's `static-auth-secret`. Unset ⇒ no `turn` in HELLO           |
| `SPOOL_TURN_URLS`        | Comma-separated `turn:`/`turns:` URIs. Required when the secret is set. Two (UDP and TLS) are the recommended set (S-7.6-25) |
| `SPOOL_TURN_TTL_S`       | Credential lifetime, default `300` (60–3600; S-7.6-8)                                                    |
| `SPOOL_TURN_RATE`        | Issues per connection per 10 min, default `4`                                                            |

`SpoolServer` gains one record handler. `ScopeStore` is untouched, because nothing is stored.

| ID           | Requirement                                                                                                                                                                                                                       |
|--------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **S-7.6-25** | A spool SHOULD hand out one TURN URL per transport for each TURN server, in this order: `turn:<host>:3478?transport=udp`, then `turns:<host>:<TLS port>?transport=tcp`. A plain `turn:…?transport=tcp` URL adds an allocation per network to every call, and seldom reaches a network that the other two miss. |

The TLS port is 5349 for a TURN sidecar on a spool's own box, where Caddy holds 443, and 443 for a TURN box of its
own. An operator may still list a plain TCP URL; the client takes what it is given.

### 2.4 Running the TURN server

coturn runs beside the daemon: a sidecar in `deploy/`, `network_mode: host`, because it needs the
relay port range. A starting `turnserver.conf`:

```
use-auth-secret
static-auth-secret=<SPOOL_TURN_SECRET>
realm=<spool host>
listening-port=3478
tls-listening-port=5349
alt-tls-listening-port=443
min-port=49160
max-port=49660
fingerprint
no-cli
no-multicast-peers
no-tcp-relay
user-quota=3
total-quota=16
max-bps=16000
bps-capacity=125000
denied-peer-ip=0.0.0.0-0.255.255.255
denied-peer-ip=10.0.0.0-10.255.255.255
denied-peer-ip=100.64.0.0-100.127.255.255
denied-peer-ip=127.0.0.0-127.255.255.255
denied-peer-ip=169.254.0.0-169.254.255.255
denied-peer-ip=172.16.0.0-172.31.255.255
denied-peer-ip=192.168.0.0-192.168.255.255
denied-peer-ip=::1
denied-peer-ip=fc00::-fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff
denied-peer-ip=fe80::-febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff
```

- **The `denied-peer-ip` list is not optional.** Without it, any credential holder can use the relay
  to reach the host's loopback, its private network or a cloud metadata endpoint. This is the
  standard TURN SSRF.
- **`max-bps` and `bps-capacity` are bytes per second.** One Opus call leg is about 4 kB/s. On a
  public spool anyone can get a credential, so these caps, not the credential, are what bound a
  stranger: 1 Mbps for the whole relay is about 325 GB/month if saturated around the clock.
- **Bandwidth.** A relayed call is Opus at about 32 kbps each way, about 128 kbps through the server
  counting both legs, which is roughly 58 MB per call-hour. A Nanode's 1 TB transfer allowance covers
  on the order of 10,000 relayed call-hours a month.
- **TLS on 443** gets through networks that block UDP and 3478. Media is DTLS-encrypted either way, and
  TLS additionally hides the TURN username from the network path.
- **Hosted.** A TURN sidecar is an add-on for Knit Hosted. Any metering or billing lives in the control
  plane, never in `daemon/`.

### 2.5 What the spool and the TURN server observe

Proposed §10.1 bullet:

- **The spool** sees that an IP asked for a TURN credential at a given time. It does not see which
  scope the IP is calling from.
- **The TURN server** sees both endpoint IPs of a relayed call, the call's duration and its byte counts.
  It never sees content.
- **Joined**, as one operator can join them, this sharpens §10.1's residual leak. "These k IPs touch the
  same scope" becomes "these two IPs talked for 14 minutes at 09:14". Tor cannot cover it, because media
  is UDP.
- **The "relay all calls" setting** (§3.7) moves the IP-pair exposure from the peer to the TURN
  operator. It does not remove the exposure.
- **Casts** (§2.7) show the spool, live and never on disk, that a DM scope is in a walkie session, which side talks
  when and for how long (about two casts a second per talking side), and both subscribers' IPs. Padding (C-7.7-12) and
  a constant-bitrate encoder keep a chunk's size from saying what was said; nothing hides that something was. On a
  spool that sells casts, the ticket rule (S-7.7-6) ties the payer's daily pseudonym to that timing, which the spool
  could already tie to the payer's subscriptions.

### 2.6 Entitlement tickets and STUN (experimental)

Two additions for a spool whose operator sells TURN (Knit Anywhere; knit-spool ADR 2026-10.bxsg).
Which capabilities need a ticket is the operator's choice, advertised in HELLO (knit-spool ADR 2026-10.h6pt).
A **ticket** is an Ed25519 grant an issuer signs and the spool checks with only the public half, so
the spool can tell a paying caller from anyone else without holding an account. A **STUN list** lets
any client, paying or not, learn its own public address — without which a free phone offers only
private addresses, and a paying peer's relay cannot permit it (coturn keys permissions on IP alone).

| Record | Direction | Fields                                    | Semantics                                                         |
|--------|-----------|-------------------------------------------|-------------------------------------------------------------------|
| `ent`  | c→s       | `t, q: Long, tk: bytes, sig: bytes(64)`   | Present a ticket. Answered by `ok`, or by `err ticket` with `msg` |

```
Ticket {                 // tk: the house CBOR, every field but ab required, unknown keys ignored
    v:   1
    kid: bytes(8)        // SHA-256(raw Ed25519 public key)[0..8]
    aud: String          // the one spool it is good for
    nbf: uint            // Unix seconds
    exp: uint            // Unix seconds, exp - nbf <= 48 h
    ps:  bytes(16)       // the holder's pseudonym for the day, opaque to the spool
    fl:  uint            // bit 0: may ask for TURN; bit 1: may upload attachments; bit 3: may cast, and
                         // unlocks casts for the scope (§2.7). Bit 2 is reserved.
    ab:  uint?           // upload budget in bytes for this pseudonym's day; absent means the spool's own
}
sig = Ed25519(issuer key, "knit/spool/v1/ticket" ‖ tk)
```

| ID           | Requirement                                                                                                                                                                                                                      |
|--------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **S-7.6-13** | A spool configured with a ticket key advertises `ent: true` in HELLO; one that is not MUST omit it. Beside `ent` it sends `entGates: uint`, the `fl` bits it requires a ticket for; a client that sees `ent` without `entGates` reads it as 9 (bits 0 and 3). A spool that advertises STUN puts a non-empty `stun: [String]` of `stun:`/`stuns:` URIs in HELLO, with no credential. |
| **S-7.6-14** | On `ent`, a spool MUST refuse with `err ticket` and a `msg` of `malformed`, `key`, `signature`, `audience`, `early` or `expired` unless `tk` decodes, `kid` names its key, `sig` verifies, `aud` is its own, and `nbf - 300 s <= now <= exp + 300 s`. |
| **S-7.6-15** | An accepted ticket binds to the connection until it expires, and a later accepted `ent` replaces it. A refused `ent` MUST leave an earlier ticket in place, and is never a strike.                                               |
| **S-7.6-16** | On a connection whose HELLO carried `ent` with bit 0 in `entGates`, `turnreq` needs a live ticket with bit 0 of `fl`, else `err ticket` with `msg` `missing`, `expired` or `flags`. With one, no subscription is needed. S-7.6-5's limits still apply.   |
| **S-7.6-17** | A ticketed credential's suffix is `SHA-256("knit/spool/v1/turn-user" ‖ ps)[0..10]`, so every issue under one pseudonym shares it, and the TURN server's per-user quota counts the holder, not the credential.                |
| **S-7.6-18** | A spool MUST NOT log or persist a ticket or a pseudonym. Metrics MAY count acceptances and refusals by `msg`.                                                                                                                     |
| **C-7.6-19** | A client that holds a ticket for a spool sends `ent` right after HELLO when the spool advertised `ent`, and again before the ticket expires. It MUST NOT retry a refused ticket on every round.                                 |
| **C-7.6-20** | A client SHOULD gather from every `stun` URI its connected spools advertise, on every call, whether or not it obtained a TURN credential.                                                                                       |
| **C-7.6-21** | A client that holds a ticket for a spool SHOULD ask that spool for TURN on every call, whether or not the DM scope is subscribed there; this refines C-7.6-12.                                                                   |
| **S-7.6-22** | On a connection whose HELLO carried `ent` with bit 1 in `entGates`, `aput` needs the uploading connection's own live ticket with bit 1 of `fl` and budget left, else `err ticket` with `q`, `scope` and a `msg` of `missing`, `expired`, `flags` or `budget`. Another subscriber's ticket never admits it. The budget is `min(ab, the spool's own)` bytes per pseudonym, charged only for chunks stored, at the size the spool charges them. A refusal is never a strike. |
| **S-7.6-23** | A ticket never gates `aget`: a member without one fetches every attachment a member with one stored.                                                                                                                             |
| **C-7.6-24** | A client without a files ticket for a spool whose `entGates` has bit 1 does not offer to send attachments through it, and still fetches the ones it receives. After `budget` it does not upload there again under that ticket's pseudonym (`ps`). |
| **C-7.6-27** | While the Internet plane runs, a client holding a customer key for a relay asks its issuer for a ticket when the key arrives, and whenever that relay's held ticket is missing, not presentable (`SpoolTicketPolicy.presentable`), or less than 6 h from its `exp` on the phone's clock. After a 200 it asks no sooner than 1 h later, so an account whose `exp` is capped at its end can't make it loop. With the plane off it asks nothing. |
| **C-7.6-28** | A 200 imports the ticket under the host rule; a 401 parks the key until a different one arrives; a 403 records the end and retries daily; anything else backs off from 1 min to 1 h. |
| **C-7.6-29** | A customer key goes only to the issuer origin it arrived with, only in the `Authorization` header, only over `https://` (`http://` in a debug build). It is stored beside the relay's `?k=` token, under the protection ADR 2026-09.66cw gives that token, and never minted into an invite. Removing the relay forgets its key, issuer and ticket. |
| **C-7.6-30** | A client reads each gate from its own bit of `entGates` (S-7.6-13): `turnreq` needs a ticket only under bit 0, `aput` only under bit 1, a cast only under bit 3. A gate the HELLO doesn't name needs no ticket, whatever the spool's `ent`. |

> **Why one ticket per connection.** The spool already ties a connection to every scope it
> subscribes, so a ticket on each record would buy no unlinkability and cost a signature check per
> record. The pseudonym does let the spool link one holder's connections across addresses within a
> day; blind-signed tickets are the later answer if that becomes a concern.
>
> **Why the suffix is hashed.** coturn's `user-quota` counts the part of the username after the colon
> (4.18.0, `get_real_username`), so a stable suffix per pseudonym is what makes a quota per customer
> possible. Hashing it keeps the spool-side pseudonym out of the TURN server's logs verbatim.
>
> **Why only the uploader's ticket admits an upload.** TURN and casts let one payer unlock a conversation,
> because a call or a walkie that only one side can use is broken. Attachments are not: a free member
> still receives every one. A rule under which sending works in some conversations and not others, for
> reasons the sender cannot see, would need explaining in every client (knit-spool ADR 2026-10.h6pt).
>
> **How a client keeps C-7.6-24.** It may upload on a connection whose gates lack bit 1, or that holds an
> accepted ticket with bit 1, not past `exp` on its clock, whose `ps` it has not seen spent at that relay.
> Before the `ent` answer it does not upload; with no presentable files ticket it is locked at once, so it
> sends neither `aput` nor the `ahave` that would precede one, and the sender's attachment reads "Nearby
> only". A `budget` refusal marks the (relay host, `ps`) pair spent in memory: the mark outlives a reconnect
> and a re-presented ticket with that `ps`, and a ticket with another `ps` clears it. It is never a refusal
> verdict on the stored ticket, which would stop that ticket's TURN and casts (knit-next ADR 2026-10.8t2y).
>
> **The key swap (C-7.6-27 to C-7.6-29).** A ticket is a day's grant, so something has to fetch the next one. An
> issuer's operator hands a customer a relay invite (`docs/RELAY_INVITE.md` §2) that carries, beside the relay, the
> issuer's origin `i` and a customer key `k` (`kak1_…`). Accepting it holds the key for the relay's host name, and
> the client trades it at `POST <i>/api/v1/ticket` with `Authorization: Bearer <k>` and no body (knit-spool-coordinator
> ADR 2026-10.ukvm, as amended by knit-spool-coordinator ADR 2026-10.mrnm). The answers are 200
> `{ ticket, aud, nbf, exp, flags: { turn, files, cast }, endsAt }`, 401
> `{ error: "unknown-key" }` and 403 `{ error: "ended", endsAt }`, all `no-store`. The client acts on the claims
> inside `ticket`, never the JSON copies, and puts it through the same import door as a pasted one, so a ticket
> whose `aud` is not that relay's host is never held; the connection then presents it at once (C-7.6-19). A phone
> never re-mints a key into an invite: it lends out the customer's own quota. Client: knit-next ADR 2026-10.3sk7.
>
> **Why STUN rides HELLO.** A free phone that is the *caller* gathers before any answer arrives, so a
> STUN URI carried in the paying peer's signaling would come too late. coturn answers STUN without a
> credential, so advertising it costs the operator nothing a TURN allocation would.

**Test vector.** Key pair RFC 8032 §7.1 TEST 1; `aud = "lab.example"`, `nbf = 1800000000`,
`exp = 1800086400`, `ps = 00 01 … 0f`, `fl = 1`; the `ent` with `q = 7`:

```
kid = 21fe31dfa154a261
tk  = a7617601636b69644821fe31dfa154a261636175646b6c61622e6578616d706c65636e62661a6b49d200
      636578701a6b4b238062707350000102030405060708090a0b0c0d0e0f62666c01
sig = c0740d14d5f46f2771721c593fa34a6e926b39182955cc6f8fb15365dddcf229
      da2cb33bdf6f23881cf4b153eedc4238e143fa53ef1a834961040bb55818b809
```

The same fixture with `fl = 3` (TURN and files) and `ab = 134217728`, and a HELLO with `ent` and
`entGates = 11` (TURN, files and cast):

```
tk            = a8617601636b69644821fe31dfa154a261636175646b6c61622e6578616d706c65636e62661a6b49d200
                636578701a6b4b238062707350000102030405060708090a0b0c0d0e0f62666c036261621a08000000
sig           = 1942b4b07f1ef10f7e4061e822d909876b2e5a1597469c5aa70c87c5909dbc9c
                c24dc7703dfcb8a33ea190b19e4b9a9f460319f9ec2d11d17e787afe551b5d0a
helloEntGates = a461746568656c6c6f61760163656e74f568656e7447617465730b
```

### 2.7 Casts: a walkie's chunks through a spool (experimental)

A walkie session (§3.11) streams a chunk about every half second, and over the radios those are `talk` frames nobody
custodies. Two phones with no radio path to each other have nothing to carry them. A stored blob is the wrong
carrier: each one costs a digest entry, an eviction, a tombstone, a place in every listing and a pull per member per
heal round, and two a second push a scope's real history out within minutes. A **cast** is the other carrier: one
sealed frame that the spool hands to the scope's other live subscribers and keeps nothing of. It is written to drop
into `docs/SPOOL_PROTOCOL.md` as §7.7 (knit-spool ADR 2026-10.3wdy; here, ADR 2026-10.79yb's sixth amendment).

| Record  | Direction | Fields                                   | Semantics                                                                                   |
|---------|-----------|------------------------------------------|---------------------------------------------------------------------------------------------|
| `hello` | s→c       | `…, cast: { maxBytes: Int, rate: Int }`  | Present when the spool fans out casts: `maxBytes` caps a cast's `data`, `rate` is casts per second per connection, with a burst of four times that |
| `cast`  | c→s       | `t, scope: bstr32, data: bstr`           | One sealed frame for the scope's other subscribers. No `q`, and never answered              |
| `cast`  | s→c       | `t, scope: bstr32, data: bstr`           | The same record, relayed unchanged to every other subscriber of the scope, caster excluded  |

| ID           | Requirement |
|--------------|-------------|
| **S-7.7-1**  | A spool that fans out casts advertises `cast` in HELLO; one that does not MUST omit it. Presence is the signal. |
| **S-7.7-2**  | A spool MUST NOT answer a cast, with `ok` or with `err`, whatever becomes of it. B-7.1-8 gains its one exception: a client record that carries no `q`. |
| **S-7.7-3**  | A cast is fanned out only when its connection holds a `sub` for the scope, its `data` is at most `maxBytes`, it is within the per-connection, per-IP and spool-wide budgets, the scope is not the commons, and the ticket rule (S-7.7-6) holds. Anything else is dropped, and MAY be counted. |
| **S-7.7-4**  | A spool MUST NOT store a cast or let one touch a stored scope: no blob, digest fold, tombstone, listing, `maxScopes` hold, creation gate or disk write. A cast for a scope the store has shed still fans out while subscribers hold the scope. |
| **S-7.7-5**  | Fan-out is best-effort, and a dropped copy MUST NOT count toward the slow-consumer close: a subscriber whose channel is full loses that cast and nothing else. |
| **S-7.7-6**  | On a connection whose HELLO carried `ent` with bit 3 in `entGates`, a cast fans out only while some live subscriber of its scope on this spool holds an accepted ticket with bit 3 of `fl` (`FL_CAST = 8`): the caster first, then the others. Liveness and skew are S-7.6-14's. A spool without a ticket key admits any subscriber's cast. |
| **S-7.7-7**  | A dropped cast MUST NOT be a strike. A flood still pays the per-record budget that every record pays, and that budget strikes. |
| **S-7.7-8**  | A spool MUST NOT log or persist a cast, the scope it names, a ticket or a pseudonym. Metrics MAY count casts, copies fanned out and drops by reason. |
| **C-7.7-9**  | A client MUST NOT cast to a spool whose HELLO omitted `cast`, and MUST keep within its `maxBytes` and `rate` unprompted, because a refusal is silent. On a spool whose `entGates` has bit 3 it casts only while it holds a cast ticket there, or its peer said it does (`CallPayload.ct`, §3.2). |
| **C-7.7-10** | A client casts only into a scope whose SUB that spool has answered. |
| **C-7.7-11** | Every cast is signed: its `sig` is the Ed25519 frame signature over `signed`, whatever the same frame's radio copy carries. |
| **C-7.7-12** | The seal is the one below: its own version byte, nonce label and aad, the plaintext padded to a multiple of 128 bytes. An opener refuses any plaintext but the canonical one: whole blocks, a nonzero length inside them, at most one block of padding, all of it zero. |
| **C-7.7-13** | The cast set: on a pair's own DM scope (never a pair scope, §3.5, a group or the commons), `type = talk`, no group, sender and recipient the scope's two members in the direction of travel; then the signature verifies against the pinned sender key (C-4.4-1's step). Nothing else is sealed into a cast or accepted out of one. |
| **C-7.7-14** | A received cast re-enters delivery as a frame for this phone alone (`ttl = 0`, `relay = false`): never through §9.4, and never custodied, accounted (§9.6), quarantined (§9.3) or taken as presence. A cast that fails is dropped and forgotten. |
| **C-7.7-15** | One frame may arrive over the radios and from several spools at once. The receiver keeps the first copy by frame id. |

```
pt    = sig(64) ‖ u16be(|signed|) ‖ signed ‖ 0x00…            // padded to a multiple of 128 bytes
nonce = HKDF(ikm = nonceKey, info = "knit/scope/v1/cnonce" ‖ SHA-256(pt), L = 12)
aad   = "knit/scope/v1/cast" ‖ scopeId                          // 50 bytes: a frame's is 45, a chunk's 52
data  = 0x04 ‖ nonce(12) ‖ AES-256-GCM(key = sealKey, nonce, pt, aad)
```

A 480 ms walkie chunk's frame is about 693 bytes, so its plaintext pads to 768 and its cast is 797 bytes: about
1.7 KB/s at two a second.

> **Why no answer.** The shipped client reads a scoped `err` with no `q` as a refused `sub`, and would drop the
> conversation. A `q` would put an ack, a 30 s pending slot and the silent-request strike (C-9.1-4) on every chunk. A
> chunk is live or lost, and HELLO already states every bound a refusal could have named.
>
> **Why a seal of its own.** With `0x04` and its own aad, a cast never opens as a frame blob (§4.3) or a chunk (§4.5),
> and neither of those opens as a cast. So nothing can slip a custodial frame into the one lane that skips custody and
> the digest, or a cast into a scope's digest. It is deterministic for §4.3's reason, though nothing dedups a cast: the
> same frame cast to two spools is the same bytes.
>
> **Why signed when the radio copy is not.** §4.4's invariant holds on this plane too: every frame the Internet carries
> is checked against the pinned key before any ratchet work. The 64 bytes land only on the spool's copy, which has no
> Coded PHY budget to keep.
>
> **Why one payer unlocks the scope.** As one payer is enough for TURN (§2.6). A ticket on every caster would make a
> free friend's walkie one-way. It ties the payer's pseudonym to nothing the spool had not already tied it to.
>
> **Why `push` with a marker was rejected.** An older spool ignores an unknown key (B-2-2) and stores the chunk. The
> HELLO gate is what makes an unstored lane safe to add.

Proposed §12.2 rows: casts off by default (`rate` 0, omitted); a suggested `rate` of 4 a second, burst 4×; `maxBytes`
2048; a spool-wide budget of 200 casts a second, burst 4×.

**Test vectors.** One record for both directions, with `scope = 00 01 … 1f` and `data = a0 a1 … af`; a HELLO with `ent`
and `cast = {2048, 4}`; and §2.6's ticket fixture with `fl = 9` (TURN and cast):

```
cast      = a3617464636173746573636f70655820000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f
            646461746150a0a1a2a3a4a5a6a7a8a9aaabacadaeaf
helloCast = a461746568656c6c6f61760163656e74f56463617374a2686d61784279746573190800647261746504
tk        = a7617601636b69644821fe31dfa154a261636175646b6c61622e6578616d706c65636e62661a6b49d200
            636578701a6b4b238062707350000102030405060708090a0b0c0d0e0f62666c09
sig       = 9f0d82c5e15ddc486fc693a848dd2e937df32e505f22631dde6a49f82435d147
            b64da30b5ac432ef18ee9e2349c4c8915cc8e9d5a3e829f95c2784bc5a5f530f
```

The seal on §13's DM fixture (`dmSealKey`, `dmNonceKey`, `dmScopeId`), with `sig` and `signed` the `sealBlob` inputs
(64 and 40 bytes):

```
castSeal  = 0486dfeaff6b30c3cebc549d35183a43023f9b0c354d6f49d5cc1bac81def2774409f7f4ef746f0f4491271f09f7d2635d25
            34931edda7a37adf7b73bd7c0e4a7a08e381a0861afaa8d7bd57436bf0bf26a80fd14a99a9aeadc356cc58a785ee926ca293
            e1632be7c7da48dbbb51a7949e6743e392774d9f469d69cdd86b02f9c71f91415ca1b114fe8db955ad94fa4679cffb59ade4
            6563a3650fd4b3
```

## 3. Signaling (client)

### 3.1 Carriage

```
CTL_CALL = 10        MessageContent.cl: CallPayload?
CAP_CALL = 0x4000    profile capability bit
```

| ID          | Requirement                                                                                                                                                       |
|-------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-3.1-1** | Call signaling is a `MessageContent.ctl` value carried in an ordinary v2-sealed DM. It is never persisted as a message, never notified as one and never receipted. |
| **C-3.1-2** | A client MUST send an `OFFER` only toward a pinned profile carrying `CAP_CALL`, because a build without it consumes the ctl as a silent no-op.                   |
| **C-3.1-3** | Signaling rides every path a DM rides: the mesh, and every spool of the DM scope. A client MUST NOT special-case its routing.                                      |

> **Why not a dedicated call scope with a short `ttlMs`.** It would need a new scope kind in §3 and a
> new derivation, would not ride the mesh, and would buy only the early expiry that §3.4's `sentAt`
> window already gives. The cost of reuse is a few signaling blobs sitting in the DM scope for its 48 h
> retention. They are opaque, and §3.6 bounds them to a handful per call.

### 3.2 Payload

```
CallPayload {
    id:   String     // 128-bit random, base32; one per call attempt, both directions
    ph:   Int        // phase, below
    gen:  Int = 0    // negotiation generation; > 0 is an ICE restart (§3.8)
    sdp:  String?    // OFFER, ANSWER
    cand: [String]?  // CANDS: "candidate:…" lines; an empty list = end of candidates
    rsn:  Int?       // DECLINE, CANCEL, END
    m:    Int = 1    // media bitmask: 1 audio; 2 video reserved; 4 walkie-talkie (§3.11)
    dw:   DirectInvite?  // OFFER: the Wi-Fi Direct group the caller will host (§3.10)
    dx:   Int?       // RINGING, ANSWER: 1 = the callee is joining that group (§3.10)
    ct:   Boolean = false  // OFFER, ANSWER beside the walkie bit: the sender holds a cast ticket (§2.7)
    tk:   TalkChunk? // TALK: one chunk of a walkie burst, {b, q, a, e} (§3.11)
}
```

| `ph` | Name      | Sender | Meaning                                                                    |
|------|-----------|--------|----------------------------------------------------------------------------|
| 1    | `OFFER`   | caller | Start a call (`gen = 0`) or restart ICE (`gen > 0`, §3.8)                  |
| 2    | `RINGING` | callee | The offer reached a device that is alerting. The caller UI moves from "Calling" to "Ringing" |
| 3    | `ANSWER`  | callee | Accept, carrying the answer SDP for this `gen`                             |
| 4    | `DECLINE` | callee | Refuse, with `rsn`                                                         |
| 5    | `CANCEL`  | caller | Withdraw before an answer, with `rsn`                                      |
| 6    | `END`     | either | Hang up after connecting, with `rsn`                                       |
| 7    | `CANDS`   | either | Late candidates for this `gen` (§3.6)                                      |
| 8    | `TALK`    | either | A chunk of a walkie burst, in a `talk` frame (§3.11)                       |

Reasons, in one registry as `TransferPayload` has: `1` user · `2` busy · `3` timeout · `4` no route
(ICE failed or no network) · `5` media unsupported · `6` error.

### 3.3 State machine

```
caller: Idle ─OFFER→ Calling ─RINGING→ Ringing ─ANSWER→ Connecting ─ICE ok→ Connected ─END→ Ended
                        └──────────────┴── 45 s / user ─CANCEL→ Ended
callee: Idle ─OFFER (fresh)→ Alerting ─user─ANSWER→ Connecting ─ICE ok→ Connected ─END→ Ended
                                 └──user / busy─DECLINE→ Ended
both:   Connected ─path lost→ Reconnecting ─ICE ok→ Connected
                                   └── 20 s ─END no_route→ Ended   (§3.8)
```

| ID          | Requirement                                                                                                                                                            |
|-------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-3.3-1** | A caller MUST send `CANCEL rsn=timeout` after 45 s without an `ANSWER`.                                                                                                |
| **C-3.3-2** | A callee already in a call answers a new `OFFER` with `DECLINE rsn=busy` and does not alert.                                                                           |
| **C-3.3-3** | Connecting that does not reach ICE `connected` within 20 s ends with `END rsn=no_route`.                                                                               |
| **C-3.3-4** | Every phase is idempotent on `(id, ph, gen)`. Custody and the spool can deliver a frame twice, and the second copy MUST change nothing.                                |

### 3.4 Staleness and ordering

Signaling rides store-and-forward paths, so a frame can arrive late, twice or out of order.

| ID          | Requirement                                                                                                                                                                                         |
|-------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-3.4-1** | A callee MUST NOT alert for an `OFFER` whose envelope `sentAt` lies outside `[now − 60 s, now + 60 s]`.                                                                                             |
| **C-3.4-2** | A stale `OFFER` for which no `CANCEL rsn=user` or `ANSWER` has been seen is recorded as a **missed call**. A `CANCEL rsn=user` that arrives first suppresses it.                                      |
| **C-3.4-3** | A terminal phase (`DECLINE`, `CANCEL`, `END`) for an unknown `id` MUST be remembered as a tombstone, at least for the custody TTL and in a bounded set, so a later-arriving `OFFER` for that `id` never alerts. |

> **Why `sentAt` and not a payload expiry.** `sentAt` is signed inside the `RelayEnvelope` and is what
> `CTL_TRANSFER`'s `STALE_OFFER_MS` already reads. A payload `exp` would be the same sender clock with
> a second field to keep consistent.
>
> **Missed calls fall out for free.** A callee that was offline pulls the stale offer on reconnect,
> and C-3.4-2 turns it into the missed-call row every phone app shows.

### 3.5 Glare

Each side sends an `OFFER` to the other before seeing the other's offer.

| ID          | Requirement                                                                                                                                                       |
|-------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-3.5-1** | The offer from the **lower node id** survives. The higher side MUST drop its own attempt without sending `CANCEL`, and MUST answer the surviving offer at once without alerting. |

> **Why auto-answer.** Both users just asked to talk to each other. A `CANCEL` would race the surviving
> offer and could suppress it on a peer that sees them out of order (C-3.4-3).

### 3.6 Candidates

| ID          | Requirement                                                                                                                                                                         |
|-------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-3.6-1** | A client MUST NOT trickle candidates one frame each. It gathers for up to 1.5 s, or until gathering completes, and embeds the candidates in the SDP.                                 |
| **C-3.6-2** | At most one `CANDS` frame per side per `gen` carries the late ones, usually a slow relay allocation.                                                                                |

> **Why.** Every signaling frame is a ratchet step, a spool blob kept 48 h and, on the mesh, a flood.
> Per-candidate trickle is 10–20 frames per call. Batched, a whole call is about five: `OFFER`,
> `RINGING`, `ANSWER`, at most one `CANDS`, then `END`. An SDP is 2–5 KB, well inside `maxBlob` (64 KiB)
> and the link layer's 512 KiB.

### 3.7 Media and identity binding

| ID          | Requirement                                                                                                                                                                                              |
|-------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-3.7-1** | Media MUST be DTLS-SRTP. SDES and plain RTP MUST be refused.                                                                                                                                             |
| **C-3.7-2** | The remote DTLS certificate MUST match the `a=fingerprint` in the SDP that arrived in that peer's sealed `CallPayload`, and SDP from any other source MUST NOT be applied.                              |
| **C-3.7-3** | With "relay all calls" on, a client sets `iceTransportPolicy = relay` **and** MUST strip its own host and server-reflexive candidates from the SDP it sends. Otherwise its own IP still reaches the peer. |
| **C-3.7-4** | Opus is mandatory; `bundle` and `rtcp-mux` are required.                                                                                                                                                 |

> **Why C-3.7-2 is the whole E2E argument.** The SDP is authenticated by the frame signature and the DM
> ratchet, so its fingerprint is the peer's own. DTLS then proves that whoever terminates the media
> holds the matching key. A TURN server, a spool or a network attacker can drop or delay a call, but
> cannot read or join it. Fresh ECDHE per call gives forward secrecy independently of the ratchet.

### 3.8 Network changes: reconnecting by ICE restart

A move from Wi-Fi to cellular, a lost Wi-Fi Direct group or a dead TURN allocation kills the selected candidate pair.
The call does not end there. It is **Reconnecting** for a window, and the caller restarts ICE under the same call id
with `gen + 1` and fresh ICE credentials. The DTLS session and the call `id` survive. ADR 2026-10.3tee's third
amendment records why this is the one recovery mechanism, with no redial under a new id.

| ID          | Requirement                                                                                                                                                                                                          |
|-------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-3.8-1** | A connected call whose media reports the path lost (ICE `disconnected` or `failed`) is Reconnecting. ICE reaching `connected` again makes it Connected, and its connect time is unchanged.                           |
| **C-3.8-2** | Only the **caller** restarts. It sends an `OFFER` with `gen` one above its last, carrying an ICE restart's SDP gathered as C-3.6-1 says, from ICE servers fetched again. The callee never sends a restart, so two never cross. |
| **C-3.8-3** | The caller restarts 2 s after the loss, unless ICE healed meanwhile, and once more 8 s later while still Reconnecting. It sends no more than two per loss.                                                           |
| **C-3.8-4** | A callee answers a restart `OFFER` only for the call it is in, from that call's caller, while connected or reconnecting, with a `gen` above the last it answered and a signed `sentAt` within 20 s of now. It never alerts for a restart and never declines one; it drops any other. |
| **C-3.8-5** | The caller applies only the `ANSWER` for its latest `gen`. It applies that one even if ICE healed while the answer was in flight.                                                                                   |
| **C-3.8-6** | A call still Reconnecting 20 s after the loss ends with `END rsn=no_route`, and ICE `failed` meanwhile ends nothing. A callee's window starts again with each restart it answers while reconnecting. A callee still connected answers without entering Reconnecting. |
| **C-3.8-7** | No `CANDS` follows a restart. Its description carries what gathering found, and the second restart is the second chance.                                                                                            |

| Timer | Value | Why |
|---|---|---|
| first restart | 2 s | a blip ICE heals on its own costs no frame |
| second restart | 8 s after the first | a network still coming up at the first has settled |
| reconnect window | 20 s | long enough for both restarts to connect, short enough that a dead call is not held open |

> **Why the caller alone.** Either side could notice a loss first, and two restarters would need a glare rule over
> store-and-forward signaling. With one, a callee-side change costs only the seconds the caller's ICE takes to see it.
> A callee that was offline when the restart went out pulls it from the spool once its network returns, inside the
> window.
>
> **Why no redial.** A dead peer, a session reset mid-call (ctl frames are never re-sealed, ADR 2026-10.gcrd) and a
> dead network all end the same way: the window runs out. A redial under a new id would ring again, glare with the
> user's own redial and leave two records for one conversation. A call that ends this way is recorded as dropped
> (§3.12), and its call screen offers Call back.

An older build ignores a `gen > 0` `OFFER`, so its restart goes unanswered and the call ends at the window.

### 3.9 Ringing

Android rings only while the callee holds a live spool connection. `event` fan-out then delivers the
`OFFER` in well under a second. Otherwise the offer waits and becomes a missed call (C-3.4-2). iOS
background ringing is out of scope until ADR 2026-10.3tee's push question is settled.

### 3.10 A call between neighbours: the Wi-Fi Direct leg

When caller and callee are mesh neighbours, the call's media rides a Wi-Fi Direct group the two phones raise —
no spool, no STUN — and with an Internet path as well, the group wins and the Internet path is its fallback. The
group is the direct transfer's (ADR 2026-09.wtmz): raised by credentials alone, no discovery, no dialog, with Wi-Fi
Aware paused while it is up and Bluetooth carrying the mesh. ADR 2026-10.kxnw records why.

```
DirectInvite {           // CallPayload.dw — every field optional, so a broken invite costs only itself
    s: String?           // group name, DIRECT-kn-xxxxxxxx
    p: String?           // passphrase
    o: Int?              // the lane's TCP port on the group
    k: String?           // the lane's 32-byte secret, base64
}
```

- **C-3.10-1** The caller puts `dw` in its OFFER only when the callee is a mesh neighbour whose pinned profile
  carries `CAP_DIRECT_TRANSFER`, the radio is free and usable, and neither relay-only nor direct mode `off` is set.
  Minting raises nothing.
- **C-3.10-2** A callee that can join answers `dx = 1` on its RINGING and joins while it rings, after a grace that
  lets the RINGING leave before the radio does. It joins with an **IPv4 lease**: libwebrtc never gathers on an IPv6
  link-local. A callee that may not join off screen (API 29-32) or runs direct mode `answer` joins when it answers,
  and says `dx = 1` on its ANSWER. A relay-only callee never joins: its checks would show its real address.
- **C-3.10-3** The caller hosts on the first `dx = 1` whose signed `sentAt` is under 8 s old, on RINGING or ANSWER,
  once per call. A peer that never says it — an older build, a callee that could not join, a glare loser — makes the
  caller raise nothing.
- **C-3.10-4** Once joined, the callee dials the **lane**: TCP to the group owner on port `o`, admitted by the
  transfer's client proof under `HKDF(k, "knit/call/v1/lane-proof")`; the host then writes a 16-byte salt and both
  ends key AES-256-GCM per direction from `k` and the salt (`…/lane/h2j`, `…/lane/j2h`), frame index as nonce, call
  id as AAD. Frames: a `CallPayload`, the sender's group candidates, a ping each second, a bye. A lane silent for
  5 s is gone. One lane per call.
- **C-3.10-5** Every signal of the call rides the lane as well as the mesh, queued from the leg's start (the OFFER
  excepted, which precedes any lane), so what the mesh carried while the radio changed hands reaches the peer again.
  The copies collapse on `(id, ph, gen)`.
- **C-3.10-6** A candidate on the group gathered after the description is lane-only, never a CANDS: only a peer
  inside the group can reach it, and C-3.6-2's one CANDS stands. A remote group candidate that beats the peer's SDP
  waits for it like a CANDS does.
- **C-3.10-7** The group is reported to libwebrtc as an Ethernet network (cost 0, against Wi-Fi's 10), so its pair
  outranks a LAN, IPv6-global, reflexive or relayed one on cost before priority; those stay up as backups, pinged
  every 5 s.
- **C-3.10-8** While a leg is still coming up, an ICE `Failed` does not end the call (with no other route ICE fails
  before the group exists) and C-3.3-3's connect window is 30 s, not 20.
- **C-3.10-9** Whatever ends the call ends the leg once: the terminal frame down the lane first (≤ 300 ms), then the
  lane closes and the radio goes back to the mesh. A lost group is not raised again; the call rides on whatever
  other pair ICE holds, or reconnects by restart on the networks that remain (§3.8).

| Timer | Value | Why |
|---|---|---|
| grace + join delay | 0.5 s + 1 s | the RINGING leaves before the radio; the caller's group gets a head start |
| join window / attempt | 20 s / 10 s | a join is ~4 s of `connect()`, flat; one retry fits |
| host window | 15 s | the host gives the radio back if no lane arrives |
| commit age | 8 s | a custody-delayed "joining" is not acted on |
| lane ping / silence | 1 s / 5 s | a lost group frees the radio fast |
| connect (direct) | 30 s | C-3.10-8 |

The radio is shared with the direct transfer through one lease (`transfer/DirectWifiLease`): whoever holds it
first keeps it, the other is refused `Busy`, and neither can release the other's group.

### 3.11 A call with no live path: the walkie-talkie

When no live path exists, the call screen becomes a walkie-talkie over the mesh. The user holds a button to talk, and
the burst streams to the peer in small chunks while it is spoken. The peer's phone plays them as they land. Nothing is
stored: a chunk that is lost is skipped, and no burst appears in the thread. It is not a live call: playback trails
the speaker by a chunk and the mesh's latency, about a second between neighbours. ADR 2026-10.79yb records why. It is
an experiment behind `BuildConfig.WALKIE` (`-Pwalkie=`, on in debug and off in release), read once as
`di/CallWiring.WALKIE_WIRED`.

- **C-3.11-1** A build that runs the walkie advertises `CAP_WALKIE` (`0x8000`). It is never in
  `LOCAL_CAPABILITIES`. A caller offers a walkie session, or the drop to one, only toward a pinned profile that
  carries it.
- **C-3.11-2** A walkie session's OFFER has `m = 4` and no `sdp`, `cand` or `dw`. It rings, goes stale, gets busy
  and glares exactly as §3.3–3.5 say. Answering connects both sides at once, with `m = 4` on the ANSWER: there are
  no ICE servers, no connect timer and no grace. A build without the walkie declines it with `rsn = 5`, because the
  audio bit is absent.
- **C-3.11-3** A call placed without a kind (the profile's Call button) is a walkie session only when the peer carries
  `CAP_WALKIE` and no live path looks possible. "Possible" means a validated Internet route here, or a Wi-Fi Direct
  leg (§3.10) to a neighbour. Anything else is a live call.

  The chat offers both kinds by the same rule: the call it prefers in its top bar, the other in its overflow.
  A mesh call placed on purpose is a walkie session whatever the route.
- **C-3.11-4** A live OFFER carries `m = 1 | 4` when the caller can drop to a walkie. The callee echoes `4` on its
  ANSWER when it can too. With both bits, each of C-3.3-3's `no_route` endings drops the call to a connected walkie
  session instead:
  - the connect window,
  - ICE failed,
  - a lost path past its reconnect window (§3.8).

  The drop closes the media and ends any direct leg, and sends nothing. The other side's ICE sees the media go and
  drops on its own timer. Without both bits, the call ends as before.

  **Parked (2026-10-09).** The Android build no longer sets the bit on either message, and it ignores the bit when
  a peer sends it (`CallManager.dropToWalkie`, off), so a lost live call ends `no_route`. In a P9↔P7 call over the
  Internet the drop left the two phones in a walkie session neither could send on, because the peer was off every
  radio. A walkie session placed on purpose is unchanged.
- **C-3.11-5** A burst is Opus at 8 kbps, captured at 16 kHz mono, in 20 ms packets, up to 60 s. Before the encoder
  the capture passes a gain stage (`call/TalkGain`), because the phone's flat microphone path has no gain control and
  a live call's audio has WebRTC's. Speech is brought toward −18 dBFS RMS, gaining at most 24 dB and never losing.
  The gain falls at once and rises at most 5 dB per second of speech. It holds between words, where a frame is within
  12 dB of the quietest frame of the last 2 s. No peak passes −1 dBFS. Each microphone keeps its own gain across
  bursts. It is cut into chunks: one each time 480 ms of audio is in hand, then the rest marked as the last. A chunk's plaintext is binary:

  ```text
  flags (bit 0: last) · call tag (2 bytes, big-endian) · varint b · varint q · packets
  ```

  - `b` is the sender's burst number in the call, and `q` the chunk number from 0.
  - Each packet sits behind a one-byte length.
  - The tag is the low 16 bits of the call id's Java `String.hashCode`, so a chunk names its session.

  A burst released before its first chunk went out sends nothing. A cancelled one that had sent sends an empty
  last chunk.
- **C-3.11-6** A chunk rides a frame of type `talk`, sealed on the DM ratchet and sent unsigned: its AEAD
  authenticates it to its addressee, as ADR 059's tick is authenticated, and only the addressee reads it.
  - **Payload.** `0x01 · varint se · varint pe · varint n · ek (32 bytes) · ciphertext`: the v3 seal with its nonce
    derived. A header that form cannot carry (an init, a reset flag), or a peer that reads only v2, gets a
    `ChatContent` instead, whose CBOR never begins with `0x01`.
  - **Id.** Its frame id is 11 characters.
  - **Reach.** No node custodies it, parks it, re-serves it or fans it onto the coordination plane or LoRa, and no
    spool stores it. A neighbour of the sender gets it alone, with `ttl = 0`. A peer heard through the mesh gets a
    flood of at most `ttl = 3` relays. A peer the mesh has no path to gets it **cast** through every connected spool
    that would carry it (§2.7), signed, and beside the blind flood when this phone has links at all. A neighbour whose
    live link is near its floor (`MeshTransport.liveLinkFading`: its link RSSI at or under −95, or three reads in a row
    unanswered) gets the cast beside its radio copy, because a dying link counts as a path until its supervision timeout
    closes it, about 20 s later. A build that does not know the type relays it like any unknown type and delivers
    nothing.
  - **Receipt.** The addressee admits the frame unsigned only in this shape: addressed to it, by someone else, in no
    group, and admits the signed copy a spool casts through the ordinary signed door. It hands the opened plaintext to
    the call layer only from an accepted contact, and a failed open is never a reset trigger, signed or not. Only a connected walkie session with the sender, under the chunk's tag, takes the chunk.
  - **Size.** A 480 ms chunk's frame is about 714 bytes, about 1.5 KB/s, inside Coded PHY's 2.1 KB/s.
- **C-3.11-7** The receiver plays bursts one after another, in the order of `b`, and each in the order of `q`:
  - It starts a burst once it holds two chunks, or the last one, or 400 ms after the first chunk arrived.
  - When chunks after a missing one are held, it waits 500 ms for the missing one, then skips it.
  - A burst ends on its `e` chunk, or after 4 s with nothing held and nothing new.
  - A chunk of a burst that ended, or one behind the play position, is dropped.

  Talking holds playback: what arrives meanwhile plays after the release. Bursts play on the call's audio route, which
  starts on the loudspeaker (§3.13). The session hangs up after 10 min with no burst either way.
- **C-3.11-8** From the ring to the hang-up, a walkie call holds a Wi-Fi Aware link to its peer
  (`MeshTransport.holdLink`), renewing the hold every 10 s; a hold lapses 25 s after its last renewal.
  - **No link yet.** The hold raises one through the hint an attachment fetch gives
    (`MeshTransport.expectBulkTransfer`), within its bounds: a fresh sighting, the initiator hold, the cooldown after a
    failed initiate.
  - **A link.** The hold keeps it open past its idle teardown (5 s for the initiator, 8 s for the responder) but not
    past its absolute window (30 s, 45 s). The window is the only way a responder can tell a dead initiator from a
    quiet one, so a held link comes round every 30 s or so, and the hold raises it again at once.
  - **No link at a send.** A chunk retries for up to 2.5 s, past the recycle's gap of about 1.9 s. A chunk to a neighbour goes over its
  quickest link (`MeshTransport.sendLive`): Wi-Fi Aware when that radio holds one, else Bluetooth. Every other frame
  keeps the Bluetooth-first order.

  The call screen shows how a chunk would reach the peer (`TalkLink`):
  - **direct:** over a link of this phone's own; nothing is shown. Over a fading link with a relay that would cast,
    the chunks also go through the relay; nothing more is shown, and the diagnostics path reads
    `Bluetooth · fading, relay too`;
  - **through the mesh:** no link of its own, but the peer's frames reached it through another phone lately; a notice
    says the chunks go through other phones;
  - **through the relay:** no mesh path, but a connected relay would cast the chunks (§2.7); a notice says so;
  - **none:** no link it can see; a warning says it is linking.

  With neither a link nor a relay that would cast, nothing is sealed, and the burst says it did not go.

### 3.12 Call records in the thread

Every call leaves a line in its DM thread: "Outgoing call · 3:42 · 2:02 PM", "Missed call · 9:15 AM", "They were in
another call". The line is a record this phone writes for itself, so it costs no wire field and no schema change. It is
a `messages` row of `kind = 10` (`MessageEntity.KIND_CALL`) whose body is a small JSON `CallRecord`, the convention a
direct transfer's card already uses. ADR 2026-10.3tee's second amendment records the choice.

| ID           | Requirement                                                                                                                                                                                                                                                      |
|--------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-3.12-1** | A record is written once, when the call ends on this phone (`CallManager.logged`), and never sent. Each side keeps its own record of the same call under the same id (`call:<id>`), so a second write for one call replaces the row.                           |
| **C-3.12-2** | A record holds facts, not a sentence: who placed the call, its reason, whether the peer's terminal phase ended it, the manager's missed verdict (C-3.4-2), and when it connected and ended. The line's wording (`CallRecord.outcome`) is derived when it is drawn: a call that connected and then ended `no_route` reads "Call dropped" (§3.8). |
| **C-3.12-3** | An offer that never rang (C-3.4-1) and was not withdrawn by hand is recorded as missed under its own call id, at its `sentAt` or now, whichever is earlier. A busy DECLINE (C-3.3-2) and a glare loser's dropped offer (C-3.5-1) leave no record.              |
| **C-3.12-4** | The signaling hook never writes the record. The manager hands it over on a channel and `call/CallLog` saves it on the app scope, so a closed or refusing database (ADR 2026-10.47rw) costs that one line and never delays `onSignal`.                          |
| **C-3.12-5** | A record never carries an SDP, a candidate, a Wi-Fi Direct credential or a TURN credential.                                                                                                                                                                       |

A record is a status notice in every list that filters on `kind`: it gets no delivery tick and no unread count, and
search never finds it. The chat list is the exception, as it is for a transfer. A record speaks for its thread there, as
the same sentence without the time, and with no "You: " in front of it, because the sentence already says who did
what. A missed call is notified on its own (`debug/call/CallController`), so the badge does not count it twice.

### 3.13 The audio route

Every call, live or walkie, is registered with the phone as a self-managed Telecom call (Jetpack core-telecom, debug
builds only). Telecom owns where its audio goes: the earpiece, the loudspeaker, or a wired or Bluetooth headset, both
ways. A headset's button, a watch or a car can answer the call and hang it up. ADR 2026-10.wbua records why.

`call/SystemCallSync` keeps the system's call in step with `CallManager`'s. It follows the manager's state on the app
scope, never on the inbound path. `debug/call/TelecomCalls` is the one importer of the library.

| ID           | Requirement                                                                                                                                                                                                                                                                       |
|--------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **C-3.13-1** | An outgoing call is registered as it dials and set active when it connects. An incoming one is registered as it alerts and answered when this phone answers it. If the system's own answer took it, the system is not answered a second time.                                    |
| **C-3.13-2** | A call that ends is disconnected from the system with what ended it: missed, busy, an error (a lost path, the media, a failure), the peer's own ending (remote), an incoming call never answered (rejected, or missed when it rang out), else local. A call the system ended is not told back. |
| **C-3.13-3** | Glare's surviving call (§3.5) keeps the system call of the call it replaced: same peer, new id.                                                                                                                                                                                  |
| **C-3.13-4** | The system is offered no hold, so a phone call answered over ours ends it. A call the system refuses is declined `busy` while it alerts, else hung up. So is every call on a phone that would not register this app.                                                              |
| **C-3.13-5** | A live call starts where Telecom puts it: a connected headset, else the earpiece. A walkie session starts on the loudspeaker unless a headset is connected, which takes it as it would a call.                                                                                   |
| **C-3.13-6** | When a walkie session's route falls to the earpiece without the user picking it (a headset unplugged, a start not honoured), the session asks for the loudspeaker, once per fall. A live call that drops to a walkie is moved off the earpiece the same way.                        |
| **C-3.13-7** | Nothing but Telecom sets the route while a call is up: no `setCommunicationDevice`, no `startBluetoothSco`, no preferred device on a track. Both kinds play on the voice-communication usage. A walkie burst is recorded from `VOICE_COMMUNICATION` while a headset has the call, else from the phone's microphone (`VOICE_RECOGNITION`, then `MIC`). |
| **C-3.13-8** | The call screen's route control is the loudspeaker toggle while the earpiece and the loudspeaker are the only routes. With a third route it names the current one and lists them all. The notification's one route action goes onto the loudspeaker, or off it to a Bluetooth headset, then a wired one, then the earpiece. It carries that route's key, so a stale tap never moves the call anywhere unseen. |
| **C-3.13-9** | A mute set from a car or a watch reaches the call. The first value the system reports is only where it starts, and is not acted on.                                                                                                                                             |
| **C-3.13-10** | A call the system answered starts the call's microphone service from inside the system's answer, because the call screen may never open. Without the service, a backgrounded call's microphone is silenced. Android allowed that background start on the trial phone (a Pixel 9, API 37). |

The system shows the peer's label as the call's name, and `knit:<node id>` as its address. VoIP calls stay out of the
system call log. A call's line in its thread is §3.12's.

**Known limit: a Bluetooth headset and Coded PHY.** While a headset holds a call link, its eSCO slots share the
controller with the mesh's Bluetooth links. Over 1M PHY a walkie loses nothing to it. Over Coded PHY, whose packets
are long, the phone holding the eSCO link received 2 of each 9 chunks in the 2026-10-12 trial, against 9 of 9 with the
link down; its own sends were all delivered. Nothing steers a link off Coded while eSCO is up yet.

## 4. Tests this would add

- **knit-spool:**
  - `SpoolRecordsTest` vectors for `turnreq`/`turncred`, and the §2.2 HMAC vector.
  - Conformance cases: no `turn` in HELLO without config; `not_subscribed` with no sub; `rate` past the
    budget; two issues never share a suffix.
- **knit-spool, casts (§2.7):** `CastRecordVectorTest` for the vectors; `CastTest` for fan-out, nothing stored,
  silent drops, no strike, the reload kill switch and the ticket rule; conformance checks `cast-advertised`,
  `cast-fanout`, `cast-silent-refusal`, `cast-ticket` and the advisory `cast-rate`.
- **knit-next:**
  - Casts (§2.7): `SpoolCastRecordsTest` against the same hex; `CastSealTest`; the cast set in `ScopeFramesTest`;
    `SpoolConnectionCastTest`; `ScopeSyncTest`'s cast cases; a signed talk frame that fails to open raises no reset
    (`InboundPipelineTest`); the `ct` flag in `CallManagerTest`; and `WalkieTalkLabTest`'s burst cast between two
    phones with no radio path.
  - The key swap (C-7.6-27 to C-7.6-29): `RelayInviteTest`'s `ticketInvite` vector and refusals, `TicketRefresherTest`
    for the schedule row by row and the refresher against the stores (the host rule, the park, the daily retry, the
    route and plane gates, nothing secret in a log), `OkHttpTicketIssuerTest` for the request's shape and the
    coordinator's three answers on a socket, `TicketKeyStoreTest`, `RelayInviteApplierTest`'s key cases,
    `InternetRelayScreenContentTest`'s sheet lines and `SettingsSnapshotTest` (the key rides a backup, its state does
    not).
  - The files gate (C-7.6-24, C-7.6-30): `SpoolTicketRecordsTest` against `tkFiles`, `sigFiles` and `helloEntGates`;
    `SpoolConnectionTicketTest` for the gate table, each verb against its bit, the upload states and a late `ticket`
    refusal; `ScopeSyncTest` for an unticketed member that never `aput`s and still fetches, a spent budget across a
    reconnect and a same-`ps` ticket, and TURN on a files-only spool; `RelayReachTest` for `Locked`'s precedence; and
    `InternetPlaneLabTest`'s photo through a files-gated relay. knit-spool's side is `TicketFilesTest`,
    `TicketGatesTest` and the `ent-gates`, `files-ticket` and `files-receive` conformance checks.
  - A `CallPayload` golden vector (with `dw`/`dx`).
  - TURN pruning (C-7.6-26): `CallConfigurationTest` pins the prune policy beside continual gathering and relay-only,
    and `RelayTallyTest` the call log's `allocations gathered N, pruned M, held K`; a measured relay-only call gives
    the numbers.
  - Reconnecting (§3.8): the restart cases in `CallManagerTest` — answered under the same call, a blip that costs no
    frame, two restarts then dropped, the callee's window and its renewal, a restart that does not fit, a superseded
    answer, relay-only.
  - Call records (§3.12): `CallRecordTest` for the outcome table and the row, the record cases in `CallManagerTest`,
    `CallLogTest`, and the call line in `ChatScreenContentTest` and `ChatListTransferPreviewTest`.
  - The audio route (§3.13): `SystemCallSyncTest` for the system call's lifecycle, glare, refusals, mute and the
    walkie's correction; `CallAudioPolicyTest` for the start, the correction and the notification's target.
  - Stale `OFFER` → missed call; `CANCEL` before `OFFER` → no alert.
  - Glare resolution; idempotent duplicates.
  - Relay-only SDP stripping, and fingerprint mismatch → call refused.

## 5. Open questions

- **iOS ringing:** a PushKit relay, foreground-only ringing or outbound-only calls (ADR 2026-10.3tee).
- **Secret rotation:** coturn has one `static-auth-secret`, so rotation is a short window of failed
  allocations unless the sidecar uses its database-backed multi-secret mode.
- **Whose TURN:** a client may want to prefer a TURN server run by someone other than the spool
  operator, to avoid §2.5's join. Nothing above stops that, but no configuration surface exists for it.
- **Timing signature:** a call shows up as a short burst of about five blobs plus a `turnreq`. That is
  more distinctive than chat traffic, and padding (§11) is the only answer. A walkie over a relay (§2.7) is the sharper
  case, two casts a second per talking side: padding hides what was said, but not that something was or when, and only
  cover traffic during the silences would.
