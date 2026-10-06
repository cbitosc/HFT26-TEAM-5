#!/usr/bin/env python3
"""Time a file across one Bluetooth link, and the texts sent behind it, rep after rep (#120).

    python3 scripts/ble-drain-trial.py run --sender p9=SERIAL --receiver p8=SERIAL --cell NAME \\
        [--pace 16384] [--reps 3] [--size 700000] [--image PATH] [--texts 5,15,30,60] [--relink] \\
        [--tune SIDE:ASK[,ASK…]] [--quiet 60] [--timeout 900] [--out DIR]
    python3 scripts/ble-drain-trial.py summary DIR [DIR ...]

Each rep, on two debug builds that already share a Bluetooth link (Wi-Fi Aware dark with `…debug.NANOFF`, other phones
paused, per the trial recipe on #120):

1. Sets the sender's 1M/2M file budget with `…debug.LINKTUNE --ei filePace` (`--pace`, 0 unbounded) and checks it.
2. With `--relink`, sheds the link (`…debug.BLECAP --ei max 0` on the sender) and waits for it back, so asks made of
   the old link (a PHY, a priority) are gone. With `--tune`, asks the fresh link for them: `sender:phy=2m,priority=high`
   or `receiver:mtu=517` — the keys are LINKTUNE's extras (`phy`, `priority`, `mtu`).
3. Waits until neither phone has logged a `file …` or `rx …` line for `--quiet` seconds: a carrier backlog on the link
   would share its air.
4. Sends a fresh file of `--size` random bytes as a DM attachment (`…debug.SENDIMG --ez file true`), or a copy of
   `--image` (the pipeline may shrink it, and a picture this phone already holds goes out as its old identity — the
   driver refuses a reply with `reused` set). Then a DM text at each `--texts` offset.
5. Polls the receiver's thread (`…debug.STATE --es conv`) every second until the file is held and every text is in,
   and times each on the host clock.
6. Dumps both phones' `BluetoothMeshTransport` log for the rep (`logcat -d -v epoch`, never streamed: a stream over
   network adb is Wi-Fi traffic beside the link being measured) and reads the receiver's `rx … in <ms>ms` line — the
   drain — the sender's `file … in` line against size ÷ pace (a sender that slept stretches its own pace), and every
   `bt file pace` and `bt tune` line.

On the way out, even after a failure, it puts both phones' budget back to 4096, lets their tuners go, and relinks a
link `--tune` asked of, so a soak or a battery night run next measures shipped settings. A `--tune` that asks a PHY
needs the Coded experiment off on both phones (`…debug.PHY --es mode off`); the driver checks and refuses otherwise.

Every rep is one JSON line in DIR/reps.jsonl, with the Wi-Fi frequency and Coded mode each phone was on; the logcat dumps sit beside
it for `scripts/btsnoop-drain.py summary --logcat`. Polling the receiver wakes its app, so a screen-off cell measures
a receiver kept a little more awake than it would be.

Driving a physical phone needs the maintainer's go-ahead for the session (.agents/rules/devices.md); never toggle Wi-Fi
on a network-adb phone.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import statistics
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BRIDGE = ROOT / "scripts" / "bridge.sh"
PKG = "app.getknit.knit"
TAG = "BluetoothMeshTransport"
LINE = re.compile(r"^\s*(\d+\.\d+)\s+\d+\s+\d+\s+([VDIWEF])\s+([^:]+?)\s*: (.*)$")
RX_DONE = re.compile(r"^rx (\S+) (\d+)B in (\d+)ms ← (\S+)")
TX_DONE = re.compile(r"^file (\S+) (\d+)B in (\d+)ms @(-?\d+) → (\S+)")
STREAM = re.compile(r"^(rx|file) ")
LINK_UP = re.compile(r"^bt link up: (\S+) \(")
LINK_DOWN = re.compile(r"^bt link down: (\S+) \(")
KEPT = re.compile(r"^(bt file pace |bt tune |bt conn params |bt phy |bt link )")


def log(msg: str) -> None:
    print(f"[{dt.datetime.now():%H:%M:%S}] {msg}", file=sys.stderr, flush=True)


class Phone:
    def __init__(self, label: str, serial: str) -> None:
        self.label, self.serial = label, serial
        self.node_id = ""
        self.phy_mode = ""  # the Coded experiment's mode, or `dark` on a build without it
        self.offset = 0.0  # device clock minus host clock, seconds

    def sh(self, command: str, timeout: float = 30) -> str:
        # A log line can carry bytes that are not UTF-8 (a peer's name, a binary tag); keep the rest.
        r = subprocess.run(["adb", "-s", self.serial, "shell", command], capture_output=True, text=True,
                           errors="replace", timeout=timeout, check=False)
        return r.stdout.replace("\r", "")

    def bridge(self, action: str, *extras: str) -> dict:
        r = subprocess.run([str(BRIDGE), self.serial, action, *extras], capture_output=True, text=True, timeout=60,
                           check=False)
        if r.returncode != 0:
            raise RuntimeError(f"{self.label} {action}: {r.stderr.strip()}")
        reply = json.loads(r.stdout)
        if reply.get("status") == "error":
            raise RuntimeError(f"{self.label} {action}: {reply.get('message')}")
        return reply

    def measure_offset(self) -> None:
        """The device clock's offset from the host's, from the probe with the shortest round trip."""
        best = None
        for _ in range(5):
            t0 = time.time()
            out = self.sh("date +%s.%N", timeout=15).strip()
            t1 = time.time()
            try:
                dev = float(out)
            except ValueError:
                continue
            if best is None or t1 - t0 < best[0]:
                best = (t1 - t0, dev - (t0 + t1) / 2)
        if best is None:
            raise RuntimeError(f"{self.label}: no clock reading")
        self.offset = best[1]

    def lines(self, since_host: float) -> list[tuple[float, str]]:
        """The transport's log lines since [since_host], as (host time, message)."""
        since = f"{since_host + self.offset:.3f}"
        out = self.sh(f"logcat -d -v epoch -T {since} -s {TAG}:*", timeout=60)
        got = []
        for line in out.splitlines():
            m = LINE.match(line)
            if m and m.group(3) == TAG:
                got.append((float(m.group(1)) - self.offset, m.group(4)))
        return got

    def dump(self, since_host: float, path: Path) -> list[tuple[float, str]]:
        since = f"{since_host + self.offset:.3f}"
        path.write_text(self.sh(f"logcat -d -v epoch -T {since}", timeout=120))
        return self.lines(since_host)

    def wifi_mhz(self) -> int | None:
        out = self.sh("cmd wifi status 2>/dev/null; dumpsys wifi 2>/dev/null | grep -m3 -i frequency", timeout=30)
        m = re.search(r"[Ff]requency[:=]\s*(\d{4})", out)
        return int(m.group(1)) if m else None


