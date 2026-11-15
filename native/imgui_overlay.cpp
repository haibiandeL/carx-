// PigHax B-mode overlay for CarX Street 1.24
// Stock Dear ImGui style. Touch input is tapped at UnityPlayer.nativeInjectEvent,
// registered through JNI RegisterNatives. Touches inside the ImGui window are
// consumed by the overlay; touches outside the window continue to Unity.
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <pthread.h>
#include <unistd.h>
#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <vector>
#include <deque>
#include <mutex>
#include <sys/stat.h>
#include "imgui.h"
#include "backends/imgui_impl_opengl3.h"
#include "dobby.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "PigHax", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "PigHax", __VA_ARGS__)

namespace {
using SwapFn = EGLBoolean (*)(EGLDisplay, EGLSurface);
using RegisterNativesFn = jint (JNICALL *)(JNIEnv*, jclass, const JNINativeMethod*, jint);
using NativeInjectEventFn = jboolean (JNICALL *)(JNIEnv*, jclass, jobject);

SwapFn real_swap = nullptr;
RegisterNativesFn real_register_natives = nullptr;
NativeInjectEventFn real_native_inject_event = nullptr;
void* egl_handle = nullptr;

std::atomic<bool> render_hook_installed{false};
std::atomic<bool> jni_hook_installed{false};
std::atomic<bool> imgui_ready{false};
std::atomic<int> surface_w{0}, surface_h{0};
std::atomic<unsigned int> swap_count{0}, touch_count{0};

std::atomic<float> menu_left{0.0f}, menu_top{0.0f}, menu_right{0.0f}, menu_bottom{0.0f};
std::atomic<bool> menu_rect_ready{false};
std::atomic<bool> overlay_touch_captured{false};

struct TouchEvent {
    float x;
    float y;
    int button; // -1 = position only, 0 = release, 1 = press
};
std::mutex touch_mutex;
std::deque<TouchEvent> touch_events;

jclass motion_event_class = nullptr;
jmethodID motion_get_action = nullptr;
jmethodID motion_get_x = nullptr;
jmethodID motion_get_y = nullptr;
std::mutex motion_cache_mutex;

bool snow_enabled = true;
int weather_index = 0;
int game_minutes = 720;
float particle_scale = 1.0f;

void write_value(const char* path, const char* fmt, ...) {
    FILE* f = std::fopen(path, "w");
    if (!f) return;
    va_list ap;
    va_start(ap, fmt);
    std::vfprintf(f, fmt, ap);
    va_end(ap);
    std::fputc('\n', f);
    std::fclose(f);
    chmod(path, 0644);
}

void enqueue_touch(float x, float y, int button) {
    std::lock_guard<std::mutex> lock(touch_mutex);
    if (touch_events.size() >= 128) touch_events.pop_front();
    touch_events.push_back({x, y, button});
}

void consume_touch_events(ImGuiIO& io) {
    std::deque<TouchEvent> local;
    {
        std::lock_guard<std::mutex> lock(touch_mutex);
        local.swap(touch_events);
    }
    for (const TouchEvent& e : local) {
        io.AddMousePosEvent(e.x, e.y);
        if (e.button >= 0) io.AddMouseButtonEvent(0, e.button != 0);
    }
}

bool point_inside_menu(float x, float y) {
    if (!menu_rect_ready.load(std::memory_order_acquire)) return false;
    return x >= menu_left.load(std::memory_order_relaxed) &&
           x <= menu_right.load(std::memory_order_relaxed) &&
           y >= menu_top.load(std::memory_order_relaxed) &&
           y <= menu_bottom.load(std::memory_order_relaxed);
}

bool ensure_motion_event_cache(JNIEnv* env) {
    if (motion_event_class && motion_get_action && motion_get_x && motion_get_y) return true;
    std::lock_guard<std::mutex> lock(motion_cache_mutex);
    if (motion_event_class && motion_get_action && motion_get_x && motion_get_y) return true;

    jclass local = env->FindClass("android/view/MotionEvent");
    if (!local) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    jclass global = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (!global) return false;

    jmethodID action = env->GetMethodID(global, "getAction", "()I");
    jmethodID x = env->GetMethodID(global, "getX", "()F");
    jmethodID y = env->GetMethodID(global, "getY", "()F");
    if (!action || !x || !y || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteGlobalRef(global);
        return false;
    }
    motion_event_class = global;
    motion_get_action = action;
    motion_get_x = x;
    motion_get_y = y;
    LOGI("MotionEvent JNI methods cached");
    return true;
}

jboolean JNICALL hooked_native_inject_event(JNIEnv* env, jclass clazz, jobject event) {
    if (event && ensure_motion_event_cache(env) && env->IsInstanceOf(event, motion_event_class)) {
        const jint action_full = env->CallIntMethod(event, motion_get_action);
        const jfloat x = env->CallFloatMethod(event, motion_get_x);
        const jfloat y = env->CallFloatMethod(event, motion_get_y);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        } else {
            const jint action = action_full & 0xff;
            bool captured = overlay_touch_captured.load(std::memory_order_acquire);

            if (action == 0 /* ACTION_DOWN */) {
                captured = point_inside_menu(x, y);
                overlay_touch_captured.store(captured, std::memory_order_release);
                if (captured) enqueue_touch(x, y, 1);
            } else if (captured) {
                if (action == 2 /* ACTION_MOVE */) {
                    enqueue_touch(x, y, -1);
                } else if (action == 1 /* ACTION_UP */ || action == 3 /* ACTION_CANCEL */) {
                    enqueue_touch(x, y, 0);
                    overlay_touch_captured.store(false, std::memory_order_release);
                }
            }

            if (captured) {
                const unsigned int n = ++touch_count;
                if (n == 1 || n == 20 || n == 100) {
                    LOGI("Unity touch captured #%u action=%d x=%.1f y=%.1f", n, action, x, y);
                }
                return JNI_TRUE; // do not pass menu touches into the game
            }
        }
    }

