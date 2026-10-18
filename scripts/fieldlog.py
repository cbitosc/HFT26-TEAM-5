#!/usr/bin/env python3
"""Validate and summarise Knit field logs (ADR 2026-10.a239, docs/FIELD_LOG.md).

    python3 scripts/fieldlog.py check LOG [LOG …]
    python3 scripts/fieldlog.py summary LOG [LOG …] [--json]

LOG is the `.jsonl` a user shared from Diagnostics → Field log. `check` reads every line against the schema
and exits non-zero on anything a phone should never have written (an unknown kind, a field outside its kind's
set, a string where a number belongs, a node-id-shaped token). `summary` merges any number of logs, from any
number of phones, and prints the tables the open radio questions ask for:

- Bluetooth relink time (`bt_up.relink`) and link lifetime (`bt_down.life`) by device and drop reason;
- dial time and failed dials by reason;
- link RSSI by PHY from the 5-minute samples, and the RSSI each PHY step happened at;
- Wi-Fi Aware data-path lifetimes and session cycles;
- message latency per plane, as each phone summarised it;
- voice calls (a build with calls): how each ended and from which phase, ring and setup times, the media's round
  trip and loss by route, the microphone's health, Wi-Fi Direct legs, walkie bursts, and calls with no end line.

Peers are per-log pseudonyms, so nothing here joins two people's logs; a peer is keyed by (log, pseudonym).
Standard library only.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

SCHEMA = 1

# kind -> the fields it may carry (beyond "t" and "e"); None means any counter name (the `counters` kind).
KINDS: dict[str, set[str] | None] = {
    "mesh_on": {"vc"},
    "mesh_off": set(),
    "power": {"screen", "charging", "low"},
    "radio": {"radio", "health", "linked", "nearby", "contended", "held"},
    "counters": None,
    "bt_up": {"peer", "dialed", "dial", "sighted", "links", "relink"},
    "bt_down": {"peer", "reason", "life", "links", "phy", "rssi"},
    "bt_fail": {"peer", "reason", "dur", "streak"},
    "bt_phy": {"peer", "coded", "rssi"},
    "bt_scan": {"links", "le", "sighted", "hunting", "coded"},
    "bt_orphan": {"peer", "linked", "armed", "tries", "cleared", "age"},
    "bt_link": {"peer", "age", "tx", "rx", "phy", "rssi", "interval", "mtu", "earned"},
    "bt_peer": {"peer", "linked", "rssi", "rssi1m", "rssiCoded", "codedLag"},
    "nan_up": {"peer", "initiator", "links"},
    "nan_down": {"peer", "initiator", "life", "clean"},
    "nan_cycle": set(),
    "low_power": {"on", "why"},
    "lora": {"role", "snr10", "rssi", "queued", "heard", "boards", "airUsed", "airBudget"},
    "latency": {"dir", "plane", "n", "p50", "p90", "max"},
    "call_start": {"peer", "out", "walkie"},
    "call_end": {
        "peer", "out", "walkie", "reason", "last", "life", "byPeer", "missed", "degraded", "ring", "answer", "setup",
        "talk", "restarts", "drops", "relay", "direct", "directUp", "turn", "stun", "unreached", "unproven",
    },
    "call_route": {"local", "remote", "proto", "net", "direct", "seq"},
    "call_media": {
        "lost", "rtt", "jitter", "buffer", "lossIn10", "lossOut10", "lossCall10", "send", "recv", "pkts", "mic",
        "heard", "local", "remote", "proto", "relayProto", "net", "direct", "pairs", "pending", "micPath", "micHealth",
    },
    "call_direct": {"host", "end", "group", "lane", "life", "tries"},
    "talk": {"dir", "chunks", "ms", "sent", "path", "held", "lost", "wait", "spread"},
    "walkie_end": {
        "burstsOut", "burstsIn", "chunksOut", "failed", "chunksIn", "lost", "transit", "jitter", "hand", "frameOut",
        "frameIn",
    },
}

# Summary sections shown as a count of lines, and as the values themselves, rather than as percentiles.
COUNTED = {"nan cycles", "call routes", "call mic", "call deaf readings"}
LISTED = {"call drops / restarts"}

PSEUDONYM = re.compile(r"^p[0-9a-f]{8}$")
ENUM = re.compile(r"^[A-Za-z0-9_]+$")
NODE_ID = re.compile(r"(?<![a-z2-7])[a-z2-7]{26}(?![a-z2-7])")


@dataclass
class Log:
    path: Path
    header: dict[str, str] = field(default_factory=dict)
    events: list[dict] = field(default_factory=list)
    problems: list[str] = field(default_factory=list)

    @property
    def device(self) -> str:
        return self.header.get("device", "?")


def read(path: Path) -> Log:
    log = Log(path)
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line:
            continue
        if line.startswith("# "):
            key, sep, value = line[2:].partition(": ")
            if sep:
                log.header[key] = value
            elif line.startswith("# knit field log, schema "):
                log.header["schema"] = line.rsplit(" ", 1)[-1]
            continue
        where = f"{path.name}:{number}"
        if NODE_ID.search(line):
            log.problems.append(f"{where}: a node-id-shaped token")
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            log.problems.append(f"{where}: not JSON")
            continue
        log.problems.extend(f"{where}: {p}" for p in validate(event))
        log.events.append(event)
    if log.header.get("schema") not in (None, str(SCHEMA)):
        log.problems.append(f"{path.name}: schema {log.header['schema']}, this reader knows {SCHEMA}")
    return log


def validate(event: dict) -> list[str]:
    problems = []
    kind = event.get("e")
    if not isinstance(event.get("t"), int):
        problems.append("no integer t")
    if kind not in KINDS:
        return problems + [f"unknown kind {kind!r}"]
    allowed = KINDS[kind]
    for name, value in event.items():
        if name in ("t", "e"):
            continue
        if allowed is not None and name not in allowed:
            problems.append(f"{kind}: unexpected field {name!r}")
        if isinstance(value, str):
            ok = PSEUDONYM.match(value) if name == "peer" else ENUM.match(value)
            if not ok:
                problems.append(f"{kind}.{name}: {value!r} is neither a pseudonym nor a constant")
        elif not isinstance(value, (bool, int)):
            problems.append(f"{kind}.{name}: {type(value).__name__}")
    return problems


def percentiles(values: list[float]) -> str:
    if not values:
        return "—"
    s = sorted(values)

    def at(p: int) -> float:
        return s[(len(s) - 1) * p // 100]

    return f"n={len(s)} p50={at(50):g} p90={at(90):g} max={s[-1]:g}"


def route(e: dict) -> str:
    """A call's selected pair as a call_route or call_media line names it: `RELAY/SRFLX CELLULAR`, `… direct`."""
    pair = f"{e.get('local', '?')}/{e.get('remote', '?')}"
    return f"{pair} {e.get('net', '?')}" + (" direct" if e.get("direct") else "")


