---
id: "2026-10.2da4"
slug: a-bluetooth-link-that-feeds-a-file-asks-for-a-short-interval-and-earns-a-larger
title: "A Bluetooth link that feeds a file asks for a short interval and earns a larger budget"
date: 2026-10-06
topics: [ble, mesh]
---

# ADR 2026-10.2da4 — A Bluetooth link that feeds a file asks for a short interval and earns a larger budget

Status: Accepted (2026-10-06), behind `BuildConfig.BLE_LINK_BOOST`, on in release since 2026-10-08, ahead of its device trial. Work item
knit/knit-next#120. Amends ADR 2026-10.8jwn (the budget) and ADR 2026-09.dqvb (Android links and GATT). No wire change,
Android only.

**What was observed.** 8jwn held every 1M/2M link to one 4 KiB/s file budget, so a 700 KB photo took about 171 s, and
named the follow-up: ask for a shorter connection interval while a file streams. The premise check on #120 doubted it.
A Pixel 7 snoop from the receiving side showed a link using 1–2 of about 12 packet slots per event, which looked like
controller scheduling, not the interval.

The drain trial (2026-10-05/06, `scripts/ble-drain-trial.py`, `…debug.LINKTUNE`, both phones' HCI snoops read by
`scripts/btsnoop-drain.py`; Wi-Fi Aware off, 5 GHz Wi-Fi, a fresh 700 KB file per rep) found the cap on the sending
side, which the receiver's snoop cannot show:

- **The host feeds a link only its share of the controller's LE buffers.** The Pixel 9 never had more than 3 of a
  link's packets in the controller (13 buffers split evenly across its 4 LE connections, idle ones included; AOSP's
  `l2c_link_adjust_allocation`). The Pixel 3, holding one link, kept 8 in flight.
- **About 2 packets cross per connection event**, so the drain follows the interval:

| Interval | Measured drain | 2 × 247 B per event |
|---|---|---|
| 45 ms | 8.8–9.5 KB/s | ≈ 9.9 KB/s |
| 30 ms | 12.6–14.3 KB/s | ≈ 14.3 KB/s |
| 15 ms (`CONNECTION_PRIORITY_HIGH`) | 22.5–29.2 KB/s | ≈ 24.7 KB/s |

