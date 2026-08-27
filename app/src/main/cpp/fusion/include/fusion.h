/*
 * BepInEx.Android — libfusion.so public API
 *
 * libfusion.so provides the core injection logic:
 *   1. fusion_stage_from_config_path() — reads config, hooks libunity, sets paths
 *   2. fusion_bootstrap_from_libmain() — installs il2cpp_init hook → CoreCLR → BepInEx
 *
 * libfusion has NO JNI_OnLoad. All entry points are pure C functions exported
 * with C linkage, resolved at runtime by libmain.so via dlopen/dlsym.
 */

#ifndef FUSION_H
#define FUSION_H

#include <jni.h>
#include <stdbool.h>
#include <string>

/* ── Fusion configuration (shared between fusion.cpp and dotnet.cpp) ── */

struct FusionConfig {
    std::string gameLibraryDir;
    std::string appLibraryDir;
    std::string appDataDir;
    std::string bepInExDir;
    std::string dotnetDir;
    std::string unityDataDir;
    std::string unityVersion;
    bool useOriginalLibUnity = false;
};

extern FusionConfig g_config;

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Stage 1: Read fusion config from file, set up hooks on libunity.so,
 * configure path overrides (unity, il2cpp, dotnet, BepInEx).
 *
 * Called from libmain's load() BEFORE dlopen(libunity.so) and
 * dlopen(libil2cpp.so).
 *
 * @param configPath  Absolute path to the staged fusion config file
 * @return true on success
 */
bool fusion_stage_from_config_path(const char *configPath);

/**
 * Stage 2: Bootstrap the BepInEx injection chain.
 * - Installs Dobby hook on il2cpp_init
 * - The hook callback starts CoreCLR → BepInEx before chaining to Unity
 *
 * Called from libmain's load() AFTER dlopen(libunity.so) and
 * dlopen(libil2cpp.so), but BEFORE Unity calls il2cpp_init.
 *
 * @param env  JNI environment from the calling thread
 * @return true on success
 */
bool fusion_bootstrap_from_libmain(JNIEnv *env);

/* ── Internal API (used between fusion .cpp files) ─────────────────── */

/* IL2CPP initialization and hooking (il2cpp.cpp) */
bool il2cpp_initialize(const char *il2cppPath);
void *il2cpp_get_handle();
uintptr_t il2cpp_get_library_base();
const char *il2cpp_method_get_name(void *method);

/* il2cpp_init wrapper — calls the real function through Dobby trampoline */
int il2cpp_init(char *domain_name);

/* Hook management */
void il2cpp_install_init_hook(void *hookCallback);
void il2cpp_destroy_init_hook();

/* SafeHook / Dobby (safehook.cpp) */
bool safehook_initialize(void *lib_handle, uintptr_t lib_base, void *(*allocator)(void *, void *, size_t));

/* libunity hooks (libunity.cpp) */
bool try_hook_libunity(const char *libUnityPath, const char *fallbackLibUnityPath);
bool libunity_initialize(const char *unityPath);

/* Code cave injection (allocator.cpp) */
void *allocate_setup_injected(const char *library, const char *output_path, size_t pool_size);
void *allocate_setup_injected_noload(const char *library, const char *output_path, size_t pool_size);
void *allocate_bind_loaded(void *handle);
void *allocate_injected(void *target, void *library_base, size_t size);

#ifdef __cplusplus
}
#endif

#endif /* FUSION_H */
