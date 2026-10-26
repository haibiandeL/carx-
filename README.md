# CarX Street 1.23 雪花大小悬浮滑块 — 研究源代码包

## 现在包里有什么
- `app/`：Android 悬浮窗控制端源代码。包含可拖动的浮窗、0.25×–3.00× 滑块和 SukiSU root 配置写入逻辑。
- `native/snow_runtime_control.cpp`：通过 IL2CPP 导出 API 定位当前 RenderPipeline → ParticleEffectSystem → SnowParticleEffect → sizeMinMax，并按倍率修改两个 float、置 override flag 和 emitter dirty 标志。
- `native/zygisk_module.cpp`：只在 `com.carxtech.sr` 游戏进程启动控制器的 Zygisk 入口。
- `native/build_native.sh`：需要 Android NDK 与官方 `zygisk.hpp` 后用于编译 ARM64 Zygisk `.so`。
- `analysis/`：1.23 专用反汇编和偏移核验记录。

## 重要状态
**这不是可直接安装的完成版。** 当前环境没有 Android NDK、Android SDK/Gradle 或匹配的官方 `zygisk.hpp`，所以还没有编译出 APK 和 Zygisk `.so`；也没有真机验证。不要把 `module_template/` 当作可以安装的模块。控制端源代码与原生控制逻辑已经写出，但还需在目标构建环境中编译、打包和实测。

## 目标环境
- CarX Street 1.23.0，ARM64，用户提供的 `libil2cpp.so`
- SukiSU Ultra / Zygisk Next
- 游戏进程名按 `com.carxtech.sr` 过滤

## 版本限制
所有 RVA 和对象字段布局都只针对这份 1.23 二进制。不能用于 1.24。源码中 `Vector2ParameterMod` 的 x/y 位于对象 `+0x14`、`+0x18`，`m_OverrideState` 位于 `+0x10`；`ParticleEffectSystem.m_emitterListIsDirty` 位于 `+0x20`。

## Native overlay development revision

The native source now includes `native/overlay_ui.cpp`, a Dear ImGui GLES3 panel rendered from an EGL swap hook. GitHub Actions clones Dear ImGui and compiles its core and OpenGL ES backend into the ARM64 Zygisk library. See `native/OVERLAY_STATUS.md` for current limits: touch routing and live game-parameter bindings still require implementation and device validation. CI compilation alone is not proof of runtime functionality.