- The levers, pace unbounded:
  - HIGH, asked from either end, gave 2–3× (P9→P8 22.5–24.7 KB/s, P9→P7 28.3–29.2 KB/s). A link settles at 15 ms
    (Android 12's HIGH gives 7.5 ms).
  - Android 12 sends 27-byte PDUs until an ATT MTU exchange. `requestMtu(517)` raised the Pixel 3's TX data length
    to 251 and its drain to 23 KB/s, and 65 KB/s with HIGH.
  - 2M alone bought nothing at the same interval, and about 10% with HIGH.
  - The release radio flags (side plane, Coded, GATT peers off) changed nothing.
- **A pace is still needed.** Unbounded and boosted, texts waited 10–28 s behind the stack's queue.
- **HIGH at an 18 KiB/s pace** moved the file in 41–42 s, against ~171 s, and every text behind it (+5/+15/+30/+60 s)
  arrived in 3.4 s or less (P9→P8, three reps).

**What changed.**

1. **Either side asks, while its own screen is on.** `FramedLink` pokes `onBusy` when its (feeding, receiving) pair
   moves. Feeding is a file queued or streaming (`pendingFileKeys`, not `txInProgress`, so back-to-back files do not
   flap). Receiving is a header in and no end. The transport makes a `BleLinkBoost` at a link's first file either way.
   When the link goes busy either way on a side whose screen is on, that side asks the link for
   `CONNECTION_PRIORITY_HIGH`, and for the ATT MTU (517) the first time on that link. It asks through the link's one
   `BleLinkTuner`: a GATT client on the link's ACL, attached without discovery, as `BlePhyControl` and the doorbell
   attach. A boost's asks are queued without waiting for their reports, so a release never waits behind an earlier
   ask's wait (which would stretch in suspend).
2. **It lets go when the link is quiet.** The side that asked asks for `CONNECTION_PRIORITY_BALANCED` once it has
   neither fed nor received for 15 s (`LinkBoostPolicy.LINGER_MS`). Receiving holds that release, so one side's linger
   never cuts the other's feed.
3. **Screen on only, for the asker.** The linger waits on the elapsed clock (pj9w), and that wait never wakes a
   suspended CPU. A boost asked on a dark screen could hold the controller at 15 ms until the next wake. So only a side
   whose screen is on (`PowerState.interactive`) asks. The screen-off edge, which runs on a waking CPU, lets go at once,
   even under the peer's feed: a dark phone cannot be trusted to let go later, and a peer still streaming with its own
   screen on asks again. Nor does a side ask while Bluetooth audio streams (A2DP takes the air a 15 ms link would need,
   and the budget was measured with none), and no link earns then either. A side with its screen off still listens. A sender in a pocket does not ask, but it hears the
   watching receiver's ask and earns from it. Neither asking nor listening happens for an iPhone
   (`DoorbellPolicy.serves`: its doorbell owns the parameters, dqvb), for a link on Coded (its pace sits outside the
   split), or with the boost switched off (`LinkBoostPolicy.observes`).
4. **Earned by what the link reports, not by the ask.** The tuner hears every `onConnectionUpdated` on the ACL,
   whoever asked. A successful update at or under 15 ms earns. A slower update clears it, and so do this side's release
   and losing the client that heard it (`forget`, when the tuner ends or the bridge closes it). Android has no getter for the interval, and a same-values ask may report nothing. So a tuner attaches at the link's
   first file in either direction and stays for the link's life, and a link never heard stays unearned.
5. **The budget.** `CodedPhyPolicy.pace(phy, tuning, feeding, earned, leLinks)` takes the boosted budget instead of
   4 KiB/s only while every link feeding a file on 1M/2M has earned it (`BluetoothMeshTransport.paceFor`). The boosted
   budget is `PhyTuning.boostPaceBytesPerSec` (18 KiB/s, 85% of the slowest HIGH drain) on a phone holding up to four
   LE connections, the count it was measured at. A phone holding more gets `crowdedBoostPaceBytesPerSec` (12 KiB/s):
   the host splits the controller's buffers over every LE connection, so past four a link can fall to one packet in
   flight, about 16 KB/s at 15 ms. The count (`leLinkCount`) is the mesh's links plus every other device a GATT client
   or server on the phone is connected to (`getConnectedDevices(GATT)`, the mesh's own addresses not counted twice),
   read at most every 10 s. The split, the two-second chunk and its clamps are 8jwn's. One iPhone, Coded or unheard
   link among the feeders holds them all to the base, which fails safe.
6. **Asks repeat a bounded number of times.** A slower report while this side streams (a put-back, the peer letting
   go) and a settle check 2 s after an unanswered ask each re-ask, at most twice an episode, as `DoorbellPolicy.Balanced`
   does. A failed update (`0x2A`, two asks colliding) counts for nothing. A release that no report has answered is
   asked again, at most twice, so a release that collided does not leave the link fast. Any report after the release
   ends that (slow: it landed; fast: the peer asked since and holds the link), and so does a file that started after
   it. A release is therefore never repeated between the peer's files. A release made under the peer's feed (the
   screen went off) keeps its retry until that feed ends, since a pocketed peer would never put the link back. A tuner that never attached spends a try from
   `DoorbellPolicy.Lookups`' budget.
7. **Oracles.** `bt boost <id> high[ mtu=517]|release|earned interval=…us|unearned interval=…us`; `bt file pace …
   earned=true|false le=N`, with both last so every older parse holds; and `bt state … boosts=N boosting=N earned=N`.
   `…debug.LINKTUNE` carries `--ez boost false|true` (the A/B switch on one build: off, nothing asks or attaches),
   `--ei boostPace N` and `--ei crowdedPace N`, and reports `leLinks` and, per link, `boosting` / `earned`.
   `scripts/ble-drain-trial.py --boost on|off` runs the A/B and reports `releaseAfterS`.

**Not taken.**

- **A 2M step.** It adds about 10% with HIGH, nothing without it, and trades range (yvn6 deferred it on the same
  grounds).
- **Only the feeder asks.** That was the first draft. A receiver's ask buys nothing from an older feeder, which paces at
  4 KiB/s whatever the link does. But a feeder whose screen is off cannot ask at all, and that is often the case: a
  photo sent from a pocket, or a carrier relaying, while the person waiting for it watches the chat. Either-side asking
  costs a receiver some power with an older feeder, on a phone whose screen is already on, and more `0x2A` collisions,
  which the settle check absorbs.
- **A wake lock or an alarm for the linger,** so a side with its screen off could ask too. Either costs more than the
  15 ms interval it would bound. A file between two dark phones keeps 8jwn's budget. That is the follow-up.
- **Pacing to the measured drain.** The sender cannot see it (the stack's queue hides it). The receiver could report
  it, but only with a link record the iOS port and older builds would tear the link down on (8jwn's option 2).

**What it costs, and the traps.**

- **Every Android link that carries a file now opens a GATT client.** dqvb kept Android↔Android links off GATT
  because discovery on some stacks (the Moto G's) misbehaved. The tuner never discovers, but it is one more client
  registration per busy link (32 shared by every app), and the Moto's stack is a trial cell.
- **Power.** A boosted link runs about three times the connection events of a BALANCED one, but only while a file
  moves and for the 15 s after. A 700 KB photo at 15 ms for ~40 s costs fewer events than at 45 ms for ~171 s.
- **The budgets assume a buffer count like the Pixel 9's.** 18 KiB/s holds at 2–3 packets in flight. The 12 KiB/s
  crowded budget is a guess from one packet per event, and the four-connection line is the Pixel 9's. Another
  controller with fewer buffers drops to one in flight sooner. The trial's crowded-phone cell sets both shipped numbers,
  each at 0.85 × the worst boosted drain measured in its range.
- **A dark phone's pace stretches anyway.** Its writer's waits between chunks stop while the CPU sleeps (8jwn's trap).
  So a sender in a pocket that earns the receiver's ask may still feed slower than its budget. The screen-off cell
  measures how much the boost buys there.
- **The trial ran on 5 GHz Wi-Fi with Wi-Fi Aware off and no Bluetooth audio.** 2.4 GHz coexistence and Wi-Fi Aware's
  discovery windows take Bluetooth air. Both are cells before the release flag flips. A2DP is kept out by rule; LE Audio
  is not (it is not what `BluetoothAudioMonitor` watches), so it belongs in the crowded cell.
- **The LE count may miss native-profile links.** `getConnectedDevices(GATT)` reads the Java GATT service's client and
  server maps. An LE ACL held inside the native stack (HID over GATT, LE Audio, ASHA) may not appear there unless some
  app has a GATT server registered, yet it still takes a buffer share. Such a phone may earn 18 KiB/s where 12 would
  fit: unsafe, but bounded to that difference. The crowded cell checks `le=` against the snoop's LE handles; if it
  undercounts, count connected bonded LE devices too, or lower the line.
- **A link replaced over the same ACL** gets its departing tuner's last ask, BALANCED, if this side was boosting
  (`BleLinkTuner.finish`, no report waited for). A link torn down lets its client go at once: the ACL goes with it.
- **The release oracle is the tuner's served ask** (`bt tune <id> ask priority=BALANCED requested=true`), not the
  boost's `bt boost <id> release`, which is its intent: an ask queued on a client that never attached is dropped.
- **A side that attaches after the other side's update completes never hears it,** and so stays unearned until its
  own ask or a later update. It keeps the base budget, which is safe, only slower. A dark sender in that position never
  asks: the receiver's next re-ask is all it can hear.
- **`onConnectionUpdated` is hidden** until compileSdk 37.2 (#126). `LinkParamsCallback` declares it once, `@Keep` and
  without `override`. It now carries production decisions, so the 37.2 bump must turn it into an `override`, not
  drop it.

Tests:

- `LinkBoostPolicyTest`: either side's asks, the linger, the screen (a dark side listens, never asks, and lets go even
  under the peer's feed), earning from a foreign update, forgetting it with the client, the bounded re-asks, and the
  release's settle, which never repeats after a report or a file since.
- `FramedLinkTest`: the busy edges, which never fire under a lock.
- `CodedPhyPolicyTest`: the earned budget alone, split and crowded, unearned unchanged, Coded outside.
- `TransferPacePolicyTest`: losing the boost mid-file charges the next chunk at the base share.
- `BluetoothWaitClockTest`.

`BleLinkBoost` and the tuner are verified on hardware only; there is no host GATT stack.

## Amendment 2026-10-06 — each link earns its own budget (ADR 2026-10.g24x)

Point 5's "one iPhone, Coded or unheard link among the feeders holds them all to the base" is gone. A feed's budget
is its own link's: boosted while that link has earned it, the base otherwise, split over the feeding links as
before. On 2026-10-06 one iPhone pulling a photo held three earned links to a quarter of the base. `bt file pace` is
written per link and ends `→ <node>`. The debug bridge's `LinkTuneReport.earned` still reads "every feeding link
earned".
