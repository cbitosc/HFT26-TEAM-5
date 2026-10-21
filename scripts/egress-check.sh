#!/usr/bin/env bash
#
# Egress check: install Knit fresh on an emulator, leave every setting at its default, run it for a while, and fail
# if the app's own UID sent or received a single byte over IP. The README promises "a fresh install makes no network
# calls at all"; this is that sentence as a test.
#
# Usage:
#   scripts/egress-check.sh                       # build the staging APK (release + R8, debug-signed), boot, check
#   scripts/egress-check.sh --variant debug       # the debug build instead (every experimental flag on)
#   scripts/egress-check.sh --variant debug --control
#                                                 # …then prove the check can fail: switch the relay plane on
#                                                 # through the debug bridge and expect the dial to be counted
#   scripts/egress-check.sh --apk <file>          # check an APK already built (no Gradle)
#   scripts/egress-check.sh --soak 600            # run for ten minutes instead of the default three
#   scripts/egress-check.sh --keep                # leave the emulator up afterwards
#
# Why an emulator, and why it is enough. An emulator has no Wi-Fi Aware and no real Bluetooth, so the mesh can never
# put an IP packet on the air there: any byte charged to Knit's UID is a byte towards the Internet (a DNS lookup
# counts — netd charges and chowns the resolver's socket to the asking app). The emulator's network is validated, so
# every opener behind `net/InternetGate` would really try. Two independent witnesses watch the UID:
#   - the kernel's per-UID traffic counters, read through `dumpsys netstats` after a forced poll — cumulative, so a
#     socket that lived for a millisecond is still in the total;
#   - a sample of /proc/net/{tcp,tcp6,udp,udp6} every two seconds, which names the remote end of anything caught open.
# Loopback is never counted by the first and is filtered out of the second.
#
# Before Knit runs, a self-test pings the emulator's host from the shell UID and expects the counters to see it, so a
# pass can never come from blind accounting. `--control` goes further and makes Knit itself dial (a closed port on the
# host, 10.0.2.2:9, so nothing leaves the machine).
#
# Default settings means what a person who installs the app and grants the radios gets: the install grants the radio
# and notification permissions and takes back the ones onboarding never asks for (entry is gated on the radio grants
# alone, ADR 2026-09.nzpr, so the app goes straight to the chat list), and nothing else is touched. The run opens the app, sends it home, brings it
# back, and leaves it idle with the mesh service up.
#
# The emulator lives on an adb server of its own (port 5039, KNIT_EGRESS_ADB_PORT) and takes a console port past
# 5585, the top of the range a 5037 server scans, so nothing here can reach a lab phone on network adb, and an
# install can never land on one (rules/devices.md). A `--serial` that is not an emulator is refused.
# Env: KNIT_EGRESS_AVD picks the AVD (Knit_A11y_API34, an aosp_atd image with no GMS, so nothing else on the device
#      shares the network much); KNIT_EGRESS_PORT the console port (5612); KNIT_EGRESS_BOOT_S the boot budget (300).

set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
export ANDROID_ADB_SERVER_PORT=${KNIT_EGRESS_ADB_PORT:-5039}
export ADB_LOCAL_TRANSPORT_MAX_PORT=5625
AVD=${KNIT_EGRESS_AVD:-Knit_A11y_API34}
PORT=${KNIT_EGRESS_PORT:-5612}
BOOT_S=${KNIT_EGRESS_BOOT_S:-300}
PKG=app.getknit.knit
OUT="$ROOT/build/egress-check/$(date +%Y%m%d-%H%M%S)"

variant=staging
apk=
soak=180
control=0
keep=0
serial=

usage() { sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }
die() { echo "egress-check: $*" >&2; exit 2; }

while [ $# -gt 0 ]; do
  case "$1" in
    --variant) variant=$2; shift 2 ;;
    --apk) apk=$2; shift 2 ;;
    --soak) soak=$2; shift 2 ;;
    --control) control=1; shift ;;
    --keep) keep=1; shift ;;
    --serial) serial=$2; shift 2 ;;
    -h|--help) usage ;;
    *) echo "unknown argument: $1" >&2; usage 2 ;;
  esac
