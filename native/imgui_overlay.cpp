// PigHax B-mode diagnostic overlay for CarX Street 1.24
// This build adds startup/render/input diagnostics and responsive sizing.
// Touch remains observational: AInputQueue_getEvent may not be Unity's input path.
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

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "PigHaxDiag", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "PigHaxDiag", __VA_ARGS__)

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
std::atomic<int> surface_w{0}, surface_h{0};
std::atomic<unsigned int> swap_count{0}, input_count{0};
std::atomic<float> pending_x{0.0f}, pending_y{0.0f};
std::atomic<bool> pending_position{false}, pending_down{false}, pending_button_change{false};

bool menu_open = true;
bool snow_enabled = true;
int weather_index = 0;
int game_minutes = 720;
float particle_scale = 1.0f;
float ui_scale = 1.35f;

void write_value(const char* path, const char* fmt, ...) {
    FILE* f = std::fopen(path, "w");
    if (!f) return;
    va_list ap; va_start(ap, fmt); std::vfprintf(f, fmt, ap); va_end(ap);
    std::fputc('\n', f); std::fclose(f); chmod(path, 0644);
}

void consume_pending_touch(ImGuiIO& io) {
    if (pending_position.exchange(false, std::memory_order_acq_rel)) {
        const float x = pending_x.load(std::memory_order_relaxed);
        const float y = pending_y.load(std::memory_order_relaxed);
        io.AddMousePosEvent(x, y);
    }
    if (pending_button_change.exchange(false, std::memory_order_acq_rel))
        io.AddMouseButtonEvent(0, pending_down.load(std::memory_order_relaxed));
}

