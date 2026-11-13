// CarX Street B-mode ImGui overlay: EGL/GLES3 render-hook repair.
// Input observation is optional and never gates render-hook installation.
#include <android/log.h>
#include <android/input.h>
#include <dlfcn.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <pthread.h>
#include <unistd.h>
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
bool imgui_ready = false;
bool render_hook_installed = false;
bool input_hook_installed = false;
bool menu_open = true;
bool snow_enabled = true;
int weather_index = 0;
int game_minutes = 720;
float particle_scale = 1.0f;
int surface_w = 0, surface_h = 0;

void write_value(const char* path, const char* fmt, ...) {
    FILE* f = std::fopen(path, "w");
    if (!f) return;
    va_list ap; va_start(ap, fmt); std::vfprintf(f, fmt, ap); va_end(ap);
    std::fputc('\\n', f); std::fclose(f); chmod(path, 0644);
}

void draw_menu() {
    ImGuiIO& io = ImGui::GetIO();
    io.DisplaySize = ImVec2((float)surface_w, (float)surface_h);
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
        ImGui::Begin("PigHax  |  CarX Street", &menu_open,
                     ImGuiWindowFlags_AlwaysAutoResize | ImGuiWindowFlags_NoCollapse);
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
        EGLint w=0,h=0;
        if (eglQuerySurface(display,surface,EGL_WIDTH,&w)==EGL_TRUE &&
            eglQuerySurface(display,surface,EGL_HEIGHT,&h)==EGL_TRUE && w>0 && h>0) {
            surface_w=w; surface_h=h;
        }
        if (!imgui_ready && surface_w>0 && surface_h>0) {
            IMGUI_CHECKVERSION();
            ImGui::CreateContext();
            ImGui::StyleColorsDark();
            ImGuiIO& io=ImGui::GetIO();
            io.IniFilename=nullptr;
            io.DisplaySize=ImVec2((float)surface_w,(float)surface_h);
            if (ImGui_ImplOpenGL3_Init("#version 300 es")) {
                imgui_ready=true;
                LOGI("ImGui GLES3 initialized %dx%d",surface_w,surface_h);
            } else {
                LOGE("ImGui GLES3 initialization failed");
                ImGui::DestroyContext();
            }
        }
        if (imgui_ready) draw_menu();
    }
    return real_swap(display,surface);
}

int hooked_get_event(AInputQueue* queue, AInputEvent** event) {
    if (!real_get_event) return -1;
    int result=real_get_event(queue,event);
    if (result<0 || !event || !*event || !imgui_ready) return result;
    AInputEvent* e=*event;
    if (AInputEvent_getType(e)==AINPUT_EVENT_TYPE_MOTION) {
        int action=AMotionEvent_getAction(e)&AMOTION_EVENT_ACTION_MASK;
        ImGuiIO& io=ImGui::GetIO();
        io.AddMousePosEvent(AMotionEvent_getX(e,0),AMotionEvent_getY(e,0));
        if (action==AMOTION_EVENT_ACTION_DOWN) io.AddMouseButtonEvent(0,true);
        if (action==AMOTION_EVENT_ACTION_UP || action==AMOTION_EVENT_ACTION_CANCEL)
            io.AddMouseButtonEvent(0,false);
    }
    // Do not consume or finish Unity's input event from this observer hook.
    return result;
}

void* hook_worker(void*) {
    for (int i=0;i<120 && !render_hook_installed;i++) {
        void* egl=dlopen("libEGL.so",RTLD_NOW);
        if (egl) {
            void* swap=dlsym(egl,"eglSwapBuffers");
            if (swap) {
                int rc=DobbyHook(swap,(void*)hooked_swap,(void**)&real_swap);
                if (rc==0 && real_swap) {
                    render_hook_installed=true;
                    LOGI("eglSwapBuffers hook installed");
                } else LOGE("eglSwapBuffers hook failed rc=%d",rc);
            } else LOGE("eglSwapBuffers symbol not found");
            dlclose(egl);
        }
        if (!render_hook_installed) sleep(1);
    }
    if (!render_hook_installed) LOGE("Render hook install timed out");

    // Optional input observer: failure here does not affect rendering.
    for (int i=0;i<120 && !input_hook_installed;i++) {
        void* android=dlopen("libandroid.so",RTLD_NOW);
        if (android) {
            void* fn=dlsym(android,"AInputQueue_getEvent");
            if (fn) {
                int rc=DobbyHook(fn,(void*)hooked_get_event,(void**)&real_get_event);
                if (rc==0 && real_get_event) {
                    input_hook_installed=true;
                    LOGI("AInputQueue_getEvent observer installed");
                } else LOGE("Input observer hook failed rc=%d",rc);
            } else LOGE("AInputQueue_getEvent symbol not found");
            dlclose(android);
        }
        if (!input_hook_installed) sleep(1);
    }
    if (!input_hook_installed) LOGE("Input observer unavailable; renderer remains active");
    return nullptr;
}
}

extern "C" __attribute__((visibility("default"))) void carx_imgui_start() {
    pthread_t t{};
    int rc=pthread_create(&t,nullptr,hook_worker,nullptr);
    if (rc==0) { pthread_detach(t); LOGI("ImGui hook worker started"); }
    else LOGE("ImGui hook worker creation failed rc=%d",rc);
}

