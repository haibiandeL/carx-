// CarX Street launch-baseline diagnostic build.
// Intentionally performs NO IL2CPP lookup, background polling, game-memory reads,
// or game-memory writes. This is used to isolate whether runtime-controller logic
// caused launch crashes/black screens. It does not change snow size or weather.
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "CarXSnow", __VA_ARGS__)
extern "C" __attribute__((visibility("default"))) void carx_snow_start() {
    LOGI("Launch-baseline mode: runtime controller disabled; no game memory access");
}
