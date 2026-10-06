#!/usr/bin/env bash
#
# An emulator on the real BLE mesh through one of knit-ios's nRF52840 dongles, with the host stack's whole HCI
# conversation tapped and fault-injectable on the host (scripts/hci-bridge.py has the why).
#
# The other route, scripts/emulator-ble-mesh.sh, passes a dongle's USB device into the guest. This one leaves the
# dongle on the host and points the emulator's Bluetooth HAL at a Bumble bridge instead of netsim
# (`-packet-streamer-endpoint`), so it needs no root image, no firmware push, no SELinux or driver rebind, and no
# unbind from the host's btusb — Bumble's libusb detaches btusb on open and hands the dongle back on close.
#
# Usage:
#   scripts/emulator-hci-bridge.sh host [a|b]     # check the host side for that dongle (prints the one sudo step)
#   scripts/emulator-hci-bridge.sh up [a|b]       # start the bridge on that dongle, then the AVD on it
#   scripts/emulator-hci-bridge.sh status [a|b]   # bridge links/faults/counters, adapter address, mesh state
#   scripts/emulator-hci-bridge.sh ctl [a|b] ARGS…  # hci-bridge.py ctl ARGS… (refuse / cut / deafen / clear / links)
#   scripts/emulator-hci-bridge.sh down [a|b]     # kill the emulator and the bridge; the dongle goes back to BlueZ
#
# The dongle defaults to b; status and down without one take every instance that is up. The two dongles run side by
# side, each with its own bridge, ports and emulator (scripts/ble-pair-test.sh runs a pair that way):
#
#   dongle  emulator        packet streamer  control
#   a       emulator-5584   8867             8868
#   b       emulator-5582   8877             8878
#
# Each `up` writes build/hci-bridge/<stamp>-<dongle>/{bridge.log,hci.btsnoop,emulator.log}; `latest-<dongle>` and
# `latest` point at it.
# Env: KNIT_HCI_AVD (Knit_Mesh_BT), KNIT_HCI_EMU_PORT / KNIT_HCI_PORT / KNIT_HCI_CONTROL (the table, for the dongle
#      named), KNIT_HCI_HEADLESS=1 for -no-window, KNIT_HCI_READONLY=1 for -read-only (one AVD twice at once, nothing
#      it writes kept), KNIT_HCI_ISOLATE=1 for the bridge's --isolate (the emulator hears only another isolated one),
#      KNIT_HCI_NICE (0) for the emulator's niceness, KNIT_HCI_BOOT_S (300) for how long a boot may take.
#
# The dongles belong to knit-ios's Linux peer rig (its .agents/rules/devices.md): A (E0:E4:06:34:B9:73) is
# knit-peer's default adapter, B (7C:F7:1D:74:2A:49) its second peer. While the bridge holds one, BlueZ has no such
# adapter and a knit-peer run on it exits; check nothing of knit-ios's is using it first.

set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
AVD=${KNIT_HCI_AVD:-Knit_Mesh_BT}
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
EMULATOR="$SDK/emulator/emulator"
ADB="$SDK/platform-tools/adb"
OUT="$ROOT/build/hci-bridge"
VIDPID=2fe3:000b

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }
adb_() { "$ADB" -s "$SERIAL" "$@"; }
bridge() { uv run -q "$ROOT/scripts/hci-bridge.py" "$@"; }

# The ports and serial of one dongle's instance (the table above); sets DONGLE EMU_PORT SERIAL PORT CONTROL RUN.
instance() {
  DONGLE=$1
  case $DONGLE in
    a) EMU_PORT=${KNIT_HCI_EMU_PORT:-5584} PORT=${KNIT_HCI_PORT:-8867} CONTROL=${KNIT_HCI_CONTROL:-8868} ;;
    b) EMU_PORT=${KNIT_HCI_EMU_PORT:-5582} PORT=${KNIT_HCI_PORT:-8877} CONTROL=${KNIT_HCI_CONTROL:-8878} ;;
    *) die "dongle is a or b" ;;
  esac
  SERIAL="emulator-$EMU_PORT"
  RUN="$OUT/latest-$DONGLE"
}

