// CarX Street runtime probe v3.1: delayed, read-only module-map check.
// No dlopen/dlsym, IL2CPP calls, object access, or game-memory writes.
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "CarXSnow", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "CarXSnow", __VA_ARGS__)

static void* probe_worker(void*) {
    // Let app specialization and normal loader initialization finish first.
    sleep(10);
    FILE* f = std::fopen("/proc/self/maps", "r");
    if (!f) {
        LOGE("Probe: cannot read /proc/self/maps");
        return nullptr;
    }
    char line[1024];
    bool found = false;
    while (std::fgets(line, sizeof(line), f)) {
        if (std::strstr(line, "libil2cpp.so")) {
            found = true;
            break;
        }
    }
    std::fclose(f);
    LOGI("Probe v3.1 completed: libil2cpp.so %s in process maps; no IL2CPP API calls made", found ? "FOUND" : "NOT FOUND");
    return nullptr;
}

extern "C" __attribute__((visibility("default"))) void carx_snow_start() {
    LOGI("Probe v3.1 injected into target; starting delayed read-only maps check");
    pthread_t thread{};
    if (pthread_create(&thread, nullptr, probe_worker, nullptr) == 0) {
        pthread_detach(thread);
    } else {
        LOGE("Probe v3.1 could not create worker thread");
    }
}
