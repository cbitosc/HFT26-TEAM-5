#!/usr/bin/env python3
"""Read a Bluetooth HCI snoop and say what held a link's file drain down (#120).

    python3 scripts/btsnoop-drain.py summary SNOOP [SNOOP …] [--logcat LOGCAT …] [--offset auto|SECONDS]
        [--handle N] [--window START,END] [--timeline] [--json]

SNOOP is a btsnoop file: an Android phone's `btsnoop_hci.log` (from `adb bugreport`, under
`FS/data/misc/bluetooth/logs/`; pass `btsnoop_hci.log.last` too, the file Android rolled out, and the records are
read in time order), or the `hci.btsnoop` that `scripts/hci-bridge.py --snoop` writes. LOGCAT is a `logcat -d -v
epoch` dump; its `rx … in <ms>ms` and `file … in <ms>ms` lines (BluetoothMeshTransport) mark each file's window. Give
the peer's dump too when the sender's own line is no window (a feed the pace leaves unbounded is handed to the stack in
milliseconds): its `rx` line times the air. Without it, or with `--window` (Unix seconds), the windows are given by hand; with neither,
every connection's busiest minute is summarised.

For each connection handle it reports who opened it and on what parameters, every parameter, PHY and data-length
change and who asked for it (connection-event lengths included), and the controller's ACL buffers. For each window
it reports the bytes each way, the receive side's packets per connection event and the events it skipped, the send
side's packets held in the controller (sent less Number of Completed Packets), the scan and advertising duty running
beside it, and a verdict:

- `host-starved` — the controller mostly had nothing of this link's to send: the cap is above HCI (the app's pace,
  the stack's queue).
- `host-quota-capped` — the host never put more than a fixed few of this link's packets in the controller although
  it had more to send and the controller had buffers free: AOSP splits the controller's ACL buffers evenly across
  the LE links it holds, idle ones included, so each link's events can carry no more than its share.
- `dle-limited` — the sending side's PDUs never exceeded 27 bytes: Data Length Extension is off that way.
- `phy-limited` — the events are full: the air time of what each carried is most of the interval.
- `interval-capped` — a hard number of packets per event, no events skipped, more than that waiting.
- `controller-scheduled` — data waiting in the controller while events go short or skipped: its scheduler (other
  links, scans, adverts, Wi-Fi coexistence) is the cap.
- `unclear` — none of these fit; read the numbers.

The verdict is a reading aid, not a measurement: the thresholds are in `verdict()`. The receive side's clustering
reads host timestamps, so check that the gap histogram sits on whole intervals before trusting it. Snoop clocks may
be the phone's local time; `--offset auto` aligns the logcat's `bt link up` lines to the snoop's connections on
15-minute steps.

Stdlib only. Tests: `python3 -m unittest scripts/test_btsnoop_drain.py`.
"""

from __future__ import annotations

import argparse
import json
import re
import struct
import sys
from bisect import bisect_left, bisect_right
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

EPOCH_DELTA_US = 0x00DCDDB30F2F8000  # btsnoop counts microseconds from year 0; Unix from 1970
H4_CMD, H4_ACL, H4_EVT = 0x01, 0x02, 0x04
SIG_CID, ATT_CID = 0x0005, 0x0004
PHY_NAMES = {1: "1M", 2: "2M", 3: "CODED"}

# The air a data PDU and its empty answer take, in µs: preamble + access address + header + payload + CRC, then two
# inter-frame spaces and the empty PDU. Unencrypted (Knit's L2CAP channels are insecure), so no MIC.
T_IFS_US = 150
US_PER_BYTE = {1: 8.0, 2: 4.0, 3: 64.0}  # Coded at S=8 is 64 µs/byte, near enough for a verdict
PREAMBLE = {1: 1, 2: 2, 3: 10}


def pdu_air_us(payload: int, phy: int) -> float:
    per = US_PER_BYTE.get(phy, 8.0)
    pre = PREAMBLE.get(phy, 1)
    data = (pre + 4 + 2 + payload + 3) * per
    empty = (pre + 4 + 2 + 3) * per
    return data + T_IFS_US + empty + T_IFS_US


@dataclass
class Record:
    t: float  # Unix seconds, in the snoop's own clock
    sent: bool  # host to controller
    kind: int  # H4 packet type
    data: bytes  # after the type byte
    orig_len: int


