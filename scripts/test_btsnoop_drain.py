"""Unit tests for scripts/btsnoop-drain.py. Run: python3 -m unittest scripts/test_btsnoop_drain.py"""

from __future__ import annotations

import importlib.util
import struct
import sys
import tempfile
import unittest
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location("btsnoop_drain", Path(__file__).with_name("btsnoop-drain.py"))
drain = importlib.util.module_from_spec(_SPEC)
sys.modules["btsnoop_drain"] = drain  # dataclasses look their module up by name
assert _SPEC.loader is not None
_SPEC.loader.exec_module(drain)

T0 = 1_790_000_000.0
PEER = bytes([0x31, 0x12, 0xA1, 0x1B, 0x5B, 0xF8])  # F8:5B:1B:A1:12:31, little-endian on the wire


def le16(*vals: int) -> bytes:
    return b"".join(struct.pack("<H", v) for v in vals)


def cmd(op: int, params: bytes) -> tuple[int, bool, bytes]:
    return (drain.H4_CMD, True, struct.pack("<HB", op, len(params)) + params)


def evt(code: int, params: bytes) -> tuple[int, bool, bytes]:
    return (drain.H4_EVT, False, bytes([code, len(params)]) + params)


def le(sub: int, params: bytes) -> tuple[int, bool, bytes]:
    return evt(0x3E, bytes([sub]) + params)


def complete(op: int, ret: bytes) -> tuple[int, bool, bytes]:
    return evt(0x0E, bytes([1]) + struct.pack("<H", op) + ret)


def acl(handle: int, payload: bytes, sent: bool, pb: int = 0b10, length: int | None = None) -> tuple[int, bool, bytes]:
    hdr = struct.pack("<HH", handle | (pb << 12), len(payload) if length is None else length)
    return (drain.H4_ACL, sent, hdr + payload)


def l2cap(cid: int, body: bytes, total: int | None = None) -> bytes:
    return struct.pack("<HH", len(body) if total is None else total, cid) + body


def conn_complete(handle: int, role: int, interval: int, latency: int = 0, timeout: int = 500) -> tuple[int, bool, bytes]:
    return le(0x01, bytes([0]) + le16(handle) + bytes([role, 0]) + PEER + le16(interval, latency, timeout) + bytes([0]))


def nocp(handle: int, n: int) -> tuple[int, bool, bytes]:
    return evt(0x13, bytes([1]) + le16(handle, n))


def snoop_file(records: list[tuple[float, tuple[int, bool, bytes]]], truncate: dict[int, int] | None = None) -> Path:
    """A btsnoop file (datalink 1002, H4) of (time, packet) records; [truncate] cuts record i to that many bytes."""
    out = bytearray(b"btsnoop\0" + struct.pack(">II", 1, 1002))
    for i, (t, (kind, sent, body)) in enumerate(records):
        pkt = bytes([kind]) + body
        incl = pkt[: truncate[i]] if truncate and i in truncate else pkt
        flags = (0 if sent else 1) | (0 if kind == drain.H4_ACL else 2)
        ts = round(t * 1e6) + drain.EPOCH_DELTA_US
        out += struct.pack(">IIIIq", len(pkt), len(incl), flags, 0, ts) + incl
    with tempfile.NamedTemporaryFile(suffix=".log", delete=False) as f:
        f.write(bytes(out))
    return Path(f.name)


def parsed(records, truncate=None) -> drain.Snoop:
    return drain.parse(drain.read_btsnoop(snoop_file(records, truncate)))


class ReadTest(unittest.TestCase):
    def test_a_filtered_record_counts_its_acl_length_not_what_was_kept(self):
        payload = l2cap(0x0040, bytes(247))
        s = parsed([(T0, conn_complete(64, 0, 24)), (T0 + 1, acl(64, payload, sent=True))], truncate={1: 9})
        c = s.conns[0]
        self.assertEqual([(T0 + 1, 251)], [(round(t, 3), n) for t, n in c.tx])
        self.assertEqual(251, c.cid_bytes[("tx", 0x0040)])

    def test_the_timestamp_is_unix_seconds(self):
        s = parsed([(T0 + 0.25, conn_complete(64, 0, 24))])
        self.assertAlmostEqual(T0 + 0.25, s.conns[0].opened, places=4)


