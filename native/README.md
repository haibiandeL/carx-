# Runtime controller core (research build)

This source now contains a concrete IL2CPP API-based locator for the active render pipeline, its `ParticleEffectSystem`, the current `SnowParticleEffect`, and `sizeMinMax`. It avoids guessing a `VolumeStack` dictionary key by using `RenderPipelineManager.s_CurrentPipeline` and the pipeline's own `m_particleEffectSystem.m_snowFX` references.

## Config interface

The in-process worker reads one float from `/data/local/tmp/carx_snow_multiplier`, clamped to `0.25..3.00`. If the file is absent, it uses `1.0`. The worker captures a baseline `Vector2` for each parameter object and applies `baseline * multiplier`; it also sets the `Vector2ParameterMod` override flag and `ParticleEffectSystem.m_emitterListIsDirty`.

## Important limitations

- This is a native source file, not a compiled `.so`, Zygisk module, APK, or installable ZIP.
- The code is not yet compiled against Android NDK and has not been run in the game.
- Threading safety and whether the game replaces `m_snowFX` or overwrites its parameter later in the frame require device testing.
- The config file interface still needs a real floating UI / IPC writer.
- Intended only for the exact supplied CarX Street 1.23 ARM64 `libil2cpp.so`; never reuse these object offsets for 1.24.

The runtime core logs startup milestones and applied multipliers under the Android log tag `CarXSnow`. On-device debugging can inspect that tag with logcat; absence of the startup messages means the Zygisk loader did not start or IL2CPP has not initialized.
