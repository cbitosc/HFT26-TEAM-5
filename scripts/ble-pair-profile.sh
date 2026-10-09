#!/usr/bin/env bash
#
# Collects the baseline and startup profiles (:baselineprofile's BaselineProfileGenerator) on an emulator that has a
# live mesh peer in range, over real Bluetooth: the two-emulator rig of scripts/ble-pair-test.sh, with the collector
# on one side and an ordinary debug Knit on the other.
#
#   side a  collector  Knit_Mesh_BT (the AVD every committed profile came from) on dongle A, emulator-5604
#   side b  peer       Knit_BLE_Pair (lean aosp_atd) on dongle B, emulator-5602, posting to the Nearby room
#
# Both bridges run --isolate, so the pair hears each other and nobody else: the collector's "Morning" goes to the peer
# alone, and the lab phones neither hear nor are heard. The peer's posts land in the collector's Nearby room, so the
# journey's thread draws incoming bubbles too, and the baseline flavour holds each iteration on the chat list for
# KNIT_BP_DWELL_S so the link forms while the process lives (the startup flavour never dwells, though a known peer
# relinks within its seconds).
#
# Usage:
#   scripts/ble-pair-profile.sh up       # both bridges and emulators (a on Knit_Mesh_BT, b on Knit_BLE_Pair)
#   scripts/ble-pair-profile.sh install  # build nonMinifiedRelease + the generator (a) and debug (b), adb install
#   scripts/ble-pair-profile.sh run      # peer up and posting, then both generator tests on a; pulls the profiles
#   scripts/ble-pair-profile.sh apply    # copy the latest run's two files over the committed ones
#   scripts/ble-pair-profile.sh down     # both emulators down, both dongles back to BlueZ
#   scripts/ble-pair-profile.sh all      # up, install, run, down (down whatever the verdict); apply stays manual
#
# Each run writes build/ble-pair-profile/<stamp>/: the two profiles, the instrumentation output, both logcats, the
# peer's posting log and its STATE snapshots (its `reachable` list is the evidence the link formed).
# Env: KNIT_BP_DWELL_S (30) per-iteration dwell of the baseline flavour; 0 runs the journey as it always was;
#      KNIT_BP_POST_S (20) the peer's posting interval, 0 posts nothing; KNIT_BP_CLASS narrows the run to
#      `<fqcn>#<method>`; KNIT_PAIR_NO_BUILD=1 installs the APKs already built.
#
# Same adb server (5038) and console ports as ble-pair-test.sh, so its `status` and `down` work on this pair too, and
# nothing here can reach a lab phone. Gradle only builds; the APKs go on with `adb install`.

set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
HB="$ROOT/scripts/emulator-hci-bridge.sh"
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
ADB="$SDK/platform-tools/adb"
export ANDROID_ADB_SERVER_PORT=${KNIT_PAIR_ADB_PORT:-5038}
export ADB_LOCAL_TRANSPORT_MAX_PORT=5605
export KNIT_HCI_NICE=${KNIT_HCI_NICE:-10}
export KNIT_HCI_BOOT_S=${KNIT_HCI_BOOT_S:-600}
PKG=app.getknit.knit
GEN_PKG=$PKG.baselineprofile
GEN_CLASS=${KNIT_BP_CLASS:-$GEN_PKG.BaselineProfileGenerator}
APP_APK="$ROOT/app/build/outputs/apk/nonMinifiedRelease/app-nonMinifiedRelease.apk"
GEN_APK="$ROOT/baselineprofile/build/outputs/apk/nonMinifiedRelease/baselineprofile-nonMinifiedRelease.apk"
PEER_APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
OUT="$ROOT/build/ble-pair-profile"
DWELL_S=${KNIT_BP_DWELL_S:-30}
POST_S=${KNIT_BP_POST_S:-20}
# Where macrobenchmark writes when no additionalTestOutputDir is passed: the test package's media directory.
DEVICE_OUT=/sdcard/Android/media/$GEN_PKG