void draw_menu() {
    ImGuiIO& io = ImGui::GetIO();
    const int w = surface_w.load(std::memory_order_relaxed);
    const int h = surface_h.load(std::memory_order_relaxed);
    io.DisplaySize = ImVec2((float)w, (float)h);
    consume_pending_touch(io);

    ImGui_ImplOpenGL3_NewFrame();
    ImGui::NewFrame();

    if (menu_open && w > 0 && h > 0) {
        const float panel_w = (float)w * 0.78f;
        const float max_w = 900.0f * ui_scale;
        const float final_w = panel_w < max_w ? panel_w : max_w;
        ImGui::SetNextWindowPos(ImVec2((float)w * 0.035f, (float)h * 0.06f), ImGuiCond_Once);
        ImGui::SetNextWindowSize(ImVec2(final_w, 0.0f), ImGuiCond_Once);
        ImGui::SetNextWindowBgAlpha(0.96f);
        ImGui::PushStyleVar(ImGuiStyleVar_WindowRounding, 15.0f * ui_scale);
        ImGui::PushStyleVar(ImGuiStyleVar_FrameRounding, 8.0f * ui_scale);
        ImGui::PushStyleColor(ImGuiCol_WindowBg, ImVec4(0.075f,0.08f,0.09f,0.96f));
        ImGui::PushStyleColor(ImGuiCol_Header, ImVec4(0.12f,0.27f,0.34f,1.0f));
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
        ImGui::TextDisabled("Diagnostic build | UI scale %.2f", ui_scale);
        ImGui::End();
        ImGui::PopStyleColor(2);
        ImGui::PopStyleVar(2);
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
            const int old_w = surface_w.exchange(w);
            const int old_h = surface_h.exchange(h);
            if (old_w != w || old_h != h) LOGI("EGL surface size %dx%d", w, h);
        }

        if (!imgui_ready.load(std::memory_order_acquire) &&
            surface_w.load() > 0 && surface_h.load() > 0) {
            IMGUI_CHECKVERSION();
            ImGui::CreateContext();
            ImGui::StyleColorsDark();
            ImGuiIO& io = ImGui::GetIO();
            io.IniFilename = nullptr;
            io.DisplaySize = ImVec2((float)surface_w.load(), (float)surface_h.load());
            ImGui::GetStyle().ScaleAllSizes(ui_scale);
            io.FontGlobalScale = ui_scale;
            if (ImGui_ImplOpenGL3_Init("#version 300 es")) {
                imgui_ready.store(true, std::memory_order_release);
                LOGI("ImGui GLES3 initialized; surface=%dx%d scale=%.2f",
                     surface_w.load(), surface_h.load(), ui_scale);
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
        const float x = AMotionEvent_getX(e, 0), y = AMotionEvent_getY(e, 0);
        pending_x.store(x); pending_y.store(y);
        pending_position.store(true, std::memory_order_release);
        if (action == AMOTION_EVENT_ACTION_DOWN || action == AMOTION_EVENT_ACTION_POINTER_DOWN) {
            pending_down.store(true); pending_button_change.store(true, std::memory_order_release);
        } else if (action == AMOTION_EVENT_ACTION_UP || action == AMOTION_EVENT_ACTION_POINTER_UP ||
                   action == AMOTION_EVENT_ACTION_CANCEL) {
            pending_down.store(false); pending_button_change.store(true, std::memory_order_release);
        }
        const unsigned int c = ++input_count;
        if (c == 1 || c == 30 || c == 100)
            LOGI("Android input queue saw motion #%u x=%.1f y=%.1f action=%d", c, x, y, action);
    }
    return result; // observe only; do not consume Unity's event
}

bool try_install_render_hook() {
    if (render_hook_installed.load()) return true;
    if (!egl_handle) egl_handle = dlopen("libEGL.so", RTLD_NOW | RTLD_NOLOAD);
    if (!egl_handle) egl_handle = dlopen("libEGL.so", RTLD_NOW);
    if (!egl_handle) { LOGI("libEGL handle not ready"); return false; }
    void* swap = dlsym(egl_handle, "eglSwapBuffers");
    if (!swap) swap = dlsym(RTLD_DEFAULT, "eglSwapBuffers");
    if (!swap) {
        using GetProcFn = __eglMustCastToProperFunctionPointerType (*)(const char*);
        auto gp = reinterpret_cast<GetProcFn>(dlsym(egl_handle, "eglGetProcAddress"));
        if (gp) swap = reinterpret_cast<void*>(gp("eglSwapBuffers"));
    }
    if (!swap) { LOGE("eglSwapBuffers symbol unavailable"); return false; }
    const int rc = DobbyHook(swap, (void*)hooked_swap, (void**)&real_swap);
    if (rc == 0 && real_swap) {
        render_hook_installed.store(true);
        LOGI("eglSwapBuffers hook installed at %p", swap);
        return true;
    }
    LOGE("eglSwapBuffers hook failed rc=%d", rc);
    return false;
}

bool try_install_input_hook() {
    if (input_hook_installed.load()) return true;
    if (!android_handle) android_handle = dlopen("libandroid.so", RTLD_NOW | RTLD_NOLOAD);
    if (!android_handle) android_handle = dlopen("libandroid.so", RTLD_NOW);
    if (!android_handle) { LOGI("libandroid handle not ready"); return false; }
    void* fn = dlsym(android_handle, "AInputQueue_getEvent");
    if (!fn) { LOGE("AInputQueue_getEvent symbol unavailable"); return false; }
    const int rc = DobbyHook(fn, (void*)hooked_get_event, (void**)&real_get_event);
    if (rc == 0 && real_get_event) {
        input_hook_installed.store(true);
        LOGI("AInputQueue_getEvent observer installed at %p", fn);
        return true;
    }
    LOGE("AInputQueue observer hook failed rc=%d", rc);
    return false;
}

void* hook_worker(void*) {
    LOGI("Render/input hook worker entered");
    for (int i = 0; i < 120; ++i) {
        const bool r = try_install_render_hook();
        const bool in = try_install_input_hook();
        if (r && in) break;
        if (i == 0 || i == 9 || i == 29 || i == 59 || i == 119)
            LOGI("hook retry=%d render=%d input=%d", i + 1, r ? 1 : 0, in ? 1 : 0);
        sleep(1);
    }
    LOGI("hook worker finished render=%d input=%d",
         render_hook_installed.load() ? 1 : 0, input_hook_installed.load() ? 1 : 0);
    if (!render_hook_installed.load()) LOGE("render hook timed out; no ImGui frame can be drawn");
    if (!input_hook_installed.load()) LOGE("input observer unavailable; touch path not observed");
    return nullptr;
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
        LOGI("hook worker thread created");
    } else {
        started.store(false);
        LOGE("hook worker thread creation failed rc=%d", rc);
    }
}
