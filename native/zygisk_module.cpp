#include <jni.h>
#include <cstring>
#include <zygisk.hpp>
extern "C" void carx_snow_start();
extern "C" void carx_imgui_start();
class CarXSnowModule final : public zygisk::ModuleBase {
public:
 void onLoad(zygisk::Api* api, JNIEnv* env) override { api_ = api; env_ = env; }
 void postAppSpecialize(const zygisk::AppSpecializeArgs* args) override {
  if (!api_ || !env_ || !args || !args->nice_name) { closeModule(); return; }
  const char* p = env_->GetStringUTFChars(args->nice_name, nullptr);
  if (!p) { closeModule(); return; }
  const bool target = std::strcmp(p, "com.carxtech.sr") == 0;
  env_->ReleaseStringUTFChars(args->nice_name, p);
  if (target) { carx_snow_start(); carx_imgui_start(); } else closeModule();
 }
private:
 void closeModule() { if (api_) api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY); }
 zygisk::Api* api_ = nullptr;
 JNIEnv* env_ = nullptr;
};
REGISTER_ZYGISK_MODULE(CarXSnowModule)
