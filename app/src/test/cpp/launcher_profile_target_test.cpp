#include "runtime_profile_resolver.h"
#include <elf.h>
#include <cassert>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <vector>

// Exercise the production resolver with an ELF loaded by its real program headers.
int main(int argc, char** argv) {
    assert(argc == 2);
    std::ifstream input(argv[1], std::ios::binary);
    std::vector<uint8_t> file((std::istreambuf_iterator<char>(input)), {});
    assert(file.size() >= sizeof(Elf64_Ehdr));
    const auto* header = reinterpret_cast<const Elf64_Ehdr*>(file.data());
    assert(header->e_machine == EM_AARCH64);
    assert(header->e_phoff + header->e_phnum * sizeof(Elf64_Phdr) <= file.size());
    const auto* programs = reinterpret_cast<const Elf64_Phdr*>(file.data() + header->e_phoff);
    size_t span = 0;
    for (size_t i=0;i<header->e_phnum;++i) if (programs[i].p_type == PT_LOAD)
        span = std::max(span, size_t(programs[i].p_vaddr + programs[i].p_memsz));
    assert(span > 0 && span < 0x4000000);
    std::vector<uint8_t> mapped(span);
    for (size_t i=0;i<header->e_phnum;++i) if (programs[i].p_type == PT_LOAD) {
        assert(programs[i].p_offset + programs[i].p_filesz <= file.size());
        memcpy(mapped.data() + programs[i].p_vaddr, file.data() + programs[i].p_offset, programs[i].p_filesz);
    }
    assert(header->e_shoff + header->e_shnum * sizeof(Elf64_Shdr) <= file.size());
    const auto* sections = reinterpret_cast<const Elf64_Shdr*>(file.data() + header->e_shoff);
    uintptr_t entry = 0;
    for (size_t i=0;i<header->e_shnum;++i) if (sections[i].sh_type == SHT_DYNSYM) {
        const auto& section = sections[i];
        const auto& strings = sections[section.sh_link];
        assert(section.sh_offset + section.sh_size <= file.size());
        assert(strings.sh_offset + strings.sh_size <= file.size());
        const auto* symbols = reinterpret_cast<const Elf64_Sym*>(file.data() + section.sh_offset);
        for (size_t j=0;j<section.sh_size / sizeof(Elf64_Sym);++j) {
            if (strcmp(reinterpret_cast<const char*>(file.data()+strings.sh_offset+symbols[j].st_name), "app_entry_point") == 0)
                entry = symbols[j].st_value;
        }
    }
    assert(entry != 0 && entry + 48 <= mapped.size());
    using namespace miui_home_runtime_profile;
    ResolutionStorage storage{};
    ResolutionDiagnostics diagnostics{};
    const bool resolved = ResolveSideBoundaryProfile(mapped.data(), mapped.data()+entry, &storage, &diagnostics);
    printf("structural launcher: resolved=%d stage=%u side=%u runtime=%u rstring=%u entry=%zx side=%zx\n", resolved,
        unsigned(diagnostics.stage), diagnostics.side_candidate_count, diagnostics.runtime_confirmation_count,
        diagnostics.rstring_candidate_count, entry, diagnostics.side_handler_offset);
    fflush(stdout);
    assert(resolved && diagnostics.stage == ResolveStage::kComplete);
    assert(strcmp(storage.profile.id, "runtime-side-v1") == 0);
    assert(storage.profile.side_handler_offset > 0);
    const auto side = diagnostics.side_handler_offset;
    ResolutionStorage broadcast{};
    assert(ResolveBroadcastSupportProfile(mapped.data(), mapped.data()+entry, &broadcast));
    const auto broadcast_profile = broadcast.profile;
    assert(broadcast_profile.runtime_pointer_offset == storage.profile.runtime_pointer_offset);
    assert(broadcast_profile.rstring_vtable_offset == storage.profile.rstring_vtable_offset);
    assert(broadcast_profile.side_handler_offset == 0u);
    uint8_t saved_side[48];
    memcpy(saved_side, mapped.data()+side, sizeof(saved_side));
    memset(mapped.data()+side, 0, sizeof(saved_side));
    assert(ResolveBroadcastSupportProfile(mapped.data(), mapped.data()+entry, &broadcast));
    assert(broadcast.profile.runtime_pointer_offset == broadcast_profile.runtime_pointer_offset);
    assert(broadcast.profile.rstring_vtable_offset == broadcast_profile.rstring_vtable_offset);
    assert(!ResolveSideBoundaryProfile(mapped.data(), mapped.data()+entry, &storage, &diagnostics));
    assert(diagnostics.stage == ResolveStage::kRejectedSideBoundary);
    memcpy(mapped.data()+side, saved_side, sizeof(saved_side));
    memset(mapped.data()+entry, 0, 48);
    assert(!ResolveBroadcastSupportProfile(mapped.data(), mapped.data()+entry, &broadcast));
    assert(!ResolveSideBoundaryProfile(mapped.data(), mapped.data()+entry, &storage, &diagnostics));
    assert(diagnostics.stage == ResolveStage::kRejectedElf);
}