def read_btsnoop(path: Path) -> list[Record]:
    raw = path.read_bytes()
    if raw[:8] != b"btsnoop\0":
        raise ValueError(f"{path}: not a btsnoop file")
    _version, datalink = struct.unpack(">II", raw[8:16])
    if datalink not in (1001, 1002):
        raise ValueError(f"{path}: datalink {datalink} is not HCI")
    out: list[Record] = []
    pos = 16
    while pos + 24 <= len(raw):
        orig, incl, flags, _drops, ts = struct.unpack(">IIIIq", raw[pos : pos + 24])
        pos += 24
        pkt = raw[pos : pos + incl]
        pos += incl
        if len(pkt) < incl:
            break  # a file cut mid-record (Android rolls them)
        t = (ts - EPOCH_DELTA_US) / 1e6
        sent = not (flags & 1)
        if datalink == 1002:
            if not pkt:
                continue
            kind, body = pkt[0], pkt[1:]
            orig -= 1
        else:  # un-encapsulated: the flags say command/event or data
            kind = (H4_CMD if sent else H4_EVT) if flags & 2 else H4_ACL
            body = pkt
        out.append(Record(t, sent, kind, body, orig))
    return out


def u16(b: bytes, i: int) -> int:
    return b[i] | (b[i + 1] << 8)


def u24(b: bytes, i: int) -> int:
    return b[i] | (b[i + 1] << 8) | (b[i + 2] << 16)


def addr(b: bytes, i: int) -> str:
    return ":".join(f"{x:02X}" for x in reversed(b[i : i + 6]))


@dataclass
class Conn:
    handle: int
    opened: float
    role: str
    peer: str
    closed: float | None = None
    reason: int | None = None
    # (t, intervalUs, latency, timeoutMs, source)
    params: list[tuple[float, int, int, int, str]] = field(default_factory=list)
    # (t, what, detail) — every ask made of the link: host commands, the peer's L2CAP requests, remote requests
    asks: list[tuple[float, str, dict]] = field(default_factory=list)
    phy: list[tuple[float, int, int, str]] = field(default_factory=list)  # (t, tx, rx, source)
    dle: list[tuple[float, int, int, int, int]] = field(default_factory=list)  # (t, txOct, txTime, rxOct, rxTime)
    tx: list[tuple[float, int]] = field(default_factory=list)  # ACL packets sent: (t, ACL payload length)
    rx: list[tuple[float, int]] = field(default_factory=list)
    done: list[tuple[float, int]] = field(default_factory=list)  # Number of Completed Packets: (t, count)
    cid_bytes: Counter = field(default_factory=Counter)  # (direction, cid) -> bytes
    coc: list[dict] = field(default_factory=list)
    credits: Counter = field(default_factory=Counter)  # direction -> credits granted by flow-control indications
    att_mtu: list[tuple[float, str, int]] = field(default_factory=list)
    cur_cid: dict = field(default_factory=dict)

    def at(self, series: list, t: float):
        """The last entry of [series] at or before [t]."""
        i = bisect_right([e[0] for e in series], t)
        return series[i - 1] if i else None

    def interval_us(self, t: float) -> int | None:
        p = self.at(self.params, t)
        return p[1] if p else None

    def phy_at(self, t: float) -> tuple[int, int]:
        p = self.at(self.phy, t)
        return (p[1], p[2]) if p else (1, 1)


