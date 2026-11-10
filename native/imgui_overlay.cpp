// In-game Dear ImGui overlay for CarX Street (ARM64 / Android).
// Render path: eglSwapBuffers + OpenGL ES 3. Input path: AInputQueue_getEvent.
// This is a source integration; runtime compatibility must be checked on-device.
#include <android/log.h>
#include <android/input.h>
#include <android/looper.h>
#include <dlfcn.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <pthread.h>
#include <unistd.h>
#include <cerrno>
#include <cstdarg>
#include <sys/stat.h>
#include <cstdio>
#include <cstring>
#include <algorithm>
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
pthread_mutex_t ui_mutex = PTHREAD_MUTEX_INITIALIZER;
bool imgui_ready = false;
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
    std::fputc('\n', f); std::fclose(f);
    chmod(path, 0644);
}

void draw_menu() {
    ImGuiIO& io = ImGui::GetIO();
    io.DisplaySize = ImVec2((float)surface_w, (float)surface_h);
    ImGui::NewFrame();
    if (menu_open) {
        ImGui::SetNextWindowPos(ImVec2(24, 48), ImGuiCond_Once);
        ImGui::SetNextWindowSize(ImVec2(430, 0), ImGuiCond_Once);
        ImGui::SetNextWindowBgAlpha(0.94f);
        ImGui::PushStyleVar(ImGuiStyleVar_WindowRounding, 15.0f);
        ImGui::PushStyleVar(ImGuiStyleVar_FrameRounding, 8.0f);
        ImGui::PushStyleColor(ImGuiCol_WindowBg, ImVec4(0.075f,0.08f,0.09f,0.96f));
        ImGui::PushStyleColor(ImGuiCol_Header, ImVec4(0.12f,0.27f,0.34f,1.0f));
        ImGui::Begin("PigHax  |  CarX Street", &menu_open, ImGuiWindowFlags_AlwaysAutoResize | ImGuiWindowFlags_NoCollapse);
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
        ImGui::TextDisabled("Tap the close button to hide; touch outside the panel passes to the game.");
        ImGui::End();
        ImGui::PopStyleColor(2);
        ImGui::PopStyleVar(2);
    }
    ImGui::Render();
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
}

EGLBoolean hooked_swap(EGLDisplay display, EGLSurface surface) {
    if (!real_swap) return EGL_FALSE;
    EGLint w=0,h=0;
    eglQuerySurface(display, surface, EGL_WIDTH, &w);
    eglQuerySurface(display, surface, EGL_HEIGHT, &h);
    if (w > 0 && h > 0) { surface_w=w; surface_h=h; }
    if (!imgui_ready && surface_w > 0 && surface_h > 0) {
        IMGUI_CHECKVERSION(); ImGui::CreateContext();
        ImGui::StyleColorsDark();
        ImGuiIO& io=ImGui::GetIO(); io.IniFilename=nullptr; io.DisplaySize=ImVec2((float)surface_w,(float)surface_h);
        if (ImGui_ImplOpenGL3_Init("#version 300 es")) { imgui_ready=true; LOGI("ImGui GLES3 initialized %dx%d",surface_w,surface_h); }
        else LOGE("ImGui GLES3 init failed");
    }
    if (imgui_ready) {
        GLint old_program=0, old_vao=0, old_array=0, old_element=0;
        glGetIntegerv(GL_CURRENT_PROGRAM,&old_program); glGetIntegerv(GL_VERTEX_ARRAY_BINDING,&old_vao);
        glGetIntegerv(GL_ARRAY_BUFFER_BINDING,&old_array); glGetIntegerv(GL_ELEMENT_ARRAY_BUFFER_BINDING,&old_element);
        draw_menu();
        glUseProgram((GLuint)old_program); glBindVertexArray((GLuint)old_vao);
        glBindBuffer(GL_ARRAY_BUFFER,(GLuint)old_array); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,(GLuint)old_element);
    }
    return real_swap(display,surface);
}

int hooked_get_event(AInputQueue* queue, AInputEvent** event) {
    if (!real_get_event) return -1;
    int result=real_get_event(queue,event);
    if (result < 0 || !event || !*event || !imgui_ready) return result;
    AInputEvent* e=*event;
    if (AInputEvent_getType(e)==AINPUT_EVENT_TYPE_MOTION) {
        const int action=AMotionEvent_getAction(e)&AMOTION_EVENT_ACTION_MASK;
        const float x=AMotionEvent_getX(e,0), y=AMotionEvent_getY(e,0);
        ImGuiIO& io=ImGui::GetIO();
        io.AddMousePosEvent(x,y);
        if (action==AMOTION_EVENT_ACTION_DOWN) io.AddMouseButtonEvent(0,true);
        if (action==AMOTION_EVENT_ACTION_UP || action==AMOTION_EVENT_ACTION_CANCEL) io.AddMouseButtonEvent(0,false);
        // Consume touches only while they fall inside the menu's screen rectangle.
        const bool inside = menu_open && x>=24 && x<=470 && y>=48 && y<=760;
        if (inside && (action==AMOTION_EVENT_ACTION_DOWN || action==AMOTION_EVENT_ACTION_MOVE || action==AMOTION_EVENT_ACTION_UP)) {
            AInputQueue_finishEvent(queue,e,1);
            *event=nullptr;
            return -EAGAIN;
        }
    }
    return result;
}

void* hook_worker(void*) {
    for (int i=0;i<90;i++) {
        void* egl=dlopen("libEGL.so",RTLD_NOW);
        void* android=dlopen("libandroid.so",RTLD_NOW);
        if (egl && android) {
            void* swap=dlsym(egl,"eglSwapBuffers");
            void* get_event=dlsym(android,"AInputQueue_getEvent");
            if (swap && DobbyHook(swap,(void*)hooked_swap,(void**)&real_swap)==0) LOGI("eglSwapBuffers hook installed");
            else LOGE("eglSwapBuffers hook failed");
            if (get_event && DobbyHook(get_event,(void*)hooked_get_event,(void**)&real_get_event)==0) LOGI("AInputQueue_getEvent hook installed");
            else LOGE("input hook failed; touch input may not be captured");
            return nullptr;
        }
        sleep(1);
    }
    LOGE("EGL/Android libraries not found"); return nullptr;
}
}

extern "C" __attribute__((visibility("default"))) void carx_imgui_start() {
    pthread_t t{}; if (pthread_create(&t,nullptr,hook_worker,nullptr)==0) pthread_detach(t);
}