SIDES=(a b)
console_of() { case $1 in a) echo 5604 ;; b) echo 5602 ;; esac; }
serial_of() { echo "emulator-$(console_of "$1")"; }
avd_of() { case $1 in a) echo Knit_Mesh_BT ;; b) echo Knit_BLE_Pair ;; esac; }

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }
on() { local s; s=$(serial_of "$1"); shift; timeout 300 "$ADB" -s "$s" "$@"; }
bridge() { "$ROOT/scripts/bridge.sh" "$(serial_of b)" "$@"; }

cmd_up() {
  local side
  [ -f "$HOME/.android/avd/Knit_BLE_Pair.avd/config.ini" ] || "$ROOT/scripts/ble-pair-test.sh" avd
  "$ADB" start-server
  for side in "${@:-${SIDES[@]}}"; do
    KNIT_HCI_AVD=$(avd_of "$side") KNIT_HCI_ISOLATE=1 KNIT_HCI_READONLY=1 KNIT_HCI_HEADLESS=${KNIT_HCI_HEADLESS-1} \
      KNIT_HCI_EMU_PORT=$(console_of "$side") "$HB" up "$side"
  done
}

cmd_install() {
  local side
  for side in "${SIDES[@]}"; do
    "$ADB" devices | grep -q "^$(serial_of "$side")[[:space:]]*device" || die "$(serial_of "$side") is not up (up first)"
  done
  if [ -z "${KNIT_PAIR_NO_BUILD:-}" ]; then
    say "building nonMinifiedRelease, the generator and debug"
    (cd "$ROOT" && ./gradlew -q -Pknit.baselineProfile=true \
      :app:assembleNonMinifiedRelease :baselineprofile:assembleNonMinifiedRelease :app:assembleDebug)
  fi
  say "installing the collector on $(serial_of a)"
  # Knit_Mesh_BT keeps whatever Knit was last put on it (a debug build outranks this version code, and is signed
  # differently); it boots read-only, so dropping it here costs the AVD nothing.
  on a uninstall "$PKG" >/dev/null 2>&1 || true
  on a install -r -t "$APP_APK" | tail -1
  on a install -r -t "$GEN_APK" | tail -1
  say "installing the peer on $(serial_of b)"
  on b install -r -t "$PEER_APK" | tail -1
}

grant() {
  local side=$1 p
  for p in NEARBY_WIFI_DEVICES BLUETOOTH_SCAN BLUETOOTH_ADVERTISE BLUETOOTH_CONNECT POST_NOTIFICATIONS; do
    on "$side" shell pm grant "$PKG" "android.permission.$p"
  done
  on "$side" shell cmd deviceidle whitelist "+$PKG" >/dev/null
  on "$side" shell svc power stayon true
  on "$side" shell input keyevent KEYCODE_WAKEUP
  on "$side" shell wm dismiss-keyguard
}