@dataclass
class Snoop:
    conns: list[Conn] = field(default_factory=list)
    live: dict = field(default_factory=dict)  # handle -> Conn
    acl_len: int | None = None
    acl_total: int | None = None
    le_acl_len: int | None = None
    le_acl_total: int | None = None
    scans: list[tuple[float, bool, dict]] = field(default_factory=list)  # (t, enabled, {phy: (intervalMs, windowMs)})
    scan_params: dict = field(default_factory=dict)
    scan_on: bool = False
    adv_intervals: dict = field(default_factory=dict)  # set -> min interval ms
    adv_on: dict = field(default_factory=dict)  # set -> enabled
    adv: list[tuple[float, int]] = field(default_factory=list)  # (t, enabled sets)
    adv_reports: list[float] = field(default_factory=list)
    vendor_events: list[float] = field(default_factory=list)
    pending_create: dict | None = None  # the last create-connection's asks, claimed by the next completion
    first: float | None = None
    last: float | None = None

    @property
    def buffers(self) -> int | None:
        return self.le_acl_total or self.acl_total

    def conn(self, handle: int, t: float) -> Conn:
        c = self.live.get(handle)
        if c is None:  # a link already up when the snoop began
            c = Conn(handle, t, "?", "?")
            self.live[handle] = c
            self.conns.append(c)
        return c

    def feed(self, r: Record) -> None:
        self.first = r.t if self.first is None else self.first
        self.last = r.t
        try:
            if r.kind == H4_CMD and r.sent:
                self.command(r.t, r.data)
            elif r.kind == H4_EVT and not r.sent:
                self.event(r.t, r.data)
            elif r.kind == H4_ACL:
                self.acl(r.t, r.sent, r.data)
        except (IndexError, struct.error):
            pass  # a packet truncated by a filtered snoop: what it held is lost, not the file

    # ---- commands --------------------------------------------------------------------------------------------
    def command(self, t: float, d: bytes) -> None:
        op, p = u16(d, 0), d[3:]
        if op in {0x2013, 0x2020}:  # LE Connection Update / Remote Connection Parameter Request Reply
            c = self.conn(u16(p, 0), t)
            c.asks.append(
                (
                    t,
                    "host update" if op == 0x2013 else "host accepts peer's",
                    {
                        "minUs": u16(p, 2) * 1250,
                        "maxUs": u16(p, 4) * 1250,
                        "latency": u16(p, 6),
                        "timeoutMs": u16(p, 8) * 10,
                        "minCeUs": u16(p, 10) * 625,
                        "maxCeUs": u16(p, 12) * 625,
                    },
                )
            )
        elif op == 0x2043:  # LE Extended Create Connection (v1)
            phys, asks, i = p[9], {}, 10
            for bit, name in ((1, "1M"), (2, "2M"), (4, "CODED")):
                if phys & bit:
                    asks[name] = {
                        "minUs": u16(p, i + 4) * 1250,
                        "maxUs": u16(p, i + 6) * 1250,
                        "latency": u16(p, i + 8),
                        "timeoutMs": u16(p, i + 10) * 10,
                        "minCeUs": u16(p, i + 12) * 625,
                        "maxCeUs": u16(p, i + 14) * 625,
                    }
                    i += 16
            self.pending_create = {"peer": addr(p, 3), "phys": asks}
        elif op == 0x200D:  # LE Create Connection (legacy)
            self.pending_create = {
                "peer": addr(p, 6),
                "phys": {
                    "1M": {
                        "minUs": u16(p, 13) * 1250,
                        "maxUs": u16(p, 15) * 1250,
                        "latency": u16(p, 17),
                        "timeoutMs": u16(p, 19) * 10,
                        "minCeUs": u16(p, 21) * 625,
                        "maxCeUs": u16(p, 23) * 625,
                    }
                },
            }
        elif op == 0x2032:  # LE Set PHY
            c = self.conn(u16(p, 0), t)
            c.asks.append((t, "host set phy", {"all": p[2], "tx": p[3], "rx": p[4], "options": u16(p, 5)}))
        elif op == 0x2022:  # LE Set Data Length
            c = self.conn(u16(p, 0), t)
            c.asks.append((t, "host set data length", {"txOctets": u16(p, 2), "txTime": u16(p, 4)}))
        elif op == 0x200B:  # LE Set Scan Parameters
            self.scan_params = {"1M": (u16(p, 1) * 0.625, u16(p, 3) * 0.625)}
            self.note_scan(t)
        elif op in {0x200C, 0x2042}:  # LE Set (Extended) Scan Enable
            self.scan_on = bool(p[0])
            self.note_scan(t)
        elif op == 0x2041:  # LE Set Extended Scan Parameters
            phys, params, i = p[2], {}, 3
            for bit, name in ((1, "1M"), (4, "CODED")):
                if phys & bit:
                    params[name] = (u16(p, i + 1) * 0.625, u16(p, i + 3) * 0.625)
                    i += 5
            self.scan_params = params
            self.note_scan(t)
        elif op in (0x2036, 0x207F):  # LE Set Extended Advertising Parameters v1/v2
            self.adv_intervals[p[0]] = u24(p, 3) * 0.625
        elif op == 0x2039:  # LE Set Extended Advertising Enable
            on, n = bool(p[0]), p[1]
            if n == 0 and not on:
                self.adv_on.clear()
            for k in range(n):
                self.adv_on[p[2 + 4 * k]] = on
            self.adv.append((t, sum(1 for v in self.adv_on.values() if v)))

    def note_scan(self, t: float) -> None:
        self.scans.append((t, self.scan_on, dict(self.scan_params)))

    # ---- events ----------------------------------------------------------------------------------------------
    def event(self, t: float, d: bytes) -> None:
        code, p = d[0], d[2:]
        if code == 0x0E:  # Command Complete
            self.complete(t, u16(p, 1), p[3:])
        elif code == 0x05:  # Disconnection Complete
            if p[0] == 0 and (c := self.live.pop(u16(p, 1), None)):
                c.closed, c.reason = t, p[3]
        elif code == 0x13:  # Number of Completed Packets
            for k in range(p[0]):
                h, n = u16(p, 1 + 4 * k), u16(p, 3 + 4 * k)
                if h in self.live:
                    self.live[h].done.append((t, n))
        elif code == 0xFF:
            self.vendor_events.append(t)
        elif code == 0x3E:
            self.le_meta(t, p[0], p[1:])

    def complete(self, t: float, op: int, r: bytes) -> None:
        if r and r[0] != 0:
            return
        if op == 0x1005:  # Read Buffer Size
            self.acl_len, self.acl_total = u16(r, 1), u16(r, 4)
        elif op in (0x2002, 0x2060):  # LE Read Buffer Size v1/v2
            self.le_acl_len, self.le_acl_total = u16(r, 1), r[3]
        elif op == 0x2030:  # LE Read PHY
            c = self.conn(u16(r, 1), t)
            c.phy.append((t, r[3], r[4], "read"))

    def le_meta(self, t: float, sub: int, p: bytes) -> None:
        if sub in (0x01, 0x0A, 0x29):  # LE (Enhanced v1/v2) Connection Complete
            if p[0] != 0:
                self.pending_create = None if p[0] != 0x02 else self.pending_create  # 0x02: a cancel, not an answer
                return
            off = 11 if sub == 0x01 else 23  # the enhanced forms carry both resolvable addresses first
            c = Conn(u16(p, 1), t, "central" if p[3] == 0 else "peripheral", addr(p, 5))
            c.params.append((t, u16(p, off) * 1250, u16(p, off + 2), u16(p, off + 4) * 10, "open"))
            if c.role == "central" and self.pending_create:
                c.asks.append((t, "host create", self.pending_create["phys"]))
            self.pending_create = None
            self.live[c.handle] = c
            self.conns.append(c)
        elif sub == 0x03:  # LE Connection Update Complete
            if p[0] == 0:
                c = self.conn(u16(p, 1), t)
                c.params.append((t, u16(p, 3) * 1250, u16(p, 5), u16(p, 7) * 10, "update"))
        elif sub == 0x06:  # LE Remote Connection Parameter Request
            c = self.conn(u16(p, 0), t)
            c.asks.append(
                (
                    t,
                    "peer requests (LL)",
                    {"minUs": u16(p, 2) * 1250, "maxUs": u16(p, 4) * 1250, "latency": u16(p, 6), "timeoutMs": u16(p, 8) * 10},
                )
            )
        elif sub == 0x07:  # LE Data Length Change
            c = self.conn(u16(p, 0), t)
            c.dle.append((t, u16(p, 2), u16(p, 4), u16(p, 6), u16(p, 8)))
        elif sub == 0x0C:  # LE PHY Update Complete
            if p[0] == 0:
                c = self.conn(u16(p, 1), t)
                c.phy.append((t, p[3], p[4], "update"))
        elif sub == 0x23:  # LE Subrate Change
            if p[0] == 0:
                c = self.conn(u16(p, 1), t)
                c.asks.append((t, "subrate", {"factor": u16(p, 3), "latency": u16(p, 5), "continuation": u16(p, 7)}))
        elif sub in (0x02, 0x0D):  # LE (Extended) Advertising Report
            self.adv_reports.extend([t] * p[0])

    # ---- data ------------------------------------------------------------------------------------------------
    def acl(self, t: float, sent: bool, d: bytes) -> None:
        hf, length = u16(d, 0), u16(d, 2)
        handle, pb = hf & 0x0FFF, (hf >> 12) & 0x3
        c = self.conn(handle, t)
        way = "tx" if sent else "rx"
        (c.tx if sent else c.rx).append((t, length))
        payload = d[4:]
        if pb in (0b00, 0b10):  # the first fragment of an L2CAP PDU
            if len(payload) < 4:
                return
            cid = u16(payload, 2)
            c.cur_cid[way] = cid
            c.cid_bytes[(way, cid)] += length
            if cid == SIG_CID:
                self.signalling(t, c, way, payload[4:])
            elif cid == ATT_CID and len(payload) >= 7 and payload[4] in (0x02, 0x03):
                c.att_mtu.append((t, "request" if payload[4] == 0x02 else "response", u16(payload, 5)))
        else:
            c.cid_bytes[(way, c.cur_cid.get(way, -1))] += length

    def signalling(self, t: float, c: Conn, way: str, s: bytes) -> None:
        code, data = s[0], s[4:]
        who = "host" if way == "tx" else "peer"
        if code == 0x12:  # Connection Parameter Update Request
            c.asks.append(
                (
                    t,
                    f"{who} requests (L2CAP)",
                    {
                        "minUs": u16(data, 0) * 1250,
                        "maxUs": u16(data, 2) * 1250,
                        "latency": u16(data, 4),
                        "timeoutMs": u16(data, 6) * 10,
                    },
                )
            )
        elif code == 0x13:
            c.asks.append((t, f"{who} answers (L2CAP)", {"result": u16(data, 0)}))
        elif code == 0x14:  # LE Credit Based Connection Request
            c.coc.append(
                {"t": t, "who": who, "what": "request", "psm": u16(data, 0), "mtu": u16(data, 4), "mps": u16(data, 6), "credits": u16(data, 8)}
            )
        elif code == 0x15:
            c.coc.append(
                {"t": t, "who": who, "what": "response", "mtu": u16(data, 2), "mps": u16(data, 4), "credits": u16(data, 6), "result": u16(data, 8)}
            )
        elif code == 0x16:  # Flow Control Credit Indication
            c.credits[way] += u16(data, 2)


