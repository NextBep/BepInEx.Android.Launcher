/*
 * BepInEx.Android 鈥?libfusion.so core
 *
 * Ported from FusionCore main branch (fusion/src/fusion.cpp).
 *
 * Coordinates the full injection pipeline:
 *   Config parse 鈫?allocate injected code cave 鈫?il2cpp_init hook 鈫?CoreCLR 鈫?BepInEx
 */

#include "fusion.h"
#include <dlfcn.h>
#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdio>
#include <dirent.h>
#include <sys/stat.h>
#include <unistd.h>
#include <android/log.h>

#define LOG_TAG "Fusion"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Global config instance

FusionConfig g_config;
static std::string g_configPath;
static bool g_staged = false;

// Forward declarations

/* libmain overrides (defined in main.cpp with extern "C" linkage) */
extern "C" {
    void libmain_set_override_unity_path(const char *path);
    void libmain_set_override_il2cpp_path(const char *path);
    const char *libmain_get_override_unity_path();
    const char *libmain_get_override_il2cpp_path();
}

/* Hooking */
bool il2cpp_initialize(const char *il2cppPath);
void il2cpp_install_init_hook(void *hookCallback);
void il2cpp_destroy_init_hook();
int il2cpp_init(char *domain_name);
void *il2cpp_get_handle();
uintptr_t il2cpp_get_library_base();
bool safehook_initialize(void *lib_handle, uintptr_t lib_base,
                         void *(*allocator)(void *, void *, size_t));

/* CoreCLR / BepInEx bootstrap */
bool dotnet_start_runtime();

// Config file parsing

static std::string read_file(const char *path)
{
    std::string result;
    FILE *f = fopen(path, "rb");
    if (!f) return result;

    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (size > 0) {
        result.resize(size);
        fread(&result[0], 1, size, f);
    }
    fclose(f);
    return result;
}

/*
 * Simple config parser. Expected format (one key=value per line):
 *
 *   gameLibraryDirectory=/data/app/.../lib/arm64
 *   appLibraryDirectory=/data/app/.../lib/arm64
 *   appDataDirectory=/data/data/com.bepinex.android/files/...
 *   bepInExDirectory=/storage/...
 *   dotnetDirectory=/storage/...
 *   unityDataDirectory=/storage/...
 *   unityVersion=2022.3.62f3
 *   useOriginalLibUnity=false
 */
static bool parse_fusion_config(const char *text, FusionConfig *out)
{
    if (!text || !out) return false;

    const char *p = text;
    while (*p) {
        /* Skip whitespace */
        while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n') p++;
        if (!*p) break;

        /* Read key */
        std::string key;
        while (*p && *p != '=' && *p != '\n' && *p != '\r') key += *p++;
        if (*p != '=') continue; /* skip malformed lines */
        p++; /* skip = */

        /* Read value */
        std::string value;
        while (*p && *p != '\n' && *p != '\r') value += *p++;
        if (*p) p++; /* skip newline */

        /* Assign */
        if (key == "gameLibraryDirectory") out->gameLibraryDir = value;
        else if (key == "appLibraryDirectory") out->appLibraryDir = value;
        else if (key == "appDataDirectory") out->appDataDir = value;
        else if (key == "bepInExDirectory") out->bepInExDir = value;
        else if (key == "dotnetDirectory") out->dotnetDir = value;
        else if (key == "unityDataDirectory") out->unityDataDir = value;
        else if (key == "unityVersion") out->unityVersion = value;
        else if (key == "useOriginalLibUnity") out->useOriginalLibUnity = (value == "true");
    }

    return !out->appLibraryDir.empty();
}

// Staging

