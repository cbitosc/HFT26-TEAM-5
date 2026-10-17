#!/usr/bin/env bash
# Stop hook: an uncommitted change under a reviewer's paths gets that reviewer before Claude finishes the turn.
#
# Each reviewer persona (`.agents/personas/<name>.md`) hunts one class of defect from a catalogue of the ones
# this codebase has already shipped and fixed. review-gate.conf maps each to the paths it owns and its stage; this
# script asks for the reviewers of the earliest stage with a review pending, in one message, so they run in parallel
# (.agents/rules/review-rounds.md: the subsystem reviewers first, the test reviewers once none of those is pending).
#
# no uncommitted change under a reviewer's paths                                      -> not pending.
# same HEAD and files as its last stamp, and within its follow-up lines of that diff  -> not pending: the caller
#                                                                                       is applying its findings.
# anything else                                                                       -> pending; owed unless
#                                                                                       this same state was asked
#                                                                                       for already.
# A stamp covers one review round: a commit since that touches the reviewer's paths, or a file the review never saw,
# needs a fresh one — else a small new change would ride an old stamp's slack. With no such commit, the next pass is a
# follow-up: the diff since the stamp is written to `<git dir>/claude-review/<name>.delta` and the message names it,
# so the reviewer reads the delta and the closure of its previous findings rather than the whole change again.
# nothing owed -> exit 0, Claude stops normally; else exit 2 with the instruction on stderr, which Claude
# Code feeds back.
#
# `--stamp <name>` records what a review approved: each reviewer runs it as its last step, so the few-line
# edits that apply its findings don't ask for a review of the review. The state lives in the git dir, so each
# worktree keeps its own. Honors `stop_hook_active`, so the enforced round can always end.

set -uo pipefail

conf="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/review-gate.conf"
project_dir="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
cd "$project_dir" || exit 0
git rev-parse --git-dir >/dev/null 2>&1 || exit 0
state="$(git rev-parse --git-dir)/claude-review"

reviewers() { grep -v -e '^#' -e '^[[:space:]]*$' "$conf"; }

# What a reviewer reads: the tracked diff plus every untracked file's bytes.
snapshot() {
  git diff HEAD -- "$@"
  git ls-files --others --exclude-standard -- "$@" | sort | while read -r f; do
    printf '%s\n' "$f"
    cat "$f"
  done
}

# The working state of the paths as a git tree, written through a throwaway index so the real one is untouched: the
# stamp keeps it, and a follow-up pass's delta is a plain `git diff` between two of them.
worktree_tree() {
  local idx
  idx=$(mktemp)
  cp "$(git rev-parse --git-dir)/index" "$idx" 2>/dev/null || : >"$idx"
  GIT_INDEX_FILE="$idx" git add -A -- "$@" >/dev/null 2>&1
  GIT_INDEX_FILE="$idx" git write-tree
  rm -f "$idx"
}

# Whether a commit since <name>'s stamp left its paths alone: HEAD is the stamped one, or the commits between the two
# touch none of those paths (a docs or hook commit), so the stamped diff still reads against HEAD.
same_base() {
  local name=$1 head
  shift
  head=$(cat "$state/$name.head" 2>/dev/null) || return 1
  [ "$head" = "$(git rev-parse HEAD)" ] && return 0
  git cat-file -e "$head^{commit}" 2>/dev/null && git diff --quiet "$head" HEAD -- "$@"
}

changed_files() { git status --porcelain --untracked-files=all -- "$@" | awk '{print $NF}' | sort; }

fingerprint() { printf '%s\n' "$1" | sha256sum | cut -d' ' -f1; }

# Content lines only: a follow-up edit shifts the line numbers in every later hunk header and the blob ids in
# each `index` line, which would otherwise count as changes of their own.
content() { grep -v -e '^index ' -e '^@@ '; }

if [ "${1:-}" = "--stamp" ]; then
  name="${2:?usage: review-gate.sh --stamp <reviewer>}"
  line=$(reviewers | awk -F'\t' -v n="$name" '$1 == n')
  if [ -z "$line" ]; then
    echo "review-gate: no reviewer named '$name' in $conf" >&2
    exit 1
  fi
  IFS=$'\t' read -r _ _ _ paths _ <<<"$line"
  read -ra specs <<<"$paths"
  current=$(snapshot "${specs[@]}")
  mkdir -p "$state"
  printf '%s\n' "$current" >"$state/$name.snapshot"
  changed_files "${specs[@]}" >"$state/$name.files"
  git rev-parse HEAD >"$state/$name.head"
  worktree_tree "${specs[@]}" >"$state/$name.tree"
  rm -f "$state/$name.delta"
  fingerprint "$current" >"$state/$name.asked"
  echo "$name: review stamped ($(wc -l <"$state/$name.snapshot") lines of diff)"
  exit 0
