---
id: "2026-10.b8yf"
slug: a-bluetooth-link-s-acl-is-armed-to-idle-out-when-its-channel-closes
title: "A Bluetooth link's ACL is armed to idle out when its channel closes"
date: 2026-10-09
topics: [ble, transport]
---

# ADR 2026-10.b8yf — A Bluetooth link's ACL is armed to idle out when its channel closes

Status: Proposed (2026-10-09). Work item #142. JVM-tested (`OrphanAclPolicyTest`, `FieldEventSchemaTest`). The device
trial's Android 16/17 cells passed on the P9 and P8 (2026-10-09, below); its Android 12 cells are owed before it stands. No wire change, Android only. Amends ADR 2026-09.dqvb ("no Android↔Android link
opens a GATT client") and builds on ADR 2026-09.hj4a (the lonely dial whose replaced link left the first orphans) and
ADR 2026-10.2da4 (the buffer share an orphan takes).

**What was observed.** After a lonely dial (hj4a) was replaced by the ordinary dial, the LE connection the lonely dial
had opened stayed up, idle, for 30 minutes and more on both phones. AOSP splits the controller's ACL buffers over every
LE connection (`l2c_link_adjust_allocation`, 2da4), so two such orphans cut the Pixel 3's file drain from 11–21 KiB/s to
about 7: a group picture took 125–268 s instead of 13–32 s. The issue left open who held the connection.

**Why it stays up.** Nobody holds it. An LE ACL that no *local GATT client* ever held is not idled out when its last
L2CAP channel, Knit's CoC, closes:

- **Android 12** (`system/bt`, android12-release). `gatt_init` registers the ATT fixed channel with
  `default_idle_tout = 0xffff`, never. `gatt_send_conn_cback` sets an idle timeout only when an app holds the link, and
  `l2cu_no_dynamic_ccbs` returns early while a fixed channel has no idle timeout.
- **Android 16 and 17** (android16-release; found on the P9 and P8, 2026-10-09, below). A link with no holder is meant
  to get `GATT_LINK_IDLE_TIMEOUT_WHEN_NO_APP` (4 s): `gatt_send_conn_cback` calls `GATT_SetIdleTimeout`, which stores
  it on the ATT fixed channel through `L2CA_SetLeGattTimeout`. On the side that **dialed** (the central) that call
  fails when the connection comes up. The stack logs `gatt_set_idle_timeout: idle_timeout=4, is_active=false,
  status=false (1-OK 0-not performed)` on 13 of 13 central links, against `status=true` on 6 of 6 peripheral links.
  `L2CA_SetLeGattTimeout` fails only when it finds no LE link or no ATT channel for the address, so on a central one of
  the two is not in place yet when GATT is told of the connection (which one is not traced). The central's link keeps
  no idle timeout and
  outlives its CoC, while the peripheral idles out 4 s after it. That is #142's pair: an Android 16/17 lonely dialer
  (central, never idles) against the Android 12 P3 (never idles either). The source reading this replaces
  (`l2cu_allocate_ccb` marks a CoC link active, so it should idle out) is about the gate that lets the timer run, not
  the timeout it runs with.

