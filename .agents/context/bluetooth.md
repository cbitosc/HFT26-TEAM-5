# Bluetooth plane — which decision governs which symbol

The symbol → ADR routing for Knit's BLE mesh transport: `mesh/bluetooth/` outside `meshtastic/` and `wear/`, with
`mesh/link/FramedLink.kt` and `mesh/power/ElapsedWait.kt`. The `bluetooth-owner` reads it before it writes and the
`bluetooth-reviewer` before it judges (both in `.agents/personas/`); `AGENTS.md` points here instead of carrying these
lines. Each entry names the symbols that trigger it, the ADRs to read (`.agents/memory/decisions.md` maps an id to its
file, amendments included) and, where there is one, the section of `mesh-transport.md` that explains the mechanism.
An entry is an index into its ADRs, not a substitute: where they disagree, the ADR wins and the entry is fixed.

Bluetooth-adjacent routing that stays in `AGENTS.md`, because another subsystem owns it: the LoRa/Meshtastic bridge
(`mesh/bluetooth/meshtastic/`), the Wear status server (`mesh/bluetooth/wear/`), the lonely cadence shared with NAN
(`PowerPolicy`, kb68/w3xk), and the blob exchange's reads of the link (`BlobExchange`, `fileInFlightTo`, 4tx5 and on).

## Entries

- **When touching `mesh/bluetooth/BleSideChannel`, `SideCarousel`, `SideScanPolicy`, `SideCapableTracker`,
  `BleFastRoutePolicy`, `BleAdvertPayload`'s flags byte, or `BluetoothMeshTransport.fastFanout`/`fastSend`:**
  READ ADR 2026-09.sjaa and the "page carousel" section of `.agents/context/mesh-transport.md`. The BLE side
  channel carries `shouldFastFanout` frames on non-connectable extended-advertising pages — one
  `FastFrameCodec` unit per 236-B page, never a chain, never presence, never a DM-form frame — gated per
  peer on the advert's flags byte and dark in release behind `BuildConfig.BLE_SIDE_PLANE` until its device
  trial (CHECK `.agents/memory/roadmap.md`). The receive scan is Off for an all-linked clique with nothing
  streaming (ADR 2026-09.u8qj) — the link copy already reaches every linked peer; don't widen that gate. `hasFastPlane` is now true for Bluetooth: the link copy the
  composite used to send lives inside the transport's `fastFanout`/`fastSend`; don't add it back upstream.
