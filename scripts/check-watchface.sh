#!/usr/bin/env bash
#
# Check the Knit watch faces (`watchface/` and `watchface-monogram/`, ADR 2026-10.3y2s) the way Play's pipeline
# would, before a sideload.
#
# WHAT IT DOES. (1) each committed watchface.xml is what scripts/gen-watchface.py writes; then for each face,
# (2) it builds as a release APK that carries no dex; (3) its XML is valid Watch Face Format v4 by Google's own
# validator; (4) the APK fits Google's memory budget (10 MB always-on, 100 MB interactive), by Google's own evaluator.
#
# THE TOOLS are built from github.com/google/watchface at one pinned commit, into the git dir (per worktree,
# never tracked), and reused while that commit stands. They are build-machine tools on a prototype's path, not
# the release's: nothing here touches :app or what F-Droid rebuilds (.agents/context/distribution.md).
#
# Usage: scripts/check-watchface.sh   (JDK 21, network on the first run)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS_COMMIT=b6cdda0acd3e4c5d0be5624fcdc01209380029d1
CACHE="$(git -C "$ROOT" rev-parse --path-format=absolute --git-common-dir)/watchface-tools/$TOOLS_COMMIT"
REPO="$CACHE/watchface"
VALIDATOR="$REPO/third_party/wff/specification/validator/build/libs/wff-validator.jar"
FOOTPRINT="$REPO/play-validations/memory-footprint/build/libs/memory-footprint.jar"

python3 "$ROOT/scripts/gen-watchface.py" --check
echo "✓ both watchface.xml files match their generator"

if [[ ! -f "$VALIDATOR" || ! -f "$FOOTPRINT" ]]; then
    echo "… building Google's WFF tools at ${TOOLS_COMMIT:0:12} (once per commit)"
    rm -rf "$REPO" && mkdir -p "$CACHE"
    git -C "$CACHE" init -q watchface
    git -C "$REPO" fetch -q --depth 1 https://github.com/google/watchface.git "$TOOLS_COMMIT"
    git -C "$REPO" checkout -q FETCH_HEAD
    (cd "$REPO/third_party/wff" && ./gradlew -q :specification:validator:executable-jar)
    (cd "$REPO/play-validations" && ./gradlew -q :memory-footprint:executable-jar)
fi

(cd "$ROOT" && ./gradlew -q -Pknit.wear=true :watchface:assembleRelease :watchface-monogram:assembleRelease)

for FACE in watchface watchface-monogram; do
    APK="$ROOT/$FACE/build/outputs/apk/release/$FACE-release-unsigned.apk"
    XML="$ROOT/$FACE/src/main/res/raw/watchface.xml"
    echo "— $FACE"
    if unzip -l "$APK" | grep -q '\.dex$'; then
        echo "✗ $APK carries a dex; a Watch Face Format APK must have no code" >&2
        exit 1
    fi
    echo "✓ the face builds, with no code"

    java -jar "$VALIDATOR" 4 "$XML" 2>&1 | tee /dev/stderr | grep -q "PASSED"
    echo "✓ valid Watch Face Format v4"

    java -jar "$FOOTPRINT" --watch-face "$APK" --schema-version 4 --ambient-limit-mb 10 --active-limit-mb 100 \
        | tee /dev/stderr | grep -q "PASS"
    echo "✓ within the memory budget"
done
