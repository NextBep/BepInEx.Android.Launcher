# BepInEx Android Launcher

Android launcher app for running BepInEx mods in Unity IL2CPP games.

## Features

- One-click BepInEx injection into Unity IL2CPP games (Among Us, etc.)
- Modpack manager: create, import/export, enable/disable mod sets
- Config file editor with syntax highlighting
- Game-side log viewer (logcat integration)

## How it works

The launcher uses Pine (ART hook framework) to intercept the game's ClassLoader, native library loading, and UnityPlayer initialization. A custom `libmain.so` and `libfusion.so` control the dlopen order to install an `il2cpp_init` hook before Unity starts. The hook launches CoreCLR and the BepInEx preloader.

## Requirements

- Android 9+ (API 28+)
- arm64-v8a device
- Unity IL2CPP game installed

## Building

```
MSYS_NO_PATHCONV=1 ./gradlew assembleDebug
```

Requirements: Android SDK 35, NDK 27.0.12077973, CMake 3.22.1, JDK 17.

## Project structure

```
app/src/main/
  cpp/          Native code (libmain.so, libfusion.so)
  java/         Kotlin launcher UI and injection logic
  assets/       BepInEx framework and .NET runtime archives
  res/          UI strings (EN + zh-CN)
AuthFixPlugin/  Among Us Google login fix (BepInEx plugin)
build_assets/   BepInEx core files for packaging
```

## Related projects

- [NextBep/BepInEx.Android](https://github.com/NextBep/BepInEx.Android) — BepInEx fork for Android
- [NextBep/runtime](https://github.com/NextBep/runtime) — dotnet/runtime fork with Android fixes
- Starlight's FusionCore (reference implementation)

## License

GNU General Public License v3.0
