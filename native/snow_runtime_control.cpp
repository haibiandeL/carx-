// CarX Street runtime-probe build (phase 2).
// It verifies that the injected process can see IL2CPP and the expected 1.23 types.
// IMPORTANT: this phase does NOT inspect object instances or write game memory.
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>
#include <sys/stat.h>
#include <cstdio>
#include <cstring>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "CarXSnow", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "CarXSnow", __VA_ARGS__)

struct Il2CppDomain;
struct Il2CppAssembly;
struct Il2CppImage;
struct Il2CppClass;

using domain_get_t = Il2CppDomain* (*)();
using thread_attach_t = void* (*)(Il2CppDomain*);
using domain_get_assemblies_t = const Il2CppAssembly** (*)(Il2CppDomain*, size_t*);
using assembly_get_image_t = const Il2CppImage* (*)(const Il2CppAssembly*);
using image_get_name_t = const char* (*)(const Il2CppImage*);
using class_from_name_t = Il2CppClass* (*)(const Il2CppImage*, const char*, const char*);

static void set_status(const char* s) {
    FILE* f = std::fopen("/data/local/tmp/carx_snow_status", "w");
    if (!f) { LOGE("cannot write status file"); return; }
    std::fprintf(f, "%s\n", s);
    std::fclose(f);
    chmod("/data/local/tmp/carx_snow_status", 0644);
}

static const Il2CppImage* find_image(Il2CppDomain* domain,
    domain_get_assemblies_t get_assemblies, assembly_get_image_t get_image,
    image_get_name_t get_name, const char* wanted) {
    size_t count = 0;
    const Il2CppAssembly** assemblies = get_assemblies(domain, &count);
    if (!assemblies) return nullptr;
    for (size_t i = 0; i < count; ++i) {
        const Il2CppImage* image = get_image(assemblies[i]);
        if (!image) continue;
        const char* name = get_name(image);
        if (name && std::strcmp(name, wanted) == 0) return image;
    }
    return nullptr;
}

static void* probe_worker(void*) {
    set_status("Zygisk 已进入游戏进程，等待 libil2cpp.so");
    void* handle = nullptr;
    for (int i = 0; i < 90 && !handle; ++i) {
        handle = dlopen("libil2cpp.so", RTLD_NOW | RTLD_NOLOAD);
        if (!handle) { handle = dlopen("libil2cpp.so", RTLD_NOW); }
        if (!handle) sleep(1);
    }
    if (!handle) { set_status("未找到 libil2cpp.so；请确认游戏版本与注入状态"); return nullptr; }

#define SYM(type, name) reinterpret_cast<type>(dlsym(handle, name))
    auto domain_get = SYM(domain_get_t, "il2cpp_domain_get");
    auto thread_attach = SYM(thread_attach_t, "il2cpp_thread_attach");
    auto get_assemblies = SYM(domain_get_assemblies_t, "il2cpp_domain_get_assemblies");
    auto get_image = SYM(assembly_get_image_t, "il2cpp_assembly_get_image");
    auto get_name = SYM(image_get_name_t, "il2cpp_image_get_name");
    auto class_from_name = SYM(class_from_name_t, "il2cpp_class_from_name");
#undef SYM
    if (!domain_get || !thread_attach || !get_assemblies || !get_image || !get_name || !class_from_name) {
        set_status("libil2cpp 已加载，但缺少所需导出 API");
        LOGE("IL2CPP export lookup failed");
        return nullptr;
    }
    set_status("IL2CPP API 已找到，等待域初始化");
    Il2CppDomain* domain = nullptr;
    for (int i = 0; i < 90 && !domain; ++i) {
        domain = domain_get();
        if (!domain) sleep(1);
    }
    if (!domain) { set_status("IL2CPP API 存在，但域初始化超时"); return nullptr; }
    if (!thread_attach(domain)) { set_status("IL2CPP 域已初始化，但线程附加失败"); return nullptr; }

    set_status("IL2CPP 域已就绪，检查目标程序集与类型");
    const Il2CppImage* core = find_image(domain, get_assemblies, get_image, get_name, "UnityEngine.CoreModule.dll");
    const Il2CppImage* render = find_image(domain, get_assemblies, get_image, get_name, "RenderCore.Runtime.dll");
    if (!core || !render) {
        set_status("IL2CPP 正常，但未找到预期程序集名称；暂不触碰游戏对象");
        LOGE("Expected assemblies not found: core=%p render=%p", core, render);
        return nullptr;
    }
    Il2CppClass* manager = class_from_name(core, "UnityEngine.Rendering", "RenderPipelineManager");
    Il2CppClass* pipeline = class_from_name(render, "RenderCore", "RenderPipeline");
    Il2CppClass* particles = class_from_name(render, "RenderCore", "ParticleEffectSystem");
    Il2CppClass* snow = class_from_name(render, "RenderCore", "SnowParticleEffect");
    if (!manager || !pipeline || !particles || !snow) {
        char msg[192];
        std::snprintf(msg, sizeof(msg), "程序集已找到，类型检查未通过（M:%d P:%d PS:%d S:%d）",
            manager != nullptr, pipeline != nullptr, particles != nullptr, snow != nullptr);
        set_status(msg);
        LOGE("Type lookup failed manager=%p pipeline=%p particles=%p snow=%p", manager, pipeline, particles, snow);
        return nullptr;
    }
    set_status("探测成功：IL2CPP、目标程序集和雪花类型均已找到；尚未修改游戏效果");
    LOGI("Runtime probe succeeded; no game object memory was read or written");
    // One-shot probe: intentionally exits here; no polling or writes to Unity objects.
    return nullptr;
}

extern "C" __attribute__((visibility("default"))) void carx_snow_start() {
    pthread_t thread{};
    if (pthread_create(&thread, nullptr, probe_worker, nullptr) == 0) pthread_detach(thread);
    else { set_status("无法创建探测线程"); LOGE("pthread_create failed"); }
}