def parse(records: list[Record]) -> Snoop:
    s = Snoop()
    for r in records:
        s.feed(r)
    return s


# ---- logcat ---------------------------------------------------------------------------------------------------
LINE = re.compile(r"^\s*(\d+\.\d+)\s+\d+\s+\d+\s+([VDIWEF])\s+([^:]+?)\s*: (.*)$")
RX_DONE = re.compile(r"^rx (\S+) (\d+)B in (\d+)ms ← (\S+)")
TX_DONE = re.compile(r"^file (\S+) (\d+)B in (\d+)ms @(-?\d+) → (\S+)")
LINK_UP = re.compile(r"^bt link up: (\S+) \(")
TAG = "BluetoothMeshTransport"


@dataclass
class Window:
    start: float
    end: float
    label: str
    peer: str = ""
    bytes: int = 0
    side: str = ""  # rx: this phone received; tx: this phone sent


def read_logcat(path: Path) -> tuple[list[Window], list[float]]:
    windows, ups = [], []
    for line in path.read_text(errors="replace").splitlines():
        m = LINE.match(line)
        if not m or m.group(3) != TAG:
            continue
        t, msg = float(m.group(1)), m.group(4)
        if r := RX_DONE.match(msg):
            ms = int(r.group(3))
            windows.append(Window(t - ms / 1000, t, f"rx {r.group(1)}", r.group(4), int(r.group(2)), "rx"))
        elif r := TX_DONE.match(msg):
            ms = int(r.group(3))
            windows.append(Window(t - ms / 1000, t, f"file {r.group(1)} @{r.group(4)}", r.group(5), int(r.group(2)), "tx"))
        elif LINK_UP.match(msg):
            ups.append(t)
    return windows, ups


