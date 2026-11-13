// PigHax B-mode ImGui overlay: render/input hook coordination repair.
// Input events are captured on the Android input thread and consumed on the
// render thread; ImGui itself is never accessed concurrently.
#include <android/log.h>
#include <android/input.h>
#include <dlfcn.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <pthread.h>
#include <unistd.h>
#include <atomic>
#include <cstdarg>
#include <sys/stat.h>
#include <cstdio>
#include <cstring>
#include "imgui.h"
#include "backends/imgui_impl_opengl3.h"
#include "dobby.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "PigHaxImGui", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "PigHaxImGui", __VA_ARGS__)

namespace {
using SwapFn = EGLBoolean (*)(EGLDisplay, EGLSurface);
using GetEventFn = int (*)(AInputQueue*, AInputEvent**);

SwapFn real_swap = nullptr;
GetEventFn real_get_event = nullptr;
void* egl_handle = nullptr;
void* android_handle = nullptr;

std::atomic<bool> render_hook_installed{false};
std::atomic<bool> input_hook_installed{false};
std::atomic<bool> imgui_ready{false};
std::atomic<int> surface_w{0};
std::atomic<int> surface_h{0};

std::atomic<float> pending_x{0.0f};
std::atomic<float> pending_y{0.0f};
std::atomic<bool> pending_position{false};
std::atomic<bool> pending_down{false};
std::atomic<bool> pending_button_change{false};
std::atomic<unsigned int> input_event_count{0};

bool menu_open = true;
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

void consume_pending_touch(ImGuiIO& io) {
    if (pending_position.exchange(false, std::memory_order_acq_rel)) {
        const int w = surface_w.load(std::memory_order_relaxed);
        const int h = surface_h.load(std::memory_order_relaxed);
        float x = pending_x.load(std::memory_order_relaxed);
        float y = pending_y.load(std::memory_order_relaxed);

        // Android MotionEvent coordinates are normally view-local pixels.
        // Clamp only when a valid EGL surface size is known.
        if (w > 0 && h > 0) {
            if (x < 0.0f) x = 0.0f;
            if (y < 0.0f) y = 0.0f;
            if (x > (float)w) x = (float)w;
            if (y > (float)h) y = (float)h;
        }
        io.AddMousePosEvent(x, y);
    }
    if (pending_button_change.exchange(false, std::memory_order_acq_rel)) {
        io.AddMouseButtonEvent(0, pending_down.load(std::memory_order_relaxed));
    }
}

void draw_menu() {
    ImGuiIO& io = ImGui::GetIO();
    const int w = surface_w.load(std::memory_order_relaxed);
    const int h = surface_h.load(std::memory_order_relaxed);
    io.DisplaySize = ImVec2((float)w, (float)h);

    // All ImGui calls, including input injection, happen on this render thread.
    consume_pending_touch(io);

    ImGui_ImplOpenGL3_NewFrame();
    ImGui::NewFrame();

    if (menu_open) {
        ImGui::SetNextWindowPos(ImVec2(24, 48), ImGuiCond_Once);
        ImGui::SetNextWindowSize(ImVec2(430, 0), ImGuiCond_Once);
        ImGui::SetNextWindowBgAlpha(0.94f);
        ImGui::PushStyleVar(ImGuiStyleVar_WindowRounding, 15.0f);
        ImGui::PushStyleVar(ImGuiStyleVar_FrameRounding, 8.0f);
        ImGui::PushStyleColor(ImGuiCol_WindowBg, ImVec4(0.075f,0.08f,0.09f,0.96f));
        ImGui::PushStyleColor(ImGuiCol_Header, ImVec4(0.12f,0.27f,0.34f,1.0f));
        // Keep the title bar draggable. Do not add ImGuiWindowFlags_NoMove.
        ImGui::Begin("PigHax  |  CarX Street", &menu_open,
                     ImGuiWindowFlags_AlwaysAutoResize | ImGuiWindowFlags_NoCollapse |
                     ImGuiWindowFlags_NoSavedSettings);
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
            int hour = game_minutes / 60, minute = game_minutes % 60;
            if (ImGui::SliderInt("Hour", &hour, 0, 23)) {
                game_minutes = hour * 60 + minute;
                write_value("/data/local/tmp/carx_snow_time_minutes", "%d", game_minutes);
            }
            if (ImGui::SliderInt("Minute", &minute, 0, 59)) {
                game_minutes = hour * 60 + minute;
                write_value("/data/local/tmp/carx_snow_time_minutes", "%d", game_minutes);
            }
            ImGui::Text("Game time: %02d:%02d", game_minutes/60, game_minutes%60);
        }
        ImGui::Separator();
        ImGui::TextDisabled("B-mode render diagnostic");
        ImGui::End();
        ImGui::PopStyleColor(2);
        ImGui::PopStyleVar(2);
    }
    ImGui::Render();
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
}

