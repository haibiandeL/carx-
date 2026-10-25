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
struct MethodInfo;
struct Il2CppType;
struct Il2CppArray;

using domain_get_t = Il2CppDomain* (*)();
using thread_attach_t = void* (*)(Il2CppDomain*);
using domain_get_assemblies_t = const Il2CppAssembly** (*)(Il2CppDomain*, size_t*);
using assembly_get_image_t = const Il2CppImage* (*)(const Il2CppAssembly*);
using image_get_name_t = const char* (*)(const Il2CppImage*);
using class_from_name_t = Il2CppClass* (*)(const Il2CppImage*, const char*, const char*);
using class_get_field_t = FieldInfo* (*)(Il2CppClass*, const char*);
using field_static_get_t = void (*)(FieldInfo*, void*);
using field_get_t = void (*)(Il2CppObject*, FieldInfo*, void*);
using class_get_method_t = const MethodInfo* (*)(Il2CppClass*, const char*, int);
using class_get_type_t = const Il2CppType* (*)(Il2CppClass*);
using type_get_object_t = Il2CppObject* (*)(const Il2CppType*);
using runtime_invoke_t = Il2CppObject* (*)(const MethodInfo*, void*, void**, Il2CppObject**);

static domain_get_t domain_get;
static thread_attach_t thread_attach;
static domain_get_assemblies_t domain_get_assemblies;
static assembly_get_image_t assembly_get_image;
static image_get_name_t image_get_name;
static class_from_name_t class_from_name;
static class_get_field_t class_get_field;
static field_static_get_t field_static_get;
static field_get_t field_get;
static class_get_method_t class_get_method;
static class_get_type_t class_get_type;
static type_get_object_t type_get_object;
static runtime_invoke_t runtime_invoke;
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
    LOAD_SYM(class_get_method, "il2cpp_class_get_method_from_name");
    LOAD_SYM(class_get_type, "il2cpp_class_get_type");
    LOAD_SYM(type_get_object, "il2cpp_type_get_object");
    LOAD_SYM(runtime_invoke, "il2cpp_runtime_invoke");
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
static Il2CppClass* g_timecycle_class = nullptr;
static const MethodInfo* g_set_preset_method = nullptr;
static const MethodInfo* g_set_manual_time_method = nullptr;
static const MethodInfo* g_find_objects_method = nullptr;
static Il2CppObject* g_timecycle_type_object = nullptr;
static Il2CppClass* g_unity_object_class = nullptr;
static FieldInfo* g_weather_presets_field = nullptr;
static FieldInfo* g_weather_volume_base_field = nullptr;

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

    // Video-confirmed weather call: LocationTimecycleBehaviour.SetPresetType(PresetType).
    // Find an active instance dynamically; never reuse the address shown in IL2CPP Tool,
    // because that address changes every process launch.
    auto game = find_image("Assembly-CSharp.dll");
    if (!game) game = find_image("Assembly-CSharp");
    if (game) g_timecycle_class = class_from_name(game, "Prototype.Views.Locations", "LocationTimecycleBehaviour");
    g_set_preset_method = g_timecycle_class ? class_get_method(g_timecycle_class, "SetPresetType", 1) : nullptr;
    g_set_manual_time_method = g_timecycle_class ? class_get_method(g_timecycle_class, "SetManualTime", 1) : nullptr;
    g_timecycle_type_object = (g_timecycle_class && class_get_type && type_get_object)
        ? type_get_object(class_get_type(g_timecycle_class)) : nullptr;
    g_unity_object_class = class_from_name(core, "UnityEngine", "Object");
    g_find_objects_method = g_unity_object_class ? class_get_method(g_unity_object_class, "FindObjectsOfType", 1) : nullptr;
    g_weather_presets_field = g_timecycle_class ? class_get_field(g_timecycle_class, "m_presets") : nullptr;
    g_weather_volume_base_field = g_timecycle_class ? class_get_field(g_timecycle_class, "m_volumeBase") : nullptr;
    return g_current_pipeline_field && g_particle_system_field && g_snow_effect_field && g_size_field;
}

