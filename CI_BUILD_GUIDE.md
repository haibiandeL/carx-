# GitHub Actions 自动编译说明

这个项目可以把 Android SDK/NDK 编译工作交给 GitHub-hosted runner；手机不需要本地安装 NDK。

## 目前流程
- `.github/workflows/android-build.yml`：推送到 `main` / `master` 或手动运行时触发。
- runner 安装 JDK 17、Gradle 8.9、Android platform 35、NDK 27.2.12479018。
- 从官方 Zygisk 示例仓库取得 `zygisk.hpp`，尝试编译 APK 与 ARM64 原生库。
- 成功时上传 APK、模块 ZIP 和 SHA256 清单作为 workflow artifact。

## 必须说明
这是自动化构建配置，不代表已经编译成功或已在手机上验证。第一次运行要查看 Actions 日志；若 C++ 接口、项目 Gradle 配置或运行时定位有错误，workflow 会失败，需要根据日志修复。即使编译通过，也必须在 CarX Street 1.23 上测试，不能用于 1.24。

## 如何触发
1. 把本项目文件夹里的内容上传到你自己的 GitHub 仓库（仓库根目录应直接包含 `settings.gradle`、`app/`、`native/` 和 `.github/`）。
2. 打开仓库的 **Actions** 标签。
3. 选择 **Build CarX Snow Slider**，点 **Run workflow**；也可以把文件推送到 `main` / `master` 自动触发。
4. 构建成功后，在该次运行页面底部的 **Artifacts** 下载 `carx-snow-slider-build`。

如果你希望我直接把工作流推送到你的仓库，需要先连接/授权 GitHub，并提供目标仓库；我不能从当前对话直接假定有你的仓库写入权限。


## 修复第一次构建失败（Android SDK 初始化）

如果日志在“设置 Android SDK”处以 `sdkmanager` 退出码 1 失败，请使用本包更新后的 `.github/workflows/android-build.yml`：已将 `android-actions/setup-android@v3` 更新为 `@v4`，并只让该步骤安装 `platform-tools`，避免初始化阶段安装额外旧工具包。Android 平台、Build Tools 和 NDK 仍由下一步明确安装。

把本包更新后的仓库文件上传/覆盖到 GitHub 仓库，然后打开 **Actions → Build CarX Snow Slider → Run workflow** 重新运行。若仍失败，请展开“Set up Android SDK”步骤，截取从 `Warning` 或 `Error` 首次出现处开始、往上约 20 行的日志；当前截图只有调用栈，无法确定底层 `sdkmanager` 的具体失败原因。
