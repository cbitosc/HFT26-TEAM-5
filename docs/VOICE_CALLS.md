# Internet voice calls

| | |
|--------------------|-------------------------------------------------------------------------------------------------------------------------------|
| Status             | **Draft sketch, not normative.** Nothing here is on the wire                                                                  |
| Decision           | ADR 2026-10.3tee (Proposed); §3.10, a call between neighbours over Wi-Fi Direct, is ADR 2026-10.kxnw; §3.11, the walkie-talkie, is ADR 2026-10.79yb |
| Spool side         | §2 is written to drop into `docs/SPOOL_PROTOCOL.md` as §7.6 (records) and a §10.1 bullet (observations) once accepted        |
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
- **TURN credentials** are the only new spool-side behaviour (§2). The spool hands out short-lived
  credentials for a TURN server its operator runs. It never carries media.
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
| `SPOOL_TURN_URLS`        | Comma-separated `turn:`/`turns:` URIs. Required when the secret is set                                   |
| `SPOOL_TURN_TTL_S`       | Credential lifetime, default `300` (60–3600; S-7.6-8)                                                    |
| `SPOOL_TURN_RATE`        | Issues per connection per 10 min, default `4`                                                            |

`SpoolServer` gains one record handler. `ScopeStore` is untouched, because nothing is stored.

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

### 2.6 Entitlement tickets and STUN (experimental)

Two additions for a spool whose operator sells TURN (Knit Anywhere; knit-spool ADR 2026-10.bxsg).
A **ticket** is an Ed25519 grant an issuer signs and the spool checks with only the public half, so
the spool can tell a paying caller from anyone else without holding an account. A **STUN list** lets
any client, paying or not, learn its own public address — without which a free phone offers only
private addresses, and a paying peer's relay cannot permit it (coturn keys permissions on IP alone).

| Record | Direction | Fields                                    | Semantics                                                         |
|--------|-----------|-------------------------------------------|-------------------------------------------------------------------|
| `ent`  | c→s       | `t, q: Long, tk: bytes, sig: bytes(64)`   | Present a ticket. Answered by `ok`, or by `err ticket` with `msg` |

```
Ticket {                 // tk: the house CBOR, every field required, unknown keys ignored
    v:   1
    kid: bytes(8)        // SHA-256(raw Ed25519 public key)[0..8]
    aud: String          // the one spool it is good for
    nbf: uint            // Unix seconds
    exp: uint            // Unix seconds, exp - nbf <= 48 h
    ps:  bytes(16)       // the holder's pseudonym for the day, opaque to the spool
    fl:  uint            // bit 0: may ask for TURN
}
sig = Ed25519(issuer key, "knit/spool/v1/ticket" ‖ tk)
```

| ID           | Requirement                                                                                                                                                                                                                      |
|--------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **S-7.6-13** | A spool configured with a ticket key advertises `ent: true` in HELLO; one that is not MUST omit it. A spool that advertises STUN puts a non-empty `stun: [String]` of `stun:`/`stuns:` URIs in HELLO, with no credential.        |
| **S-7.6-14** | On `ent`, a spool MUST refuse with `err ticket` and a `msg` of `malformed`, `key`, `signature`, `audience`, `early` or `expired` unless `tk` decodes, `kid` names its key, `sig` verifies, `aud` is its own, and `nbf - 300 s <= now <= exp + 300 s`. |
| **S-7.6-15** | An accepted ticket binds to the connection until it expires, and a later accepted `ent` replaces it. A refused `ent` MUST leave an earlier ticket in place, and is never a strike.                                               |
| **S-7.6-16** | On a connection whose HELLO carried `ent`, `turnreq` needs a live ticket with bit 0 of `fl`, else `err ticket` with `msg` `missing`, `expired` or `flags`. With one, no subscription is needed. S-7.6-5's limits still apply.   |
| **S-7.6-17** | A ticketed credential's suffix is `SHA-256("knit/spool/v1/turn-user" ‖ ps)[0..10]`, so every issue under one pseudonym shares it, and the TURN server's per-user quota counts the holder, not the credential.                |
| **S-7.6-18** | A spool MUST NOT log or persist a ticket or a pseudonym. Metrics MAY count acceptances and refusals by `msg`.                                                                                                                     |
| **C-7.6-19** | A client that holds a ticket for a spool sends `ent` right after HELLO when the spool advertised `ent`, and again before the ticket expires. It MUST NOT retry a refused ticket on every round.                                 |
| **C-7.6-20** | A client SHOULD gather from every `stun` URI its connected spools advertise, on every call, whether or not it obtained a TURN credential.                                                                                       |
| **C-7.6-21** | A client that holds a ticket for a spool SHOULD ask that spool for TURN on every call, whether or not the DM scope is subscribed there; this refines C-7.6-12.                                                                   |

