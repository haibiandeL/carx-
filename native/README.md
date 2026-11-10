# Native module

The native module loads in the target CarX Street process through Zygisk. The current integration adds an in-game Dear ImGui overlay rendered from the OpenGL ES 3 `eglSwapBuffers` path, with a best-effort Android `AInputQueue_getEvent` touch hook. It does not create an Android application overlay window.

See `IMGUI_INTEGRATION.md` for dependencies, controls, and validation limitations. Build through `.github/workflows/android-build.yml`; the workflow fetches Dear ImGui and Dobby and builds the ARM64 library with the Android NDK.

The existing IL2CPP runtime controller remains version-sensitive and must be validated against the exact CarX Street 1.24 ARM64 build. No claim of successful in-game execution is made until tested on device.
