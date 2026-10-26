# Native overlay status

This revision adds an in-process Dear ImGui panel rendered from the EGL `eglSwapBuffers` path using the GLES3 backend. GitHub Actions fetches the upstream Dear ImGui source at build time and compiles it into the ARM64 shared library.

## Important limitations (not device-verified)
- A visible panel is implemented in source, but touch input routing is not yet wired to ImGui; slider/combo/checkbox interaction requires forwarding Android touch events to `ImGuiIO::AddMouse...` or a verified input hook.
- The panel's controls are UI state only. Snow-size slider is not yet connected to the existing runtime controller. Time/weather and wet-ground writes are not connected. Wet-ground field `RenderCore.Weather.icenessRoughnessWeight` at field offset `0xA0` is retained as a documented user-provided field offset, not treated as a raw float address.
- EGL symbol interception depends on loader/linker behavior and must be tested on the target Android version. A successful CI build does not prove the overlay appears on device.
- Hiding the panel currently hides it without an in-panel reopen control. Reopen integration and a touch gesture/hotkey are follow-up work.

Do not describe this as a fully working in-game mod until build artifacts and on-device rendering/input tests pass.
