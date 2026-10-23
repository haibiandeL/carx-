// Zygisk entry for the runtime controller.
// Build together with snow_runtime_control.cpp and the official zygisk.hpp
// supplied by the installed root solution's module SDK.
#include <jni.h>
#include <string>
#include <zygisk.hpp>

extern "C" void carx_snow_start();

class CarXSnowModule final : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api* api, JNIEnv* env) override {
        api_ = api;
        env_ = env;
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs* args) override {
        if (!args) {
            if (api_) api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        jstring name = args->nice_name; // AppSpecializeArgs stores JNI references, not pointers.
        if (!name) {
            if (api_) api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        const char* process = env_->GetStringUTFChars(name, nullptr);
        if (!process) {
            if (api_) api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        const bool target = std::string(process) == "com.carxtech.sr";
        env_->ReleaseStringUTFChars(name, process);
        if (target) {
            carx_snow_start();
        } else if (api_) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
        }
    }
private:
    zygisk::Api* api_ = nullptr;
    JNIEnv* env_ = nullptr;
};

REGISTER_ZYGISK_MODULE(CarXSnowModule)