done
case "$variant" in staging|debug) ;; *) die "--variant is staging or debug" ;; esac
mkdir -p "$OUT"

# --- emulator ---------------------------------------------------------------------------------------------------

booted_here=0
# shellcheck disable=SC2317,SC2329 # invoked by the EXIT trap below
cleanup() {
  if [ "$booted_here" = 1 ] && [ "$keep" = 0 ]; then
    "$ADB" -s "$serial" emu kill >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [ -z "$serial" ]; then
  serial="emulator-$PORT"
  if ! "$ADB" devices | grep "^${serial}[[:space:]]*device" >/dev/null; then
    echo "booting $AVD on $serial (log: $OUT/emulator.log)"
    # -read-only: the AVD is shared with the a11y suite; this run leaves it exactly as it found it.
    nohup "$EMULATOR" -avd "$AVD" -port "$PORT" -no-window -no-audio -no-snapshot -read-only -no-boot-anim \
      >"$OUT/emulator.log" 2>&1 &
    booted_here=1
  fi
fi
case "$serial" in emulator-*) ;; *) die "$serial is not an emulator; this check never drives a phone" ;; esac
A=("$ADB" -s "$serial")
sh_() { "${A[@]}" shell "$@" | tr -d '\r'; }

timeout "$BOOT_S" "${A[@]}" wait-for-device || die "$serial did not come up in ${BOOT_S}s"
deadline=$((SECONDS + BOOT_S))
until [ "$(sh_ getprop sys.boot_completed)" = 1 ]; do
  [ $SECONDS -lt $deadline ] || die "$serial did not finish booting in ${BOOT_S}s"
  sleep 2
done
[ "$(sh_ getprop ro.kernel.qemu)" = 1 ] || [ "$(sh_ getprop ro.boot.qemu)" = 1 ] \
  || die "$serial does not report itself as an emulator"

# A pass on a phone with no Internet would prove nothing: every opener sits behind a validated route.
deadline=$((SECONDS + 60))
# Validation can be reported a moment before the default route is up, so wait for both.
until sh_ dumpsys connectivity | grep "IS_VALIDATED" >/dev/null && sh_ ping -c 1 -W 2 10.0.2.2 >/dev/null 2>&1; do
  [ $SECONDS -lt $deadline ] || die "no validated network on $serial: the check would be vacuous"
  sleep 2
done

# --- measurement --------------------------------------------------------------------------------------------------

# Total rx/tx bytes charged to one UID, across every network and bucket, after a forced poll. Prints "rx tx".
uid_bytes() {
  sh_ dumpsys netstats --poll >/dev/null
  sh_ dumpsys netstats detail | awk -v uid="$1" '
    /^UID stats:/ { on = 1; next }
    /^[A-Za-z].*:$/ && on { on = 0 }
    on && /ident=/ { mine = ($0 ~ (" uid=" uid " ")) }
    on && mine && /st=/ {
      for (i = 1; i <= NF; i++) { split($i, kv, "="); if (kv[1] == "rb") rb += kv[2]; if (kv[1] == "tb") tb += kv[2] }
    }
    END { printf "%d %d\n", rb, tb }'
}

# The networks the UID's bytes went over, for the report.
uid_idents() {
  sh_ dumpsys netstats detail | awk -v uid="$1" '
    /^UID stats:/ { on = 1; next }
    /^[A-Za-z].*:$/ && on { on = 0 }
    on && /ident=/ && ($0 ~ (" uid=" uid " ")) { sub(/^ +/, ""); print }'
}