class ConnectionTest(unittest.TestCase):
    def test_legacy_and_enhanced_completions_read_the_same_parameters(self):
        rpa = bytes(12)
        enhanced = le(0x0A, bytes([0]) + le16(65) + bytes([1, 0]) + PEER + rpa + le16(40, 0, 500) + bytes([0]))
        enhanced_v2 = le(0x29, bytes([0]) + le16(66) + bytes([0, 0]) + PEER + rpa + le16(12, 0, 500) + bytes([0, 0xFF]) + le16(0xFFFF))
        s = parsed([(T0, conn_complete(64, 0, 24)), (T0 + 1, enhanced), (T0 + 2, enhanced_v2)])
        got = [(c.handle, c.role, c.peer, c.params[0][1:4]) for c in s.conns]
        self.assertEqual(
            [
                (64, "central", "F8:5B:1B:A1:12:31", (30000, 0, 5000)),
                (65, "peripheral", "F8:5B:1B:A1:12:31", (50000, 0, 5000)),
                (66, "central", "F8:5B:1B:A1:12:31", (15000, 0, 5000)),
            ],
            got,
        )

    def test_a_failed_completion_makes_no_link(self):
        s = parsed([(T0, le(0x01, bytes([0x3E]) + bytes(18)))])
        self.assertEqual([], s.conns)

    def test_updates_phy_and_data_length_build_the_timeline(self):
        s = parsed(
            [
                (T0, conn_complete(64, 1, 24)),
                (T0 + 1, le(0x03, bytes([0]) + le16(64, 12, 0, 500))),
                (T0 + 2, le(0x0C, bytes([0]) + le16(64) + bytes([2, 2]))),
                (T0 + 3, le(0x07, le16(64, 251, 2120, 27, 328))),
                (T0 + 4, le(0x03, bytes([0x3B]) + le16(64, 6, 0, 72))),  # a failed update moves nothing
            ]
        )
        c = s.conns[0]
        self.assertEqual([30000, 15000], [p[1] for p in c.params])
        self.assertEqual(15000, c.interval_us(T0 + 10))
        self.assertEqual((2, 2), c.phy_at(T0 + 10))
        self.assertEqual([(251, 27)], [(d[1], d[3]) for d in c.dle])

    def test_a_create_connections_event_lengths_are_claimed_by_the_link_it_made(self):
        per_phy = le16(96, 48, 8, 16, 0, 500, 2, 1200)  # scan iv/win, 10–20 ms, CE 1.25 ms .. 750 ms
        create = cmd(0x2043, bytes([0, 1, 1]) + PEER + bytes([0b001]) + per_phy)
        s = parsed([(T0, create), (T0 + 0.2, conn_complete(64, 0, 12))])
        what, ask = s.conns[0].asks[0][1:]
        self.assertEqual("host create", what)
        self.assertEqual({"minUs": 10000, "maxUs": 20000, "latency": 0, "timeoutMs": 5000, "minCeUs": 1250, "maxCeUs": 750000}, ask["1M"])

    def test_host_and_peer_asks_are_both_recorded(self):
        update = cmd(0x2013, le16(64, 9, 12, 0, 500, 0, 0))
        sig = l2cap(drain.SIG_CID, bytes([0x12, 1]) + le16(8) + le16(24, 40, 0, 500))
        s = parsed([(T0, conn_complete(64, 0, 24)), (T0 + 1, update), (T0 + 2, acl(64, sig, sent=False))])
        asks = [(a[1], a[2].get("maxUs")) for a in s.conns[0].asks]
        self.assertEqual([("host update", 15000), ("peer requests (L2CAP)", 50000)], asks)

    def test_disconnection_closes_only_on_success(self):
        s = parsed(
            [
                (T0, conn_complete(64, 0, 24)),
                (T0 + 1, evt(0x05, bytes([0x0C]) + le16(64) + bytes([0x13]))),
                (T0 + 2, evt(0x05, bytes([0]) + le16(64) + bytes([0x08]))),
            ]
        )
        self.assertEqual((T0 + 2, 8), (round(s.conns[0].closed, 3), s.conns[0].reason))


