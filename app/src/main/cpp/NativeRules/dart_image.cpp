// SPDX-License-Identifier: Apache-2.0
#include "dart_image.h"

#include <string.h>

// This translation unit is compiled twice: into the on-device payload (Android
// NDK, where <elf.h> exists) and into the host-side probe (macOS/Linux, where it
// does not). The minimal ELF64 subset below is therefore defined locally instead
// of including <elf.h>, so both builds see byte-identical layout rules and no
// platform header can change the validation behaviour.
namespace {

constexpr uint8_t kElfMagic[4] = {0x7f, 'E', 'L', 'F'};
constexpr uint8_t kElfClass64 = 2u;
constexpr uint8_t kElfData2Lsb = 1u;
constexpr uint16_t kElfTypeDyn = 3u;
constexpr uint16_t kElfMachineAarch64 = 183u;
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

static_assert(sizeof(Elf64Header) == 64u, "unexpected ELF64 header layout");
static_assert(sizeof(Elf64ProgramHeader) == 56u,
              "unexpected ELF64 program header layout");

// The program header table must live in the first page, which is the same bound
// the upstream resolver enforces: it keeps a malformed header from steering the
// parser into arbitrary image offsets.
constexpr uint64_t kMaxProgramHeaderOffset = 0x1000u;
constexpr uint16_t kMaxProgramHeaders = 64u;

// Single comparison primitive shared by the counting and locating paths, so a
// pattern can never be counted by one rule and located by a slightly different
// one. Semantics: a mask byte of 0xff requires an exact match, 0x00 ignores the
// byte, and intermediate values compare only the masked bits. A null mask means
// "all bytes exact".
bool BytesMatchAt(const uint8_t* data, const uint8_t* expect,
                  const uint8_t* mask, size_t size) {
    if (mask == nullptr) return memcmp(data, expect, size) == 0;
    for (size_t index = 0u; index < size; ++index) {
        const uint8_t bits = mask[index];
        if (bits == 0u) continue;
        if ((data[index] & bits) != (expect[index] & bits)) return false;
    }
    return true;
}

}  // namespace

