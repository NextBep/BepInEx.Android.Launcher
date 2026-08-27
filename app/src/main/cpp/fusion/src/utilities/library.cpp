/*
 * BepInEx.Android — ELF padding / code cave injection
 * Ported from FusionCore main branch (fusion/src/utilities/library.cpp)
 */

#include <dlfcn.h>
#include <fstream>
#include <elf.h>
#include <unistd.h>
#include <cstring>
#include <android/log.h>

#define TAG "LibraryUtils"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#if defined(__aarch64__)
using Elf_Ehdr_t = Elf64_Ehdr;
using Elf_Phdr_t = Elf64_Phdr;
using Elf_Addr_t  = Elf64_Addr;
using Elf_Xword_t = Elf64_Xword;
#else
using Elf_Ehdr_t = Elf32_Ehdr;
using Elf_Phdr_t = Elf32_Phdr;
using Elf_Addr_t  = Elf32_Addr;
using Elf_Xword_t = Elf32_Xword;
#endif

struct PaddedOpenResult {
    void *handle;
    void *base;
    size_t pool_base;
    size_t pool_size;
};

static PaddedOpenResult padded_write_elf_impl(const char *library_name,
                                              const char *temp_path,
                                              size_t pool_size,
                                              bool load_now)
{
    auto page_size = sysconf(_SC_PAGESIZE);

    auto align_up = [](Elf_Addr_t addr, Elf_Xword_t align) {
        return (addr + align - 1) & ~(align - 1);
    };

    std::ifstream file(library_name, std::ios::binary);
    if (!file) {
        LOGE("Failed to open file: %s", library_name);
        return {nullptr, nullptr, 0, 0};
    }

    // Read ELF header
    Elf_Ehdr_t elf_header{};
    file.read(reinterpret_cast<char *>(&elf_header), sizeof(elf_header));
    if (file.gcount() != sizeof(elf_header) || elf_header.e_type != ET_DYN) {
        LOGE("Invalid ELF file: %s", library_name);
        return {nullptr, nullptr, 0, 0};
    }

    // Read program headers
    file.seekg(static_cast<long long>(elf_header.e_phoff), std::ios::beg);
    auto phdrs = new Elf_Phdr_t[elf_header.e_phnum];
    file.read(reinterpret_cast<char *>(phdrs),
              elf_header.e_phnum * sizeof(Elf_Phdr_t));
    if (file.gcount() != static_cast<std::streamsize>(
            elf_header.e_phnum * sizeof(Elf_Phdr_t))) {
        LOGE("Failed to read PHDRs from file: %s", library_name);
        delete[] phdrs;
        return {nullptr, nullptr, 0, 0};
    }

    // Find the last program segment and the base vaddr
    Elf_Phdr_t *last_phdr = &phdrs[0];
    Elf_Addr_t base_vaddr = 0xFFFFFFFF;

    for (int i = 0; i < elf_header.e_phnum; ++i) {
        const auto &ph = phdrs[i];
        if (ph.p_type == PT_LOAD) {
            if (ph.p_vaddr < base_vaddr)
                base_vaddr = ph.p_vaddr;
            if (ph.p_vaddr + ph.p_memsz > last_phdr->p_vaddr + last_phdr->p_memsz)
                last_phdr = &phdrs[i];
        }
    }

    // Calculate pool offset (end of last segment, page-aligned)
    Elf_Addr_t segment_end = last_phdr->p_vaddr + last_phdr->p_memsz;
    segment_end = align_up(segment_end, page_size);
    Elf_Addr_t pool_offset = segment_end - base_vaddr;

    // Pad the last segment
    last_phdr->p_memsz = align_up(last_phdr->p_memsz + pool_size, page_size);
    Elf_Addr_t new_segment_end = last_phdr->p_vaddr + last_phdr->p_memsz;
    size_t new_pool_size = new_segment_end - segment_end;

    // Write the patched ELF to temp file
    std::ofstream temp_file(temp_path, std::ios::binary);
    if (!temp_file) {
        LOGE("Failed to open temp file: %s", temp_path);
        delete[] phdrs;
        return {nullptr, nullptr, 0, 0};
    }

    // Copy original contents
    file.seekg(0, std::ios::beg);
    auto buffer = new char[page_size];
    while (file.read(buffer, page_size))
        temp_file.write(buffer, page_size);
    if (file.gcount() > 0)
        temp_file.write(buffer, file.gcount());
    delete[] buffer;

    // Write updated program headers
    temp_file.seekp(elf_header.e_phoff, std::ios::beg);
    temp_file.write(reinterpret_cast<char *>(phdrs),
                    elf_header.e_phnum * sizeof(Elf_Phdr_t));
    delete[] phdrs;

    temp_file.close();
    file.close();

    if (!load_now) {
        LOGI("Patched ELF written to %s (deferred load), pool_offset=0x%zx size=%zu",
             temp_path, (size_t)pool_offset, new_pool_size);
        return {nullptr, nullptr, (size_t)pool_offset, new_pool_size};
    }

    // Load the patched ELF
    void *handle = dlopen(temp_path, RTLD_GLOBAL | RTLD_NOW);
    if (!handle) {
        LOGE("dlopen failed for %s: %s", temp_path, dlerror());
        return {nullptr, nullptr, 0, 0};
    }

    // Resolve base address via dladdr
    Dl_info info{};
    void *sym = dlsym(handle, "il2cpp_init");
    if (!sym || !dladdr(sym, &info) || !info.dli_fbase) {
        LOGE("dladdr(il2cpp_init) failed for %s", temp_path);
        return {nullptr, nullptr, 0, 0};
    }
    size_t trampoline_base =
        reinterpret_cast<uintptr_t>(info.dli_fbase) + pool_offset;

    LOGI("Padded ELF: pool at 0x%zx, size %zu",
         trampoline_base, new_pool_size);

    return {handle, info.dli_fbase, trampoline_base, new_pool_size};
}

PaddedOpenResult padded_write_elf(const char *library_name,
                                  const char *temp_path,
                                  size_t pool_size)
{
    return padded_write_elf_impl(library_name, temp_path, pool_size, false);
}

PaddedOpenResult padded_dlopen(const char *library_name,
                               const char *temp_path,
                               size_t pool_size)
{
    return padded_write_elf_impl(library_name, temp_path, pool_size, true);
}
