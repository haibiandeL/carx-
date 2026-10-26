// Conservative Zygisk entry for launch-isolation testing.
// The paired snow_runtime_control.cpp is intentionally a no-op.
#include <jni.h>
#include <cstring>
#include <zygisk.hpp>

extern "C" void carx_snow_start();
            carx_overlay_install();
extern "C" void carx_overlay_install();

class CarXSnowModule final : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api* api, JNIEnv* env) override {
        api_ = api;
        env_ = env;
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs* args) override {
        if (api_ == nullptr || env_ == nullptr || args == nullptr || args->nice_name == nullptr) {
            closeModule();
            return;
        }

        const char* process = env_->GetStringUTFChars(args->nice_name, nullptr);
        if (process == nullptr) {
            // JNI may have raised an exception; avoid further JNI calls here.
            closeModule();
            return;
        }

        const bool target = std::strcmp(process, "com.carxtech.sr") == 0;
        env_->ReleaseStringUTFChars(args->nice_name, process);

        if (target) {
            // This build only emits a log line; it does not touch IL2CPP/game memory.
            carx_snow_start();
            carx_overlay_install();
        } else {
            closeModule();
        }
    }

private:
    void closeModule() {
        if (api_ != nullptr) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
        }
    }

    zygisk::Api* api_ = nullptr;
    JNIEnv* env_ = nullptr;
};

REGISTER_ZYGISK_MODULE(CarXSnowModule)
