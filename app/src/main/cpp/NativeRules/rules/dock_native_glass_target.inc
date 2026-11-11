// SPDX-License-Identifier: Apache-2.0
// The phone Hotseat's optional background child and the Launcher's own
// HyperOS 4 glass arm. Both are matched by instruction shape; the glass arm is
// reached only through a reconstructed frame of its own builder.
namespace {
namespace native_dock_glass {

using native_dock_refresh::Words;
using native_dock_refresh::UnscaledOffset;
constexpr uint32_t kExec = kFlagRead | kFlagExec;
constexpr uint32_t kField = 0xffe00fffu;
constexpr uint32_t kLoad = 0xffc003ffu;
constexpr uint32_t kAddHigh = 0xffc003ffu;
constexpr uint32_t kCall = 0xfc000000u;
constexpr uint32_t kTest = 0xfff8001fu;
constexpr uint32_t kConditional = 0xff00001fu;
constexpr uint32_t kPushFrame = 0xa9bf79fdu;  // stp x29, x30, [x15, #-16]!
constexpr uint32_t kSetFrame = 0xaa0f03fdu;   // mov x29, x15

// The phone Hotseat LayoutBuilder creates its child list, then appends the
// background factory's result only while a captured Rx flag is true.
constexpr uint32_t kBranch[] = {
    0xf9400361,  // ldr x1, [pp, #list_type]
    0xd2800002,  // mov x2, #0
    0x94000000,  // bl allocate growable list
    0xaa0003e2,  // mov x2, x0
    0xf85f83a0,  // ldur x0, [fp, #-8]
    0xf81d83a2,  // stur x2, [fp, #-0x28]
    0xb8400001,  // ldur w1, [x0, #flag]       <- gate
    0x8b1c8021,  // add x1, x1, heap, lsl #32
    0x37200001,  // tbnz w1, #4, skip
    0xf85e83a3,  // ldur x3, [fp, #-0x18]
    0xb840f061,  // ldur w1, [x3, #0xf]
    0x8b1c8021,  // add x1, x1, heap, lsl #32
    0x94000000,  // bl background factory
    0xaa0003e2,  // mov x2, x0                 <- return gate
    0xf84003a0,  // ldur x0, [fp, #list]
    0xf80003a2,  // stur x2, [fp, #result]
    0xb8400001,  // ldur w1, [x0, #length]
};
constexpr uint32_t kBranchMasks[] = {
    kLoad, ~0u, kCall, ~0u, ~0u, ~0u, kField, ~0u, kTest, ~0u, ~0u, ~0u, kCall, ~0u,
    kField, kField, kField,
};
constexpr size_t kResumeOffset = 52;
constexpr size_t kGateOffset = 24;
constexpr size_t kFactoryCallOffset = 48;
constexpr uint32_t kSkipMove = 0xaa0203e3u;  // mov x3, x2: the list without a background

// `return BackgroundWidget()`: the factory body is one allocation.
constexpr uint32_t kFactory[] = {
    kPushFrame, kSetFrame, 0x94000000, kDartRestoreFrame, kDartPopFrame, kDartReturn,
};
constexpr uint32_t kFactoryMasks[] = {~0u, ~0u, kCall, ~0u, ~0u, ~0u};

// _buildStrategy(state, mode, radius): mode <= 0 selects the first arm, which
// allocates the glass background and fills radius, tint and opacity only from
// its own frame and the object pool.
constexpr uint32_t kStrategyEntry[] = {
    kPushFrame, kSetFrame,
    0xd10081ef,  // sub x15, x15, #0x20
    0xf81f83a1,  // stur x1, [fp, #-8]
    0xf81f03a2,  // stur x2, [fp, #-0x10]
    0xfc1e03a0,  // stur d0, [fp, #-0x20]
};
constexpr uint32_t kStrategy[] = {
    0xf85f03a0,  // ldur x0, [fp, #-0x10]
    0xf8407003,  // ldur x3, [x0, #7]       mode index
    0xf100047f,  // cmp x3, #1
    0x5400000c,  // b.gt
    0xf100007f,  // cmp x3, #0
    0x5400000c,  // b.gt
    0x94000000,  // bl allocate glass        <- entry
    0xf801f01f,  // stur xzr, [x0, #0x1f]
    0xfc5e03a0,  // ldur d0, [fp, #-0x20]    radius
    0xfc00b000,  // stur d0, [x0, #0xb]
    0x91400361,  // add x1, pp, #tint_hi, lsl #12
    0xf9400021,  // ldr x1, [x1, #tint_lo]
    0xb8013001,  // stur w1, [x0, #0x13]
    0x1e6e1001,  // fmov d1, #1.0
    0xfc017001,  // stur d1, [x0, #0x17]
    kDartRestoreFrame, kDartPopFrame, kDartReturn,
};
constexpr uint32_t kStrategyMasks[] = {
    ~0u, ~0u, ~0u, kConditional, ~0u, kConditional, kCall, ~0u, ~0u, ~0u,
    kAddHigh, kLoad, ~0u, ~0u, ~0u, ~0u, ~0u, ~0u,
};
constexpr size_t kGlassOffset = 24;
constexpr uintptr_t kStrategyFrame = 0x20;

// EdgeInsets `operator +`: four sums spilled unboxed, then one allocation that
// copies the spills into left, top, right and bottom. Entering at the
// allocation with a rebuilt frame yields EdgeInsets from our four values.
constexpr uint32_t kSubFrame = 0xd10001efu;  // sub x15, x15, #frame
constexpr uint32_t kInsets[] = {
    kPushFrame, kSetFrame, kSubFrame,
    0xfc400020, 0xfc400041, 0x1e612800, 0xfc0003a0,
    0xfc400020, 0xfc400041, 0x1e612800, 0xfc0003a0,
    0xfc400020, 0xfc400041, 0x1e612800, 0xfc0003a0,
    0xfc400020, 0xfc400041, 0x1e612800, 0xfc0003a0,
    0x94000000,                                   // bl allocate EdgeInsets  <- entry
    0xfc4003a0, 0xfc000000, 0xfc4003a0, 0xfc000000,
    0xfc4003a0, 0xfc000000, 0xfc4003a0, 0xfc000000,
    kDartRestoreFrame, kDartPopFrame, kDartReturn,
};
constexpr uint32_t kDoubleField = 0xffe00fffu;
constexpr uint32_t kSpill = 0xffe00fe0u;
constexpr uint32_t kInsetsMasks[] = {
    ~0u, ~0u, kLoad,
    kDoubleField, kDoubleField, 0xffffffe0u, kSpill,
    kDoubleField, kDoubleField, 0xffffffe0u, kSpill,
    kDoubleField, kDoubleField, 0xffffffe0u, kSpill,
    kDoubleField, kDoubleField, 0xffffffe0u, kSpill,
    kCall,
    kDoubleField, kDoubleField, kDoubleField, kDoubleField,
    kDoubleField, kDoubleField, kDoubleField, kDoubleField,
    ~0u, ~0u, ~0u,
};
constexpr size_t kInsetsEntry = 19 * 4;

// `return Padding(padding: insets, child: build())`: the call result becomes
// the child; the padding is a frame slot filled by the EdgeInsets allocator.
constexpr uint32_t kPadding[] = {
    0x94000000,  // bl child builder
    0xf80003a0,  // stur x0, [fp, #child]       <- return address
    0x94000000,  // bl allocate Padding
    0xf84003a1,  // ldur x1, [fp, #padding]
    0xb800f001,  // stur w1, [x0, #0xf]
    0xf84003a1,  // ldur x1, [fp, #child]
    0xb800b001,  // stur w1, [x0, #0xb]
    kDartRestoreFrame, kDartPopFrame, kDartReturn,
};
constexpr uint32_t kPaddingMasks[] = {
    kCall, kField, kCall, kField, ~0u, kField, ~0u, ~0u, ~0u, ~0u,
};


// Hotseat item builder: `controller.calHotseatCenterPosition()` through a
// switchable call, whose Offset result the item code keeps in a frame slot.
// The gate follows the call's return.
constexpr uint32_t kCenter[] = {
    0xf84003a0,  // ldur x0, [fp, #context]
    0xf80003a1,  // stur x1, [fp, #x]          (previous item result)
    0xb8400002,  // ldur w2, [x0, #controller]
    0x8b1c8042,  // add x2, x2, heap, lsl #32
    0xf90001e2,  // str x2, [x15]              receiver
    0xd2800004,  // mov x4, #0
    0xf94001e0,  // ldr x0, [x15]
    0x91400370,  // add x16, pp, #hi, lsl #12
    0x91000210,  // add x16, x16, #lo
    0xa940161e,  // ldp x30, x5, [x16]
    0xd63f03c0,  // blr x30
    0xaa0003e3,  // mov x3, x0                 <- center return
    0xf84003a2,  // ldur x2, [fp, #item]       <- center gate
    0xf80003a3,  // stur x3, [fp, #center]
    0xb8400040,  // ldur w0, [x2, #field]
    0x8b1c8000,  // add x0, x0, heap, lsl #32
};
constexpr uint32_t kCenterMasks[] = {
    kField, kField, kField, ~0u, ~0u, ~0u, ~0u, kAddHigh, kAddHigh, ~0u, ~0u, ~0u,
    kField, kField, kField, ~0u,
};
constexpr size_t kCenterReturn = 11 * 4;
constexpr size_t kCenterGate = 12 * 4;

// The per-item unlock animation wrapper: child, center position, item info,
// Hotseat kind 2, fixed-screen flag, screen -1 and a debug title. The gate
// follows the field stores, before the next allocation.
constexpr uint32_t kWrap[] = {
    0x94000000,  // bl allocate wrapper         <- wrap entry
    0xaa0003e2,  // mov x2, x0
    0xf84003a0,  // ldur x0, [fp, #child]
    0xf80003a2,  // stur x2, [fp, #result]
    0xb800b040,  // stur w0, [x2, #0xb]
    0xf84003a0,  // ldur x0, [fp, #position]
    0xb800f040,  // stur w0, [x2, #0xf]
    0xf84003a0,  // ldur x0, [fp, #info]
    0xb8013040,  // stur w0, [x2, #0x13]
    0xd2800040,  // mov x0, #2
    0xf8017040,  // stur x0, [x2, #0x17]
    0x910082c3,  // add x3, null, #0x20         true
    0xb8027043,  // stur w3, [x2, #0x27]
    0x92800004,  // mov x4, #-1
    0xf801f044,  // stur x4, [x2, #0x1f]
    0xf84003a1,  // ldur x1, [fp, #title]       <- wrap gate
    0xb800b041,  // stur w1, [x2, #title_field]
    0x91400361,  // add x1, pp, #hi, lsl #12
    0xf9400021,  // ldr x1, [x1, #lo]
    0x94000000,  // bl allocate next
};
constexpr uint32_t kWrapMasks[] = {
    kCall, ~0u, kField, kField, ~0u, kField, ~0u, kField, ~0u, ~0u, ~0u, ~0u, ~0u, ~0u, ~0u,
    kField, kField, kAddHigh, kLoad, kCall,
};
constexpr size_t kWrapGate = 15 * 4;

uint32_t Insn(const Image& image, uintptr_t at) {
    uint32_t word = 0;
    ReadInsn(image, at, &word);
    return word;
}

bool Calls(const Image& image, uintptr_t pc, uintptr_t* out) {
    return DecodeBlTarget(pc, Insn(image, pc), out) && Contains(image, *out, 4, kExec);
}

// tbz/tbnz: imm14 at bits 5..18, signed, in words.
bool DecodeBitTestTarget(uintptr_t pc, uint32_t instruction, uintptr_t* target) {
    if ((instruction & 0x7e000000u) != 0x36000000u) return false;
    int64_t words = static_cast<int64_t>((instruction >> 5u) & 0x3fffu);
    if (words & 0x2000) words -= 0x4000;
    const int64_t resolved = static_cast<int64_t>(pc) + words * 4;
    if (resolved <= 0) return false;
    *target = static_cast<uintptr_t>(resolved);
    return true;
}

bool FindOne(const Image& image, BytePattern pattern, uintptr_t* site) {
    uint32_t count = 0;
    return FindUniqueBytes(image, pattern, site, &count);
}

int32_t Slot(uint32_t instruction) { return static_cast<int32_t>(UnscaledOffset(instruction)) - 0x200; }

// Allocation stub: mov x2, #tags_lo; movk x2, #tags_hi, lsl #16; b shared.
bool StubCid(const Image& image, uintptr_t stub, uint32_t* cid) {
    const uint32_t lo = Insn(image, stub), hi = Insn(image, stub + 4), jump = Insn(image, stub + 8);
    if ((lo & 0xffe0001fu) != 0xd2800002u || (hi & 0xffe0001fu) != 0xf2a00002u ||
        (jump & 0xfc000000u) != 0x14000000u) return false;
    const uint32_t tags = ((lo >> 5u) & 0xffffu) | (((hi >> 5u) & 0xffffu) << 16u);
    *cid = tags >> 12u;
    return *cid != 0;
}

uint32_t FrameSize(uint32_t instruction) { return (instruction >> 10u) & 0xfffu; }

bool DecodeInsets(const Image& image, uintptr_t entry, NativeDockGlassLayout* out) {
    if (entry < kInsetsEntry) return false;
    const uintptr_t begin = entry - kInsetsEntry;
    uintptr_t stub = 0;
    if (!MatchBytes(image, begin, Words(kInsets, kInsetsMasks), kExec) ||
        !Calls(image, entry, &stub) || !StubCid(image, stub, &out->insets_cid)) return false;
    const uint32_t frame = Insn(image, begin + 8);
    if ((frame & 0xffc003ffu) != kSubFrame || FrameSize(frame) == 0 || FrameSize(frame) > 0x40) return false;
    out->insets_frame = FrameSize(frame);
    for (unsigned index = 0; index < 4; ++index) {
        const uintptr_t group = begin + 12 + index * 16;
        const uint32_t field = UnscaledOffset(Insn(image, group));
        const uint32_t sum = Insn(image, group + 8) & 31u;
        const uint32_t spill = Insn(image, group + 12);
        const uintptr_t copy = entry + 4 + index * 8;
        // Each sum is spilled from its own register and copied to its own field,
        // in field order left, top, right, bottom.
        if (field != 7u + index * 8u || UnscaledOffset(Insn(image, group + 4)) != field ||
            (spill & 31u) != sum || sum < 2u ||
            UnscaledOffset(Insn(image, copy)) != UnscaledOffset(spill) ||
            UnscaledOffset(Insn(image, copy + 4)) != field) return false;
        out->insets_slots[index] = Slot(spill);
        if (out->insets_slots[index] >= 0 ||
            static_cast<uint32_t>(-out->insets_slots[index]) > out->insets_frame) return false;
        for (unsigned earlier = 0; earlier < index; ++earlier)
            if (out->insets_slots[earlier] == out->insets_slots[index]) return false;
    }
    out->insets_entry = entry;
    return true;
}

bool DecodePadding(const Image& image, uintptr_t returned, uintptr_t insets_stub,
                   NativeDockGlassLayout* out) {
    if (returned < 4) return false;
    const uintptr_t site = returned - 4;
    uintptr_t stub = 0;
    if (!MatchBytes(image, site, Words(kPadding, kPaddingMasks), kExec) ||
        !Calls(image, site + 8, &stub) || !StubCid(image, stub, &out->padding_cid) ||
        out->padding_cid == out->insets_cid) return false;
    const int32_t child = Slot(Insn(image, site + 4));
    const int32_t padding = Slot(Insn(image, site + 12));
    if (Slot(Insn(image, site + 20)) != child || child == padding || child >= 0 || padding >= 0) return false;
    uintptr_t entry = 0;
    for (uintptr_t distance = 4; distance <= 0x400 && distance <= site; distance += 4) {
        if (IsDartPrologue(image, site - distance)) { entry = site - distance; break; }
    }
    if (!entry) return false;
    const uint32_t frame = Insn(image, entry + 8);
    if ((frame & 0xffc003ffu) != kSubFrame || FrameSize(frame) > 0x100 ||
        static_cast<uint32_t>(-child) > FrameSize(frame) ||
        static_cast<uint32_t>(-padding) > FrameSize(frame)) return false;
    // The padding slot must hold an object from the same EdgeInsets allocator.
    bool fed = false;
    for (uintptr_t at = entry + 12; at + 8 <= site; at += 4) {
        uintptr_t called = 0;
        const uint32_t store = Insn(image, at + 4);
        if (Calls(image, at, &called) && called == insets_stub &&
            (store & kField) == 0xf80003a0u && Slot(store) == padding) fed = true;
    }
    if (!fed) return false;
    out->padding_return = returned;
    out->padding_frame = FrameSize(frame);
    out->padding_slot = padding;
    return true;
}

// The Hotseat LayoutBuilder closure reloads its constraints argument and
// reads maxWidth; BoxConstraints stores maxHeight two doubles later.
bool DecodeConstraints(const Image& image, uintptr_t gate, NativeDockGlassLayout* out) {
    uintptr_t entry = 0;
    for (uintptr_t distance = 4; distance <= 0x400 && distance <= gate; distance += 4) {
        if (IsDartPrologue(image, gate - distance)) { entry = gate - distance; break; }
    }
    if (!entry) return false;
    unsigned found = 0;
    for (uintptr_t at = entry + 8; at + 4 <= gate; at += 4) {
        const uint32_t load = Insn(image, at);
        if ((load & 0xffc003ffu) != 0xf94003a2u) continue;  // ldr x2, [fp, #arg]
        for (uintptr_t next = at + 4; next < at + 36 && next < gate; next += 4) {
            const uint32_t word = Insn(image, next);
            if ((word & 0xffe00fffu) == 0xfc400040u) {        // ldur d0, [x2, #maxWidth]
                const uint32_t width = UnscaledOffset(word);
                if ((width & 7u) != 7u || width + 0x10u > 0xffu) return false;
                out->constraints_arg = static_cast<int32_t>(((load >> 10u) & 0xfffu) * 8u);
                out->max_height_field = width + 0x10u;
                ++found;
                break;
            }
            // Stop when x2 is redefined before its double load.
            if ((word & 31u) == 2u && (word & 0x3b000000u) != 0x38000000u) break;
        }
    }
    return found == 1 && out->constraints_arg >= 0x10;
}


uintptr_t PrologueBefore(const Image& image, uintptr_t at, uintptr_t limit) {
    for (uintptr_t distance = 4; distance <= limit && distance <= at; distance += 4)
        if (IsDartPrologue(image, at - distance)) return at - distance;
    return 0;
}

// Direct branch targets of b, b.cond, cbz/cbnz and tbz/tbnz.
bool BranchTarget(uintptr_t pc, uint32_t word, uintptr_t* target) {
    int64_t words = 0;
    if ((word & 0xfc000000u) == 0x14000000u) {
        words = word & 0x3ffffffu; if (words & 0x2000000) words -= 0x4000000;
    } else if ((word & 0xff000010u) == 0x54000000u || (word & 0x7e000000u) == 0x34000000u) {
        words = (word >> 5u) & 0x7ffffu; if (words & 0x40000) words -= 0x80000;
    } else if (DecodeBitTestTarget(pc, word, target)) {
        return true;
    } else {
        return false;
    }
    *target = static_cast<uintptr_t>(static_cast<int64_t>(pc) + words * 4);
    return true;
}

// A patched window must only be entered at its first word.
bool NoBranchInto(const Image& image, uintptr_t begin, uintptr_t end, uintptr_t window) {
    for (uintptr_t at = begin; at < end; at += 4) {
        uintptr_t target = 0;
        if (BranchTarget(at, Insn(image, at), &target) && target > window && target < window + 16) return false;
    }
    return true;
}

// `add xd, xn, #hi, lsl #12` followed by an `add` or scaled `ldr` low part.
uint32_t PoolOffset(uint32_t high, uint32_t low, bool load) {
    const uint32_t lo = (low >> 10u) & 0xfffu;
    return (((high >> 10u) & 0xfffu) << 12u) + (load ? lo * 8u : lo);
}

bool SlotIn(int32_t slot, uint32_t frame) {
    return slot < 0 && static_cast<uint32_t>(-slot) <= frame && (slot & 7) == 0;
}

// fmov d, #imm8 (VFPExpandImm): sign, 3-bit exponent, 4-bit fraction.
double FmovImmediate(uint32_t word) {
    const uint32_t imm = (word >> 13u) & 0xffu;
    const int exponent = static_cast<int>(((imm >> 4u) & 7u) ^ 4u) - 3;
    double value = (16.0 + static_cast<double>(imm & 15u)) / 16.0;
    for (int index = 0; index < exponent; ++index) value *= 2.0;
    for (int index = 0; index > exponent; --index) value /= 2.0;
    return (imm & 0x80u) ? -value : value;
}

// The item builder's inline Dock cell width (GridController.dockCellWidth):
//   flag = static A.flag; cell = flag ? currentConfig().alt : currentConfig().cell + extra
// The config getter is called twice with the same receiver slot.
constexpr uint32_t kCell[] = {
    0xb8400001,  // ldur w1, [x0, #flag]
    0x8b1c8021,  // add x1, x1, heap, lsl #32
    0x37200101,  // tbnz w1, #4, alt (+8 words)
    0xf84003a1,  // ldur x1, [fp, #receiver]
    0x94000000,  // bl currentConfig
    0xfc400000,  // ldur d0, [x0, #cell]
    0x1e601001,  // fmov d1, #extra
    0x1e612802,  // fadd d2, d0, d1
    0x4ea21c41,  // mov v1.16b, v2.16b
    0x14000006,  // b join
    0x1e601001,  // fmov d1, #extra             <- alt
    0xf84003a1,  // ldur x1, [fp, #receiver]
    0x94000000,  // bl currentConfig
    0xfc400000,  // ldur d0, [x0, #alt]
    0x4ea01c01,  // mov v1.16b, v0.16b           -> join, d1 = cell width
};
constexpr uint32_t kCellMasks[] = {
    kField, ~0u, ~0u, kField, kCall, kField, 0xffe01fffu, ~0u, ~0u, ~0u, 0xffe01fffu, kField, kCall,
    kField, ~0u,
};

// static table load, static slot load, null test, branch to `target`.
bool StaticBefore(const Image& image, uintptr_t target, uint32_t reg, uint32_t* table, uint32_t* slot) {
    for (uintptr_t at = target - 16; at + 0x80 > target && at > 16; at -= 4) {
        const uint32_t load_table = Insn(image, at), load_slot = Insn(image, at + 4);
        const uint32_t compare = Insn(image, at + 8), branch = Insn(image, at + 12);
        uintptr_t to = 0;
        if ((load_table & 0xffc003ffu) != (0xf9400340u | reg) ||                   // ldr xR, [thr, #table]
            (load_slot & 0xffc003ffu) != (0xf9400000u | (reg << 5u) | reg) ||       // ldr xR, [xR, #slot]
            compare != (0x6b16001fu | (reg << 5u)) ||                              // cmp wR, null
            (branch & 0xff00001fu) != 0x54000001u || !BranchTarget(at + 12, branch, &to) || to != target)
            continue;
        *table = ((load_table >> 10u) & 0xfffu) * 8u;
        *slot = ((load_slot >> 10u) & 0xfffu) * 8u;
        return true;
    }
    return false;
}

// currentConfig: B = static slot; return (B ?? find()).rx.value, and the Rx
// getter returns rx.value_field unless it still holds the sentinel.
bool DecodeConfig(const Image& image, uintptr_t getter, uint32_t table, NativeDockGlassLayout* out) {
    if (!IsDartPrologue(image, getter)) return false;
    const uintptr_t body = getter + 12;
    uint32_t slot_table = 0, slot = 0;
    uintptr_t join = 0;
    const uint32_t branch = Insn(image, body + 12);
    if (!BranchTarget(body + 12, branch, &join) || join <= body + 16 || join > body + 0x80 ||
        !StaticBefore(image, join, 0, &slot_table, &slot) || slot_table != table) return false;
    const uint32_t rx = Insn(image, join), widen = Insn(image, join + 4);
    uintptr_t value = 0;
    if ((rx & 0xffe00fffu) != 0xb8400001u || widen != 0x8b1c8021u || !Calls(image, join + 8, &value) ||
        !IsDartPrologue(image, value)) return false;
    // Rx getter: ... ldur w0, [x1, #value]; add x0, x0, heap; ldr x16, [pp, #sentinel];
    // cmp w0, w16; b.eq late-error.
    for (uintptr_t at = value + 12; at < value + 0x80; at += 4) {
        const uint32_t load = Insn(image, at), add = Insn(image, at + 4), sentinel = Insn(image, at + 8);
        if ((load & 0xffe00fffu) != 0xb8400020u || add != 0x8b1c8000u ||
            (sentinel & 0xffc003ffu) != 0xf9400370u || Insn(image, at + 12) != 0x6b10001fu ||
            (Insn(image, at + 16) & 0xff00001fu) != 0x54000000u) continue;
        out->config_static = slot;
        out->config_rx_field = UnscaledOffset(rx);
        out->rx_value_field = UnscaledOffset(load);
        out->sentinel_pool = ((sentinel >> 10u) & 0xfffu) * 8u;
        return (out->config_rx_field & 3u) == 3u && (out->rx_value_field & 3u) == 3u;
    }
    return false;
}

bool DecodeCell(const Image& image, uintptr_t builder, NativeDockGlassLayout* out) {
    uintptr_t end = builder + 12;
    while (end < builder + 0x2000 && !IsDartPrologue(image, end)) end += 4;
    uintptr_t cell = 0;
    uint32_t count = 0;
    if (!CountBytes(image, builder, end, Words(kCell, kCellMasks), &count) || count != 1 ||
        !FindBytesForward(image, builder, end - builder, Words(kCell, kCellMasks), &cell)) return false;
    uintptr_t first = 0, second = 0, alt = 0;
    const uint32_t extra = Insn(image, cell + 24);
    if (!Calls(image, cell + 16, &first) || !Calls(image, cell + 48, &second) || first != second ||
        Insn(image, cell + 12) != Insn(image, cell + 44) || extra != Insn(image, cell + 40) ||
        !DecodeBitTestTarget(cell + 8, Insn(image, cell + 8), &alt) || alt != cell + 40) return false;
    // The joined width is halved and added to the item position x.
    bool used = false;
    for (uintptr_t at = cell + 60; at < cell + 100; at += 4) {
        const uint32_t two = Insn(image, at), divide = Insn(image, at + 4);
        if (two == 0x1e601000u && (divide & 0xfffffc1fu) == (0x1e601800u | (divide & 31u)) &&
            ((divide >> 5u) & 31u) == 1u && ((divide >> 16u) & 31u) == 0u) used = true;
    }
    uint32_t table = 0, flag_static = 0;
    if (!used || !StaticBefore(image, cell, 0, &table, &flag_static) ||
        !DecodeConfig(image, first, table, out)) return false;
    out->static_table = table;
    out->cell_flag_static = flag_static;
    out->cell_flag_field = UnscaledOffset(Insn(image, cell));
    out->cell_field = UnscaledOffset(Insn(image, cell + 20));
    out->alt_cell_field = UnscaledOffset(Insn(image, cell + 52));
    out->cell_extra = FmovImmediate(extra);
    return (out->cell_flag_field & 3u) == 3u && (out->cell_field & 3u) == 3u && (out->alt_cell_field & 3u) == 3u &&
        out->cell_extra >= 0.0 && out->cell_extra <= 64.0 && out->cell_flag_static != out->config_static;
}

bool WritesFrameSlot(uint32_t word, int32_t slot) {
    return ((word & 0xffe00fe0u) == 0xf80003a0u || (word & 0xffe00fe0u) == 0xb80003a0u) && Slot(word) == slot;
}

// Optional adaptive-width contract. The Hotseat builder passes the item list
// as x5, loaded from a context it keeps in a frame slot:
//   ldur xC, [fp, #slot]; ldur w5, [xC, #items]; add x5, x5, heap, lsl #32
// followed only by frame reloads of the other arguments. The item builder
// spills x5, publishes it into its own context and iterates it, reading the
// growable-list length field the resume words read. The Hotseat builder also
// derives the cell width as constraints.maxWidth / columns.
bool DecodeAdaptive(const Image& image, uintptr_t closure, uintptr_t gate, uintptr_t call,
                    uintptr_t builder, NativeDockGlassLayout* out) {
    uintptr_t list_load = 0;
    for (uintptr_t at = call; at > call - 0x20 && at > closure + 8; at -= 4) {
        if ((Insn(image, at) & 0xffe00c1fu) == 0xb8400005u) { list_load = at; break; }  // ldur w5, [xC, #items]
    }
    if (!list_load || Insn(image, list_load + 4) != 0x8b1c80a5u) return false;  // add x5, x5, heap, lsl #32
    for (uintptr_t at = list_load + 8; at < call; at += 4) {
        const uint32_t word = Insn(image, at);
        if ((word & 0xffe00fe0u) != 0xf84003a0u || (word & 31u) == 5u) return false;
    }
    const uint32_t list_word = Insn(image, list_load);
    const uint32_t context_word = Insn(image, list_load - 4);
    const uint32_t context_register = (list_word >> 5u) & 31u;
    if ((context_word & 0xffe00fe0u) != 0xf84003a0u || (context_word & 31u) != context_register) return false;
    const int32_t slot = Slot(context_word);
    const uint32_t field = UnscaledOffset(list_word);
    if (slot >= 0 || (slot & 7) != 0 || (field & 3u) != 3u || field >= 0x100u) return false;
    // The background gate reads the context from this slot, so it must be
    // written before the gate and never again until the item builder call.
    bool stored = false;
    for (uintptr_t at = closure; at < call; at += 4) {
        if (!WritesFrameSlot(Insn(image, at), slot)) continue;
        if (at >= gate) return false;
        stored = true;
    }
    if (!stored) return false;

    int32_t spill = 0;
    for (uintptr_t at = builder + 12; at < builder + 0x80 && !spill; at += 4) {
        const uint32_t word = Insn(image, at);
        if ((word & 0xffe00fffu) == 0xf80003a5u) spill = Slot(word);  // stur x5, [fp, #spill]
    }
    if (!spill) return false;
    uint32_t published = 0;
    for (uintptr_t at = builder + 12; at < builder + 0x100 && !published; at += 4) {
        const uint32_t load = Insn(image, at), store = Insn(image, at + 4);
        if ((load & 0xffe00fe0u) == 0xf84003a0u && Slot(load) == spill &&
            (store & 0xffe00c00u) == 0xb8000000u && (store & 31u) == (load & 31u))
            published = UnscaledOffset(store);
    }
    if (!published || (published & 3u) != 3u) return false;
    bool iterated = false;
    for (uintptr_t at = builder + 12; at < builder + 0x200 && !iterated; at += 4) {
        const uint32_t load = Insn(image, at);
        const uint32_t list = load & 31u;
        if ((load & 0xffe00c00u) != 0xb8400000u || UnscaledOffset(load) != published ||
            Insn(image, at + 4) != (0x8b1c8000u | (list << 5u) | list)) continue;
        for (uintptr_t next = at + 8; next < at + 20; next += 4) {
            const uint32_t length = Insn(image, next);
            if ((length & 0xffe00c00u) == 0xb8400000u && ((length >> 5u) & 31u) == list &&
                UnscaledOffset(length) == out->resume_length_field) iterated = true;
        }
    }
    if (!iterated) return false;

    const uint32_t width = out->max_height_field - 0x10u;
    unsigned found = 0;
    double columns = 0;
    for (uintptr_t at = closure + 8; at + 8 < gate; at += 4) {
        const uint32_t load = Insn(image, at), constant = Insn(image, at + 4), divide = Insn(image, at + 8);
        if ((load & 0xffe00c00u) != 0xfc400000u || UnscaledOffset(load) != width ||
            (constant & 0xffe01fe0u) != 0x1e601000u || (divide & 0xffe0fc00u) != 0x1e601800u ||
            ((divide >> 5u) & 31u) != (load & 31u) || ((divide >> 16u) & 31u) != (constant & 31u)) continue;
        columns = FmovImmediate(constant);
        ++found;
    }
    if (found != 1 || columns < 3.0 || columns > 6.0 || columns != static_cast<double>(static_cast<uint32_t>(columns)))
        return false;
    if (!DecodeCell(image, builder, out)) return false;
    out->items_context_slot = slot;
    out->items_field = field;
    out->columns = static_cast<uint32_t>(columns);
    return true;
}

// The item builder and its unlock wrapper. The controller reaches it as the
// sixth argument, captured into a context field the center call reads.
bool DecodeWrap(const Image& image, uintptr_t gate, uintptr_t center, uintptr_t wrap,
                NativeDockGlassLayout* out) {
    if (center < kCenterGate || wrap < kWrapGate) return false;
    const uintptr_t center_begin = center - kCenterGate;
    const uintptr_t wrap_begin = wrap - kWrapGate;
    if (!MatchBytes(image, center_begin, Words(kCenter, kCenterMasks), kExec) ||
        !MatchBytes(image, wrap_begin, Words(kWrap, kWrapMasks), kExec)) return false;
    const uintptr_t entry = PrologueBefore(image, center_begin, 0x1000);
    if (!entry || entry != PrologueBefore(image, wrap_begin, 0x1000) || wrap_begin <= center_begin) return false;
    const uint32_t sub = Insn(image, entry + 8);
    if ((sub & 0xffc003ffu) != kSubFrame || FrameSize(sub) == 0 || FrameSize(sub) > 0x200) return false;
    const uint32_t frame = FrameSize(sub);
    uintptr_t end = wrap_begin + sizeof(kWrap);
    while (end < wrap_begin + 0x400 && !IsDartPrologue(image, end)) end += 4;
    uint32_t count = 0;
    if (!CountBytes(image, entry, end, Words(kCenter, kCenterMasks), &count) || count != 1 ||
        !CountBytes(image, entry, end, Words(kWrap, kWrapMasks), &count) || count != 1 ||
        !NoBranchInto(image, entry, end, center) || !NoBranchInto(image, entry, end, wrap)) return false;

    // Prologue: x6 spilled, then copied into the context the center call reads.
    const int32_t context = Slot(Insn(image, center_begin));
    const uint32_t field = UnscaledOffset(Insn(image, center_begin + 8));
    int32_t spill = 0;
    bool captured = false, published = false;
    for (uintptr_t at = entry + 12; at < entry + 0x80; at += 4) {
        const uint32_t word = Insn(image, at);
        if ((word & 0xffe00fffu) == 0xf80003a6u && !spill) spill = Slot(word);
        if (spill && (word & 0xffe00fe0u) == 0xf84003a0u && Slot(word) == spill) {
            const uint32_t next = Insn(image, at + 4);
            if ((next & 0xffe00fe0u) == 0xb8000060u && (next & 31u) == (word & 31u) &&
                UnscaledOffset(next) == field) captured = true;
        }
        if ((word & 0xffe00fffu) == 0xf80003a3u && Slot(word) == context) published = true;
    }
    if (!spill || !captured || !published || (field & 3u) != 3u) return false;

    uintptr_t stub = 0;
    out->build_frame = frame;
    out->build_child_slot = Slot(Insn(image, wrap_begin + 8));
    out->wrap_position_slot = Slot(Insn(image, wrap_begin + 20));
    const int32_t info = Slot(Insn(image, wrap_begin + 28));
    out->title_slot = Slot(Insn(image, wrap));
    out->title_field = UnscaledOffset(Insn(image, wrap + 4));
    out->title_pool = PoolOffset(Insn(image, wrap + 8), Insn(image, wrap + 12), true);
    // The item-cell flag stored as `true`: with it, the unlock animation looks up
    // item info or the screen id, neither of which the glass has.
    out->fixed_field = UnscaledOffset(Insn(image, wrap_begin + 48));
    out->center_call = PoolOffset(Insn(image, center_begin + 28), Insn(image, center_begin + 32), false);
    out->center_return = center_begin + kCenterReturn;
    out->center_item_slot = Slot(Insn(image, center));
    out->center_slot = Slot(Insn(image, center + 4));
    out->center_field = UnscaledOffset(Insn(image, center + 8));
    out->wrap_entry = wrap_begin;
    // Our path skips the center store, so the info slot stays null; it must
    // differ from every slot the path fills.
    const int32_t slots[] = {out->build_child_slot, out->wrap_position_slot, info, out->title_slot,
                             out->center_item_slot, out->center_slot, context};
    for (int32_t value : slots) if (!SlotIn(value, frame)) return false;
    if (out->build_child_slot == out->wrap_position_slot || info == out->build_child_slot ||
        info == out->wrap_position_slot || out->title_slot == out->build_child_slot ||
        out->title_slot == out->wrap_position_slot || out->title_field != 0x2bu ||
        out->fixed_field != 0x27u || Insn(image, wrap_begin + 44) != 0x910082c3u ||
        !Calls(image, wrap_begin, &stub) || !StubCid(image, stub, &out->wrap_cid) ||
        out->wrap_cid == out->padding_cid || out->wrap_cid == out->insets_cid ||
        (out->center_call & 7u) != 0u || out->center_call == 0u || (out->title_pool & 7u) != 0u)
        return false;

    // The Hotseat builder passes its controller slot as x6, never rewriting it
    // after the background call.
    for (uintptr_t at = gate; at < gate + 0x400; at += 4) {
        uintptr_t called = 0;
        const uint32_t word = Insn(image, at);
        if ((word & 0xffe00fffu) != 0xf84003a6u || !Calls(image, at + 4, &called) || called != entry) continue;
        const int32_t controller = Slot(word);
        const uintptr_t closure = PrologueBefore(image, gate, 0x400);
        if (!closure || controller >= 0) return false;
        bool stored = false;
        for (uintptr_t scan = closure; scan < at; scan += 4) {
            const uint32_t store = Insn(image, scan);
            if ((store & 0xffe00fe0u) != 0xf80003a0u || Slot(store) != controller) continue;
            if (scan >= gate) return false;
            stored = true;
        }
        out->controller_slot = controller;
        // Adaptive width is optional: without its contract the glass keeps
        // the configured side margins.
        if (stored && !DecodeAdaptive(image, closure, gate, at, entry, out)) {
            out->items_context_slot = 0;
            out->items_field = 0;
            out->columns = 0;
        }
        return stored;
    }
    return false;
}

bool Decode(const Image& image, uintptr_t gate, uintptr_t factory, uintptr_t glass,
            uintptr_t insets, uintptr_t padding, uintptr_t center, uintptr_t wrap,
            NativeDockGlassLayout* out) {
    if (gate < kGateOffset || glass < kGlassOffset ||
        !MatchBytes(image, gate - kGateOffset, Words(kBranch, kBranchMasks), kExec) ||
        !MatchBytes(image, factory, Words(kFactory, kFactoryMasks), kExec) ||
        !MatchBytes(image, glass - kGlassOffset, Words(kStrategy, kStrategyMasks), kExec)) return false;

    uintptr_t called = 0;
    uintptr_t skip = 0;
    uintptr_t stub = 0;
    uintptr_t glass_stub = 0;
    const uintptr_t test = gate + 8;
    if (!Calls(image, gate - kGateOffset + kFactoryCallOffset, &called) || called != factory ||
        !DecodeBitTestTarget(test, Insn(image, test), &skip) ||
        skip <= gate - kGateOffset + sizeof(kBranch) || skip - gate > 0x200 ||
        Insn(image, skip) != kSkipMove || !Calls(image, factory + 8, &stub) ||
        !Calls(image, glass, &glass_stub) || stub == glass_stub) return false;

    // The glass arm is only safe to enter with the frame this builder sets up:
    // three tagged slots and the radius spilled as an unboxed double.
    uintptr_t entry = 0;
    for (uintptr_t distance = 4; distance <= 0x100 && distance <= glass; distance += 4) {
        if (IsDartPrologue(image, glass - distance)) { entry = glass - distance; break; }
    }
    if (!entry || !MatchBytes(image, entry, Words(kStrategyEntry), kExec)) return false;

    const uint32_t flag = UnscaledOffset(Insn(image, gate));
    if ((flag & 3u) != 3u || flag >= 0x100u) return false;
    out->flag_field = flag;
    out->branch_skip = skip;
    out->factory_stub = stub;
    out->factory_return = gate - kGateOffset + kFactoryCallOffset + 4;
    out->glass_entry = glass;
    out->strategy_frame = kStrategyFrame;
    const uintptr_t resume = gate - kGateOffset + kResumeOffset;
    out->resume_list_slot = Slot(Insn(image, resume + 4));
    out->resume_result_slot = Slot(Insn(image, resume + 8));
    out->resume_length_field = UnscaledOffset(Insn(image, resume + 12));
    uintptr_t insets_stub = 0;
    return out->factory_return == resume && out->resume_list_slot < 0 && out->resume_result_slot < 0 &&
        DecodeConstraints(image, gate, out) && DecodeInsets(image, insets, out) &&
        Calls(image, insets, &insets_stub) && DecodePadding(image, padding, insets_stub, out) &&
        DecodeWrap(image, gate, center, wrap, out);
}

// Equivalent fragments are interchangeable, so the first valid one in image
// order is used. Every candidate is fully decoded before it can be chosen.
bool FindPadding(const Image& image, uintptr_t insets_stub, uintptr_t* out) {
    uintptr_t offsets[256]{};
    size_t stored = 0;
    uint32_t total = 0;
    NativeDockGlassLayout probe{};
    if (!StubCid(image, insets_stub, &probe.insets_cid) ||
        !CollectBytes(image, Words(kPadding, kPaddingMasks), offsets, 256, &stored, &total) ||
        total != stored) return false;
    for (size_t index = 0; index < stored; ++index) {
        if (!DecodePadding(image, offsets[index] + 4, insets_stub, &probe)) continue;
        *out = offsets[index] + 4;
        return true;
    }
    return false;
}

// EdgeInsets and EdgeInsetsDirectional share this arithmetic shape; Padding
// accepts either. Use the first allocator that also feeds a Padding fragment.
bool FindInsets(const Image& image, uintptr_t* insets, uintptr_t* padding) {
    uintptr_t offsets[4]{};
    size_t stored = 0;
    uint32_t total = 0;
    if (!CollectBytes(image, Words(kInsets, kInsetsMasks), offsets, 4, &stored, &total) ||
        total != stored) return false;
    for (size_t index = 0; index < stored; ++index) {
        NativeDockGlassLayout probe{};
        uintptr_t stub = 0;
        const uintptr_t entry = offsets[index] + kInsetsEntry;
        if (!DecodeInsets(image, entry, &probe) || !Calls(image, entry, &stub) ||
            !FindPadding(image, stub, padding)) continue;
        *insets = entry;
        return true;
    }
    return false;
}

bool FindFamily(const Image& image, uintptr_t* sites) {
    uintptr_t branch = 0;
    uintptr_t strategy = 0;
    uintptr_t factory = 0;
    if (!FindOne(image, Words(kBranch, kBranchMasks), &branch) ||
        !FindOne(image, Words(kStrategy, kStrategyMasks), &strategy) ||
        !Calls(image, branch + kFactoryCallOffset, &factory) ||
        !FindInsets(image, &sites[3], &sites[4])) return false;
    sites[0] = branch + kGateOffset;
    sites[1] = factory;
    sites[2] = strategy + kGlassOffset;
    // The item builder is the function the Hotseat builder calls with x6.
    for (uintptr_t at = sites[0]; at < sites[0] + 0x400; at += 4) {
        uintptr_t builder = 0;
        if ((Insn(image, at) & 0xffe00fffu) != 0xf84003a6u || !Calls(image, at + 4, &builder)) continue;
        uintptr_t end = builder + 12;
        while (end < builder + 0x2000 && !IsDartPrologue(image, end)) end += 4;
        uintptr_t center = 0, wrap = 0;
        if (!FindBytesForward(image, builder, end - builder, Words(kCenter, kCenterMasks), &center) ||
            !FindBytesForward(image, builder, end - builder, Words(kWrap, kWrapMasks), &wrap)) continue;
        sites[5] = center + kCenterGate;
        sites[6] = wrap + kWrapGate;
        NativeDockGlassLayout layout{};
        return Decode(image, sites[0], sites[1], sites[2], sites[3], sites[4], sites[5], sites[6], &layout);
    }
    return false;
}

template <size_t Index>
bool Find(const Image& image, uintptr_t* out, uint32_t* count) {
    uintptr_t sites[7]{};
    const bool found = FindFamily(image, sites);
    *count = found ? 1u : 0u;
    if (found) *out = sites[Index];
    return found;
}

const SiteSpec kSites[] = {
    {"dock_glass_branch", {}, Find<0>, true,
        {reinterpret_cast<const uint8_t*>(kBranch + 6), reinterpret_cast<const uint8_t*>(kBranchMasks + 6), 16}, 0},
    {"dock_glass_factory", {}, Find<1>, true, Words(kFactory, kFactoryMasks), 0},
    {"dock_glass_arm", {}, Find<2>, true,
        {reinterpret_cast<const uint8_t*>(kStrategy + 6), reinterpret_cast<const uint8_t*>(kStrategyMasks + 6), 48}, 0},
    {"dock_glass_insets", {}, Find<3>, true,
        {reinterpret_cast<const uint8_t*>(kInsets + 19), reinterpret_cast<const uint8_t*>(kInsetsMasks + 19), 48}, 0},
    {"dock_glass_padding", {}, Find<4>, true,
        {reinterpret_cast<const uint8_t*>(kPadding + 1), reinterpret_cast<const uint8_t*>(kPaddingMasks + 1), 36}, 0},
    {"dock_glass_center", {}, Find<5>, true,
        {reinterpret_cast<const uint8_t*>(kCenter + 12), reinterpret_cast<const uint8_t*>(kCenterMasks + 12), 16}, 0},
    {"dock_glass_wrap", {}, Find<6>, true,
        {reinterpret_cast<const uint8_t*>(kWrap + 15), reinterpret_cast<const uint8_t*>(kWrapMasks + 15), 20}, 0},
};

}  // namespace native_dock_glass
}  // namespace

bool DecodeNativeDockGlass(const Image& image, uintptr_t gate, uintptr_t factory, uintptr_t glass,
                           uintptr_t insets, uintptr_t padding, uintptr_t center, uintptr_t wrap,
                           NativeDockGlassLayout* out) {
    return out && native_dock_glass::Decode(image, gate, factory, glass, insets, padding, center, wrap, out);
}

const TargetSpec kNativeDockGlassTarget{
    "native_dock_glass", "libapp.so", native_dock_glass::kSites,
    sizeof(native_dock_glass::kSites) / sizeof(native_dock_glass::kSites[0]),
};