    return real_native_inject_event
        ? real_native_inject_event(env, clazz, event)
        : JNI_FALSE;
}

jint JNICALL hooked_register_natives(JNIEnv* env, jclass clazz,
                                     const JNINativeMethod* methods, jint count) {
    if (!real_register_natives || !methods || count <= 0) {
        return real_register_natives ? real_register_natives(env, clazz, methods, count) : JNI_ERR;
    }

    int target_index = -1;
    for (jint i = 0; i < count; ++i) {
        if (methods[i].name && methods[i].signature &&
            std::strcmp(methods[i].name, "nativeInjectEvent") == 0 &&
            std::strcmp(methods[i].signature, "(Landroid/view/InputEvent;)Z") == 0) {
            target_index = i;
            break;
        }
    }

    if (target_index < 0) return real_register_natives(env, clazz, methods, count);

    jclass unity_player = env->FindClass("com/unity3d/player/UnityPlayer");
    if (!unity_player) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGE("UnityPlayer class lookup failed while registering nativeInjectEvent");
        return real_register_natives(env, clazz, methods, count);
    }
    const bool is_unity_player = env->IsSameObject(clazz, unity_player);
    env->DeleteLocalRef(unity_player);
    if (!is_unity_player) return real_register_natives(env, clazz, methods, count);

    std::vector<JNINativeMethod> patched(methods, methods + count);
    real_native_inject_event = reinterpret_cast<NativeInjectEventFn>(patched[target_index].fnPtr);
    patched[target_index].fnPtr = reinterpret_cast<void*>(hooked_native_inject_event);

    const jint result = real_register_natives(env, clazz, patched.data(), count);
    if (result == JNI_OK) {
        LOGI("UnityPlayer.nativeInjectEvent hook registered successfully");
    } else {
        LOGE("RegisterNatives for UnityPlayer failed rc=%d", result);
    }
    return result;
}

