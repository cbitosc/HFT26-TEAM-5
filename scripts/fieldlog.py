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
- message latency per plane, as each phone summarised it.

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
}

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


def summarise(logs: list[Log]) -> dict:
    out: dict = defaultdict(lambda: defaultdict(list))
    for log in logs:
        dev = log.device
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
            shown = values[0] if section == "latency s" else (
                f"{len(values)}" if section == "nan cycles" else percentiles(values)
            )
            print(f"  {key:<40} {shown}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
