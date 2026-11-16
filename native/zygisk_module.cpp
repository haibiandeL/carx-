#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <zygisk.hpp>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "PigHax", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "PigHax", __VA_ARGS__)

extern "C" void carx_snow_start();
extern "C" void carx_imgui_set_env(JNIEnv *);
extern "C" void carx_imgui_start();

class PigHaxModule final : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        api_ = api;
        env_ = env;
        LOGI("module loaded");
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *args) override {
        if (!api_ || !env_ || !args || !args->nice_name) {
            LOGE("missing specialize arguments");
            close_module();
            return;
        }

        const char *name = env_->GetStringUTFChars(args->nice_name, nullptr);
        if (!name) {
            if (env_->ExceptionCheck()) env_->ExceptionClear();
            close_module();
            return;
        }

        const bool target = std::strcmp(name, "com.carxtech.sr") == 0;
        LOGI("specialized process: %s", name);
        env_->ReleaseStringUTFChars(args->nice_name, name);

        if (!target) {
            close_module();
            return;
        }

        LOGI("CarX Street matched; starting B mode");
        carx_imgui_set_env(env_);
        carx_snow_start();
        carx_imgui_start();
        LOGI("B-mode startup calls dispatched");
    }

private:
    void close_module() {
        if (api_) api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }

    zygisk::Api *api_ = nullptr;
    JNIEnv *env_ = nullptr;
};

REGISTER_ZYGISK_MODULE(PigHaxModule)
