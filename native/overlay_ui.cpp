// CarX Street in-process overlay UI. Requires Dear ImGui + Dobby supplied by CI.
#include <android/log.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <dlfcn.h>
#include <pthread.h>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <unistd.h>
#include "imgui.h"
#include "backends/imgui_impl_opengl3.h"
#include "dobby.h"

#define OLOGI(...) __android_log_print(ANDROID_LOG_INFO, "CarXSnowUI", __VA_ARGS__)
using SwapFn = EGLBoolean (*)(EGLDisplay, EGLSurface);
static SwapFn g_originalSwap = nullptr;
static std::atomic<bool> g_uiReady{false};
static bool g_visible = true;
static float g_snow = 1.0f;
static int g_hour = 12;
static int g_weather = 0;
static bool g_wetGround = false;
static EGLContext g_context = EGL_NO_CONTEXT;
static EGLSurface g_surface = EGL_NO_SURFACE;
static int g_width = 0, g_height = 0;

static void write_value(const char* path, const char* value) {
    FILE* f = std::fopen(path, "w");
    if (!f) return;
    std::fputs(value, f);
    std::fclose(f);
}
static void save_controls() {
    char buf[64];
    std::snprintf(buf, sizeof(buf), "%.2f", g_snow); write_value("/data/local/tmp/carx_snow_multiplier", buf);
    std::snprintf(buf, sizeof(buf), "%d", g_hour * 60); write_value("/data/local/tmp/carx_snow_time_minutes", buf);
    std::snprintf(buf, sizeof(buf), "%d", g_weather); write_value("/data/local/tmp/carx_snow_weather", buf);
    write_value("/data/local/tmp/carx_wet_ground", g_wetGround ? "1" : "0");
}
static void init_imgui(EGLDisplay display, EGLSurface surface) {
    if (g_uiReady.load()) return;
    EGLint w=0,h=0; eglQuerySurface(display, surface, EGL_WIDTH, &w); eglQuerySurface(display, surface, EGL_HEIGHT, &h);
    if (w <= 0 || h <= 0) return;
    g_width=w; g_height=h; g_context=eglGetCurrentContext(); g_surface=surface;
    IMGUI_CHECKVERSION(); ImGui::CreateContext();
    ImGuiIO& io=ImGui::GetIO(); io.IniFilename=nullptr; io.DisplaySize=ImVec2((float)w,(float)h);
    ImGui::StyleColorsDark(); ImGuiStyle& st=ImGui::GetStyle(); st.WindowRounding=10.f; st.FrameRounding=6.f; st.Alpha=0.94f;
    if (!ImGui_ImplOpenGL3_Init("#version 300 es")) { OLOGI("ImGui OpenGL init failed"); return; }
    // Android input backend needs the native app's window; touch events are not intercepted here.
    g_uiReady.store(true); OLOGI("overlay renderer initialized %dx%d", w, h);
}
static void render_overlay() {
    ImGui_ImplOpenGL3_NewFrame(); ImGui::NewFrame();
    if (g_visible) {
        ImGui::SetNextWindowSize(ImVec2(360, 0), ImGuiCond_FirstUseEver);
        if (ImGui::Begin("CarX Street  |  Snow & Weather", &g_visible, ImGuiWindowFlags_AlwaysAutoResize)) {
            ImGui::TextUnformatted("Native in-process panel"); ImGui::Separator();
            ImGui::TextUnformatted("Snow particle size");
            if (ImGui::SliderFloat("Overall multiplier", &g_snow, 0.25f, 10.0f, "%.2f x")) save_controls();
            ImGui::Separator();
            if (ImGui::SliderInt("Time (hour)", &g_hour, 0, 23)) save_controls();
            const char* weatherItems[]={"Default", "Clear", "Cloudy", "Rain", "Snow", "Fog"};
            if (ImGui::Combo("Weather", &g_weather, weatherItems, IM_ARRAYSIZE(weatherItems))) save_controls();
            if (ImGui::Checkbox("Wet ground", &g_wetGround)) save_controls();
            ImGui::TextWrapped("Wet-ground parameter is not yet connected to a verified game field.");
            if (ImGui::Button("Hide panel")) g_visible=false;
        } ImGui::End();
    } else {
        ImGui::SetNextWindowPos(ImVec2(20, 80), ImGuiCond_Always);
        ImGui::SetNextWindowSize(ImVec2(110, 0), ImGuiCond_Always);
        if (ImGui::Begin("##show", nullptr, ImGuiWindowFlags_NoTitleBar|ImGuiWindowFlags_AlwaysAutoResize)) {
            if (ImGui::Button("OPEN CARX UI", ImVec2(95, 36))) g_visible=true;
        } ImGui::End();
    }
    ImGui::Render(); ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
}
static EGLBoolean hooked_swap(EGLDisplay d, EGLSurface s) {
    if (g_originalSwap && eglGetCurrentContext()!=EGL_NO_CONTEXT) {
        if (!g_uiReady.load()) init_imgui(d,s);
        if (g_uiReady.load()) render_overlay();
    }
    return g_originalSwap ? g_originalSwap(d,s) : EGL_FALSE;
}
extern "C" __attribute__((visibility("default"))) void carx_overlay_install() {
    void* sym=dlsym(RTLD_DEFAULT, "eglSwapBuffers");
    if (!sym) { void* egl=dlopen("libEGL.so", RTLD_NOW); if (egl) sym=dlsym(egl,"eglSwapBuffers"); }
    if (!sym) { OLOGI("eglSwapBuffers not found"); return; }
    int rc=DobbyHook(sym, (void*)hooked_swap, (void**)&g_originalSwap);
    OLOGI("DobbyHook eglSwapBuffers rc=%d", rc);
}
