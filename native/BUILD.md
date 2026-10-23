# Native build notes

The Zygisk loader source expects the official `zygisk.hpp` from the installed root solution's module SDK. It must be compiled for `arm64-v8a` and linked together with `snow_runtime_control.cpp` as one shared library. The exported entry starts a worker only after Android specializes the `com.carxtech.sr` process.

A valid installable Zygisk module still requires:
1. Android NDK toolchain (not present in the current build environment).
2. The matching official `zygisk.hpp` header.
3. ARM64 compilation and link verification.
4. On-device smoke test to confirm `s_CurrentPipeline`, `m_particleEffectSystem`, `m_snowFX` and the value write behave as expected.

Do not install the source tree as if it were a module. The final module ZIP must contain the compiled `zygisk/arm64-v8a.so`, `module.prop`, and module scripts; those binaries are not generated in this research package.