# Non-loopback sockets the UID holds right now, one "proto remote state" line each, addresses decoded.
uid_sockets() {
  sh_ "for p in tcp tcp6 udp udp6; do sed \"s/^/\$p /\" /proc/net/\$p 2>/dev/null; done" | awk -v uid="$1" '
    function hex(s,   i, n) { n = 0; s = toupper(s); for (i = 1; i <= length(s); i++) n = n * 16 + index("0123456789ABCDEF", substr(s, i, 1)) - 1; return n }
    function v4(h) { return hex(substr(h, 7, 2)) "." hex(substr(h, 5, 2)) "." hex(substr(h, 3, 2)) "." hex(substr(h, 1, 2)) }
    function addr(a,   h, p) {
      split(a, hp, ":"); h = hp[1]; p = hex(hp[2])
      if (length(h) == 8) return v4(h) ":" p
      if (substr(h, 1, 24) == "0000000000000000FFFF0000") return v4(substr(h, 25, 8)) ":" p
      return "[" h "]:" p
    }
    $1 == "sl" || $2 == "sl" { next }
    $9 != uid { next }
    {
      rem = $4; split(rem, hp, ":")
      if (hp[1] ~ /^0+$/) next                                         # listening / unconnected
      if (hp[1] ~ /7F$/ && length(hp[1]) == 8) next                    # 127.x
      if (hp[1] == "00000000000000000000000001000000") next            # ::1
      if (hp[1] ~ /^0000000000000000FFFF0000/ && substr(hp[1], 31, 2) == "7F") next   # ::ffff:127.x
      print $1, addr(rem), $5
    }'
}

echo "self-test: the shell UID's ping to the host must be counted"
read -r _ s_tx0 < <(uid_bytes 2000)
sh_ ping -c 3 -W 2 10.0.2.2 >/dev/null || die "ping 10.0.2.2 failed on $serial"
read -r _ s_tx1 < <(uid_bytes 2000)
[ $((s_tx1 - s_tx0)) -gt 0 ] || die "netstats did not count the shell's own ping (tx $s_tx0 → $s_tx1): accounting is blind here"
echo "  counted $((s_tx1 - s_tx0)) bytes out — accounting works"

# --- the app ------------------------------------------------------------------------------------------------------

if [ -z "$apk" ]; then
  task=":app:assembleStaging"; apk="$ROOT/app/build/outputs/apk/staging/app-staging.apk"
  if [ "$variant" = debug ]; then task=":app:assembleDebug"; apk="$ROOT/app/build/outputs/apk/debug/app-debug.apk"; fi
  echo "building $task"
  (cd "$ROOT" && scripts/gradle-turn.sh ./gradlew "$task" -q)
fi
[ -f "$apk" ] || die "no APK at $apk"

"${A[@]}" uninstall "$PKG" >/dev/null 2>&1 || true
"${A[@]}" install -g "$apk" >/dev/null || die "install of $apk failed"
uid=$(sh_ pm list packages -U "$PKG" | sed -n "s/^package:$PKG uid:\([0-9]*\).*/\1/p" | head -1)
[ -n "$uid" ] || die "could not read $PKG's UID"
# `install -g` grants every runtime permission, which is more than a person gives at onboarding: take back the ones
# the front door never asks for, so the run is the default install and not a maximal one.
sdk=$(sh_ getprop ro.build.version.sdk)
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION CAMERA RECORD_AUDIO; do
  [ "$sdk" -ge 33 ] || case "$p" in ACCESS_*) continue ;; esac   # 29-32: location rides the radio grant
  sh_ pm revoke "$PKG" "android.permission.$p" 2>/dev/null || true
done
# Not `grep -q`: it exits at the first match, and pipefail then reads dumpsys's SIGPIPE as a miss.
debuggable=0; sh_ dumpsys package "$PKG" | grep "flags=.*DEBUGGABLE" >/dev/null && debuggable=1
[ "$control" = 0 ] || [ "$debuggable" = 1 ] || die "--control needs a debuggable APK (the debug bridge): add --variant debug"

# A reinstalled package can get its old UID back, and netstats keeps that UID's history: measure from here.
read -r rx0 tx0 < <(uid_bytes "$uid")
echo "installed $(basename "$apk") as uid $uid on $serial (API $sdk); soaking ${soak}s with default settings"