def auto_offset(snoop: Snoop, ups: list[float]) -> float:
    """The whole number of 15-minute steps that best lays the logcat's link-ups on the snoop's connections."""
    opens = sorted(c.opened for c in snoop.conns if c.role != "?")
    if not opens or not ups:
        return 0.0

    def score(off: float) -> int:
        hits = 0
        for u in ups:
            i = bisect_left(opens, u + off)
            near = [abs(opens[j] - (u + off)) for j in (i - 1, i) if 0 <= j < len(opens)]
            hits += bool(near) and min(near) < 5.0
        return hits

    return max((k * 900.0 for k in range(-56, 57)), key=lambda off: (score(off), -abs(off)))


# ---- analysis -------------------------------------------------------------------------------------------------
def pct(xs: list[float], q: float) -> float | None:
    if not xs:
        return None
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(q * len(xs)))]


def in_window(series: list[tuple], a: float, b: float) -> list[tuple]:
    ts = [e[0] for e in series]
    return series[bisect_left(ts, a) : bisect_right(ts, b)]


def outstanding(c: Conn, a: float, b: float) -> list[tuple[float, int]]:
    """This link's packets held in the controller over [a, b], as time-weighted (duration, depth) pieces."""
    steps = sorted([(t, 1) for t, _ in c.tx] + [(t, -n) for t, n in c.done])
    depth, pieces, last = 0, [], None
    for t, d in steps:
        if t > b:
            break
        if last is not None and t > a:
            pieces.append((t - max(last, a), depth))
        depth = max(0, depth + d)
        last = t
    if last is not None and last < b:
        pieces.append((b - max(last, a), depth))
    return [(w, d) for w, d in pieces if w > 0]