def parse_phone(spec: str) -> Phone:
    label, _, serial = spec.partition("=")
    if not serial:
        raise SystemExit(f"--sender/--receiver take LABEL=SERIAL, not {spec!r}")
    return Phone(label, serial)


def parse_tune(spec: str) -> tuple[str, list[str]]:
    side, _, asks = spec.partition(":")
    if side not in ("sender", "receiver") or not asks:
        raise SystemExit(f"--tune takes sender|receiver:KEY=VALUE[,…], not {spec!r}")
    extras: list[str] = []
    for ask in asks.split(","):
        key, _, value = ask.partition("=")
        flag = "--ei" if key == "mtu" else "--es"
        extras += [flag, key, value]
    return side, extras


class Trial:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.tx, self.rx = parse_phone(args.sender), parse_phone(args.receiver)
        stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
        self.out = Path(args.out or ROOT / "build" / "ble-drain-trial" / f"{stamp}-{args.cell}")
        self.out.mkdir(parents=True, exist_ok=True)

    def setup(self) -> None:
        for p in (self.tx, self.rx):
            p.node_id = p.bridge("STATE")["self"]["nodeId"]
            try:
                p.phy_mode = p.bridge("PHY")["mode"]
            except RuntimeError as e:
                if "dark" not in str(e):
                    raise
                p.phy_mode = "dark"
            p.measure_offset()
            log(f"{p.label} {p.serial} node {p.node_id[:8]} phy mode {p.phy_mode} clock offset {p.offset:+.3f}s")
        # A PHY mask lasts the link's life and a Coded stepper on either side reads a foreign answer as a refusal, so a
        # PHY ask needs the experiment off on both phones (the transport refuses it only on the asking side).
        if self.args.tune and "phy=" in self.args.tune:
            running = [p.label for p in (self.tx, self.rx) if p.phy_mode not in ("off", "dark")]
            if running:
                raise SystemExit(f"--tune asks a PHY but the Coded experiment runs on {running}: "
                                 "scripts/bridge.sh <serial> PHY --es mode off on each first")

    def restore(self) -> None:
        """Leaves both phones as shipped: the budget back, the tuners gone, and a tuned link made afresh."""
        for p in (self.tx, self.rx):
            for extras in (("--ez", "resetPace", "true"), ("--es", "node", "all", "--ez", "close", "true")):
                try:
                    p.bridge("LINKTUNE", *extras)
                except (RuntimeError, subprocess.SubprocessError, json.JSONDecodeError) as e:
                    log(f"restore {p.label}: {e}")
        if self.args.tune and self.tx.node_id:
            try:
                self.relink()
            except (RuntimeError, subprocess.SubprocessError, json.JSONDecodeError) as e:
                log(f"restore relink: {e}")

    def wait_line(self, phone: Phone, pattern: re.Pattern, peer: str, since: float, deadline: float) -> float | None:
        while time.time() < deadline:
            for t, msg in phone.lines(since):
                if (m := pattern.match(msg)) and m.group(1).startswith(peer[:8]):
                    return t
            time.sleep(2)
        return None

    def relink(self) -> bool:
        start = time.time()
        self.tx.bridge("BLECAP", "--ei", "max", "0")
        try:
            down = self.wait_line(self.tx, LINK_DOWN, self.rx.node_id, start, start + 90)
        finally:
            self.tx.bridge("BLECAP", "--ez", "clear", "true")
        if down is None:
            log("relink: the link never went down")
            return False
        up = self.wait_line(self.tx, LINK_UP, self.rx.node_id, down, time.time() + 300)
        log(f"relink: down after {down - start:.0f}s, up after {(up or time.time()) - down:.0f}s")
        return up is not None

    def wait_quiet(self) -> float:
        """Seconds waited until neither phone streamed a file for --quiet seconds."""
        start = time.time()
        while time.time() - start < self.args.quiet_max:
            last = max((t for p in (self.tx, self.rx) for t, msg in p.lines(start - 600) if STREAM.match(msg)),
                       default=0.0)
            if time.time() - last >= self.args.quiet:
                return time.time() - start
            time.sleep(5)
        log("quiet: still streaming after --quiet-max; measuring anyway")
        return time.time() - start

    def stage(self, rep: int) -> tuple[Path, int]:
        if self.args.image:
            src = Path(self.args.image)
            data = src.read_bytes()
            name = f"drain-{rep}{src.suffix}"
        else:
            data = os.urandom(self.args.size)
            name = f"drain-{rep}.bin"
        local = self.out / name
        local.write_bytes(data)
        subprocess.run(["adb", "-s", self.tx.serial, "push", str(local), f"/data/local/tmp/{name}"],
                       capture_output=True, check=True, timeout=120)
        self.tx.sh(f"cat /data/local/tmp/{name} | run-as {PKG} sh -c 'cat > files/{name}'; rm /data/local/tmp/{name}")
        return Path(f"/data/data/{PKG}/files/{name}"), len(data)

    def rep(self, n: int) -> dict:
        a = self.args
        tag = f"drain {a.cell} {n} {int(time.time())}"
        reply = self.tx.bridge("LINKTUNE", "--ei", "filePace", str(a.pace))
        if reply["filePace"] != a.pace:
            raise RuntimeError(f"filePace reads {reply['filePace']}, not {a.pace}")
        if a.relink and not self.relink():
            return {"cell": a.cell, "rep": n, "error": "relink failed"}
        if a.tune:
            side, extras = parse_tune(a.tune)
            asker, peer = (self.tx, self.rx) if side == "sender" else (self.rx, self.tx)
            tuned = asker.bridge("LINKTUNE", "--es", "node", peer.node_id, *extras, "--ez", "read", "true")
            log(f"tune {side}: {json.dumps(tuned['links'])}")
        quiet_s = self.wait_quiet()
        path, size = self.stage(n)
        start = time.time()
        extras = ["--es", "to", self.rx.node_id, "--es", "path", str(path), "--es", "text", f"{tag} file"]
        if not a.image:
            extras += ["--ez", "file", "true"]
        sent = self.tx.bridge("SENDIMG", *extras)
        if sent.get("reused"):
            raise RuntimeError(f"the file went out as an old identity ({sent['reused']}); use a fresh one")
        texts = {f"{tag} +{s}": start + s for s in a.text_offsets}
        pending = dict(texts)
        arrived: dict[str, float] = {}
        held_at = None
        while time.time() - start < a.timeout:
            now = time.time()
            for body, due in list(pending.items()):
                if now >= due:
                    self.tx.bridge("SEND", "--es", "to", self.rx.node_id, "--es", "text", body)
                    texts[body] = time.time()
                    del pending[body]
            rows = self.rx.bridge("STATE", "--es", "conv", self.tx.node_id, "--ei", "limit", "30").get("messages", [])
            seen = time.time()
            for row in rows:
                body = row.get("text") or row.get("body") or ""
                if body in texts and body not in pending and body not in arrived:
                    arrived[body] = seen
                if body == f"{tag} file" and row.get("attachmentHeld") and held_at is None:
                    held_at = seen
            if held_at and not pending and len(arrived) == len(texts):
                break
            time.sleep(1)
        rx_lines = self.rx.dump(start - 5, self.out / f"{a.cell}-{n}-{self.rx.label}.logcat")
        tx_lines = self.tx.dump(start - 5, self.out / f"{a.cell}-{n}-{self.tx.label}.logcat")
        drain = next(((int(m.group(2)), int(m.group(3))) for _, msg in rx_lines
                      if (m := RX_DONE.match(msg)) and abs(int(m.group(2)) - size) < max(4096, size // 20)), None)
        fed = next(((int(m.group(3)), int(m.group(4))) for _, msg in tx_lines
                    if (m := TX_DONE.match(msg)) and abs(int(m.group(2)) - size) < max(4096, size // 20)), None)
        return {
            "cell": a.cell,
            "rep": n,
            "at": dt.datetime.now().isoformat(timespec="seconds"),
            "sender": self.tx.label,
            "receiver": self.rx.label,
            "pace": a.pace,
            "tune": a.tune,
            "phyMode": {p.label: p.phy_mode for p in (self.tx, self.rx)},
            "relinked": a.relink,
            "quietWaitS": round(quiet_s),
            "size": size,
            "fileHeldS": round(held_at - start, 1) if held_at else None,
            "rxBytes": drain[0] if drain else None,
            "rxMs": drain[1] if drain else None,
            "drainBps": round(drain[0] / (drain[1] / 1000)) if drain and drain[1] else None,
            "feedMs": fed[0] if fed else None,
            "feedExpectedMs": round(size / a.pace * 1000) if a.pace > 0 else None,
            "textWaitsS": {b.rsplit(" ", 1)[1]: (round(arrived[b] - texts[b], 1) if b in arrived else None) for b in texts},
            "wifiMHz": {p.label: p.wifi_mhz() for p in (self.tx, self.rx)},
            "lines": {p.label: [m for _, m in ls if KEPT.match(m)] for p, ls in ((self.tx, tx_lines), (self.rx, rx_lines))},
        }

    def run(self) -> None:
        self.setup()
        try:
            with (self.out / "reps.jsonl").open("a") as f:
                for n in range(1, self.args.reps + 1):
                    r = self.rep(n)
                    f.write(json.dumps(r) + "\n")
                    f.flush()
                    log(f"rep {n}: drain={r.get('drainBps')}B/s held={r.get('fileHeldS')}s texts={r.get('textWaitsS')}")
        finally:
            self.restore()
        log(f"wrote {self.out / 'reps.jsonl'}")


def summary(dirs: list[str]) -> None:
    cells: dict[str, list[dict]] = {}
    for d in dirs:
        for line in (Path(d) / "reps.jsonl").read_text().splitlines():
            r = json.loads(line)
            cells.setdefault(f"{r['cell']} {r.get('sender')}→{r.get('receiver')} pace={r.get('pace')}", []).append(r)
    for cell, reps in sorted(cells.items()):
        drains = [r["drainBps"] for r in reps if r.get("drainBps")]
        held = [r["fileHeldS"] for r in reps if r.get("fileHeldS")]
        waits = [w for r in reps for w in (r.get("textWaitsS") or {}).values() if w is not None]
        missed = sum(1 for r in reps for w in (r.get("textWaitsS") or {}).values() if w is None)
        fmt = lambda xs: f"{min(xs)}..{max(xs)} (median {statistics.median(xs)})" if xs else "-"
        print(f"{cell}: {len(reps)} reps")
        print(f"  drain B/s {fmt(drains)}")
        print(f"  file held s {fmt(held)}")
        print(f"  text waits s {fmt(waits)}{f', {missed} never arrived' if missed else ''}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--sender", required=True, help="LABEL=SERIAL")
    r.add_argument("--receiver", required=True, help="LABEL=SERIAL")
    r.add_argument("--cell", required=True, help="a name for this cell of the matrix")
    r.add_argument("--pace", type=int, default=16384, help="the sender's file budget, B/s (0 unbounded)")
    r.add_argument("--reps", type=int, default=3)
    r.add_argument("--size", type=int, default=700_000, help="random bytes per file (ignored with --image)")
    r.add_argument("--image", help="send a copy of this picture instead of random bytes")
    r.add_argument("--texts", default="5,15,30,60", help="seconds after the file to send each text")
    r.add_argument("--relink", action="store_true", help="shed and re-make the link before each rep")
    r.add_argument("--tune", help="sender|receiver:KEY=VALUE[,…] asked of the link before each rep")
    r.add_argument("--quiet", type=int, default=60, help="seconds with no file on either phone before sending")
    r.add_argument("--quiet-max", type=int, default=900)
    r.add_argument("--timeout", type=int, default=900, help="seconds to wait for the file and the texts")
    r.add_argument("--out")
    s = sub.add_parser("summary")
    s.add_argument("dirs", nargs="+")
    args = ap.parse_args()
    if args.cmd == "summary":
        summary(args.dirs)
        return
    args.text_offsets = [int(x) for x in args.texts.split(",") if x]
    Trial(args).run()


if __name__ == "__main__":
    main()
