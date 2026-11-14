#!/usr/bin/env bash
# Tests check-release-on-main.sh against a throwaway repository: each case states the arguments and whether the
# script must succeed.
set -euo pipefail

script="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/check-release-on-main.sh"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
passed=0
failed=0

# A repository whose main has two commits, with an unmerged branch and a merged one.
repo="$work/repo"
git init -q -b main "$repo"
git -C "$repo" config user.name "release test"
git -C "$repo" config user.email "release-test@example.invalid"
git -C "$repo" config commit.gpgsign false
commit() {
  git -C "$repo" commit -q --allow-empty -m "$1"
  git -C "$repo" rev-parse HEAD
}
first=$(commit "first on main")
git -C "$repo" checkout -q -b merged
merged=$(commit "on a branch that is merged later")
git -C "$repo" checkout -q main
git -C "$repo" merge -q --no-ff -m "merge the branch" merged
tip=$(git -C "$repo" rev-parse HEAD)
git -C "$repo" checkout -q -b unmerged "$first"
unmerged=$(commit "on a branch that is not merged")
git -C "$repo" checkout -q main

# expect <ok|fail> <description> [args...]
expect() {
  local want="$1" description="$2"
  shift 2
  local status=0
  (cd "$repo" && "$script" "$@") > /dev/null 2>&1 || status=$?
  if { [[ "$want" == ok && $status -eq 0 ]] || [[ "$want" == fail && $status -ne 0 ]]; }; then
    passed=$((passed + 1))
    echo "ok    $description"
  else
    failed=$((failed + 1))
    echo "FAIL  $description (expected $want, exit status $status)"
  fi
}

main=refs/heads/main
expect ok   "the tip of main"                                   "$tip" "$main"
expect ok   "an earlier commit of main"                         "$first" "$main"
expect ok   "a branch commit that was merged into main"         "$merged" "$main"
expect ok   "HEAD while main is checked out"                    HEAD "$main"
expect fail "a commit on a branch that is not merged"           "$unmerged" "$main"
expect fail "a commit that does not exist"                      0000000000000000000000000000000000000000 "$main"
expect fail "a main ref that does not exist"                    "$tip" refs/remotes/origin/main
expect fail "no arguments"
expect fail "commit only, main ref missing"                     "$tip"
expect fail "a short main ref name"                             "$tip" main

# A tag named like the remote branch wins the short name origin/main; the full ref name is not fooled by it.
git -C "$repo" update-ref refs/remotes/origin/main "$tip"
git -C "$repo" tag origin/main "$unmerged"
expect fail "an unmerged commit with a tag that shadows origin/main" "$unmerged" refs/remotes/origin/main
expect ok   "the tip of main with a tag that shadows origin/main"    "$tip" refs/remotes/origin/main

# The refusal names the commit, so the log says which one was tagged.
status=0
message=$( (cd "$repo" && "$script" "$unmerged" "$main") 2>&1) || status=$?
if [[ $status -ne 0 && "$message" == *"$unmerged"* ]]; then
  passed=$((passed + 1))
  echo "ok    the refusal names the commit"
else
  failed=$((failed + 1))
  echo "FAIL  the refusal names the commit (got: $message)"
fi

echo "check-release-on-main: $passed passed, $failed failed"
[[ $failed -eq 0 ]]