EGLBoolean hooked_swap(EGLDisplay display, EGLSurface surface) {
    if (!real_swap) return EGL_FALSE;

    if (eglGetCurrentContext() != EGL_NO_CONTEXT) {
        EGLint w = 0, h = 0;
        if (eglQuerySurface(display, surface, EGL_WIDTH, &w) == EGL_TRUE &&
            eglQuerySurface(display, surface, EGL_HEIGHT, &h) == EGL_TRUE &&
            w > 0 && h > 0) {
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
                LOGI("ImGui GLES3 initialized %dx%d",
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

int hooked_get_event(AInputQueue* queue, AInputEvent** event) {
    if (!real_get_event) return -1;
    const int result = real_get_event(queue, event);
    if (result < 0 || !event || !*event) return result;

    AInputEvent* e = *event;
    if (AInputEvent_getType(e) == AINPUT_EVENT_TYPE_MOTION) {
        const int action = AMotionEvent_getAction(e) & AMOTION_EVENT_ACTION_MASK;
        const float x = AMotionEvent_getX(e, 0);
        const float y = AMotionEvent_getY(e, 0);

        pending_x.store(x, std::memory_order_relaxed);
        pending_y.store(y, std::memory_order_relaxed);
        pending_position.store(true, std::memory_order_release);

        if (action == AMOTION_EVENT_ACTION_DOWN ||
            action == AMOTION_EVENT_ACTION_POINTER_DOWN) {
            pending_down.store(true, std::memory_order_relaxed);
            pending_button_change.store(true, std::memory_order_release);
        } else if (action == AMOTION_EVENT_ACTION_UP ||
                   action == AMOTION_EVENT_ACTION_POINTER_UP ||
                   action == AMOTION_EVENT_ACTION_CANCEL) {
            pending_down.store(false, std::memory_order_relaxed);
            pending_button_change.store(true, std::memory_order_release);
        }

        const unsigned int n = ++input_event_count;
        if (n == 1 || n == 50 || n == 200)
            LOGI("Touch observer received motion event count=%u x=%.1f y=%.1f action=%d",
                 n, x, y, action);
    }
    // Observe only: never consume, finish, or null Unity's input event.
    return result;
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
        auto get_proc = reinterpret_cast<GetProcFn>(dlsym(egl_handle, "eglGetProcAddress"));
        if (get_proc) swap = reinterpret_cast<void*>(get_proc("eglSwapBuffers"));
    }
    if (!swap) {
        LOGE("eglSwapBuffers symbol unavailable");
        return false;
    }

    const int rc = DobbyHook(swap, (void*)hooked_swap, (void**)&real_swap);
    if (rc == 0 && real_swap) {
        render_hook_installed.store(true);
        LOGI("eglSwapBuffers hook installed");
        return true;
    }
    LOGE("eglSwapBuffers hook failed rc=%d", rc);
    return false;
}

bool try_install_input_hook() {
    if (input_hook_installed.load()) return true;

    if (!android_handle) android_handle = dlopen("libandroid.so", RTLD_NOW | RTLD_NOLOAD);
    if (!android_handle) android_handle = dlopen("libandroid.so", RTLD_NOW);
    if (!android_handle) return false;

    void* fn = dlsym(android_handle, "AInputQueue_getEvent");
    if (!fn) {
        LOGE("AInputQueue_getEvent symbol unavailable");
        return false;
    }
    const int rc = DobbyHook(fn, (void*)hooked_get_event, (void**)&real_get_event);
    if (rc == 0 && real_get_event) {
        input_hook_installed.store(true);
        LOGI("AInputQueue_getEvent observer installed");
        return true;
    }
    LOGE("Input observer hook failed rc=%d", rc);
    return false;
}

void* hook_worker(void*) {
    LOGI("ImGui hook worker started; waiting for game graphics/input libraries");
    // Try both independently on every pass. A slow EGL hook must not delay input.
    for (int i = 0; i < 120; ++i) {
        const bool render_ok = try_install_render_hook();
        const bool input_ok = try_install_input_hook();
        if (render_ok && input_ok) break;
        sleep(1);
    }
    if (!render_hook_installed.load())
        LOGE("Render hook timed out; no EGL swap callback was hooked");
    if (!input_hook_installed.load())
        LOGE("Input observer unavailable; ImGui can render but touch will not work");
    return nullptr;
}
}

extern "C" __attribute__((visibility("default"))) void carx_imgui_start() {
    static std::atomic<bool> started{false};
    bool expected = false;
    if (!started.compare_exchange_strong(expected, true)) {
        LOGI("ImGui worker already started");
        return;
    }
    pthread_t t{};
    const int rc = pthread_create(&t, nullptr, hook_worker, nullptr);
    if (rc == 0) {
        pthread_detach(t);
        LOGI("ImGui worker thread created");
    } else {
        started.store(false);
        LOGE("ImGui hook worker creation failed rc=%d", rc);
    }
}
