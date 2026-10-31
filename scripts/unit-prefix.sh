#!/usr/bin/env bash
#
# Run the tests as they are now against the code as it was before the change, many times on one core.
#
# WHY THIS EXISTS. A regression test proves its fix only if it goes red on the pre-fix code on every run. For a test
# that drives real threads that is a property of the schedule: `TransferListenerTest` as first committed went red on
# 30 of 30 runs with free cores and still passed 3 to 7 of 30 pinned to one, because its latch held only the second of two
# listeners and a cancelled coroutine never arrived (2026-10-17). One red run clears nothing. This is the
# unit-test-reviewer's "fails without the change" check, and it never touches the tree it is run from.
#
# WHAT IT DOES. Adds a throwaway worktree at BASE (default HEAD: the code before the uncommitted change), copies in
# every file under app/src/test that differs from BASE in this tree (the tests as written now, deletions included)
# and local.properties, compiles there once, and runs the named classes RUNS times through JUnitCore on the Test
# task's own JVM, classpath and arguments, pinned to one core. Each run's failures are tallied per test method; the
# regression test should read red on every run. A test that does not compile against BASE is reported as such: it
# needs the change's API, so it proves nothing about the change's behaviour. The worktree is removed at the end.
# A test that skips (`assumeTrue`) reads green here, as JUnitCore does not report assumption failures.
#
# Usage: scripts/unit-prefix.sh --class <fully.qualified.Class> [--class ...] [--base <rev>] [--runs <n>] [--all-cores]
#   --class      a test class to run (JUnitCore runs whole classes; read the method you care about in the tally)
#   --base       the revision whose main code to run against (default HEAD; HEAD^ for a change already committed)
#   --runs       runs per class (default 30)
#   --all-cores  do not pin to one core (the default pin is the one-vCPU runner, where the windows open)
set -euo pipefail

cd "$(dirname "$0")/.."
root=$(pwd)

classes=()
base=HEAD
runs=30
pin=1
while (($#)); do
  case "$1" in
    --class) classes+=("$2"); shift 2 ;;
    --base) base="$2"; shift 2 ;;
    --runs) runs="$2"; shift 2 ;;
    --all-cores) pin=""; shift ;;
    -h | --help) sed -n '2,/^set -euo/p' "$0" | sed 's/^# \{0,1\}//;$d'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
((${#classes[@]})) || { echo "unit-prefix: name the test classes with --class" >&2; exit 2; }
rev=$(git rev-parse --verify --short "$base^{commit}")

tmp=$(mktemp -d "${TMPDIR:-/tmp}/unit-prefix.XXXXXX")
wt="$tmp/tree"
cleanup() {
  git -C "$root" worktree remove --force "$wt" >/dev/null 2>&1 || true
  rm -rf "$tmp"
}
trap cleanup EXIT
git worktree add --quiet --detach "$wt" "$rev"

# The tests as they are in this tree, over the main code as it is at BASE.
overlaid=0
while IFS= read -r f; do
  if [[ -e "$f" ]]; then
    mkdir -p "$wt/$(dirname "$f")"
    cp "$f" "$wt/$f"
  else
    rm -f "$wt/$f"
  fi
  overlaid=$((overlaid + 1))
done < <({
  git diff --name-only "$rev" -- app/src/test
  git ls-files --others --exclude-standard -- app/src/test
} | sort -u)
[[ -f local.properties ]] && cp local.properties "$wt/"
echo "unit-prefix: main code at $rev, $overlaid test file(s) from this tree, ${classes[*]}"

# The Test task's launcher, JVM arguments and classpath, written as a java @argfile; asking for them builds them.
cat >"$tmp/args.gradle" <<'EOF'
gradle.projectsEvaluated {
    def app = gradle.rootProject.project(':app')
    app.tasks.register('knitPrefixArgs') {
        def test = app.tasks.getByName('testDebugUnitTest')
        dependsOn(test.classpath, test.testClassesDirs)
        doLast {
            def q = { String s -> '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"' }
            // Kover's coverage agent is attached at execution and its jar never built here: coverage is beside the point.
            def jvm = test.allJvmArgs.findAll { !(it.startsWith('-javaagent:') && it.contains('kover')) }
            def lines = jvm.collect(q) + ['-cp', q(test.classpath.asPath)]
            new File(System.getenv('KNIT_PREFIX_DIR'), 'java').text = test.javaLauncher.get().executablePath.asFile.absolutePath
            new File(System.getenv('KNIT_PREFIX_DIR'), 'args').text = lines.join('\n') + '\n'
        }
    }
}
EOF
set +e
(cd "$wt" && KNIT_PREFIX_DIR="$tmp" ./gradlew :app:knitPrefixArgs --init-script "$tmp/args.gradle" \
  --no-configuration-cache --no-daemon --console=plain -q) >"$tmp/build.log" 2>&1
status=$?
set -e
if ((status != 0)); then
  if grep -q 'compileDebugUnitTestKotlin' "$tmp/build.log"; then
    echo "unit-prefix: the tests do not compile against $rev — they need the change's API, so they prove nothing about"
    echo "its behaviour there. First errors:"
    grep -m 8 '^e: ' "$tmp/build.log" || tail -n 20 "$tmp/build.log"
  else
    echo "unit-prefix: the build at $rev failed:"
    tail -n 30 "$tmp/build.log"
  fi
  exit 3
fi

java=$(cat "$tmp/java")
cmd=("$java" "@$tmp/args" org.junit.runner.JUnitCore "${classes[@]}")
((pin)) && cmd=(taskset -c 0 "${cmd[@]}")
for ((i = 1; i <= runs; i++)); do
  # The Test task runs in the module directory; tests that read src/main/assets rely on it.
  (cd "$wt/app" && "${cmd[@]}") >"$tmp/run$i.log" 2>&1 || true
done

python3 - "$tmp" "$runs" "$rev" "${pin:+one core}" <<'EOF'
import glob, re, sys
tmp, runs, rev, where = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4] or "all cores"
red, firsts, totals, broken, dead = {}, {}, set(), 0, ""
for i in range(1, runs + 1):
    out = open(f"{tmp}/run{i}.log", errors="replace").read()
    m = re.search(r"Tests run: (\d+),\s+Failures: (\d+)", out) or re.search(r"^OK \((\d+) tests?\)", out, re.M)
    if not m:
        broken += 1
        dead = dead or "\n".join(out.splitlines()[-15:])
        continue
    totals.add(int(m.group(1)))
    for name, cls, first in re.findall(r"^\d+\) (.+?)\((.+?)\)\n(.*)$", out, re.M):
        key = f"{cls}.{name}"
        red[key] = red.get(key, 0) + 1
        firsts.setdefault(key, first[:200])
lines = []
for key in sorted(red, key=lambda k: -red[k]):
    lines.append(f"red {red[key]}/{runs - broken}  {key}\n     {firsts[key]}")
if broken:
    lines.append(f"unit-prefix: {broken} run(s) printed no JUnit summary (the JVM died?); the first one ended:\n{dead}")
lines.append(f"unit-prefix: {len(red)} of {max(totals, default=0)} test(s) went red on the code at {rev} ({runs} runs, {where});"
             " a regression test should read red on every run")
print("\n".join(lines))
sys.exit(3 if broken == runs else 0)
EOF
