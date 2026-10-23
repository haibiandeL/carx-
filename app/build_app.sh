#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v gradle >/dev/null || { echo 'Gradle is required; not installed in the current environment.' >&2; exit 2; }
gradle --no-daemon :app:assembleDebug
printf 'APK: %s\n' "$(pwd)/app/build/outputs/apk/debug/app-debug.apk"
