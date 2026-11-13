#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <zygisk.hpp>

#define ZLOGI(...) __android_log_print(ANDROID_LOG_INFO, "PigHaxDiag", __VA_ARGS__)
#define ZLOGE(...) __android_log_print(ANDROID_LOG_ERROR, "PigHaxDiag", __VA_ARGS__)

extern "C" void carx_snow_start();
extern "C" void carx_imgui_start();

class CarXSnowModule final : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api* api, JNIEnv* env) override {
        api_ = api;
        env_ = env;
        ZLOGI("Zygisk onLoad reached");
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs* args) override {
        ZLOGI("postAppSpecialize entered");
        if (!api_ || !env_ || !args || !args->nice_name) {
            ZLOGE("specialize args/api/env missing; closing module");
            closeModule();
            return;
        }

        const char* p = env_->GetStringUTFChars(args->nice_name, nullptr);
        if (!p) {
            ZLOGE("GetStringUTFChars failed; closing module");
            closeModule();
            return;
        }

        ZLOGI("specialized process nice_name=%s", p);
        const bool target = std::strcmp(p, "com.carxtech.sr") == 0;
        env_->ReleaseStringUTFChars(args->nice_name, p);

        if (target) {
            ZLOGI("target process matched; starting runtime and ImGui");
            carx_snow_start();
            ZLOGI("carx_snow_start returned");
            carx_imgui_start();
            ZLOGI("carx_imgui_start returned");
        } else {
            ZLOGI("non-target process; unloading module");
            closeModule();
        }
    }

private:
    void closeModule() {
        if (api_) api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }
    zygisk::Api* api_ = nullptr;
    JNIEnv* env_ = nullptr;
};

REGISTER_ZYGISK_MODULE(CarXSnowModule)