# The bridge's pid for that dongle, if it is running.
bridge_pid() {
  local pid
  pid=$(cat "$OUT/latest-$1/bridge.pid" 2>/dev/null) && kill -0 "$pid" 2>/dev/null && echo "$pid"
}

# The dongles whose instance is up, or the one named.
dongles() {
  if [ -n "${1:-}" ]; then echo "$1"; return; fi
  local d; for d in a b; do [ -n "$(bridge_pid $d)" ] && echo $d; done
}

# Dongle → USB serial and BD address (knit-ios .agents/context/linux-peer.md, "The adapter").
usb_serial() { case $1 in a) echo C849E41B33D5B711 ;; b) echo FA157AEC5AD9FD9D ;; *) die "dongle is a or b" ;; esac; }
bd_addr() { case $1 in a) echo E0:E4:06:34:B9:73 ;; b) echo 7C:F7:1D:74:2A:49 ;; esac; }

# The sysfs directory of the dongle with that USB serial (empty if it is not plugged in).
usb_dir() {
  local d
  for d in /sys/bus/usb/devices/*; do
    [ "$(cat "$d/idVendor" 2>/dev/null):$(cat "$d/idProduct" 2>/dev/null)" = "$VIDPID" ] || continue
    [ "$(cat "$d/serial" 2>/dev/null)" = "$1" ] && { echo "$d"; return; }
  done
}

cmd_host() {
  local dongle=${1:-b} serial dir node ok=0
  serial=$(usb_serial "$dongle")
  dir=$(usb_dir "$serial")
  [ -n "$dir" ] || die "dongle $dongle (USB serial $serial) is not plugged in"
  node=$(printf '/dev/bus/usb/%03d/%03d' "$(cat "$dir/busnum")" "$(cat "$dir/devnum")")
  if [ -w "$node" ]; then
    echo "ok: $node (dongle $dongle) is writable"
  else
    ok=1
    echo "MISSING: $node is not writable by $USER. One sudo, persists:"
    # shellcheck disable=SC2028  # the \\n is meant literally: it is the printf the user runs
    echo "  printf 'SUBSYSTEM==\"usb\", ATTR{idVendor}==\"2fe3\", ATTR{idProduct}==\"000b\", MODE=\"0660\", GROUP=\"plugdev\"\\n' \\"
    echo "    | sudo tee /etc/udev/rules.d/61-knit-hci-bridge.rules"
    echo "  sudo udevadm control --reload && sudo udevadm trigger --subsystem-match=usb --attr-match=idVendor=2fe3"
  fi
  command -v uv >/dev/null || { ok=1; echo "MISSING: uv (the bridge runs as a uv script)"; }
  if pgrep -af knit-peer | grep -q -- "$(bd_addr "$dongle")"; then
    ok=1; echo "BUSY: a knit-peer is running on dongle $dongle"
  fi
  return $ok
}

wait_booted() {
  local boot=${KNIT_HCI_BOOT_S:-300}
  timeout "$boot" "$ADB" -s "$SERIAL" wait-for-device || die "$SERIAL never came up (emulator.log; a running VirtualBox VM?)"
  # shellcheck disable=SC2016  # $(getprop) must expand in the guest shell, not here
  timeout "$boot" "$ADB" -s "$SERIAL" shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done' \
    || die "$SERIAL never finished booting"
}

cmd_up() {
  instance "${1:-b}"
  cmd_host "$DONGLE" || die "host prerequisites are not met (see above)"
  [ -n "$(bridge_pid "$DONGLE")" ] && die "the bridge on dongle $DONGLE is already running (down $DONGLE first)"
  "$ADB" devices | grep -q "^$SERIAL" && die "$SERIAL is already running"
  local readonly=()
  if [ -n "${KNIT_HCI_READONLY:-}" ]; then
    readonly=(-read-only)
  elif pgrep -f "qemu-system-x86_64.* -avd $AVD( |$)" >/dev/null; then
    die "$AVD is already running (KNIT_HCI_READONLY=1 runs it twice)"
  fi
  local run; run="$OUT/$(date +%Y%m%d-%H%M%S)-$DONGLE"
  mkdir -p "$run"; ln -sfn "$run" "$OUT/latest-$DONGLE"; ln -sfn "$run" "$OUT/latest"

  local isolate=(); [ -n "${KNIT_HCI_ISOLATE:-}" ] && isolate=(--isolate)
  say "bridge on dongle $DONGLE ($(bd_addr "$DONGLE")) → localhost:$PORT ${isolate[*]} (logs: $run)"
  nohup uv run -q "$ROOT/scripts/hci-bridge.py" serve --controller "usb:${VIDPID}/$(usb_serial "$DONGLE")" \
    --port "$PORT" --control "$CONTROL" --snoop "$run/hci.btsnoop" "${isolate[@]}" >"$run/bridge.log" 2>&1 &
  echo $! >"$run/bridge.pid"
  local _
  for _ in $(seq 30); do grep -q "control on" "$run/bridge.log" && break; sleep 1; done
  grep -q "control on" "$run/bridge.log" || { cat "$run/bridge.log"; die "the bridge never came up"; }

  local window=(); [ -n "${KNIT_HCI_HEADLESS:-}" ] && window=(-no-window)
  say "launching $AVD on $SERIAL"
  nohup nice -n "${KNIT_HCI_NICE:-0}" "$EMULATOR" -avd "$AVD" -port "$EMU_PORT" -no-audio -no-snapshot -no-boot-anim \
    "${readonly[@]}" "${window[@]}" \
    -packet-streamer-endpoint "localhost:$PORT" >"$run/emulator.log" 2>&1 &
  echo $! >"$run/emulator.pid"
  wait_booted
  adb_ shell 'svc bluetooth enable' >/dev/null 2>&1 || true
  sleep 8
  cmd_status "$DONGLE"
}

cmd_status() {
  local d any=
  for d in $(dongles "${1:-}"); do
    any=1
    status_of "$d"
  done
  [ -n "$any" ] || say "bridge: none running"
}

status_of() {
  instance "$1"
  if [ -n "$(bridge_pid "$DONGLE")" ]; then
    say "bridge $DONGLE (control $CONTROL)"
    bridge ctl status --control "$CONTROL" | python3 -m json.tool
    tail -5 "$RUN/bridge.log" 2>/dev/null | sed 's/^/  /'
  else
    say "bridge $DONGLE: not running"
  fi
  if "$ADB" devices | grep -q "^$SERIAL"; then
    say "adapter ($SERIAL)"
    adb_ shell 'dumpsys bluetooth_manager | sed -n "2,6p"'
    if adb_ shell 'pm path app.getknit.knit' >/dev/null 2>&1; then
      say "knit mesh ($SERIAL)"
      adb_ shell 'am broadcast -f 0x20 -p app.getknit.knit -a app.getknit.knit.debug.STATE' 2>/dev/null |
        sed -n 's/.*data="//;s/"$//p' | sed -e 's/,"metrics".*//' -e 's/^/  /'
    fi
  fi
}

