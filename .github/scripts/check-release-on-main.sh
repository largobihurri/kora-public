#!/usr/bin/env bash
# Fails unless the release commit is on main: <main-ref> itself or one of its ancestors. Versions are immutable
# (docs/releasing.md), so a tag on a commit that never reached main must not publish.
# Needs the full history of <main-ref>; with a shallow clone the check fails instead of guessing.
# <main-ref> is a full ref name (refs/remotes/origin/main): a short name such as origin/main resolves to a tag of
# that name first, which would let a tag stand in for main.
# Usage: check-release-on-main.sh <commit> <main-ref>
set -euo pipefail

commit="${1:?usage: check-release-on-main.sh <commit> <main-ref>}"
main="${2:?usage: check-release-on-main.sh <commit> <main-ref>}"

if [[ "$main" != refs/* ]]; then
  echo "::error::'$main' is not a full ref name; pass refs/remotes/origin/main." >&2
  exit 1
fi
if ! sha=$(git rev-parse --verify --quiet "$commit^{commit}"); then
  echo "::error::Cannot resolve the release commit '$commit'." >&2
  exit 1
fi
if ! git rev-parse --verify --quiet "$main^{commit}" > /dev/null; then
  echo "::error::Cannot resolve '$main', so commit $sha cannot be checked against main." >&2
  exit 1
fi
if ! git merge-base --is-ancestor "$sha" "$main"; then
  echo "::error::Commit $sha is not on main ('$main'). Releases are tagged on main only (docs/releasing.md)." >&2
  exit 1
fi
echo "Commit $sha is on main."
