#!/usr/bin/env bash
#
# Sweep mesh-lab scenarios under seeded scheduling noise (app/src/test/.../mesh/lab/LabChaos.kt).
#
# WHY THIS EXISTS. A mesh-lab flake is a window a workstation closes before anyone looks: a counter bumped
# after the `send` a scenario waited on, an emission before a late subscriber. CI's slow runners open those
# windows one run in thirty; the throttled-core loop opens them at twenty minutes a run. Chaos mode stretches
# the lab's dispatch, pipe and collector timing on purpose (never what a pipe means), so a scenario that
# fails here is one a slow runner fails too, given the same luck. Run it on any lab test you write or touch
# before you push it.
#
# WHAT IT DOES. One Gradle invocation: every selected scenario runs RUNS times on seeds SEED, SEED+1, ...
# (each run a fresh MeshLab), stopping a scenario's slice at its first failure. The seeds are split across
# SHARDS test JVMs that run in parallel (`labChaosShard<i>` in app/build.gradle.kts): under chaos a JVM is
# mostly asleep in the stalls it injects, so one JVM per seed slice is nearly free time. Then it tallies the
# per-class XMLs (never Gradle's summary — see the Gradle 9.5 result race in .agents/context/testing.md),
# prints each failure with its seed and names every scenario slow enough to be waiting out a production timer
# the lab should shorten. A seed replays a distribution of delays, not an exact schedule: re-run a failing
# seed a few times, and fix the scenario by awaiting the event it needs.
#
# ONE SWEEP PER TREE. Each sweep is stamped (in the git dir, so per worktree) with a fingerprint of the tree
# it compiled. `--reuse` answers from that stamp instead of sweeping again when the tree has not moved and
# the stamp covers the asked-for patterns with at least as many runs — how the mesh-lab-reviewer avoids
# re-running the sweep its caller just ran.
#
# Usage: scripts/lab-chaos.sh [--tests <pattern>] [--runs <n>] [--seed <n>|random] [--shards <k>|auto]
#                             [--quota <pct>] [--reuse]
#   --tests   Gradle --tests filter, repeatable (default: the whole app.getknit.knit.mesh.lab package)
#   --runs    runs per scenario (default 20)
#   --seed    first seed (default: random, printed)
#   --shards  test JVMs to split the seeds across (default auto: a quarter of the cores, ~2.5 GB of free
#             memory each, at most 8 and never more than --runs)
#   --quota   also cap the run at <pct>% of one core (systemd-run), the slow-runner loop's throttle; one shard
#   --reuse   print the last sweep's result instead when it ran on this same tree and covers this request
set -euo pipefail

cd "$(dirname "$0")/.."

patterns=()
runs=20
seed=""
quota=""
shards=auto
reuse=""
while (($#)); do
  case "$1" in
    --tests) patterns+=("$2"); shift 2 ;;
    --runs) runs="$2"; shift 2 ;;
    --seed) seed="$2"; shift 2 ;;
    --shards) shards="$2"; shift 2 ;;
    --quota) quota="$2"; shift 2 ;;
    --reuse) reuse=1; shift ;;
    -h | --help) sed -n '2,/^set -euo/p' "$0" | sed 's/^# \{0,1\}//;$d'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
((${#patterns[@]})) || patterns=("app.getknit.knit.mesh.lab.*")
for p in "${patterns[@]}"; do
  [[ "$p" != *,* ]] || { echo "lab-chaos: a --tests pattern cannot hold a comma: $p" >&2; exit 2; }
done

# What the sweep's verdict depends on: HEAD plus every uncommitted byte the test JVM compiles or reads.
tree_paths=(app gradle build.gradle.kts settings.gradle.kts gradle.properties)
fingerprint() {
  {
    git rev-parse HEAD
    git diff HEAD --binary -- "${tree_paths[@]}"
    git ls-files --others --exclude-standard -- "${tree_paths[@]}" | sort | while read -r f; do
      printf '%s\n' "$f"
      cat "$f"
    done
  } | sha256sum | cut -d' ' -f1
}
state="$(git rev-parse --git-dir)/lab-chaos"
stamp="$state/last.json"
before=$(fingerprint)

if [[ -n "$reuse" && -f "$stamp" ]]; then
  set +e
  python3 - "$stamp" "$before" "$runs" "${patterns[@]}" <<'EOF'
import json, sys
stamp, tree, runs, asked = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4:]
try:
    last = json.load(open(stamp))
except (OSError, ValueError):
    sys.exit(100)
def covers(q, p):  # does the stamped pattern q select everything the asked-for pattern p does?
    return q == p or p.startswith(q + ".") or (q.endswith("*") and p.startswith(q[:-1]))
if last["tree"] != tree or last["runs"] < runs or not all(any(covers(q, p) for q in last["patterns"]) for p in asked):
    sys.exit(100)
print(f"lab-chaos: reusing the sweep already run on this tree ({' '.join(last['patterns'])}, {last['runs']} runs)")
print(last["report"], end="")
sys.exit(last["status"])
EOF
  status=$?
  set -e
  ((status == 100)) || exit "$status"
fi

if [[ -z "$seed" || "$seed" == random ]]; then seed=$((($(date +%s%N) / 1000) % 1000000000)); fi
if [[ -n "$quota" ]]; then
  shards=1 # the throttle is one core; more JVMs on it would only queue
elif [[ "$shards" == auto ]]; then
  cores=$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN)
  mem_kb=$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo 2>/dev/null || true)
  # A shard is a test JVM: the 1 GB heap testOptions gives it, ~0.75 GB of Robolectric metaspace, and its natives.
  by_mem=$((${mem_kb:-0} > 0 ? mem_kb / (2560 * 1024) : 8))
  shards=$((cores / 4))
  ((by_mem < shards)) && shards=$by_mem
  ((shards > 8)) && shards=8