cmd_down() {
  local d stopped=
  for d in $(dongles "${1:-}"); do
    down_of "$d"
    stopped=1
  done
  [ -n "$stopped" ] || { instance "${1:-b}"; adb_ emu kill >/dev/null 2>&1 || true; }
  sleep 2
  echo "emulators and bridges stopped; BlueZ adapters now:"
  hcitool dev | sed 1d
}

down_of() {
  instance "$1"
  adb_ emu kill >/dev/null 2>&1 || true
  # SIGINT, so Bumble closes the device and libusb gives it back to btusb; a SIGTERM leaves it detached.
  local pid _
  if pid=$(bridge_pid "$DONGLE"); then
    kill -INT "$pid"
    for _ in $(seq 10); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -0 "$pid" 2>/dev/null && kill "$pid"
  fi
  echo "dongle $DONGLE: $SERIAL and its bridge stopped"
}

# ctl [a|b] ARGS…: the dongle is optional, and no bridge action is called a or b.
cmd_ctl() {
  local d=b
  case "${1:-}" in a | b) d=$1; shift ;; esac
  instance "$d"
  bridge ctl "$@" --control "$CONTROL"
}

case "${1:-status}" in
  host)   shift; cmd_host "$@" ;;
  up)     shift; cmd_up "$@" ;;
  status) shift; cmd_status "$@" ;;
  ctl)    shift; cmd_ctl "$@" ;;
  down)   shift; cmd_down "$@" ;;
  *)      die "unknown command '${1}' — one of: host up status ctl down" ;;
esac
