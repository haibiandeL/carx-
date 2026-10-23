#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to an installed Android NDK}"
: "${ZYGISK_INCLUDE_DIR:?Set ZYGISK_INCLUDE_DIR to the directory containing official zygisk.hpp}"
HOST_TAG="linux-x86_64"
CXX="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android29-clang++"
OUT="$(cd "$(dirname "$0")/.." && pwd)/module_template/zygisk/arm64-v8a.so"
mkdir -p "$(dirname "$OUT")"
"$CXX" -std=c++17 -O2 -fPIC -shared -fvisibility=hidden \
  -I"$ZYGISK_INCLUDE_DIR" \
  "$(dirname "$0")/zygisk_module.cpp" "$(dirname "$0")/snow_runtime_control.cpp" \
  -o "$OUT" -ldl -llog -pthread
printf 'Built: %s\n' "$OUT"
