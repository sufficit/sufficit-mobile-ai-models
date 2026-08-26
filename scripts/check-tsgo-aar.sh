#!/usr/bin/env bash
# Verifies that the committed gomobile artifacts were built from the current Go sources.
# Pass --write only from build-tsgo-aar.sh after gomobile bind succeeds.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="$REPO_ROOT/android/app/libs/tsgo.integrity"
AAR="$REPO_ROOT/android/app/libs/tsgo.aar"
SOURCES_JAR="$REPO_ROOT/android/app/libs/tsgo-sources.jar"

source_hash() {
  (
    cd "$REPO_ROOT"
    while IFS= read -r file; do
      sha256sum "$file"
    done < <(
      {
        find android-tsgo -maxdepth 1 -type f \( -name '*.go' -o -name 'go.mod' -o -name 'go.sum' \)
        printf '%s\n' \
          scripts/build-tsgo-aar.sh \
          scripts/build-llama-static.sh \
          scripts/build-whisper-static.sh
      } | LC_ALL=C sort
    )
  ) | sha256sum | awk '{print $1}'
}

artifact_hash() {
  sha256sum "$1" | awk '{print $1}'
}

if [ "${1:-}" = "--write" ]; then
  test -s "$AAR"
  test -s "$SOURCES_JAR"
  {
    printf 'sources %s\n' "$(source_hash)"
    printf 'aar %s\n' "$(artifact_hash "$AAR")"
    printf 'sources_jar %s\n' "$(artifact_hash "$SOURCES_JAR")"
  } > "$MANIFEST"
  echo "Wrote $MANIFEST"
  exit 0
fi

if [ ! -s "$MANIFEST" ]; then
  echo "Missing $MANIFEST; rebuild with scripts/build-tsgo-aar.sh" >&2
  exit 1
fi

expected_sources="$(awk '$1 == "sources" { print $2 }' "$MANIFEST")"
expected_aar="$(awk '$1 == "aar" { print $2 }' "$MANIFEST")"
expected_sources_jar="$(awk '$1 == "sources_jar" { print $2 }' "$MANIFEST")"
actual_sources="$(source_hash)"

if [ "$actual_sources" != "$expected_sources" ]; then
  echo "tsgo.aar is stale: android-tsgo sources changed; run scripts/build-tsgo-aar.sh" >&2
  exit 1
fi
if [ ! -s "$AAR" ] || [ "$(artifact_hash "$AAR")" != "$expected_aar" ]; then
  echo "tsgo.aar does not match its integrity manifest; rebuild it" >&2
  exit 1
fi
if [ ! -s "$SOURCES_JAR" ] || [ "$(artifact_hash "$SOURCES_JAR")" != "$expected_sources_jar" ]; then
  echo "tsgo-sources.jar does not match its integrity manifest; rebuild it" >&2
  exit 1
fi

echo "tsgo gomobile artifacts match the current sources"
