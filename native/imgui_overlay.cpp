// PigHax B-mode overlay for CarX Street 1.24
// Complete replacement for native/imgui_overlay.cpp.
// Uses stock Dear ImGui dark style. Touches inside the menu are consumed;
// touches outside the menu continue to Unity.

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
std::atomic<bool> register_hook_installed{false};
std::atomic<bool> native_input_hook_installed{false};
std::atomic<bool> imgui_ready{false};
std::atomic<int> surface_w{0}, surface_h{0};
std::atomic<unsigned int> swap_count{0}, touch_count{0};

std::atomic<float> menu_left{0}, menu_top{0}, menu_right{0}, menu_bottom{0};
std::atomic<bool> menu_rect_ready{false};
std::atomic<bool> overlay_touch_captured{false};

struct TouchEvent { float x; float y; int button; };
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
    if (!f) {
        LOGE("cannot write setting file: %s", path);
        return;
    }
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
    for (const auto& e : local) {
        io.AddMousePosEvent(e.x, e.y);
        if (e.button >= 0) io.AddMouseButtonEvent(0, e.button != 0);
    }
}

bool point_inside_menu(float x, float y) {
    if (!menu_rect_ready.load(std::memory_order_acquire)) return false;
    return x >= menu_left.load() && x <= menu_right.load() &&
           y >= menu_top.load() && y <= menu_bottom.load();
}

bool ensure_motion_event_cache(JNIEnv* env) {
    if (motion_event_class && motion_get_action && motion_get_x && motion_get_y) return true;
    std::lock_guard<std::mutex> lock(motion_cache_mutex);
    if (motion_event_class && motion_get_action && motion_get_x && motion_get_y) return true;

    jclass local = env->FindClass("android/view/MotionEvent");
    if (!local) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGE("MotionEvent class lookup failed");
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
        LOGE("MotionEvent method lookup failed");
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
    if (event && ensure_motion_event_cache(env) &&
        env->IsInstanceOf(event, motion_event_class)) {
        jint full_action = env->CallIntMethod(event, motion_get_action);
        jfloat x = env->CallFloatMethod(event, motion_get_x);
        jfloat y = env->CallFloatMethod(event, motion_get_y);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        } else {
            jint action = full_action & 0xff;
            bool captured = overlay_touch_captured.load(std::memory_order_acquire);

            if (action == 0) { // ACTION_DOWN
                captured = point_inside_menu(x, y);
                overlay_touch_captured.store(captured, std::memory_order_release);
                if (captured) enqueue_touch(x, y, 1);
            } else if (captured) {
                if (action == 2) enqueue_touch(x, y, -1); // ACTION_MOVE
                else if (action == 1 || action == 3) {   // UP / CANCEL
                    enqueue_touch(x, y, 0);
                    overlay_touch_captured.store(false, std::memory_order_release);
                }
            }

            if (captured) {
                unsigned int n = ++touch_count;
                if (n == 1 || n == 20 || n == 100)
                    LOGI("menu touch captured #%u action=%d x=%.1f y=%.1f", n, action, x, y);
                return JNI_TRUE;
            }
        }
    }
    return real_native_inject_event
        ? real_native_inject_event(env, clazz, event)
        : JNI_FALSE;
}

bool is_unity_player_class(JNIEnv* env, jclass clazz) {
    // clazz is already the exact java.lang.Class object supplied to RegisterNatives.
    // GetObjectClass(clazz) avoids FindClass() using the wrong application class loader.
    jclass class_class = env->GetObjectClass(clazz);
    if (!class_class) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    jmethodID get_name = env->GetMethodID(class_class, "getName", "()Ljava/lang/String;");
    if (!get_name || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(class_class);
        return false;
    }
    auto name = static_cast<jstring>(env->CallObjectMethod(clazz, get_name));
    env->DeleteLocalRef(class_class);
    if (!name || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (name) env->DeleteLocalRef(name);
        return false;
    }
    const char* utf = env->GetStringUTFChars(name, nullptr);
    bool match = utf && std::strcmp(utf, "com.unity3d.player.UnityPlayer") == 0;
    if (utf) env->ReleaseStringUTFChars(name, utf);
    env->DeleteLocalRef(name);
    return match;
}