A client that attaches to the ACL and leaves (`disconnect()` + `close()`) sets the no-app idle timeout when it leaves.
On Android 16/17 that is the 4 s timeout (`gatt_set_idle_timeout: idle_timeout=4 … status=true` right after each
`bt acl arm … attached`, central or peripheral). The timer runs once no dynamic channel remains, and the stack drops
the ACL about 4 s after the CoC closes (1–4 s on Android 12, by source). Either end's disconnect ends the ACL on both
phones, so one armed side clears the pair, and an armed Android 16+ central needs nothing from its peer (trial cell 3's
question, answered on the P9 and P8). EATT is not involved: the arm opened no EATT channel, and both sides refuse it on
Knit's unencrypted links. The hunt's Pixel 9 dump shows the healthy case: a debug build with Coded on lists
`app.getknit.knit` (`BlePhyControl`'s client) as each mesh link's holder, and those links dropped cleanly.

Knit's GATT clients rarely touched an Android↔Android link: `BlePhyControl` needs Coded and a peer heard on Coded,
`BleLinkTuner` a file under the boost or a debug `LINKTUNE`, and the doorbell and the payload reader serve iPhones
only. So every Android↔Android link closed in range could leave its ACL: an eof, an eviction, a replacement, `stop()`,
the notification's Pause. So could every channel that opened an ACL and never became a link: a dialer the responder
refused (on the lab pair the commonest way in, since the P3 holds the largest id and refuses a sighted lonely dialer,
hj4a), a HELLO that failed, a dial that connected and then failed. A force-stop does not; an armed ACL is the stack's
state, not the app's.

**#142's own snoop** (the P3's, run G, read again on 2026-10-09):

- **Handle 21, the P9's lonely dial, P3 peripheral, opened 02:29:38.** The P9 opened four CoCs on it between 02:29:39
  and 02:30:52, and the P3 sent `DISC_REQ` about 100 ms after each, refusing every one. The ACL then stayed up with no
  channel until the capture ended. This is a refused dial's ACL, the unlinked case below.
- **Handle 3, the P8's lonely dial, P3 peripheral, opened 02:35:35.** It carried the link's CoC until the *P8* sent
  `DISC_REQ` at 02:37:51; #142 had read the P8's `eof` as the P3 closing. The ACL then stayed up with no channel. No
  disconnect event follows either handle, so neither was a re-made ACL.

**Step 0, on the phones (2026-10-09, 6760b843, wear, Coded and boost off, the P3 dialing the P8, then the P3 paused).**

- **The P8 (Android 17) drops the ACL as peripheral** 4 s after the CoC closed (`l2c_link_timeout … All channels
  closed`, reason 0x16), its TCB reading `No ACL holders` throughout, and every Android 14+ peer did the same after a
  force-stop of the P3. So #142's orphans come from the central side (its Android 16/17 lonely dialers) and from
  Android 12 in either role. The P3 holds the lab's largest id, so only a lonely dial ever makes it a peripheral.
- **Android 12 re-dials every dropped LE peer from its controller's accept list.** The P3 logs
  `btm_add_dev_to_controller adding RPA into acceptlist` at each disconnect and connects again as central within
  2–3 s whenever the peer still advertises that address, Knit force-stopped or not. The re-made ACL carries no
  channel; the P8 held one more than 80 s, until the P3's next Knit dial reused it. No arm prevents that.
- **`getConnectedDevices(GATT)` on the P8 counted the orphan** (`leLinks` 4 against 3 links) with Knit's GATT server
  off: GMS registers GATT servers, and the stack tells every registered server of every LE connection. A phone with no
  server registered may not list it, so that count is no oracle for an orphan.

**What changed.**

1. **Every link's ACL is armed at link-up.** `registerLink` calls `armAcl` for each link with a device that the
   doorbell does not serve (an iPhone's doorbell lookup attaches already). `armAcl` is a `BleLinkTuner` that asks
   nothing: `finish(LinkTuneRequest(nodeId))` attaches with `autoConnect = false` and calls `disconnect()` and `close()`
   in its `finally`. It is kept out of `tuners`, so the boost and `LINKTUNE close` never take it over, logs as
   `bt acl arm <id> …` (not `bt tune`, which `scripts/ble-drain-trial.py` reads), and is not cancelled at teardown: its
   `isLive` is "this link is live, or the ACL's connect edge was seen and no disconnect since", so an attach that lands
   after the channel closed still lets the ACL go. One Knit side armed is enough, which also covers older peer builds.
   An iPhone's doorbell lookup attaches at link-up and reports that attach as its arm.
2. **So is the ACL of a channel that never became a link** (`armUnlinked`): the responder's refusal, a HELLO that
   failed or never came, and the dialer's failures after `socket.connect()` returned (connect error, HELLO refused or
   mismatched, a watchdog timeout whose connect unwound later). It waits 2 s on the elapsed clock — the ACL's connect
   edge comes from the Bluetooth app's process and can trail a channel that lasted milliseconds — then watches the
   address as an unlinked channel's end and arms it, only if the edge says the ACL is up and no live link or dial of
   ours is on it. A dial whose ACL never came up therefore arms nothing and dials nothing.
3. **The tuner lets a late registration go.** On Android 12 `close()` before `onClientRegistered` does nothing
   (`unregisterApp` returns while the client id is 0), and the late registration then connects and holds the link.
   `BleLinkTuner` marks itself released in its `finally`; a connect callback after that disconnects and closes, and a
   client that never attached is closed once more 5 s later on the elapsed clock (a pending direct connect is a radio
   user), for a registration whose connect never reports. This covers the boost's tuners too.
4. **A detector releases what the arms missed** (`OrphanAclPolicy`, #142's option 3). The `ACTION_ACL_*` receiver (9utz,
   exported on purpose) reads `EXTRA_DEVICE` and, from API 33, `EXTRA_TRANSPORT`; a link's end (teardown, or the
   replaced link in `registerLink`) hands the policy its address. An address is watched only while its connect edge
   was seen and no disconnect followed, so a missed edge misses an orphan and never flags a connection that is gone.
   On the existing 60 s `diagLoop` tick, in every build: an ACL up 15 s past its channel's end (or its last release)
   is released by a fresh attach-and-leave, at most twice, then reported once as kept; one that goes down is reported
   once as cleared. A disconnect counts only once the address has stayed down 15 s, so Android 12's re-dial is the same
   orphan with its tries carried over, and an entry that went down and came back inside the grace gets one try, not
   two: each release only invites the next re-dial. An address a live link or an in-flight dial uses is skipped, at the
   tick and again just before the release attaches. At most 16 addresses are watched; adapter on/off clears it all.