def weighted(pieces: list[tuple[float, int]], q: float) -> int | None:
    total = sum(w for w, _ in pieces)
    if not total:
        return None
    acc = 0.0
    for w, d in sorted(pieces, key=lambda p: p[1]):
        acc += w
        if acc >= q * total:
            return d
    return pieces[-1][1]


def events(packets: list[tuple[float, int]], interval_s: float) -> list[list[tuple[float, int]]]:
    """Received packets grouped into connection events: a new event after a gap of more than half an interval."""
    groups: list[list[tuple[float, int]]] = []
    for p in packets:
        if groups and p[0] - groups[-1][-1][0] <= interval_s / 2:
            groups[-1].append(p)
        else:
            groups.append([p])
    return groups


def scan_share(snoop: Snoop, a: float, b: float) -> dict:
    """The share of [a, b] a scan was enabled, and the duty (window / interval) per PHY while it was."""
    on_s, duty = 0.0, {}
    state = None
    for t, on, params in snoop.scans + [(b, False, {})]:
        if state and state[1] and t > a:
            span = min(t, b) - max(state[0], a)
            if span > 0:
                on_s += span
                for phy, (iv, win) in state[2].items():
                    duty[phy] = duty.get(phy, 0.0) + span * (win / iv if iv else 0)
        state = (t, on, params)
        if t >= b:
            break
    span = b - a
    return {
        "enabledShare": round(on_s / span, 2) if span else None,
        "duty": {phy: round(v / on_s, 2) for phy, v in duty.items()} if on_s else {},
    }


def analyse(snoop: Snoop, c: Conn, w: Window) -> dict:
    a, b = w.start, w.end
    span = max(b - a, 1e-6)
    iv = c.interval_us(a) or 7500
    tx_phy, rx_phy = c.phy_at(a)
    tx, rx = in_window(c.tx, a, b), in_window(c.rx, a, b)
    # Which way the file went, as this phone saw it: the receiver's snoop reads the events, the sender's the queue.
    sending = sum(n for _, n in tx) >= sum(n for _, n in rx)
    out: dict = {
        "window": w.label,
        "peer": w.peer,
        "start": round(a, 3),
        "seconds": round(span, 1),
        "handle": c.handle,
        "role": c.role,
        "side": "sender" if sending else "receiver",
        "intervalUs": iv,
        "phy": f"tx {PHY_NAMES.get(tx_phy, tx_phy)} rx {PHY_NAMES.get(rx_phy, rx_phy)}",
        "txBytesPerS": round(sum(n for _, n in tx) / span),
        "rxBytesPerS": round(sum(n for _, n in rx) / span),
        "maxTxFragment": max((n for _, n in tx), default=0),
        "maxRxFragment": max((n for _, n in rx), default=0),
    }
    dle = c.at(c.dle, a)
    if dle:
        out["dle"] = {"txOctets": dle[1], "rxOctets": dle[3]}
    # The receive side: what each connection event carried.
    if not sending and len(rx) >= 3:
        evs = events(rx, iv / 1e6)
        counts = [len(e) for e in evs]
        gaps = [round((evs[i + 1][0][0] - evs[i][-1][0]) / (iv / 1e6)) for i in range(len(evs) - 1)]
        busy = [g for g in gaps if g <= 50]  # longer is idle, not skipping
        air = [sum(pdu_air_us(n, rx_phy) for _, n in e) / iv for e in evs]
        out["rxEvents"] = {
            "events": len(evs),
            "packetsP50": pct(counts, 0.5),
            "packetsP90": pct(counts, 0.9),
            "packetsMax": max(counts),
            "bytesPerEventP50": pct([sum(n for _, n in e) for e in evs], 0.5),
            "gapIntervals": dict(sorted(Counter(min(g, 10) for g in busy).items())),
            "skippedShare": round(sum(1 for g in busy if g >= 2) / len(busy), 2) if busy else None,
            "airShareP50": round(pct(air, 0.5), 2),
        }
    # The send side: what waited in the controller, and how the controller reported it gone. Controllers batch
    # Number of Completed Packets differently (per event, or on a timer), so the report cadence is a weaker read of
    # the events than the receiver's own snoop.
    if sending and len(tx) >= 3:
        pieces = outstanding(c, a, b)
        total = sum(wt for wt, _ in pieces) or 1.0
        buffers = snoop.buffers
        done = in_window(c.done, a, b)
        gaps = [round((done[i + 1][0] - done[i][0]) / (iv / 1e6)) for i in range(len(done) - 1)]
        busy = [g for g in gaps if g <= 50]
        counts = [n for _, n in done]
        out["txQueue"] = {
            "p50": weighted(pieces, 0.5),
            "p90": weighted(pieces, 0.9),
            "max": max((d for _, d in pieces), default=0),
            "zeroShare": round(sum(wt for wt, d in pieces if d == 0) / total, 2),
            "fullShare": round(sum(wt for wt, d in pieces if buffers and d >= buffers) / total, 2) if buffers else None,
            "controllerBuffers": buffers,
            "reports": len(done),
            "completedPerReportP50": pct(counts, 0.5),
            "completedPerReportP90": pct(counts, 0.9),
            "completedPerReportMax": max(counts, default=None),
            "reportGapIntervals": dict(sorted(Counter(min(g, 10) for g in busy).items())),
            "skippedShare": round(sum(1 for g in busy if g >= 2) / len(busy), 2) if busy else None,
        }
    out["scan"] = scan_share(snoop, a, b)
    adv = [n for t, n in snoop.adv if t <= b]
    out["advSetsEnabled"] = adv[-1] if adv else None
    out["advReportsPerS"] = round(len(in_window([(t,) for t in snoop.adv_reports], a, b)) / span, 1)
    others = [o for o in snoop.conns if o is not c and o.opened <= b and (o.closed is None or o.closed >= a)]
    out["otherLinks"] = [
        {"handle": o.handle, "bytes": sum(n for _, n in in_window(o.tx, a, b)) + sum(n for _, n in in_window(o.rx, a, b))}
        for o in others
    ]
    out["vendorEventsPerS"] = round(len(in_window([(t,) for t in snoop.vendor_events], a, b)) / span, 1)
    out["verdict"] = verdict(out)
    return out


