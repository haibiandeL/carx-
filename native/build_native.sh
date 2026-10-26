#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to an installed Android NDK}"
: "${ZYGISK_INCLUDE_DIR:?Set ZYGISK_INCLUDE_DIR to the directory containing official zygisk.hpp}"
: "${IMGUI_DIR:?Set IMGUI_DIR to a Dear ImGui checkout}"
: "${DOBBY_DIR:?Set DOBBY_DIR to a Dobby checkout}"
HOST_TAG="linux-x86_64"
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"
CXX="$TOOLCHAIN/aarch64-linux-android29-clang++"
OUT="$(cd "$(dirname "$0")/.." && pwd)/module_template/zygisk/arm64-v8a.so"
mkdir -p "$(dirname "$OUT")"
# Build Dobby as a static ARM64 library for the injected process.
cmake -S "$DOBBY_DIR" -B "$DOBBY_DIR/build-android" -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DCMAKE_BUILD_TYPE=Release -DDOBBY_DEBUG=OFF -DDOBBY_GENERATE_SHARED=OFF
cmake --build "$DOBBY_DIR/build-android" --config Release -j2
DOBBY_LIB="$(find "$DOBBY_DIR/build-android" -name 'libdobby.a' -print -quit)"
test -n "$DOBBY_LIB"
"$CXX" -std=c++17 -O2 -fPIC -shared -fvisibility=hidden \
  -I"$ZYGISK_INCLUDE_DIR" -I"$IMGUI_DIR" -I"$IMGUI_DIR/backends" -I"$DOBBY_DIR/include" \
  "$(dirname "$0")/zygisk_module.cpp" "$(dirname "$0")/snow_runtime_control.cpp" "$(dirname "$0")/overlay_ui.cpp" \
  "$IMGUI_DIR/imgui.cpp" "$IMGUI_DIR/imgui_draw.cpp" "$IMGUI_DIR/imgui_tables.cpp" "$IMGUI_DIR/imgui_widgets.cpp" "$IMGUI_DIR/backends/imgui_impl_opengl3.cpp" \
  "$DOBBY_LIB" -o "$OUT" -ldl -llog -landroid -lEGL -lGLESv3 -pthread
printf 'Built: %s\n' "$OUT"
