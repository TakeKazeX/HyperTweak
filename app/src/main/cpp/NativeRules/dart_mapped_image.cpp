#include "dart_mapped_image.h"
#include "launcher_process_identity.h"

#include <dlfcn.h>
#include <elf.h>
#include <string.h>

namespace hypertweak::native::mapped {
bool MatchesCode(const uint8_t* base, uintptr_t offset,
                 const uint8_t* expected, size_t expected_size) {
    return base != nullptr && expected != nullptr && expected_size != 0u &&
            memcmp(base + offset, expected, expected_size) == 0;
}

bool DartMappedRangeHasFlags(const uint8_t* base, uintptr_t offset,
                             size_t size, uint32_t required_flags) {
    if (base == nullptr || offset == 0u || size == 0u ||
            offset > UINTPTR_MAX - size) {
        return false;
    }
    const auto* header = reinterpret_cast<const Elf64_Ehdr*>(base);
    if (memcmp(header->e_ident, ELFMAG, SELFMAG) != 0 ||
            header->e_ident[EI_CLASS] != ELFCLASS64 ||
            header->e_ident[EI_DATA] != ELFDATA2LSB ||
            header->e_type != ET_DYN || header->e_machine != EM_AARCH64 ||
            header->e_phentsize != sizeof(Elf64_Phdr) ||
            header->e_phnum == 0u || header->e_phnum > 128u ||
            header->e_phoff > 0x10000u) {
        return false;
    }
    const auto* programs = reinterpret_cast<const Elf64_Phdr*>(
            base + header->e_phoff);
    const uintptr_t end = offset + size;
    for (uint16_t index = 0u; index < header->e_phnum; ++index) {
        const Elf64_Phdr& program = programs[index];
        if (program.p_type != PT_LOAD ||
                (program.p_flags & required_flags) != required_flags ||
                program.p_vaddr > UINTPTR_MAX - program.p_memsz) {
            continue;
        }
        const uintptr_t segment_start =
                static_cast<uintptr_t>(program.p_vaddr);
        const uintptr_t segment_end = segment_start +
                static_cast<uintptr_t>(program.p_memsz);
        if (offset >= segment_start && end <= segment_end) return true;
    }
    return false;
}

bool DartBlTargets(const uint8_t* base, uintptr_t instruction_offset,
                   uintptr_t expected_target) {
    if (!DartMappedRangeHasFlags(base, instruction_offset, sizeof(uint32_t),
                                 PF_R | PF_X)) {
        return false;
    }
    uint32_t instruction = 0u;
    memcpy(&instruction, base + instruction_offset, sizeof(instruction));
    if ((instruction & 0xfc000000u) != 0x94000000u) return false;
    int64_t immediate = static_cast<int64_t>(instruction & 0x03ffffffu);
    if ((immediate & (int64_t{1} << 25u)) != 0) {
        immediate -= int64_t{1} << 26u;
    }
    const int64_t target = static_cast<int64_t>(instruction_offset) +
            immediate * 4;
    return target >= 0 && static_cast<uintptr_t>(target) == expected_target;
}

bool MatchesLauncherProfile(
        const uint8_t* base, void* app_entry_point,
        const miui_home_profiles::LauncherProfile& profile) {
    if (base == nullptr || app_entry_point != base + profile.entry_offset ||
            profile.identity_fingerprints == nullptr ||
            profile.identity_fingerprint_count == 0u) {
        return false;
    }
    for (size_t index = 0u; index < profile.identity_fingerprint_count;
         ++index) {
        const auto& fingerprint = profile.identity_fingerprints[index];
        if (!MatchesCode(base, fingerprint.offset, fingerprint.bytes,
                         fingerprint.size)) {
            return false;
        }
    }
    return true;
}

}  // namespace hypertweak::native::mapped

using namespace hypertweak::native::mapped;
using namespace hypertweak::native::identity;

bool MiuiHomeHyosResolveDartImage(void* dart_handle, uint8_t** base_out,
                                  const uint8_t** build_id_out) {
    if (dart_handle == nullptr || base_out == nullptr ||
            build_id_out == nullptr) {
        return false;
    }
    void* instructions = dlsym(dart_handle,
                               kDartSnapshotInstructionsSymbol);
    void* build_id = dlsym(dart_handle, kDartSnapshotBuildIdSymbol);
    Dl_info info{};
    if (instructions == nullptr || build_id == nullptr ||
            dladdr(instructions, &info) == 0 || info.dli_fbase == nullptr ||
            !IsDartLibraryPath(info.dli_fname)) {
        return false;
    }
    auto* base = static_cast<uint8_t*>(info.dli_fbase);
    const uintptr_t base_address = reinterpret_cast<uintptr_t>(base);
    const uintptr_t instructions_address =
            reinterpret_cast<uintptr_t>(instructions);
    const uintptr_t build_id_address = reinterpret_cast<uintptr_t>(build_id);
    if (instructions_address < base_address || build_id_address < base_address) {
        return false;
    }
    const uintptr_t instructions_offset = instructions_address - base_address;
    const uintptr_t build_id_offset = build_id_address - base_address;
    if (!DartMappedRangeHasFlags(base, instructions_offset, sizeof(uint32_t),
                                 PF_R | PF_X) ||
            !DartMappedRangeHasFlags(base, build_id_offset, 32u, PF_R)) {
        return false;
    }
    *base_out = base;
    *build_id_out = static_cast<const uint8_t*>(build_id);
    return true;
}

bool MiuiHomeHyosDartRangeHasFlags(const uint8_t* base, uintptr_t offset,
                                   size_t size, uint32_t required_flags) {
    return DartMappedRangeHasFlags(base, offset, size, required_flags);
}