class BufferAndChannelTest(unittest.TestCase):
    def test_le_buffers_win_over_the_shared_ones(self):
        s = parsed([(T0, complete(0x1005, bytes([0]) + le16(1021) + bytes([64]) + le16(8, 8))), (T0, complete(0x2060, bytes([0]) + le16(251) + bytes([15]) + le16(0) + bytes([0])))])
        self.assertEqual(15, s.buffers)
        shared = parsed([(T0, complete(0x1005, bytes([0]) + le16(1021) + bytes([64]) + le16(8, 8)))])
        self.assertEqual(8, shared.buffers)

    def test_a_continuation_fragment_counts_to_the_channel_its_pdu_began_on(self):
        start = l2cap(0x0045, bytes(247), total=400)
        s = parsed(
            [
                (T0, conn_complete(64, 0, 24)),
                (T0 + 0.01, acl(64, start, sent=False)),
                (T0 + 0.02, acl(64, bytes(153), sent=False, pb=0b01)),
            ]
        )
        self.assertEqual(404, s.conns[0].cid_bytes[("rx", 0x0045)])

    def test_the_credit_channel_open_is_read(self):
        req = l2cap(drain.SIG_CID, bytes([0x14, 2]) + le16(10) + le16(0x80, 0x40, 65535, 251, 65535))
        s = parsed([(T0, conn_complete(64, 1, 24)), (T0 + 1, acl(64, req, sent=False))])
        self.assertEqual({"who": "peer", "what": "request", "psm": 0x80, "mtu": 65535, "mps": 251, "credits": 65535}, {k: v for k, v in s.conns[0].coc[0].items() if k != "t"})