def summarise(logs: list[Log]) -> dict:
    out: dict = defaultdict(lambda: defaultdict(list))
    for log in logs:
        dev = log.device
        # Calls that began and have no end line yet: a process death in the call, or one still up at export.
        open_calls = 0
        for e in log.events:
            kind = e["e"]
            if kind == "bt_up":
                if "relink" in e:
                    out["bt relink s"][dev].append(e["relink"] / 1000)
                if "dial" in e:
                    out["bt dial s"][dev].append(e["dial"] / 1000)
            elif kind == "bt_down":
                out["bt life s"][f"{dev} {e['reason']}"].append(e["life"] / 1000)
            elif kind == "bt_fail":
                out["bt fails"][f"{dev} {e['reason']}"].append(e["dur"] / 1000)
            elif kind == "bt_link" and "rssi" in e:
                out["bt link rssi"][f"{dev} {e.get('phy', '?')}"].append(e["rssi"])
            elif kind == "bt_orphan":
                outcome = "cleared" if e["cleared"] else "kept"
                out["bt orphan s"][f"{dev} {outcome} linked={e['linked']} armed={e['armed']} tries={e['tries']}"].append(e["age"] / 1000)
            elif kind == "bt_phy" and "rssi" in e:
                out["bt step rssi"][f"{dev} {'to CODED' if e['coded'] else 'up'}"].append(e["rssi"])
            elif kind == "nan_down":
                out["nan life s"][f"{dev} {'clean' if e['clean'] else 'reset'}"].append(e["life"] / 1000)
            elif kind == "nan_cycle":
                out["nan cycles"][dev].append(1)
            elif kind == "latency":
                out["latency s"][f"{dev} {e['dir']} {e['plane']}"].append(
                    f"n={e['n']} p50={e['p50'] / 1000:g} p90={e['p90'] / 1000:g} max={e['max'] / 1000:g}"
                )
            elif kind == "call_start":
                open_calls += 1
            elif kind == "call_end":
                if "last" in e:
                    open_calls = max(0, open_calls - 1)
                what = f"{dev} {'walkie' if e['walkie'] else 'live'} {'out' if e['out'] else 'in'}"
                out["call ends s"][f"{what} {e['reason']} from {e.get('last', 'never rang')}"].append(e["life"] / 1000)
                if "ring" in e:
                    out["call ring s"][dev].append(e["ring"] / 1000)
                if "setup" in e and "answer" in e:
                    out["call answer→connected s"][f"{what} direct={e['directUp']}"].append((e["setup"] - e["answer"]) / 1000)
                if e["drops"] or e["restarts"]:
                    out["call drops / restarts"][what].append(f"{e['drops']}/{e['restarts']}")
            elif kind == "call_route":
                out["call routes"][f"{dev} {route(e)}"].append(e["seq"])
            elif kind == "call_media":
                where = f"{dev} {route(e)}"
                if "rtt" in e:
                    out["call rtt ms"][where].append(e["rtt"])
                if "lossIn10" in e:
                    out["call loss in %"][where].append(e["lossIn10"] / 10)
                if "lossOut10" in e:
                    out["call loss out %"][where].append(e["lossOut10"] / 10)
                if "micHealth" in e:
                    out["call mic"][f"{dev} {e.get('micPath', '?')} {e['micHealth']}"].append(1)
                if e.get("pkts") == 0 and not e["lost"]:
                    out["call deaf readings"][dev].append(1)
            elif kind == "call_direct":
                out["call direct life s"][f"{dev} {'host' if e['host'] else 'join'} {e['end']}"].append(e["life"] / 1000)
                if "lane" in e:
                    out["call direct lane up s"][f"{dev} {'host' if e['host'] else 'join'}"].append(e["lane"] / 1000)
            elif kind == "talk" and e["dir"] == "OUT" and e["chunks"]:
                out["talk out sent %"][f"{dev} {e.get('path', '?')}"].append(100 * e.get("sent", 0) / e["chunks"])
            elif kind == "talk" and e["dir"] == "IN":
                heard = e["chunks"] + e.get("lost", 0)
                if heard:
                    out["talk in lost %"][dev].append(100 * e.get("lost", 0) / heard)
                if "wait" in e:
                    out["talk in wait ms"][dev].append(e["wait"])
        if open_calls:
            out["calls with no end line"][dev].append(open_calls)
    return out


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="cmd", required=True)
    for name in ("check", "summary"):
        p = sub.add_parser(name)
        p.add_argument("logs", nargs="+", type=Path)
        if name == "summary":
            p.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    logs = [read(path) for path in args.logs]

    if args.cmd == "check":
        bad = [p for log in logs for p in log.problems]
        for problem in bad:
            print(problem)
        events = sum(len(log.events) for log in logs)
        print(f"{len(logs)} log(s), {events} event(s), {len(bad)} problem(s)", file=sys.stderr)
        return 1 if bad else 0

    table = summarise(logs)
    if args.json:
        print(json.dumps({k: dict(v) for k, v in table.items()}, indent=2, default=str))
        return 0
    for section, rows in table.items():
        print(f"\n{section}")
        for key in sorted(rows):
            values = rows[key]
            if section == "latency s":
                shown = values[0]
            elif section in COUNTED:
                shown = f"{len(values)}"
            elif section in LISTED:
                shown = " ".join(str(v) for v in values)
            elif section == "calls with no end line":
                shown = f"{sum(values)}"
            else:
                shown = percentiles(values)
            print(f"  {key:<40} {shown}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
