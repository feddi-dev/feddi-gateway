#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Parse command line arguments
# Default: force rerun all tests (ignore cache)
RERUN_TASKS="--rerun-tasks"
while [[ $# -gt 0 ]]; do
    case $1 in
        -c|--cached)
            RERUN_TASKS=""
            shift
            ;;
        -h|--help)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  -c, --cached   Allow cached test results (faster but may skip tests)"
            echo "  -h, --help     Show this help message"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

# Function to count tests from JUnit XML reports
count_tests_from_xml() {
    local dir="$1"
    local total=0
    if [ -d "$dir" ]; then
        for xml in "$dir"/*.xml; do
            if [ -f "$xml" ]; then
                # Extract tests attribute from testsuite element
                count=$(grep -o 'tests="[0-9]*"' "$xml" 2>/dev/null | head -1 | grep -o '[0-9]*' || echo "0")
                total=$((total + count))
            fi
        done
    fi
    echo "$total"
}

# Function to count YAML test files
count_yaml_tests() {
    local pattern="$1"
    find "$PROJECT_ROOT/gateway/engine/src/test/resources" -path "$pattern" -name "*.yaml" 2>/dev/null | wc -l | tr -d ' '
}

TOTAL_TESTS=0

echo "=============================================="
echo "Building and testing gateway project"
echo "=============================================="
cd "$PROJECT_ROOT/gateway"
./gradlew clean test integrationTest bootJar jacocoAggregateReport $RERUN_TASKS

# Count YAML-based tests (these are dynamically generated)
COMPOSITION_SUCCESS_TESTS=$(count_yaml_tests "*/composition/success/*")
COMPOSITION_ERROR_TESTS=$(count_yaml_tests "*/composition/errors/*")
COMPOSITION_TESTS=$((COMPOSITION_SUCCESS_TESTS + COMPOSITION_ERROR_TESTS))
PLANNING_TESTS=$(count_yaml_tests "*/schemas/*/planning/*")
EXECUTION_TESTS=$(count_yaml_tests "*/schemas/*/executions/*")

# Count total engine tests from XML and calculate other unit tests
ENGINE_TESTS_TOTAL=$(count_tests_from_xml "$PROJECT_ROOT/gateway/engine/build/test-results/test")
YAML_BASED_TESTS=$((COMPOSITION_TESTS + PLANNING_TESTS + EXECUTION_TESTS))
OTHER_UNIT_TESTS=$((ENGINE_TESTS_TOTAL - YAML_BASED_TESTS))

# Count app tests
APP_TESTS=$(count_tests_from_xml "$PROJECT_ROOT/gateway/app/build/test-results/test")
INTEGRATION_TESTS=$(count_tests_from_xml "$PROJECT_ROOT/gateway/app/build/test-results/integrationTest")

GATEWAY_TOTAL=$((ENGINE_TESTS_TOTAL + APP_TESTS + INTEGRATION_TESTS))
TOTAL_TESTS=$((TOTAL_TESTS + GATEWAY_TOTAL))

echo ""
echo "=============================================="
echo "Publishing extension-api to Maven local"
echo "=============================================="
./gradlew :extension-api:publishToMavenLocal

echo ""
echo "=============================================="
echo "Running e2e tests"
echo "=============================================="
cd "$PROJECT_ROOT/e2e-tests"
./gradlew clean test $RERUN_TASKS

# Count e2e-tests
E2E_TESTS=$(count_tests_from_xml "$PROJECT_ROOT/e2e-tests/build/test-results/test")
TOTAL_TESTS=$((TOTAL_TESTS + E2E_TESTS))

echo ""
echo "=============================================="
echo "All tests passed!"
echo "=============================================="
echo ""
echo "Total: $TOTAL_TESTS tests"
echo ""
echo "----------------------------------------------"
echo "gateway/engine (unit tests): $ENGINE_TESTS_TOTAL"
echo "----------------------------------------------"
echo "  Composition:     $COMPOSITION_TESTS"
echo "    - success:     $COMPOSITION_SUCCESS_TESTS"
echo "    - errors:      $COMPOSITION_ERROR_TESTS"
echo "  Planning:        $PLANNING_TESTS"
echo "  Execution:       $EXECUTION_TESTS"
echo "  Other:           $OTHER_UNIT_TESTS"
echo ""
echo "----------------------------------------------"
echo "gateway/app (unit tests): $APP_TESTS"
echo "----------------------------------------------"
echo ""
echo "----------------------------------------------"
echo "gateway/app (integration tests): $INTEGRATION_TESTS"
echo "----------------------------------------------"
echo ""
echo "----------------------------------------------"
echo "e2e-tests: $E2E_TESTS"
echo "----------------------------------------------"
echo "=============================================="