jint JNICALL hooked_register_natives(JNIEnv* env, jclass clazz,
                                     const JNINativeMethod* methods, jint count) {
    if (!real_register_natives || !methods || count <= 0)
        return real_register_natives
            ? real_register_natives(env, clazz, methods, count) : JNI_ERR;

    int target = -1;
    for (jint i = 0; i < count; ++i) {
        if (methods[i].name && methods[i].signature &&
            std::strcmp(methods[i].name, "nativeInjectEvent") == 0 &&
            std::strcmp(methods[i].signature, "(Landroid/view/InputEvent;)Z") == 0) {
            target = i;
            break;
        }
    }
    if (target < 0 || !is_unity_player_class(env, clazz))
        return real_register_natives(env, clazz, methods, count);

    std::vector<JNINativeMethod> patched(methods, methods + count);
    real_native_inject_event =
        reinterpret_cast<NativeInjectEventFn>(patched[target].fnPtr);
    patched[target].fnPtr = reinterpret_cast<void*>(hooked_native_inject_event);

    jint rc = real_register_natives(env, clazz, patched.data(), count);
    if (rc == JNI_OK) {
        native_input_hook_installed.store(true, std::memory_order_release);
        LOGI("UnityPlayer.nativeInjectEvent hook registered successfully");
    } else {
        LOGE("UnityPlayer RegisterNatives failed rc=%d", rc);
    }
    return rc;
}

