// SPDX-License-Identifier: Apache-2.0
#include "dock_native_rule.h"

#include "dart_mapped_image.h"
#include "dart_resolution_cache.h"
#include "dart_rule_support.h"
#include "logging.h"
#include "lsposed_hook_backend.h"
#include "native_rule_settings_record.h"

#include <pthread.h>
#include <stddef.h>
#include <string.h>

#define DOCK_SHARED __attribute__((visibility("hidden")))
extern "C" {
DOCK_SHARED uint32_t g_native_dock_ready = 0;
DOCK_SHARED uint32_t g_native_dock_requested = 0;
DOCK_SHARED uint32_t g_native_dock_recovery = 0;
DOCK_SHARED uintptr_t g_native_dock_owner = 0;
DOCK_SHARED uintptr_t g_native_dock_glass_field = 0;
DOCK_SHARED uintptr_t g_native_dock_glass_skip = 0;
DOCK_SHARED uintptr_t g_native_dock_glass_continue = 0;
DOCK_SHARED uintptr_t g_native_dock_glass_stub = 0;
DOCK_SHARED uintptr_t g_native_dock_glass_entry = 0;
DOCK_SHARED uintptr_t g_native_dock_glass_return = 0;
DOCK_SHARED uintptr_t g_native_dock_reassemble = 0;

// Read by the glass gates with fixed offsets (dock_native_hook.S). Slots are
// signed frame offsets; geometry is in logical pixels.
struct NativeDockGlassFrames {
    uintptr_t resume_continue;
    intptr_t resume_list_slot;
    intptr_t resume_result_slot;
    uintptr_t resume_length_field;
    uintptr_t constraints_arg;
    uintptr_t max_height_field;
    uintptr_t insets_entry;
    uintptr_t insets_frame;
    intptr_t insets_slots[4];
    uintptr_t insets_cid;
    uintptr_t padding_return;
    uintptr_t padding_frame;
    intptr_t padding_slot;
    uintptr_t padding_cid;
    double height;
    double margin;
    double bottom;
    double radius;
    double limit;
    uintptr_t stage;
    uintptr_t thread;
    double observed_height;  // last Hotseat area max height, diagnostics only
    // Unlock wrapper: the item builder frame, entered at the center call.
    uintptr_t build_frame;
    intptr_t build_child_slot;
    intptr_t controller_slot;
    uintptr_t center_call;
    uintptr_t center_return;
    intptr_t center_item_slot;
    intptr_t center_slot;
    uintptr_t center_field;
    uintptr_t center_continue;
    intptr_t wrap_position_slot;
    uintptr_t wrap_entry;
    intptr_t title_slot;
    uintptr_t title_field;
    uintptr_t title_pool;
    uintptr_t title_continue;
    double observed_center[2];  // last Dock center, diagnostics only
    uintptr_t fixed_field;
    // Adaptive width: the factory widens both side margins by half a Dock cell
    // per missing icon (an empty row counts as one). Columns 0: contract
    // unavailable. The cell width is read like the host item builder does.
    uintptr_t adaptive;
    intptr_t items_slot;
    uintptr_t items_field;
    uintptr_t max_width_field;
    double columns;
    // Diagnostics only: last factory width and item count, and the first and
    // last item center x the host item builder stored since that factory.
    double observed_width;
    uintptr_t observed_items;
    double observed_first;
    double observed_last;
    uintptr_t observed_built;
    uintptr_t static_table;
    uintptr_t sentinel_pool;
    uintptr_t flag_static;
    uintptr_t flag_field;
    uintptr_t config_static;
    uintptr_t config_rx_field;
    uintptr_t rx_value_field;
    uintptr_t cell_field;
    uintptr_t alt_cell_field;
    double cell_extra;
    double observed_cell;  // diagnostics only
};
static_assert(offsetof(NativeDockGlassFrames, resume_continue) == 0x00);
static_assert(offsetof(NativeDockGlassFrames, insets_entry) == 0x30);
static_assert(offsetof(NativeDockGlassFrames, insets_slots) == 0x40);
static_assert(offsetof(NativeDockGlassFrames, insets_cid) == 0x60);
static_assert(offsetof(NativeDockGlassFrames, padding_cid) == 0x80);
static_assert(offsetof(NativeDockGlassFrames, height) == 0x88);
static_assert(offsetof(NativeDockGlassFrames, limit) == 0xa8);
static_assert(offsetof(NativeDockGlassFrames, thread) == 0xb8);
static_assert(offsetof(NativeDockGlassFrames, observed_height) == 0xc0);
static_assert(offsetof(NativeDockGlassFrames, build_frame) == 0xc8);
static_assert(offsetof(NativeDockGlassFrames, center_continue) == 0x108);
static_assert(offsetof(NativeDockGlassFrames, title_continue) == 0x138);
static_assert(offsetof(NativeDockGlassFrames, observed_center) == 0x140);
static_assert(offsetof(NativeDockGlassFrames, fixed_field) == 0x150);
static_assert(offsetof(NativeDockGlassFrames, adaptive) == 0x158);
static_assert(offsetof(NativeDockGlassFrames, columns) == 0x178);
static_assert(offsetof(NativeDockGlassFrames, observed_built) == 0x1a0);
static_assert(offsetof(NativeDockGlassFrames, static_table) == 0x1a8);
static_assert(offsetof(NativeDockGlassFrames, cell_extra) == 0x1f0);
static_assert(sizeof(NativeDockGlassFrames) == 0x200);
DOCK_SHARED NativeDockGlassFrames g_native_dock_glass_frames{
    0, 0, 0, 0, 0, 0, 0, 0, {0, 0, 0, 0}, 0, 0, 0, 0, 0, 95.0, 5.0, 5.0, 24.0, 4096.0, 0, 0, 0.0,  // kNativeDockGlassDefault
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, {0.0, 0.0}, 0,
    0, 0, 0, 0, 0.0, 0.0, 0, 0.0, 0.0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0,
};

void HyperTweakNativeDockGlassBranch();
void HyperTweakNativeDockGlassFactory();
void HyperTweakNativeDockGlassReturn();
void HyperTweakNativeDockGlassCenter();
void HyperTweakNativeDockGlassWrap();
void HyperTweakNativeDockIdle();
}