def verdict(o: dict) -> str:
    """The reading the numbers point at. The receiver's snoop cannot see the sender's queue, so its verdicts say so."""
    q, e = o.get("txQueue"), o.get("rxEvents")
    frag = o["maxTxFragment"] if o["side"] == "sender" else o["maxRxFragment"]
    if q and q["zeroShare"] >= 0.5:
        return "host-starved"
    if 0 < frag <= 27:
        return "dle-limited"
    if e and e["airShareP50"] is not None and e["airShareP50"] >= 0.7:
        return "phy-limited"
    if e:
        cap = e["packetsP90"] == e["packetsMax"] and (e["skippedShare"] or 0) < 0.1
        if cap:
            return "interval-capped (if the sender's queue held more; check its snoop)"
        if (e["skippedShare"] or 0) >= 0.1:
            return "controller-scheduled (or the sender ran dry; check its snoop)"
        return "unclear"
    if q:
        buffers = q["controllerBuffers"] or 0
        if q["p50"] and q["p50"] == q["max"] and q["max"] < buffers and q["zeroShare"] < 0.2:
            return f"host-quota-capped ({q['max']} in flight of {buffers} buffers)"
        waiting = (q["p50"] or 0) >= 2 or (q["fullShare"] or 0) >= 0.2
        steady = q["completedPerReportP90"] == q["completedPerReportMax"] and (q["skippedShare"] or 0) < 0.1
        if waiting and steady and (q["p50"] or 0) > (q["completedPerReportMax"] or 0):
            return "interval-capped"
        if waiting:
            return "controller-scheduled"
    return "unclear"


def busiest_minute(c: Conn) -> Window | None:
    data = sorted(c.tx + c.rx)
    if not data:
        return None
    ts = [t for t, _ in data]
    best, at = 0, data[0][0]
    for i, t in enumerate(ts):
        j = bisect_right(ts, t + 60)
        n = sum(x for _, x in data[i:j])
        if n > best:
            best, at = n, t
    return Window(at, at + 60, f"busiest minute of handle {c.handle}")


def conn_for(snoop: Snoop, w: Window) -> Conn | None:
    """The link that carried [w]: the one that moved the most bytes inside it."""
    def moved(c: Conn) -> int:
        return sum(n for _, n in in_window(c.tx, w.start, w.end)) + sum(n for _, n in in_window(c.rx, w.start, w.end))

    best = max(snoop.conns, key=moved, default=None)
    return best if best and moved(best) else None


