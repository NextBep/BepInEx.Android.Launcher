/*
 * BepInEx.Android 鈥?libunity.so hooks
 *
 * Ported from FusionCore main branch (fusion/src/hooking/libunity.cpp).
 *
 * Hooks scripting_method_invoke to prevent crashes when plugins
 * (like EHR via Harmony) call Unity scripting methods with null pointers.
 */

#include "fusion.h"
#include "dobby.h"
#include <dlfcn.h>
#include <string>
#include <cstring>
#include <android/log.h>

#define TAG "LibUnityHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// scripting_method_invoke hook

/*
 * FusionCore pattern: hook scripting_method_invoke to prevent crashes
 * from unstripped libunity failing to resolve scripting methods.
 * If method is null, return null instead of crashing.
 */
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

    /*
     * Resolve the C++ mangled symbol for:
     *   scripting_method_invoke(ScriptingMethodPtr, ScriptingObjectPtr,
     *                           ScriptingArguments&, ScriptingExceptionPtr*, bool)
     */
    const char *mangled =
        "_Z23scripting_method_invoke18ScriptingMethodPtr18ScriptingObjectPtr"
        "R18ScriptingArgumentsP21ScriptingExceptionPtrb";

    dlerror();
    void *target = dlsym(RTLD_DEFAULT, mangled);
    if (!target) {
        LOGE("scripting_method_invoke not found: %s", dlerror());
        return false;
    }

    LOGI("scripting_method_invoke @ %p", target);

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
