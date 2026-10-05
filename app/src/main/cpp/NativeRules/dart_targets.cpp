// SPDX-License-Identifier: Apache-2.0
#include "dart_targets.h"

#include <string.h>

namespace hypertweak::native::dart {
namespace {

// ---------------------------------------------------------------- patterns --
//
// Each pattern is the *evidence*, not the target address. Nothing here encodes a
// build id or an RVA: the resolver searches the mapped snapshot, so a launcher
// update that relocates a target is handled without a new table row.

// `RecentsPageState._insertClearButtonOverlay` entry. This is a stock Dart AOT
// frame setup, so it matches every Dart function with the same frame size --
// hundreds of sites -- and cannot be used as identity on its own. It is kept as
// the T1 candidate source that the structural finder disambiguates.
constexpr uint8_t kInsertClearButtonOverlayPrologue[16] = {
    0xfd, 0x79, 0xbf, 0xa9, 0xfd, 0x03, 0x0f, 0xaa,
    0xef, 0xe1, 0x00, 0xd1, 0xa1, 0x83, 0x1f, 0xf8,
};

// Opened-folder grid getter return epilogue. Distinctive: the `add x0, x0, #6`
// before the frame restore is specific to this getter.
constexpr uint8_t kFolderGridReturnEpilogue[16] = {
    0x00, 0x18, 0x00, 0x91, 0xef, 0x03, 0x1d, 0xaa,
    0xfd, 0x79, 0xc1, 0xa8, 0xc0, 0x03, 0x5f, 0xd6,
};

// Folder preview icon getter epilogue. NOT distinctive: the sequence is a bare
// `mov x0, x1` plus the standard restore, which is stock code (thousands of
// sites), so this site needs structural evidence.
constexpr uint8_t kPreviewIconReturnEpilogue[16] = {
    0xe0, 0x03, 0x01, 0xaa, 0xef, 0x03, 0x1d, 0xaa,
    0xfd, 0x79, 0xc1, 0xa8, 0xc0, 0x03, 0x5f, 0xd6,
};

// Folder preview item-list read prologue: NO byte evidence is usable here.
//
// Measured against the two verified snapshots:
//   * the longest byte prefix that is identical across both builds is 12 bytes;
//     the two diverge at 16 because the encoded `ldr` displacement changes;
//   * masking just that displacement (bits 10..21 of word 1) makes the pattern
//     match 24,330 / 25,534 sites -- a masked pattern is strictly more general,
//     so widening the mask trades a per-build table row for collisions.
//
// This site therefore needs structural evidence (T3). It is left without a
// pattern rather than carrying one that would only be decorative.

// ------------------------------------------------------------------- specs --

// --- structural evidence ---------------------------------------------------
//
// Everything below was derived with the host probe (`--classes`, `--expect`) and
// then re-verified against both snapshots. None of it is a build id, an RVA, or a
// build-specific byte: each predicate keys on instruction shape and on
// relationships inside a single function, so relocating a target does not
// invalidate it.

// Instruction kinds. Authors do not hand-write these values: run
//   dart_probe <libapp.so> --classes <rva>
// and paste the numbers. Keeping a single classifier (dart_image.cpp) is what
// stops a spec from being transcribed against a different tool's notion of a
// class.
constexpr InsnClass kOther = InsnClass::kOther;
constexpr InsnClass kMove = InsnClass::kMove;
constexpr InsnClass kAddSub = InsnClass::kAddSubImm;
constexpr InsnClass kUnscaled = InsnClass::kLoadStoreUnscaled;
constexpr InsnClass kCall = InsnClass::kCall;
constexpr InsnClass kSubs = InsnClass::kSubs;
constexpr InsnClass kBCond = InsnClass::kBranchCond;
constexpr InsnClass kLoad = InsnClass::kLoad;

// `RecentsPageState._insertClearButtonOverlay`: the stock Dart frame prologue
// matches hundreds of functions, so the discriminator is the 16 instruction
// *kinds* that follow. The kind sequence is identical on both verified snapshots
// even though the operands (pool displacements, call targets) differ, which is
// exactly why class matching works where byte matching cannot.
constexpr InsnClass kInsertOverlayClasses[16] = {
    kOther, kMove, kAddSub, kUnscaled, kOther, kCall, kMove, kUnscaled,
    kUnscaled, kUnscaled, kUnscaled, kOther, kSubs, kBCond, kLoad, kLoad,
};

// Folder preview item-count getter's site bytes: `madd x0, x1, x1, xzr` followed
// by the frame restore. Byte-identical on both verified snapshots, which is what
// lets it serve as the site's verification contract.
constexpr uint8_t kPreviewItemsReturnEpilogue[16] = {
    0x20, 0x7c, 0x01, 0x9b, 0xef, 0x03, 0x1d, 0xaa,
    0xfd, 0x79, 0xc1, 0xa8, 0xc0, 0x03, 0x5f, 0xd6,
};

// First words of the item-list reader's body, at the continuation address the
// preview hook returns into.
constexpr uint8_t kSetItemsContinuation[4] = {0x01, 0x7c, 0x41, 0x93};

// Inline-cache field-load preamble that opens the folder getters and the
// item-list reader. Masked because the two verified builds encode a different
// field displacement in the second `ldr`; the mask covers bits 10..21 of that
// word, which is the whole imm12 field.
constexpr uint8_t kIcPreamble[16] = {
    0x40, 0x3f, 0x40, 0xf9, 0x00, 0xac, 0x63, 0xf9,
    0x70, 0x23, 0x40, 0xf9, 0x1f, 0x00, 0x10, 0x6b,
};
constexpr uint8_t kIcPreambleMask[16] = {
    0xff, 0xff, 0xff, 0xff, 0xff, 0x03, 0xc0, 0xff,
    0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
};
constexpr BytePattern kIcPreamblePattern = {
    kIcPreamble, kIcPreambleMask, sizeof(kIcPreamble)};

// Body of the item-list reader, immediately after its inline-cache call. This
// sequence is byte-identical on both verified snapshots and unique in the whole
// image: Smi untag, store, decrement, int-to-double. Anchoring here instead of on
// the varying preamble is what makes the reader resolvable without a table.
constexpr uint8_t kSetItemsBody[16] = {
    0x01, 0x7c, 0x41, 0x93, 0xa1, 0x83, 0x1e, 0xf8,
    0x22, 0x04, 0x00, 0xd1, 0x40, 0x00, 0x62, 0x9e,
};

// `preview_set_items` sits 0x20 bytes before the body above (the preamble is
// 0x1c bytes of loads plus the call).
constexpr size_t kSetItemsBodyDistance = 0x20u;

// The item-count getter's body: Smi untag, then the square via `madd x0, x1, x1,
// xzr`, then the standard frame restore. Byte-identical on both snapshots and
// unique in the image, because the multiply is specific to this getter.
constexpr uint8_t kPreviewItemsBody[20] = {
    0x01, 0x7c, 0x41, 0x93, 0x20, 0x7c, 0x01, 0x9b,
    0xef, 0x03, 0x1d, 0xaa, 0xfd, 0x79, 0xc1, 0xa8,
    0xc0, 0x03, 0x5f, 0xd6,
};

bool FindPreviewSetItems(const Image& image, uintptr_t* offset_out,
                         uint32_t* candidates_out);

// The icon getter cannot be separated from its doppelgangers by its own body: it
// ends in `mov x0, x1`, the most ordinary Dart epilogue there is, and five family
// candidates share it on both snapshots.
//
// It IS separable by its inline-cache descriptor. The icon getter, the item-count
// getter and the item-list reader all read the same field, so all three reference
// the same pool object: bytes +0x10..+0x1c (the compare, the pool `add` and the
// descriptor `ldr`) are byte-identical across the three, while every unrelated
// candidate differs in at least one of them.
//
// The reader is resolved first because it is the one with a unique body, and its
// descriptor then acts as the anchor. This is a relationship between two
// functions, not a build constant: whatever the pool layout, the sibling that
// shares the descriptor and ends in the icon epilogue is the icon getter.
bool FindPreviewIcon(const Image& image, uintptr_t* offset_out,
                     uint32_t* candidates_out) {
    uintptr_t reader = 0u;
    uint32_t ignored = 0u;
    if (!FindPreviewSetItems(image, &reader, &ignored)) return false;

    constexpr size_t kDescriptorOffset = 0x10u;
    constexpr size_t kDescriptorSize = 12u;
    uint8_t descriptor[kDescriptorSize];
    if (!Contains(image, reader + kDescriptorOffset, kDescriptorSize,
                  kFlagRead | kFlagExec)) {
        return false;
    }
    memcpy(descriptor, image.base + reader + kDescriptorOffset,
           kDescriptorSize);

    const ByteProbe probes[] = {
        {kDescriptorOffset, {descriptor, nullptr, kDescriptorSize}},
        {0x24u, {kPreviewIconReturnEpilogue, nullptr,
                 sizeof(kPreviewIconReturnEpilogue)}},
    };
    uintptr_t first = 0u;
    uint32_t total = 0u;
    if (!CountQualified(image, kIcPreamblePattern, probes,
                        sizeof(probes) / sizeof(probes[0]), nullptr, &first,
                        &total)) {
        return false;
    }
    if (candidates_out != nullptr) *candidates_out = total;
    if (total != 1u) return false;
    if (offset_out != nullptr) *offset_out = first + 0x24u;
    return true;
}

// The item-count getter multiplies the untagged value by itself, and that body is
// unique in the image on its own.
bool FindPreviewItems(const Image& image, uintptr_t* offset_out,
                      uint32_t* candidates_out) {
    uintptr_t hit = 0u;
    uint32_t total = 0u;
    if (!FindUniqueBytes(
                image,
                {kPreviewItemsBody, nullptr, sizeof(kPreviewItemsBody)}, &hit,
                &total)) {
        if (candidates_out != nullptr) *candidates_out = total;
        return false;
    }
    if (candidates_out != nullptr) *candidates_out = 1u;
    if (offset_out != nullptr) *offset_out = hit + 4u;
    return true;
}

// The reader is needed by three sites (itself, the icon getter's descriptor
// anchor, and the continuation address). Each look-up is a full scan of the
// executable segments, so the result is memoized per thread and per image.
// thread_local rather than a shared slot on purpose: a stale hit would hand a
// rule the wrong address, and two threads can resolve different images at once.
// The key is the image pointer, so alternating images on one thread simply
// re-scans.
struct ReaderMemo {
    const uint8_t* base;
    uintptr_t offset;
    uint32_t family;
    bool valid;
};
thread_local ReaderMemo g_reader_memo = {nullptr, 0u, 0u, false};

// The item-list reader: the preamble candidate whose inline-cache call is
// followed by the int-to-double body. The body is what makes it unique -- the
// preamble alone matches tens of thousands of sites.
bool FindPreviewSetItems(const Image& image, uintptr_t* offset_out,
                         uint32_t* candidates_out) {
    if (g_reader_memo.valid && g_reader_memo.base == image.base) {
        if (offset_out != nullptr) *offset_out = g_reader_memo.offset;
        if (candidates_out != nullptr) *candidates_out = g_reader_memo.family;
        return true;
    }
    const ByteProbe probes[] = {
        {kSetItemsBodyDistance, {kSetItemsBody, nullptr, sizeof(kSetItemsBody)}},
    };
    uintptr_t first = 0u;
    uint32_t total = 0u;
    if (!CountQualified(image, kIcPreamblePattern, probes,
                        sizeof(probes) / sizeof(probes[0]), nullptr, &first,
                        &total)) {
        return false;
    }
    if (candidates_out != nullptr) *candidates_out = total;
    if (total != 1u) return false;
    g_reader_memo.base = image.base;
    g_reader_memo.offset = first;
    g_reader_memo.family = total;
    g_reader_memo.valid = true;
    if (offset_out != nullptr) *offset_out = first;
    return true;
}

// The recents clear-button overlay insert: the frame prologue matches hundreds of
// functions, so the discriminator is the instruction-kind sequence that follows.
bool FindInsertOverlay(const Image& image, uintptr_t* offset_out,
                       uint32_t* candidates_out) {
    const ClassSequence sequence = {
        kInsertOverlayClasses,
        sizeof(kInsertOverlayClasses) / sizeof(kInsertOverlayClasses[0])};
    uintptr_t first = 0u;
    uint32_t total = 0u;
    if (!CountQualified(image,
                        {kInsertClearButtonOverlayPrologue, nullptr,
                         sizeof(kInsertClearButtonOverlayPrologue)},
                        nullptr, 0u, &sequence, &first, &total)) {
        return false;
    }
    if (candidates_out != nullptr) *candidates_out = total;
    if (total != 1u) return false;
    if (offset_out != nullptr) *offset_out = first;
    return true;
}

// The address the item-list preview hook hands control back to: the body that
// follows the reader's inline-cache call.
bool FindPreviewContinuation(const Image& image, uintptr_t* offset_out,
                             uint32_t* candidates_out) {
    uintptr_t reader = 0u;
    uint32_t total = 0u;
    if (!FindPreviewSetItems(image, &reader, &total)) {
        if (candidates_out != nullptr) *candidates_out = total;
        return false;
    }
    if (candidates_out != nullptr) *candidates_out = total;
    if (offset_out != nullptr) *offset_out = reader + kSetItemsBodyDistance;
    return true;
}

// Opened-grid layout has a late-static cache distinct from the preview cache.
// Identify its reader through the complete width/spacing calculation: untag,
// convert, multiply by cell width, add spacing*(columns-1), store grid width.
bool FindFolderCacheReader(const Image& image, uintptr_t* offset_out,
                           uint32_t* candidates_out) {
    constexpr uint8_t body[] = {
        0xa1, 0x03, 0x5f, 0xf8, 0x30, 0x7c, 0x41, 0x93,
        0x01, 0x02, 0x62, 0x1e, 0x02, 0x08, 0x61, 0x1e,
    };
    constexpr uint8_t tail[] = {
        0xa2, 0x83, 0x5f, 0xf8, 0x40, 0x30, 0x44, 0xfc,
        0x23, 0x7c, 0x41, 0x93, 0x61, 0x04, 0x00, 0xd1,
        0x21, 0x00, 0x62, 0x9e, 0x03, 0x08, 0x61, 0x1e,
        0x40, 0x28, 0x63, 0x1e, 0x40, 0x30, 0x05, 0xfc,
    };
    const ByteProbe probes[] = {
        {0x2cu, {body, nullptr, sizeof(body)}},
        {0x3cu, {tail, nullptr, sizeof(tail)}},
    };
    uintptr_t first = 0u;
    uint32_t total = 0u;
    if (!CountQualified(image, kIcPreamblePattern, probes, 2u, nullptr,
                        &first, &total)) return false;
    if (candidates_out != nullptr) *candidates_out = total;
    if (total != 1u) return false;
    if (offset_out != nullptr) *offset_out = first;
    return true;
}

// onFolderOpen allocates a closure context, saves its receiver/folder arguments,
// and binds the folder to that receiver. Calls and Dart frame size may relocate;
// the argument/data-flow contract must remain exact. A unique match is required.
constexpr uint8_t kFolderOpenEntry[] = {
    0xfd, 0x79, 0xbf, 0xa9, 0xfd, 0x03, 0x0f, 0xaa,
    0xef, 0x01, 0x01, 0xd1, 0xe0, 0x03, 0x02, 0xaa,
    0xa1, 0x83, 0x1f, 0xf8, 0xa2, 0x03, 0x1f, 0xf8,
    0x21, 0x00, 0x80, 0xd2, 0x00, 0x00, 0x00, 0x94,
    0xe2, 0x03, 0x00, 0xaa, 0xa1, 0x83, 0x5f, 0xf8,
    0xa2, 0x83, 0x1e, 0xf8, 0x41, 0xf0, 0x00, 0xb8,
    0xa0, 0x03, 0x5f, 0xf8, 0x20, 0xf0, 0x01, 0xb8,
};
constexpr uint8_t kFolderOpenMask[] = {
    0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
    0xff, 0x03, 0xc0, 0xff, 0xff, 0xff, 0xff, 0xff,
    0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
    0xff, 0xff, 0xff, 0xff, 0x00, 0x00, 0x00, 0xfc,
    0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
    0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
    0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
};


// Only compiler operands are masked: object-field displacement, CID, pool
// displacement and branch/call distances are derived from the current image.
constexpr uint32_t kWidgetGateWords[] = {
    0xb84fb040u, 0x8b1c8000u, 0x37200500u, 0xaa0403e1u,
    0xaa0603e2u, 0x94000000u, 0x37200480u,
    0xf84003a0u, 0xf84003a2u, 0xaa0003e1u,
};
constexpr uint32_t kWidgetGateMasks[] = {
    0xffe00fffu, 0xffffffffu, 0xfff8001fu, 0xffffffffu,
    0xffffffffu, 0xfc000000u, 0xfff8001fu,
    0xffe00fffu, 0xffe00fffu, 0xffffffffu,
};
const BytePattern kWidgetGatePattern = {
    reinterpret_cast<const uint8_t*>(kWidgetGateWords),
    reinterpret_cast<const uint8_t*>(kWidgetGateMasks), sizeof(kWidgetGateWords)};

bool DecodeWidgetReject(uintptr_t pc, uint32_t word, uintptr_t* target) {
    if ((word & 0xfff8001fu) != 0x37200000u) return false;
    int32_t displacement = static_cast<int32_t>((word >> 5u) & 0x3fffu);
    if ((displacement & 0x2000) != 0) displacement -= 0x4000;
    const intptr_t address = static_cast<intptr_t>(pc) + displacement * 4;
    if (address < 0) return false;
    *target = static_cast<uintptr_t>(address);
    return true;
}

bool IsWidgetSpanContract(const Image& image, uintptr_t offset) {
    if (!IsDartPrologue(image, offset)) return false;
    // The helper compares both dimensions with 1, widths 2/4 and height 3,
    // then inverts a Dart bool and restores its frame. No operand address is
    // pinned, and the shared helper itself is never overridden.
    const uint32_t comparisons[] = {0xf100043fu, 0xf100045fu,
        0xf100083fu, 0xf100103fu, 0xf1000c3fu};
    bool found[5]{};
    bool inverted = false;
    for (size_t delta = 0u; delta < 384u; delta += 4u) {
        uint32_t w = 0u;
        if (!ReadInsn(image, offset + delta, &w)) return false;
        for (size_t i = 0u; i < 5u; ++i) found[i] |= w == comparisons[i];
        inverted |= w == 0xd27c0020u; // eor x0, x1, #0x10
        if (w == kDartReturn && inverted) {
            for (bool present : found) if (!present) return false;
            return true;
        }
    }
    return false;
}

// Find the type=AppWidget serializer contract, not an obfuscated Dart class.
// A CID test selects widget_type=SMI(1), widget_id, then the same boolean
// field used by the eligibility gate. Pool keys relocate between snapshots.
bool FindWidgetSerializer(const Image& image, uint32_t field,
                          uintptr_t* offset, uint32_t* count) {
    *count = 0u;
    for (size_t n = 0u; n < image.load_count; ++n) {
        const auto& seg = image.loads[n];
        if ((seg.flags & (kFlagRead | kFlagExec)) != (kFlagRead | kFlagExec)) continue;
        for (uintptr_t pc = seg.start; pc + 88u <= seg.end; pc += 4u) {
            uint32_t w[22]{};
            if (!ReadInsns(image, pc, w, 22u)) break;
            PoolRef type{}, id{}, flag{};
            if ((w[0] & 0xffc003ffu) != 0xf100003fu || // cmp x1, #CID
                (w[1] & 0xff00001fu) != 0x54000001u || // b.ne
                !DecodePoolRef(w[3], w[4], &type) || type.dest_reg != 2u ||
                w[5] != 0xd2800043u || !IsBl(w[6]) || // widget_type=SMI(1)
                !DecodePoolRef(w[12], w[13], &id) || id.dest_reg != 2u || !IsBl(w[14]) ||
                (w[16] & 0xffe00fffu) != 0xb8400003u ||
                ((w[16] >> 12u) & 0x1ffu) != field || w[17] != 0x8b1c8063u ||
                !DecodePoolRef(w[19], w[20], &flag) || flag.dest_reg != 2u || !IsBl(w[21])) continue;
            uintptr_t type_writer = 0u, id_writer = 0u, bool_writer = 0u;
            if (!DecodeBlTarget(pc + 24u, w[6], &type_writer) ||
                !DecodeBlTarget(pc + 56u, w[14], &id_writer) ||
                !DecodeBlTarget(pc + 84u, w[21], &bool_writer) ||
                type_writer != id_writer || type_writer == bool_writer) continue;
            const uint32_t cid = (w[0] >> 10u) & 0xfffu;
            if (cid == 0u) continue;
            ++*count;
            *offset = pc;
        }
    }
    return *count == 1u;
}

struct WidgetFamily { uintptr_t gate; uintptr_t serializer; uint32_t count; };
WidgetFamily FindWidgetFamily(const Image& image) {
    WidgetFamily result{};
    uintptr_t candidates[32]{};
    size_t stored = 0u;
    uint32_t total = 0u;
    if (!CollectBytes(image, kWidgetGatePattern, candidates, 32u, &stored, &total) || total > stored) return result;
    for (size_t i = 0u; i < stored; ++i) {
        const uintptr_t gate = candidates[i];
        uint32_t w[7]{};
        uintptr_t reject = 0u, second_reject = 0u, span = 0u;
        if (!ReadInsns(image, gate, w, 7u) ||
            !DecodeWidgetReject(gate + 8u, w[2], &reject) ||
            !DecodeWidgetReject(gate + 24u, w[6], &second_reject) || reject != second_reject ||
            reject <= gate + 28u || reject > gate + 512u ||
            !Contains(image, reject, 16u, kFlagRead | kFlagExec) ||
            !DecodeBlTarget(gate + 20u, w[5], &span) || !IsWidgetSpanContract(image, span)) continue;
        // Skipping the two eligibility checks must land on the native success
        // block that restores its own receivers from the Dart frame.
        uint32_t success[3]{};
        if (!ReadInsns(image, gate + 28u, success, 3u) ||
            (success[0] & 0xffe00fffu) != 0xf84003a0u ||
            (success[1] & 0xffe00fffu) != 0xf84003a2u ||
            success[2] != 0xaa0003e1u) continue;
        uintptr_t serializer = 0u;
        uint32_t serializers = 0u;
        if (!FindWidgetSerializer(image, (w[0] >> 12u) & 0x1ffu, &serializer, &serializers)) continue;
        // Success records the selected object before logging and a true return.
        const uint8_t state_store[] = {0x20, 0x70, 0x03, 0xb8}; // stur w0,[x1,#0x37]
        const uint8_t state_mask[] = {0xff, 0x0f, 0xe0, 0xff};
        uintptr_t success_store = 0u;
        if (!FindBytesForward(image, gate + 28u, reject - gate - 28u,
                {state_store, state_mask, sizeof(state_store)}, &success_store)) continue;
        ++result.count;
        result.gate = gate;
        result.serializer = serializer;
    }
    return result;
}
bool FindWidgetGate(const Image& image, uintptr_t* out, uint32_t* count) {
    const auto family = FindWidgetFamily(image);
    *count = family.count;
    if (family.count != 1u) return false;
    *out = family.gate;
    return true;
}
bool FindWidgetType(const Image& image, uintptr_t* out, uint32_t* count) {
    const auto family = FindWidgetFamily(image);
    *count = family.count;
    if (family.count != 1u) return false;
    *out = family.serializer;
    return true;
}
constexpr uint32_t kWidgetTypeWord = 0xf100003fu;
constexpr uint32_t kWidgetTypeMask = 0xffc003ffu;
const SiteSpec kAssistantWidgetSites[] = {
    {"widget_eligibility", {nullptr, nullptr, 0u}, FindWidgetGate, true, kWidgetGatePattern, 0u},
    {"app_widget_type", {nullptr, nullptr, 0u}, FindWidgetType, true,
     {reinterpret_cast<const uint8_t*>(&kWidgetTypeWord),
      reinterpret_cast<const uint8_t*>(&kWidgetTypeMask), 4u}, 0u},
};

constexpr SiteSpec kClearButtonSites[] = {
    {"insert_overlay",
     {kInsertClearButtonOverlayPrologue, nullptr,
      sizeof(kInsertClearButtonOverlayPrologue)},
     FindInsertOverlay,
     true,
     {kInsertClearButtonOverlayPrologue, nullptr,
      sizeof(kInsertClearButtonOverlayPrologue)},
     0u},
};

constexpr SiteSpec kFolderColumnsSites[] = {
    {"grid_return",
     {kFolderGridReturnEpilogue, nullptr, sizeof(kFolderGridReturnEpilogue)},
     nullptr,
     true,
     {kFolderGridReturnEpilogue, nullptr, sizeof(kFolderGridReturnEpilogue)},
     0u},
    // The preview keeps a fixed three-column rendering while the opened folder is
    // resized, so a missing preview guard must not silently change the desktop.
    {"preview_icon",
     {nullptr, nullptr, 0u},
     FindPreviewIcon,
     true,
     {kPreviewIconReturnEpilogue, nullptr, sizeof(kPreviewIconReturnEpilogue)},
     0u},
    {"preview_items",
     {nullptr, nullptr, 0u},
     FindPreviewItems,
     true,
     {kPreviewItemsReturnEpilogue, nullptr,
      sizeof(kPreviewItemsReturnEpilogue)},
     0u},
    {"preview_set_items",
     {nullptr, nullptr, 0u},
     FindPreviewSetItems,
     true,
     // Masked: the two verified builds encode a different pool displacement in
     // this preamble, so only the masked form is a build-independent contract.
     {kIcPreamble, kIcPreambleMask, sizeof(kIcPreamble)},
     0u},
    {"preview_continuation",
     {nullptr, nullptr, 0u},
     FindPreviewContinuation,
     true,
     {kSetItemsContinuation, nullptr, sizeof(kSetItemsContinuation)},
     0u},
    {"cache_reader", {nullptr, nullptr, 0u}, FindFolderCacheReader, true,
     {kIcPreamble, kIcPreambleMask, sizeof(kIcPreamble)}, 0u},
    {"folder_open", {kFolderOpenEntry, kFolderOpenMask, sizeof(kFolderOpenEntry)},
     nullptr, true, {kFolderOpenEntry, kFolderOpenMask, sizeof(kFolderOpenEntry)}, 0u},
};

}  // namespace

const TargetSpec kRecentsClearButtonTarget = {
    "recents_clear_button", "libapp.so", kClearButtonSites,
    sizeof(kClearButtonSites) / sizeof(kClearButtonSites[0])};

const TargetSpec kFolderColumnsTarget = {
    "folder_columns", "libapp.so", kFolderColumnsSites,
    sizeof(kFolderColumnsSites) / sizeof(kFolderColumnsSites[0])};

const TargetSpec kAssistantWidgetTarget = {
    "assistant_android_widgets", "libapp.so", kAssistantWidgetSites,
    sizeof(kAssistantWidgetSites) / sizeof(kAssistantWidgetSites[0])};

const TargetSpec* const kTargetSpecs[] = {
    &kRecentsClearButtonTarget,
    &kFolderColumnsTarget,
    &kAssistantWidgetTarget,
};

const size_t kTargetSpecCount =
        sizeof(kTargetSpecs) / sizeof(kTargetSpecs[0]);

const char* FindTierName(FindTier tier) {
    switch (tier) {
        case FindTier::kMaskedBytes:
            return "masked-bytes";
        case FindTier::kStructural:
            return "structural";
        case FindTier::kRejected:
        default:
            return "rejected";
    }
}

TargetResult ResolveTarget(const Image& image, const TargetSpec& spec) {
    TargetResult result{};
    result.id = spec.id;
    result.resolved = false;
    result.verified = false;
    result.reason = "not_attempted";
    result.failure = "none";
    if (spec.sites == nullptr || spec.site_count == 0u) {
        result.reason = "no_sites";
        return result;
    }
    if (spec.site_count > sizeof(result.sites) / sizeof(result.sites[0])) {
        result.reason = "too_many_sites";
        return result;
    }
    result.site_count = spec.site_count;
    // Every site is evaluated even after one fails, so a rejection names all the
    // sites that could not be resolved instead of only the first. A partial report
    // is what turns "the feature did not install" into a one-line diagnosis.
    bool required_failed = false;
    bool all_verified = true;
    const char* first_failure = nullptr;
    const char* failure = "none";
    for (size_t index = 0u; index < spec.site_count; ++index) {
        const SiteSpec& site = spec.sites[index];
        SiteResult& out = result.sites[index];
        out.name = site.name;
        out.offset = 0u;
        out.candidates = 0u;
        out.tier = FindTier::kRejected;
        out.found = false;
        out.verified = false;
        out.resolved = false;

        // T1: masked bytes, only when the site actually carries byte evidence.
        if (site.pattern.bytes != nullptr && site.pattern.size != 0u) {
            uintptr_t offset = 0u;
            uint32_t candidates = 0u;
            if (FindUniqueBytes(image, site.pattern, &offset, &candidates)) {
                out.offset = offset;
                out.candidates = 1u;
                out.tier = FindTier::kMaskedBytes;
                out.found = true;
            } else {
                out.candidates = candidates;
            }
        }

        // T2/T3: structural evidence, used only when T1 could not decide.
        if (site.structural != nullptr) {
            uintptr_t offset = 0u;
            uint32_t candidates = 0u;
            if (site.structural(image, &offset, &candidates)) {
                out.offset = offset;
                out.candidates = candidates;
                out.tier = FindTier::kStructural;
                out.found = true;
            } else if (candidates > out.candidates) {
                out.candidates = candidates;
            }
        }

        // Defence in depth. The finding evidence already observed these bytes,
        // but re-reading them here keeps the contract explicit and catches a
        // caller that resolved against a stale image view.
        if (out.found) {
            if (site.verify.bytes != nullptr && site.verify.size != 0u) {
                out.verified = MatchBytes(image,
                                          out.offset + site.verify_delta,
                                          site.verify,
                                          kFlagRead | kFlagExec);
            } else {
                out.verified = true;
            }
        }
        // A site is usable once it is located. Verification is recorded, not
        // enforced: see TargetResult for why.
        out.resolved = out.found;
        if (!out.verified) all_verified = false;

        if (!out.found) {
            if (first_failure == nullptr) {
                first_failure = site.name;
                failure = "not_found";
            }
            if (site.required) required_failed = true;
        }
    }
    result.resolved = !required_failed;
    result.verified = result.resolved && all_verified;
    result.reason = first_failure != nullptr ? first_failure : "resolved";
    result.failure = failure;
    return result;
}

}  // namespace hypertweak::native::dart
