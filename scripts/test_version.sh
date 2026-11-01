#!/usr/bin/env bash
# Tests for the shared build logic in buildSrc (app version, release signing). No device needed.
# Run: scripts/test_version.sh
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew -q -p buildSrc test
echo "ok   buildSrc tests (AppVersion, ReleaseSigning, ThirdParty)"
