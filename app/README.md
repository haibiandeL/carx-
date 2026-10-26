# Android floating control app source

This app source provides a draggable overlay window with a size multiplier slider. It writes a sanitized numeric multiplier to `/data/local/tmp/carx_snow_multiplier` using a persistent `su` shell, which should prompt SukiSU for root authorization.

It is not compiled into an APK in this environment. Android SDK/Gradle are not available here. The app is only the UI/config writer; the native Zygisk module must also be built and installed for the game effect to change.

## Build

With JDK 17, Android SDK platform 35, Android Gradle Plugin 8.6.1, and Gradle installed, run `./app/build_app.sh`. This environment does not have Gradle or Android SDK, so no APK has been produced here.
