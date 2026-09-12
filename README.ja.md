# BepInEx Android Launcher

[English](README.md) | [简体中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md) | [Português (BR)](README.pt-BR.md) | [Русский](README.ru.md) | [日本語](README.ja.md)

任意のUnity IL2CPPゲームにBepInExをインジェクションする汎用BepInEx Androidランチャー。

## Features

- Unity IL2CPPゲームにワンタップでBepInExをインジェクション
- インストール済みUnity IL2CPPゲームを自動検出
- モッドパック管理: 作成、インポート/エクスポート (.rhp/.zip)、有効/無効切り替え
- ゲームごとの設定: フローティングログ、非ストリップlibunity、Unity Killブロッキング
- JSON/Luaシンタックスハイライト付き設定ファイルエディタ
- logcat統合ゲームログビュークラッシュ検出と診断エクスポート
- Material You動的テーマ（Monetサポート）
- デスクトップショートカット作成
- 18言語サポート

## Target

- ランチャーパッケージ: `com.bepinex.android.launcher`
- ABI: `arm64-v8a`
- 最低SDK: 28 (Android 9)

## Credits

### Projects Used & Referenced

- [BepInEx](https://github.com/BepInEx/BepInEx) — Unity IL2CPP modding framework, the core plugin loader
- [FusionCore](https://github.com/All-Of-Us-Mods/FusionCore) — Android Unity IL2CPP Runtime Container by Starlight team
- [NextBep (BepInEx.Android)](https://github.com/NextBep/BepInEx.Android) — Custom BepInEx fork for Android
- [dotnet/runtime](https://github.com/dotnet/runtime) — .NET Runtime, built from source with OpenSSL crypto backend
- [OpenSSL](https://github.com/openssl/openssl) — OpenSSL 3.4.0, replacing BoringSSL for ARM64 Android crypto
- [Pine](https://github.com/canyie/Pine) — ART Java method hook framework
- [Dobby](https://github.com/jmpews/Dobby) — Native hook framework for ARM64
- [Cpp2IL](https://github.com/SamboyCoding/Cpp2IL) — IL2CPP reverse engineering tool

### Lead Developers

HayashiUme · Gaoshu · NextBep

©2026 NextBep

## Guide

- [Documentation](https://nextbep.github.io) — Full usage guide
- [Contributing](CONTRIBUTING.md) — How to contribute to the project
- [Translate](TRANSLATING.md) — Help translate the launcher into your language
- [Troubleshooting](https://nextbep.github.io/guide/troubleshooting) — Common issues and fixes

## Build

There is a CI build in the GitHub Actions page.

Run from this directory:

```powershell
.\gradlew.bat assembleDebug
```

The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Each build increments `ci-version.txt` and sets the APK version to:

```text
0.0.1-ci.<number>
```

## Install

For a clean test installation:

```powershell
$adb = "C:\path\to\platform-tools\adb.exe"
$apk = "path\to\app-debug.apk"
& $adb uninstall com.bepinex.android.launcher
& $adb install $apk
```

Uninstalling removes launcher-private data and settings. It does not remove the external per-game data directories.

## External Data

Runtime BepInEx data is stored per-game under:

```text
/storage/emulated/0/BepInEx_Launcher/<game-package>/
```

Each game directory contains BepInEx/, plugins, configuration, logs, modpacks, and vanilla state.

Internal data (libunity cache, Unity version data) is stored under:

```text
/data/user/0/com.bepinex.android.launcher/files/<game-package>/
```

## Injection Model

The launcher injects BepInEx into the game process via native bootstrap libraries and **Pine** (Java method hooking framework):

1. `createPackageContext()` for the target game to obtain its class loader and DEX access.
2. Install Pine hooks: bidirectional ClassLoader, Instrumentation, PackageManager, native library, and UnityPlayer.
3. Redirect the game activity to a manifest-registered `StubActivity` via `Instrumentation.execStartActivity` hook.
4. `Instrumentation.newActivity` restores the real game activity class and original Intent.
5. `Activity.attachBaseContext` hook wraps the Context with a three-way `GameContextWrapper`:
   - **Game resources** (Assets, Resources, Theme) → game package context
   - **File/storage** (getFilesDir, SharedPreferences) → launcher Application
   - **Window services** (getDisplay, getSystemService) → original Activity base Context
6. `ClassLoader.findLibrary()` hook redirects native .so loading: game libs from game APK, BepInEx libs from launcher, .NET/il2cpp/unity libs from data directory.
7. `UnityPlayer` constructor hook sets the activity field.
8. `UnityPlayer.kill()` hook optionally blocks the first call for 5 seconds (per-game opt-in).

All hooks are installed from Kotlin/Java via Pine — with native `libmain.so`/`libfusion.so` controlling dlopen order and CoreCLR bootstrap.

## Debug Snapshots

After a crash, the next launcher start saves diagnostics under:

```text
/data/user/0/com.bepinex.android.launcher/files/debug/<timestamp>/
```

Snapshots include launcher logs, logcat, crash logcat, package/activity state, process exit information, and available BepInEx logs.
