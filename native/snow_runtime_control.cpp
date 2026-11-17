#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>
#include <sys/stat.h>
#include <android/log.h>

// CarX Street 1.24 controller bootstrap.
// The 1.24 dump confirms the SnowParticleEffect and Weather field offsets,
// but offsets alone do not establish the managed object graph or generic
// VolumeParameter<T> value layout. This file deliberately avoids blind writes.
#define SLOGI(...) __android_log_print(ANDROID_LOG_INFO, "CarXSnow", __VA_ARGS__)
#define SLOGE(...) __android_log_print(ANDROID_LOG_ERROR, "CarXSnow", __VA_ARGS__)

namespace {
using DomainGet = void *(*)();
using ThreadAttach = void *(*)(void *);
static void *il2cpp = nullptr;
static DomainGet domain_get = nullptr;
static ThreadAttach thread_attach = nullptr;

void write_status(const char *value) {
    FILE *f = std::fopen("/data/local/tmp/carx_snow_status", "w");
    if (!f) return;
    std::fprintf(f, "%s\\n", value);
    std::fclose(f);
    chmod("/data/local/tmp/carx_snow_status", 0644);
}

bool resolve_il2cpp() {
    if (!il2cpp) il2cpp = dlopen("libil2cpp.so", RTLD_NOW);
    if (!il2cpp) return false;
    domain_get = reinterpret_cast<DomainGet>(dlsym(il2cpp, "il2cpp_domain_get"));
    thread_attach = reinterpret_cast<ThreadAttach>(dlsym(il2cpp, "il2cpp_thread_attach"));
    return domain_get && thread_attach;
}

void *runtime_worker(void *) {
    write_status("PigHax runtime worker started; delaying initialization for 10 seconds");
    SLOGI("10-second delayed initialization started");

    // Restore the original startup delay before resolving IL2CPP.
    sleep(10);

    SLOGI("10-second delay finished; resolving IL2CPP API");
    while (!resolve_il2cpp()) {
        write_status("Waiting for libil2cpp.so");
        sleep(1);
    }

    void *domain = nullptr;
    while (!(domain = domain_get())) sleep(1);
    thread_attach(domain);

    // Verified CarX Street 1.24 dump offsets:
    // SnowParticleEffect.enabled 0x48
    // SnowParticleEffect.sizeMinMax 0x80
    // Weather.rainDropsScale 0xE0
    // Weather.rainDropsIntensity 0xE8
    // Weather.wetnessAlbedoWeight 0xA0 (intentionally unused)
    // Weather.wetnessRoughnessWeight 0xA8 (intentionally unused)
    // Weather.icenessRoughnessWeight 0xB0 (intentionally unused)
    //
    // Do not write these offsets directly: the active object chain and
    // VolumeParameter<T> boxed/value offsets must first be resolved at runtime.
    write_status("IL2CPP attached; 1.24 field map loaded; runtime writes guarded");
    SLOGI("CarX 1.24 runtime attached; unverified raw writes disabled");

    for (;;) {
        // Keep the worker alive for runtime diagnostics. The overlay's existing
        // settings files remain compatible, but applying them awaits a verified
        // object-instance resolver and Unity-main-thread dispatch.
        usleep(250000);
    }
    return nullptr;
}
} // namespace

extern "C" __attribute__((visibility("default")))
void carx_snow_start() {
    static std::atomic<bool> started{false};
    bool expected = false;
    if (!started.compare_exchange_strong(expected, true)) return;

    pthread_t thread{};
    const int rc = pthread_create(&thread, nullptr, runtime_worker, nullptr);
    if (rc == 0) pthread_detach(thread);
    else {
        started = false;
        SLOGE("runtime worker creation failed: %d", rc);
    }
}
