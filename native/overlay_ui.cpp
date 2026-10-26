// In-process ImGui overlay for CarX Street (ARM64 / Android GLES3).
// Render hook only: game parameter writes remain in snow_runtime_control.cpp.
#include <android/log.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <dlfcn.h>
#include <pthread.h>
#include <atomic>
#include <cstdint>
#include "imgui.h"
#include "backends/imgui_impl_opengl3.h"

#define OLOGI(...) __android_log_print(ANDROID_LOG_INFO, "CarXSnowUI", __VA_ARGS__)
using SwapFn = EGLBoolean (*)(EGLDisplay, EGLSurface);
static SwapFn realSwap = nullptr;
static std::atomic<bool> initialized{false};
static std::atomic<bool> visible{true};
static float snowScale = 1.0f;
static int timePreset = 0;
static int weatherPreset = 0;
static bool wetGround = false;
static EGLContext lastContext = EGL_NO_CONTEXT;

static void draw_panel() {
    if (!visible.load()) return;
    ImGuiIO& io = ImGui::GetIO();
    ImGui::SetNextWindowSize(ImVec2(370, 0), ImGuiCond_FirstUseEver);
    ImGui::SetNextWindowPos(ImVec2(io.DisplaySize.x * 0.06f, io.DisplaySize.y * 0.18f), ImGuiCond_FirstUseEver);
    if (ImGui::Begin("CarX Street | Snow & Weather", &visible, ImGuiWindowFlags_AlwaysAutoResize)) {
        ImGui::TextUnformatted("In-game native control panel");
        ImGui::Separator();
        ImGui::Text("Snowflake size: %.2fx", snowScale);
        ImGui::SliderFloat("Overall size", &snowScale, 0.25f, 10.0f, "%.2fx");
        ImGui::TextDisabled("Parameter bridge must be connected to game runtime.");
        ImGui::Separator();
        const char* times[] = {"Follow game", "Morning", "Noon", "Evening", "Night"};
        ImGui::Combo("Time", &timePreset, times, IM_ARRAYSIZE(times));
        const char* weather[] = {"Follow game", "Clear", "Cloudy", "Rain", "Snow"};
        ImGui::Combo("Weather", &weatherPreset, weather, IM_ARRAYSIZE(weather));
        ImGui::Checkbox("Wet ground", &wetGround);
        ImGui::TextDisabled("Wet-ground memory binding is not yet verified.");
        ImGui::Separator();
        if (ImGui::Button("Hide panel")) visible.store(false);
    }
    ImGui::End();
}

static void render_overlay(EGLDisplay display, EGLSurface surface) {
    EGLContext ctx = eglGetCurrentContext();
    if (ctx == EGL_NO_CONTEXT || surface == EGL_NO_SURFACE) return;
    if (!initialized.load() || ctx != lastContext) {
        IMGUI_CHECKVERSION();
        ImGui::CreateContext();
        ImGui::StyleColorsDark();
        ImGui::GetStyle().ScaleAllSizes(1.25f);
        if (!ImGui_ImplOpenGL3_Init("#version 300 es")) {
            OLOGI("ImGui GLES3 backend initialization failed");
            return;
        }
        lastContext = ctx;
        initialized.store(true);
        OLOGI("ImGui overlay initialized for current EGL context");
    }
    EGLint width = 0, height = 0;
    eglQuerySurface(display, surface, EGL_WIDTH, &width);
    eglQuerySurface(display, surface, EGL_HEIGHT, &height);
    if (width <= 0 || height <= 0) return;
    ImGuiIO& io = ImGui::GetIO();
    io.DisplaySize = ImVec2((float)width, (float)height);
    io.DeltaTime = 1.0f / 60.0f;
    ImGui_ImplOpenGL3_NewFrame();
    ImGui::NewFrame();
    draw_panel();
    ImGui::Render();
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
}

extern "C" __attribute__((visibility("default"))) EGLBoolean eglSwapBuffers(EGLDisplay display, EGLSurface surface) {
    if (!realSwap) realSwap = reinterpret_cast<SwapFn>(dlsym(RTLD_NEXT, "eglSwapBuffers"));
    if (realSwap) render_overlay(display, surface);
    return realSwap ? realSwap(display, surface) : EGL_FALSE;
}

// The UI is deliberately exposed as an explicit entry point for loader integration.
extern "C" __attribute__((visibility("default"))) void carx_overlay_set_visible(bool value) {
    visible.store(value);
}
