#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "=============================================="
echo "Project Structure Analysis"
echo "=============================================="
echo ""

# Temp file for collecting all prod files
PROD_FILES_TMP=$(mktemp)
trap "rm -f $PROD_FILES_TMP" EXIT

# Totals
TOTAL_PROD=0
TOTAL_TEST=0
TOTAL_PROD_LINES=0
TOTAL_TEST_LINES=0

analyze_gradle_project() {
    local project_dir="$1"
    local project_name="$2"

    local prod_count=0
    local test_count=0
    local prod_lines=0
    local test_lines=0

    # Count prod source files (src/main)
    if [ -d "$project_dir/src/main" ]; then
        while IFS= read -r file; do
            if [ -n "$file" ] && [ -f "$file" ]; then
                prod_count=$((prod_count + 1))
                lines=$(wc -l < "$file" | tr -d ' ')
                prod_lines=$((prod_lines + lines))
                # Store for sorted output later
                echo "$lines $file" >> "$PROD_FILES_TMP"
            fi
        done < <(find "$project_dir/src/main" -type f \( -name "*.java" -o -name "*.kt" -o -name "*.groovy" \) 2>/dev/null)
    fi

    # Count test source files (src/test)
    if [ -d "$project_dir/src/test" ]; then
        while IFS= read -r file; do
            if [ -n "$file" ] && [ -f "$file" ]; then
                test_count=$((test_count + 1))
                lines=$(wc -l < "$file" | tr -d ' ')
                test_lines=$((test_lines + lines))
            fi
        done < <(find "$project_dir/src/test" -type f \( -name "*.java" -o -name "*.kt" -o -name "*.groovy" \) 2>/dev/null)
    fi

    printf "  %-40s prod: %3d files (%5d lines)  test: %3d files (%5d lines)\n" \
        "$project_name" "$prod_count" "$prod_lines" "$test_count" "$test_lines"

    TOTAL_PROD=$((TOTAL_PROD + prod_count))
    TOTAL_TEST=$((TOTAL_TEST + test_count))
    TOTAL_PROD_LINES=$((TOTAL_PROD_LINES + prod_lines))
    TOTAL_TEST_LINES=$((TOTAL_TEST_LINES + test_lines))
}

echo "Gradle Projects:"
echo "----------------------------------------------"

# Analyze gateway projects
if [ -d "$PROJECT_ROOT/gateway" ]; then
    echo ""
    echo "gateway/ (multi-project)"

    # Subprojects
    for subproject in engine app customization-api; do
        if [ -d "$PROJECT_ROOT/gateway/$subproject" ] && [ -f "$PROJECT_ROOT/gateway/$subproject/build.gradle" ]; then
            analyze_gradle_project "$PROJECT_ROOT/gateway/$subproject" "gateway/$subproject"
        fi
    done
fi

# Analyze e2e-tests projects
if [ -d "$PROJECT_ROOT/e2e-tests" ]; then
    echo ""
    echo "e2e-tests/ (multi-project)"

    # Root e2e-tests project (has test sources)
    if [ -f "$PROJECT_ROOT/e2e-tests/build.gradle" ]; then
        analyze_gradle_project "$PROJECT_ROOT/e2e-tests" "e2e-tests"
    fi

    # Subprojects
    for subproject in customizations; do
        if [ -d "$PROJECT_ROOT/e2e-tests/$subproject" ] && [ -f "$PROJECT_ROOT/e2e-tests/$subproject/build.gradle" ]; then
            analyze_gradle_project "$PROJECT_ROOT/e2e-tests/$subproject" "e2e-tests/$subproject"
        fi
    done
fi

echo ""
echo "----------------------------------------------"
printf "  %-40s prod: %3d files (%5d lines)  test: %3d files (%5d lines)\n" \
    "TOTAL" "$TOTAL_PROD" "$TOTAL_PROD_LINES" "$TOTAL_TEST" "$TOTAL_TEST_LINES"
echo ""

# Show all prod files sorted by lines of code
echo "=============================================="
echo "Production Source Files (sorted by lines)"
echo "=============================================="
echo ""

if [ -s "$PROD_FILES_TMP" ]; then
    file_count=$(wc -l < "$PROD_FILES_TMP" | tr -d ' ')

    sort -rn "$PROD_FILES_TMP" | head -10 | while read -r lines file; do
        # Make path relative to project root
        relative_path="${file#$PROJECT_ROOT/}"
        printf "%5d  %s\n" "$lines" "$relative_path"
    done

    if [ "$file_count" -gt 10 ]; then
        echo ""
        echo "  ... and $((file_count - 10)) more files"
    fi
else
    echo "  No production source files found."
fi

echo ""
echo "=============================================="