5. **At `stop()`** the edge receiver and the tick are gone, so a watched ACL still up whose arm never attached (nor is
   still trying) gets one more arm and its record (`tries=0`, `cleared=false`) before the policy forgets everything.
   Without it a Pause kept that ACL, unrecorded, for its length. The arm runs on the app's scope, which a stop does not
   cancel.
6. **Oracles.** `bt acl edge ACL_CONNECTED|ACL_DISCONNECTED <addr>` (debug), `bt acl arm <id> attached (<addr>) in
   <ms>ms`, `bt acl release <id> attached …`, `bt acl orphan <id> <addr> release try=<n> (link ended <ms>ms ago)`,
   `bt acl orphan <id> <addr> cleared|kept tries=<n> armed=<b> age=<ms>ms`, `bt acl orphan <id> <addr> unarmed at
   stop (ended <ms>ms ago)`, and `arms=<attached>/<begun> orphans=<watching> released=<n> reported=<n>` on the debug
   `bt state` line, where `arms=` counts arms (link-up, unlinked, stop) and never releases. The field log records each
   orphan once as `bt_orphan` (`peer`, `linked`, `armed`, `tries`, `cleared`, `age`): `linked=false` is a channel that
   never became a link, and `armed` tells "the arm failed" (`false`) from "the stack ignored it".
7. **The A/B switch.** `…debug.LINKTUNE --ez arm false|true` (`LinkTuneDiag.aclArm`, echoed as `arm`). Off, nothing is
   armed or released, and each orphan is still reported, with `tries=0`. The extra is applied only while the
   Bluetooth transport runs: a paused mesh answers "Bluetooth transport is not running" and drops it, so set it after
   `RESUME`. No build flag: this is a fix, and the trial gates the merge.

