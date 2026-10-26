// SPDX-License-Identifier: Apache-2.0
//
// Shared discovery toolkit for the launcher's Dart AOT snapshot (libapp.so).
//
// This is the reusable half of a structural target finder: ELF image validation,
// AArch64 decoding, Dart AOT code-shape predicates, and masked pattern search.
// It is deliberately free of Android dependencies so the exact same translation
// unit compiles into the on-device payload and into the host-side probe
// (`dart_probe.cpp`); an offline verdict therefore matches device behaviour.
//
// Relationship to the upstream resolver: `dart_runtime_resolver.cpp` is vendored
// byte-identical from MiuiBackGestureHook and carries its own copy of the ELF and
// decode helpers in an anonymous namespace. Those helpers stay private there on
// purpose -- refactoring that file would break the byte-for-byte parity that
// keeps upstream syncs conflict-free. The small amount of duplication here is the
// deliberate price of that parity.
//
// Nothing in this file writes to the image. Discovery and patching are separate
// concerns: a finder only ever reports an offset that passed every check.
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace hypertweak::native::dart {

// Mirrors the upstream resolver's bounds so both agree on what a valid image is.
constexpr size_t kMaxLoadSegments = 16u;
constexpr uintptr_t kMaxImageSpan = 0x4000000u;

// Segment permission bits, spelled out here rather than taken from <elf.h> so the
// same values are used on Android and on the host, where that header is absent.
constexpr uint32_t kFlagExec = 0x1u;
constexpr uint32_t kFlagWrite = 0x2u;
constexpr uint32_t kFlagRead = 0x4u;

struct LoadSegment {
    uintptr_t start;
    uintptr_t end;
    uint32_t flags;
};

// A validated view of a mapped Dart AOT snapshot. Offsets are ELF virtual
// addresses, which equal file offsets in every launcher build inspected so far
// (the loadable segments are not re-based), but nothing here assumes that.
struct Image {
    const uint8_t* base;
    LoadSegment loads[kMaxLoadSegments];
    size_t load_count;
    uintptr_t image_span;
};

bool AddOverflows(uintptr_t left, uintptr_t right);

// Validates the ELF header and collects PT_LOAD segments. Fails closed on any
// structural surprise: a wrong class, a stripped/relocated layout, an oversized
// image, or a missing segment-0 header mapping.
bool ParseImage(const uint8_t* base, Image* output);

// True when [offset, offset+size) lies entirely inside one segment that has every
// bit of `required_flags` and none of `forbidden_flags`.
bool Contains(const Image& image, uintptr_t offset, size_t size,
              uint32_t required_flags, uint32_t forbidden_flags = 0u);

bool ReadInsn(const Image& image, uintptr_t offset, uint32_t* value);
bool ReadInsns(const Image& image, uintptr_t offset, uint32_t* values,
               size_t count);

// ---------------------------------------------------------------- AArch64 --

// `bl <imm26>` -- the call used throughout Dart AOT code for helper invocations.
bool IsBl(uint32_t instruction);
bool DecodeBlTarget(uintptr_t instruction_offset, uint32_t instruction,
                    uintptr_t* target);

// `b.cond <imm19>` -- the branch that guards a pool load behind a runtime test.
bool DecodeTestBranchTarget(uintptr_t instruction_offset, uint32_t instruction,
                            uintptr_t* target);

// `ldr x0, [x0, #imm]` -- the canonical "dereference the receiver" opener.
bool IsLoadX0FromX0(uint32_t instruction, uintptr_t* byte_offset);

// `add xD, xN, #imm12{, lsl #12}`.
bool IsAddImm(uint32_t instruction, uint8_t* rd, uint8_t* rn, uint32_t* imm,
              bool* shift12);

// `ldr xT, [xN, #imm12*8]`.
bool IsLdrImm(uint32_t instruction, uint8_t* rt, uint8_t* rn, uint32_t* imm12);

// `adrp xD, <page>` -- returns the page delta relative to the instruction page.
bool IsAdrp(uint32_t instruction, uint8_t* rd, intptr_t* page_delta);

// Dart AOT reaches its object pool through the PP register, not through PC
// relative addressing: a build independent `add`/`ldr` pair such as
//
//     add x16, x27, #0xb5, lsl #12
//     ldr x16, [x16, #0x510]
//
// where x27 is Dart's pool pointer and set by the runtime. The absolute pool
// address is therefore NOT recoverable statically; only the shape and the
// relative displacement are. Structural matchers anchor on this shape rather
// than on resolved addresses.
struct PoolRef {
    uint8_t base_reg;   // register multiplied out of the pool pointer (x27 at runtime)
    uint8_t temp_reg;   // intermediate register from the `add`
    uint8_t dest_reg;   // register the `ldr` loads into
    uint32_t add_bytes; // the `add` displacement after the LSL #12 is applied
    uint32_t ldr_offset;// byte offset of the `ldr`
};

bool DecodePoolRef(uint32_t add_instruction, uint32_t ldr_instruction,
                   PoolRef* output);

// ------------------------------------------------------------- Dart shapes --

constexpr uint32_t kDartRestoreFrame = 0xaa1d03efu;  // mov x29, x15
constexpr uint32_t kDartPopFrame = 0xa8c179fdu;      // ldp x29, x30, [sp], #...
constexpr uint32_t kDartReturn = 0xd65f03c0u;        // ret
constexpr uint32_t kDartReturnX22 = 0xaa1603e0u;     // mov x0, x22 (null return)

// The register Dart reserves for its own stack pointer. Present in the frame
// setup of every Dart AOT function, which makes it a cheap "is this Dart code"
// probe -- but NOT a target discriminator, since all Dart code shares it.
constexpr uint8_t kDartStackPointerRegister = 15u;

// A Dart function's opening frame: `stp x29, x30, [sp, #-N]!` followed by
// `mov x29, x15` (the Dart SP, not the platform SP). Used as a cheap filter, not
// as an identity.
bool IsDartPrologue(const Image& image, uintptr_t offset);

// True when the instructions at `offset` are one of the Dart return epilogues.
// On success `epilogue_start` receives the offset of the first matched word,
// which may be earlier than `offset`.
bool IsDartReturnEpilogue(const Image& image, uintptr_t offset,
                          size_t max_length, uintptr_t* epilogue_start);

// ------------------------------------------------------- masked byte patterns --
// A byte pattern with a per-byte mask: a mask byte of 0xff requires an exact
// match, 0x00 ignores the byte, and intermediate values compare only the set
// bits. A null mask means "all bytes exact".
//
// The mask is what keeps a hand-written fingerprint usable across builds that
// differ only in an encoded immediate -- for example the folder preview-prologue
// where two verified snapshots share every word except one `add` displacement.
struct BytePattern {
    const uint8_t* bytes;
    const uint8_t* mask;
    size_t size;
};

bool MatchBytes(const Image& image, uintptr_t offset,
                const BytePattern& pattern, uint32_t required_flags);

// Counts matches of `pattern` inside [begin, end). Returns false on a malformed
// range or pattern so a saturated/overflowing count can never be mistaken for an
// authoritative one.
bool CountBytes(const Image& image, uintptr_t begin, uintptr_t end,
                const BytePattern& pattern, uint32_t* matches);

// Counts matches across every executable segment. `range_out_begin`/`end_begin`
// report the scanned range for diagnostics.
bool CountBytesInExecutable(const Image& image, const BytePattern& pattern,
                            uint32_t* matches);

// Convenience for the common 'exactly one candidate' rule. On anything other than
// exactly one match, `out_offset` is left untouched and `candidate_count`
// reports what was seen (saturating at UINT32_MAX).
bool FindUniqueBytes(const Image& image, const BytePattern& pattern,
                     uintptr_t* out_offset, uint32_t* candidate_count);

// Compares bytes at an absolute runtime address against `pattern`.
//
// A rule that already holds a resolved address does not need the parsed Image to
// re-check it, and carrying the Image around would mean re-parsing the ELF on
// every maintenance pass. The address must already have been validated by a
// resolver; this function reads it directly and never faults on its own account.
bool MatchPatternAtAddress(uintptr_t address, const BytePattern& pattern);

// Enumerates matches instead of demanding uniqueness, so a structural finder can
// apply its own predicate to a candidate family. `out_offsets` receives at most
// `max` offsets; `total` reports how many exist in all, so a finder can detect
// that it is looking at a truncated family and reject rather than decide from an
// incomplete set. Returns false only on a malformed pattern or unreadable range.
bool CollectBytes(const Image& image, const BytePattern& pattern,
                  uintptr_t* out_offsets, size_t max, size_t* out_count,
                  uint32_t* total);

// First occurrence of `pattern` in [begin, begin + limit). Used to confirm that a
// function body contains the expected tail without pinning the exact distance,
// which is what makes a body-shape matcher survive a compiler that moves a
// constant or reorders two independent instructions.
bool FindBytesForward(const Image& image, uintptr_t begin, size_t limit,
                      const BytePattern& pattern, uintptr_t* out_offset);

// ------------------------------------------------- instruction classes --
//
// Operand-agnostic instruction categories. Register numbers, displacements and
// pool offsets all move between builds, but the sequence of operation *kinds* in
// a function is far more stable -- and for small functions it is stable enough
// to identify them uniquely. This is the evidence used when a byte pattern is
// hopeless: a stock Dart frame prologue matches hundreds of functions, yet the
// 16 instruction kinds that follow are unique to one of them.
enum class InsnClass : uint8_t {
    kOther = 0u,
    kAdrp,
    kAddSubImm,
    kLogicalImm,
    kLogicalReg,
    kMove,
    kLoad,
    kStore,
    kLoadStoreUnscaled,
    kCall,
    kBranch,
    kBranchCond,
    kReturn,
    kSubs,
    kCmpImm,
    kCsel,
    kFpSimd,
    kAdvSimd,
};

InsnClass ClassifyInsn(uint32_t instruction);

struct ClassSequence {
    const InsnClass* classes;
    size_t count;
};

// True when the instruction kinds at `offset` equal `sequence` exactly.
bool MatchesClassSequence(const Image& image, uintptr_t offset,
                          const ClassSequence& sequence);

// A secondary byte condition at a fixed distance from a candidate.
struct ByteProbe {
    size_t delta;
    BytePattern pattern;
};

// Counts candidates that match `pattern` AND every probe AND (when non-null)
// `classes`.
//
// This streams rather than materializing the candidate family, because the
// families that matter are large: the launcher's inline-cache preamble occurs
// tens of thousands of times, so any fixed-size buffer would either truncate the
// family (and make a uniqueness verdict meaningless) or blow the stack. Nothing
// is stored beyond the first accepted offset and a saturating count.
//
// `first_out` receives the first accepted offset; `total_out` the number of
// accepted candidates, saturating at UINT32_MAX so an overflow can only ever
// cause a rejection. Returns false only on a malformed input.
bool CountQualified(const Image& image, const BytePattern& pattern,
                    const ByteProbe* probes, size_t probe_count,
                    const ClassSequence* classes, uintptr_t* first_out,
                    uint32_t* total_out);

}  // namespace hypertweak::native::dart
