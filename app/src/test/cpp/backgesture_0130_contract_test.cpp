#include "runtime_profile_resolver.h"
#include "dart_runtime_resolver.h"
#include <elf.h>
#include <cassert>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <vector>

static std::vector<uint8_t> mapped(const char* path) {
    std::ifstream input(path, std::ios::binary);
    std::vector<uint8_t> file((std::istreambuf_iterator<char>(input)), {});
    assert(file.size() >= sizeof(Elf64_Ehdr));
    Elf64_Ehdr header{}; memcpy(&header, file.data(), sizeof(header));
    assert(header.e_machine == EM_AARCH64 && header.e_phentsize == sizeof(Elf64_Phdr));
    std::vector<uint8_t> result(0x4000000);
    for (uint16_t i = 0; i < header.e_phnum; ++i) {
        assert(header.e_phoff + (i + 1) * sizeof(Elf64_Phdr) <= file.size());
        Elf64_Phdr p{}; memcpy(&p, file.data() + header.e_phoff + i * sizeof(p), sizeof(p));
        if (p.p_type != PT_LOAD) continue;
        assert(p.p_vaddr + p.p_memsz <= result.size() && p.p_offset + p.p_filesz <= file.size());
        memcpy(result.data() + p.p_vaddr, file.data() + p.p_offset, p.p_filesz);
    }
    return result;
}
int main(int argc, char** argv) {
    assert(argc == 3);
    auto launcher = mapped(argv[1]);
    uintptr_t physical = 0;
    assert(miui_home_runtime_profile::ResolveContextualLongPressInput(launcher.data(), &physical));
    assert(physical > 0 && physical + 4 < launcher.size());
    uint32_t physical_word = 0; memcpy(&physical_word, launcher.data() + physical, 4);
    memset(launcher.data() + physical, 0, 4);
    uintptr_t missing_input = 0;
    assert(!miui_home_runtime_profile::ResolveContextualLongPressInput(launcher.data(), &missing_input));
    assert(missing_input == 0);
    memcpy(launcher.data() + physical, &physical_word, 4);
    miui_home_runtime_profile::SfAnimStartLayout layout{};
    assert(miui_home_runtime_profile::ResolveSfAnimStartAnimation(launcher.data(), &layout));
    assert(layout.entry_offset > 0 && layout.w_start != layout.h_start);
    printf("current native contracts: physical input=%zx SfAnim=%zx anim=%x width=%x height=%x\n",
           physical, layout.entry_offset, layout.anim_type, layout.w_start, layout.h_start);
    // A malformed entry revokes the optional geometry contract instead of partially publishing it.
    uint32_t saved = 0; memcpy(&saved, launcher.data() + layout.entry_offset, 4);
    memset(launcher.data() + layout.entry_offset, 0, 4);
    miui_home_runtime_profile::SfAnimStartLayout rejected{};
    assert(!miui_home_runtime_profile::ResolveSfAnimStartAnimation(launcher.data(), &rejected));
    assert(rejected.entry_offset == 0);
    memcpy(launcher.data() + layout.entry_offset, &saved, 4);
    auto dart = mapped(argv[2]);
    miui_home_dart_profile::ContextualActionSite action{};
    assert(miui_home_dart_profile::ResolveContextualActionSite(dart.data(), &action));
    printf("current Dart NavLongPress: selector=%zx context=%td result=%td\n", action.offset,
           action.context_frame_offset, action.result_frame_offset);
    // The overwritten instructions are part of the ownership proof, not an address-only target.
    memset(dart.data() + action.offset, 0, sizeof(action.displaced));
    miui_home_dart_profile::ContextualActionSite bad{};
    assert(!miui_home_dart_profile::ResolveContextualActionSite(dart.data(), &bad));
    assert(bad.offset == 0);
}