fi
((shards > runs)) && shards=$runs
((shards >= 1)) || shards=1

results=app/build/test-results
rm -rf "$results"/labChaosShard*
mkdir -p app/build

tasks=()
for ((i = 0; i < shards; i++)); do tasks+=(":app:labChaosShard$i"); done
cmd=(./gradlew "${tasks[@]}" --continue --console=plain "-Pknit.labChaos=$seed" "-Pknit.labChaosRuns=$runs"
  "-Pknit.labChaosShards=$shards" "-Pknit.labChaosTests=$(IFS=,; echo "${patterns[*]}")")
if [[ -n "$quota" ]]; then
  cmd=(systemd-run --user --scope -q -p "CPUQuota=${quota}%" taskset -c 0 "${cmd[@]}" --no-daemon)
fi

echo "lab-chaos: seeds $seed..$((seed + runs - 1)) over $shards shard(s), ${patterns[*]}${quota:+, CPUQuota=$quota%}"
set +e
"${cmd[@]}" > "app/build/lab-chaos.log" 2>&1
gradle_status=$?
set -e

mkdir -p "$state"
python3 - "$results" "$seed" "$runs" "$gradle_status" "$stamp" "$before" "$(fingerprint)" "${patterns[@]}" <<'EOF'
import glob, json, re, sys, xml.etree.ElementTree as ET
results, seed, runs, gradle_status, stamp, before, after = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), sys.argv[5], sys.argv[6], sys.argv[7]
patterns = sys.argv[8:]
# A scenario that spends longer than this a run past its node boots, per node it built (LabChaos's MESHLAB-RUN
# line), is waiting on something real — a production timer the lab has not shortened (LabLimits), or a lost
# wake that only a timed round covers. Work grows with the nodes; a timer sat out does not. Under chaos the
# ordinary scenarios spend a few seconds a node there, the eight-node flood included.
SLOW_S_PER_NODE = 6.0
files = glob.glob(f"{results}/labChaosShard*/*.xml")
if not files:
    print("lab-chaos: no test results written — see app/build/lab-chaos.log"); sys.exit(3)
scenarios = {}  # name -> {"time", "fails": [...], "skipped"}
timed = {}  # name -> [runs, ms, boot ms, nodes], from the passing runs' MESHLAB-RUN lines
for f in sorted(files):
    root = ET.parse(f).getroot()
    err = "".join(e.text or "" for e in root.iter("system-err"))
    for m in re.finditer(r"MESHLAB-RUN (\S+) ms=(\d+) bootMs=(\d+) nodes=(\d+)", err):
        t = timed.setdefault(m.group(1), [0, 0, 0, 0])
        t[0] += 1; t[1] += int(m.group(2)); t[2] += int(m.group(3)); t[3] += int(m.group(4))
    for tc in root.iter("testcase"):
        name = f"{tc.get('classname')}.{tc.get('name')}"
        s = scenarios.setdefault(name, {"time": 0.0, "fails": [], "skipped": False})
        s["time"] += float(tc.get("time") or 0)
        if tc.find("skipped") is not None:
            s["skipped"] = True
            continue
        fail = tc.find("failure") if tc.find("failure") is not None else tc.find("error")
        if fail is None:
            continue
        where = [l for l in err.splitlines() if "MESHLAB-CHAOS-FAIL" in l and name in l]
        first = (fail.get("message") or "").splitlines()[0][:200] if fail.get("message") else ""
        s["fails"].append((where[-1].split("failed under ", 1)[-1] if where else "(seed not recorded)", first))
lines = []
ran = {n: s for n, s in scenarios.items() if not s["skipped"]}
failed = [n for n, s in ran.items() if s["fails"]]
for n in failed:
    for where, first in ran[n]["fails"]:
        lines.append(f"FAIL {n}\n     {where}\n     {first}")
for n, (count, ms, boot, built) in sorted(timed.items(), key=lambda kv: -(kv[1][1] - kv[1][2]) / max(kv[1][3], 1)):
    waited, per_node = (ms - boot) / count / 1000, (ms - boot) / max(built, 1) / 1000
    if n in ran and not ran[n]["fails"] and per_node > SLOW_S_PER_NODE:
        lines.append(f"slow {n}: {waited:.0f}s a run past its boots ({per_node:.0f}s a node) — a field timer the lab "
                     "should shorten? (testing.md, \"Chaos mode\")")
all_ms, all_boot = sum(t[1] for t in timed.values()), sum(t[2] for t in timed.values())
if all_ms:
    lines.append(f"lab-chaos: node boots took {100 * all_boot // all_ms}% of the scenarios' time")
skipped = len(scenarios) - len(ran)
lines.append(f"lab-chaos: {len(ran) - len(failed)}/{len(ran)} scenarios survived {runs} seeded runs each (first seed {seed})"
             + (f", {skipped} skipped" if skipped else ""))
status = 1 if failed else 0
broken = not failed and gradle_status != 0
if broken:
    lines.append("lab-chaos: Gradle failed with every XML green — see app/build/lab-chaos.log")
    status = gradle_status
report = "\n".join(lines) + "\n"
print(report, end="")
if before != after:
    print("lab-chaos: the tree moved during the sweep; not stamped for --reuse")
elif not broken:
    json.dump({"tree": before, "patterns": patterns, "runs": runs, "seed": seed, "status": status, "report": report}, open(stamp, "w"))
sys.exit(status)
EOF