fi

input=$(cat)
if [ "$(printf '%s' "$input" | jq -r '.stop_hook_active // false' 2>/dev/null)" = "true" ]; then
  exit 0
fi

# Pass 1: which reviewers have a review pending, and the earliest stage among them.
pending=()
first_stage=""
while IFS=$'\t' read -r name stage follow_up paths relay <&3; do
  read -ra specs <<<"$paths"
  changed=$(changed_files "${specs[@]}")
  [ -n "$changed" ] || continue

  current=$(snapshot "${specs[@]}")
  same_round=""
  if [ -f "$state/$name.snapshot" ] && same_base "$name" "${specs[@]}"; then
    same_round=1
    if [ -z "$(comm -23 <(printf '%s\n' "$changed") "$state/$name.files" 2>/dev/null)" ]; then
      delta=$(diff <(content <"$state/$name.snapshot") <(printf '%s\n' "$current" | content) | grep -c '^[<>]')
      [ "$delta" -le "$follow_up" ] && continue
    fi
  fi
  pending+=("$name"$'\t'"$stage"$'\t'"$paths"$'\t'"$relay"$'\t'"$same_round")
  if [ -z "$first_stage" ] || [ "$stage" -lt "$first_stage" ]; then first_stage=$stage; fi
done 3< <(reviewers)

[ ${#pending[@]} -gt 0 ] || exit 0

# Pass 2: ask for the pending reviewers of that stage, unless this same state was asked for already (they may be
# running now). A later stage waits, owed or not, until the earlier one has stamped.
owed=""
relays=""
followups=""
later=""
for entry in "${pending[@]}"; do
  IFS=$'\t' read -r name stage paths relay same_round <<<"$entry"
  if [ "$stage" != "$first_stage" ]; then
    later+=" $name"
    continue
  fi
  read -ra specs <<<"$paths"
  print=$(fingerprint "$(snapshot "${specs[@]}")")
  [ "$(cat "$state/$name.asked" 2>/dev/null)" = "$print" ] && continue
  mkdir -p "$state"
  printf '%s\n' "$print" >"$state/$name.asked"

  label="$name:"
  if [ -n "$same_round" ] && [ -s "$state/$name.tree" ]; then
    git diff "$(cat "$state/$name.tree")" "$(worktree_tree "${specs[@]}")" -- "${specs[@]}" >"$state/$name.delta"
    label="$name (follow-up pass, its delta: $state/$name.delta, $(wc -l <"$state/$name.delta") lines):"
    followups+=" $name"
  else
    rm -f "$state/$name.delta"
  fi
  owed+="  $label"$'\n'"$(changed_files "${specs[@]}" | sed 's/^/    /')"$'\n'
  relays+="  $name: $relay"$'\n'
done

[ -n "$owed" ] || exit 0
{
  echo "Stop blocked: these changes have no review from the reviewer that owns them:"
  printf '%s' "$owed"
  echo
  echo "Spawn each reviewer named above (Agent tool, subagent_type = its name) on its files, all in one message so"
  echo "they run in parallel. Each reads the change against its catalogue (.agents/personas/<name>.md), runs its"
  echo "checks, and stamps what it reviewed (.agents/rules/review-rounds.md sets the pass and the labels)."
  if [ -n "$followups" ]; then
    echo "On a follow-up pass (${followups# }), hand the reviewer its delta file and its previous findings: it checks"
    echo "each finding closed and reviews the delta; a new finding outside it counts only if it is Blocking."
  fi
  if [ -n "$later" ]; then
    echo "Asked for once these have stamped (test reviewers come second):${later}."
  fi
  echo "Every finding comes labelled Blocking or Non-blocking. Fix each blocking one, or say why it does not hold;"
  echo "apply the non-blocking ones in the same batch or list them for the user as follow-ups. Then give the user"
  echo "the findings and, from each report:"
  printf '%s' "$relays"
} >&2
exit 2
