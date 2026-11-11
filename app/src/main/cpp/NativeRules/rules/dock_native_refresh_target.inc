// SPDX-License-Identifier: Apache-2.0
// The frame return is after both post-frame callbacks and the scheduler's
// transition to idle. WidgetsBinding supplies the root already used by Flutter.
namespace {
namespace native_dock_refresh {

constexpr uint32_t kUnscaledOffsetMask = 0xffe00fffu;
constexpr uint32_t kLoadOffsetMask = 0xffc003ffu;
constexpr uint32_t kMoveImmediateMask = 0xffe0001fu;
constexpr uint32_t kCondBranchMask = 0xff00001fu;
constexpr uint32_t kCallMask = 0xfc000000u;

template <size_t N>
BytePattern Words(const uint32_t (&words)[N], const uint32_t* masks = nullptr) {
    return {reinterpret_cast<const uint8_t*>(words), reinterpret_cast<const uint8_t*>(masks),
            sizeof(words)};
}

constexpr uint32_t kIdleReturn[] = {
    kDartReturnX22, kDartRestoreFrame, kDartPopFrame, kDartReturn,
};
constexpr uint32_t kIdleTail[] = {
    0xf84003a1,  // ldur x1, [fp, #binding_slot]
    0xf9400362,  // ldr x2, [pp, #idle_enum]
    0xb8000022,  // stur w2, [x1, #phase]
    0xb8000036,  // stur w22, [x1, #frame_timestamp]
    kDartReturnX22, kDartRestoreFrame, kDartPopFrame, kDartReturn,
};
constexpr uint32_t kIdleTailMasks[] = {
    kUnscaledOffsetMask, kLoadOffsetMask, kUnscaledOffsetMask, kUnscaledOffsetMask,
    ~0u, ~0u, ~0u, ~0u,
};

uint32_t UnscaledOffset(uint32_t instruction) {
    return (instruction >> 12u) & 0x1ffu;
}

bool MatchIdleFrame(const Image& image, uintptr_t tail) {
    uint32_t idle[4]{};
    if (!ReadInsns(image, tail, idle, 4)) return false;
    const uint32_t binding_slot = UnscaledOffset(idle[0]);
    const uint32_t phase = UnscaledOffset(idle[2]);
    const uint32_t timestamp = UnscaledOffset(idle[3]);
    if (binding_slot < 0x100u || (binding_slot & 7u) != 0 ||
        phase == timestamp || (phase & 3u) != 3 || (timestamp & 3u) != 3) return false;

    // The protected error exit restores the same idle enum and clears the
    // same timestamp before rethrowing. Other scheduler exits do neither.
    const uint32_t cleanup[] = {
        idle[0] ^ 1u, idle[1] ^ 3u, (idle[2] & ~0x3ffu) | 1u,
        idle[3] & ~0x20u,
    };
    uintptr_t error_exit = 0;
    if (!FindBytesForward(image, tail + sizeof(kIdleTail), 0x100, Words(cleanup), &error_exit) ||
        (error_exit & 3u) != 0) return false;

    // Bind the epilogue to the same frame's saved scheduler receiver. Stop at
    // the first prologue so a neighboring function cannot satisfy the check.
    for (uintptr_t distance = 4; distance <= 0x500 && distance <= tail; distance += 4) {
        const uintptr_t entry = tail - distance;
        if (!IsDartPrologue(image, entry)) continue;
        uint32_t opener[3]{};
        return ReadInsns(image, entry + 8, opener, 3) &&
            (opener[0] & 0xffc003ffu) == 0xd10001efu &&
            opener[1] == 0xaa0103e0u &&
            opener[2] == (0xf80003a1u | (binding_slot << 12u)) &&
            ((opener[0] >> 10u) & 0xfffu) >= 0x200u - binding_slot;
    }
    return false;
}

bool FindIdleFrame(const Image& image, uintptr_t* out, uint32_t* count) {
    uintptr_t tails[32]{};
    size_t found = 0;
    uint32_t total = 0;
    *count = 0;
    if (!CollectBytes(image, Words(kIdleTail, kIdleTailMasks), tails, 32, &found, &total) ||
        total != found) return false;
    for (size_t i = 0; i < found; ++i) {
        if (!MatchIdleFrame(image, tails[i])) continue;
        *out = tails[i] + sizeof(kIdleTail) - sizeof(kIdleReturn);
        ++*count;
    }
    return *count == 1;
}

// Both the draw path (BuildOwner.buildScope) and the reassemble bridge
// (BuildOwner.reassemble) read root and owner from the same receiver in the
// same order. Keep their two field operands relocatable and require agreement.
constexpr uint32_t kBindingRead[] = {
    0xd2800011, 0xb8716802, 0x8b1c8042, 0x6b16005f, 0x54000000,
    0xd2800011, 0xb8716801, 0x8b1c8021, 0x6b16003f, 0x54000000,
};
constexpr uint32_t kBindingReadMasks[] = {
    kMoveImmediateMask, ~0u, ~0u, ~0u, kCondBranchMask,
    kMoveImmediateMask, ~0u, ~0u, ~0u, kCondBranchMask,
};
constexpr uint32_t kBridgeStart[] = {
    0xa9bf79fd, 0xaa0f03fd, 0xd10021ef, 0xaa0103e0, 0xf81f83a1,
};
constexpr uint32_t kBridgeEnd[] = {
    0x94000000, 0xf85f83a1, 0x94000000, kDartRestoreFrame, kDartPopFrame, kDartReturn,
};
constexpr uint32_t kBridgeEndMasks[] = {kCallMask, ~0u, kCallMask, ~0u, ~0u, ~0u};
constexpr uint32_t kOwnerToRoot[] = {
    0xa9bf79fd, 0xaa0f03fd, 0xd10001ef, 0xaa0203e0, 0xf80003a2,
    0xf9403f41, 0xf9400021, 0x37200001,
};
constexpr uint32_t kOwnerToRootMasks[] = {
    ~0u, ~0u, kLoadOffsetMask, ~0u, kUnscaledOffsetMask,
    kLoadOffsetMask, kLoadOffsetMask, 0xfff8001fu,
};
constexpr uint32_t kElementEntry[] = {
    0xa9bf79fd, 0xaa0f03fd, 0xd10021ef, 0xaa0103e2, 0xf81f83a1,
    0xf85ff040, 0xd34c7c00, 0xaa0203e1, 0xd2800011, 0xf2a00011,
    0x8b11001e, 0xf87e7abe, 0xd63f03c0,
};
constexpr uint32_t kElementEntryMasks[] = {
    ~0u, ~0u, ~0u, ~0u, ~0u, ~0u, ~0u, ~0u, kMoveImmediateMask,
    kMoveImmediateMask, ~0u, ~0u, ~0u,
};

bool ReadRootCall(const Image& image, uintptr_t bridge, uintptr_t* root_call,
                  NativeDockRefreshLayout* layout) {
    constexpr uint32_t flags = kFlagRead | kFlagExec;
    const uintptr_t read = bridge + sizeof(kBridgeStart);
    const uintptr_t call = read + sizeof(kBindingRead);
    uint32_t fields[10]{};
    uint32_t jump = 0;
    uintptr_t owner = 0;
    if (!MatchBytes(image, bridge, Words(kBridgeStart), flags) ||
        !MatchBytes(image, read, Words(kBindingRead, kBindingReadMasks), flags) ||
        !MatchBytes(image, call, Words(kBridgeEnd, kBridgeEndMasks), flags) ||
        !ReadInsns(image, read, fields, 10) ||
        !ReadInsn(image, call, &jump) || !DecodeBlTarget(call, jump, &owner) ||
        !MatchBytes(image, owner, Words(kOwnerToRoot, kOwnerToRootMasks), flags)) return false;

    uint32_t setup[8]{};
    uint32_t transfer_words[2]{};
    if (!ReadInsns(image, owner, setup, 8)) return false;
    // The bounded forward TBNZ skips timeline setup before reloading the root.
    const uintptr_t transfer = owner + 28 + ((setup[7] >> 5u) & 0x3fffu) * 4u;
    if (transfer <= owner + sizeof(setup) || transfer > owner + 0x60 ||
        !ReadInsns(image, transfer, transfer_words, 2) ||
        (transfer_words[0] & kUnscaledOffsetMask) != 0xf84003a1u ||
        UnscaledOffset(transfer_words[0]) != UnscaledOffset(setup[4]) ||
        !DecodeBlTarget(transfer + 4, transfer_words[1], root_call) ||
        !MatchBytes(image, *root_call, Words(kElementEntry, kElementEntryMasks), flags)) return false;

    const uint32_t root = (fields[0] >> 5u) & 0xffffu;
    const uint32_t owner_field = (fields[5] >> 5u) & 0xffffu;
    if ((root & 3u) != 3 || (owner_field & 3u) != 3 || root == owner_field) return false;
    layout->root_field = root;
    layout->build_owner_field = owner_field;
    return true;
}

bool FindBinding(const Image& image, uintptr_t* out, uint32_t* count) {
    uintptr_t reads[32]{};
    size_t found = 0;
    uint32_t total = 0;
    *count = 0;
    if (!CollectBytes(image, Words(kBindingRead, kBindingReadMasks), reads, 32, &found, &total) ||
        total != found) return false;
    for (size_t i = 0; i < found; ++i) {
        if (reads[i] < sizeof(kBridgeStart)) continue;
        const uintptr_t bridge = reads[i] - sizeof(kBridgeStart);
        uintptr_t element = 0;
        NativeDockRefreshLayout layout{};
        if (!ReadRootCall(image, bridge, &element, &layout)) continue;
        uint32_t words[10]{};
        if (!ReadInsns(image, reads[i], words, 10)) continue;
        for (size_t j = 0; j < found; ++j) {
            if (i == j) continue;
            uint32_t peer[10]{};
            uint32_t next[2]{};
            uintptr_t build_scope = 0;
            if (!ReadInsns(image, reads[j], peer, 10) ||
                peer[0] != words[0] || peer[5] != words[5] ||
                !ReadInsns(image, reads[j] + sizeof(kBindingRead), next, 2) ||
                (next[0] & kLoadOffsetMask) != 0xf9400364u ||
                !DecodeBlTarget(reads[j] + sizeof(kBindingRead) + 4, next[1], &build_scope) ||
                !IsDartPrologue(image, build_scope)) continue;
            *out = bridge;
            ++*count;
        }
    }
    return *count == 1;
}

bool FindReassemble(const Image& image, uintptr_t* out, uint32_t* count) {
    uintptr_t bridge = 0;
    NativeDockRefreshLayout layout{};
    return FindBinding(image, &bridge, count) && ReadRootCall(image, bridge, out, &layout);
}

const SiteSpec kSites[] = {
    {"dock_refresh_frame", {}, FindIdleFrame, true, Words(kIdleReturn), 0},
    {"dock_refresh_binding", {}, FindBinding, true, Words(kBridgeStart), 0},
    {"dock_refresh_reassemble", {}, FindReassemble, true, Words(kElementEntry, kElementEntryMasks), 0},
};

}  // namespace native_dock_refresh
}  // namespace

bool DecodeNativeDockRefresh(const Image& image, uintptr_t frame, uintptr_t binding_bridge,
                             uintptr_t reassemble,
                             NativeDockRefreshLayout* layout) {
    using namespace native_dock_refresh;
    constexpr auto kReturnOffset = sizeof(kIdleTail) - sizeof(kIdleReturn);
    uint32_t load_binding = 0;
    NativeDockRefreshLayout decoded{};
    uintptr_t root_call = 0;
    if (!layout || frame < kReturnOffset) return false;
    const uintptr_t tail = frame - kReturnOffset;
    if (!MatchBytes(image, tail, Words(kIdleTail, kIdleTailMasks), kFlagRead | kFlagExec) ||
        !MatchIdleFrame(image, tail) || !ReadInsn(image, tail, &load_binding) ||
        !ReadRootCall(image, binding_bridge, &root_call, &decoded) ||
        root_call != reassemble) return false;
    decoded.binding_frame_slot = static_cast<int32_t>(UnscaledOffset(load_binding)) - 0x200;
    *layout = decoded;
    return true;
}

const TargetSpec kNativeDockRefreshTarget{
    "native_dock_refresh", "libapp.so", native_dock_refresh::kSites, 3,
};
