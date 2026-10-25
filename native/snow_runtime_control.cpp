// CarX Street runtime probe v3.2: read-only module-map check with unmistakable log markers.
// No dlopen/dlsym, IL2CPP calls, object access, or game-memory writes.
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>

#define TAG "CarXSnowProbe"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static void* probe_worker(void*) {
    LOGI("V3.2_WORKER_STARTED; waiting 10 seconds before read-only maps scan");
    sleep(10);
    FILE* f = std::fopen("/proc/self/maps", "r");
    if (!f) {
        LOGE("V3.2_MAPS_OPEN_FAILED");
        return nullptr;
    }
    char line[1024];
    bool found = false;
    while (std::fgets(line, sizeof(line), f)) {
        if (std::strstr(line, "libil2cpp.so")) {
            found = true;
            LOGI("V3.2_IL2CPP_MAP_LINE: %s", line);
            break;
        }
    }
    std::fclose(f);
    LOGI("V3.2_RESULT: libil2cpp.so %s; read-only scan complete; no IL2CPP API calls or memory writes", found ? "FOUND" : "NOT_FOUND");
    return nullptr;
}

extern "C" __attribute__((visibility("default"))) void carx_snow_start() {
    LOGI("V3.2_INJECTED: target specialization reached; launching read-only probe worker");
    pthread_t thread{};
    if (pthread_create(&thread, nullptr, probe_worker, nullptr) == 0) {
        pthread_detach(thread);
        LOGI("V3.2_THREAD_CREATE_OK");
    } else {
        LOGE("V3.2_THREAD_CREATE_FAILED");
    }
}
