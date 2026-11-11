// SPDX-License-Identifier: Apache-2.0
// Dock switches wait for the original drag manager's drop guard and RxBool.
// These are read on the Dart thread; no Dart references are retained.
namespace {
namespace native_dock_interaction {
using native_dock_route::Calls;
using native_dock_route::Insn;
using native_dock_route::UnscaledOffset;
using native_dock_route::Words;
using native_dock_route::kCall;
using native_dock_route::kConditional;
using native_dock_route::kExec;
using native_dock_route::kField;
using native_dock_route::kLoad;

// The start and end callers recover the same lazy singleton, then pass it as
// the receiver. The calls must write opposite values through the same Rx setter.
constexpr uint32_t kManagerCaller[] = {
    0xf9400340, 0xf9400000, 0xf9400370, 0x6b10001f, 0x54000081,
    0x91400362, 0xf9400042, 0x94000000, 0xaa0003e1, 0xf85f03a2, 0x94000000,
};
constexpr uint32_t kManagerCallerMasks[] = {
    kLoad, kLoad, kLoad, ~0u, ~0u, kLoad, kLoad, kCall, ~0u, ~0u, kCall,
};
constexpr uint32_t kWriteBool[] = {
    0xf85f83a0, 0xb8400001, 0x8b1c8021, 0x910082c2, 0x94000000,
};
constexpr uint32_t kWriteBoolMasks[] = {~0u, kField, ~0u, 0xffffbfffu, kCall};
constexpr uint32_t kDropTail[] = {
    0xf85e83a1, 0xaa1603e2, 0x94000000, 0xf85f83a1, 0xf85e03a2,
    0x94000000, 0xf85f83a1, 0x94000000,
};
constexpr uint32_t kDropTailMasks[] = {~0u, ~0u, kCall, ~0u, ~0u, kCall, ~0u, kCall};
constexpr uint32_t kStoreDropGuard[] = {
    0xf85f83a0, 0xf81f03a2, 0xb800f040, 0x910082c1,
    0xb8000001, 0xb8400001, 0x8b1c8021, 0x6b16003f,
};
constexpr uint32_t kStoreDropGuardMasks[] = {~0u, ~0u, ~0u, ~0u, kField, kField, ~0u, ~0u};

constexpr uint32_t kLoadRxValue[] = {0xb8400020, 0x8b1c8000, 0xf9400370, 0x6b10001f, 0x54000000};
constexpr uint32_t kLoadRxValueMasks[] = {kField, ~0u, kLoad, ~0u, kConditional};
constexpr uint32_t kStoreRxValue[] = {
    0xf85f03a0, 0xb8000020, 0x360000e0, 0x385ff030, 0x385ff011,
    0x8a500a30, 0xea5c821f,
};
constexpr uint32_t kStoreRxValueMasks[] = {~0u, kField, ~0u, ~0u, ~0u, ~0u, ~0u};

struct BoolWrite {
    uintptr_t pc;
    uintptr_t setter;
    uint32_t field;
    bool value;
};

bool ReadBoolWrite(const Image& image, uintptr_t caller, BoolWrite* out) {
    uintptr_t handler = 0;
    uintptr_t write = 0;
    if (!Calls(image, caller + 40, &handler) || !IsDartPrologue(image, handler) ||
        !FindBytesForward(image, handler, 0xe0, Words(kWriteBool, kWriteBoolMasks), &write) ||
        (write & 3u) != 0) return false;
    uintptr_t setter = 0;
    if (!Calls(image, write + 16, &setter) || !IsDartPrologue(image, setter)) return false;
    *out = {write, setter, UnscaledOffset(Insn(image, write + 4)), Insn(image, write + 12) == 0x910082c2u};
    return true;
}

bool ReadDropContract(const Image& image, uintptr_t caller, const BoolWrite& write,
                      NativeDockInteractionLayout* out) {
    uintptr_t same_setter = 0;
    uintptr_t guard = 0;
    if (write.value ||
        !MatchBytes(image, write.pc + 20, Words(kDropTail, kDropTailMasks), kExec) ||
        !Calls(image, write.pc + 28, &same_setter) || same_setter != write.setter ||
        !Calls(image, write.pc + 48, &guard) || !IsDartPrologue(image, guard) ||
        !MatchBytes(image, guard + 28, Words(kStoreDropGuard, kStoreDropGuardMasks), kExec))
        return false;

    uintptr_t load = 0;
    uintptr_t store = 0;
    if (!FindBytesForward(image, write.setter, 0x100, Words(kLoadRxValue, kLoadRxValueMasks), &load) ||
        !FindBytesForward(image, load + sizeof(kLoadRxValue), 0x90,
                         Words(kStoreRxValue, kStoreRxValueMasks), &store) ||
        ((load | store) & 3u) != 0 ||
        Insn(image, load + 8) != Insn(image, caller + 8) ||
        UnscaledOffset(Insn(image, load)) != UnscaledOffset(Insn(image, store + 4)))
        return false;
    const uint32_t table = ((Insn(image, caller) >> 10u) & 0xfffu) * 8u;
    const uint32_t manager = ((Insn(image, caller + 4) >> 10u) & 0xfffu) * 8u;
    const uint32_t sentinel = ((Insn(image, caller + 8) >> 10u) & 0xfffu) * 8u;
    const uint32_t value = UnscaledOffset(Insn(image, load));
    const uint32_t drop = UnscaledOffset(Insn(image, guard + 44));
    if (!table || !manager || !sentinel || drop == write.field ||
        (value & 3u) != 3 || (drop & 3u) != 3 || (write.field & 3u) != 3)
        return false;
    *out = {table, manager, sentinel, write.field, value, drop};
    return true;
}

bool SameManager(const Image& image, uintptr_t left, uintptr_t right) {
    // The same static receiver, sentinel, initializer pool descriptor and
    // lazy-init helper must feed both original calls.
    for (uintptr_t byte = 0; byte < 28; byte += 4) {
        if (Insn(image, left + byte) != Insn(image, right + byte)) return false;
    }
    uintptr_t left_init = 0;
    uintptr_t right_init = 0;
    return Calls(image, left + 28, &left_init) && Calls(image, right + 28, &right_init) &&
        left_init == right_init;
}

bool FindGuard(const Image& image, uintptr_t* out, uint32_t* count) {
    uintptr_t candidates[128]{};
    size_t found = 0;
    uint32_t total = 0;
    *count = 0;
    if (!CollectBytes(image, Words(kManagerCaller, kManagerCallerMasks), candidates, 128, &found, &total) ||
        total != found) return false;
    for (size_t i = 0; i < found; ++i) {
        BoolWrite end{};
        NativeDockInteractionLayout layout{};
        if (!ReadBoolWrite(image, candidates[i], &end) ||
            !ReadDropContract(image, candidates[i], end, &layout)) continue;
        for (size_t j = 0; j < found; ++j) {
            if (i == j || !SameManager(image, candidates[i], candidates[j])) continue;
            BoolWrite start{};
            if (!ReadBoolWrite(image, candidates[j], &start) || !start.value ||
                start.field != end.field || start.setter != end.setter) continue;
            *out = candidates[i];
            ++*count;
            break;
        }
    }
    return *count == 1;
}

const SiteSpec kSites[] = {
    {"dock_interaction", {}, FindGuard, true, Words(kManagerCaller, kManagerCallerMasks), 0},
};
}  // namespace native_dock_interaction
}  // namespace

bool DecodeNativeDockInteraction(const Image& image, uintptr_t observer, NativeDockInteractionLayout* out) {
    using namespace native_dock_interaction;
    uintptr_t unique = 0;
    uint32_t count = 0;
    BoolWrite write{};
    return out && FindGuard(image, &unique, &count) && unique == observer &&
        ReadBoolWrite(image, observer, &write) && ReadDropContract(image, observer, write, out);
}

const TargetSpec kNativeDockInteractionTarget{
    "native_dock_interaction", "libapp.so", native_dock_interaction::kSites, 1,
};
