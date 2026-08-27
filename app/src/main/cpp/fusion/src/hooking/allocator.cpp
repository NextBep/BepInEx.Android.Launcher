/*
 * BepInEx.Android — Trampoline allocator (code cave)
 * Ported from FusionCore main branch (fusion/src/hooking/allocator.cpp)
 *
 * Uses the padded libil2cpp.so's extra memory pool to allocate
 * trampoline space for SafeHook, ensuring hooks on small functions
 * have enough room for absolute jumps.
 */

#include <cstdint>
#include <dlfcn.h>
#include <android/log.h>

#define TAG "Allocator"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Forward declaration from library.cpp
struct PaddedOpenResult {
    void *handle;
    void *base;
    size_t pool_base;
    size_t pool_size;
};
PaddedOpenResult padded_write_elf(const char *library_name,
                                  const char *temp_path, size_t pool_size);
PaddedOpenResult padded_dlopen(const char *library_name,
                               const char *temp_path, size_t pool_size);

static PaddedOpenResult g_padded;
static size_t g_pool_pointer = 0;

extern "C" {

void *allocate_setup_injected(const char *library, const char *output_path,
                               size_t pool_size)
{
    LOGI("allocate_setup_injected: %s -> %s, pool=%zu",
         library, output_path, pool_size);
    g_padded = padded_dlopen(library, output_path, pool_size);
    if (!g_padded.handle) {
        LOGE("padded_dlopen failed!");
        return nullptr;
    }
    LOGI("Pool base: 0x%zx, size: %zu",
         g_padded.pool_base, g_padded.pool_size);
    return g_padded.handle;
}

void *allocate_setup_injected_noload(const char *library, const char *output_path,
                                     size_t pool_size)
{
    LOGI("allocate_setup_injected_noload: %s -> %s, pool=%zu",
         library, output_path, pool_size);
    g_padded = padded_write_elf(library, output_path, pool_size);
    if (!g_padded.pool_size) {
        LOGE("padded_write_elf failed!");
        return nullptr;
    }
    LOGI("Patched ELF written, pool size %zu (load deferred)", g_padded.pool_size);
    return reinterpret_cast<void *>(1);
}

void *allocate_bind_loaded(void *handle)
{
    if (!handle) return nullptr;
    g_padded.handle = handle;
    void *sym = dlsym(handle, "il2cpp_init");
    if (!sym) {
        LOGE("allocate_bind_loaded: il2cpp_init not found");
        return nullptr;
    }
    Dl_info info{};
    if (!dladdr(sym, &info) || !info.dli_fbase) {
        LOGE("allocate_bind_loaded: dladdr failed");
        return nullptr;
    }
    g_padded.base = info.dli_fbase;
    const size_t pool_offset = g_padded.pool_base;
    g_padded.pool_base = reinterpret_cast<uintptr_t>(info.dli_fbase) + pool_offset;
    LOGI("Bound loaded il2cpp: base=%p pool=0x%zx size=%zu",
         g_padded.base, g_padded.pool_base, g_padded.pool_size);
    return handle;
}

void *allocate_injected(void *target, void *library_base, size_t size)
{
    if (!g_padded.handle) {
        LOGE("allocator not initialized!");
        return nullptr;
    }

    uintptr_t target_ptr = reinterpret_cast<uintptr_t>(target);
    uintptr_t tramp_ptr = g_padded.pool_base + g_pool_pointer;

    if (g_pool_pointer + size > g_padded.pool_size) {
        LOGE("Trampoline pool full!");
        return nullptr;
    }

    // Check distance: ARM64 branch range is +/-128MB
    uintptr_t dist = tramp_ptr > target_ptr ?
        tramp_ptr - target_ptr : target_ptr - tramp_ptr;
    if (dist > 0x7FFFFFF) {
        LOGE("Target 0x%zx too far from trampoline space 0x%zx",
             (size_t)target_ptr, (size_t)tramp_ptr);
        return nullptr;
    }

    g_pool_pointer += size;
    return reinterpret_cast<void *>(tramp_ptr);
}

} /* extern "C" */