# The peer: a fresh identity, opened so KnitApp starts the mesh, then a dull post to the Nearby room every POST_S and
# a STATE snapshot beside it. Runs until killed.
peer_loop() {
  local dir=$1 n=0
  local words=(Afternoon Evening "Still here" "Over here" Hello "On my way" "Back soon" Lunch?)
  while :; do
    n=$((n + 1))
    if [ "$POST_S" -gt 0 ]; then
      bridge SEND --es conv nearby --es text "${words[$((n % ${#words[@]}))]}" >/dev/null 2>&1 || true
    fi
    { date +%T; bridge STATE 2>/dev/null | python3 -c '
import json, sys
s = json.load(sys.stdin)
print("reachable", [p.get("nodeId", p) if isinstance(p, dict) else p for p in s.get("reachable", [])])' || echo "no STATE"; } \
      >>"$dir/peer.log" 2>&1
    sleep "$(( POST_S > 0 ? POST_S : 20 ))"
  done
}

cmd_run() {
  local stamp dir runner verdict=0
  stamp=$(date +%Y%m%d-%H%M%S)
  dir="$OUT/$stamp"
  mkdir -p "$dir"; ln -sfn "$dir" "$OUT/latest"
  on a shell pm path "$PKG" >/dev/null 2>&1 || die "Knit is not on $(serial_of a) (install first)"
  runner=$(on a shell pm list instrumentation | sed -n "s/^instrumentation:\(${GEN_PKG//./\\.}\/[^ ]*\) .*/\1/p" | head -1)
  [ -n "$runner" ] || die "no generator instrumentation on $(serial_of a) (install first)"

  say "peer: fresh Knit on $(serial_of b)"
  on b shell pm clear "$PKG" >/dev/null
  grant b
  on b logcat -c || true
  on b shell am start -W -n "$PKG/.MainActivity" >/dev/null
  sleep 5
  say "peer is $(bridge STATE | python3 -c 'import json, sys; print(json.load(sys.stdin)["self"]["nodeId"])')"
  peer_loop "$dir" &
  PEER_PID=$!

  say "collector: fresh Knit on $(serial_of a), dwell ${DWELL_S}s (logs: $dir)"
  on a shell pm clear "$PKG" >/dev/null
  grant a
  on a shell rm -rf "$DEVICE_OUT" || true
  on a logcat -c || true
  timeout 5400 "$ADB" -s "$(serial_of a)" shell am instrument -w -e class "$GEN_CLASS" \
    -e knitLinkDwellMs "$((DWELL_S * 1000))" "$runner" >"$dir/a.instrument.txt" 2>&1 || verdict=1

  stop_peer
  on a logcat -d >"$dir/a.logcat.txt" 2>&1 || true
  on b logcat -d >"$dir/b.logcat.txt" 2>&1 || true
  on a pull "$DEVICE_OUT" "$dir/device-out" >/dev/null 2>&1 || true
  find "$dir/device-out" -name '*-baseline-prof.txt' -o -name '*-startup-prof.txt' 2>/dev/null |
    grep -v -- '-20[0-9][0-9]-' | while read -r f; do cp "$f" "$dir/"; done

  grep -q '^OK (' "$dir/a.instrument.txt" || verdict=1
  say "peer saw (first and last):"
  grep reachable "$dir/peer.log" | sed -n '1p;$p' | sed 's/^/  /'
  say "profiles:"
  local f
  for f in "$dir"/*-prof.txt; do
    [ -f "$f" ] && printf '  %6d  %s\n' "$(wc -l <"$f")" "${f##*/}"
  done
  say "$([ $verdict = 0 ] && echo PASSED || echo FAILED) — $dir"
  [ $verdict = 0 ] || tail -30 "$dir/a.instrument.txt"
  return $verdict
}

cmd_apply() {
  local dir; dir=$(readlink -f "$OUT/latest") || die "no run yet"
  local base="$dir/BaselineProfileGenerator_startupAndFirstConversation-baseline-prof.txt"
  local start="$dir/BaselineProfileGenerator_startupProfileForDexLayout-startup-prof.txt"
  if [ ! -f "$base" ] || [ ! -f "$start" ]; then die "$dir lacks one of the two profiles"; fi
  # The two paths are not symmetrical (.agents/context/baseline-profile.md): the startup file must be in the directory.
  cp "$base" "$ROOT/app/src/main/baseline-prof.txt"
  cp "$start" "$ROOT/app/src/main/baselineProfiles/startup-prof.txt"
  (cd "$ROOT" && git diff --stat -- app/src/main/baseline-prof.txt app/src/main/baselineProfiles/startup-prof.txt)
}

PEER_PID=
stop_peer() { [ -z "$PEER_PID" ] || kill "$PEER_PID" 2>/dev/null || true; PEER_PID=; }

cmd_down() {
  local side
  for side in "${SIDES[@]}"; do KNIT_HCI_EMU_PORT=$(console_of "$side") "$HB" down "$side" || true; done
  "$ADB" kill-server 2>/dev/null || true
}

case "${1:-}" in
  up)      shift; cmd_up "$@" ;;
  install) cmd_install ;;
  run)     trap stop_peer EXIT; cmd_run ;;
  apply)   cmd_apply ;;
  down)    cmd_down ;;
  all)     trap 'stop_peer; cmd_down' EXIT; cmd_up; cmd_install; cmd_run ;;
  *)       die "one of: up install run apply down all" ;;
esac