static bool stage_fusion_config(const FusionConfig &config)
{
    LOGI("=== Staging Fusion ===");
    LOGI("  gameLibDir:   %s", config.gameLibraryDir.c_str());
    LOGI("  appLibDir:    %s", config.appLibraryDir.c_str());
    LOGI("  appDataDir:   %s", config.appDataDir.c_str());
    LOGI("  bepInExDir:   %s", config.bepInExDir.c_str());
    LOGI("  dotnetDir:    %s", config.dotnetDir.c_str());
    LOGI("  unityDataDir: %s", config.unityDataDir.c_str());
    LOGI("  unityVersion: %s", config.unityVersion.c_str());
    LOGI("  useOrigUnity: %s", config.useOriginalLibUnity ? "true" : "false");

    /* Set library paths */
    std::string unityPath;
    std::string il2cppPath;
    std::string gameIl2cppPath = config.gameLibraryDir + "/libil2cpp.so";

    if (config.useOriginalLibUnity) {
        unityPath = config.gameLibraryDir + "/libunity.so";
    } else {
        unityPath = config.appLibraryDir + "/libunity.so";
    }

    /*
     * FusionCore pattern: patch libil2cpp.so with an extended memory segment
     * (1 MB code cave) 鈫?copy to appDataDir 鈫?load the patched copy.
     *
     * The code cave provides executable memory within the library's own
     * segment, bypassing Android 14+ W^X restrictions. Dobby trampolines
     * are allocated from this cave instead of mmap (which would be W^X).
     *
     * The patched copy path matches the DataLibrary redirect in
     * NativeLibraryManager 鈫?findLibrary("il2cpp") returns this path.
     */
    std::string patchedIl2CppPath = config.appDataDir + "/libil2cpp.so";
    LOGI("Patching il2cpp: %s -> %s (1MB pool)", gameIl2cppPath.c_str(), patchedIl2CppPath.c_str());

    void *poolHandle = allocate_setup_injected_noload(
        gameIl2cppPath.c_str(),
        patchedIl2CppPath.c_str(),
        1024 * 1024);  // 1 MB code cave, load AFTER libunity
    if (!poolHandle) {
        LOGE("allocate_setup_injected_noload failed — falling back to original il2cpp path");
        il2cppPath = gameIl2cppPath;
    } else {
        il2cppPath = patchedIl2CppPath;
        LOGI("Patched il2cpp ELF written (load deferred until after libunity)");
    }

    LOGI("  unityPath:  %s", unityPath.c_str());
    LOGI("  il2cppPath: %s", il2cppPath.c_str());

    /* Tell libmain where to find the libraries.
     * Do NOT dlopen/hook libunity here — UnityPlayer is still constructing
     * and loading libunity too early crashes some games (PVZ). */
    libmain_set_override_unity_path(unityPath.c_str());
    libmain_set_override_il2cpp_path(il2cppPath.c_str());

    g_config = config;
    g_staged = true;

    LOGI("=== Fusion staged ===");
    return true;
}

// il2cpp_init hook callback

/*
 * One-shot hook on il2cpp_init (FusionCore pattern).
 *
 * Called when Unity invokes il2cpp_init. We:
 *   1. Destroy the hook (one-shot 鈥?only fire once)
 *   2. Call the real il2cpp_init (chains through Dobby trampoline)
 *   3. Set environment variables for BepInEx
 *   4. Start CoreCLR 鈫?BepInEx
 *
 * Signature matches FusionCore: int il2cpp_init(char *domain_name)
 */
static int il2cpp_init_hook(char *domain_name)
{
    __android_log_write(ANDROID_LOG_ERROR, "FusionB", "il2cpp_init HOOK FIRED!");

    /* 1. Destroy hook IMMEDIATELY (one-shot pattern) */
    il2cpp_destroy_init_hook();
    __android_log_write(ANDROID_LOG_ERROR, "FusionB", "hook destroyed (one-shot)");

    /* 2. Call the real il2cpp_init (goes through Dobby's trampoline) */
    int result = il2cpp_init(domain_name);
    { char buf[64]; snprintf(buf, sizeof(buf), "real il2cpp_init returned %d", result);
      __android_log_write(ANDROID_LOG_ERROR, "FusionB", buf); }

    /* 3. Set env vars for BepInEx 鈥?use libmain override paths */
    const char *il2cppPath = libmain_get_override_il2cpp_path();
    setenv("BEPINEX_GAME_ASSEMBLY_PATH", il2cppPath, 1);
    setenv("NEXT_GAME_BINARY", il2cppPath, 1);
    setenv("NEXT_BEPINEX_PATH", g_config.bepInExDir.c_str(), 1);
    setenv("NEXT_GAME_DATA_DIR", g_config.unityDataDir.c_str(), 1);
    setenv("NEXT_APP_DATA_DIR", g_config.appDataDir.c_str(), 1);
    setenv("NEXT_UNITY_VERSION", g_config.unityVersion.c_str(), 1);
    setenv("TMPDIR", g_config.appDataDir.c_str(), 1);
    setenv("DOTNET_ReadyToRun", "0", 1);
    setenv("DOTNET_EnableWriteXorExecute", "0", 1);
    __android_log_write(ANDROID_LOG_ERROR, "FusionB", "env vars set");

    /* 4. Start CoreCLR + BepInEx */
    __android_log_write(ANDROID_LOG_ERROR, "FusionB", "calling dotnet_start_runtime...");
    dotnet_start_runtime();

    __android_log_write(ANDROID_LOG_ERROR, "FusionB", "hook returning");
    return result;
}