def describe(c: Conn) -> dict:
    return {
        "handle": c.handle,
        "role": c.role,
        "peer": c.peer,
        "opened": round(c.opened, 3),
        "closed": round(c.closed, 3) if c.closed else None,
        "reason": c.reason,
        "params": [{"t": round(t, 3), "intervalUs": i, "latency": lat, "timeoutMs": to, "source": s} for t, i, lat, to, s in c.params],
        "asks": [{"t": round(t, 3), "what": w, **d} for t, w, d in c.asks],
        "phy": [{"t": round(t, 3), "tx": PHY_NAMES.get(tx, tx), "rx": PHY_NAMES.get(rx, rx), "source": s} for t, tx, rx, s in c.phy],
        "dle": [{"t": round(t, 3), "txOctets": to, "txTime": tt, "rxOctets": ro, "rxTime": rt} for t, to, tt, ro, rt in c.dle],
        "coc": c.coc,
        "attMtu": [{"t": round(t, 3), "what": w, "mtu": m} for t, w, m in c.att_mtu],
        "creditsGranted": dict(c.credits),
        "bytesByCid": {f"{way}:{cid:#06x}": n for (way, cid), n in sorted(c.cid_bytes.items())},
    }


def summary(args: argparse.Namespace) -> int:
    snoop = parse(sorted((r for path in args.snoop for r in read_btsnoop(Path(path))), key=lambda r: r.t))
    windows: list[Window] = []
    offset = 0.0
    if args.logcat:
        ups: list[float] = []
        for path in args.logcat:
            w, u = read_logcat(Path(path))
            windows += w
            ups += u
        offset = auto_offset(snoop, ups) if args.offset == "auto" else float(args.offset)
        for w in windows:
            w.start += offset
            w.end += offset
    if args.window:
        a, b = (float(x) for x in args.window.split(","))
        windows = [Window(a + offset, b + offset, "--window")]
    conns = [c for c in snoop.conns if args.handle is None or c.handle == args.handle]
    report: dict = {
        "snoop": {"first": snoop.first, "last": snoop.last, "offset": offset, "aclBuffers": snoop.buffers, "aclLen": snoop.le_acl_len or snoop.acl_len},
        "links": [describe(c) for c in conns],
        "windows": [],
    }
    if not windows:
        windows = [w for w in (busiest_minute(c) for c in conns) if w]
    for w in windows:
        if snoop.first is None or w.end < snoop.first or w.start > snoop.last:
            report["windows"].append({"window": w.label, "start": round(w.start, 3), "verdict": "outside the snoop (rolled over?)"})
            continue
        c = next((x for x in conns if args.handle is not None), None) or conn_for(snoop, w)
        if c is None:
            report["windows"].append({"window": w.label, "start": round(w.start, 3), "verdict": "no link moved bytes"})
            continue
        report["windows"].append(analyse(snoop, c, w))
    if args.json:
        json.dump(report, sys.stdout, indent=2, default=str)
        print()
    else:
        print_text(report, args.timeline)
    return 0


def print_text(report: dict, timeline: bool) -> None:
    s = report["snoop"]
    print(f"snoop {s['first']}..{s['last']} offset={s['offset']}s controller ACL buffers={s['aclBuffers']}×{s['aclLen']}B")
    for c in report["links"]:
        opened = c["params"][0] if c["params"] else {}
        print(
            f"\nhandle {c['handle']} {c['role']} peer={c['peer']} opened={c['opened']} "
            f"interval={opened.get('intervalUs')}us latency={opened.get('latency')} timeout={opened.get('timeoutMs')}ms "
            f"closed={c['closed']} reason={c['reason']}"
        )
        if timeline:
            for key in ("params", "asks", "phy", "dle", "coc", "attMtu"):
                for e in c[key]:
                    print(f"  {key:6} {e}")
        print(f"  bytes by cid: {c['bytesByCid']}  credits granted: {c['creditsGranted']}")
    for w in report["windows"]:
        print(f"\n[{w.get('verdict')}] {w['window']} peer={w.get('peer', '')} start={w['start']}")
        for k, v in w.items():
            if k not in ("window", "peer", "start", "verdict"):
                print(f"  {k}: {v}")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("summary", help="per-link timelines and per-window verdicts")
    s.add_argument("snoop", nargs="+", help="btsnoop files, the rolled `.last` one included")
    s.add_argument("--logcat", action="append", help="a `logcat -d -v epoch` dump; repeat for the peer's")
    s.add_argument("--offset", default="auto", help="seconds to add to logcat times, or auto (15-minute steps)")
    s.add_argument("--handle", type=int, help="only this connection handle")
    s.add_argument("--window", help="START,END in Unix seconds (logcat clock), instead of the logcat's windows")
    s.add_argument("--timeline", action="store_true", help="print every parameter, PHY and data-length event")
    s.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)
    return summary(args)


if __name__ == "__main__":
    sys.exit(main())
