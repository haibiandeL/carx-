// CarX Street runtime probe v3.4 — diagnostic only, no game-memory writes.
// Reports only executable file-backed ELF/shared-library mapping candidates; data files and dmabuf are excluded.
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
static const char* BUILD_ID = "CARX_SNOW_DIAG_V3.4_20261025_A";

static void stamp(char* out, size_t n) {
    struct timespec ts{};
    clock_gettime(CLOCK_REALTIME, &ts);
    std::snprintf(out, n, "%lld.%03ld", (long long)ts.tv_sec, ts.tv_nsec / 1000000L);
}
static bool is_exec_mapping(const char* line) {
    // /proc/self/maps fields: address perms offset dev inode pathname.
    // Require executable permissions; ignore anonymous mappings and Android graphics buffers.
    const char* first_space = std::strchr(line, ' ');
    if (!first_space || std::strncmp(first_space + 1, "r-x", 3) != 0 &&
        std::strncmp(first_space + 1, "--x", 3) != 0 &&
        std::strncmp(first_space + 1, "-wx", 3) != 0 &&
        std::strncmp(first_space + 1, "rwx", 3) != 0) return false;
    const char* path = std::strchr(first_space + 1, '/');
    if (!path) return false;
    if (std::strstr(path, "/dev/ashmem") || std::strstr(path, "dmabuf") ||
        std::strstr(path, "global-metadata.dat") || std::strstr(path, ".dat")) return false;
    return true;
}
static void scan_maps(int stage) {
    char ts[40]{}; stamp(ts, sizeof(ts));
    FILE* f = std::fopen("/proc/self/maps", "r");
    if (!f) { LOGE("V3.4_MAPS_OPEN_FAILED build=%s stage=%d time=%s errno=%d", BUILD_ID, stage, ts, errno); return; }
    char line[2048]; int total=0, executable=0, il2cpp=0, unity=0, carx=0;
    while (std::fgets(line, sizeof(line), f)) {
        ++total;
        if (!is_exec_mapping(line)) continue;
        ++executable;
        const char* path = std::strchr(line, '/');
        if (!path) continue;
        if (std::strstr(path, "il2cpp")) { ++il2cpp; LOGI("V3.4_ELF_CANDIDATE build=%s stage=%d kind=il2cpp line=%s", BUILD_ID, stage, line); }
        if (std::strstr(path, "Unity") || std::strstr(path, "unity")) { ++unity; LOGI("V3.4_ELF_CANDIDATE build=%s stage=%d kind=unity line=%s", BUILD_ID, stage, line); }
        if (std::strstr(path, "carx") || std::strstr(path, "CarX")) { ++carx; LOGI("V3.4_ELF_CANDIDATE build=%s stage=%d kind=carx line=%s", BUILD_ID, stage, line); }
    }
    std::fclose(f);
    stamp(ts, sizeof(ts));
    LOGI("V3.4_SCAN_RESULT build=%s stage=%d time=%s pid=%d map_lines=%d executable_file_maps=%d il2cpp_candidates=%d unity_candidates=%d carx_candidates=%d", BUILD_ID, stage, ts, (int)getpid(), total, executable, il2cpp, unity, carx);
}
static void* probe_worker(void*) {
    char ts[40]{}; stamp(ts, sizeof(ts));
    LOGI("V3.4_WORKER_STARTED build=%s time=%s pid=%d", BUILD_ID, ts, (int)getpid());
    // Scan now and again later: read-only and bounded, to detect delayed library loading.
    scan_maps(0);
    sleep(5); scan_maps(5);
    sleep(10); scan_maps(15);
    sleep(15); scan_maps(30);
    LOGI("V3.4_PROBE_FINISHED build=%s pid=%d", BUILD_ID, (int)getpid());
    return nullptr;
}
extern "C" __attribute__((visibility("default"))) void carx_snow_start() {
    char ts[40]{}; stamp(ts, sizeof(ts));
    LOGI("V3.4_INJECTED build=%s time=%s pid=%d", BUILD_ID, ts, (int)getpid());
    pthread_t thread{};
    int rc = pthread_create(&thread, nullptr, probe_worker, nullptr);
    if (rc == 0) { pthread_detach(thread); LOGI("V3.4_THREAD_CREATE_OK build=%s pid=%d", BUILD_ID, (int)getpid()); }
    else { LOGE("V3.4_THREAD_CREATE_FAILED build=%s rc=%d pid=%d", BUILD_ID, rc, (int)getpid()); }
}
