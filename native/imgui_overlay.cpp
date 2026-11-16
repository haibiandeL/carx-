#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <pthread.h>
#include <unistd.h>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <cstdarg>
#include <deque>
#include <mutex>
#include <vector>
#include <sys/stat.h>
#include "imgui.h"
#include "backends/imgui_impl_opengl3.h"
#include "dobby.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "PigHax", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "PigHax", __VA_ARGS__)

namespace {
using SwapFn = EGLBoolean (*)(EGLDisplay, EGLSurface);
using RegisterFn = jint (JNICALL *)(JNIEnv *, jclass, const JNINativeMethod *, jint);
using InjectFn = jboolean (JNICALL *)(JNIEnv *, jclass, jobject);

SwapFn real_swap = nullptr;
RegisterFn real_register = nullptr;
InjectFn real_inject = nullptr;
void *egl_lib = nullptr;

std::atomic<bool> render_hooked{false}, register_hooked{false}, input_hooked{false};
std::atomic<bool> imgui_ready{false}, bounds_ready{false}, touch_owned{false};
std::atomic<int> screen_w{0}, screen_h{0};
std::atomic<float> x0{0}, y0{0}, x1{0}, y1{0};

struct Touch { float x, y; int state; };
std::mutex touch_mutex, class_mutex;
std::deque<Touch> touch_queue;
jclass motion_class = nullptr;
jmethodID action_id = nullptr, x_id = nullptr, y_id = nullptr;

bool snow_enabled = true;
int weather = 0, time_minutes = 720;
float size_multiplier = 1.0f;

void write_setting(const char *path, const char *format, ...) {
    FILE *f = std::fopen(path, "w");
    if (!f) return;
    va_list ap;
    va_start(ap, format);
    std::vfprintf(f, format, ap);
    va_end(ap);
    std::fputc('\n', f);
    std::fclose(f);
    chmod(path, 0644);
}

void enqueue_touch(float x, float y, int state) {
    std::lock_guard<std::mutex> lock(touch_mutex);
    if (touch_queue.size() >= 64) touch_queue.pop_front();
    touch_queue.push_back({x, y, state});
}

bool hit_menu(float x, float y) {
    return bounds_ready && x >= x0 && x <= x1 && y >= y0 && y <= y1;
}

bool resolve_motion_methods(JNIEnv *env) {
    if (motion_class && action_id && x_id && y_id) return true;
    std::lock_guard<std::mutex> lock(class_mutex);
    if (motion_class && action_id && x_id && y_id) return true;

    jclass local = env->FindClass("android/view/MotionEvent");
    if (!local) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    motion_class = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (!motion_class) return false;

    action_id = env->GetMethodID(motion_class, "getAction", "()I");
    x_id = env->GetMethodID(motion_class, "getX", "()F");
    y_id = env->GetMethodID(motion_class, "getY", "()F");
    if (!action_id || !x_id || !y_id || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    return true;
}

jboolean JNICALL input_hook(JNIEnv *env, jclass cls, jobject event) {
    if (event && resolve_motion_methods(env) &&
        env->IsInstanceOf(event, motion_class)) {
        const int action = env->CallIntMethod(event, action_id) & 0xff;
        const float x = env->CallFloatMethod(event, x_id);
        const float y = env->CallFloatMethod(event, y_id);

        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        } else {
            bool own = touch_owned.load();
            if (action == 0) {
                own = hit_menu(x, y);
                touch_owned = own;
                if (own) enqueue_touch(x, y, 1);
            } else if (own) {
                if (action == 2) enqueue_touch(x, y, -1);
                if (action == 1 || action == 3) {
                    enqueue_touch(x, y, 0);
                    touch_owned = false;
                }
            }
            if (own) return JNI_TRUE;
        }
    }
    return real_inject ? real_inject(env, cls, event) : JNI_FALSE;
}

bool is_unity_player(JNIEnv *env, jclass clazz) {
    jclass class_class = env->GetObjectClass(clazz);
    if (!class_class) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    jmethodID get_name = env->GetMethodID(class_class, "getName", "()Ljava/lang/String;");
    env->DeleteLocalRef(class_class);
    if (!get_name || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }

    jstring name = static_cast<jstring>(env->CallObjectMethod(clazz, get_name));
    if (!name || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    const char *chars = env->GetStringUTFChars(name, nullptr);
    const bool match = chars && std::strcmp(chars, "com.unity3d.player.UnityPlayer") == 0;
    if (chars) env->ReleaseStringUTFChars(name, chars);
    env->DeleteLocalRef(name);
    return match;
}

jint JNICALL register_hook(JNIEnv *env, jclass clazz,
                           const JNINativeMethod *methods, jint count) {
    if (!real_register) return JNI_ERR;
    if (!methods || count <= 0 || !is_unity_player(env, clazz))
        return real_register(env, clazz, methods, count);

    std::vector<JNINativeMethod> copy(methods, methods + count);
    int target = -1;
    for (int i = 0; i < count; ++i) {
        if (methods[i].name && methods[i].signature &&
            std::strcmp(methods[i].name, "nativeInjectEvent") == 0 &&
            std::strcmp(methods[i].signature, "(Landroid/view/InputEvent;)Z") == 0) {
            target = i;
            break;
        }
    }
    if (target < 0) return real_register(env, clazz, methods, count);

    real_inject = reinterpret_cast<InjectFn>(copy[target].fnPtr);
    copy[target].fnPtr = reinterpret_cast<void *>(input_hook);
    const jint result = real_register(env, clazz, copy.data(), count);
    if (result == JNI_OK) {
        input_hooked = true;
        LOGI("UnityPlayer input hook registered");
    } else {
        LOGE("UnityPlayer RegisterNatives failed: %d", result);
    }
    return result;
}

void feed_imgui_touch(ImGuiIO &io) {
    std::deque<Touch> local;
    {
        std::lock_guard<std::mutex> lock(touch_mutex);
        local.swap(touch_queue);
    }
    for (const auto &t : local) {
        io.AddMousePosEvent(t.x, t.y);
        if (t.state >= 0) io.AddMouseButtonEvent(0, t.state == 1);
    }
}

void draw_menu() {
    ImGuiIO &io = ImGui::GetIO();
    io.DisplaySize = ImVec2(static_cast<float>(screen_w.load()),
                            static_cast<float>(screen_h.load()));
    feed_imgui_touch(io);
    ImGui_ImplOpenGL3_NewFrame();
    ImGui::NewFrame();

    ImGui::SetNextWindowPos(ImVec2(screen_w.load() * 0.04f,
                                   screen_h.load() * 0.06f), ImGuiCond_FirstUseEver);
    ImGui::SetNextWindowSize(ImVec2(screen_w.load() * 0.78f, 0),
                             ImGuiCond_FirstUseEver);
    ImGui::Begin("PigHax | CarX Street", nullptr,
                 ImGuiWindowFlags_AlwaysAutoResize | ImGuiWindowFlags_NoCollapse);

    const ImVec2 pos = ImGui::GetWindowPos();
    const ImVec2 sz = ImGui::GetWindowSize();
    x0 = pos.x; y0 = pos.y; x1 = pos.x + sz.x; y1 = pos.y + sz.y;
    bounds_ready = true;

    ImGui::TextDisabled("B MODE | Dear ImGui");
    ImGui::Separator();

    if (ImGui::CollapsingHeader("Weather", ImGuiTreeNodeFlags_DefaultOpen)) {
        const char *items[] = {"Generic", "Halloween", "Winter", "Cloudy", "Rainy"};
        if (ImGui::Combo("Weather preset", &weather, items, IM_ARRAYSIZE(items)))
            write_setting("/data/local/tmp/carx_snow_weather", "%d", weather);
    }
    if (ImGui::CollapsingHeader("Snow", ImGuiTreeNodeFlags_DefaultOpen)) {
        if (ImGui::Checkbox("Snow weather / particles", &snow_enabled))
            write_setting("/data/local/tmp/carx_snow_enabled", "%d", snow_enabled ? 1 : 0);
        if (ImGui::SliderFloat("Rain + snowflake size", &size_multiplier,
                               0.25f, 3.0f, "%.2fx"))
            write_setting("/data/local/tmp/carx_snow_multiplier", "%.3f", size_multiplier);
    }
    if (ImGui::CollapsingHeader("Time")) {
        int hour = time_minutes / 60;
        int minute = time_minutes % 60;
        if (ImGui::SliderInt("Hour", &hour, 0, 23)) {
            time_minutes = hour * 60 + minute;
            write_setting("/data/local/tmp/carx_snow_time_minutes", "%d", time_minutes);
        }
        if (ImGui::SliderInt("Minute", &minute, 0, 59)) {
            time_minutes = hour * 60 + minute;
            write_setting("/data/local/tmp/carx_snow_time_minutes", "%d", time_minutes);
        }
    }

    ImGui::End();
    ImGui::Render();
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
}

EGLBoolean JNICALL swap_hook(EGLDisplay display, EGLSurface surface) {
    if (!real_swap) return EGL_FALSE;
    if (eglGetCurrentContext() != EGL_NO_CONTEXT) {
        EGLint width = 0, height = 0;
        if (eglQuerySurface(display, surface, EGL_WIDTH, &width) &&
            eglQuerySurface(display, surface, EGL_HEIGHT, &height) &&
            width > 0 && height > 0) {
            screen_w = width;
            screen_h = height;
        }

        if (!imgui_ready && screen_w > 0 && screen_h > 0) {
            IMGUI_CHECKVERSION();
            ImGui::CreateContext();
            ImGui::StyleColorsDark();
            ImGui::GetIO().IniFilename = nullptr;
            if (ImGui_ImplOpenGL3_Init("#version 300 es")) {
                imgui_ready = true;
                LOGI("Dear ImGui GLES3 initialized");
            } else {
                LOGE("Dear ImGui GLES3 initialization failed");
                ImGui::DestroyContext();
            }
        }
        if (imgui_ready) draw_menu();
    }
    return real_swap(display, surface);
}

bool install_render_hook() {
    if (render_hooked) return true;
    if (!egl_lib) egl_lib = dlopen("libEGL.so", RTLD_NOW | RTLD_NOLOAD);
    if (!egl_lib) egl_lib = dlopen("libEGL.so", RTLD_NOW);
    if (!egl_lib) return false;

    void *target = dlsym(egl_lib, "eglSwapBuffers");
    if (!target) target = dlsym(RTLD_DEFAULT, "eglSwapBuffers");
    if (!target) {
        auto get_proc = reinterpret_cast<__eglMustCastToProperFunctionPointerType (*)(const char *)>(
            dlsym(egl_lib, "eglGetProcAddress"));
        if (get_proc) target = reinterpret_cast<void *>(get_proc("eglSwapBuffers"));
    }
    if (!target) return false;

    const int rc = DobbyHook(target, reinterpret_cast<void *>(swap_hook),
                             reinterpret_cast<void **>(&real_swap));
    if (rc == 0 && real_swap) {
        render_hooked = true;
        LOGI("eglSwapBuffers hook installed");
        return true;
    }
    LOGE("eglSwapBuffers hook failed: %d", rc);
    return false;
}

void *render_worker(void *) {
    LOGI("render worker started");
    for (int i = 0; i < 600 && !render_hooked; ++i) {
        install_render_hook();
        if (!render_hooked) sleep(1);
    }
    LOGI("hook state render=%d register=%d input=%d",
         render_hooked.load(), register_hooked.load(), input_hooked.load());
    return nullptr;
}
} // namespace

extern "C" __attribute__((visibility("default")))
void carx_imgui_set_env(JNIEnv *env) {
    if (!env || register_hooked) return;
    void *target = reinterpret_cast<void *>(env->functions->RegisterNatives);
    const int rc = DobbyHook(target, reinterpret_cast<void *>(register_hook),
                             reinterpret_cast<void **>(&real_register));
    if (rc == 0 && real_register) {
        register_hooked = true;
        LOGI("RegisterNatives hook installed");
    } else {
        LOGE("RegisterNatives hook failed: %d", rc);
    }
}

extern "C" __attribute__((visibility("default")))
void carx_imgui_start() {
    static std::atomic<bool> started{false};
    bool expected = false;
    if (!started.compare_exchange_strong(expected, true)) return;
    pthread_t thread{};
    const int rc = pthread_create(&thread, nullptr, render_worker, nullptr);
    if (rc == 0) pthread_detach(thread);
    else {
        started = false;
        LOGE("render worker creation failed: %d", rc);
    }
}

