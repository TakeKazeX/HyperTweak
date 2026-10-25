#include "dart_mapped_image.h"
#include "launcher_process_identity.h"

#include <cassert>
#include <cstring>
#include <elf.h>
#include <iostream>

int main() {
    using namespace hypertweak::native::mapped;
    using namespace hypertweak::native::identity;
    alignas(Elf64_Ehdr) uint8_t image[1024]{};
    auto* header = reinterpret_cast<Elf64_Ehdr*>(image);
    std::memcpy(header->e_ident, ELFMAG, SELFMAG);
    header->e_ident[EI_CLASS] = ELFCLASS64;
    header->e_ident[EI_DATA] = ELFDATA2LSB;
    header->e_type = ET_DYN;
    header->e_machine = EM_AARCH64;
    header->e_phoff = sizeof(Elf64_Ehdr);
    header->e_phentsize = sizeof(Elf64_Phdr);
    header->e_phnum = 1;
    auto* segment = reinterpret_cast<Elf64_Phdr*>(image + header->e_phoff);
    segment->p_type = PT_LOAD;
    segment->p_flags = PF_R | PF_X;
    segment->p_vaddr = 128;
    segment->p_memsz = 512;
    assert(DartMappedRangeHasFlags(image, 128, 512, PF_R | PF_X));
    assert(!DartMappedRangeHasFlags(image, 127, 4, PF_X));
    assert(!DartMappedRangeHasFlags(image, 639, 2, PF_R));
    assert(!DartMappedRangeHasFlags(image, UINTPTR_MAX - 1, 4, PF_R));
    assert(!DartMappedRangeHasFlags(image, 128, 4, PF_W));
    const uint32_t branch = 0x94000004u; // BL +16
    std::memcpy(image + 128, &branch, sizeof(branch));
    assert(DartBlTargets(image, 128, 144));
    assert(!DartBlTargets(image, 128, 148));
    header->e_machine = EM_X86_64;
    assert(!DartMappedRangeHasFlags(image, 128, 4, PF_X));
    assert(!DartBlTargets(image, 128, 144));
    assert(!MiuiHomeHyosResolveDartImage(nullptr, nullptr, nullptr));
    assert(!IsLauncherLibraryPath("/data/local/tmp/libapp_launcher.so"));
    assert(IsLauncherLibraryPath("libapp_launcher.so"));
    assert(!IsDartLibraryPath("libapp.so.fake"));
    assert(IsDartLibraryPath("/data/app/package/lib/arm64/libapp.so"));
    assert(!StringsEqual("com.miui.home:remote", kLauncherProcessName));
    std::cout << "mapped image bounds, branch, process and library admission passed\n";
}