namespace hypertweak::native {
namespace {
constexpr size_t kRouteCount = static_cast<size_t>(dart::NativeDockGate::kCount);
// The Pad route only supplies the verified static slots the idle gate reads.
// Its sites are never patched; the phone Hotseat keeps its own tree.
constexpr size_t kGlassBranch = 0;
constexpr size_t kGlassFactory = 1;
constexpr size_t kGlassReturn = 2;
constexpr size_t kGlassCenter = 3;
constexpr size_t kGlassWrap = 4;
constexpr size_t kFrame = 5;
constexpr size_t kSiteCount = 6;
constexpr size_t kPatchSize = 16;

struct Site {
    uint8_t* address;
    uint8_t stock[kPatchSize];
    uint8_t patch[kPatchSize];
    bool owned;
};

struct Plan {
    uint8_t* base;
    uint8_t identity[kDartIdentitySize];
    dart::NativeDockRouteLayout route;
    dart::NativeDockRefreshLayout refresh;
    dart::NativeDockInteractionLayout interaction;
    dart::NativeDockGlassLayout glass;
    uintptr_t reassemble;
    Site sites[kSiteCount];
};

pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
Plan plan{};
const char* reason = "disabled";

struct Lock {
    explicit Lock(bool wait) : acquired((wait ? pthread_mutex_lock(&lock) : pthread_mutex_trylock(&lock)) == 0) {}
    ~Lock() { if (acquired) pthread_mutex_unlock(&lock); }
    const bool acquired;
};

bool Failure(const char* next) {
    const char* previous = __atomic_exchange_n(&reason, next, __ATOMIC_ACQ_REL);
    if (strcmp(previous, next) != 0 && strcmp(next, "dart_preparation_pending") != 0)
        LogWarn("native Dock glass unavailable: %s", next);
    return false;
}

bool SameImage(uint8_t* base, const uint8_t* identity) {
    return base && plan.base == base && ValidDartCacheIdentity(identity) &&
        memcmp(plan.identity, identity, sizeof(plan.identity)) == 0;
}

bool AnyOwned() {
    for (const auto& site : plan.sites) if (site.owned) return true;
    return false;
}

void Passivate(bool recover = false) {
    __atomic_store_n(&g_native_dock_ready, 0u, __ATOMIC_RELEASE);
    const auto owner = __atomic_exchange_n(&g_native_dock_owner, uintptr_t{0}, __ATOMIC_ACQ_REL);
    if (recover && owner) __atomic_store_n(&g_native_dock_recovery, 1u, __ATOMIC_RELEASE);
}

bool Stock(const Site& site) {
    return memcmp(site.address, site.stock, kPatchSize) == 0;
}

bool Owned(const Site& site) {
    return site.owned && memcmp(site.address, site.patch, kPatchSize) == 0;
}

bool Rollback() {
    Passivate();
    bool restored = true;
    for (size_t index = kSiteCount; index > 0; --index) {
        auto& site = plan.sites[index - 1];
        if (!site.owned) continue;
        if ((!Owned(site) && !Stock(site)) || RemoveInlineHook(site.address) != kHookSuccess ||
            !Stock(site)) {
            restored = false;
            continue;
        }
        site.owned = false;
    }
    return restored;
}

bool BuildPlan(const DartResolution& route, const DartResolution& refresh,
               const DartResolution& interaction, const DartResolution& glass,
               const uint8_t* identity, Plan* out) {
    auto& next = *out;
    if (!route.base || route.base != refresh.base || route.base != interaction.base ||
        route.base != glass.base || route.site_count != kRouteCount) return false;
    dart::Image image{};
    if (!dart::ParseImage(route.base, &image)) return false;
    const auto base = reinterpret_cast<uintptr_t>(route.base);
    uintptr_t routes[kRouteCount]{};
    for (size_t index = 0; index < kRouteCount; ++index) {
        if (!route.MatchVerify(route.sites[index].name) || route.sites[index].address < base)
            return false;
        routes[index] = route.sites[index].address - base;
    }
    constexpr const char* kBranchName = "dock_glass_branch";
    constexpr const char* kFactoryName = "dock_glass_factory";
    constexpr const char* kArmName = "dock_glass_arm";
    constexpr const char* kInsetsName = "dock_glass_insets";
    constexpr const char* kPaddingName = "dock_glass_padding";
    constexpr const char* kCenterName = "dock_glass_center";
    constexpr const char* kWrapName = "dock_glass_wrap";
    if (!glass.MatchVerify(kBranchName) || !glass.MatchVerify(kFactoryName) ||
        !glass.MatchVerify(kArmName) || !glass.MatchVerify(kInsetsName) ||
        !glass.MatchVerify(kPaddingName) || !glass.MatchVerify(kCenterName) ||
        !glass.MatchVerify(kWrapName)) return false;
    uintptr_t offsets[kSiteCount]{};
    offsets[kGlassBranch] = glass.Find(kBranchName) - base;
    offsets[kGlassFactory] = glass.Find(kFactoryName) - base;
    if (!dart::DecodeNativeDockGlass(image, offsets[kGlassBranch], offsets[kGlassFactory],
                                    glass.Find(kArmName) - base, glass.Find(kInsetsName) - base,
                                    glass.Find(kPaddingName) - base, glass.Find(kCenterName) - base,
                                    glass.Find(kWrapName) - base, &next.glass)) return false;
    offsets[kGlassReturn] = next.glass.factory_return;
    offsets[kGlassCenter] = glass.Find(kCenterName) - base;
    offsets[kGlassWrap] = glass.Find(kWrapName) - base;
    constexpr const char* kFrameName = "dock_refresh_frame";
    constexpr const char* kBindingName = "dock_refresh_binding";
    constexpr const char* kReassembleName = "dock_refresh_reassemble";
    constexpr const char* kInteractionName = "dock_interaction";
    if (!refresh.MatchVerify(kFrameName) || !refresh.MatchVerify(kBindingName) ||
        !refresh.MatchVerify(kReassembleName) || !interaction.MatchVerify(kInteractionName)) return false;
    offsets[kFrame] = refresh.Find(kFrameName) - base;
    next.reassemble = refresh.Find(kReassembleName);
    if (!dart::DecodeNativeDockRoute(image, routes, &next.route) ||
        !dart::DecodeNativeDockRefresh(image, offsets[kFrame], refresh.Find(kBindingName) - base,
                                      next.reassemble - base, &next.refresh) ||
        !dart::DecodeNativeDockInteraction(image, interaction.Find(kInteractionName) - base,
                                          &next.interaction) ||
        next.route.static_table_field != next.interaction.static_table_field) return false;
    for (size_t index = 0; index < kSiteCount; ++index) {
        if (!dart::Contains(image, offsets[index], kPatchSize, dart::kFlagRead | dart::kFlagExec))
            return false;
        for (size_t earlier = 0; earlier < index; ++earlier) {
            if (offsets[index] < offsets[earlier] + kPatchSize &&
                offsets[earlier] < offsets[index] + kPatchSize) return false;
        }
        auto& site = next.sites[index];
        site.address = route.base + offsets[index];
        memcpy(site.stock, site.address, kPatchSize);
    }
    next.base = route.base;
    memcpy(next.identity, identity, sizeof(next.identity));
    return true;
}

void PublishContinuations() {
    const auto base = reinterpret_cast<uintptr_t>(plan.base);
    const auto& glass = plan.glass;
    g_native_dock_glass_field = glass.flag_field;
    g_native_dock_glass_skip = base + glass.branch_skip;
    g_native_dock_glass_continue = reinterpret_cast<uintptr_t>(plan.sites[kGlassBranch].address) + kPatchSize;
    g_native_dock_glass_stub = base + glass.factory_stub;
    g_native_dock_glass_entry = base + glass.glass_entry;
    g_native_dock_glass_return = base + glass.factory_return;
    g_native_dock_reassemble = plan.reassemble;
    auto& frames = g_native_dock_glass_frames;
    frames.resume_continue = reinterpret_cast<uintptr_t>(plan.sites[kGlassReturn].address) + kPatchSize;
    frames.resume_list_slot = glass.resume_list_slot;
    frames.resume_result_slot = glass.resume_result_slot;
    frames.resume_length_field = glass.resume_length_field;
    frames.constraints_arg = static_cast<uintptr_t>(glass.constraints_arg);
    frames.max_height_field = glass.max_height_field;
    frames.insets_entry = base + glass.insets_entry;
    frames.insets_frame = glass.insets_frame;
    for (size_t index = 0; index < 4; ++index) frames.insets_slots[index] = glass.insets_slots[index];
    frames.insets_cid = glass.insets_cid;
    frames.padding_return = base + glass.padding_return;
    frames.padding_frame = glass.padding_frame;
    frames.padding_slot = glass.padding_slot;
    frames.padding_cid = glass.padding_cid;
    frames.build_frame = glass.build_frame;
    frames.build_child_slot = glass.build_child_slot;
    frames.controller_slot = glass.controller_slot;
    frames.center_call = glass.center_call;
    frames.center_return = base + glass.center_return;
    frames.center_item_slot = glass.center_item_slot;
    frames.center_slot = glass.center_slot;
    frames.center_field = glass.center_field;
    frames.center_continue = reinterpret_cast<uintptr_t>(plan.sites[kGlassCenter].address) + kPatchSize;
    frames.wrap_position_slot = glass.wrap_position_slot;
    frames.wrap_entry = base + glass.wrap_entry;
    frames.title_slot = glass.title_slot;
    frames.title_field = glass.title_field;
    frames.title_pool = glass.title_pool;
    frames.fixed_field = glass.fixed_field;
    frames.items_slot = glass.items_context_slot;
    frames.items_field = glass.items_field;
    frames.max_width_field = glass.max_height_field - 0x10u;
    frames.static_table = glass.static_table;
    frames.sentinel_pool = glass.sentinel_pool;
    frames.flag_static = glass.cell_flag_static;
    frames.flag_field = glass.cell_flag_field;
    frames.config_static = glass.config_static;
    frames.config_rx_field = glass.config_rx_field;
    frames.rx_value_field = glass.rx_value_field;
    frames.cell_field = glass.cell_field;
    frames.alt_cell_field = glass.alt_cell_field;
    frames.cell_extra = glass.cell_extra;
    const double columns = static_cast<double>(glass.columns);
    __atomic_store(&frames.columns, const_cast<double*>(&columns), __ATOMIC_RELEASE);
    if (!glass.columns) LogWarn("native Dock glass adaptive width unavailable: item list contract rejected");
    frames.title_continue = reinterpret_cast<uintptr_t>(plan.sites[kGlassWrap].address) + kPatchSize;
    __atomic_store_n(&frames.stage, uintptr_t{0}, __ATOMIC_RELEASE);
}

template <class Value>
Value Read(uintptr_t address) {
    Value value;
    memcpy(&value, reinterpret_cast<const void*>(address), sizeof(value));
    return value;
}

uintptr_t Reference(uintptr_t object, uint32_t field, uintptr_t heap) {
    const auto compressed = Read<uint32_t>(object + field);
    return (compressed & 1u) ? (heap << 32u) + compressed : 0u;
}

bool Object(uintptr_t value, uintptr_t null_value, uintptr_t sentinel) {
    return (value & 1u) && value != null_value && value != sentinel;
}

bool DragIdle(uintptr_t table, uintptr_t heap, uintptr_t null_value, uintptr_t sentinel) {
    const auto& interaction = plan.interaction;
    const auto manager = Read<uintptr_t>(table + interaction.manager_slot);
    // Both native drag entry points initialize this singleton before use.
    if (manager == sentinel) return true;
    if (!Object(manager, null_value, sentinel)) return false;
    const auto stock_false = null_value + 0x30;
    if (Reference(manager, interaction.drop_guard_field, heap) != stock_false) return false;
    const auto rx = Reference(manager, interaction.dragging_rx_field, heap);
    return Object(rx, null_value, sentinel) &&
        Reference(rx, interaction.rx_value_field, heap) == stock_false;
}
}

void SetNativeDockEnabled(bool enabled) {
    __atomic_store_n(&g_native_dock_requested, enabled ? 1u : 0u, __ATOMIC_RELEASE);
}

bool SetNativeDockGlassGeometry(uint32_t packed) {
    if (!ValidNativeDockGlassGeometry(packed)) return false;
    auto& frames = g_native_dock_glass_frames;
    const uintptr_t adaptive = (packed & kNativeDockGlassAdaptive) ? 1u : 0u;
    bool changed = __atomic_exchange_n(&frames.adaptive, adaptive, __ATOMIC_ACQ_REL) != adaptive;
    // The adaptive width owns the side margin: it is the default one around a full row.
    const uint32_t margin = adaptive ? (kNativeDockGlassDefault >> 8u) & 0xffu : (packed >> 8u) & 0xffu;
    const double values[] = {static_cast<double>(packed & 0x7fu), static_cast<double>(margin),
                             static_cast<double>((packed >> 16u) & 0xffu), static_cast<double>(packed >> 24u)};
    double* const targets[] = {&frames.height, &frames.margin, &frames.bottom, &frames.radius};
    for (size_t index = 0; index < 4; ++index) {
        double current = 0;
        __atomic_load(targets[index], &current, __ATOMIC_ACQUIRE);
        if (current == values[index]) continue;
        __atomic_store(targets[index], const_cast<double*>(&values[index]), __ATOMIC_RELEASE);
        changed = true;
    }
    // A selected Launcher rebuilds once on its next idle frame.
    if (changed && NativeDockRequested() && __atomic_load_n(&g_native_dock_owner, __ATOMIC_ACQUIRE))
        __atomic_store_n(&g_native_dock_recovery, 1u, __ATOMIC_RELEASE);
    return true;
}

bool NativeDockRequested() {
    return __atomic_load_n(&g_native_dock_requested, __ATOMIC_ACQUIRE) != 0u;
}

const char* NativeDockRuleReason() {
    return __atomic_load_n(&reason, __ATOMIC_ACQUIRE);
}

void ResetNativeDockAfterFork() {
    pthread_mutex_t fresh = PTHREAD_MUTEX_INITIALIZER;
    lock = fresh;
    Passivate();
    __atomic_store_n(&g_native_dock_recovery, 0u, __ATOMIC_RELEASE);
}

bool PrepareNativeDockRule(void* handle) {
    if (!NativeDockRequested()) return true;
    uint8_t* base = nullptr;
    const uint8_t* identity = nullptr;
    if (!MiuiHomeHyosResolveDartImage(handle, &base, &identity) ||
        !ValidDartCacheIdentity(identity)) return Failure("dart_image_unresolved");
    {
        Lock guard(true);
        if (!guard.acquired) return false;
        if (SameImage(base, identity)) return true;
        if (AnyOwned()) {
            Passivate();
            return Failure("dock_image_changed");
        }
    }
    const dart::TargetSpec* const specs[] = {
        &dart::kNativeDockRouteTarget, &dart::kNativeDockRefreshTarget, &dart::kNativeDockInteractionTarget,
        &dart::kNativeDockGlassTarget,
    };
    DartResolution resolutions[4]{};
    if (!PrepareDartTargetSet(handle, specs, resolutions, 4)) return Failure("dock_contract_unavailable");
    Plan next{};
    if (resolutions[0].base != base ||
        !BuildPlan(resolutions[0], resolutions[1], resolutions[2], resolutions[3], identity, &next))
        return Failure("dock_layout_rejected");

    Lock guard(true);
    if (!guard.acquired || AnyOwned()) return false;
    plan = next;
    PublishContinuations();
    __atomic_store_n(&reason, "prepared", __ATOMIC_RELEASE);
    return true;
}

bool ApplyNativeDockRule(void* handle) {
    if (!NativeDockRequested() &&
        !__atomic_load_n(&g_native_dock_owner, __ATOMIC_ACQUIRE) &&
        !__atomic_load_n(&g_native_dock_recovery, __ATOMIC_ACQUIRE)) return true;
    Lock guard(false);
    if (!guard.acquired) return false;
    uint8_t* base = nullptr;
    const uint8_t* identity = nullptr;
    if (!MiuiHomeHyosResolveDartImage(handle, &base, &identity) ||
        !SameImage(base, identity)) {
        Passivate();
        if (AnyOwned()) return Failure("dock_image_changed");
        const char* state = NativeDockRuleReason();
        if (strcmp(state, "disabled") == 0 || strcmp(state, "prepared") == 0 ||
            strcmp(state, "installed") == 0) return Failure("dart_preparation_pending");
        return false;  // Worker may still be preparing or have reported rejection.
    }
    bool complete = true;
    for (const auto& site : plan.sites) {
        if (Owned(site)) continue;
        complete = false;
        if (!Stock(site)) {
            Passivate();
            return Failure("dock_foreign_patch");
        }
    }
    if (!complete) {
        Passivate(true);
        void* const replacements[] = {
            reinterpret_cast<void*>(HyperTweakNativeDockGlassBranch),
            reinterpret_cast<void*>(HyperTweakNativeDockGlassFactory),
            reinterpret_cast<void*>(HyperTweakNativeDockGlassReturn),
            reinterpret_cast<void*>(HyperTweakNativeDockGlassCenter),
            reinterpret_cast<void*>(HyperTweakNativeDockGlassWrap),
            reinterpret_cast<void*>(HyperTweakNativeDockIdle),
        };
        static_assert(sizeof(replacements) / sizeof(replacements[0]) == kSiteCount);
        for (size_t index = 0; index < kSiteCount; ++index) {
            auto& site = plan.sites[index];
            if (Owned(site)) continue;
            if (site.owned) {
                if (RemoveInlineHook(site.address) != kHookSuccess || !Stock(site))
                    return Failure("dock_remap_repair_failed");
                site.owned = false;
            }
            void* trampoline = nullptr;
            const int status = InstallInlineHook(site.address, replacements[index], &trampoline);
            if (!Stock(site) || status == kHookSuccess) {
                site.owned = true;
                memcpy(site.patch, site.address, kPatchSize);
            }
            if (status != kHookSuccess || !trampoline || Stock(site)) {
                return Failure(Rollback() ? "dock_hook_failed" : "dock_rollback_failed");
            }
        }
        LogInfo("native Dock glass gates installed");
    }
    __atomic_store_n(&reason, "installed", __ATOMIC_RELEASE);
    __atomic_store_n(&g_native_dock_ready, 1u, __ATOMIC_RELEASE);
    return true;
}

// Called only by the verified scheduler's idle epilogue. The returned root is
// used immediately by the Dart tail transfer. There are no retained heap refs.
extern "C" DOCK_SHARED uintptr_t HyperTweakNativeDockAtIdle(uintptr_t thread, uintptr_t frame,
                                                         uintptr_t heap, uintptr_t null_value,
                                                         uintptr_t pool) {
    Lock guard(false);
    if (!guard.acquired || !__atomic_load_n(&g_native_dock_ready, __ATOMIC_ACQUIRE)) return 0;
    const bool enable = NativeDockRequested();
    const auto owner = __atomic_load_n(&g_native_dock_owner, __ATOMIC_ACQUIRE);
    const bool recover = __atomic_load_n(&g_native_dock_recovery, __ATOMIC_ACQUIRE) != 0;
    if (!recover && (enable ? owner == thread : owner != thread)) return 0;
    if (!enable && owner && owner != thread) return 0;

    const auto table = Read<uintptr_t>(thread + plan.route.static_table_field);
    if (!table) return 0;
    const auto sentinel = Read<uintptr_t>(pool + plan.interaction.sentinel_pool_offset);
    const auto binding = Read<uintptr_t>(table + plan.route.widgets_binding_slot);
    const auto home = Read<uintptr_t>(table + plan.route.home_state_slot);
    const auto config = Read<uintptr_t>(table + plan.route.device_config_slot);
    if (!Object(binding, null_value, sentinel) || !Object(home, null_value, sentinel) ||
        !Object(config, null_value, sentinel) ||
        binding != Read<uintptr_t>(frame + plan.refresh.binding_frame_slot)) return 0;

    const auto stock_false = null_value + 0x30;
    if (enable && (Reference(config, plan.route.pad_field, heap) != stock_false ||
                   Reference(config, plan.route.fold_field, heap) != stock_false)) return 0;

    const auto root = Reference(binding, plan.refresh.root_field, heap);
    const auto build_owner = Reference(binding, plan.refresh.build_owner_field, heap);
    if (!Object(root, null_value, sentinel) || !Object(build_owner, null_value, sentinel) ||
        !DragIdle(table, heap, null_value, sentinel)) return 0;

    __atomic_store_n(&g_native_dock_owner, enable ? thread : uintptr_t{0}, __ATOMIC_RELEASE);
    __atomic_store_n(&g_native_dock_recovery, 0u, __ATOMIC_RELEASE);
    const auto& frames = g_native_dock_glass_frames;
    double area = 0, width = 0, center_x = 0, center_y = 0, first = 0, last = 0, columns = 0, cell = 0;
    __atomic_load(&frames.observed_cell, &cell, __ATOMIC_RELAXED);
    __atomic_load(&frames.observed_height, &area, __ATOMIC_RELAXED);
    __atomic_load(&frames.observed_width, &width, __ATOMIC_RELAXED);
    __atomic_load(&frames.observed_center[0], &center_x, __ATOMIC_RELAXED);
    __atomic_load(&frames.observed_center[1], &center_y, __ATOMIC_RELAXED);
    __atomic_load(&frames.observed_first, &first, __ATOMIC_RELAXED);
    __atomic_load(&frames.observed_last, &last, __ATOMIC_RELAXED);
    __atomic_load(&frames.columns, &columns, __ATOMIC_RELAXED);
    LogInfo("native Dock glass %s: rebuilding Launcher elements (area %.1fx%.1f, center %.1f,%.1f, "
            "adaptive %u/%.0f columns, cell %.1f, items %zu, built %zu centers %.1f..%.1f)",
            enable ? (owner == thread ? "geometry" : "selected") : "released", width, area, center_x, center_y,
            static_cast<unsigned>(__atomic_load_n(&frames.adaptive, __ATOMIC_RELAXED)), columns, cell,
            static_cast<size_t>(__atomic_load_n(&frames.observed_items, __ATOMIC_RELAXED)),
            static_cast<size_t>(__atomic_load_n(&frames.observed_built, __ATOMIC_RELAXED)), first, last);
    return root;
}
}  // namespace hypertweak::native
