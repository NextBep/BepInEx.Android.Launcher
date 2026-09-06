# BepInEx Android Launcher

通用 BepInEx Android 启动器，支持在 Unity IL2CPP 游戏中注入 mod 框架。

## 功能

- 一键注入 BepInEx 到 Unity IL2CPP 游戏
- Modpack 管理：创建、导入/导出、启用/禁用 mod 组合
- 配置文件编辑器（带语法高亮）
- 游戏日志查看器（logcat 集成）
- 崩溃检测与诊断导出
- 动态主题色（Material You / Monet）
- 多语言支持（18 种语言）
- 每个游戏独立的 libunity 配置

## 技术原理

启动器使用 Pine（ART hook 框架）拦截游戏的 ClassLoader、native library 加载和 UnityPlayer 初始化。自定义的 `libmain.so` 和 `libfusion.so` 控制 dlopen 顺序，在 Unity 启动前安装 `il2cpp_init` hook。该 hook 启动 CoreCLR 和 BepInEx preloader。

## 系统要求

- Android 9+（API 28+）
- arm64-v8a 设备
- 已安装 Unity IL2CPP 游戏

## 构建

```bash
MSYS_NO_PATHCONV=1 ./gradlew assembleDebug
```

环境要求：Android SDK 35、NDK 27.0.12077973、CMake 3.22.1、JDK 17。

## 项目结构

```
app/src/main/
  cpp/          Native 代码（libmain.so、libfusion.so）
  java/         Kotlin 启动器 UI 和注入逻辑
  assets/       BepInEx 框架和 .NET 运行时
  res/          UI 字符串（18 种语言）
AuthFixPlugin/  Among Us Google 登录修复（BepInEx 插件）
build_assets/   BepInEx 核心文件打包
```

## 相关项目

- [NextBep/BepInEx.Android](https://github.com/NextBep/BepInEx.Android) — Android BepInEx 分支
- [NextBep/runtime](https://github.com/NextBep/runtime) — dotnet/runtime Android 修复分支
- [NextBep/AndroidNativeLibraries](https://github.com/NextBep/AndroidNativeLibraries) — 未剥离的 libunity.so

## 文档

访问 [NextBep 文档站](https://nextbep.github.io) 获取详细使用指南。

## 许可证

GNU General Public License v3.0
