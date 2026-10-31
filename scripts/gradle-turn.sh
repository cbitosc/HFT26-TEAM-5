#!/usr/bin/env bash
#
# Run a command while holding this worktree's Gradle turn, so Gradle runs in one worktree never overlap.
#
# WHY THIS EXISTS. The Stop hook spawns every owed reviewer at once, and each runs Gradle in the same worktree. Two
# `testDebugUnitTest` runs that overlap rewrite each other's test-results/binary/ and the first dies with
# `NoSuchFileException` though every test passed (.agents/context/testing.md, the Gradle 9.5 result race); two compiles
# of a moved tree write the same intermediates; and every test JVM is ~2.5 GB, so a crowd of them has run the
# workstation out of memory. Reviewers read in parallel and take turns at Gradle.
#
# The turn is an flock on <git dir>/gradle-turn.lock, so each worktree has its own and worktrees never wait on each
# other. It is re-entrant: a script that holds the turn (lab-chaos.sh, unit-stress.sh) and runs another under it does
# not wait on itself. Waits up to GRADLE_TURN_WAIT seconds (default 3600), then fails. Without flock (macOS), the
# command runs at once.
#
# Usage: scripts/gradle-turn.sh <command> [args...]
#   e.g. scripts/gradle-turn.sh timeout 900 ./gradlew :app:testDebugUnitTest -q --tests 'app.getknit.knit.FooTest'
set -euo pipefail

(($#)) || { echo "usage: scripts/gradle-turn.sh <command> [args...]" >&2; exit 2; }
if [[ -n "${KNIT_GRADLE_TURN:-}" ]] || ! command -v flock >/dev/null 2>&1; then
  exec "$@"
fi
lock="$(git -C "$(dirname "$0")/.." rev-parse --absolute-git-dir)/gradle-turn.lock"
exec 9>>"$lock"
if ! flock --nonblock 9; then
  echo "gradle-turn: another Gradle run holds this worktree; waiting for it (up to ${GRADLE_TURN_WAIT:-3600} s)" >&2
  flock --wait "${GRADLE_TURN_WAIT:-3600}" 9 || { echo "gradle-turn: still busy after the wait; giving up" >&2; exit 75; }
fi
export KNIT_GRADLE_TURN=1
exec "$@"