class WindowTest(unittest.TestCase):
    def sender(self, sends: int, done_every: float, per_report: int) -> drain.Snoop:
        recs = [(T0, conn_complete(64, 0, 24)), (T0, complete(0x2002, bytes([0]) + le16(251) + bytes([15])))]
        recs += [(T0 + 0.001 * i, acl(64, l2cap(0x40, bytes(247)), sent=True)) for i in range(sends)]
        k, t = 0, T0 + done_every
        while k < sends:
            recs.append((t, nocp(64, per_report)))
            k += per_report
            t += done_every
        return parsed(sorted(recs, key=lambda r: r[0]))

    def test_a_queue_that_waits_on_steady_reports_is_interval_capped(self):
        s = self.sender(sends=60, done_every=0.030, per_report=2)
        o = drain.analyse(s, s.conns[0], drain.Window(T0, T0 + 0.9, "w"))
        self.assertEqual("sender", o["side"])
        self.assertGreaterEqual(o["txQueue"]["p50"], 10)
        self.assertEqual(2, o["txQueue"]["completedPerReportMax"])
        self.assertEqual("interval-capped", o["verdict"])

    def test_a_queue_held_flat_below_the_buffers_is_host_quota_capped(self):
        recs = [(T0, conn_complete(64, 0, 36)), (T0, complete(0x2002, bytes([0]) + le16(251) + bytes([13])))]
        recs += [(T0 + 0.001 * i, acl(64, l2cap(0x40, bytes(247)), sent=True)) for i in range(3)]
        for k in range(30):  # two of three complete each 45 ms event, and the host tops the link back up to three
            t = T0 + 0.045 * (k + 1)
            recs += [(t, nocp(64, 2)), (t + 0.001, acl(64, l2cap(0x40, bytes(247)), sent=True)), (t + 0.002, acl(64, l2cap(0x40, bytes(247)), sent=True))]
        s = parsed(recs)
        o = drain.analyse(s, s.conns[0], drain.Window(T0 + 0.01, T0 + 1.3, "w"))
        self.assertEqual("host-quota-capped (3 in flight of 13 buffers)", o["verdict"])

    def test_a_queue_that_sits_empty_is_host_starved(self):
        recs = [(T0, conn_complete(64, 0, 24))]
        for i in range(10):
            recs += [(T0 + i, acl(64, l2cap(0x40, bytes(247)), sent=True)), (T0 + i + 0.03, nocp(64, 1))]
        s = parsed(recs)
        o = drain.analyse(s, s.conns[0], drain.Window(T0, T0 + 10, "w"))
        self.assertEqual("host-starved", o["verdict"])

    def test_the_receive_side_counts_packets_per_event_and_the_events_skipped(self):
        recs = [(T0, conn_complete(64, 1, 24)), (T0, le(0x07, le16(64, 251, 2120, 251, 2120)))]
        t = T0
        for i in range(40):
            t += 0.030 * (2 if i % 2 else 1)  # every other gap skips an event
            recs += [(t, acl(64, l2cap(0x40, bytes(247)), sent=False)), (t + 0.002, acl(64, l2cap(0x40, bytes(247)), sent=False))]
        s = parsed(recs)
        o = drain.analyse(s, s.conns[0], drain.Window(T0, t + 0.01, "w"))
        e = o["rxEvents"]
        self.assertEqual("receiver", o["side"])
        self.assertEqual((40, 2, 2), (e["events"], e["packetsP50"], e["packetsMax"]))
        self.assertEqual({1: 19, 2: 20}, e["gapIntervals"])
        self.assertTrue(o["verdict"].startswith("controller-scheduled"))

    def test_27_byte_pdus_are_dle_limited(self):
        recs = [(T0, conn_complete(64, 1, 24))]
        recs += [(T0 + 0.03 * i, acl(64, l2cap(0x40, bytes(23)), sent=False)) for i in range(30)]
        s = parsed(recs)
        o = drain.analyse(s, s.conns[0], drain.Window(T0, T0 + 1, "w"))
        self.assertEqual("dle-limited", o["verdict"])

    def test_scan_duty_is_read_per_phy(self):
        params = cmd(0x2041, bytes([1, 0, 0b101]) + bytes([1]) + le16(160, 40) + bytes([1]) + le16(160, 80))
        s = parsed([(T0, conn_complete(64, 0, 24)), (T0, params), (T0 + 5, cmd(0x2042, bytes([1, 0]) + le16(0, 0)))])
        self.assertEqual({"enabledShare": 0.5, "duty": {"1M": 0.25, "CODED": 0.5}}, drain.scan_share(s, T0, T0 + 10))


class LogcatTest(unittest.TestCase):
    def test_windows_come_from_the_rx_and_file_lines_and_the_offset_from_link_ups(self):
        lines = [
            f"  {T0 - 3600 + 0.1:.3f}  1  2 I BluetoothMeshTransport: bt link up: abc (1 live)",
            f"  {T0 - 3600 + 70:.3f}  1  2 D BluetoothMeshTransport: rx blob/h1 700000B in 60000ms ← abc",
            f"  {T0 - 3600 + 80:.3f}  1  2 D BluetoothMeshTransport: file blob/h2 5000B in 2000ms @4096 → abc",
            f"  {T0 - 3600 + 90:.3f}  1  2 D OtherTag: rx blob/h3 1B in 1ms ← abc",
        ]
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
            f.write("\n".join(lines) + "\n")
        windows, ups = drain.read_logcat(Path(f.name))
        self.assertEqual([("rx", 700000, 60.0), ("tx", 5000, 2.0)], [(w.side, w.bytes, round(w.end - w.start, 3)) for w in windows])
        s = parsed([(T0 + 0.2, conn_complete(64, 0, 24))])
        self.assertEqual(3600.0, drain.auto_offset(s, ups))


if __name__ == "__main__":
    unittest.main()
