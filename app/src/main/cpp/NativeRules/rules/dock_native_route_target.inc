// SPDX-License-Identifier: Apache-2.0
// Match the in-tree Pad slot and the paired phone/Pad consumers. Instruction
// operands link the sites; neither the ELF symbols nor its object pool is needed.
namespace {
namespace native_dock_route {

using native_dock_refresh::Words;
using native_dock_refresh::UnscaledOffset;
constexpr uint32_t kExec = kFlagRead | kFlagExec;
constexpr uint32_t kField = 0xffe00fffu;
constexpr uint32_t kLoad = 0xffc003ffu;
constexpr uint32_t kCall = 0xfc000000u;
constexpr uint32_t kTest = 0xfff8001fu;
constexpr uint32_t kConditional = 0xff00001fu;

constexpr uint32_t kSlot[] = {
    0xf9400340, 0xf9400000, 0x6b16001f, 0x54000001,
    0xf9400370, 0xf90001f0, 0xf9400364, 0x94000000,
    0xb8400001, 0x8b1c8021, 0x37200001, 0xf85f83a0,
    0xb840f001, 0x8b1c8021, 0x9100c2c2, 0x94000000, 0xaa1603e1,
};
constexpr uint32_t kSlotMasks[] = {
    kLoad, kLoad, ~0u, kConditional, kLoad, ~0u, kLoad, kCall,
    kField, ~0u, kTest, ~0u, ~0u, ~0u, ~0u, kCall, ~0u,
};
constexpr uint32_t kSlotAfter[] = {
    0xf85e83a1, 0x94000000, 0x91400361, 0xf9400021, 0xaa1603e2, 0xf81f03a0,
};
constexpr uint32_t kSlotAfterMasks[] = {~0u, kCall, kLoad, kLoad, ~0u, ~0u};
constexpr uint32_t kFoldSlot[] = {
    0xb8400001, 0x8b1c8021, 0x37200001, 0xf85f83a3, 0xf85e83a0,
    0xb840f061, 0x8b1c8021, 0x910082c2, 0x94000000, 0xaa0003e2,
};
constexpr uint32_t kFoldSlotMasks[] = {
    kField, ~0u, kTest, ~0u, ~0u, ~0u, ~0u, 0xffffbfffu, kCall, ~0u,
};
constexpr uint32_t kSlotFactory[] = {
    0xa9bf79fd, 0xaa0f03fd, 0xd10061ef, 0xf81f83a1, 0xf81f03a2,
    0xd2800041, 0x94000000, 0xaa0003e1, 0xf85f83a0, 0xf81e83a1,
    0xb800f020, 0xf85f03a0, 0xb8013020, 0x94000000, 0xf85e83a2,
    0x91400361, 0xf9400021, 0xf81f83a0, 0x94000000, 0xaa0003e1,
    0xf85f83a0, 0xb800b001, kDartRestoreFrame, kDartPopFrame, kDartReturn,
};
constexpr uint32_t kSlotFactoryMasks[] = {
    ~0u, ~0u, ~0u, ~0u, ~0u, ~0u, kCall, ~0u, ~0u, ~0u, ~0u, ~0u, ~0u,
    kCall, ~0u, kLoad, kLoad, ~0u, kCall, ~0u, ~0u, ~0u, ~0u, ~0u, ~0u,
};

constexpr uint32_t kPadVisibility[] = {
    0xf85f83a4, 0xf81e03a3, 0x37200084, 0xf85f03a5, 0x910082c6,
    0x14000006, 0xf85f03a5, 0x37200065, 0xaa0303e6, 0x14000002,
    0x9100c2c6, 0xf81e83a6,
};
constexpr uint32_t kPhoneVisibility[] = {
    0xf85f83a2, 0x362000e2, 0xf85f03a2, 0x36200062, 0x910082c0,
    0x14000004, 0xaa0103e0, 0x14000002, 0x9100c2c0,
    kDartRestoreFrame, kDartPopFrame, kDartReturn,
};
constexpr uint32_t kReadPad[] = {
    0xb8400001, 0x8b1c8021, 0xf81f83a1, 0xf9400340, 0xf9400000, 0x6b16001f,
};
constexpr uint32_t kReadPadMasks[] = {kField, ~0u, ~0u, kLoad, kLoad, ~0u};
constexpr uint32_t kReadFold[] = {
    0xf85f83a0, 0xb8400022, 0x8b1c8042, 0xf81f03a2,
};
constexpr uint32_t kReadFoldMasks[] = {~0u, kField, ~0u, ~0u};

constexpr uint32_t kController[] = {
    0xb8400001, 0x8b1c8021, 0x362000a1, 0xf85f83a0,
    0xf9400370, 0x6b10001f, 0x54000001, 0xf9400340, 0xf9400000,
};
constexpr uint32_t kControllerMasks[] = {kField, ~0u, ~0u, ~0u, kLoad, ~0u, kConditional, kLoad, kLoad};

constexpr uint32_t kDragCaller[] = {
    0x94000000, 0x372000c0, 0x94000000, 0xb8400001, 0x8b1c8021,
    0xaa0103e0, 0x14000000, 0x94000000, 0xaa0003e3, 0xaa1603e2, 0xaa1603e1,
};
constexpr uint32_t kDragCallerMasks[] = {kCall, ~0u, kCall, kField, ~0u, ~0u, kCall, kCall, ~0u, ~0u, ~0u};
constexpr uint32_t kDragEntry[] = {0xa9bf79fd, 0xaa0f03fd, 0xd10041ef, 0x94000000, 0x37200200};
constexpr uint32_t kDragEntryMasks[] = {~0u, ~0u, ~0u, kCall, ~0u};
constexpr uint32_t kRegistered[] = {
    0x91400370, 0xf9400210, 0xf90001f0, 0xf9400364, 0x94000000,
};
constexpr uint32_t kRegisteredMasks[] = {kLoad, kLoad, ~0u, kLoad, kCall};
constexpr uint32_t kGeometry[] = {
    0x37200360, 0xf9400340, 0xf9400000, 0x6b16001f, 0x540000a1,
    0xf9400370, 0xf90001f0, 0xf9400364, 0x94000000,
    0xb8400001, 0x8b1c8021, 0x37200061, 0x910082c0, 0x1400000f,
};
constexpr uint32_t kGeometryMasks[] = {
    ~0u, kLoad, kLoad, ~0u, ~0u, kLoad, ~0u, kLoad, kCall, kField, ~0u, ~0u, ~0u, ~0u,
};

// HomePage registers itself on the mounted WidgetsBinding after initializing
// its own manager. The manager's init reads the same DeviceConfig as the slot.
constexpr uint32_t kHomeInit[] = {
    0xf9400340, 0xf9400000, 0xf9400370, 0x6b10001f, 0x54000081,
    0x91400362, 0xf9400042, 0x94000000, 0xaa0003e1, 0x94000000,
    0xf9400340, 0xf9400000, 0x6b16001f, 0x54000000, 0xd2800011, 0xb8716802, 0x8b1c8042,
};
constexpr uint32_t kHomeInitMasks[] = {
    kLoad, kLoad, kLoad, ~0u, ~0u, kLoad, kLoad, kCall, ~0u, kCall,
    kLoad, kLoad, ~0u, kConditional, 0xffe0001fu, ~0u, ~0u,
};
constexpr uint32_t kManagerInit[] = {
    0xa9bf79fd, 0xaa0f03fd, 0xd10101ef, 0xf81f83a1, 0xd28000a1,
    0x94000000, 0xaa0003e1, 0xf85f83a0, 0xf81f03a1, 0xb800f020,
    0x9100c2c2, 0xb800f002, 0xf9400342, 0xf9400042, 0x6b16005f, 0x54000001,
};
constexpr uint32_t kManagerInitMasks[] = {
    ~0u, ~0u, ~0u, ~0u, ~0u, kCall, ~0u, ~0u, ~0u, ~0u,
    ~0u, ~0u, kLoad, kLoad, ~0u, kConditional,
};

uintptr_t FrameEntry(const Image& image, uintptr_t site, uintptr_t max_distance) {
    for (uintptr_t distance = 4; distance <= max_distance && distance <= site; distance += 4) {
        if (IsDartPrologue(image, site - distance)) return site - distance;
    }
    return 0;
}

uint32_t Insn(const Image& image, uintptr_t at) {
    uint32_t word = 0;
    ReadInsn(image, at, &word);
    return word;
}

bool Calls(const Image& image, uintptr_t pc, uintptr_t* out) {
    return DecodeBlTarget(pc, Insn(image, pc), out) && Contains(image, *out, 4, kExec);
}

bool FindOne(const Image& image, BytePattern pattern, uintptr_t* site) {
    uint32_t count = 0;
    return FindUniqueBytes(image, pattern, site, &count);
}

uint32_t LoadOffset(uint32_t instruction) {
    return ((instruction >> 10u) & 0xfffu) * 8u;
}

bool DecodeHome(const Image& image, uintptr_t slot, NativeDockRouteLayout* out) {
    uintptr_t home = 0;
    uintptr_t manager = 0;
    if (!FindOne(image, Words(kHomeInit, kHomeInitMasks), &home) ||
        !Calls(image, home + 36, &manager) ||
        !MatchBytes(image, manager, Words(kManagerInit, kManagerInitMasks), kExec)) return false;
    const uint32_t table = LoadOffset(Insn(image, slot - 32));
    const uint32_t config_slot = LoadOffset(Insn(image, slot - 28));
    const uint32_t home_slot = LoadOffset(Insn(image, home + 4));
    const uint32_t binding_slot = LoadOffset(Insn(image, home + 44));
    if (!table || !config_slot || !home_slot || !binding_slot ||
        config_slot == home_slot || config_slot == binding_slot || home_slot == binding_slot ||
        LoadOffset(Insn(image, home)) != table ||
        LoadOffset(Insn(image, home + 40)) != table ||
        LoadOffset(Insn(image, manager + 48)) != table ||
        LoadOffset(Insn(image, manager + 52)) != config_slot) return false;
    out->static_table_field = table;
    out->device_config_slot = config_slot;
    out->home_state_slot = home_slot;
    out->widgets_binding_slot = binding_slot;
    return true;
}

bool MatchVisibilityReads(const Image& image, uintptr_t gate, uint32_t pad_field,
                          uint32_t fold_field) {
    const uintptr_t entry = FrameEntry(image, gate, 0x120);
    if (!entry) return false;
    uintptr_t pad = 0;
    uintptr_t fold = 0;
    return FindBytesForward(image, entry, gate - entry, Words(kReadPad, kReadPadMasks), &pad) &&
        FindBytesForward(image, pad + sizeof(kReadPad), gate - pad - sizeof(kReadPad),
                         Words(kReadFold, kReadFoldMasks), &fold) &&
        ((pad | fold) & 3u) == 0 && UnscaledOffset(Insn(image, pad)) == pad_field &&
        UnscaledOffset(Insn(image, fold + 4)) == fold_field;
}

bool DecodeSlot(const Image& image, uintptr_t slot, NativeDockRouteLayout* out) {
    constexpr size_t kGateOffset = 32;
    if (slot < kGateOffset || !MatchBytes(image, slot - kGateOffset, Words(kSlot, kSlotMasks), kExec))
        return false;
    uintptr_t factory = 0;
    uintptr_t config_ready = 0;
    if (!DecodeTestBranchTarget(slot - 20, Insn(image, slot - 20), &config_ready) ||
        config_ready != slot || !Calls(image, slot + 28, &factory) ||
        !MatchBytes(image, factory, Words(kSlotFactory, kSlotFactoryMasks), kExec)) return false;

    const uintptr_t entry = FrameEntry(image, slot, 0x600);
    if (!entry) return false;
    uint32_t fold_field = 0;
    unsigned slots = 0;
    for (uintptr_t pc = entry; pc + sizeof(kFoldSlot) <= slot; pc += 4) {
        if (!MatchBytes(image, pc, Words(kFoldSlot, kFoldSlotMasks), kExec)) continue;
        uintptr_t callee = 0;
        if (!Calls(image, pc + 32, &callee) || callee != factory) continue;
        const uint32_t argument = Insn(image, pc + 28);
        if (argument == 0x910082c2u) {
            if (slots != 0) return false;
            fold_field = UnscaledOffset(Insn(image, pc));
        } else if (slots != 1 || fold_field != UnscaledOffset(Insn(image, pc))) {
            return false;
        }
        ++slots;
    }
    const uint32_t pad_field = UnscaledOffset(Insn(image, slot));
    const uintptr_t skip = slot + 8 + ((Insn(image, slot + 8) >> 5u) & 0x3fffu) * 4u;
    if (slots != 2 || pad_field == fold_field || (pad_field & 3u) != 3 ||
        (fold_field & 3u) != 3 || pad_field >= 0x100 || fold_field >= 0x100 ||
        skip <= slot + sizeof(kSlot) - kGateOffset || skip >= factory ||
        !MatchBytes(image, skip, Words(kSlotAfter, kSlotAfterMasks), kExec)) return false;
    out->pad_field = pad_field;
    out->fold_field = fold_field;
    out->slot_skip = skip;
    return true;
}

bool MatchControllerDispatch(const Image& image, uintptr_t controller, uintptr_t pad,
                             uintptr_t geometry) {
    // The standalone Pad accessor and the general controller's Pad arm
    // register, construct and retrieve the exact same typed dependency.
    constexpr uintptr_t kControllerPadEntry = 28;
    constexpr uintptr_t kPadRegistration = 40;
    const uintptr_t dispatch = controller + kControllerPadEntry;
    if (!IsDartPrologue(image, pad) ||
        !MatchBytes(image, dispatch + 28, Words(kRegistered, kRegisteredMasks), kExec) ||
        !MatchBytes(image, pad + kPadRegistration, Words(kRegistered, kRegisteredMasks), kExec) ||
        geometry < 56 || !MatchBytes(image, geometry - 56, Words(kRegistered, kRegisteredMasks), kExec))
        return false;

    for (uintptr_t byte = 0; byte < 8; byte += 4) {
        if (Insn(image, dispatch + 28 + byte) != Insn(image, pad + kPadRegistration + byte) ||
            Insn(image, dispatch + 28 + byte) != Insn(image, geometry - 56 + byte)) return false;
    }
    uintptr_t registry = 0;
    uintptr_t peer = 0;
    if (!Calls(image, geometry - 40, &registry) ||
        !Calls(image, dispatch + 44, &peer) || registry != peer) return false;
    constexpr uintptr_t kCalls[] = {16, 24, 36, 60, 80};
    for (const uintptr_t byte : kCalls) {
        uintptr_t arm_call = 0;
        uintptr_t pad_call = 0;
        if (!Calls(image, dispatch + 28 + byte, &arm_call) ||
            !Calls(image, pad + kPadRegistration + byte, &pad_call) || arm_call != pad_call) return false;
    }
    return true;
}

bool Decode(const Image& image, const uintptr_t* sites, uintptr_t drag_caller,
            NativeDockRouteLayout* layout) {
    const uintptr_t slot = sites[0];
    const uintptr_t pad_show = sites[1];
    const uintptr_t phone_show = sites[2];
    const uintptr_t controller = sites[3];
    const uintptr_t drag = sites[4];
    const uintptr_t geometry = sites[5];
    NativeDockRouteLayout result{};
    uintptr_t drag_predicate = 0;
    uintptr_t pad_controller = 0;
    uintptr_t general_controller = 0;
    if (!DecodeSlot(image, slot, &result) || !DecodeHome(image, slot, &result) ||
        !MatchBytes(image, pad_show, Words(kPadVisibility), kExec) ||
        !MatchBytes(image, phone_show, Words(kPhoneVisibility), kExec) ||
        !MatchVisibilityReads(image, pad_show, result.pad_field, result.fold_field) ||
        !MatchVisibilityReads(image, phone_show, result.pad_field, result.fold_field) ||
        !MatchBytes(image, controller, Words(kController, kControllerMasks), kExec) ||
        !MatchBytes(image, drag_caller, Words(kDragCaller, kDragCallerMasks), kExec) ||
        !MatchBytes(image, drag, Words(kDragEntry, kDragEntryMasks), kExec) ||
        geometry < 36 ||
        !MatchBytes(image, geometry - 36, Words(kGeometry, kGeometryMasks), kExec) ||
        UnscaledOffset(Insn(image, controller)) != result.pad_field ||
        UnscaledOffset(Insn(image, geometry)) != result.pad_field ||
        !Calls(image, drag_caller, &drag_predicate) || drag_predicate != drag ||
        !Calls(image, drag_caller + 8, &pad_controller) ||
        !Calls(image, drag_caller + 28, &general_controller) ||
        general_controller != FrameEntry(image, controller, 0x100) ||
        !MatchControllerDispatch(image, controller, pad_controller, geometry) ||
        !Calls(image, drag + 12, &result.drag_first_call)) return false;

    result.pad_visibility_fallback = pad_show + 24;
    result.phone_hide = phone_show + 32;
    result.phone_fold_show = phone_show + 24;
    result.controller_pad = controller + 28;
    result.drag_return = drag_caller + 4;
    result.geometry_fallback = geometry + 20;
    *layout = result;
    return true;
}

bool FindFamily(const Image& image, uintptr_t* sites) {
    uintptr_t slot = 0;
    uintptr_t drag = 0;
    uintptr_t geometry = 0;
    if (!FindOne(image, Words(kSlot, kSlotMasks), &slot) ||
        !FindOne(image, Words(kPadVisibility), &sites[1]) ||
        !FindOne(image, Words(kPhoneVisibility), &sites[2]) ||
        !FindOne(image, Words(kController, kControllerMasks), &sites[3]) ||
        !FindOne(image, Words(kDragCaller, kDragCallerMasks), &drag) ||
        !FindOne(image, Words(kGeometry, kGeometryMasks), &geometry) ||
        !Calls(image, drag, &sites[4])) return false;
    sites[0] = slot + 32;
    sites[5] = geometry + 36;
    NativeDockRouteLayout layout{};
    return Decode(image, sites, drag, &layout);
}

template <size_t Index>
bool Find(const Image& image, uintptr_t* out, uint32_t* count) {
    uintptr_t sites[static_cast<size_t>(NativeDockGate::kCount)]{};
    const bool found = FindFamily(image, sites);
    *count = found ? 1u : 0u;
    if (found) *out = sites[Index];
    return found;
}

const SiteSpec kSites[] = {
    {"dock_pad_slot", {}, Find<0>, true,
        {reinterpret_cast<const uint8_t*>(kSlot + 8), reinterpret_cast<const uint8_t*>(kSlotMasks + 8), 16}, 0},
    {"dock_pad_visible", {}, Find<1>, true, Words(kPadVisibility), 0},
    {"dock_phone_visible", {}, Find<2>, true, Words(kPhoneVisibility), 0},
    {"dock_controller", {}, Find<3>, true, Words(kController, kControllerMasks), 0},
    {"dock_drag_predicate", {}, Find<4>, true, Words(kDragEntry, kDragEntryMasks), 0},
    {"dock_icon_geometry", {}, Find<5>, true,
        {reinterpret_cast<const uint8_t*>(kGeometry + 9), reinterpret_cast<const uint8_t*>(kGeometryMasks + 9), 16}, 0},
};

}  // namespace native_dock_route
}  // namespace

bool DecodeNativeDockRoute(const Image& image, const uintptr_t* sites, NativeDockRouteLayout* out) {
    using namespace native_dock_route;
    uintptr_t drag = 0;
    return sites && out && FindOne(image, Words(kDragCaller, kDragCallerMasks), &drag) &&
        Decode(image, sites, drag, out);
}

const TargetSpec kNativeDockRouteTarget{
    "native_dock_route", "libapp.so", native_dock_route::kSites,
    sizeof(native_dock_route::kSites) / sizeof(native_dock_route::kSites[0]),
};