"${A[@]}" logcat -c || true
sh_ am start -W -n "$PKG/.MainActivity" >/dev/null
seen="$OUT/sockets.txt"; : >"$seen"
half=$((SECONDS + soak / 2)); end=$((SECONDS + soak)); bounced=0
while [ $SECONDS -lt $end ]; do
  uid_sockets "$uid" >>"$seen"
  if [ "$bounced" = 0 ] && [ $SECONDS -ge $half ]; then
    # Background and back: ON_STOP, then the ON_RESUME heal and the route effect's start check run again.
    sh_ input keyevent KEYCODE_HOME; sleep 5
    sh_ am start -W -n "$PKG/.MainActivity" >/dev/null
    bounced=1
  fi
  sleep 2
done
sh_ pidof "$PKG" >/dev/null || echo "warning: $PKG is not running at the end of the soak (see $OUT/logcat.txt)"
read -r rx1 tx1 < <(uid_bytes "$uid")
uid_idents "$uid" >"$OUT/netstats-idents.txt"
"${A[@]}" logcat -d >"$OUT/logcat.txt" 2>/dev/null || true

rx=$((rx1 - rx0)); tx=$((tx1 - tx0))
fail=0
echo "result: $rx bytes in, $tx bytes out charged to uid $uid"
if [ "$rx" -ne 0 ] || [ "$tx" -ne 0 ]; then
  fail=1
  echo "FAIL: Knit used the network with default settings. Networks it used:"
  sed 's/^/  /' "$OUT/netstats-idents.txt"
fi
if [ -s "$seen" ]; then
  fail=1
  echo "FAIL: sockets caught open (proto, remote, state):"
  sort -u "$seen" | sed 's/^/  /'
fi
[ "$fail" = 1 ] || echo "PASS: no outbound traffic in ${soak}s"

# --- the control: prove the check can fail --------------------------------------------------------------------------

if [ "$control" = 1 ]; then
  echo "control: switching the relay plane on, towards a closed port on the host"
  bridge() { sh_ "am broadcast -f 0x20 -p $PKG -a $PKG.debug.SPOOL $*" >>"$OUT/control.txt"; }
  # The debug build comes with a real relay configured: drop it first, so the dial the control provokes goes to the
  # closed port and nowhere else. The plane reconciles its relay list on a 60 s tick, so the dial can take that long.
  for url in $(sh_ "am broadcast -f 0x20 -p $PKG -a $PKG.debug.SPOOL" | grep -o '"configured":\[[^]]*' | grep -o 'wss\?:[^"]*' | sed 's|\\/|/|g'); do
    bridge "--es drop $url"
  done
  bridge "--es url ws://10.0.2.2:9/spool/v1"
  bridge "--ez on true"
  cseen="$OUT/control-sockets.txt"; : >"$cseen"
  tx2=$tx1; cend=$((SECONDS + 90))
  while [ $SECONDS -lt $cend ] && [ "$tx2" -le "$tx1" ]; do
    uid_sockets "$uid" >>"$cseen"; sleep 2
    read -r _ tx2 < <(uid_bytes "$uid")
  done
  bridge "--es drop ws://10.0.2.2:9/spool/v1"
  bridge "--ez on false"
  if [ $((tx2 - tx1)) -gt 0 ]; then
    echo "  control counted $((tx2 - tx1)) bytes out — the check sees Knit's own sockets"
    if [ -s "$cseen" ]; then
      echo "  and the sampler caught: $(sort -u "$cseen" | head -3 | paste -sd ';' -)"
    else
      echo "  (the sampler caught no socket open: the dial is shorter than its 2 s tick; the counters still saw it)"
    fi
  else
    echo "FAIL: the control's dial was not counted — the check is blind to this app (see $OUT/control.txt)"
    fail=1
  fi
fi

echo "artifacts: $OUT"
exit "$fail"
