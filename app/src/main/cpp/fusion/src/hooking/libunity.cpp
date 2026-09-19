/*
 * BepInEx.Android — libunity.so hooks
 *
 * Hooks scripting_method_invoke so that a null scripting method pointer
 * from an unstripped libunity does not crash the game.
 */

#include "fusion.h"
#include "utilities/elf.h"
#include "dobby.h"
#include <dlfcn.h>
#include <filesystem>
#include <string>
#include <android/log.h>

#define TAG "LibUnityHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace fs = std::filesystem;

using scripting_method_invoke_fn = void* (*)(void* method, void* obj,
                                              void* args, void* exc, bool something);

static scripting_method_invoke_fn g_original_scripting_method_invoke = nullptr;

static void* scripting_method_invoke_hook(void* method, void* obj,
                                           void* args, void* exc, bool something)
{
    if (!method) {
        return nullptr;
    }
    return g_original_scripting_method_invoke(method, obj, args, exc, something);
}

/* scripting_method_invoke is LOCAL HIDDEN — absent from .dynsym, so dlsym
 * cannot see it. Read its RVA out of the companion libunity.sym.so instead. */
static uintptr_t resolve_module_base(const char* path)
{
    void* handle = dlopen(path, RTLD_NOLOAD | RTLD_LAZY);
    if (!handle) {
        handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    }
    if (!handle) return 0;

    void* sym = dlsym(handle, "JNI_OnLoad");
    if (!sym) return 0;

    Dl_info info{};
    if (!dladdr(sym, &info) || !info.dli_fbase) return 0;

    return reinterpret_cast<uintptr_t>(info.dli_fbase);
}

// Public API

extern "C" {

bool try_hook_libunity(const char *libUnityPath, const char *fallbackLibUnityPath)
{
    LOGI("try_hook_libunity: %s", libUnityPath);

    /* Prefer an already-loaded libunity (loaded by NativeLoader). Extra
     * RTLD_NOW dlopen during UnityPlayer construction crashes some games. */
    void *handle = dlopen(libUnityPath, RTLD_NOW | RTLD_NOLOAD);
    if (!handle) {
        handle = dlopen(libUnityPath, RTLD_LAZY | RTLD_GLOBAL);
    }
    if (!handle && fallbackLibUnityPath) {
        handle = dlopen(fallbackLibUnityPath, RTLD_NOW | RTLD_NOLOAD);
        if (!handle) {
            handle = dlopen(fallbackLibUnityPath, RTLD_LAZY | RTLD_GLOBAL);
        }
    }
    if (!handle) {
        LOGE("Failed to dlopen libunity.so: %s", dlerror());
        return false;
    }

    /* The shipped libunity is stripped; symbols live in the companion .sym.so. */
    fs::path sym_path = fs::path(libUnityPath);
    sym_path.replace_extension("sym.so");
    if (!fs::exists(sym_path)) {
        LOGE("libunity.sym.so not found at %s", sym_path.c_str());
        return false;
    }

    const char *mangled =
        "_Z23scripting_method_invoke18ScriptingMethodPtr18ScriptingObjectPtr"
        "R18ScriptingArgumentsP21ScriptingExceptionPtrb";

    uintptr_t rva = get_rva_from_sym_file(sym_path.c_str(), mangled);
    if (rva == 0) {
        LOGE("scripting_method_invoke not found in %s", sym_path.c_str());
        return false;
    }

    uintptr_t base = resolve_module_base(libUnityPath);
    if (base == 0 && fallbackLibUnityPath) {
        base = resolve_module_base(fallbackLibUnityPath);
    }
    if (base == 0) {
        LOGE("Failed to resolve libunity base address");
        return false;
    }

    void *target = reinterpret_cast<void *>(base + rva);
    LOGI("scripting_method_invoke @ %p (base=%p, rva=0x%zx)",
         target, reinterpret_cast<void *>(base), rva);

    int ret = DobbyHook(
        target,
        reinterpret_cast<void *>(scripting_method_invoke_hook),
        reinterpret_cast<void **>(&g_original_scripting_method_invoke));

    if (ret != 0) {
        LOGE("DobbyHook scripting_method_invoke failed: %d", ret);
        return false;
    }

    LOGI("scripting_method_invoke hook installed");
    return true;
}

} /* extern "C" */