> **Why one ticket per connection.** The spool already ties a connection to every scope it
> subscribes, so a ticket on each record would buy no unlinkability and cost a signature check per
> record. The pseudonym does let the spool link one holder's connections across addresses within a
> day; blind-signed tickets are the later answer if that becomes a concern.
>
> **Why the suffix is hashed.** coturn's `user-quota` counts the part of the username after the colon
> (4.18.0, `get_real_username`), so a stable suffix per pseudonym is what makes a quota per customer
> possible. Hashing it keeps the spool-side pseudonym out of the TURN server's logs verbatim.
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
}
```

| `ph` | Name      | Sender | Meaning                                                                    |
|------|-----------|--------|----------------------------------------------------------------------------|
| 1    | `OFFER`   | caller | Start a call (`gen = 0`) or restart ICE (`gen > 0`)                        |
| 2    | `RINGING` | callee | The offer reached a device that is alerting. The caller UI moves from "Calling" to "Ringing" |
| 3    | `ANSWER`  | callee | Accept, carrying the answer SDP for this `gen`                             |
| 4    | `DECLINE` | callee | Refuse, with `rsn`                                                         |
| 5    | `CANCEL`  | caller | Withdraw before an answer, with `rsn`                                      |
| 6    | `END`     | either | Hang up after connecting, with `rsn`                                       |
| 7    | `CANDS`   | either | Late candidates for this `gen` (§3.6)                                      |

Reasons, in one registry as `TransferPayload` has: `1` user · `2` busy · `3` timeout · `4` no route
(ICE failed or no network) · `5` media unsupported · `6` error.

### 3.3 State machine

```
caller: Idle ─OFFER→ Calling ─RINGING→ Ringing ─ANSWER→ Connecting ─ICE ok→ Connected ─END→ Ended
                        └──────────────┴── 45 s / user ─CANCEL→ Ended
callee: Idle ─OFFER (fresh)→ Alerting ─user─ANSWER→ Connecting ─ICE ok→ Connected ─END→ Ended
                                 └──user / busy─DECLINE→ Ended
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

### 3.8 Network changes

A move from Wi-Fi to cellular kills the selected candidate pair. The side that notices sends `OFFER`
with `gen + 1` and fresh ICE credentials (an ICE restart), and the peer answers with the same `gen`.
The DTLS session and the call `id` survive. A ratchet session reset during a call is not recovered,
because ctl frames are never re-sealed (ADR 2026-10.gcrd's lesson for `CTL_TRANSFER`). An established
call keeps running, and only a later restart would fail.

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
  other pair ICE holds, or ends after the disconnect grace (§3.8 is not built).

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

When no live path exists, the call screen becomes a walkie-talkie over the mesh. The user holds a button to talk and
releases it to send the clip as an ordinary DM voice note (ADR 034). While the session is up, the peer's clips play
by themselves. It is not real time: a burst plays once its whole clip has crossed the mesh. ADR 2026-10.79yb records
why. It is an experiment behind `BuildConfig.WALKIE` (`-Pwalkie=`, on in debug and off in release), read once as
`di/CallWiring.WALKIE_WIRED`.

- **C-3.11-1** A build that runs the walkie advertises `CAP_WALKIE` (`0x8000`). It is never in
  `LOCAL_CAPABILITIES`. A caller offers a walkie session, or the drop to one, only toward a pinned profile that
  carries it.
- **C-3.11-2** A walkie session's OFFER has `m = 4` and no `sdp`, `cand` or `dw`. It rings, goes stale, gets busy
  and glares exactly as §3.3–3.5 say. Answering connects both sides at once, with `m = 4` on the ANSWER: there are
  no ICE servers, no connect timer and no grace. A build without the walkie declines it with `rsn = 5`, because the
  audio bit is absent.
- **C-3.11-3** The caller places a walkie session only when the peer carries `CAP_WALKIE` and no live path looks
  possible: no validated Internet route here, and no Wi-Fi Direct leg (§3.10) to a neighbour. Anything else is a
  live call.
- **C-3.11-4** A live OFFER carries `m = 1 | 4` when the caller can drop to a walkie. The callee echoes `4` on its
  ANSWER when it can too. With both bits, each of C-3.3-3's `no_route` endings drops the call to a connected walkie
  session instead:
  - the connect window,
  - ICE failed,
  - a lost path past its grace.

  The drop closes the media and ends any direct leg, and sends nothing. The other side's ICE sees the media go and
  drops on its own timer. Without both bits, the call ends as before.
- **C-3.11-5** A burst is a sealed DM voice note: `audio/aac`, at 16 kbps, 0.5–60 s. There is no new frame and no
  new MIME.
- **C-3.11-6** While a session is connected, a voice note plays by itself only if it meets all of these, judged on
  this phone's clock except the lag:
  - it is from the session's peer and has no file name;
  - it arrived after the session connected and has not played yet;
  - its `sentAt` is no more than 60 s before its arrival;
  - its clip landed within 90 s of its arrival.

  The notes play one at a time, in arrival order. A note that misses stays a voice note in the thread. A played
  note advances the thread's read watermark. The session suppresses the thread's notifications and hangs up after
  10 min with no burst either way.

## 4. Tests this would add

- **knit-spool:**
  - `SpoolRecordsTest` vectors for `turnreq`/`turncred`, and the §2.2 HMAC vector.
  - Conformance cases: no `turn` in HELLO without config; `not_subscribed` with no sub; `rate` past the
    budget; two issues never share a suffix.
- **knit-next:**
  - A `CallPayload` golden vector (with `dw`/`dx`).
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
  more distinctive than chat traffic, and padding (§11) is the only answer.
