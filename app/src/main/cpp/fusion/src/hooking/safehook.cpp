/*
 * BepInEx.Android 鈥?Dobby SafeHook wrapper
 *
 * Ported from FusionCore main branch (fusion/src/hooking/safehook.cpp).
 *
 * Wraps DobbyHook with additional logic:
 *   - Trampoline allocation from injected code cave (via allocator)
 *   - Near-branch optimization for close targets
 *   - Bridge hook for functions using X8 return buffer (ARM64)
 */

#include "fusion.h"
#include "dobby.h"
#include <dlfcn.h>
#include <mutex>
#include <cstring>
#include <sys/mman.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "SafeHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#if defined(__aarch64__)
static constexpr size_t kTrampolineSize = 16;  // 4 instructions
#elif defined(__arm__)
static constexpr size_t kTrampolineSize = 8;
#endif

static std::mutex g_hook_mutex;
static const size_t kPageSize = sysconf(_SC_PAGESIZE);

static void *g_library_handle = nullptr;
static uintptr_t g_library_base = 0;

/* Allocator function 鈥?allocates from injected code cave */
using allocate_func = void *(*)(void *target, void *base, size_t size);
static allocate_func g_allocator = nullptr;

// Helpers

static inline uintptr_t align_down(uintptr_t addr, size_t page_size) {
    return addr & ~(static_cast<uintptr_t>(page_size) - 1);
}

static bool protect_trampoline(void *tramp, size_t size, int protection) {
    auto start = reinterpret_cast<uintptr_t>(tramp);
    auto start_page = align_down(start, kPageSize);
    auto end = start + size - 1;
    auto last_page = align_down(end, kPageSize);

    if (mprotect(reinterpret_cast<void *>(start_page), kPageSize, protection) != 0) {
        LOGE("mprotect failed: %s", strerror(errno));
        return false;
    }
    if (start_page != last_page) {
        if (mprotect(reinterpret_cast<void *>(last_page), kPageSize, protection) != 0) {
            LOGE("mprotect page2 failed: %s", strerror(errno));
            return false;
        }
    }
    return true;
}

// Absolute jump emitter (ARM64)

static bool emit_absolute_jump(void *code_addr, void *branch_addr) {
    if (!code_addr || !branch_addr) return false;

    // ARM64:
    // 0x00: LDR X16, [PC, #8]
    // 0x04: BR X16
    // 0x08: .quad branch_address
    auto *code = reinterpret_cast<uint32_t *>(code_addr);
    code[0] = 0x58000050;  // LDR X16, #8
    code[1] = 0xD61F0200;  // BR X16
    auto *literal = reinterpret_cast<uint64_t *>(code + 2);
    literal[0] = reinterpret_cast<uint64_t>(branch_addr);

    auto start = reinterpret_cast<uintptr_t>(code_addr);
    __builtin___clear_cache(reinterpret_cast<char *>(start),
                            reinterpret_cast<char *>(start + 24));
    return true;
}

// Public API