void draw_menu() {
    ImGuiIO& io = ImGui::GetIO();
    int w = surface_w.load();
    int h = surface_h.load();
    io.DisplaySize = ImVec2((float)w, (float)h);
    consume_touch_events(io);

    ImGui_ImplOpenGL3_NewFrame();
    ImGui::NewFrame();

    if (w > 0 && h > 0) {
        float panel_w = (float)w * 0.78f;
        float final_w = panel_w < 900.0f ? panel_w : 900.0f;
        ImGui::SetNextWindowPos(ImVec2((float)w * 0.035f, (float)h * 0.06f),
                                ImGuiCond_Once);
        ImGui::SetNextWindowSize(ImVec2(final_w, 0.0f), ImGuiCond_Once);
        ImGui::Begin("PigHax | CarX Street", nullptr,
                     ImGuiWindowFlags_AlwaysAutoResize |
                     ImGuiWindowFlags_NoCollapse |
                     ImGuiWindowFlags_NoSavedSettings);

        ImVec2 pos = ImGui::GetWindowPos();
        ImVec2 size = ImGui::GetWindowSize();
        menu_left.store(pos.x);
        menu_top.store(pos.y);
        menu_right.store(pos.x + size.x);
        menu_bottom.store(pos.y + size.y);
        menu_rect_ready.store(true, std::memory_order_release);

        ImGui::TextDisabled("IN-GAME CONTROL PANEL");
        ImGui::Separator();

        if (ImGui::CollapsingHeader("Weather", ImGuiTreeNodeFlags_DefaultOpen)) {
            const char* weather[] = {"Generic", "Halloween", "Winter", "Cloudy", "Rainy"};
            if (ImGui::Combo("Weather preset", &weather_index, weather, IM_ARRAYSIZE(weather)))
                write_value("/data/local/tmp/carx_snow_weather", "%d", weather_index);
        }

        if (ImGui::CollapsingHeader("Snow", ImGuiTreeNodeFlags_DefaultOpen)) {
            if (ImGui::Checkbox("Snow weather / particles", &snow_enabled))
                write_value("/data/local/tmp/carx_snow_enabled", "%d", snow_enabled ? 1 : 0);
            if (ImGui::SliderFloat("Rain + snowflake size", &particle_scale, 0.25f, 3.0f, "%.2fx"))
                write_value("/data/local/tmp/carx_snow_multiplier", "%.3f", particle_scale);
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
    unsigned int n = ++swap_count;
    if (n == 1 || n == 1200) LOGI("eglSwapBuffers callback active count=%u", n);

    if (eglGetCurrentContext() != EGL_NO_CONTEXT) {
        EGLint w = 0, h = 0;
        if (eglQuerySurface(display, surface, EGL_WIDTH, &w) == EGL_TRUE &&
            eglQuerySurface(display, surface, EGL_HEIGHT, &h) == EGL_TRUE &&
            w > 0 && h > 0) {
            surface_w.store(w);
            surface_h.store(h);
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
                LOGI("Stock ImGui initialized surface=%dx%d",
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
    if (!egl_handle) {
        LOGE("dlopen libEGL.so failed: %s", dlerror());
        return false;
    }

    void* target = dlsym(egl_handle, "eglSwapBuffers");
    if (!target) target = dlsym(RTLD_DEFAULT, "eglSwapBuffers");
    if (!target) {
        using GetProcFn = __eglMustCastToProperFunctionPointerType (*)(const char*);
        auto get_proc = reinterpret_cast<GetProcFn (*)(const char*)>(
            dlsym(egl_handle, "eglGetProcAddress"));
        if (get_proc) target = reinterpret_cast<void*>(get_proc("eglSwapBuffers"));
    }
    if (!target) {
        LOGE("eglSwapBuffers symbol not found");
        return false;
    }

    int rc = DobbyHook(target, reinterpret_cast<void*>(hooked_swap),
                       reinterpret_cast<void**>(&real_swap));
    if (rc == 0 && real_swap) {
        render_hook_installed.store(true, std::memory_order_release);
        LOGI("eglSwapBuffers hook installed");
        return true;
    }
    LOGE("eglSwapBuffers hook failed rc=%d", rc);
    return false;
}

void* hook_worker(void*) {
    LOGI("Render hook worker entered");
    for (int i = 0; i < 300; ++i) {
        if (try_install_render_hook()) break;
        if (i == 0 || i == 9 || i == 29 || i == 59 ||
            i == 119 || i == 179 || i == 239 || i == 299)
            LOGI("render hook retry=%d", i + 1);
        sleep(1);
    }
    LOGI("hook worker finished render=%d register=%d input=%d",
         render_hook_installed.load() ? 1 : 0,
         register_hook_installed.load() ? 1 : 0,
         native_input_hook_installed.load() ? 1 : 0);
    if (!render_hook_installed.load())
        LOGE("render hook timed out; overlay cannot be drawn");
    if (!native_input_hook_installed.load())
        LOGE("Unity nativeInjectEvent was not intercepted; touch may not work");
    return nullptr;
}
} // namespace

extern "C" __attribute__((visibility("default")))
void carx_imgui_set_env(JNIEnv* env) {
    if (!env || register_hook_installed.load(std::memory_order_acquire)) return;
    void* target = reinterpret_cast<void*>(env->functions->RegisterNatives);
    if (!target) {
        LOGE("JNI RegisterNatives pointer unavailable");
        return;
    }
    int rc = DobbyHook(target, reinterpret_cast<void*>(hooked_register_natives),
                       reinterpret_cast<void**>(&real_register_natives));
    if (rc == 0 && real_register_natives) {
        register_hook_installed.store(true, std::memory_order_release);
        LOGI("JNI RegisterNatives hook installed");
    } else {
        LOGE("JNI RegisterNatives hook failed rc=%d", rc);
    }
}

extern "C" __attribute__((visibility("default")))
void carx_imgui_start() {
    static std::atomic<bool> started{false};
    bool expected = false;
    if (!started.compare_exchange_strong(expected, true)) return;

    LOGI("carx_imgui_start entered");
    pthread_t thread{};
    int rc = pthread_create(&thread, nullptr, hook_worker, nullptr);
    if (rc == 0) {
        pthread_detach(thread);
        LOGI("render hook worker thread created");
    } else {
        started.store(false);
        LOGE("render hook worker creation failed rc=%d", rc);
    }
}
