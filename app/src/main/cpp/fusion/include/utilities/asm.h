// Copyright (c) 2026 XtraCube
#ifndef NEXTBEP_ASM_H
#define NEXTBEP_ASM_H

#include <cstdint>

inline uintptr_t align_down(uintptr_t addr, size_t page_size)
{
    return addr & ~(static_cast<uintptr_t>(page_size) - 1);
}

inline uintptr_t align_up(uintptr_t addr, size_t page_size)
{
    return (addr + page_size - 1) & ~(static_cast<uintptr_t>(page_size) - 1);
}

// Emit an absolute jump to the branch address at code address.
// The code address should be aligned for the architecture.
// Returns false if the code address is not properly aligned
// or null pointers are provided.
// ------------------------------
// Memory layout for ARM64:
// 0x00: LDR X16, [PC, #8]      ; Load the branch address into X16
// 0x04: BR X16                 ; Jump to the branch address
// 0x08: .quad branch_address   ; The address of the branch target
// ------------------------------
// Memory layout for ARM32:
// 0x00: LDR PC, [PC, #-4]      ; Load the branch address into PC
// 0x04: .word branch_address   ; The address of the branch target
// ------------------------------
bool emit_absolute_jump(void *code_address, void *branch_address);

// Tries to resolve an inner branch for a function at the given address,
// following branches up to max_depth times. Useful for resolving
// thunks or small wrapper functions that simply jump to another function.
void *resolve_inner_branch(void *func_addr, int max_depth = 1);

// Check for B instruction
bool is_b(uint32_t instr);

// Check for BL instruction
bool is_bl(uint32_t instr);

// Check for BR instruction
bool is_br(uint32_t instr);

// Check for B.cond instruction
bool is_conditional_branch(uint32_t instr);

// Check for unconditional branch (B)
bool is_unconditional_branch(uint32_t instr);

// Check for call instructions (BL and BLR)
bool is_call(uint32_t instr);

// Check for compare and branch instructions (CBZ, CBNZ)
bool is_compare_branch(uint32_t instr);

// Check for test and branch instructions (TBZ, TBNZ)
bool is_test_branch(uint32_t instr);

// Check for RET instruction
bool is_ret(uint32_t instr);

// Check for any branch instruction
bool is_branch(uint32_t instr);

// Check for trap or exit instructions (SVC, HVC, SMC, BRK)
bool is_trap_or_exit(uint32_t instr);

// Check if the function at the given address is "small" (i.e., has fewer than max_instr instructions before a return or branch)
bool is_small_function(void *address, int max_instr = 3);

#endif //NEXTBEP_ASM_H