namespace hypertweak::native::dart {

// A cheap necessary condition evaluated before the full comparison.
//
// Scanning a launcher image means testing ~19 million positions. Calling the
// general comparison at each one dominated resolution time, because the call
// itself (not the comparison) was the cost. Reducing the first four bytes to a
// single masked word compare turns the common case into two instructions and
// rejects almost every position before any call happens.
//
// This is only ever a prefilter: it uses the same masked-equality semantics as
// the full comparison over the first four bytes, so it can never accept
// something the full comparison would reject, nor reject something it would
// accept. Patterns shorter than four bytes simply leave it inactive.
struct Prefilter {
    uint32_t value;
    uint32_t mask;
    bool active;
};

Prefilter MakePrefilter(const BytePattern& pattern) {
    Prefilter prefilter{0u, 0u, false};
    if (pattern.bytes == nullptr || pattern.size < sizeof(uint32_t)) {
        return prefilter;
    }
    uint32_t value = 0u;
    uint32_t mask = 0u;
    for (size_t index = 0u; index < sizeof(uint32_t); ++index) {
        const uint8_t bits =
                pattern.mask == nullptr ? 0xffu : pattern.mask[index];
        value |= static_cast<uint32_t>(pattern.bytes[index] & bits)
                << (8u * index);
        mask |= static_cast<uint32_t>(bits) << (8u * index);
    }
    prefilter.value = value;
    prefilter.mask = mask;
    prefilter.active = true;
    return prefilter;
}

// True when the first four bytes cannot be ruled out. `data` must have at least
// four readable bytes; every caller only invokes it on positions where a
// full-length pattern still fits.
inline bool PassesPrefilter(const uint8_t* data, const Prefilter& prefilter) {
    if (!prefilter.active) return true;
    uint32_t word = 0u;
    memcpy(&word, data, sizeof(word));
    return (word & prefilter.mask) == prefilter.value;
}


bool AddOverflows(uintptr_t left, uintptr_t right) {
    return right > UINTPTR_MAX - left;
}

bool ParseImage(const uint8_t* base, Image* output) {
    if (base == nullptr || output == nullptr) return false;
    Elf64Header header{};
    memcpy(&header, base, sizeof(header));
    if (memcmp(header.ident, kElfMagic, sizeof(kElfMagic)) != 0 ||
            header.ident[4] != kElfClass64 || header.ident[5] != kElfData2Lsb ||
            header.type != kElfTypeDyn ||
            header.machine != kElfMachineAarch64 ||
            header.program_entry_size != sizeof(Elf64ProgramHeader) ||
            header.program_count == 0u || header.program_count > kMaxProgramHeaders ||
            header.program_offset > kMaxProgramHeaderOffset ||
            AddOverflows(static_cast<uintptr_t>(header.program_offset),
                         static_cast<uintptr_t>(header.program_count) *
                                 sizeof(Elf64ProgramHeader)) ||
            header.program_offset +
                            static_cast<uint64_t>(header.program_count) *
                                    sizeof(Elf64ProgramHeader) >
                    kMaxProgramHeaderOffset) {
        return false;
    }
    Image image{};
    image.base = base;
    bool headers_covered = false;
    const uint64_t headers_end =
            header.program_offset +
            static_cast<uint64_t>(header.program_count) *
                    sizeof(Elf64ProgramHeader);
    for (uint16_t index = 0u; index < header.program_count; ++index) {
        Elf64ProgramHeader program{};
        memcpy(&program,
               base + header.program_offset +
                       static_cast<uint64_t>(index) * sizeof(Elf64ProgramHeader),
               sizeof(program));
        if (program.type != kProgramTypeLoad) continue;
        if (image.load_count >= kMaxLoadSegments || program.memory_size == 0u ||
                AddOverflows(static_cast<uintptr_t>(program.vaddr),
                             static_cast<uintptr_t>(program.memory_size))) {
            return false;
        }
        const uintptr_t start = static_cast<uintptr_t>(program.vaddr);
        const uintptr_t end = start + static_cast<uintptr_t>(program.memory_size);
        if (end > kMaxImageSpan) return false;
        image.loads[image.load_count++] = {
                start, end, static_cast<uint32_t>(program.flags)};
        if (start == 0u && headers_end <= program.file_size) {
            headers_covered = true;
        }
        if (end > image.image_span) image.image_span = end;
    }
    if (!headers_covered || image.load_count == 0u || image.image_span == 0u) {
        return false;
    }
    *output = image;
    return true;
}

bool Contains(const Image& image, uintptr_t offset, size_t size,
              uint32_t required_flags, uint32_t forbidden_flags) {
    if (size == 0u || AddOverflows(offset, size)) return false;
    const uintptr_t end = offset + size;
    for (size_t index = 0u; index < image.load_count; ++index) {
        const LoadSegment& load = image.loads[index];
        if (offset >= load.start && end <= load.end &&
                (load.flags & required_flags) == required_flags &&
                (load.flags & forbidden_flags) == 0u) {
            return true;
        }
    }
    return false;
}

bool ReadInsn(const Image& image, uintptr_t offset, uint32_t* value) {
    if (value == nullptr ||
            !Contains(image, offset, sizeof(*value), kFlagRead | kFlagExec)) {
        return false;
    }
    memcpy(value, image.base + offset, sizeof(*value));
    return true;
}

bool ReadInsns(const Image& image, uintptr_t offset, uint32_t* values,
               size_t count) {
    if (values == nullptr || count == 0u ||
            !Contains(image, offset, count * sizeof(*values),
                      kFlagRead | kFlagExec)) {
        return false;
    }
    memcpy(values, image.base + offset, count * sizeof(*values));
    return true;
}

bool IsBl(uint32_t instruction) {
    return (instruction & 0xfc000000u) == 0x94000000u;
}

bool DecodeBlTarget(uintptr_t instruction_offset, uint32_t instruction,
                    uintptr_t* target) {
    if (!IsBl(instruction)) return false;
    int64_t immediate = static_cast<int64_t>(instruction & 0x03ffffffu);
    if ((immediate & (int64_t{1} << 25u)) != 0) {
        immediate -= int64_t{1} << 26u;
    }
    const int64_t resolved =
            static_cast<int64_t>(instruction_offset) + immediate * 4;
    if (resolved < 0) return false;
    if (target != nullptr) *target = static_cast<uintptr_t>(resolved);
    return true;
}

bool DecodeTestBranchTarget(uintptr_t instruction_offset, uint32_t instruction,
                            uintptr_t* target) {
    // b.cond: 0101 0100 imm19 0 cond
    if ((instruction & 0xff000010u) != 0x54000000u) return false;
    int64_t immediate = static_cast<int64_t>((instruction >> 5u) & 0x7ffffu);
    if ((immediate & (int64_t{1} << 18u)) != 0) {
        immediate -= int64_t{1} << 19u;
    }
    const int64_t resolved =
            static_cast<int64_t>(instruction_offset) + immediate * 4;
    if (resolved < 0) return false;
    if (target != nullptr) *target = static_cast<uintptr_t>(resolved);
    return true;
}

bool IsLoadX0FromX0(uint32_t instruction, uintptr_t* byte_offset) {
    if ((instruction & 0xffc003ffu) != 0xf9400000u) return false;
    if (byte_offset != nullptr) {
        *byte_offset = static_cast<uintptr_t>((instruction >> 10u) & 0xfffu) * 8u;
    }
    return true;
}

bool IsAddImm(uint32_t instruction, uint8_t* rd, uint8_t* rn, uint32_t* imm,
              bool* shift12) {
    // add (immediate), 64-bit: sf=1 op=0 S=0 100010 sh imm12 Rn Rd
    if ((instruction & 0xff800000u) != 0x91000000u) return false;
    const uint32_t shift = (instruction >> 22u) & 0x3u;
    if (shift > 1u) return false;
    const uint32_t immediate = (instruction >> 10u) & 0xfffu;
    if (rd != nullptr) *rd = static_cast<uint8_t>(instruction & 0x1fu);
    if (rn != nullptr) *rn = static_cast<uint8_t>((instruction >> 5u) & 0x1fu);
    if (shift12 != nullptr) *shift12 = shift == 1u;
    if (imm != nullptr) *imm = shift == 1u ? (immediate << 12u) : immediate;
    return true;
}

bool IsLdrImm(uint32_t instruction, uint8_t* rt, uint8_t* rn, uint32_t* imm12) {
    // ldr (immediate, unsigned offset), 64-bit: size=11 111 V=0 01 opc=01 imm12 Rn Rt
    if ((instruction & 0xffc00000u) != 0xf9400000u) return false;
    if (rt != nullptr) *rt = static_cast<uint8_t>(instruction & 0x1fu);
    if (rn != nullptr) *rn = static_cast<uint8_t>((instruction >> 5u) & 0x1fu);
    if (imm12 != nullptr) *imm12 = (instruction >> 10u) & 0xfffu;
    return true;
}

bool IsAdrp(uint32_t instruction, uint8_t* rd, intptr_t* page_delta) {
    if ((instruction & 0x9f000000u) != 0x90000000u) return false;
    int64_t immediate =
            static_cast<int64_t>(((instruction >> 5u) & 0x7ffffu) << 2u |
                                 ((instruction >> 29u) & 0x3u));
    if ((immediate & (int64_t{1} << 20u)) != 0) {
        immediate -= int64_t{1} << 21u;
    }
    if (rd != nullptr) *rd = static_cast<uint8_t>(instruction & 0x1fu);
    if (page_delta != nullptr) *page_delta = static_cast<intptr_t>(immediate);
    return true;
}

bool DecodePoolRef(uint32_t add_instruction, uint32_t ldr_instruction,
                   PoolRef* output) {
    uint8_t add_rd = 0u;
    uint8_t add_rn = 0u;
    uint32_t add_imm = 0u;
    bool shift12 = false;
    if (!IsAddImm(add_instruction, &add_rd, &add_rn, &add_imm, &shift12)) {
        return false;
    }
    uint8_t load_rt = 0u;
    uint8_t load_rn = 0u;
    uint32_t load_imm12 = 0u;
    if (!IsLdrImm(ldr_instruction, &load_rt, &load_rn, &load_imm12)) {
        return false;
    }
    // The `ldr` must consume exactly what the `add` produced; otherwise the pair
    // is not a pool access even though both instructions are individually valid.
    if (load_rn != add_rd) return false;
    if (output != nullptr) {
        *output = PoolRef{add_rn, add_rd, load_rt, add_imm, load_imm12 * 8u};
    }
    return true;
}

bool IsDartPrologue(const Image& image, uintptr_t offset) {
    uint32_t frame = 0u;
    uint32_t restore = 0u;
    if (!ReadInsns(image, offset, &frame, 1u)) return false;
    // stp x29, x30, [<dart sp>, #-N]!
    if ((frame & 0xffc00000u) != 0xa9800000u) return false;
    if ((frame & 0x1fu) != 29u) return false;                // Rt  = x29
    if (((frame >> 10u) & 0x1fu) != 30u) return false;       // Rt2 = x30
    if (((frame >> 5u) & 0x1fu) != kDartStackPointerRegister) return false;
    const int64_t immediate = static_cast<int64_t>((frame >> 15u) & 0x7fu);
    if (immediate >= 0) return false;  // pre-index decrement
    if (!ReadInsn(image, offset + 4u, &restore)) return false;
    // mov x29, x15  ==  orr x29, xzr, x15
    if ((restore & 0xffe003e0u) != 0xaa0003e0u) return false;
    if ((restore & 0x1fu) != 29u) return false;              // Rd = x29
    if (((restore >> 16u) & 0x1fu) != kDartStackPointerRegister) return false;
    return true;
}

bool IsDartReturnEpilogue(const Image& image, uintptr_t offset,
                          size_t max_length, uintptr_t* epilogue_start) {
    if (max_length < 3u * sizeof(uint32_t)) return false;
    const size_t words = max_length / sizeof(uint32_t) - 2u;
    for (size_t index = 0u; index < words; ++index) {
        uint32_t candidate[3] = {0u, 0u, 0u};
        const uintptr_t cursor = offset + index * sizeof(uint32_t);
        if (!ReadInsns(image, cursor, candidate, 3u)) return false;
        // Every Dart AOT return restores the Dart frame pointer from its own
        // stack register, pops the platform frame, then returns.
        if (candidate[0] != kDartRestoreFrame) continue;
        if (candidate[1] != kDartPopFrame) continue;
        if (candidate[2] != kDartReturn) continue;
        if (epilogue_start != nullptr) *epilogue_start = cursor;
        return true;
    }
    return false;
}

bool MatchBytes(const Image& image, uintptr_t offset,
                const BytePattern& pattern, uint32_t required_flags) {
    if (pattern.bytes == nullptr || pattern.size == 0u) return false;
    if (!Contains(image, offset, pattern.size, required_flags)) return false;
    const uint8_t* data = image.base + offset;
    for (size_t index = 0u; index < pattern.size; ++index) {
        const uint8_t value = pattern.mask == nullptr
                ? 0xffu
                : pattern.mask[index];
        if (value == 0u) continue;
        if ((data[index] & value) != (pattern.bytes[index] & value)) return false;
    }
    return true;
}

bool CountBytes(const Image& image, uintptr_t begin, uintptr_t end,
                const BytePattern& pattern, uint32_t* matches) {
    if (matches != nullptr) *matches = 0u;
    if (pattern.bytes == nullptr || pattern.size == 0u) return false;
    if (end <= begin || AddOverflows(begin, end - begin)) return false;
    if (!Contains(image, begin, static_cast<size_t>(end - begin), kFlagRead)) {
        return false;
    }
    // The range was validated once above; the inner loop compares bytes directly
    // rather than re-entering Contains per position, which would cost
    // O(bytes * segments) on a multi-megabyte text segment.
    const uint8_t* const base = image.base + begin;
    const size_t span = static_cast<size_t>(end - begin);
    const uint8_t* const expect = pattern.bytes;
    const uint8_t* const mask = pattern.mask;
    const size_t size = pattern.size;
    const Prefilter prefilter = MakePrefilter(pattern);
    uint32_t found = 0u;
    const size_t last = span - size;
    for (size_t cursor = 0u; cursor <= last; ++cursor) {
        if (!PassesPrefilter(base + cursor, prefilter)) continue;
        if (!BytesMatchAt(base + cursor, expect, mask, size)) continue;
        if (found == UINT32_MAX) return false;  // saturate loudly, never silently
        ++found;
    }
    if (matches != nullptr) *matches = found;
    return true;
}

bool CountBytesInExecutable(const Image& image, const BytePattern& pattern,
                            uint32_t* matches) {
    if (matches != nullptr) *matches = 0u;
    if (pattern.bytes == nullptr || pattern.size == 0u) return false;
    uint32_t total = 0u;
    for (size_t index = 0u; index < image.load_count; ++index) {
        const LoadSegment& load = image.loads[index];
        if ((load.flags & kFlagExec) == 0u) continue;
        if (load.end - load.start < pattern.size) continue;
        uint32_t local = 0u;
        if (!CountBytes(image, load.start, load.end, pattern, &local)) {
            return false;
        }
        if (UINT32_MAX - total < local) return false;
        total += local;
    }
    if (matches != nullptr) *matches = total;
    return true;
}

bool FindUniqueBytes(const Image& image, const BytePattern& pattern,
                     uintptr_t* out_offset, uint32_t* candidate_count) {
    if (candidate_count != nullptr) *candidate_count = 0u;
    if (pattern.bytes == nullptr || pattern.size == 0u) return false;
    // One pass collects both the count and the first hit. The count saturates
    // rather than wrapping, and a saturated count is never 1, so an overflow can
    // only ever cause a rejection.
    const Prefilter prefilter = MakePrefilter(pattern);
    uint32_t found = 0u;
    uintptr_t first = 0u;
    for (size_t index = 0u; index < image.load_count; ++index) {
        const LoadSegment& load = image.loads[index];
        if ((load.flags & kFlagExec) == 0u) continue;
        if (load.end - load.start < pattern.size) continue;
        const uint8_t* const base = image.base + load.start;
        const size_t span = static_cast<size_t>(load.end - load.start);
        const size_t last = span - pattern.size;
        for (size_t cursor = 0u; cursor <= last; ++cursor) {
            if (!PassesPrefilter(base + cursor, prefilter)) continue;
            if (!BytesMatchAt(base + cursor, pattern.bytes, pattern.mask,
                              pattern.size)) {
                continue;
            }
            if (found == 0u) first = load.start + cursor;
            if (found != UINT32_MAX) ++found;
        }
    }
    if (candidate_count != nullptr) *candidate_count = found;
    if (found != 1u) return false;
    if (out_offset != nullptr) *out_offset = first;
    return true;
}

bool CollectBytes(const Image& image, const BytePattern& pattern,
                 uintptr_t* out_offsets, size_t max, size_t* out_count,
                 uint32_t* total) {
    if (out_count != nullptr) *out_count = 0u;
    if (total != nullptr) *total = 0u;
    if (pattern.bytes == nullptr || pattern.size == 0u) return false;
    if (out_offsets == nullptr || max == 0u) return false;
    const Prefilter prefilter = MakePrefilter(pattern);
    size_t stored = 0u;
    uint32_t seen = 0u;
    for (size_t index = 0u; index < image.load_count; ++index) {
        const LoadSegment& load = image.loads[index];
        if ((load.flags & kFlagExec) == 0u) continue;
        if (load.end - load.start < pattern.size) continue;
        const uint8_t* const base = image.base + load.start;
        const size_t span = static_cast<size_t>(load.end - load.start);
        const size_t last = span - pattern.size;
        for (size_t cursor = 0u; cursor <= last; ++cursor) {
            if (!PassesPrefilter(base + cursor, prefilter)) continue;
            if (!BytesMatchAt(base + cursor, pattern.bytes, pattern.mask,
                              pattern.size)) {
                continue;
            }
            if (seen != UINT32_MAX) ++seen;
            if (stored < max) out_offsets[stored++] = load.start + cursor;
        }
    }
    if (out_count != nullptr) *out_count = stored;
    if (total != nullptr) *total = seen;
    return true;
}

bool FindBytesForward(const Image& image, uintptr_t begin, size_t limit,
                      const BytePattern& pattern, uintptr_t* out_offset) {
    if (pattern.bytes == nullptr || pattern.size == 0u) return false;
    if (limit < pattern.size) return false;
    if (AddOverflows(begin, limit)) return false;
    if (!Contains(image, begin, limit, kFlagRead)) return false;
    const uint8_t* const base = image.base + begin;
    const size_t last = limit - pattern.size;
    for (size_t cursor = 0u; cursor <= last; ++cursor) {
        if (!BytesMatchAt(base + cursor, pattern.bytes, pattern.mask,
                          pattern.size)) {
            continue;
        }
        if (out_offset != nullptr) *out_offset = begin + cursor;
        return true;
    }
    return false;
}

InsnClass ClassifyInsn(uint32_t instruction) {
    // Order matters: the more specific encodings are tested before the broader
    // families they belong to, so a load is never reported as a logical op.
    if ((instruction & 0x9f000000u) == 0x90000000u) return InsnClass::kAdrp;
    if ((instruction & 0xfc000000u) == 0x94000000u) return InsnClass::kCall;
    if ((instruction & 0xfc000000u) == 0x14000000u) return InsnClass::kBranch;
    if ((instruction & 0xff000010u) == 0x54000000u) return InsnClass::kBranchCond;
    if ((instruction & 0xfffffc1fu) == 0xd65f0000u) return InsnClass::kReturn;
    if ((instruction & 0xff800000u) == 0x91000000u) return InsnClass::kAddSubImm;
    if ((instruction & 0x7f800000u) == 0x11000000u ||
            (instruction & 0x7f800000u) == 0x51000000u) {
        return InsnClass::kAddSubImm;
    }
    if ((instruction & 0x7fe00000u) == 0x6b000000u) return InsnClass::kSubs;
    if ((instruction & 0x7fe00000u) == 0x71000000u) return InsnClass::kCmpImm;
    if ((instruction & 0x7fe0fc00u) == 0x1a000000u) return InsnClass::kCsel;
    if ((instruction & 0xff800000u) == 0x12000000u) return InsnClass::kLogicalImm;
    if ((instruction & 0xff800000u) == 0x53000000u) return InsnClass::kLogicalImm;
    // Loads and stores: the unscaled/unprivileged forms first, then the scaled
    // immediate forms the Dart pool accesses actually use.
    if ((instruction & 0x3b200c00u) == 0x38000000u ||
            (instruction & 0x3b200c00u) == 0x38000800u) {
        return InsnClass::kLoadStoreUnscaled;
    }
    if ((instruction & 0xffc00000u) == 0xf9400000u) return InsnClass::kLoad;
    if ((instruction & 0xffc00000u) == 0xf9000000u) return InsnClass::kStore;
    if ((instruction & 0xffc00000u) == 0xb9400000u) return InsnClass::kLoad;
    if ((instruction & 0xffc00000u) == 0xb9000000u) return InsnClass::kStore;
    // Moves and register logical ops share the ORR encoding; both are "data
    // movement between registers" for structural purposes.
    if ((instruction & 0x7fe00000u) == 0x2a000000u ||
            (instruction & 0x7fe00000u) == 0x0a000000u) {
        return InsnClass::kMove;
    }
    if ((instruction & 0xff200000u) == 0x1e000000u) return InsnClass::kFpSimd;
    if ((instruction & 0xff200000u) == 0x0e000000u) return InsnClass::kAdvSimd;
    return InsnClass::kOther;
}

bool MatchesClassSequence(const Image& image, uintptr_t offset,
                          const ClassSequence& sequence) {
    if (sequence.classes == nullptr || sequence.count == 0u) return false;
    if (!Contains(image, offset, sequence.count * sizeof(uint32_t),
                  kFlagRead | kFlagExec)) {
        return false;
    }
    for (size_t index = 0u; index < sequence.count; ++index) {
        uint32_t instruction = 0u;
        memcpy(&instruction, image.base + offset + index * sizeof(uint32_t),
               sizeof(instruction));
        if (ClassifyInsn(instruction) != sequence.classes[index]) return false;
    }
    return true;
}

bool CountQualified(const Image& image, const BytePattern& pattern,
                    const ByteProbe* probes, size_t probe_count,
                    const ClassSequence* classes, uintptr_t* first_out,
                    uint32_t* total_out) {
    if (first_out != nullptr) *first_out = 0u;
    if (total_out != nullptr) *total_out = 0u;
    if (pattern.bytes == nullptr || pattern.size == 0u) return false;
    if (probe_count != 0u && probes == nullptr) return false;
    const Prefilter prefilter = MakePrefilter(pattern);
    uint32_t accepted = 0u;
    uintptr_t first = 0u;
    for (size_t index = 0u; index < image.load_count; ++index) {
        const LoadSegment& load = image.loads[index];
        if ((load.flags & kFlagExec) == 0u) continue;
        if (load.end - load.start < pattern.size) continue;
        const uint8_t* const base = image.base + load.start;
        const size_t span = static_cast<size_t>(load.end - load.start);
        const size_t last = span - pattern.size;
        for (size_t cursor = 0u; cursor <= last; ++cursor) {
            const uintptr_t candidate = load.start + cursor;
            if (!PassesPrefilter(base + cursor, prefilter)) continue;
            if (!BytesMatchAt(base + cursor, pattern.bytes, pattern.mask,
                              pattern.size)) {
                continue;
            }
            bool qualified = true;
            for (size_t probe = 0u; probe < probe_count; ++probe) {
                if (!MatchBytes(image, candidate + probes[probe].delta,
                                probes[probe].pattern,
                                kFlagRead | kFlagExec)) {
                    qualified = false;
                    break;
                }
            }
            if (qualified && classes != nullptr &&
                    !MatchesClassSequence(image, candidate, *classes)) {
                qualified = false;
            }
            if (!qualified) continue;
            if (accepted == 0u) first = candidate;
            if (accepted != UINT32_MAX) ++accepted;
        }
    }
    if (first_out != nullptr) *first_out = first;
    if (total_out != nullptr) *total_out = accepted;
    return true;
}

bool MatchPatternAtAddress(uintptr_t address, const BytePattern& pattern) {
    if (address == 0u || pattern.bytes == nullptr || pattern.size == 0u) {
        return false;
    }
    const uint8_t* data = reinterpret_cast<const uint8_t*>(address);
    return BytesMatchAt(data, pattern.bytes, pattern.mask, pattern.size);
}

}  // namespace hypertweak::native::dart