static bool resolve_weather() {
    auto core = find_image("UnityEngine.CoreModule.dll");
    auto game = find_image("Assembly-CSharp.dll");
    if (!game) game = find_image("Assembly-CSharp");
    if (!core || !game) return false;
    g_timecycle_class = class_from_name(game, "Prototype.Views.Locations", "LocationTimecycleBehaviour");
    g_set_preset_method = g_timecycle_class ? class_get_method(g_timecycle_class, "SetPresetType", 1) : nullptr;
    g_set_manual_time_method = g_timecycle_class ? class_get_method(g_timecycle_class, "SetManualTime", 1) : nullptr;
    g_timecycle_type_object = (g_timecycle_class && class_get_type && type_get_object)
        ? type_get_object(class_get_type(g_timecycle_class)) : nullptr;
    g_unity_object_class = class_from_name(core, "UnityEngine", "Object");
    g_find_objects_method = g_unity_object_class ? class_get_method(g_unity_object_class, "FindObjectsOfType", 1) : nullptr;
    g_weather_presets_field = g_timecycle_class ? class_get_field(g_timecycle_class, "m_presets") : nullptr;
    g_weather_volume_base_field = g_timecycle_class ? class_get_field(g_timecycle_class, "m_volumeBase") : nullptr;
    return g_timecycle_class && g_set_preset_method && g_timecycle_type_object && g_find_objects_method && g_weather_presets_field;
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

static void write_status(const char* message);

static int read_weather() {
    FILE* f = std::fopen("/data/local/tmp/carx_snow_weather", "r");
    if (!f) return -1;
    int value = -1;
    if (std::fscanf(f, "%d", &value) != 1) value = -1;
    std::fclose(f);
    return (value >= 0 && value <= 2) ? value : -1;
}

// Version-specific reflective invocation based on the user's successful IL2CPP Tool workflow.
// Returns false if any runtime precondition is missing; it never uses a hard-coded object address.
static bool apply_weather(int preset) {
    if (!g_timecycle_class || !g_set_preset_method || !g_timecycle_type_object ||
        !g_find_objects_method || !runtime_invoke) {
        write_status("雪花控制已连接；天气方法/类型未解析");
        return false;
    }
    Il2CppObject* exception = nullptr;
    void* find_args[1] = { g_timecycle_type_object };
    Il2CppObject* array_obj = runtime_invoke(g_find_objects_method, nullptr, find_args, &exception);
    if (exception || !array_obj) {
        write_status("天气调用失败：查找 Timecycle 实例失败");
        SNOW_LOGE("FindObjectsOfType failed; exception=%p", exception);
        return false;
    }
    // IL2CPP ARM64 array layout: object header (0x10), bounds (0x10), max_length (0x18), vector (0x20).
    // This is only used for the game's matching 1.23 ARM64 build.
    auto* array_bytes = reinterpret_cast<uint8_t*>(array_obj);
    size_t count = *reinterpret_cast<size_t*>(array_bytes + 0x18);
    if (count > 128) count = 128; // defensive cap; this component should be unique in the active scene.
    auto** elements = reinterpret_cast<Il2CppObject**>(array_bytes + 0x20);
    if (count == 0) {
        write_status("天气未切换：当前场景没有活动 Timecycle 实例");
        return false;
    }
    // The dump identifies m_presets at +0x28 and m_volumeBase at +0x40.
    // Prefer a live component that has initialized preset data; do not call every
    // arbitrary object returned by Unity's type search.
    int arg_value = preset;
    void* invoke_args[1] = { &arg_value };
    Il2CppObject* selected = nullptr;
    for (size_t i = 0; i < count; ++i) {
        Il2CppObject* instance = elements[i];
        if (!instance) continue;
        Il2CppObject* presets = nullptr;
        Il2CppObject* volume_base = nullptr;
        field_get(instance, g_weather_presets_field, &presets);
        if (g_weather_volume_base_field) field_get(instance, g_weather_volume_base_field, &volume_base);
        if (presets) { selected = instance; if (volume_base) break; }
    }
    if (!selected) {
        write_status("天气未切换：找到类型但没有已初始化的预设实例");
        SNOW_LOGE("No initialized LocationTimecycleBehaviour candidate; count=%zu", count);
        return false;
    }
    // IMPORTANT: runtime_invoke is currently called from the controller worker,
    // not a Unity main-thread hook. Some Unity/game methods require main-thread
    // execution. This source must be device-tested before claiming success.
    exception = nullptr;
    runtime_invoke(g_set_preset_method, selected, invoke_args, &exception);
    if (exception) {
        write_status("天气调用失败：SetPresetType 抛出异常；查看 CarXSnow 日志");
        SNOW_LOGE("SetPresetType exception for selected instance %p: %p", selected, exception);
        return false;
    }
    const char* names[] = { "Generic", "Halloween", "Winter" };
    char status[160];
    std::snprintf(status, sizeof(status), "已向实例提交天气调用：%s（枚举 %d）；需实机确认", names[preset], preset);
    write_status(status);
    SNOW_LOGI("SetPresetType submitted: preset=%d instance=%p", preset, selected);
    return true;
}

static int read_time_minutes() {
    FILE* f = std::fopen("/data/local/tmp/carx_snow_time_minutes", "r");
    if (!f) return -1;
    int value = -1;
    if (std::fscanf(f, "%d", &value) != 1) value = -1;
    std::fclose(f);
    if (value < 0 || value > 1439) return -1;
    return value;
}

static bool apply_manual_time(int minutes) {
    if (minutes < 0 || minutes > 1439 || !g_timecycle_class || !g_set_manual_time_method ||
        !g_timecycle_type_object || !g_find_objects_method) return false;
    void* find_args[1] = { g_timecycle_type_object };
    Il2CppObject* exception = nullptr;
    Il2CppObject* array_obj = runtime_invoke(g_find_objects_method, nullptr, find_args, &exception);
    if (exception || !array_obj) { write_status("时间：查找游戏对象失败"); return false; }
    // Read the ARM64 IL2CPP array header by byte offset so this compiles
    // even when Il2CppArray is only forward-declared. Expected layout:
    // max_length at +0x18, element vector at +0x20.
    auto* arr_bytes = reinterpret_cast<uint8_t*>(array_obj);
    size_t array_count = 0;
    std::memcpy(&array_count, arr_bytes + 0x18, sizeof(array_count));
    if (array_count == 0) { write_status("时间：未找到 LocationTimecycleBehaviour 实例"); return false; }
    // Defensive cap against an unexpected/corrupt array header.
    if (array_count > 128) { write_status("时间：实例数组长度异常"); return false; }
    // User-provided API takes System.Single. The UI sends minutes since midnight;
    // this implementation passes decimal hours (e.g. 18:30 -> 18.5). Confirm this
    // mapping in-game before treating the call as validated.
    float manual_time = static_cast<float>(minutes) / 60.0f;
    void* invoke_args[1] = { &manual_time };
    Il2CppObject* selected = nullptr;
    for (size_t i = 0; i < array_count; ++i) {
        Il2CppObject* instance = nullptr;
        std::memcpy(&instance, arr_bytes + 0x20 + i * sizeof(void*), sizeof(instance));
        if (instance) { selected = instance; break; }
    }
    if (!selected) { write_status("时间：没有有效实例"); return false; }
    exception = nullptr;
    runtime_invoke(g_set_manual_time_method, selected, invoke_args, &exception);
    if (exception) { write_status("时间：SetManualTime 调用异常"); return false; }
    char status[128];
    std::snprintf(status, sizeof(status), "已提交时间调用 %02d:%02d（参数 %.3f；需画面验证）", minutes/60, minutes%60, manual_time);
    write_status(status);
    SNOW_LOGI("SetManualTime submitted: %02d:%02d value=%.3f instance=%p", minutes/60, minutes%60, manual_time, selected);
    return true;
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
    // Delay runtime initialization to let the game finish its early startup.
    write_status("模块已注入，等待 10 秒后初始化");
    SNOW_LOGI("10-second delayed initialization started");
    sleep(10);
    SNOW_LOGI("10-second delay finished; resolving IL2CPP API");
    // Zygisk may load this library before Unity has loaded/initialized IL2CPP.
    write_status("等待 IL2CPP API");
    while (!load_api()) { write_status("未找到 IL2CPP API；请确认游戏版本为 1.23 ARM64"); sleep(1); }
    write_status("IL2CPP API 已连接，等待游戏对象");
    SNOW_LOGI("IL2CPP API resolved");
    Il2CppDomain* domain = nullptr;
    while (!(domain = domain_get())) { write_status("等待 IL2CPP 域初始化"); sleep(1); }
    thread_attach(domain);
    SNOW_LOGI("IL2CPP domain ready; waiting for RenderCore types");
    while (!resolve_fields()) { write_status("未解析到 RenderCore 字段；版本或类型名不匹配"); sleep(1); }
    write_status("已解析雪花控制字段；正在解析天气方法");
    SNOW_LOGI("Runtime fields resolved for CarX Street 1.23");

    int previous_weather = -999;
    unsigned int weather_resolve_counter = 0;
    Il2CppObject* baseline_parameter = nullptr;
    float baseline_x = 0.0f, baseline_y = 0.0f;
    bool baseline_ready = false;
    float previous_multiplier = -100.0f;

    for (;;) {
        if ((!g_set_preset_method || !g_set_manual_time_method || !g_find_objects_method) && (++weather_resolve_counter % 60 == 0)) {
            resolve_weather(); // retry while assemblies finish loading; ~1 second at the normal loop rate
        }
        static int previous_time = -999;
        const int time_minutes = read_time_minutes();
        if (time_minutes >= 0 && time_minutes != previous_time) {
            apply_manual_time(time_minutes);
            previous_time = time_minutes;
        }
        const int weather = read_weather();
        if (weather >= 0 && weather != previous_weather) {
            if (apply_weather(weather)) previous_weather = weather;
            else previous_weather = weather; // one attempt per user selection; change the selection to retry.
        }
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