void draw_menu() {
    ImGuiIO& io = ImGui::GetIO();
    const int w = surface_w.load(std::memory_order_relaxed);
    const int h = surface_h.load(std::memory_order_relaxed);
    io.DisplaySize = ImVec2((float)w, (float)h);
    consume_touch_events(io);

    ImGui_ImplOpenGL3_NewFrame();
    ImGui::NewFrame();

    if (w > 0 && h > 0) {
        const float panel_w = (float)w * 0.78f;
        const float final_w = panel_w < 900.0f ? panel_w : 900.0f;
        ImGui::SetNextWindowPos(ImVec2((float)w * 0.035f, (float)h * 0.06f), ImGuiCond_Once);
        ImGui::SetNextWindowSize(ImVec2(final_w, 0.0f), ImGuiCond_Once);

        ImGui::Begin("PigHax | CarX Street", nullptr,
                     ImGuiWindowFlags_AlwaysAutoResize |
                     ImGuiWindowFlags_NoCollapse |
                     ImGuiWindowFlags_NoSavedSettings);

        const ImVec2 pos = ImGui::GetWindowPos();
        const ImVec2 size = ImGui::GetWindowSize();
        menu_left.store(pos.x, std::memory_order_relaxed);
        menu_top.store(pos.y, std::memory_order_relaxed);
        menu_right.store(pos.x + size.x, std::memory_order_relaxed);
        menu_bottom.store(pos.y + size.y, std::memory_order_relaxed);
        menu_rect_ready.store(true, std::memory_order_release);

        ImGui::TextDisabled("IN-GAME CONTROL PANEL");
        ImGui::Separator();

        if (ImGui::CollapsingHeader("Weather", ImGuiTreeNodeFlags_DefaultOpen)) {
            const char* weather[] = {"Generic", "Halloween", "Winter", "Cloudy", "Rainy"};
            if (ImGui::Combo("Weather preset", &weather_index, weather, IM_ARRAYSIZE(weather))) {
                write_value("/data/local/tmp/carx_snow_weather", "%d", weather_index);
            }
        }

        if (ImGui::CollapsingHeader("Snow", ImGuiTreeNodeFlags_DefaultOpen)) {
            if (ImGui::Checkbox("Snow weather / particles", &snow_enabled)) {
                write_value("/data/local/tmp/carx_snow_enabled", "%d", snow_enabled ? 1 : 0);
            }
            if (ImGui::SliderFloat("Rain + snowflake size", &particle_scale, 0.25f, 3.0f, "%.2fx")) {
                write_value("/data/local/tmp/carx_snow_multiplier", "%.3f", particle_scale);
            }
        }

        if (ImGui::CollapsingHeader("Time")) {
            int hour = game_minutes / 60;
            int minute = game_minutes % 60;
            if (ImGui::SliderInt("Hour", &hour, 0, 23)) {
                game_minutes = hour * 60 + minute;
                write_value("/data/local/tmp/carx_snow_time_minutes", "%d", game_minutes);
            }
            if (ImGui::SliderInt("Minute", &minute, 0, 59)) {
                game_minutes = hour * 60 + minute;
                write_value("/data/local/tmp/carx_snow_time_minutes", "%d", game_minutes);
            }
            ImGui::Text("Game time: %02d:%02d", game_minutes / 60, game_minutes % 60);
        }

        ImGui::End();
    } else {
        menu_rect_ready.store(false, std::memory_order_release);
    }

    ImGui::Render();
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
}

EGLBoolean hooked_swap(EGLDisplay display, EGLSurface surface) {
    if (!real_swap) return EGL_FALSE;
    const unsigned int n = ++swap_count;
    if (n == 1 || n == 1200) LOGI("eglSwapBuffers callback active count=%u", n);

    if (eglGetCurrentContext() != EGL_NO_CONTEXT) {
        EGLint w = 0, h = 0;
        if (eglQuerySurface(display, surface, EGL_WIDTH, &w) == EGL_TRUE &&
            eglQuerySurface(display, surface, EGL_HEIGHT, &h) == EGL_TRUE && w > 0 && h > 0) {
            surface_w.store(w, std::memory_order_relaxed);
            surface_h.store(h, std::memory_order_relaxed);
        }

        if (!imgui_ready.load(std::memory_order_acquire) &&
            surface_w.load() > 0 && surface_h.load() > 0) {
            IMGUI_CHECKVERSION();
            ImGui::CreateContext();
            ImGui::StyleColorsDark();
            ImGuiIO& io = ImGui::GetIO();
            io.IniFilename = nullptr;
            io.DisplaySize = ImVec2((float)surface_w.load(), (float)surface_h.load());

            if (ImGui_ImplOpenGL3_Init("#version 300 es")) {
                imgui_ready.store(true, std::memory_order_release);
                LOGI("Stock ImGui dark style initialized; surface=%dx%d",
                     surface_w.load(), surface_h.load());
            } else {
                LOGE("ImGui GLES3 initialization failed");
                ImGui::DestroyContext();
            }
        }
        if (imgui_ready.load(std::memory_order_acquire)) draw_menu();
    }
    return real_swap(display, surface);
}

