# The field log

A record of how a phone's radios behaved over the last three days. Every build can keep one, off until the user
turns it on at **Diagnostics → Field log**, and only the user can share it, from the same screen's **Share as a
file**. Nothing is ever uploaded. Decision record:
ADR 2026-10.a239. Code: `app/src/main/java/app/getknit/knit/fieldlog/`. Reader: `scripts/fieldlog.py`.

## What is never in it

Names, message text, contacts, node ids, group ids, blob hashes, addresses (Bluetooth, Wi-Fi, IP, a LoRa
board's), locations, and the calendar beyond the hour the log starts in. Another phone appears only as a
pseudonym, `p` followed by eight hex digits.

- **Recording side.** `FieldWriter` has no string field, so nothing typed can be written.
- **Export side.** `FieldLogDocument` withholds any line holding a node-id-shaped token and counts it in the
  header.

## Retention

The journal lives in `noBackupFilesDir/fieldlog/`, so it is never in a backup.

- **Segments.** Two append-only segments, `current.jsonl` and `prev.jsonl`. `current` rolls to `prev` at
  2 MiB, so the log holds 4 MiB at most.
- **Age.** Nothing older than 72 h is exported.
- **When it is written.** Events reach disk on the mesh session's minute tick and at stop, so a process death
  loses at most a minute.
- **Pseudonym salt.** The `salt` file holds 32 random bytes. A pseudonym is HMAC-SHA256(salt, node id), cut to
  4 bytes. Clear deletes the salt.
- **Clear and the off switch** delete the journal, the salt and the copy last staged in `cacheDir/fieldlog/` for
  a share.

## File format

UTF-8 text in two parts:

1. **Header.** Lines starting with `# `: the schema version, app, device, SoC, Android, the start hour (UTC,
   rounded down), the span, and notes.
2. **Events.** One JSON object per line: `{"t":<ms>,"e":"<kind>",…}`.
   - `t` is milliseconds since the first line.
   - The export-time summaries (`latency`) carry the export's `t`.
   - A field whose value is unknown is left out, never written as null.

Schema 1. A new field is additive. Renaming or removing a field, or changing a field's meaning, bumps
`FieldLogDocument.SCHEMA`.

## Events

| `e` | When | Fields |
|---|---|---|
| `mesh_on` | Mesh session started | `vc` versionCode |
| `mesh_off` | Mesh session stopped (Stop, pause, restart) | — |
| `power` | Power state changed | `screen`, `charging`, `low` (battery low) |
| `radio` | A radio's health, link count or flags changed; also every 15 min as a sample | `radio` Bluetooth/WifiAware/LoRa, `health` Healthy/ForegroundOnly/Degraded/Unavailable, `linked`, `nearby` (smoothed), `contended` (BT audio), `held` (NAN initiator hold) |
| `counters` | Every 15 min and at stop | Each `MeshMetrics` counter that moved, by its increase since the last `counters`. `…Peak…` values are high-water marks, written when they change. Reason maps are spread as `drop_<REASON>`, `btFail_<REASON>`, `bleSideDrop_<REASON>`, `fastDrop_<REASON>`. Names: `FieldCounters.flatten` |
| `bt_up` | A Bluetooth link came up | `peer`, `dialed`, `dial` (ms, connect → HELLO, ours only), `sighted`, `links` (live after), `relink` (ms since this peer's last `bt_down` in this log) |
| `bt_down` | A Bluetooth link ended | `peer`, `reason` EOF/STOP/EVICTED/ADAPTER_OFF/REPLACED/OTHER, `life` ms, `links` (live after), `phy`, `rssi` (link RSSI; only with a PHY handle) |
| `bt_fail` | A Bluetooth dial failed | `peer`, `reason` (ConnectFailReason), `dur` ms, `streak` |
| `bt_phy` | A link stepped PHY (Coded experiment) | `peer`, `coded` (to Coded, else up to 1M/2M), `rssi` |
| `bt_scan` | Every 5 min | `links`, `le` (LE connections the phone holds), `sighted`, `hunting`, `coded` (Coded mode) |
| `bt_orphan` | A Bluetooth channel's LE connection outlived it past 15 s (#142), once per connection | `peer`, `linked` (the channel was a link; else a refused dialer, a failed HELLO or dial), `armed` (its arm attached: link-up, an iPhone's doorbell, or at the failed channel's end), `tries` (releases; 0 with the arm off, or when handed over at stop), `cleared` (it went down, else still up after the last try), `age` ms (outlived its channel by, to its disconnect or to this line) |
| `bt_link` | Every 5 min, one per link | `peer`, `age` ms, `tx` feeding a file, `rx` receiving one, `phy`, `rssi`, `interval` µs, `mtu`, `earned` (boosted budget) |
| `bt_peer` | Every 5 min, the four strongest sightings | `peer`, `linked`, `rssi` (smoothed, 1M scale), `rssi1m`, `rssiCoded`, `codedLag` ms |
| `nan_up` | A Wi-Fi Aware data path came up | `peer`, `initiator`, `links` |
| `nan_down` | A data path ended | `peer`, `initiator`, `life` ms, `clean` (quiet sync, not a reset) |
| `nan_cycle` | The Aware session was cycled | — |
| `low_power` | Low-power mode went on or off (ADR 2026-10.d44b); while on, Wi-Fi Aware is off and the Aware lines stop | `on`, `why` SWITCH/BATTERY_SAVER (only when on) |
| `lora` | Every 5 min while a board's link is ready | `role`, `snr10` (SNR × 10), `rssi`, `queued`, `heard`, `boards`, `airUsed` / `airBudget` ms (live share) |
| `call_start` | A voice call began here: dialled, alerting, or a glare offer answered at once (docs/VOICE_CALLS.md §3). With no `call_end` after it, the process died in the call | `peer`, `out` (this phone dialled), `walkie` |
| `call_end` | A call ended here; one per call this phone saw, an offer refused on arrival or never rung included | `peer`, `out`, `walkie`, `reason` USER/BUSY/TIMEOUT/NO_ROUTE/MEDIA/ERROR, or OFF (a kind switched off, C-3.3-7), STALE (never rang, C-3.4-1/3), GLARE (our offer gave way, C-3.5-1); `last` the phase it ended from (none for an offer that never rang); `life` ms; `byPeer`, `missed`, `degraded` (dropped to a walkie); from its start: `ring` (caller: first RINGING or ANSWER), `answer`, `setup` (connected) ms; `talk` ms connected; `restarts` (ICE restarts, §3.8), `drops` (path lost while connected); `relay` (relay-only), `direct` (a Wi-Fi Direct group on offer), `directUp` (this side hosted or joined); `turn` servers and `stun` URLs gathered from (live calls); `unreached` (C-3.3-5), `unproven` (C-3.3-6) |
| `call_route` | A live call's media selected a candidate pair | `local`, `remote` HOST/SRFLX/PRFLX/RELAY/OTHER, `proto` (local transport), `net` WIFI/CELLULAR/ETHERNET/VPN/…, `direct` (the Wi-Fi Direct group), `seq` (1 for the session's first pair) |
| `call_media` | 5 s after a live call connects, then every 30 s while it is up | `lost` (reconnecting), `rtt`, `jitter`, `buffer` (jitter buffer) ms; `lossIn10` / `lossOut10` (since the last reading, each way) and `lossCall10` (whole call, received), in tenths of a percent; `send`, `recv` kbit/s; `pkts` heard since the last reading; `mic`, `heard` audio level in ‰; the selected pair as in `call_route` plus `relayProto` (the relay's leg to TURN); `pairs` answered, `pending` unanswered; `micPath` STANDARD/FALLBACK, `micHealth` WATCHING/LIVE/SILENCED/SWITCHED/DEAD (C-3.13-13) |
| `call_direct` | A call's Wi-Fi Direct leg ended (§3.10) | `host`, `end` CALL/BYE/CLOSED/SILENT/NO_LANE/RADIO/LANE_FAILED, from the leg's start `group` and `lane` (up) ms, `life` ms, `tries` (joins) |
| `talk` | A walkie burst (§3.11) went out or played to its end | `dir` OUT/IN; OUT: `chunks`, `ms` of speech, `sent` (chunks a link took), `path` (WIFI_AWARE/BLUETOOTH_CODED/BLUETOOTH/FADING/FLOODED/SPOOL/CAST_UNSEEN/NONE), `held` (Aware link held); IN: `chunks`, `lost` (skipped), `wait` (first arrival → playing), `spread` (first → last arrival) ms |
| `walkie_end` | A walkie session ended | `burstsOut`, `burstsIn`, `chunksOut`, `failed` (no link took it), `chunksIn`, `lost`; medians of the latest chunks: `transit` (two clocks, skew included), `jitter`, `hand` (cut → radio) ms; `frameOut`, `frameIn` average `talk` frame bytes |
| `latency` | At export, per plane | `dir` IN (others' messages reaching us; includes clock skew) or ACK (receipts for ours; one clock), `plane`, `n`, `p50`, `p90`, `max` ms |

The call lines exist only in a build with calls (`-Pcalls=true`, ADR 2026-10.swvp). They carry no call id, SDP,
candidate address, TURN credential or caption: types, counts and times only.

Owed, once nsr7 lands: `feed_end` (sender: size, PHY, budget share, measured drain, first ETA, outcome),
`rx_end` (receiver: size, resume offset, time, progress reports), park waits and ETA promises.

## Adding an event

1. Add a `FieldEvent` that writes only through `FieldWriter`.
2. Add its golden line to `FieldEventSchemaTest`.
3. Add a row to the table above.
4. Teach `scripts/fieldlog.py` if a summary should read it.

A periodic read goes through `FieldRecorder.sampler` and must only read: it runs on the log's own tick.
