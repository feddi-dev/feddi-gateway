#!/bin/bash
# repro-yaml-it-flake.sh — run :gateway:app:integrationTest in a loop to
# try to reproduce the YamlIntegrationTest @is parallel-lookup flake
# (is_big_selection / is_type_condition_alternatives) seen in CI.
#
# Usage:
#   scripts/repro-yaml-it-flake.sh [iterations]   # default 30
#
# Stops on the first failure and copies the HTML report + log to
# /tmp/feddi-flake-repro/ for inspection.
set -uo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
ITERATIONS="${1:-30}"

OUT=/tmp/feddi-flake-repro
mkdir -p "$OUT"
echo "Logs and any failed reports → $OUT"
echo "Running $ITERATIONS iterations of :app:integrationTest"

cd "$PROJECT_ROOT/gateway"

pass=0
fail=0
for i in $(seq 1 "$ITERATIONS"); do
  start=$(date +%s)
  log="$OUT/iter-${i}.log"
  if ./gradlew :app:integrationTest --rerun-tasks --console=plain >"$log" 2>&1; then
    pass=$((pass+1))
    elapsed=$(( $(date +%s) - start ))
    echo "[$i/$ITERATIONS] PASS  (${elapsed}s)"
    rm -f "$log"
  else
    fail=$((fail+1))
    elapsed=$(( $(date +%s) - start ))
    echo "[$i/$ITERATIONS] FAIL  (${elapsed}s) — log: $log"
    # Capture the failed run's report
    rep_src="$PROJECT_ROOT/gateway/app/build/reports/tests/integrationTest"
    if [[ -d "$rep_src" ]]; then
      cp -r "$rep_src" "$OUT/iter-${i}-report"
      echo "  report → $OUT/iter-${i}-report/index.html"
    fi
    xml_src="$PROJECT_ROOT/gateway/app/build/test-results/integrationTest"
    if [[ -d "$xml_src" ]]; then
      cp -r "$xml_src" "$OUT/iter-${i}-xml"
    fi
    break
  fi
done

echo ""
echo "Result: $pass pass / $fail fail (out of $i run)"
[[ $fail -eq 0 ]]