bool try_install_render_hook() {
    if (render_hook_installed.load()) return true;
    if (!egl_handle) egl_handle = dlopen("libEGL.so", RTLD_NOW | RTLD_NOLOAD);
    if (!egl_handle) egl_handle = dlopen("libEGL.so", RTLD_NOW);
    if (!egl_handle) return false;

    void* swap = dlsym(egl_handle, "eglSwapBuffers");
    if (!swap) swap = dlsym(RTLD_DEFAULT, "eglSwapBuffers");
    if (!swap) {
        using GetProcFn = __eglMustCastToProperFunctionPointerType (*)(const char*);
        auto gp = reinterpret_cast<GetProcFn>(dlsym(egl_handle, "eglGetProcAddress"));
        if (gp) swap = reinterpret_cast<void*>(gp("eglSwapBuffers"));
    }
    if (!swap) return false;

    const int rc = DobbyHook(swap, reinterpret_cast<void*>(hooked_swap),
                             reinterpret_cast<void**>(&real_swap));
    if (rc == 0 && real_swap) {
        render_hook_installed.store(true);
        LOGI("eglSwapBuffers hook installed");
        return true;
    }
    LOGE("eglSwapBuffers hook failed rc=%d", rc);
    return false;
}

void* hook_worker(void*) {
    LOGI("Render hook worker entered");
    for (int i = 0; i < 300; ++i) {
        const bool render_ok = try_install_render_hook();
        if (render_ok) break;
        if (i == 0 || i == 9 || i == 29 || i == 59 || i == 119 || i == 179 || i == 239 || i == 299)
            LOGI("render hook retry=%d installed=%d", i + 1, render_ok ? 1 : 0);
        sleep(1);
    }
    LOGI("hook worker finished render=%d jni_input=%d",
         render_hook_installed.load() ? 1 : 0, jni_hook_installed.load() ? 1 : 0);
    if (!render_hook_installed.load()) LOGE("render hook timed out; overlay cannot be drawn");
    if (!jni_hook_installed.load()) LOGE("Unity nativeInjectEvent hook not installed; touch is not available");
    return nullptr;
}
} // namespace

extern "C" __attribute__((visibility("default"))) void carx_imgui_set_env(JNIEnv* env) {
    if (!env || jni_hook_installed.load(std::memory_order_acquire)) return;
    void* register_natives = reinterpret_cast<void*>(env->functions->RegisterNatives);
    if (!register_natives) {
        LOGE("JNI RegisterNatives pointer unavailable");
        return;
    }
    const int rc = DobbyHook(register_natives, reinterpret_cast<void*>(hooked_register_natives),
                             reinterpret_cast<void**>(&real_register_natives));
    if (rc == 0 && real_register_natives) {
        jni_hook_installed.store(true, std::memory_order_release);
        LOGI("JNI RegisterNatives hook installed before Unity startup");
    } else {
        LOGE("JNI RegisterNatives hook failed rc=%d", rc);
    }
}

extern "C" __attribute__((visibility("default"))) void carx_imgui_start() {
    static std::atomic<bool> started{false};
    bool expected = false;
    if (!started.compare_exchange_strong(expected, true)) {
        LOGI("carx_imgui_start called again; worker already started");
        return;
    }
    LOGI("carx_imgui_start entered");
    pthread_t t{};
    const int rc = pthread_create(&t, nullptr, hook_worker, nullptr);
    if (rc == 0) {
        pthread_detach(t);
        LOGI("render hook worker thread created");
    } else {
        started.store(false);
        LOGE("render hook worker creation failed rc=%d", rc);
    }
}
