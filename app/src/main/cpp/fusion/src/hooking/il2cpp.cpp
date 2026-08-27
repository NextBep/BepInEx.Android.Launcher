/*
 * BepInEx.Android 鈥?IL2CPP initialization and hooking
 *
 * Ported from FusionCore main branch (fusion/src/hooking/il2cpp.cpp).
 *
 * Key design:
 *   - il2cpp_initialize: dlopen libil2cpp.so with RTLD_GLOBAL so that
 *     BepInEx's P/Invoke (via DllImportResolver 鈫?NativeLibrary.Load)
 *     can resolve IL2CPP symbols.
 *   - il2cpp_install_init_hook: uses DobbyHook directly (FusionCore pattern)
 *   - il2cpp_destroy_init_hook: one-shot hook cleanup (called from hook callback)
 */

#include "fusion.h"
#include <jni.h>
#include <dlfcn.h>
#include <string>
#include <cstring>
#include <android/log.h>
#include "dobby.h"

#define TAG "FusionIL2CPP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Static state

static void *g_il2cpp_handle = nullptr;       // dlopen handle
static uintptr_t g_library_base = 0;           // loaded base address

static void *g_p_il2cpp_init = nullptr;        // address of il2cpp_init in loaded library
static void *g_p_il2cpp_method_get_name = nullptr;

/* Function pointer types matching FusionCore's il2cpp.h */
typedef const char *(*il2cpp_method_get_name_t)(void *method);
typedef int         (*il2cpp_init_t)(char *domain_name);

static il2cpp_init_t             g_orig_il2cpp_init = nullptr;
static il2cpp_method_get_name_t  g_orig_il2cpp_method_get_name = nullptr;

static il2cpp_init_t             g_init_hook_fn = nullptr;  // current hook callback

// Public API

extern "C" {

bool il2cpp_initialize(const char *library_path)
{
    LOGI("il2cpp_initialize: %s", library_path);

    if (g_il2cpp_handle) {
        LOGI("il2cpp already initialized");
        return true;
    }

    /*
     * CRITICAL: RTLD_GLOBAL makes IL2CPP symbols globally visible.
     * This is required for BepInEx's managed P/Invoke to resolve
     * functions like il2cpp_domain_get, il2cpp_assembly_get_image, etc.
     *
     * Without RTLD_GLOBAL, NativeLibrary.Load in managed code returns
     * a handle that has NO globally visible symbols 鈫?SIGSEGV.
     */
    dlerror();
    g_il2cpp_handle = dlopen(library_path, RTLD_GLOBAL | RTLD_NOW);
    if (!g_il2cpp_handle) {
        char *err = dlerror();
        LOGE("Failed to open libil2cpp.so: %s", err ? err : "unknown");
        return false;
    }
    LOGI("dlopen OK (RTLD_GLOBAL)");

    /* Resolve il2cpp_init */
    dlerror();
    g_p_il2cpp_init = dlsym(g_il2cpp_handle, "il2cpp_init");
    if (!g_p_il2cpp_init) {
        char *err = dlerror();
        LOGE("Failed to find il2cpp_init: %s", err ? err : "unknown");
        return false;
    }
    g_orig_il2cpp_init = reinterpret_cast<il2cpp_init_t>(g_p_il2cpp_init);
    LOGI("il2cpp_init @ %p", g_p_il2cpp_init);

    /* Resolve il2cpp_method_get_name (used by SafeHook for diagnostics) */
    dlerror();
    g_p_il2cpp_method_get_name = dlsym(g_il2cpp_handle, "il2cpp_method_get_name");
    if (g_p_il2cpp_method_get_name) {
        g_orig_il2cpp_method_get_name =
            reinterpret_cast<il2cpp_method_get_name_t>(g_p_il2cpp_method_get_name);
        LOGI("il2cpp_method_get_name @ %p", g_p_il2cpp_method_get_name);
    } else {
        LOGI("il2cpp_method_get_name not found (non-fatal)");
    }

    /* Resolve library base via dladdr */
    Dl_info info;
    if (dladdr(g_p_il2cpp_init, &info) != 0) {
        g_library_base = reinterpret_cast<uintptr_t>(info.dli_fbase);
        LOGI("library base: 0x%lx", (unsigned long)g_library_base);
    }

    LOGI("il2cpp_initialize complete");
    return true;
}

void *il2cpp_get_handle()
{
    return g_il2cpp_handle;
}

uintptr_t il2cpp_get_library_base()
{
    return g_library_base;
}

const char *il2cpp_method_get_name(void *method)
{
    if (!g_orig_il2cpp_method_get_name) return "";
    return g_orig_il2cpp_method_get_name(method);
}

/*
 * Wrapper for the real il2cpp_init.
 * After DobbyHook is installed, this points to the original function
 * (Dobby stores the trampoline here). Called from the hook callback
 * AFTER destroying the hook, to chain to the real il2cpp_init.
 */
int il2cpp_init(char *domain_name)
{
    if (!g_orig_il2cpp_init) {
        LOGE("il2cpp_init not initialized!");
        return -1;
    }
    return g_orig_il2cpp_init(domain_name);
}

/*
 * Install a one-shot DobbyHook on il2cpp_init.
 *
 * After this call, any invocation of il2cpp_init will redirect to
 * `hook`. The REAL il2cpp_init is saved in g_orig_il2cpp_init
 * (Dobby's trampoline mechanism).
 *
 * The hook callback should call il2cpp_destroy_init_hook() before
 * chaining to the real il2cpp_init (one-shot pattern).
 */
void il2cpp_install_init_hook(void *hookCallback)
{
    if (!g_p_il2cpp_init) {
        LOGE("il2cpp_init address not resolved 鈥?call il2cpp_initialize first!");
        return;
    }

    if (!hookCallback) {
        LOGE("Hook function is null!");
        return;
    }

    g_init_hook_fn = reinterpret_cast<il2cpp_init_t>(hookCallback);

    int result = DobbyHook(
        g_p_il2cpp_init,
        hookCallback,
        reinterpret_cast<void **>(&g_orig_il2cpp_init));

    if (result != 0) {
        LOGE("DobbyHook failed: %d", result);
        return;
    }

    LOGI("DobbyHook installed on il2cpp_init");
}

/*
 * Destroy the one-shot hook. After this, il2cpp_init calls go
 * directly to the original function.
 */
void il2cpp_destroy_init_hook()
{
    if (!g_p_il2cpp_init) {
        LOGE("No hook to destroy");
        return;
    }

    DobbyDestroy(g_p_il2cpp_init);
    g_init_hook_fn = nullptr;
    LOGI("DobbyHook destroyed (one-shot complete)");
}

} /* extern "C" */
