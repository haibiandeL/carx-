// CarX Street 1.23 ARM64 runtime control core.
// This is source code for an in-process loader (e.g. Zygisk); it is NOT a standalone APK/module.
// All RVAs and object offsets are version-specific to the supplied 1.23 libil2cpp.so.

#include <dlfcn.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <sys/stat.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <cstdint>

namespace snowctl {
#define SNOW_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "CarXSnow", __VA_ARGS__)
#define SNOW_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "CarXSnow", __VA_ARGS__)
struct Il2CppDomain;
struct Il2CppAssembly;
struct Il2CppImage;
struct Il2CppClass;
struct Il2CppObject;
struct FieldInfo;

using domain_get_t = Il2CppDomain* (*)();
using thread_attach_t = void* (*)(Il2CppDomain*);
using domain_get_assemblies_t = const Il2CppAssembly** (*)(Il2CppDomain*, size_t*);
using assembly_get_image_t = const Il2CppImage* (*)(const Il2CppAssembly*);
using image_get_name_t = const char* (*)(const Il2CppImage*);
using class_from_name_t = Il2CppClass* (*)(const Il2CppImage*, const char*, const char*);
using class_get_field_t = FieldInfo* (*)(Il2CppClass*, const char*);
using field_static_get_t = void (*)(FieldInfo*, void*);
using field_get_t = void (*)(Il2CppObject*, FieldInfo*, void*);

static domain_get_t domain_get;
static thread_attach_t thread_attach;
static domain_get_assemblies_t domain_get_assemblies;
static assembly_get_image_t assembly_get_image;
static image_get_name_t image_get_name;
static class_from_name_t class_from_name;
static class_get_field_t class_get_field;
static field_static_get_t field_static_get;
static field_get_t field_get;
static void* il2cpp_handle;

static Il2CppImage* find_image(const char* fragment) {
    Il2CppDomain* domain = domain_get ? domain_get() : nullptr;
    if (!domain) return nullptr;
    size_t count = 0;
    auto assemblies = domain_get_assemblies(domain, &count);
    if (!assemblies) return nullptr;
    for (size_t i = 0; i < count; ++i) {
        auto image = assembly_get_image(assemblies[i]);
        if (!image) continue;
        const char* name = image_get_name(image);
        if (name && std::strstr(name, fragment)) return const_cast<Il2CppImage*>(image);
    }
    return nullptr;
}

static bool load_api() {
    il2cpp_handle = dlopen("libil2cpp.so", RTLD_NOW);
    if (!il2cpp_handle) return false;
#define LOAD_SYM(var, name) var = reinterpret_cast<decltype(var)>(dlsym(il2cpp_handle, name)); if (!(var)) return false
    LOAD_SYM(domain_get, "il2cpp_domain_get");
    LOAD_SYM(thread_attach, "il2cpp_thread_attach");
    LOAD_SYM(domain_get_assemblies, "il2cpp_domain_get_assemblies");
    LOAD_SYM(assembly_get_image, "il2cpp_assembly_get_image");
    LOAD_SYM(image_get_name, "il2cpp_image_get_name");
    LOAD_SYM(class_from_name, "il2cpp_class_from_name");
    LOAD_SYM(class_get_field, "il2cpp_class_get_field_from_name");
    LOAD_SYM(field_static_get, "il2cpp_field_static_get_value");
    LOAD_SYM(field_get, "il2cpp_field_get_value");
#undef LOAD_SYM
    return true;
}

struct Target {
    Il2CppObject* pipeline = nullptr;
    Il2CppObject* particle_system = nullptr;
    Il2CppObject* snow_effect = nullptr;
    Il2CppObject* size_parameter = nullptr;
};

static FieldInfo* g_current_pipeline_field = nullptr;
static FieldInfo* g_particle_system_field = nullptr;
static FieldInfo* g_snow_effect_field = nullptr;
static FieldInfo* g_size_field = nullptr;

static bool resolve_fields() {
    auto core = find_image("UnityEngine.CoreModule.dll");
    auto render = find_image("RenderCore.Runtime.dll");
    if (!core || !render) return false;

    auto manager_class = class_from_name(core, "UnityEngine.Rendering", "RenderPipelineManager");
    auto pipeline_class = class_from_name(render, "RenderCore", "RenderPipeline");
    auto particle_class = class_from_name(render, "RenderCore", "ParticleEffectSystem");
    auto snow_class = class_from_name(render, "RenderCore", "SnowParticleEffect");
    if (!manager_class || !pipeline_class || !particle_class || !snow_class) return false;

    g_current_pipeline_field = class_get_field(manager_class, "s_CurrentPipeline");
    g_particle_system_field = class_get_field(pipeline_class, "m_particleEffectSystem");
    g_snow_effect_field = class_get_field(particle_class, "m_snowFX");
    g_size_field = class_get_field(snow_class, "sizeMinMax");
    return g_current_pipeline_field && g_particle_system_field && g_snow_effect_field && g_size_field;
}

static bool locate(Target& out) {
    if (!g_current_pipeline_field || !g_particle_system_field || !g_snow_effect_field || !g_size_field)
        return false;
    field_static_get(g_current_pipeline_field, &out.pipeline);
    if (!out.pipeline) return false;
    field_get(out.pipeline, g_particle_system_field, &out.particle_system);
    if (!out.particle_system) return false;
    field_get(out.particle_system, g_snow_effect_field, &out.snow_effect);
    if (!out.snow_effect) return false;
    field_get(out.snow_effect, g_size_field, &out.size_parameter);
    return out.size_parameter != nullptr;
}

static float read_multiplier() {
    FILE* f = std::fopen("/data/local/tmp/carx_snow_multiplier", "r");
    if (!f) return 1.0f;
    float value = 1.0f;
    if (std::fscanf(f, "%f", &value) != 1) value = 1.0f;
    std::fclose(f);
    if (!std::isfinite(value)) value = 1.0f;
    if (value < 0.25f) value = 0.25f;
    if (value > 10.0f) value = 10.0f;
    return value;
}

static void write_status(const char* message) {
    const char* path = "/data/local/tmp/carx_snow_status";
    char old[256] = {};
    FILE* previous = std::fopen(path, "r");
    if (previous) { if (std::fgets(old, sizeof(old), previous)) { old[strcspn(old, "\n")] = 0; } std::fclose(previous); }
    if (message && std::strcmp(old, message) == 0) return;
    FILE* f = std::fopen(path, "w");
    if (!f) return;
    std::fprintf(f, "%s\n", message ? message : "unknown");
    std::fclose(f);
    chmod("/data/local/tmp/carx_snow_status", 0644);
}

static void* worker(void*) {
    // Zygisk may load this library before Unity has loaded/initialized IL2CPP.
    write_status("模块已注入，等待 IL2CPP API");
    while (!load_api()) { write_status("未找到 IL2CPP API；请确认游戏版本为 1.23 ARM64"); sleep(1); }
    write_status("IL2CPP API 已连接，等待游戏对象");
    SNOW_LOGI("IL2CPP API resolved");
    Il2CppDomain* domain = nullptr;
    while (!(domain = domain_get())) { write_status("等待 IL2CPP 域初始化"); sleep(1); }
    thread_attach(domain);
    SNOW_LOGI("IL2CPP domain ready; waiting for RenderCore types");
    while (!resolve_fields()) { write_status("未解析到 RenderCore 字段；版本或类型名不匹配"); sleep(1); }
    write_status("已解析 RenderCore，等待雪花对象");
    SNOW_LOGI("Runtime fields resolved for CarX Street 1.23");

    Il2CppObject* baseline_parameter = nullptr;
    float baseline_x = 0.0f, baseline_y = 0.0f;
    bool baseline_ready = false;
    float previous_multiplier = -100.0f;

    for (;;) {
        Target target{};
        if (locate(target)) {
            // Verified 1.23 layout from Vector2ParameterMod.CopyValue:
            // overrideState at +0x10; Vector2 m_Value occupies +0x14 and +0x18.
            auto* bytes = reinterpret_cast<uint8_t*>(target.size_parameter);
            float* x = reinterpret_cast<float*>(bytes + 0x14);
            float* y = reinterpret_cast<float*>(bytes + 0x18);
            if (target.size_parameter != baseline_parameter || !baseline_ready) {
                baseline_parameter = target.size_parameter;
                baseline_x = *x;
                baseline_y = *y;
                if (std::isfinite(baseline_x) && std::isfinite(baseline_y) &&
                    baseline_x > 0.0f && baseline_y > 0.0f && baseline_x < 1000.0f && baseline_y < 1000.0f) {
                    baseline_ready = true;
                    previous_multiplier = -100.0f;
                } else {
                    baseline_ready = false;
                }
            }
            const float multiplier = read_multiplier();
            if (baseline_ready && (std::fabs(multiplier - previous_multiplier) > 0.0001f ||
                                   std::fabs(*x - baseline_x * multiplier) > 0.0001f ||
                                   std::fabs(*y - baseline_y * multiplier) > 0.0001f)) {
                // OverrideState is a bool byte. Set it before writing the two float components.
                bytes[0x10] = 1;
                *x = baseline_x * multiplier;
                *y = baseline_y * multiplier;
                // SimulationStep checks this flag after UpdateEmitterList and uploads the emitter list.
                // 1.23 ParticleEffectSystem.m_emitterListIsDirty is at +0x20.
                *(reinterpret_cast<uint8_t*>(target.particle_system) + 0x20) = 1;
                previous_multiplier = multiplier;
                char status[128]; std::snprintf(status, sizeof(status), "已写入游戏对象：%.2f×", multiplier); write_status(status);
                SNOW_LOGI("Applied snow size multiplier %.2f (baseline %.4f, %.4f)", multiplier, baseline_x, baseline_y);
            }
        } else {
            write_status("模块已运行，当前未找到雪花对象");
            baseline_ready = false;
            baseline_parameter = nullptr;
        }
        usleep(16000); // ~60 Hz polling; runtime/device validation is still required.
    }
    return nullptr;
}

// Called by the in-process loader after libil2cpp.so is initialized.
void start() {
    pthread_t thread{};
    pthread_create(&thread, nullptr, worker, nullptr);
    pthread_detach(thread);
}
} // namespace snowctl

// Exported entry point for the eventual Zygisk loader. The loader must call this
// only inside the target game's process (com.carxtech.sr).
extern "C" __attribute__((visibility("default"))) void carx_snow_start() {
    snowctl::start();
}