extern "C" {

bool safehook_initialize(void *lib_handle, uintptr_t lib_base, allocate_func allocator_func)
{
    if (!lib_handle) {
        LOGE("safehook_initialize: lib_handle is null!");
        return false;
    }

    g_library_handle = lib_handle;
    g_library_base = lib_base;
    g_allocator = allocator_func;

    LOGI("SafeHook initialized: base=0x%lx, allocator=%p",
         (unsigned long)lib_base, (void *)allocator_func);
    return true;
}

/*
 * Check if a function is "small" 鈥?too short for Dobby to place an
 * inline hook safely (less than 3 instructions before a branch/return).
 */
static bool is_small_function(void *address, int max_instr = 3) {
    auto *code = reinterpret_cast<uint32_t *>(address);
    for (int i = 0; i < max_instr; i++) {
        uint32_t instr = code[i];
        // Check for RET
        if ((instr & 0xFFFFFC1F) == 0xD65F0000) return true;
        // Check for B, BL, BR, BLR
        if ((instr & 0xFC000000) == 0x14000000) return true;  // B
        if ((instr & 0xFC000000) == 0x94000000) return true;  // BL
        if ((instr & 0xFFFFFC1F) == 0xD61F0000) return true;  // BR
        if ((instr & 0xFFFFFC1F) == 0xD63F0000) return true;  // BLR
    }
    return false;
}

static void *dobby_hook_internal(void *target, void *hook, bool use_near_branch)
{
    if (!target || !hook) return nullptr;

    void *original = nullptr;

    if (use_near_branch)
        dobby_enable_near_branch_trampoline();
    else
        dobby_disable_near_branch_trampoline();

    int ret = DobbyHook(target, hook, &original);

    dobby_disable_near_branch_trampoline();

    if (ret != 0) {
        LOGE("DobbyHook failed at %p: %d", target, ret);
        return nullptr;
    }
    return original;
}

void *safehook_create_hook(void *target_function, void *hook_function, bool use_bridge)
{
    std::lock_guard<std::mutex> guard(g_hook_mutex);
    (void)use_bridge;  // Bridge hook not implemented in this simplified version

    if (!target_function || !hook_function) {
        LOGE("safehook_create_hook: null parameter");
        return nullptr;
    }

    // Offset within the library
    uintptr_t rva = reinterpret_cast<uintptr_t>(target_function) - g_library_base;
    uintptr_t distance = reinterpret_cast<uintptr_t>(hook_function) -
                         reinterpret_cast<uintptr_t>(target_function);

    bool within_limits = (distance > 0 && distance < 0x7FFFFFFF) ||
                         (distance < 0 && distance > -0x7FFFFFFF);

    void *actual_hook = hook_function;

    // If function is NOT in the library (rva < 0), use standard Dobby
    if (rva < 0) {
        return dobby_hook_internal(target_function, actual_hook, within_limits);
    }

    if (within_limits) {
        return dobby_hook_internal(target_function, actual_hook, true);
    }

    if (!is_small_function(target_function)) {
        LOGI("Target at rva 0x%lx is long enough for Dobby", (unsigned long)rva);
        return dobby_hook_internal(target_function, actual_hook, false);
    }

    // Function is too small 鈥?need a trampoline
    LOGI("Target at rva 0x%lx is small, allocating trampoline", (unsigned long)rva);

    if (!g_allocator) {
        LOGI("No allocator, using Dobby fallback");
        return dobby_hook_internal(target_function, actual_hook, true);
    }

    void *trampoline = g_allocator(target_function,
                                    reinterpret_cast<void *>(g_library_base),
                                    kTrampolineSize);
    if (!trampoline) {
        LOGE("Failed to allocate trampoline, using Dobby fallback");
        return dobby_hook_internal(target_function, actual_hook, true);
    }

    if (!protect_trampoline(trampoline, kTrampolineSize, PROT_READ | PROT_WRITE)) {
        return dobby_hook_internal(target_function, actual_hook, true);
    }

    if (!emit_absolute_jump(trampoline, actual_hook)) {
        return dobby_hook_internal(target_function, actual_hook, true);
    }

    auto start = reinterpret_cast<uintptr_t>(trampoline);
    __builtin___clear_cache(reinterpret_cast<char *>(start),
                            reinterpret_cast<char *>(start + kTrampolineSize));

    if (!protect_trampoline(trampoline, kTrampolineSize, PROT_READ | PROT_EXEC)) {
        return dobby_hook_internal(target_function, actual_hook, true);
    }

    // Hook target 鈫?trampoline 鈫?hook
    return dobby_hook_internal(target_function, trampoline, true);
}

void safehook_destroy_hook(void *target)
{
    std::lock_guard<std::mutex> guard(g_hook_mutex);
    if (DobbyDestroy(target) != 0) {
        LOGE("Failed to destroy hook at %p", target);
    }
}

} /* extern "C" */
