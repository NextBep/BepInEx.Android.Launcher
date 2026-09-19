#include "utilities/elf.h"

#include <elf.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstring>

namespace {

/* 按位宽分发的符号表扫描。所有偏移在解引用前都做了边界校验，
 * 因为输入可能来自下载或用户可写的目录。 */
template <typename Ehdr, typename Shdr, typename Sym>
uintptr_t scan(const uint8_t* base, size_t size, const char* target) {
    if (size < sizeof(Ehdr) || size < sizeof(Shdr)) return 0;

    const auto* ehdr = reinterpret_cast<const Ehdr*>(base);
    if (ehdr->e_shentsize != sizeof(Shdr) || ehdr->e_shoff == 0) return 0;
    if (ehdr->e_shoff > size - sizeof(Shdr)) return 0;

    size_t shnum = ehdr->e_shnum;
    if (shnum == 0) {
        /* 节数达到 SHN_LORESERVE 时真实值存放在第 0 节的 sh_size。 */
        shnum = reinterpret_cast<const Shdr*>(base + ehdr->e_shoff)->sh_size;
        if (shnum == 0) return 0;
    }
    if (shnum > (size - ehdr->e_shoff) / sizeof(Shdr)) return 0;

    const auto* shdrs = reinterpret_cast<const Shdr*>(base + ehdr->e_shoff);

    for (size_t i = 0; i < shnum; ++i) {
        const Shdr& sh = shdrs[i];
        if (sh.sh_type != SHT_SYMTAB && sh.sh_type != SHT_DYNSYM) continue;
        if (sh.sh_link >= shnum) continue;
        if (sh.sh_entsize != 0 && sh.sh_entsize != sizeof(Sym)) continue;
        if (sh.sh_offset > size || sh.sh_size > size - sh.sh_offset) continue;

        const Shdr& strs = shdrs[sh.sh_link];
        if (strs.sh_offset > size || strs.sh_size > size - strs.sh_offset) continue;

        const char* strtab = reinterpret_cast<const char*>(base + strs.sh_offset);
        const auto* syms = reinterpret_cast<const Sym*>(base + sh.sh_offset);
        const size_t count = sh.sh_size / sizeof(Sym);

        for (size_t j = 0; j < count; ++j) {
            if (syms[j].st_name >= strs.sh_size) continue;
            if (std::strcmp(strtab + syms[j].st_name, target) == 0) {
                return static_cast<uintptr_t>(syms[j].st_value);
            }
        }
    }
    return 0;
}

}  // namespace

uintptr_t get_rva_from_sym_image(const void* image, size_t size, const char* target_symbol) {
    if (image == nullptr || target_symbol == nullptr || size < EI_NIDENT) return 0;

    const auto* base = static_cast<const uint8_t*>(image);
    if (std::memcmp(base, ELFMAG, SELFMAG) != 0) return 0;

    /* 取文件自身声明的位宽，而不是编译目标位宽：被解析的库未必与调用方同架构。 */
    switch (base[EI_CLASS]) {
        case ELFCLASS64:
            return scan<Elf64_Ehdr, Elf64_Shdr, Elf64_Sym>(base, size, target_symbol);
        case ELFCLASS32:
            return scan<Elf32_Ehdr, Elf32_Shdr, Elf32_Sym>(base, size, target_symbol);
        default:
            return 0;
    }
}

uintptr_t get_rva_from_sym_file(const char* filepath, const char* target_symbol) {
    if (filepath == nullptr || target_symbol == nullptr) return 0;

    int fd = open(filepath, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;

    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size <= 0) {
        close(fd);
        return 0;
    }

    const size_t size = static_cast<size_t>(st.st_size);
    void* map = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (map == MAP_FAILED) return 0;

    const uintptr_t rva = get_rva_from_sym_image(map, size, target_symbol);
    munmap(map, size);
    return rva;
}
