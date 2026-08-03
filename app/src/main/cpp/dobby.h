/*
 * Dobby — ARM64 inline hook framework
 * https://github.com/jmpews/Dobby
 * MIT License
 */
#ifndef DOBBY_H
#define DOBBY_H

#ifdef __cplusplus
extern "C" {
#endif

#include <stdint.h>

// Hook a function. Returns 0 on success.
int DobbyHook(void* address, void* replace_call, void** origin_call);

// Remove a hook. Returns 0 on success.
int DobbyDestroy(void* address);

// Platform-specific: get the page size
int DobbyGetGlobalPageSize();

// Enable/disable near branch trampoline (ARM64)
void dobby_enable_near_branch_trampoline();
void dobby_disable_near_branch_trampoline();

#ifdef __cplusplus
}
#endif

#endif // DOBBY_H
