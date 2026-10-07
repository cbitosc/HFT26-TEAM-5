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
- **Switching it off.** Turning the log off deletes the journal and the salt.

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
| `bt_link` | Every 5 min, one per link | `peer`, `age` ms, `tx` feeding a file, `rx` receiving one, `phy`, `rssi`, `interval` µs, `mtu`, `earned` (boosted budget) |
| `bt_peer` | Every 5 min, the four strongest sightings | `peer`, `linked`, `rssi` (smoothed, 1M scale), `rssi1m`, `rssiCoded`, `codedLag` ms |
| `nan_up` | A Wi-Fi Aware data path came up | `peer`, `initiator`, `links` |
| `nan_down` | A data path ended | `peer`, `initiator`, `life` ms, `clean` (quiet sync, not a reset) |
| `nan_cycle` | The Aware session was cycled | — |
| `lora` | Every 5 min while a board's link is ready | `role`, `snr10` (SNR × 10), `rssi`, `queued`, `heard`, `boards`, `airUsed` / `airBudget` ms (live share) |
| `latency` | At export, per plane | `dir` IN (others' messages reaching us; includes clock skew) or ACK (receipts for ours; one clock), `plane`, `n`, `p50`, `p90`, `max` ms |

Owed, once nsr7 lands: `feed_end` (sender: size, PHY, budget share, measured drain, first ETA, outcome),
`rx_end` (receiver: size, resume offset, time, progress reports), park waits and ETA promises.

## Adding an event

1. Add a `FieldEvent` that writes only through `FieldWriter`.
2. Add its golden line to `FieldEventSchemaTest`.
3. Add a row to the table above.
4. Teach `scripts/fieldlog.py` if a summary should read it.

A periodic read goes through `FieldRecorder.sampler` and must only read: it runs on the log's own tick.
