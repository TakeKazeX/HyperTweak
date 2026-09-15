// SPDX-License-Identifier: Apache-2.0
//
// Host-side probe for the Dart target registry.
//
// This is the authoring tool for a new hook. It resolves the same specs through
// the same translation units the device payload uses (dart_image.cpp +
// dart_targets.cpp, no Android headers), so an offline verdict is the device's
// verdict -- there is no second implementation to drift. A feature author gets
// candidate counts, the chosen tier, and the resolved offset without a device
// round trip, and can prove fail-closed behaviour with --mutate.
//
// Build (host):
//   clang++ -std=c++17 -O2 -o /tmp/dart_probe dart_probe.cpp dart_image.cpp dart_targets.cpp
//
// Usage:
//   dart_probe <libapp.so>
//   dart_probe <libapp.so> --mutate 0x8fec50        # one flipped byte must reject
//   dart_probe <libapp.so> --expect grid_return=0x8fec50
//
// This file is intentionally NOT part of the Android CMake target.

#include "dart_image.h"
#include "dart_targets.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <string>
#include <vector>

namespace {

constexpr uint8_t kElfMagic[4] = {0x7f, 'E', 'L', 'F'};
constexpr uint32_t kProgramTypeLoad = 1u;

#pragma pack(push, 1)
struct Elf64Header {
    uint8_t ident[16];
    uint16_t type;
    uint16_t machine;
    uint32_t version;
    uint64_t entry;
    uint64_t program_offset;
    uint64_t section_offset;
    uint32_t flags;
    uint16_t header_size;
    uint16_t program_entry_size;
    uint16_t program_count;
    uint16_t section_entry_size;
    uint16_t section_count;
    uint16_t section_name_index;
};

struct Elf64ProgramHeader {
    uint32_t type;
    uint32_t flags;
    uint64_t offset;
    uint64_t vaddr;
    uint64_t paddr;
    uint64_t file_size;
    uint64_t memory_size;
    uint64_t align;
};
#pragma pack(pop)

bool ReadFile(const char* path, std::vector<uint8_t>* out) {
    FILE* file = fopen(path, "rb");
    if (file == nullptr) return false;
    if (fseek(file, 0, SEEK_END) != 0) {
        fclose(file);
        return false;
    }
    const long size = ftell(file);
    if (size <= 0) {
        fclose(file);
        return false;
    }
    rewind(file);
    out->resize(static_cast<size_t>(size));
    const size_t read = fread(out->data(), 1u, out->size(), file);
    fclose(file);
    return read == out->size();
}

// Builds a true in-memory image: each PT_LOAD is copied to the virtual address it
// would receive from the dynamic linker, with the tail of the segment left as the
// zero fill the loader would provide. Resolving against a raw file buffer would
// silently produce wrong offsets on any snapshot whose file offsets and virtual
// addresses diverge, so this mirrors a real mapping instead.
bool BuildImage(const std::vector<uint8_t>& file, std::vector<uint8_t>* image_out,
                uintptr_t* base_out) {
    if (file.size() < sizeof(Elf64Header)) return false;
    Elf64Header header{};
    memcpy(&header, file.data(), sizeof(header));
    if (memcmp(header.ident, kElfMagic, sizeof(kElfMagic)) != 0 ||
            header.program_entry_size != sizeof(Elf64ProgramHeader) ||
            header.program_count == 0u ||
            header.program_offset + static_cast<uint64_t>(header.program_count) *
                                            sizeof(Elf64ProgramHeader) >
                    file.size()) {
        return false;
    }
    uint64_t span = 0u;
    for (uint16_t index = 0u; index < header.program_count; ++index) {
        Elf64ProgramHeader program{};
        memcpy(&program,
               file.data() + header.program_offset +
                       static_cast<uint64_t>(index) * sizeof(Elf64ProgramHeader),
               sizeof(program));
        if (program.type != kProgramTypeLoad) continue;
        if (program.offset + program.file_size > file.size()) return false;
        const uint64_t end = program.vaddr + program.memory_size;
        if (end > span) span = end;
    }
    if (span == 0u) return false;
    image_out->assign(static_cast<size_t>(span), 0u);
    for (uint16_t index = 0u; index < header.program_count; ++index) {
        Elf64ProgramHeader program{};
        memcpy(&program,
               file.data() + header.program_offset +
                       static_cast<uint64_t>(index) * sizeof(Elf64ProgramHeader),
               sizeof(program));
        if (program.type != kProgramTypeLoad) continue;
        memcpy(image_out->data() + program.vaddr, file.data() + program.offset,
               static_cast<size_t>(program.file_size));
    }
    *base_out = reinterpret_cast<uintptr_t>(image_out->data());
    return true;
}

// Prints the instruction-class sequence at `offset`, and specifically at the
// offset the registry resolved for `site`. This is how the expected sequence in
// dart_targets.cpp is authored: the classifier lives in one place, so a sequence
// can never be hand-transcribed from a different tool's notion of "class".
void DumpClasses(const hypertweak::native::dart::Image& image,
                 uintptr_t offset, size_t count) {
    printf("classes at 0x%zx (%zu):\n  ", static_cast<size_t>(offset), count);
    for (size_t index = 0u; index < count; ++index) {
        uint32_t instruction = 0u;
        if (!hypertweak::native::dart::ReadInsn(image,
                                                offset + index * 4u,
                                                &instruction)) {
            printf("<unreadable>");
            break;
        }
        printf("%u,", static_cast<unsigned>(
                              hypertweak::native::dart::ClassifyInsn(instruction)));
    }
    printf("\n");
}

// Resolution cost matters as much as correctness: these finders run inside the
// launcher (and, for the load-callback path, during launcher startup), so a
// spec that is correct but slow is a defect. Reported per spec so a new hook can
// be budgeted before it ships.
double NowMs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<double>(ts.tv_sec) * 1000.0 +
           static_cast<double>(ts.tv_nsec) / 1e6;
}

