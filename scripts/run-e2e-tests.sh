#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "=============================================="
echo "Building feddi Gateway JAR + publishing customization-api"
echo "=============================================="
cd "$PROJECT_ROOT/gateway"
# customization-api is consumed by e2e-tests via mavenLocal — publish it
# so a fresh checkout (no prior ~/.m2 cache) resolves on first run.
./gradlew :app:bootJar :customization-api:publishToMavenLocal --quiet

echo ""
echo "=============================================="
echo "Running e2e tests"
echo "=============================================="
cd "$PROJECT_ROOT/e2e-tests"
./gradlew test "$@"

echo ""
echo "=============================================="
echo "E2E tests completed!"
echo "=============================================="
