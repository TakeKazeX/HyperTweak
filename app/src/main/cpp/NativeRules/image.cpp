// SPDX-License-Identifier: Apache-2.0
#include "image.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

namespace hypertweak::native {
namespace {

constexpr size_t kMaxProgramHeaders = 128u;
constexpr size_t kMaxDynamicEntries = 4096u;

bool AddWouldOverflow(uintptr_t left, uintptr_t right) {
    return right > UINTPTR_MAX - left;
}

const char* BaseName(const char* path) {
    if (path == nullptr) return nullptr;
    const char* slash = strrchr(path, '/');
    return slash == nullptr ? path : slash + 1;
}

struct FindImageRequest {
    const char* path;
    uintptr_t base;
    Image result;
    size_t matches;
};

int FindImageCallback(dl_phdr_info* info, size_t, void* opaque) {
    auto* request = static_cast<FindImageRequest*>(opaque);
    const char* mapped_path = info->dlpi_name;
    bool matches = false;
    if (request->base != 0u) {
        matches = static_cast<uintptr_t>(info->dlpi_addr) == request->base;
    } else if (request->path != nullptr && mapped_path != nullptr) {
        if (strchr(request->path, '/') != nullptr) {
            matches = strcmp(request->path, mapped_path) == 0;
        } else {
            matches = strcmp(request->path, BaseName(mapped_path)) == 0;
        }
    }
    if (!matches) return 0;
    ++request->matches;
    if (request->matches != 1u || info->dlpi_phnum == 0u ||
        info->dlpi_phnum > kMaxProgramHeaders) {
        return 0;
    }
    Image& image = request->result;
    image.base = static_cast<uintptr_t>(info->dlpi_addr);
    image.phnum = info->dlpi_phnum;
    image.phdr = info->dlpi_phdr;
    if (mapped_path != nullptr) {
        const size_t length = strlen(mapped_path);
        if (length >= sizeof(image.path)) return 0;
        memcpy(image.path, mapped_path, length + 1u);
    }
    // Only linker-owned program headers are read here. Dynamic tables and hash
    // pointers are deliberately left alone until BuildDynamicView validates
    // them against these segments.
    for (size_t index = 0u; index < image.phnum; ++index) {
        const ElfW(Phdr)& header = image.phdr[index];
        if (header.p_type != PT_LOAD || header.p_memsz == 0u) continue;
        if (image.segment_count >= kMaxImageSegments) break;
        if (AddWouldOverflow(image.base, header.p_vaddr)) continue;
        const uintptr_t begin = image.base + header.p_vaddr;
        if (AddWouldOverflow(begin, header.p_memsz)) continue;
        image.segments[image.segment_count++] = {
            begin, begin + header.p_memsz, header.p_flags};
    }
    return 0;
}

// Reads the SYSV hash table's `nchain`, which is the exact symbol count. Only
// the two leading 32-bit header fields are touched, and only after the range
// has been validated.
bool ReadSysvSymbolCount(const Image& image, uintptr_t address, size_t* output) {
    if (!Contains(image, address, 2u * sizeof(uint32_t), PF_R)) return false;
    const auto* header = reinterpret_cast<const uint32_t*>(address);
    const uint32_t count = header[1];
    if (count == 0u || count > kMaxSymbolCount) return false;
    *output = count;
    return true;
}

// Walks the GNU hash bucket/chain arrays to recover the highest symbol index.
// Every read below is range-checked, and the walk is capped, so a malformed
// table fails the resolution instead of running off the mapping.
bool ReadGnuSymbolCount(const Image& image, uintptr_t address, size_t* output) {
    if (!Contains(image, address, 4u * sizeof(uint32_t), PF_R)) return false;
    const auto* header = reinterpret_cast<const uint32_t*>(address);
    const uint32_t bucket_count = header[0];
    const uint32_t symbol_offset = header[1];
    const uint32_t bloom_count = header[2];
    if (bucket_count == 0u || bloom_count == 0u ||
        bucket_count > kMaxSymbolCount || bloom_count > kMaxSymbolCount) {
        return false;
    }
    uintptr_t buckets = address + 4u * sizeof(uint32_t);
    const size_t bloom_bytes = static_cast<size_t>(bloom_count) * sizeof(ElfW(Addr));
    if (AddWouldOverflow(buckets, bloom_bytes)) return false;
    buckets += bloom_bytes;
    const size_t bucket_bytes = static_cast<size_t>(bucket_count) * sizeof(uint32_t);
    if (AddWouldOverflow(buckets, bucket_bytes)) return false;
    if (!Contains(image, buckets, bucket_bytes, PF_R)) return false;
    const auto* bucket_values = reinterpret_cast<const uint32_t*>(buckets);
    const uintptr_t chains = buckets + bucket_bytes;
    uint32_t maximum = symbol_offset;
    for (uint32_t index = 0u; index < bucket_count; ++index) {
        uint32_t symbol = bucket_values[index];
        if (symbol < symbol_offset) continue;
        if (symbol >= kMaxSymbolCount) return false;
        while (true) {
            const size_t chain_index = static_cast<size_t>(symbol - symbol_offset);
            if (chain_index > (UINTPTR_MAX - chains) / sizeof(uint32_t)) return false;
            const uintptr_t chain_address = chains + chain_index * sizeof(uint32_t);
            if (!Contains(image, chain_address, sizeof(uint32_t), PF_R)) return false;
            const uint32_t chain = *reinterpret_cast<const uint32_t*>(chain_address);
            if (symbol >= maximum) maximum = symbol + 1u;
            if ((chain & 1u) != 0u) break;
            if (++symbol >= kMaxSymbolCount) return false;
        }
    }
    if (maximum == 0u) return false;
    *output = maximum;
    return true;
}

constexpr size_t kMaxMapsLine = 512u;
// A library's build-id note sits within its first page, so probing one page per
// mapping is enough to recognise an object.
constexpr size_t kMapsProbeBytes = 0x1000u;
// The launcher maps several thousand readable regions, so the table is sized
// well above that and allocated on the heap rather than the stack.
constexpr size_t kMaxMappedRanges = 16384u;

struct MappedRange {
    uintptr_t begin;
    uintptr_t end;
    bool readable;
    // True when the pathname names an ELF container (`.so` or `.apk`). Other
    // readable mappings - device nodes, dma-buf heaps, anonymous regions - can
    // report as readable yet fault on access, so their contents are never read.
    bool elf_backed;
};

struct RangeTable {
    MappedRange* ranges;
    size_t count;
    size_t capacity;
};

bool HasElfSuffix(const char* path, size_t length) {
    // Only the containers this payload ever looks inside. Widening this set
    // only adds pages to fault in, and every suffix here must name a regular
    // file whose mapped pages are safe to read.
    constexpr const char* kSuffixes[] = {".so", ".apk"};
    for (const char* suffix : kSuffixes) {
        const size_t suffix_length = strlen(suffix);
        if (length > suffix_length &&
            memcmp(path + length - suffix_length, suffix, suffix_length) == 0) {
            return true;
        }
    }
    return false;
}

// Parses the address range and permissions of one /proc/self/maps line, and
// reports the pathname when the caller asks for it.
bool ParseMapsLine(const char* line, MappedRange* range, char* path, size_t path_size) {
    char buffer[kMaxMapsLine];
    const size_t length = strlen(line);
    if (length >= sizeof(buffer)) return false;
    memcpy(buffer, line, length + 1u);
    char* cursor = buffer;
    char* dash = strchr(cursor, '-');
    if (dash == nullptr) return false;
    *dash = '\0';
    char* end_ptr = nullptr;
    const unsigned long long begin = strtoull(cursor, &end_ptr, 16);
    if (end_ptr == cursor) return false;
    cursor = dash + 1;
    const unsigned long long end = strtoull(cursor, &end_ptr, 16);
    if (end_ptr == cursor || end <= begin) return false;
    const char* perms = strchr(end_ptr, ' ');
    if (perms == nullptr || strlen(perms) < 5u) return false;
    ++perms;
    range->begin = static_cast<uintptr_t>(begin);
    range->end = static_cast<uintptr_t>(end);
    range->readable = perms[0] == 'r';
    range->elf_backed = false;
    if (path != nullptr && path_size != 0u) path[0] = '\0';
    // Skip the offset, device and inode fields, then take the pathname. The
    // classification below is independent of whether the caller wants the path,
    // because the range table is filtered on it.
    const char* rest = perms + 4;
    for (int field = 0; field < 3; ++field) {
        while (*rest == ' ') ++rest;
        while (*rest != '\0' && *rest != ' ') ++rest;
    }
    while (*rest == ' ') ++rest;
    if (*rest != '/') return true;
    const size_t path_length = strlen(rest);
    range->elf_backed = HasElfSuffix(rest, path_length);
    if (path == nullptr || path_size == 0u) return true;
    if (path_length >= path_size) return false;
    memcpy(path, rest, path_length + 1u);
    return true;
}

void FreeRangeTable(RangeTable* table) {
    if (table == nullptr) return;
    free(table->ranges);
    table->ranges = nullptr;
    table->count = 0u;
    table->capacity = 0u;
}

// Reads the process's mapping table. Only readable regions are kept: they are
// the only ones this payload may inspect.
bool LoadRangeTable(RangeTable* table) {
    if (table == nullptr) return false;
    table->ranges = static_cast<MappedRange*>(calloc(kMaxMappedRanges, sizeof(MappedRange)));
    if (table->ranges == nullptr) return false;
    table->capacity = kMaxMappedRanges;
    table->count = 0u;
    FILE* maps = fopen("/proc/self/maps", "re");
    if (maps == nullptr) {
        FreeRangeTable(table);
        return false;
    }
    char line[kMaxMapsLine];
    while (fgets(line, sizeof(line), maps) != nullptr) {
        size_t length = strlen(line);
        while (length > 0u && (line[length - 1u] == '\n' || line[length - 1u] == '\r')) {
            line[--length] = '\0';
        }
        MappedRange range {};
        if (!ParseMapsLine(line, &range, nullptr, 0u)) continue;
        if (!range.readable) continue;
        if (table->count >= table->capacity) break;
        table->ranges[table->count++] = range;
    }
    fclose(maps);
    return table->count != 0u;
}

// True when every byte of [address, address + size) is inside some readable
// mapping. A single segment is routinely split across several VMAs - a library
// mapped out of an APK has its executable range broken up on APK page
// boundaries - so coverage is accumulated across ranges rather than required
// from one.
bool TableContains(const RangeTable& table, uintptr_t address, size_t size) {
    if (size == 0u || AddWouldOverflow(address, size)) return false;
    const uintptr_t end = address + size;
    uintptr_t covered = address;
    while (covered < end) {
        uintptr_t next = covered;
        for (size_t index = 0u; index < table.count; ++index) {
            const MappedRange& range = table.ranges[index];
            if (range.begin <= covered && covered < range.end && range.end > next) {
                next = range.end;
            }
        }
        if (next == covered) return false;
        covered = next;
    }
    return true;
}

// Reads the ELF headers mapped at `base` and derives the PT_LOAD segment table.
// Each segment must be fully covered by a readable mapping in `table`, so a
// stale or unrelated address can never produce a usable image.
bool BuildSegmentsFromTable(uintptr_t base, const RangeTable& table, Image* output) {
    if (output == nullptr || base == 0u) return false;
    ElfW(Ehdr) header;
    if (!TableContains(table, base, sizeof(header))) return false;
    memcpy(&header, reinterpret_cast<const void*>(base), sizeof(header));
    if (memcmp(header.e_ident, ELFMAG, SELFMAG) != 0 ||
        header.e_ident[EI_CLASS] != ELFCLASS64 ||
        header.e_ident[EI_DATA] != ELFDATA2LSB ||
        header.e_type != ET_DYN || header.e_machine != EM_AARCH64 ||
        header.e_phentsize != sizeof(ElfW(Phdr)) || header.e_phnum == 0u ||
        header.e_phnum > kMaxProgramHeaders) {
        return false;
    }
    const size_t phdr_size = static_cast<size_t>(header.e_phnum) * sizeof(ElfW(Phdr));
    if (AddWouldOverflow(base, header.e_phoff)) return false;
    const uintptr_t phdr_address = base + header.e_phoff;
    if (!TableContains(table, phdr_address, phdr_size)) return false;
    const auto* phdrs = reinterpret_cast<const ElfW(Phdr)*>(phdr_address);

    Image image {};
    image.base = base;
    image.phnum = header.e_phnum;
    image.phdr = phdrs;
    for (size_t index = 0u; index < image.phnum; ++index) {
        const ElfW(Phdr)& entry = phdrs[index];
        if (entry.p_type != PT_LOAD || entry.p_memsz == 0u) continue;
        if (image.segment_count >= kMaxImageSegments) break;
        if (AddWouldOverflow(base, entry.p_vaddr)) continue;
        const uintptr_t begin = base + entry.p_vaddr;
        if (!TableContains(table, begin, entry.p_memsz)) continue;
        image.segments[image.segment_count++] = {
            begin, begin + entry.p_memsz, entry.p_flags};
    }
    if (image.segment_count == 0u) return false;
    *output = image;
    return true;
}

}  // namespace

bool FindImageViaMaps(const char* path, Image* output) {
    if (path == nullptr || output == nullptr || path[0] != '/') return false;
    RangeTable table {};
    if (!LoadRangeTable(&table)) return false;
    uintptr_t base = 0u;
    FILE* maps = fopen("/proc/self/maps", "re");
    if (maps != nullptr) {
        char line[kMaxMapsLine];
        char mapped[kMaxImagePathLength];
        while (fgets(line, sizeof(line), maps) != nullptr) {
            size_t length = strlen(line);
            while (length > 0u && (line[length - 1u] == '\n' || line[length - 1u] == '\r')) {
                line[--length] = '\0';
            }
            MappedRange range {};
            if (!ParseMapsLine(line, &range, mapped, sizeof(mapped))) continue;
            if (mapped[0] == '\0' || strcmp(mapped, path) != 0) continue;
            if (base == 0u || range.begin < base) base = range.begin;
        }
        fclose(maps);
    }
    bool built = false;
    if (base != 0u && BuildSegmentsFromTable(base, table, output)) {
        const size_t length = strlen(path);
        if (length < sizeof(output->path)) memcpy(output->path, path, length + 1u);
        built = true;
    }
    FreeRangeTable(&table);
    return built;
}

bool FindImageByBuildId(const uint8_t* build_id, size_t build_id_size,
                        uintptr_t build_id_va, Image* output) {
    if (build_id == nullptr || build_id_size == 0u || output == nullptr) return false;
    if (build_id_va + build_id_size > kMapsProbeBytes) return false;
    RangeTable table {};
    if (!LoadRangeTable(&table)) return false;
    uintptr_t base = 0u;
    for (size_t index = 0u; index < table.count; ++index) {
        const MappedRange& range = table.ranges[index];
        if (!range.elf_backed) continue;
        if (range.end - range.begin < kMapsProbeBytes) continue;
        const uintptr_t note = range.begin + build_id_va;
        if (note + build_id_size > range.end) continue;
        if (memcmp(reinterpret_cast<const void*>(note), build_id, build_id_size) != 0) {
            continue;
        }
        base = range.begin;
        break;
    }
    const bool built = base != 0u && BuildSegmentsFromTable(base, table, output);
    FreeRangeTable(&table);
    return built;
}

size_t PageSize() {
    static size_t value = 0u;
    if (value == 0u) {
        const long queried = sysconf(_SC_PAGESIZE);
        value = queried > 0 ? static_cast<size_t>(queried) : size_t{4096};
    }
    return value;
}

uintptr_t PageStart(uintptr_t address) {
    return address & ~(static_cast<uintptr_t>(PageSize()) - 1u);
}

bool FindImage(const char* path, uintptr_t base, Image* output) {
    if (output == nullptr || (path == nullptr && base == 0u)) return false;
    FindImageRequest request{path, base, {}, 0u};
    dl_iterate_phdr(FindImageCallback, &request);
    if (request.matches != 1u || request.result.segment_count == 0u) return false;
    *output = request.result;
    return true;
}

bool Contains(const Image& image, uintptr_t address, size_t size,
              uint32_t required_flags) {
    if (size == 0u || AddWouldOverflow(address, size)) return false;
    const uintptr_t end = address + size;
    for (size_t index = 0u; index < image.segment_count; ++index) {
        const Segment& segment = image.segments[index];
        if (address >= segment.begin && end <= segment.end &&
            (segment.flags & required_flags) == required_flags) {
            return true;
        }
    }
    return false;
}

bool ReadBytes(const Image& image, uintptr_t address, void* output, size_t size,
               uint32_t required_flags) {
    if (output == nullptr || !Contains(image, address, size, required_flags)) {
        return false;
    }
    memcpy(output, reinterpret_cast<const void*>(address), size);
    return true;
}

bool MatchesBytes(const Image& image, uintptr_t address, const uint8_t* expected,
                  size_t size) {
    if (expected == nullptr || size == 0u) return false;
    if (!Contains(image, address, size, PF_R)) return false;
    return memcmp(reinterpret_cast<const void*>(address), expected, size) == 0;
}

uintptr_t RuntimeAddress(const Image& image, ElfW(Addr) value) {
    if (value == 0u) return 0u;
    const uintptr_t address = static_cast<uintptr_t>(value);
    if (Contains(image, address, 1u)) return address;
    if (AddWouldOverflow(image.base, address)) return 0u;
    const uintptr_t relocated = image.base + address;
    return Contains(image, relocated, 1u) ? relocated : 0u;
}

bool BuildDynamicView(const Image& image, DynamicView* output) {
    if (output == nullptr) return false;
    uintptr_t dynamic_address = 0u;
    size_t dynamic_count = 0u;
    for (size_t index = 0u; index < image.phnum; ++index) {
        const ElfW(Phdr)& header = image.phdr[index];
        if (header.p_type != PT_DYNAMIC || header.p_memsz < sizeof(ElfW(Dyn))) {
            continue;
        }
        if (AddWouldOverflow(image.base, header.p_vaddr)) continue;
        dynamic_count = header.p_memsz / sizeof(ElfW(Dyn));
        if (dynamic_count > kMaxDynamicEntries) dynamic_count = kMaxDynamicEntries;
        dynamic_address = image.base + header.p_vaddr;
        break;
    }
    if (dynamic_address == 0u || dynamic_count == 0u ||
        !Contains(image, dynamic_address, dynamic_count * sizeof(ElfW(Dyn)), PF_R)) {
        return false;
    }

    uintptr_t symbols = 0u;
    uintptr_t strings = 0u;
    uintptr_t sysv_hash = 0u;
    uintptr_t gnu_hash = 0u;
    uintptr_t plt_rela = 0u;
    size_t plt_size = 0u;
    uintptr_t dyn_rela = 0u;
    size_t dyn_rela_size = 0u;
    size_t string_size = 0u;
    bool plt_is_rela = false;
    const auto* dynamic = reinterpret_cast<const ElfW(Dyn)*>(dynamic_address);
    for (size_t index = 0u; index < dynamic_count; ++index) {
        const ElfW(Dyn)& entry = dynamic[index];
        if (entry.d_tag == DT_NULL) break;
        switch (entry.d_tag) {
            case DT_SYMTAB: symbols = RuntimeAddress(image, entry.d_un.d_ptr); break;
            case DT_STRTAB: strings = RuntimeAddress(image, entry.d_un.d_ptr); break;
            case DT_STRSZ: string_size = entry.d_un.d_val; break;
            case DT_HASH: sysv_hash = RuntimeAddress(image, entry.d_un.d_ptr); break;
            case DT_GNU_HASH: gnu_hash = RuntimeAddress(image, entry.d_un.d_ptr); break;
            case DT_JMPREL: plt_rela = RuntimeAddress(image, entry.d_un.d_ptr); break;
            case DT_PLTRELSZ: plt_size = entry.d_un.d_val; break;
            case DT_PLTREL: plt_is_rela = entry.d_un.d_val == DT_RELA; break;
            case DT_RELA: dyn_rela = RuntimeAddress(image, entry.d_un.d_ptr); break;
            case DT_RELASZ: dyn_rela_size = entry.d_un.d_val; break;
            default: break;
        }
    }

    // The symbol count is only ever a scan bound. Prefer the SYSV hash when it
    // is present and sane, otherwise derive it from the GNU hash chain walk;
    // when neither is usable, fall back to the symbol table's own segment.
    size_t symbol_count = 0u;
    if (sysv_hash != 0u && ReadSysvSymbolCount(image, sysv_hash, &symbol_count)) {
        // Exact count from DT_HASH.
    } else if (gnu_hash != 0u &&
               ReadGnuSymbolCount(image, gnu_hash, &symbol_count)) {
        // Exact count from the GNU hash walk.
    } else {
        symbol_count = 0u;
    }
    if (symbols == 0u || strings == 0u || string_size == 0u) return false;
    if (symbol_count == 0u) {
        for (size_t index = 0u; index < image.segment_count; ++index) {
            const Segment& segment = image.segments[index];
            if (symbols < segment.begin || symbols >= segment.end) continue;
            if ((segment.flags & PF_R) == 0u) return false;
            symbol_count = (segment.end - symbols) / sizeof(ElfW(Sym));
            break;
        }
        if (symbol_count > kMaxSymbolCount) symbol_count = kMaxSymbolCount;
    }
    if (symbol_count == 0u ||
        !Contains(image, symbols, symbol_count * sizeof(ElfW(Sym)), PF_R) ||
        !Contains(image, strings, string_size, PF_R)) {
        return false;
    }

    DynamicView view{};
    view.symbols = reinterpret_cast<const ElfW(Sym)*>(symbols);
    view.symbol_count = symbol_count;
    view.strings = reinterpret_cast<const char*>(strings);
    view.string_size = string_size;
    if (plt_rela != 0u && plt_size != 0u && plt_is_rela &&
        plt_size % sizeof(ElfW(Rela)) == 0u &&
        Contains(image, plt_rela, plt_size, PF_R)) {
        view.plt_rela = reinterpret_cast<const ElfW(Rela)*>(plt_rela);
        view.plt_rela_count = plt_size / sizeof(ElfW(Rela));
    }
    if (dyn_rela != 0u && dyn_rela_size != 0u &&
        dyn_rela_size % sizeof(ElfW(Rela)) == 0u &&
        Contains(image, dyn_rela, dyn_rela_size, PF_R)) {
        view.dyn_rela = reinterpret_cast<const ElfW(Rela)*>(dyn_rela);
        view.dyn_rela_count = dyn_rela_size / sizeof(ElfW(Rela));
    }
    *output = view;
    return true;
}

bool SymbolNameEquals(const DynamicView& view, uint32_t symbol_index,
                      const char* expected) {
    if (expected == nullptr || symbol_index >= view.symbol_count) return false;
    const uint32_t offset = view.symbols[symbol_index].st_name;
    if (offset >= view.string_size) return false;
    const char* name = view.strings + offset;
    const size_t remaining = view.string_size - offset;
    const size_t length = strnlen(name, remaining);
    if (length >= remaining) return false;
    return strlen(expected) == length && memcmp(name, expected, length) == 0;
}

void* SymbolLookup(const Image& image, const char* name, size_t* size) {
    if (name == nullptr) return nullptr;
    DynamicView view{};
    if (!BuildDynamicView(image, &view)) return nullptr;
    for (size_t index = 0u; index < view.symbol_count; ++index) {
        const uint32_t symbol_index = static_cast<uint32_t>(index);
        if (!SymbolNameEquals(view, symbol_index, name)) continue;
        const ElfW(Sym)& symbol = view.symbols[index];
        if (symbol.st_shndx == SHN_UNDEF || symbol.st_value == 0u) return nullptr;
        const uintptr_t address = RuntimeAddress(image, symbol.st_value);
        if (address == 0u) return nullptr;
        if (size != nullptr) *size = symbol.st_size;
        return reinterpret_cast<void*>(address);
    }
    return nullptr;
}

}  // namespace hypertweak::native
