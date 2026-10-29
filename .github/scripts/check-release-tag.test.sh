#!/usr/bin/env bash
# Tests check-release-tag.sh: each case states the arguments and whether the script must succeed.
set -euo pipefail

script="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/check-release-tag.sh"
passed=0
failed=0

# expect <ok|fail> <description> [args...]
expect() {
  local want="$1" description="$2"
  shift 2
  local status=0
  "$script" "$@" > /dev/null 2>&1 || status=$?
  if { [[ "$want" == ok && $status -eq 0 ]] || [[ "$want" == fail && $status -ne 0 ]]; }; then
    passed=$((passed + 1))
    echo "ok    $description"
  else
    failed=$((failed + 1))
    echo "FAIL  $description (expected $want, exit status $status)"
  fi
}

expect ok   "tag v1.2.3 matches version 1.2.3"                 v1.2.3 1.2.3
expect fail "tag v1.2.4 does not match version 1.2.3"          v1.2.4 1.2.3
expect fail "tag without the v prefix (1.2.3 vs 1.2.3)"        1.2.3 1.2.3
expect fail "pre-release tag against a plain version"          v1.2.3-rc.1 1.2.3
expect fail "pre-release version against a plain tag"          v1.2.3 1.2.3-rc.1
expect fail "no arguments"
expect fail "tag only, version missing"                        v1.2.3
expect fail "empty tag and version"                            "" ""

echo "check-release-tag: $passed passed, $failed failed"
[[ $failed -eq 0 ]]
