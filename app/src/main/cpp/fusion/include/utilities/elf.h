#ifndef NEXTBEP_ELF_SYM_H
#define NEXTBEP_ELF_SYM_H

#include <stddef.h>
#include <stdint.h>

/* 在磁盘上的 ELF 文件中查找符号的链接时地址（即 RVA）。
 * 同时检索 .symtab 与 .dynsym，因此对未剥离的库也有效。 */
uintptr_t get_rva_from_sym_file(const char* filepath, const char* target_symbol);

/* 同上，但直接作用于一段已映射的 ELF 映像。 */
uintptr_t get_rva_from_sym_image(const void* image, size_t size, const char* target_symbol);

#endif  // NEXTBEP_ELF_SYM_H
