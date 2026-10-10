---
id: "2026-10.9utz"
slug: the-ble-presence-set-is-enabled-again-after-every-connection
title: "The BLE presence set is enabled again after every connection"
date: 2026-10-02
topics: [ble, mesh]
---

# ADR 2026-10.9utz — The BLE presence set is enabled again after every connection

Status: Accepted (2026-10-02). Device-verified on the Pixel 7 with the amendment below (#112).

**What was observed.** knit-ios's link probe ran on 2026-10-01 against a Pixel 7. A link at a 15 ms interval opened
just after the presence set started, and the controller then refused an enable of that set:
`on_set_extended_advertising_enable_complete: … CONNECTION_REJECTED_LIMITED_RESOURCES(0x0d)`. Two kinds of link
did this: a bonded central (`…12:31`, `Meshtastic_1230`) and Knit's own A3 GATT read of an iPhone.

- After the refusal, the presence advert stayed off the air for 33 to 60 s.
- A passive ATS2851 scanner heard it 0.07–0.39 times a second, against about once a second when the phone is idle.
- With the central bonded, the iPhone linked within 30 s in only 2 of 8 samples. The slowest took 109.5 s.

Nothing in the app noticed. `BleAdvertiser` had no `onAdvertisingEnabled`, and a non-null set made `update` swap data
on a set that was off.

The AOSP source explains it (`system/gd/hci/le_advertising_manager.cc`; the logic is the same on 14, 15, 16 and
main):

- **A connection to a connectable set.** The connection terminates the set. `handle_set_terminated` re-enables it
  through `enable_advertiser` with `trigger_callbacks = true`. On an extended-advertising controller the app gets
  `onAdvertisingEnabled(set, true, 4)`.
- **Any connection the stack makes or completes.** The address manager pauses and resumes every set around its
  accept-list and resolving-list writes. `OnResume` re-enables with `trigger_callbacks = false`, so a refusal there
  reaches the app not at all. That is the A3 case.
- **The stack never retries, and never rolls back its bookkeeping.** It still believes the set is enabled. The advert
  returns only when some later pause and resume happens to land after the controller has room again. That later cycle
  is the "came back on its own" in the logs.
- **The legacy HCI path reports success always.** On a controller that uses it, the app's enable callback says 0
  whatever happened.

**What changed.** The transport re-asserts the presence set itself:

- **`BleAdvertiser.reassert()`.**
  - On a live set it calls `enableAdvertising(true, 0, 0)`. The stack sends that command with no "already enabled"
    guard and always calls back with the status. The Core spec (Vol 4 Part E §7.8.56) lets an enabled set be enabled
    again; doing so only resets its duration and event count.
  - On a set whose start failed, it starts one with the newest payload.
  - It does nothing while a start is in flight, after `stop()`, or with the adapter off.
  - `onAdvertisingEnabled` reports every outcome for the live set, asked for or not.
- **`AdvertReassertPolicy.Keeper`** decides when to call it:
  - 2.5 s after any connection opens or closes. That is after the stack's own pause and resume, and outside the window
    in which our enable could make the controller refuse the resolving-list write the pause is for.
  - After a reported refusal, on a doubling wait: 2.5, 5, 10, then 20 s.
  - On a net under both: every 10 s while BLE holds no link, every minute while it holds one.
  - At once on `heal()`.
- **`BluetoothMeshTransport.advertLoop`** runs that schedule. It enables the presence set first, then the Coded set
  (ADR 2026-10.yvn6) while that one is live. The Coded set rides the presence set's turns and keeps no schedule of its
  own.
- **The connection edges come from an `ACTION_ACL_CONNECTED` / `ACTION_ACL_DISCONNECTED` receiver.** It catches the
  bonded watch, a Meshtastic board, the A3 read and other apps' connections, none of which reach the transport any
  other way. The transport also marks an edge itself at link up, link down and the end of a GATT read.
- **Oracles:** `bt advert refused <status>, retry in <ms>` and `bt advert enabled again (refused for <ms>ms)` (`Log.i`),
  plus `advert=` on the debug `bt state` line.

**Alternatives.**

- **Overriding `onAdvertisingEnabled` alone** (the issue's option 1) is the fix a reader reaches for first. It covers
  only the bonded-central case: the `OnResume` re-enable around our own connections, the A3 case, never calls back.
- **Making room in the controller** (option 3): holding the A3 read until the set has settled, or keeping fewer sets.
  This narrows the window, does nothing for a central connecting, and costs the reads their timing. Rejected.
- **A stop-and-restart instead of an enable.** This brings back the old PSM race (`BleAdvertiser`'s doc) and a start
  budget. Rejected.

**What it costs.**

- One binder call and one HCI command per turn: every 10 s alone, every minute linked, plus one per connection edge.
- The loop's wait is a plain `delay`, so it never wakes a suspended phone. The cadence does not relax when the node is
  lonely (kb68), because a lonely node is the one a newcomer must find.
- The settle cannot fully guarantee that our enable never lands inside some other pause the stack starts. The stack's
  own terminated-set re-enable has the same exposure.

**What it does not cover.**

- `WearStatusServer`'s pause-only advert and the side channel's page sets still have no re-assert. The first is up
  only during a mesh pause; the second is non-connectable, on in release since 2026-10-08, and retries its slots on a start failure only. Either can
  take `reassert()` the same way.
- On a legacy-HCI controller a refusal is invisible, so recovery waits on the net.

**Tests.** `AdvertReassertPolicyTest` covers the schedule. `BleAdvertiserTest` checks four things: a live set is
enabled again, a failed start comes back up with the newest bytes, a stopped set stays down, and only the live set's
callbacks are reported. On hardware, the bar is to repeat the 2026-10-01 probe with the central bonded and with it
disabled: the ATS2851 should never hear a 30 s silence, and the iPhone should link within 30 s in at least 7 of 8
samples.

## Amendment (2026-10-02): the ACL receiver is exported

The first Pixel 7 trial (knit-ios link probe, 2026-10-02, eight samples per run) passed with the Meshtastic board off.
Seven of eight iPhone links came within 30 s, and the longest silence the ATS2851 heard was 11.1 s. With the board
bonded the trial failed: three of eight links within 30 s and a 22.1 s silence. Before the fix the same case managed
two of eight, with 33–60 s silences.

The cause was the edge receiver. It never fired on any of the run's 18 ACL edges. The only enables to follow an edge
were the transport's own marks, at link up and at the end of a GATT read, plus the 10 s net. `ACTION_ACL_*` is sent
from the Bluetooth app's process (`BluetoothRemoteDevices`, uid 1002), not from `system_server`. A
`RECEIVER_NOT_EXPORTED` receiver hears only root, `system_server` and its own app, so it never receives these. The
adapter-state receiver keeps working because `system_server` sends `ACTION_STATE_CHANGED`. The ACL receiver is now
`RECEIVER_EXPORTED`. Both actions are protected broadcasts, and a forged edge would only bring an enable forward. It
logs `bt acl edge ACL_CONNECTED|ACL_DISCONNECTED` at debug.

Two observations from the same logs, not acted on here:

- **The Coded set (ADR 2026-10.yvn6) costs the presence set its re-enable.**
  - The stack's resume enables every set in one HCI command. With the board's link at 15 ms the controller refuses
    that whole command (0x0d), and the presence set goes down with it.
  - When `advertLoop` enabled the two sets separately at +11.7 s, presence went back on the air and only the Coded set
    was refused (`coded advertising enable=true status=4`).
  - The stack never removes a refused set from its enabled list, so while the Coded set is live, every later pause and
    resume under load fails as a whole. A shipped build, without the Coded set, should fare better than this trial did.
- **An enable reported as a success can still leave the set off while the link that caused the refusal is open.** In
  the 00:35 run, sample 3, enables at +51.3 s and +52.8 s both reported success during an A3 read's link, yet nothing
  was heard from +49.9 s to +69.5 s, while the ATS2851 kept hearing three other nodes. The first enable after the
  link closed came at +62.8 s, from the net. The `ACL_DISCONNECTED` edge, now delivered, would have brought one at
  +56.8 s.

**The re-run (2026-10-02, 288fc0ea, board bonded).**

- **Coded off (the shipped configuration).** The stack refused nothing all run (no 0x0d line). The edge receiver fired
  21 times, the longest silence was 7.2 s, and 6 of 8 iPhone links came within 30 s. Both misses were on the iPhone's
  side: the ATS2851 heard the Pixel two to eight times inside each of the iPhone's 9–12 s gaps. The silence half of the
  bar passes. The link half, at 6 of 8, falls short only on the iPhone.
- **Coded auto.** 6 of 8 links came within 30 s (up from 3 of 8), and the longest silence was 14.5 s. Each board
  connect was followed by an edge-driven enable 2.5 s later, and every refusal after it was the Coded set's. In 2
  of 8 samples, a presence enable that reported success still left the advert off until the next net turn, about 10 s
  later. That is the Coded experiment's cost to carry (ADR 2026-10.yvn6), not this decision's.

## Amendment (2026-10-09): the side channel's page sets ride the re-assert (#143)

"What it does not cover" left the side channel's two page sets (ADR 2026-09.sjaa) without a re-assert. Since
2026-10-08 `BLE_SIDE_PLANE` is on in release, so a release phone runs four sets: presence, Coded presence and two page
slots. The stack resumes all of them with one enable after each connection, with callbacks off. A refused resume left
a slot dark, and the channel noticed nothing:

- `BleSideChannel` retried a slot only after a start failure.
- It pushed bytes with `BleAdvertiser.update`, which changes the data of a set the controller may have left off.
- The carousel still counted the page as aired (`bleSidePartAired`).

So an unlinked bystander missed the floodable frames the channel exists to carry.

**What changed.**

- **`BleSideChannel.reassert()`.** It calls `BleAdvertiser.reassert()` on every slot that has a page on the air. A
  slot counts only if the channel is `live` and the slot's page is still recorded as pushed.
  - A slot the carousel took down was stopped, and `stop()` keeps it down.
  - A slot sitting out a refused start is stopped too, and has no pushed page, so it stays down until
    `SLOT_RETRY_MS` is up.
  - A dark or torn-down channel raises nothing.
- **`advertLoop` calls it on the presence set's turns**: the settle after an ACL edge, the net and a heal. It runs
  after the presence and Coded sets, and each set gets its own enable. The amendment above showed why that matters:
  when the controller refuses an enable, a separate command keeps the refusal to that one set.
- **A refusal is retried per slot, not by `advertKeeper`.** Each slot has its own `AdvertReassertPolicy.Keeper` with
  no net, the shape `codedKeeper` already has. A refused or unasked-off enable retries on the 2.5–20 s doubling wait,
  and the loop is woken so it picks up the shorter wait. Why not feed `advertKeeper`, as presence does:
  - A slot's success would call `onEnabled` and wipe a presence refusal's pending retry and backoff.
  - A slot's refusal would bring presence's enable forward for no reason.
  - One keeper shared by both slots would have the same problem between the two slots: if one kept succeeding, the
    other's backoff would never grow past 2.5 s.

  The retry is needed because the 60 s net is too slow while linked. The refusal comes from a link at 15 ms, so the
  node is linked, and a minute off the air is longer than a page's whole 12 s dwell plus 30 s linger. A retry that
  comes due on a slot with nothing to air is used up rather than kept, so it cannot hold the loop's wait at its 1 s
  floor.
- **What callbacks a slot gets.** Page sets are non-connectable, so the stack's own terminated-set re-enable (the one
  resume that reports back) never happens to them. A slot therefore hears refusals only in answer to our own enables.
- **Oracles:** `ble-side slotN advert refused <status>, retry in <ms>ms` and
  `ble-side slotN advert enabled again (refused for <ms>ms)` at debug, from the `BleSideChannel` tag.
- **Lock order.** The order is `BleSideChannel`'s lock first, then the slot's. The enable callback runs on a binder
  thread inside the slot's lock, so it touches only the slot's keeper. The start callback (`onSlotStatus`) used to
  take the channel's lock from inside the slot's lock. That inverted the order, and the new turns call into the slots
  under the channel's lock every 10 s, so the two could deadlock. Its reaction now runs on the channel's scope instead.
- **A late start reaction.** Running on the scope opens a window between the binder callback and the reaction. Two
  rules close it:
  - A reaction that stands a slot down stops the slot's sets. A sit-out stops its own slot, and a halved page stops
    every slot. Inside the window the slot's page is still pushed, so a turn or a carousel push can start the set
    again. If that start succeeds before the stale reaction lands, a reaction that only cleared `pushed` would leave
    a live set airing a page nothing tracks, holding one of the controller's sets.
  - A reaction from before an adapter bounce is dropped. `bringUp` and `tearDown` bump a generation, which
    `onSlotStatus` captures, and a fast bounce skips STATE_OFF (557d2d86). Without the check, an old reaction would
    park a fresh slot for 60 s, halve the re-probed page, or turn the new session dark. A sit-out or halved page on a
    channel that is no longer `live` is dropped as well.

**What it costs.** One more binder call and HCI enable per live slot on each turn: two at most, every 10 s alone and
every minute linked, plus one per edge. A slot with no page costs nothing. The enable does not add a set to the
stack's enabled list, since a live slot is already on it, so the whole-command resume is exposed exactly as before.

**Tests.** `BleSideChannelReassertTest` covers these cases:

- A live slot is enabled again on a turn, and the empty slot is not raised.
- A refused enable comes back after 2.5 s and wakes the loop.
- A retry that comes due on a torn-down channel is used up.
- Nothing raises a slot the carousel took down, a slot sitting out a refused start, a dark channel or a torn-down one.
- With the reaction held on a standard test dispatcher, a sit-out or halved page that lands after a restart succeeded
  stops that set.
- A sit-out, halved page or feature refusal from before a tear-down and bring-up is dropped.

The device trial is the 2026-10-01 probe with Coded and the side channel on: P7 with the Meshtastic board bonded,
Moto G and P3, eight link up/down cycles each. The bar:

- No presence silence on the ATS2851 longer than 11 s.
- The iPhone links within 30 s in 7 of 8 samples.
- After the cycles, pages from both slots are still caught (`bleSideHeard` / `bleSideReassembled` on a bystander).
