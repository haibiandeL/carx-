#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to an installed Android NDK}"
: "${ZYGISK_INCLUDE_DIR:?Set ZYGISK_INCLUDE_DIR to the directory containing official zygisk.hpp}"
: "${IMGUI_DIR:?Set IMGUI_DIR to a Dear ImGui source checkout}"
: "${DOBBY_DIR:?Set DOBBY_DIR to a Dobby source checkout}"
HOST_TAG="linux-x86_64"
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"
CXX="$TOOLCHAIN/aarch64-linux-android29-clang++"
AR="$TOOLCHAIN/llvm-ar"
OUT="$(cd "$(dirname "$0")/.." && pwd)/module_template/zygisk/arm64-v8a.so"
DOBBY_LIB="$DOBBY_DIR/cmake-build-android/libdobby.a"
if [[ ! -f "$DOBBY_LIB" ]]; then
  cmake -S "$DOBBY_DIR" -B "$DOBBY_DIR/cmake-build-android" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DCMAKE_BUILD_TYPE=Release \
    -DDOBBY_DEBUG=OFF
  cmake --build "$DOBBY_DIR/cmake-build-android" --config Release -j2
fi
mkdir -p "$(dirname "$OUT")"
IMGUI_SRC=("$IMGUI_DIR/imgui.cpp" "$IMGUI_DIR/imgui_draw.cpp" "$IMGUI_DIR/imgui_tables.cpp" "$IMGUI_DIR/imgui_widgets.cpp" "$IMGUI_DIR/backends/imgui_impl_opengl3.cpp")
"$CXX" -std=c++17 -O2 -fPIC -shared -fvisibility=hidden \
  -I"$ZYGISK_INCLUDE_DIR" -I"$IMGUI_DIR" -I"$IMGUI_DIR/backends" -I"$DOBBY_DIR/include" \
  native/zygisk_module.cpp native/snow_runtime_control.cpp native/imgui_overlay.cpp "${IMGUI_SRC[@]}" \
  "$DOBBY_LIB" -o "$OUT" -landroid -lEGL -lGLESv3 -ldl -llog -pthread
printf 'Built: %s\n' "$OUT"
