---
id: "2026-09.kwq2"
slug: an-android-phone-finds-an-iphone-through-its-gatt-payload-and-dials-it
title: "An Android phone finds an iPhone through its GATT payload and dials it"
date: 2026-09-29
topics: [ble, transport, interop]
---

# ADR 2026-09.kwq2 — An Android phone finds an iPhone through its GATT payload and dials it

Status: Accepted (2026-09-29). Companion change A3 for the iOS port (`knit-ios`, ADR 2026-09.xzpt there), knit-next issue
101, with #102 for the link-parameter motive. Behind `BuildConfig.BLE_GATT_PEERS`, on in release since
2026-10-08, ahead of its device trial. The flag bit landed in a commit of its own, after the device gate passed
(2026-09-29).

**What was observed.** A foreground iPhone advertises the `0xFE30` UUID and nothing else: iOS cannot advertise service
data, and `BleScanner` filtered on it, so an Android phone never sighted an iPhone and never dialed one. The iPhone
dialed instead, which A1 (ADR 2026-09.shzv) made work from either id order. That leaves the iPhone the central of every
iPhone–Android link, and an iPhone central runs a link at a 720 ms supervision timeout (#102). A link Android dials is
Android's to set, and runs at 5 s. The iOS port has served its advert payload from a GATT characteristic since xzpt,
so it can be found by reading it.

**What changed.** Android ports xzpt's reader. Nothing moves on the wire.

- **The flag.** `BleAdvertPayload.FLAG_DIALS_GATT_PEERS = 0x02`, bit 1 of the flags byte (byte 23), means "I read
  this payload, so I can find you". An iPhone whose id sorts below a flagged phone waits to be dialed rather than
  dialing. It is set only while the reader runs, since a flag without a reader strands the pair. Every shipped
  Android parser tests only bit 0 and ignores the rest, so the bit is additive (`docs/WIRE_COMPAT.md` now says so).
  Pinned against the iOS vector `09294213fa67091e03c2f2ae8d071b9d6700000000008002` in `BleAdvertPayloadTest`.
- **The identifier.** `DoorbellPolicy.PAYLOAD_UUID`, `848eedcd-a2e3-4fb2-86f9-e2c80821a497`, a read-only
  characteristic in the same `0xFE30` service as the doorbell. Its value is the 24-byte advert payload with the digest
  cue zeroed. It is looked up by UUID.
- **The pacing.** `GattPayloads` is `GattPayloads.swift` line for line. One read runs at a time, and each gets 12 s,
  the L2CAP connect watchdog. A failed read waits 30 s, and a stranger waits 10 min: one that connects but serves no
  `0xFE30` service, no payload characteristic, or a value under 23 bytes. `cancel()` runs on radio stop. Android adds
  one thing: each of the two maps keeps at most 64 addresses, least recently used first out, because an iPhone rotates
  its address about every 15 min and iOS never prunes. `GattPayloadsTest` mirrors `GattPayloadsTests.swift` case for
  case, plus the cap and the quiet clock. The 2026-10-11 amendment adds a second Android-only rule: a budget across
  addresses, and a ripe dial goes first.
- **The payload lifetime** (the contract, revised after the first device gate, on both sides). A payload serves every
  advert from its address until the transport forgets the address, which it does on any of three rules:
  1. A dial to that address fails before its channel opens (`failConnect`, never on HANDSHAKE). This catches an
     iPhone that restarted onto a new PSM.
  2. A HELLO on a link to that address names a node other than the payload's: the HELLO of a peer that dialed us
     (`superviseAccepted`, against `socket.remoteDevice.address`, before the verdict) or the reply to our own dial.
     A refusal, where the responder closes without a reply, keeps the payload.
  3. The address has gone unheard for 60 s of *scanning* (`GattPayloads.QUIET_MS`; only the time the presence scan
     ran counts, so a floored scan's idle gaps don't force a re-read), while no link to it is up. An advert, the read
     itself and a link up all count as hearing it.

  The first gate found the case these close: rule 1 alone never forgets an address this phone does not dial, so when
  knit-peer relaunched on the same adapter address under a new node id, the Pixel 7 and the Pixel 3 sighted the old
  node there for good (`reach=[…, xdibkr…]` for five minutes, `gattReads` frozen) and admitted the real dialer as
  unsighted.
- **The scan.** The presence `BleScanner` gets a second filter, OR'd with the service-data one, on `0xFE30` in a
  service-UUID list. `onScanResult` splits: service data is sighted as before (`sight`). A UUID-only advert with a
  cached payload is sighted with it at this advert's RSSI. Without a cached payload, it launches a read on the
  transport's scope and wakes nothing. The read's own sighting is what can boost the scan.
- **The reader.** `BleGattPayloadReader`: `connectGatt(TRANSPORT_LE)`, discovery, the characteristic, a read (the
  API-33 and pre-33 callbacks, as `MeshtasticGatt` has them), then `BleAdvertPayload.parse`. There is no ACL yet, so
  the read is a dial and gets the whole 12 s, not the doorbell's 2 s attach. It holds `BleConnectArbiter` for the
  read, so both scans pause. It never reads an address a link holds, and never while an L2CAP dial is in flight or
  the arbiter is held, and a promotion waits while a read runs. The client is closed after every read. It sends no
  `requestMtu`: the stack's Read Blob fetches the 24 bytes at MTU 23, and an MTU request races SMP on a Pixel
  (`MeshtasticGatt`'s settle).
- **The dial.** The rule is unchanged: the larger id dials, and a sighted dialer below us is refused. A read
  iPhone is sighted, so it is judged exactly as an Android peer is.
- **The dialed link's capabilities.** `registerLink` takes the HELLO reply's `PeerWire` on the dialer's side, not
  the presence advert's. The advert carries only the low capability byte, which never holds
  `CAP_DOORBELL = 0x1000`, so a dialed iPhone would never have been rung (ADR 2026-09.dqvb's amendment).

The alternative a reader reaches for first is to keep the iPhone the dialer and ask for better parameters over the
doorbell's client. That is #102, and it ships. It depends on iOS granting each request and on the lookup
succeeding, while a link Android dials is Android's own. The other alternative is dropping A1 once Android can
find iPhones. It stays whole (see the shzv amendment): it is the fallback that makes a clear flag safe.

**Diagnostics.** At info: `bt gatt read <addr> → <id> (psm <psm>)`, `bt gatt read <addr> failed (<phase>)`,
`bt gatt read <addr> stranger (<phase>)`, and `bt gatt forget <addr> (dial|hello <nodeId>|quiet)` for each rule of
the payload lifetime that forgets a payload. At debug: `bt gatt reading <addr>`, and `gattPayloads=` / `gattReads=` on the `bt state` line
while the gate is on. The iOS worker's gate scenario greps these lines.

**What it costs, and its limits.**

- **The floored scan.** When the iPhone's id sorts below ours, the first link waits on this phone's scan cadence,
  which is floored while it already holds links. The trial measures first-link times in that order. A flag that
  follows the scan tier is the follow-up only if they miss MVP item 2.
- **A failed first read.** The pacing's 30 s wait after a failed read is paid in full by a first link: in the first
  gate a read that failed at connect put a peer's first link at 71.0 s. A first link is at least the read, the
  promotion dwell counted from the read's sighting, and the connect.
- **An identity change at an address that never goes quiet** stays open, on both sides. When the new node sorts below
  a flagged phone and the old one above it, nobody dials: the phone never dials the old node, and the new one waits
  to be dialed. No HELLO arrives to fire rule 2 and the address is never quiet for rule 3, so the old sighting lasts
  until the address rotates or goes quiet.
- **The service-replace race.** A read that lands while iOS is replacing its service for a PSM change finds no
  characteristic, and that counts as a 10-minute stranger. It is kept line for line with iOS.
- **A backgrounded iPhone.** Its UUID moves to Apple's overflow area, which only iOS can read, so this phone cannot
  sight it. One whose id sorts below a flagged phone waits for a dial that cannot come. That costs nothing today,
  because a backgrounded iPhone's unfiltered scan finds no new peers either. Once iOS gains background discovery,
  its `weDial` must ignore a service-data peer's flag while backgrounded. Both sides record this limit, and neither
  changes code for it here.
- **`0xFE30` is not Knit's alone.** A stranger costs one connection per 10 min per address, and 64 addresses at most
  are remembered. Those waits are per address, so they never bounded the reads in all: the 2026-10-11 amendment
  adds a budget across addresses.

The mesh lab has no radio layer, so no lab scenario covers this. `GattPayloadsTest`, `BleAdvertPayloadTest` and
`DoorbellPolicyTest` pin the rules and identifiers. The reader is device-verified only, like `BleDoorbell`.

**Device gate: passed 2026-09-29, at b7480e85 with the flag clear.** knit-peer's `ios` profile on `hci1` ran against
the Pixel 7 (API 37, the API-33 read callback) and the Pixel 3 (API 31, the pre-33 one; the Moto G, planned for it,
now runs an API-34 GSI), in both id orders:

- Every read named knit-peer, and none named an Android phone.
- The phone dialed only when its id was larger, and found and rang the doorbell on every link it dialed. Above it,
  the peer was admitted as sighted and never dialed.
- All three forget rules fired on the Pixel 7: quiet, hello on an accepted link (then `Admit, sighted=false`), hello
  in a dial's reply (a HANDSHAKE), and dial (a TIMEOUT).
- First links took 40.5 to 104.8 s. Three of the four runs began with a failed first read, mostly
  `timeout@discover` against `hci1`, and each paid the 30 s retry. The iPhone runs settled it as the rig's: the
  overruns were `hci1`'s discovery, not the reader's, so the pacing stays as it is.

The first gate, on 7c16aae5, is what found the stale payload at a reused address. The flag then went on in its own
commit.

**iPhone checks: passed 2026-09-29, on the flag build (8a6d1a58), against the iPhone 12.** knit-peer read the
flags byte as `0x03` on both phones.

- **The iPhone below the Pixel 3:** 19 of 19. The Pixel 3 dialed it, first link in 15.9 s, relink in 3.3 s.
- **The iPhone above the Pixel 7:** 19 of 19. The iPhone dialed, first link in 10.5 s, relink in 13.8 s. The
  Pixel 7 had to be within a metre; at −97 dBm the iPhone never dialed it.
- **`iphone-wake` with the Pixel 3:** 10 of 10. Every DM to the locked iPhone woke Knit over a link Android had
  dialed, and the Pixel 3 logged ten rings.

One peer-above run on the Pixel 3 made no read for 180 s. Its presence scan ran with the UUID filter the whole
time, and every window returned only the three Android phones in reach, so knit-peer's advert never arrived: rig
side.

**Amended 2026-10-10 (AND-3): a GATT-sighted iPhone's life once it backgrounds.** A foreground iPhone read here is
sighted, so it was judged and scored as an Android peer is. Once it went to the background this phone stopped
hearing it, and for the 90 s presence linger its re-dial from below was refused as a sighted dialer's, after which
its live link scored −127 and was the first shed at the 6-link budget. Both now key on the `CAP_DOORBELL` bit in
the link's HELLO (ADR 2026-09.shzv's AND-3 amendment): such a link scores the −90 floor out of presence, and such a
dialer is sighted for admission only if heard within 12 s. The "backgrounded iPhone" limit above still holds for a
first link: this phone cannot sight one and so never dials it, and one below a flagged phone still waits.

## Amendment 2026-10-11 — a budget across addresses, and a ripe dial goes first (#154)

**What was wrong.** The pacing was per address only. One read ran at a time, but nothing bounded the reads in all.
Every read is a connection: the reader holds `BleConnectArbiter`, both scans pause, and `driveConnections` skips
every dial while one runs. One radio rotating random addresses under `0xFE30`, or a crowd of stranger devices, kept
a read in flight nearly all the time and starved this phone's dials and relinks. There was a race too. A read's end
calls `finish()` and then `wake()`, and under an advert stream the next scan result on the main thread could
`begin()` a new read before the connect loop ran. A ripe dial lost to read after read.

**What changed.** Two Android additions, beside the 64-address cap. Neither moves the wire or the iOS pacing.

- **A budget across addresses.** At most 12 charged reads in any 10 minutes of the elapsed clock
  (`GattPayloads.BUDGET_READS`, `BUDGET_WINDOW_MS`). `begin` answers false while the window is full. Twelve is sized
  for honest rotations: about one read per iPhone per 15 minutes, plus a re-read after a PSM change.
  - Every read that ends is charged, whatever it found: a payload, a failure or a stranger. The payload is
    unauthenticated, so a read that found one proves nothing. A rotating advertiser that serves a valid payload
    naming an id above every real one is never dialed, so a free read would bring the starvation back whole.
  - Only a completed L2CAP link with a HELLO vouches for a payload. When `registerLink` pairs an address with the
    HELLO's node, on a link we dialed or one we accepted, `GattPayloads.confirmed` refunds the charge of a read at
    that address whose payload named that node. A charge is refunded once: the refund removes it. A HELLO naming
    another node, or the same node at another address, refunds nothing.
  - A charge nothing refunds ages out of the window. That covers an address forgotten or evicted from the cap, and a
    refund that comes after its charge has left the window is a no-op.
  - A cancelled read is free: the radio went down under it. No forget is charged, by any of the three rules. The
    re-read a forget makes due is charged when it ends, like any other.
- **A ripe dial goes first.** The connect loop calls `GattPayloads.yieldTo` on every pass, before its read gate,
  with whether the decision has a dial to make. While it has, `begin` answers false. A read already in flight runs
  on, so a dial waits for that one read at most (12 s), never for a second. `cancel()` clears the flag.
  - The flag counts only a promoted peer with an address to dial (`DialPlan.dialWaiting`, against `dialTarget`).
    `initiateTo` returns without dialing a peer it has no address for, and such a peer stays promoted for as long
    as presence keeps it: a peer heard on Coded alone once the Coded mode goes off. Counted, it would hold every
    read off for its whole linger. A peer that is dialed and fails is backed off, and so leaves the promotion.
  - A flag left set by a peer that has since gone lasts until the loop's next pass, at most its one-minute ceiling.

**What it costs.**

- Once strangers or a forger have spent the budget, an honest iPhone at a new address waits for a slot, up to the
  window. It is still sighted at once if it dials us (A1, ADR 2026-09.shzv) or advertises service data. Only the
  order where its id sorts below this flagged phone waits on the read.
- An iPhone at the edge of range pays one unit for each re-read until it links. Each failed dial forgets its payload
  (rule 1), and the next read is charged and never refunded. With the doubling backoff, that comes to seven or eight
  units in ten minutes: one such iPhone fits, and two spend the budget.
- An honest iPhone that is read but never linked by us is never refunded. That happens when the link budget is full,
  when it sorts above us and does not dial, or when the quiet rule forgets it and it is read again. It is charged once
  per address rotation (about 15 minutes) and once per quiet forget. In a crowd of ten or more Knit iPhones these
  charges alone come near the budget, and a newcomer below this phone waits up to the window to be read. The crowd
  cell of the trial measures it; the numbers change only if it shows.

**Diagnostics.** `bt gatt budget spent (12 in 600000ms)` and `bt gatt budget free` at info, once per edge, checked at
an advert that would start a read and never logged per advert. `bt gatt confirmed <addr> → <id>` at debug for a
refund. `gattBudget=<used>/<budget>` follows `gattReads=` on the `bt state` line. The existing oracles are unchanged.
`GattPayloadsTest` and `DialPlanTest` pin the rules.

**Device trial owed.** Two stimulus variants, 30 minutes each, each beside a Pixel and an Android peer the Pixel
should dial:

1. A rotating `0xFE30` advertiser that serves no payload characteristic.
2. A rotating `0xFE30` advertiser that serves a forged, valid payload naming a large id, rotating about every 20 s.

Both pass on at most 12 charged reads per 10 minutes, the budget-spent edge logged, and no `bt initiating to` more
than one read (12 s) after its `promote=`. The cost cell: an iPhone at the edge of range for 15 minutes, then a
second iPhone in range, read within one scan window. With no strangers present an honest iPhone is still read and
linked, and `bt gatt confirmed` follows its read at the same address. Run that once with the iPhone dialing us, to
check that an accepted link reports the address we read. The crowd cell: ten or more Knit iPhones in range, where
`gattBudget=` shows how much of the budget honest rotations spend. knit-ios's `scripts/interop.py` has no rotation
mode yet: that is knit-ios #3.