- **When touching `CodedPhyPolicy`, `PhyStepper`, `BlePhyControl`, `CodedPhyDiag`, `CodedAdvertPace`, `RangeHunt`,
  `AppVisibility`,
  `BleAdvertiser.codedParams` / `setInterval`, `BleScanner.phys`, `BlePresenceTracker`'s per-PHY RSSI,
  `BuildConfig.BLE_CODED_PHY` or `…debug.PHY`:** READ ADR
  2026-10.yvn6 and the Coded PHY section of `.agents/context/mesh-transport.md`. An experiment, dark in release: a
  second presence set on Coded (the legacy advert stays), an all-PHY scan, a Coded sighting scored on the 1M scale
  (+12 dB) so every −90 floor is untouched, and per-link 1M ↔ Coded S=8 steps driven by the larger id through a GATT
  handle on the link's ACL. Capability is inferred from hearing the Coded advert — no flags-byte bit. A dialer heard on
  Coded alone is admitted as unsighted (shzv's tie-break holds for 1M), and OFF leaves a link on its PHY. A Coded dial
  gets a 25 s watchdog, and a link that drops at range makes the dialed side's Coded advert fast for 3 min, its
  interval moved in place (`CodedAdvertPace`, `BleAdvertiser.setInterval`; the amendment of 2026-10-02), while the
  dialing side hunts it — lonely gap, Coded windows, no settled floor, whatever other links it holds — for those
  3 min, or up to 30 while Knit is open on a lit screen, which hunts on its own too (`RangeHunt`, the 2026-10-04
  amendment). CHECK
  `.agents/memory/roadmap.md` for what the release flag waits on.
- **When touching `CodedPhyPolicy.pace`, `PhyTuning.filePaceBytesPerSec` / `BLE_PACE_BYTES_PER_SEC`, `PaceConfig` /
  `PaceWindow` / `TransferPacePolicy`, `BluetoothMeshTransport.paceFor`, or `FramedLink`'s `pace`:** READ ADR
  2026-10.8jwn. **Before touching `FeedWindow`, `PaceConfig.windowed`, `BluetoothMeshTransport.windowed`,
  `FramedLink.feedChunks` / `awaitReport`, `RxReports`, or `BuildConfig.BLE_FEED_WINDOW`:** READ ADR
  2026-10.5buf — a file to an Android peer that reports is clocked by those reports on any PHY (written and not yet held kept under
  the measured drain over the round trip plus a quarter second, BBR-style, no split, no earned budget; on Coded in 1 KiB
  chunks, its amendment of 2026-10-08 (2)), the old pace only for a silent receiver, an iPhone or an older build; dark in release until its A/B, switched on a debug build by `…debug.LINKTUNE --es feed window|pace`. A Bluetooth file is fed below what the air drains, from one budget split evenly among the 1M/2M links
  feeding a file at once (`txInProgress`), in chunks of two seconds of the share; a paced Coded link keeps its own pace outside the
  split (yvn6's #114 amendment). The stack's queue hides the drain from the sender, so tune against the receiver's
  `rx … in <ms>ms` line — never the sender's `file … in`, which reads size ÷ pace. No wire change.
  **Before touching `LinkBoostPolicy`, `BleLinkBoost`, `BleLinkTuner`, `LinkParamsCallback`, `FramedLink`'s `onBusy` /
  `feeding` / `busy`, `PhyTuning.boostPaceBytesPerSec`, `BuildConfig.BLE_LINK_BOOST`, `LinkTuneDiag` or
  `…debug.LINKTUNE`, or measuring a drain:** READ ADR 2026-10.2da4 (and work item #120). A link's drain is the host's
  buffer share times the interval, so while a file moves either way, a side whose screen is on asks for
  `CONNECTION_PRIORITY_HIGH` (and the MTU once) through one GATT client per link, and lets go 15 s after neither
  direction streams, or at once on screen-off; a dark side only listens. A link's budget rises to 18 KiB/s (12 past four
  LE connections) only while that link *reported* ≤ 15 ms (`earned`; per link since ADR 2026-10.g24x), else 8jwn's
  4 KiB/s. Dark in release until
  its trial. The trial is `scripts/ble-drain-trial.py --boost on|off`, and the oracle is both phones' HCI snoops read
  by `scripts/btsnoop-drain.py` — the sender's, since only it shows the queue.
- **When touching `BluetoothMeshTransport.superviseAccepted`, `BleAdmissionPolicy`, `teardownLink`, `LinkEvents`,
  `neverSighted`, `LonelyDialPolicy` / `noLinkSince`, or what a held link scores for eviction:** READ ADR
  2026-09.shzv and ADR 2026-09.hj4a. A dialer presence holds is judged by the old tie-break, unchanged — the
  Android mesh must not move. Only an unsighted dialer (an iPhone
  advertises no service data, so it is never sighted and never dialed) is admitted whatever the id order, and its
  second link replaces the first only once the held one is 30 s old. A teardown passes the link it means
  (`only =`) — never re-key it by node id alone — and only a never-sighted link scores the −90 floor. The one
  exception to "larger dials" is hj4a's lonely dial: after 180 s with **no** link (`noLinkSince`, the clock the
  scan's lonely cadence shares) a node dials the strongest larger id it sights, one at a time, and it rests on the unsighted admit:
  a responder that sighted the newcomer refuses it and dials it itself. Its oracle, `bt lonely dial <id> (…)`, is
  keyed by the iOS interop harness — don't reword it.
- **When touching a TX power in `BleAdvertiser` (`presenceParams` / `sideParams` / `codedParams`), an RSSI threshold
  on an advert reading, `PromotionPolicy.msUntilDue`, `nextConnectWaitMs`, `mesh/power/ElapsedWait`, or any wait in
  `BluetoothMeshTransport`'s scan loop, connect loop or dial/HELLO watchdogs or `BleGattPayloadReader`'s give-up:**
  READ ADR 2026-10.ryak and ADR 2026-10.pj9w, and the "Discovery keeps wall time" section of
  `.agents/context/mesh-transport.md`. The presence
  advert is `TX_POWER_HIGH` and every discovery set goes out at its power (pinned by `BleAdvertiserTest`); every −90
  floor reads it, so a new advert threshold is sized against HIGH. A dial gated on the clock gets a wake when it comes
  due — the next sighting is not one on a screen-off phone. The waits a relink waits on (connect loop, dial/HELLO
  watchdogs, scan window and pause, the hunting gap, the GATT payload read's give-up) go through `elapsedWait`, never
  `delay` / `withTimeoutOrNull`, which stop while the CPU sleeps; the settled, linked and relaxed scan gaps keep
  stretching on purpose (power budgets — moving one is a battery change). Measure with `scripts/ble-link-trial.py`.
- **When touching `BleAdvertiser.reassert` / `onAdvertisingEnabled`, `AdvertReassertPolicy`, or the transport's
  `advertLoop` / ACL edge receiver:** READ ADR 2026-10.9utz. The stack re-enables its advertising sets around every
  connection and the controller can refuse that (0x0d), reporting it only for a connection *to* the set — never for
  one the phone made — and never retrying. So the transport enables the live presence set again itself: 2.5 s after
  each ACL edge, on a doubling wait after a reported refusal, on a 10 s (no link) / 60 s net, and at once on `heal()`.
  A stopped set must stay down (`stop()` clears what `reassert` would restart). The ACL receiver is
  `RECEIVER_EXPORTED` on purpose: the Bluetooth app (uid 1002), not `system_server`, sends `ACTION_ACL_*`, so a
  non-exported receiver never fires (the ADR's amendment). Tests: `AdvertReassertPolicyTest`, `BleAdvertiserTest`.
- **When touching `mesh/bluetooth/BleDoorbell`, `DoorbellPolicy`, `Protocol.CAP_DOORBELL`, or what
  `BluetoothMeshTransport.writeOnce` rings:** READ ADR 2026-09.dqvb (companion change A4 for the iOS port). A link
  rings only when the peer's HELLO carries `CAP_DOORBELL`, which Android never claims, so Android↔Android links never
  ring (they open a GATT client only for 2da4's link boost, never with discovery). The doorbell UUIDs are cross-platform law, and the schedule is the port's. `typing`, `blobreq`,
  `blobhave`, `blobhas`, `blobprog`, `blobcancel` and `keyreq` don't ring. The GATT client attaches to the link's own ACL, closes on a 2 s attach timeout, and lives
  and dies with the link. A ring is a write without response, poked at the enqueue, never after the socket write.
  The lookup runs at link-up and each one that finds the doorbell asks for `CONNECTION_PRIORITY_BALANCED`: an
  iPhone central runs the link at a 720 ms supervision timeout, BALANCED carries 5 s (#102, the ADR's amendment).
  The stack's put-back of the central's own timeout can land after that ask, so `DoorbellPolicy.Balanced` asks again,
  on the hidden `onConnectionUpdated` or a 2 s settle check, at most twice a lookup (the put-back amendment).
- **When touching `mesh/bluetooth/GattPayloads`, `BleGattPayloadReader`, `BleAdvertPayload.FLAG_DIALS_GATT_PEERS`,
  `BuildConfig.BLE_GATT_PEERS`, `BleScanner`'s UUID filter, or `BluetoothMeshTransport.onScanResult` / `sight`:**
  READ ADR 2026-09.kwq2 (companion change A3; knit-ios ADR 2026-09.xzpt) and its section in
  `.agents/context/mesh-transport.md`. A foreground iPhone is found by reading its GATT payload and dialed by the
  unchanged rule (larger id dials); `GattPayloads` is the iOS pacing line for line (one read at a time, 12 s,
  30 s / 10 min waits) plus a 64-address LRU, and a payload lives until one of the contract's three rules forgets it: a
  dial failing before its channel opens (never on HANDSHAKE), a HELLO on a link to the address naming another node,
  or 60 s of scanning unheard with no link up. The flag is set only while the reader runs — one build switch gates filter, reader and flag, dark in
  release until the trial. A1 (shzv) stays whole: it is the fallback that makes a clear flag safe.

## Adding an entry

A new decision about this plane gets a bullet here — the symbols that trigger it, the ADR, and the invariant in a
sentence or two — never a bullet in `AGENTS.md`. A symbol listed nowhere is not exempt: its KDoc often carries the
incident that shaped it (`BleAdvertiser`, `BleScanner`, `BluetoothMeshTransport.registerAvailability`).