struct Expectation {
    std::string site;
    uintptr_t offset;
};

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <libapp.so> [--mutate <hex>] [--expect site=hex ...]\n",
                argv[0]);
        return 2;
    }
    const char* path = argv[1];
    bool mutate = false;
    uintptr_t mutate_offset = 0u;
    uintptr_t classes_offset = 0u;
    bool dump_classes = false;
    std::vector<Expectation> expectations;
    for (int index = 2; index < argc; ++index) {
        if (strcmp(argv[index], "--mutate") == 0 && index + 1 < argc) {
            mutate = true;
            mutate_offset = static_cast<uintptr_t>(
                    strtoull(argv[++index], nullptr, 16));
        } else if (strcmp(argv[index], "--classes") == 0 && index + 1 < argc) {
            dump_classes = true;
            classes_offset = static_cast<uintptr_t>(
                    strtoull(argv[++index], nullptr, 16));
        } else if (strcmp(argv[index], "--expect") == 0 && index + 1 < argc) {
            const char* spec = argv[++index];
            const char* equals = strchr(spec, '=');
            if (equals == nullptr) {
                fprintf(stderr, "malformed --expect: %s\n", spec);
                return 2;
            }
            expectations.push_back({std::string(spec, equals - spec),
                                    static_cast<uintptr_t>(strtoull(equals + 1,
                                                                    nullptr, 16))});
        } else {
            fprintf(stderr, "unknown argument: %s\n", argv[index]);
            return 2;
        }
    }

    std::vector<uint8_t> file;
    if (!ReadFile(path, &file)) {
        fprintf(stderr, "cannot read %s\n", path);
        return 1;
    }
    std::vector<uint8_t> mapped;
    uintptr_t base = 0u;
    if (!BuildImage(file, &mapped, &base)) {
        fprintf(stderr, "%s is not a loadable ELF64/aarch64 image\n", path);
        return 1;
    }
    if (mutate) {
        if (mutate_offset >= mapped.size()) {
            fprintf(stderr, "mutate offset out of range\n");
            return 2;
        }
        // Flip exactly one byte inside the mapped image. The resolver is required
        // to reject rather than choose a neighbour, so a mutation that still
        // resolves is a defect in the finder's uniqueness rule.
        mapped[mutate_offset] ^= 0xffu;
        printf("MUTATED byte at 0x%zx (image modified, expecting rejection)\n",
               static_cast<size_t>(mutate_offset));
    }

    hypertweak::native::dart::Image image{};
    if (!hypertweak::native::dart::ParseImage(mapped.data(), &image)) {
        fprintf(stderr, "image validation failed (fail-closed)\n");
        return 1;
    }
    printf("image: span=0x%zx segments=%zu\n",
           static_cast<size_t>(image.image_span), image.load_count);
    if (dump_classes) {
        DumpClasses(image, classes_offset, 24u);
        return 0;
    }

    int failures = 0;
    for (size_t index = 0u;
         index < hypertweak::native::dart::kTargetSpecCount; ++index) {
        const auto* spec = hypertweak::native::dart::kTargetSpecs[index];
        const double started = NowMs();
        const auto result = hypertweak::native::dart::ResolveTarget(image, *spec);
        const double elapsed = NowMs() - started;
        printf("\n%s: %s (reason=%s failure=%s) %.1f ms\n", result.id,
               result.resolved ? "RESOLVED" : "REJECTED", result.reason,
               result.failure, elapsed);
        for (size_t site = 0u; site < result.site_count; ++site) {
            const auto& entry = result.sites[site];
            if (entry.resolved) {
                printf("  %-22s RESOLVED 0x%zx  tier=%s  candidates=%u\n",
                       entry.name, static_cast<size_t>(entry.offset),
                       hypertweak::native::dart::FindTierName(entry.tier),
                       entry.candidates);
            } else {
                printf("  %-22s REJECTED            candidates=%u\n",
                       entry.name, entry.candidates);
            }
            for (const auto& expectation : expectations) {
                if (expectation.site != entry.name) continue;
                if (!entry.resolved || entry.offset != expectation.offset) {
                    printf("      EXPECTATION FAILED: want 0x%zx, got %s\n",
                           static_cast<size_t>(expectation.offset),
                           entry.resolved ? "a different offset" : "none");
                    ++failures;
                } else {
                    printf("      expectation ok\n");
                }
            }
        }
    }
    if (failures != 0) {
        printf("\n%d expectation(s) failed\n", failures);
        return 1;
    }
    return 0;
}