**Amends dqvb.** Its "no Android↔Android link opens a GATT client" (already narrowed by 2da4's boost) no longer holds:
every link opens one for a few milliseconds at link-up, without discovery, and lets it go. The Moto G's stack, which
dqvb kept away from discovery, is a trial cell.

**Not taken.**

- **Keeping the lonely link** (#142's option 2) instead of replacing it. It treats one way in, while every in-range
  close in release left its ACL.
- **Holding a client for the link's life** (as `BlePhyControl` does). It costs a client registration per link (32 per
  device, shared by every app) for nothing the attach-and-leave does not buy, and an open client holds the ACL, so a
  missed close would make the orphan this fixes.
- **`getConnectedDevices(GATT)` as the detector.** It lists another device's connection only while some app's GATT
  server is registered: the lab's debug builds ran the Wear status server, release builds don't.
- **`BluetoothDevice.connectGatt` with `autoConnect = true`.** A background client would keep the peer on the
  controller's accept list, which is the re-dial this has to live with on Android 12, not one to add.

**What it costs, and the traps.**

- One GATT client registration and attach per link-up and per refused or failed channel (milliseconds), and per
  release (at most two per orphan).
- **A release on a stale edge dials.** If a disconnect edge is late, `isLive` reads the ACL as up and `connectGatt`
  makes a direct connect for up to the 2 s attach window, which can reach a peer still advertising at that address.
  The attach then leaves at once and the ACL idles out; the two-try bound caps it.
- **Android 12's accept-list re-dial undoes a release** while the peer keeps its address. The detector stops after
  its tries (one, once a re-dial has been seen). The peer's RPA rotation stops further re-dials but does not end an ACL
  already up, so a re-made orphan the tries did not clear can outlive both phones' detectors: it stays until a
  supervision timeout, an adapter cycle, or a Knit dial that reuses it and arms it at link-up. The trial counts
  releases, re-dials and that lifetime per address.
- **A cross-dial over the same ACL.** An unlinked channel's arm waits 2 s and skips an address a link or a dial of ours
  is on. One that starts after the arm is safe: the arm only arms the idle timer. When the timer fires,
  `l2c_link_timeout` disconnects only `if (!p_lcb->ccb_queue.p_first_ccb)` (android12 and android16 alike), so a
  channel opened while it runs keeps the link. Only a dial that starts as the stack is already disconnecting the ACL
  fails, as an ordinary dial with its backoff.
- **An ACL edge that trails its channel by more than 2 s** leaves an unlinked channel unwatched and unarmed: a miss,
  never a false flag.
- The ACL edges are the oracle. With the receiver unregistered (a ROM without the protected-broadcast declaration),
  nothing is watched; the arm still runs.
- **`arms=` counts skipped arms.** An unlinked arm whose ACL never came up (a dial that timed out) returns at its
  `isLive` check without a log line, yet `arms=<attached>/<begun>` counts it begun: the P9 read 9/17 with every arm
  that ran attached. Read the `bt acl arm … attached` lines, not the ratio.
- **`unarmed at stop` over-reports on Android 14+.** At `stop()` an ACL whose arm never attached is recorded as a
  `bt_orphan` (`tries=0`) before the stack has had its 4 s; the peripheral's own timer then drops it. In the field it
  needs an arm that failed, so it stays rare; with the A/B switch off it fires for every link.
- `leLinkCount`'s KDoc said a mesh link appears in `getConnectedDevices(GATT)` only while a tuner holds it. It appears
  whenever any app has a GATT server registered (Step 0), and an orphan is counted, rightly: it takes a buffer share.

**Device trial, the P9 (Android 16) and P8 (Android 17), 2026-10-09 05:30–06:01.** Both ran f97421d3 release-shaped
(`-PwearStatus=false -PbleCodedPhy=false -PbleLinkBoost=false`), with Wi-Fi Aware on. The P3, P7 and Moto meshed in range
on older builds, so they were unarmed peers. The P9 dials the P8, the P7 and the Moto, and the P3 dials both. The
oracles were `dumpsys bluetooth_manager` (`shim::btm` ACL history, `shim::acl` with `is_locally_initiated`) and the
stack's `gatt_set_idle_timeout` and `l2c_link_timeout` lines.

- **Cell 2, in-range closes: passed.** PAUSE on each phone, the process kill of an install, and a natural eof: every
  Knit ACL was down 1.2–4.2 s after its channel closed.
  - An armed central disconnected itself 4.0–4.1 s after the close (`l2c_link_timeout … All channels closed`).
  - The Moto ends its side in about 1.2 s.
  - The one straggler was a disconnect collision: both ends sent `LL_TERMINATE`, and the P8 saw its side end by
    supervision timeout (0x08) 5 s later.
  - The LoRa board's link (`Knit_16b8`) on the P9 also drops 4 s after a pause. Its GATT client stops with the mesh;
    that predates this change.
- **Cell 3, the controls: passed for Android 16/17.**
  - With `arm false` on both phones, the stop logged `bt acl orphan … unarmed at stop` and a `bt_orphan` (`tries=0`) for
    each link, as designed.
  - The ACLs still dropped, but only by the peripheral's timer: the unarmed central never started one. The connect-time
    `status=false` above is why.
  - Armed, the central's timer runs whatever the peer does. So arming the Android 16+ side alone clears #142's pair,
    by mechanism; the P3 side of the cell is still owed.
- **Cell 4, side effects: passed for what this pair can show.**
  - No EATT from the arm. EATT opened only on bonded links (the LoRa board, an Oura ring), and the P3's attempts on Knit
    links were refused as unencrypted with the arm on or off.
  - The dial failures were not the arm's: `OTHER` was `CONNECTION_FAILED_ESTABLISHMENT` (0x3e) at −84 dBm before any arm
    ran, and `TIMEOUT` a peer not yet advertising after its resume.
  - Relinks after a pause took 40 s to five minutes, because the settled scan floor (the larger id does not hunt)
    predates this; a `HEAL` brought them in.
- **Steady state, 10 min, arm on.**
  - Every link was armed and attached, on both phones.
  - Both phones read `orphans=0 released=0`, with no new `bt_orphan`.
  - `leLinks` minus the links equals the phone's known non-Knit LE devices: three on the P9, one on the P8.

**Still owed**, on the same build shape with the P3:

1. #142's recipe: the P8 alone past 180 s, lonely-dials the P3, the relink replaces it. Pass: within 5 s of
   `bt link down`, both phones log `bt acl edge ACL_DISCONNECTED <addr>`; `leLinks` equals the link count on both; the
   P3's snoop holds no idle LE handle and no TCB holds a dead link.
2. An eviction close.
3. Arm the P3 alone (Android 12), then the P8 alone against the P3. Of the extra cases below, (a), (b), (d), (e) and (f)
   remain; (c) passed on the P9 and P8.
4. Releases and re-dials per address over 30 min stop after two tries; an iPhone link unchanged.
5. The drain A/B: the group-picture cell, the P3 lit sending to the dark P8 and P9, 8 reps, `arm false` with orphans
   then armed, in one session. The P3→P9 drain should match the no-orphan control.

Cell 3's extra cases:

- (a) A refused lonely dial: the P8 alone past 180 s while the P3 sights it, so the P3 refuses it (`bt refused
  client`). Pass: `bt acl arm` on both phones about 2 s after, the ACL gone within seconds more, no `bt_orphan`.
- (b) An evicted pair with the P3 as central: the churn (releases, re-dials) and the ACL's lifetime after a `kept`
  record, and the P3's `dumpsys bluetooth_manager` connection_manager section right after the close, to see who holds
  the accept-list entry.
- (c) A Pause with `--ez arm false` on both phones: `bt acl orphan … unarmed at stop` and a `bt_orphan` with
  `tries=0` on each, and the ACL still up for the pause; then the same with the arm on. Run on the P9 and P8: the
  records came as written, but the ACL did not stay up, since an Android 14+ peripheral idles out by itself; the cell's
  "still up" holds only against the P3.
- (d) The Moto G as a peer: after `bt acl arm … attached`, no service discovery and no parameter put-back in its
  snoop.
- (e) A cross-dial: force a dial to an address within 1 s of its `bt acl release` or unlinked `bt acl arm`. Pass:
  the CoC opens and holds, with no `bt_fail`.
- (f) An iPhone link closed in range: no `bt_orphan`, or one with `armed=true` (the doorbell's attach is its arm).

## Amendment, 2026-10-11: arms are bounded (#150)

Status: Proposed. JVM-tested (`OrphanAclPolicyTest`); the device trial below is owed. No wire change, no field-log
schema change, Android only.

**The hole.** Every channel that closed without becoming a link armed its ACL 2 s later, and each arm is a fresh
`BleLinkTuner`: one GATT client registration, of the 32 a device shares with the doorbell, the payload reader, a
Meshtastic board, the watch fallback and every other app. Nothing deduplicated by address or capped arms in flight, so a
dialer that opens and closes CoCs over one ACL many times a second turned each close into a registration. Worse,
`onLinkEnded` and `onLinkUp` erased the address's arm state, and `armUnlinked` called `onLinkEnded` before the arm, so
each new channel wiped the `PENDING` mark a dedup would read.

**What changed.** #150's options 1 and 2; admission is untouched.

1. **One settle per address.** `armUnlinked` keeps the addresses waiting out the 2 s settle, each with the latest node
   id a channel's end there named; a channel's end on an address already settling returns at once (its node id kept,
   so a named refusal during a HELLO-less settle still watches the ACL by name), so a flood spawns one sleeping
   coroutine, not one per channel. The entry goes in a `finally` inside the coroutine and again on completion (a launch
   cancelled before it ran); it leaves the map right after the gate, before the node id is read the last time, so an
   end after that starts a settle of its own rather than writing into one nobody reads. An ACL whose edges say it is gone, or that a link or dial of ours uses, begins nothing (it
   used to begin an arm whose tuner then returned at its `isLive` check, which is how `arms=` came to count skipped arms,
   above: it no longer does), and neither does the A/B switch off.
2. **Flights and slots.** Every arm and every release is a flight named by the token its begin returns, and its end is
   reported by that token (`onArmed`). A flight holds one of `MAX_ARMS_IN_FLIGHT` (3) slots from its begin until it
   attaches, or, when it never attached, for `SECOND_CLOSE_MS` (5 s) more, its late registration's second close. A
   flight that never made a client (its ACL was gone at the attach, or `connectGatt` refused) has nothing that could
   register late: `BleLinkTuner` reports it through `onNeverRegistered` instead of `onEnded` (only where the caller asks;
   the boost's tuners are unchanged), and the policy frees its slot at once and puts its address's row back as it was
   (no retry window). When the arm it replaced was pending (a cross-dial's link-up arm over an unlinked arm), the row
   goes back to that arm while its flight is still pending (its own end or lapse then settles it), takes its failure if
   it failed meanwhile, and says no arm if its flight is gone, so the address is never pinned pending. Otherwise a dialer from three rotating addresses that drops each ACL as its channel closes would
   pin every slot with no registration behind them. HELLO-less channels' arms hold at most `MAX_UNNAMED_ARMS` (2)
   slots, so one stays for releases and named arms (a real peer's refused dial). One that never reports back lapses to
   failed after `ARM_LAPSE_MS` (30 s). An end reported after `clear()` or `stop()`,
   or after its lapse, is ignored, so a tuner that ends just after an adapter toggle leaves nothing behind. The
   minute's `check()` emits a release only into a free slot, the fewest tries first; one that finds none waits for the
   next round with its tries intact.
3. **The arm row outlives a channel.** `OrphanAclPolicy` keeps each address's latest arm (its token, state and end)
   across its channels' ends; `onLinkEnded` copies it into the watched entry and neither it nor `onLinkUp` erases it.
   An end is read against the row's token: a superseded arm's failure (an unlinked arm, then a cross-dial's link-up arm
   on the same ACL, the first standing down at its `isLive` check) leaves the row alone, any attach marks it attached,
   and attached is never downgraded. The doorbell's lookup reports its attach without a flight (`onDoorbellAttached`).
   An attached row goes with the ACL's disconnect edge (the idle timeout it set lives on that ACL, so Android 12's
   accept-list re-dial, a new ACL, is armed again) and with a connect edge more than `EDGE_TRAIL_MS` (2 s) after its
   attach (a connect edge is always a new ACL; one that close may be the attach's own ACL, its edge trailing). A
   pending or failed row survives a disconnect edge. `clear()` (adapter off or on) and `stop()` forget every row and
   flight, and the 64-row trim takes the oldest.
4. **`tryArm` gates the unlinked arm** and refuses it when (1) an arm is pending on the address, (2) an arm attached
   to the ACL, (3) an arm failed less than `ARM_RETRY_MS` (10 s: the 2 s attach, the 5 s second close, margin) ago, or
   (4) every slot is held. Refusals (3) and (4) are lifted by time alone: the settle asks `msUntilOpen` (the retry
   window's end, else the soonest slot: a failed flight's second close, a pending one guessed done at its 2 s attach and
   never sooner than 0.5 s), waits that long on the elapsed clock, reads the link, dial and edge state again, and asks
   once more, at most twice. A refused lonely dial's ACL is therefore armed within seconds, not left to the 15 s grace
   and the minute's net, whose wait stretches while the CPU sleeps.
5. **Link-up arms are never refused.** `registerLink`'s arm (`onArming`) and the doorbell's attach keep b8yf's rule
   that every link is armed; links are few, and their arms take slots. A stop's arm stays outside (the stop forgets
   everything).
6. **The net under a refusal, and a flood's watch.** A channel with a node id is watched before the gate, as before.
   A HELLO-less channel (watched by nobody until now) is watched under `(no hello)` when it is still refused for a
   recent failure or the slots after the retries, since no arm stands on its ACL; a pending or attached arm already
   covers it. Such a watch never replaces a named entry on the address (which would reset its grace and lose
   `linked=true`), at most `MAX_UNNAMED` (4) of them are watched, and the 16-entry trim takes them first, so a flood
   from many addresses cannot push a peer's orphan out.
7. **Oracle.** No log line per refusal (a flood would flood logcat). `skipped=<n>` on the debug `bt state` line, after
   `orphans=`, counts gate refusals since the transport was made (`armsSkipped`): one per `tryArm` that refused, so a
   settle that waited and asked again adds one per ask, channels merged into a settle add nothing, and a settle that
   found an arm begun meanwhile adds nothing. It counts refusals, not channels: never size a flood from it.

**Device trial owed.** A harness dialer (Bumble on an nRF52840, `scripts/hci-bridge.py`'s stack) opens and closes about
20 CoCs a second for 60 s against the P8 (Android 17) and the Pixel 3 (Android 12), with a garbage HELLO and without,
from one address and from a few. Pass: at most one `bt acl arm … (<addr>)` in flight per address and none after its
`attached` until the ACL's `ACL_DISCONNECTED`; `skipped=` rising on `bt state`; at most three arms in flight overall; a
bonded board or the payload reader (`bt gatt read … →`) staying attached throughout; `bt acl release` lines never more than
three at once on the minute; and, with the flood from four or more addresses, a named peer's orphan still watched and
released (`bt acl orphan <id> … release try=1`). Regression: b8yf's refused lonely
dial on the P3 still logs `bt acl arm … attached` about 2 s after the refusal (and within about 12 s while a flood
holds the slots, screen off)

Two more cells:

- **Starvation.** Three or more rotating harness addresses whose ACL drops right after each channel closes, against
  the P8 and the P3, while a real peer's link is closed in range and a real peer is refused. Pass: the real orphan's
  `bt acl orphan … release try=` still fires within two minute rounds, and a real `bt refused client` is still
  followed by `bt acl arm … attached` within about 12 s.
- **A cross-dial that never registered.** Force a dial onto an address within 1 s of its unlinked `bt acl arm`, and
  tear that link down at once. Pass: a later refused channel on that address still logs `bt acl arm … attached`
  within about 12 s, never `skipped=` rising with no arm until the ACL drops.
- **A real lonely dial into the flooded phone.** A real peer, alone past 180 s, lonely-dials the phone under the flood.
  Pass: no `bt_orphan` for it, or one with `armed=true`., and its ACL drops (no `bt_orphan`, or one
`cleared`).