// Public API

extern "C" {

bool fusion_stage_from_config_path(const char *configPath)
{
    LOGI("fusion_stage_from_config_path: %s", configPath);
    g_configPath = configPath;

    std::string content = read_file(configPath);
    if (content.empty()) {
        LOGE("Failed to read config: %s", configPath);
        return false;
    }
    LOGI("Config (%zu bytes):\n%s", content.size(), content.c_str());

    FusionConfig config;
    if (!parse_fusion_config(content.c_str(), &config)) {
        LOGE("Failed to parse config");
        return false;
    }

    return stage_fusion_config(config);
}

/*
 * Bootstrap the fusion chain (FusionCore main branch pattern).
 *
 * Called from libmain's load() AFTER libunity.so and libil2cpp.so
 * have been dlopen'd. Steps:
 *
 *   1. Initialize IL2CPP (dlopen with RTLD_GLOBAL 鈫?symbols visible for P/Invoke)
 *   2. Initialize SafeHook with IL2CPP handle/base
 *   3. Install one-shot DobbyHook on il2cpp_init
 *
 * NOTE: Code cave injection (allocate_setup_injected) is not yet
 * implemented. For now we rely on Dobby's own trampoline allocation.
 * The il2cpp_init function is large enough for Dobby to hook directly.
 */
bool fusion_bootstrap_from_libmain(JNIEnv *env)
{
    if (!g_staged) {
        LOGE("fusion_bootstrap called before staging!");
        return false;
    }

    LOGI("=== fusion_bootstrap_from_libmain ===");

    /* 1. Hook libunity to prevent null method crashes (FusionCore pattern) */
    const char *unityPath = libmain_get_override_unity_path();
    const char *il2cppPath = libmain_get_override_il2cpp_path();
    LOGI("unity path: %s", unityPath);
    LOGI("il2cpp path: %s", il2cppPath);

    if (!try_hook_libunity(unityPath, unityPath)) {
        LOGW("try_hook_libunity failed 鈥?continuing anyway");
    }

    /* 2. Initialize IL2CPP 鈥?dlopen libil2cpp.so with RTLD_GLOBAL */

    if (!il2cpp_initialize(il2cppPath)) {
        LOGE("il2cpp_initialize failed");
        return false;
    }
    allocate_bind_loaded(il2cpp_get_handle());

    /* 3. Initialize SafeHook with injected code cave allocator */
    void *il2cppHandle = il2cpp_get_handle();
    uintptr_t il2cppBase = il2cpp_get_library_base();

    /*
     * Pass allocate_injected as the trampoline allocator.
     * This ensures Dobby trampolines are allocated within the patched
     * libil2cpp.so's code cave (bypassing Android W^X).
     */
    if (!safehook_initialize(il2cppHandle, il2cppBase, allocate_injected)) {
        LOGE("safehook_initialize failed");
        return false;
    }
    LOGI("SafeHook initialized");

    /* 4. Install il2cpp_init hook (one-shot, will fire when Unity calls il2cpp_init) */
    il2cpp_install_init_hook(reinterpret_cast<void *>(il2cpp_init_hook));

    LOGI("Fusion bootstrap complete 鈥?waiting for il2cpp_init...");
    return true;
}

} /* extern "C" */
