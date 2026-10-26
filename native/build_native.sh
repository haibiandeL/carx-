#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to an installed Android NDK}"
: "${ZYGISK_INCLUDE_DIR:?Set ZYGISK_INCLUDE_DIR to the directory containing official zygisk.hpp}"
HOST_TAG="linux-x86_64"
CXX="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android29-clang++"
: "${IMGUI_DIR:?Set IMGUI_DIR to a checkout of ocornut/imgui}"
OUT="$(cd "$(dirname "$0")/.." && pwd)/module_template/zygisk/arm64-v8a.so"
mkdir -p "$(dirname "$OUT")"
"$CXX" -std=c++17 -O2 -fPIC -shared -fvisibility=hidden \
  -I"$ZYGISK_INCLUDE_DIR" -I"$IMGUI_DIR" \
  "$(dirname "$0")/zygisk_module.cpp" "$(dirname "$0")/snow_runtime_control.cpp" "$(dirname "$0")/overlay_ui.cpp" \
  "$IMGUI_DIR/imgui.cpp" "$IMGUI_DIR/imgui_draw.cpp" "$IMGUI_DIR/imgui_tables.cpp" "$IMGUI_DIR/imgui_widgets.cpp" \
  "$IMGUI_DIR/backends/imgui_impl_opengl3.cpp" \
  -o "$OUT" -ldl -llog -lEGL -lGLESv3 -pthread
printf 'Built: %s\n' "$OUT"
