// CarX Street runtime probe v3.3 — diagnostic only, no game-memory writes.
// Unique build ID + staged read-only maps scans to distinguish early/late library loading.
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <sys/types.h>
#include <cstdlib>
#include <cerrno>

#define TAG "CarXSnowProbe"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
static const char* BUILD_ID = "CARX_SNOW_DIAG_V3.3_20261025_A";

static void stamp(char* out, size_t n) {
    struct timespec ts{};
    clock_gettime(CLOCK_REALTIME, &ts);
    std::snprintf(out, n, "%lld.%03ld", (long long)ts.tv_sec, ts.tv_nsec / 1000000L);
}
static void scan_maps(int stage) {
    char ts[40]{}; stamp(ts, sizeof(ts));
    FILE* f = std::fopen("/proc/self/maps", "r");
    if (!f) { LOGE("V3.3_MAPS_OPEN_FAILED build=%s stage=%d time=%s errno=%d", BUILD_ID, stage, ts, errno); return; }
    char line[2048]; int total=0, il2cpp=0, unity=0, carx=0;
    while (std::fgets(line, sizeof(line), f)) {
        ++total;
        if (std::strstr(line, "il2cpp")) { ++il2cpp; LOGI("V3.3_MAP_MATCH build=%s stage=%d kind=il2cpp line=%s", BUILD_ID, stage, line); }
        if (std::strstr(line, "Unity")) { ++unity; LOGI("V3.3_MAP_MATCH build=%s stage=%d kind=unity line=%s", BUILD_ID, stage, line); }
        if (std::strstr(line, "carx")) { ++carx; LOGI("V3.3_MAP_MATCH build=%s stage=%d kind=carx line=%s", BUILD_ID, stage, line); }
    }
    std::fclose(f);
    stamp(ts, sizeof(ts));
    LOGI("V3.3_SCAN_RESULT build=%s stage=%d time=%s pid=%d map_lines=%d il2cpp_matches=%d unity_matches=%d carx_matches=%d", BUILD_ID, stage, ts, (int)getpid(), total, il2cpp, unity, carx);
}
static void* probe_worker(void*) {
    char ts[40]{}; stamp(ts, sizeof(ts));
    LOGI("V3.3_WORKER_STARTED build=%s time=%s pid=%d", BUILD_ID, ts, (int)getpid());
    // Scan now and again later: read-only and bounded, to detect delayed library loading.
    scan_maps(0);
    sleep(5); scan_maps(5);
    sleep(10); scan_maps(15);
    sleep(15); scan_maps(30);
    LOGI("V3.3_PROBE_FINISHED build=%s pid=%d", BUILD_ID, (int)getpid());
    return nullptr;
}
extern "C" __attribute__((visibility("default"))) void carx_snow_start() {
    char ts[40]{}; stamp(ts, sizeof(ts));
    LOGI("V3.3_INJECTED build=%s time=%s pid=%d", BUILD_ID, ts, (int)getpid());
    pthread_t thread{};
    int rc = pthread_create(&thread, nullptr, probe_worker, nullptr);
    if (rc == 0) { pthread_detach(thread); LOGI("V3.3_THREAD_CREATE_OK build=%s pid=%d", BUILD_ID, (int)getpid()); }
    else { LOGE("V3.3_THREAD_CREATE_FAILED build=%s rc=%d pid=%d", BUILD_ID, rc, (int)getpid()); }
}
