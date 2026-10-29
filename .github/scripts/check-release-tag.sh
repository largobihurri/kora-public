#!/usr/bin/env bash
# Fails unless the release tag (vX.Y.Z) names the Gradle project version (X.Y.Z).
# Usage: check-release-tag.sh <tag> <version>
set -euo pipefail

tag="${1:?usage: check-release-tag.sh <tag> <version>}"
version="${2:?usage: check-release-tag.sh <tag> <version>}"

if [[ "$tag" != "v$version" ]]; then
  echo "::error::Tag '$tag' does not match the Gradle version '$version' (expected 'v$version')." >&2
  exit 1
fi
echo "Tag $tag matches version $version."
