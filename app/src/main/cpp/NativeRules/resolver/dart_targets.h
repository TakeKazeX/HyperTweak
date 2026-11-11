// SPDX-License-Identifier: Apache-2.0
//
// Declarative target registry for the launcher's Dart AOT features.
//
// Adding a feature should mean adding a spec here plus a rule implementation --
// not editing the upstream hook file at six call sites, and not hand-maintaining
// a per-build RVA table. A spec names the site and says how to find it; the
// resolver owns uniqueness, fail-closed policy, and diagnostics.
//
// Discovery is tiered, and a site declares only the tiers it actually supports:
//
//   T1  masked byte pattern, required to match exactly once in the executable
//       segments. This is the whole story for a distinctive instruction
//       sequence, and one masked pattern can cover many builds when the only
//       variation sits in an encoded immediate.
//
//   T2/T3  a structural predicate used when T1 is ambiguous. This is real
//       reverse engineering per target: it keys on instruction shape and
//       call-graph relationships rather than on bytes, because a large part of
//       Dart AOT code shares byte-identical prologues and epilogues. A pattern
//       that matches hundreds of times is not made usable by widening it -- it
//       needs a different kind of evidence.
//
// A site with no tier that resolves is REJECTED, never guessed. The resolver
// never returns a best-effort candidate: an unreached feature is a bug report,
// a mis-patched snapshot is a crash inside the launcher.
#pragma once

#include "dart_image.h"

#include <stddef.h>
#include <stdint.h>

namespace hypertweak::native::dart {

// Which evidence produced a resolved offset. Ordered by strength so diagnostics
// can report the weakest link in a feature's resolution.
enum class FindTier : uint32_t {
    kRejected = 0u,
    kMaskedBytes = 1u,
    kStructural = 2u,
};

const char* FindTierName(FindTier tier);

// A structural finder. It must return true only when the evidence is unique;
// reporting a plausible-but-ambiguous offset is a defect, not a fallback.
// `candidates_out` reports how many candidate families were seen so a rejection
// can explain itself.
using StructuralFinder = bool (*)(const Image& image, uintptr_t* offset_out,
                                  uint32_t* candidates_out);

struct SiteSpec {
    // Stable, human-readable site name; also the key used in diagnostics.
    const char* name;

    // T1 evidence. `pattern.bytes == nullptr` means this site has no byte
    // evidence and must be found structurally.
    BytePattern pattern;

    // T2/T3 evidence, tried only when T1 is absent or ambiguous.
    StructuralFinder structural;

    // True when the site is required for the feature to function. A required
    // site that fails rejects the whole feature; an optional site is reported
    // and skipped, so a partial capability degrades instead of disappearing.
    bool required;

    // Defence in depth: bytes that must be present at `offset + verify_delta`
    // before anything is written. This is not the finding evidence repeated --
    // it is the contract the rule relies on, and it must stay build independent.
    // For a site whose own bytes move between builds (a pool displacement, a
    // call target) this is a masked pattern or a nearby stable sequence, never a
    // build-specific constant. `bytes == nullptr` disables the re-check.
    BytePattern verify;
    size_t verify_delta;
};

struct TargetSpec {
    // Feature id, e.g. "recents_clear_button".
    const char* id;

    // The snapshot this feature patches, as matched by the upstream framework.
    // Kept for diagnostics only: resolution itself is build independent.
    const char* library_name;

    const SiteSpec* sites;
    size_t site_count;
};

// Outcome for one site.
struct SiteResult {
    const char* name;
    uintptr_t offset;
    uint32_t candidates;
    FindTier tier;
    // True when the finder produced a unique candidate. Tracked separately from
    // `verified` and `resolved` so a failure can say which stage rejected --
    // conflating them once made "nothing matched" report as "bytes mismatched".
    bool found;
    // True when the site's `verify` bytes were confirmed on the live image.
    bool verified;
    // found && verified. A caller must not patch a site unless this is true.
    bool resolved;
};

// Outcome for a whole feature.
//
// `resolved` means every required site was *located* -- it deliberately does NOT
// require the verification bytes to still match. Those bytes are the ones a rule
// overwrites when it installs its hook, so requiring them here would make a
// patched target unresolvable and leave a rule unable to tell "already installed"
// from "lost, reinstall". Location is structural; whether the original bytes are
// still present is the rule's question, answered at write time.
//
// `verified` is that answer for this pass: false is the normal state once our own
// hook is in place.
struct TargetResult {
    const char* id;
    bool resolved;
    bool verified;
    const char* reason;   // "resolved" | "no_sites" | "too_many_sites" | site name
    const char* failure;  // "none" | "not_found" | "verify_failed"
    SiteResult sites[8];
    size_t site_count;
};

// Runs the tiered finder for every site of `spec` against `image`.
TargetResult ResolveTarget(const Image& image, const TargetSpec& spec);

// The registered features. The device payload and the host probe both iterate
// this same list, so an offline verdict matches the device's.
extern const TargetSpec kRecentsClearButtonTarget;
extern const TargetSpec kFolderColumnsTarget;
extern const TargetSpec kAssistantWidgetTarget;
extern const TargetSpec kNativeDockRefreshTarget;
extern const TargetSpec kNativeDockRouteTarget;
extern const TargetSpec kNativeDockInteractionTarget;
extern const TargetSpec kNativeDockGlassTarget;
extern const TargetSpec kRecentsHapticTarget;
struct RecentsHapticLayout {
    uint32_t start = 0, end = 0, update = 0, metrics = 0, number = 0;
    uint16_t depth = 0, notification_metrics = 0, drag = 0, delta = 0;
    uint16_t minimum = 0, maximum = 0, pixels = 0;
    uintptr_t metrics_allocation = 0;
    uintptr_t observer_entry = 0;
    bool closure_entry = false;
};
bool DecodeRecentsHapticLayout(const Image& image, uintptr_t scroll, uintptr_t builder,
                               uintptr_t update, uintptr_t metrics, RecentsHapticLayout* output);
extern const TargetSpec kFolderStackTarget;
size_t FolderStackSiteSpan(const Image& image, unsigned site, uintptr_t offset);
extern const TargetSpec kWidgetStackTarget;
extern const TargetSpec kXiaoaiGestureOptionsTarget;
extern const TargetSpec kIconScaleTarget;
extern const TargetSpec kIconThemeTarget;
bool ValidateIconThemeTargets(const Image&,uintptr_t support,uintptr_t refresh,const uint8_t* support_original=nullptr,const uint8_t* refresh_original=nullptr);
extern const TargetSpec kDesktopGridTarget;
struct DesktopGridLayout {
 uintptr_t to=0,prepare=0,change=0,apply=0,variable=0,fixed=0,handler=0;
 uintptr_t rows_window=0,rows_null=0,clamp=0,load=0,migrate=0;
 uint32_t maximum_field=0,unset_field=0,controller_slot=0,grid_controller_slot=0;
 uint32_t current=0,selected=0,options=0,list_length=0,list_data=0,array_data=0;
 uint32_t x=0,y=0,index=0,layout=0,cell_cid=0,grid_x=0,grid_y=0;
 uint32_t context_x=0,context_y=0,input_rows=0,load_descriptor=0;
};
bool DecodeDesktopGridLayout(const Image&,const uintptr_t*,DesktopGridLayout*,const uint8_t* handler=nullptr,const uint8_t* variable=nullptr,const uint8_t* rows=nullptr,const uint8_t* clamp=nullptr);

struct IconScaleLayout {
    uintptr_t frame_returns[2];
    uint32_t current, rx_value, double_cid;
    uintptr_t scope_begin, scope_clamp;
    uint32_t controller_slot, active_rx, actual_grid_icon, actual_provider, actual_width, actual_size, actual_padding;
};
bool DecodeIconScaleLayout(const Image&, const uintptr_t* sites, IconScaleLayout*, const uint8_t* owned_frame = nullptr, const uint8_t* owned_scope = nullptr);
struct XiaoaiGestureLayout {
    uint32_t cid,context_field,trigger_field,mask_field,entity_field,long_press_pool,double_click_pool;
};
bool DecodeXiaoaiGestureLayout(const Image&, uintptr_t predicate, uintptr_t decoder, XiaoaiGestureLayout*);

struct WidgetStackLayout { uint32_t field, cid; uintptr_t drag_success, target_success; };
bool DecodeWidgetStackLayout(const Image&, uintptr_t drag, uintptr_t target,
                             uintptr_t serializer, WidgetStackLayout*);
struct FolderStackLayout {
    uintptr_t patch, children_count, children_loop;
    uintptr_t frame_return, refresh, refresh_id, refresh_return;
    uint32_t layout_id_pool, preview_id_pool, typeargs_pool;
    uint32_t model_slot, registry_slot;
    uint32_t strategy, item;
    uint32_t strategies[8];
    uint8_t strategy_count;
    uint16_t controller_strategy;
    uint16_t list, count, x, y, padding_x, padding_y;
    uint16_t large_w, large_h, small_w, small_h, gap_x, gap_y, small_gap;
};
bool DecodeFolderStackLayout(const Image&, uintptr_t getter, uintptr_t producer,
                            uintptr_t factory, uintptr_t builder, uintptr_t children, FolderStackLayout*);
bool DecodeFolderStackRefresh(const Image&, uintptr_t frame, uintptr_t refresh, uintptr_t owner, FolderStackLayout*, const uint8_t* owned_identity = nullptr);
bool VerifyFolderStackFrame(uintptr_t address);
bool VerifyFolderStackIdentity(const Image&, uintptr_t address, const FolderStackLayout&);
bool VerifyFolderStackReturn(uintptr_t address);
bool VerifyFolderStackChildren(uintptr_t address, bool loop);
bool VerifyDockUnlockLayout(const Image&, uintptr_t);
struct DockTickLayout { uintptr_t site; int receiver, scale, y, value; };
bool DecodeDockTickLayout(const Image&, uintptr_t, DockTickLayout*);

struct NativeDockRefreshLayout {
    int32_t binding_frame_slot;
    uint32_t root_field;
    uint32_t build_owner_field;
};
bool DecodeNativeDockRefresh(const Image&, uintptr_t frame, uintptr_t binding_bridge, uintptr_t reassemble,
                             NativeDockRefreshLayout*);

enum class NativeDockGate : size_t { kSlot, kPadVisibility, kPhoneVisibility, kController, kDrag, kGeometry, kCount };
struct NativeDockRouteLayout {
    uint32_t static_table_field;
    uint32_t device_config_slot;
    uint32_t home_state_slot;
    uint32_t widgets_binding_slot;
    uint32_t pad_field;
    uint32_t fold_field;
    uintptr_t slot_skip;
    uintptr_t pad_visibility_fallback;
    uintptr_t phone_hide;
    uintptr_t phone_fold_show;
    uintptr_t controller_pad;
    uintptr_t drag_return;
    uintptr_t drag_first_call;
    uintptr_t geometry_fallback;
};
bool DecodeNativeDockRoute(const Image&, const uintptr_t* sites, NativeDockRouteLayout*);

struct NativeDockInteractionLayout {
    uint32_t static_table_field;
    uint32_t manager_slot;
    uint32_t sentinel_pool_offset;
    uint32_t dragging_rx_field;
    uint32_t rx_value_field;
    uint32_t drop_guard_field;
};
bool DecodeNativeDockInteraction(const Image&, uintptr_t observer, NativeDockInteractionLayout*);

struct NativeDockGlassLayout {
    uint32_t flag_field;
    uintptr_t branch_skip;
    uintptr_t factory_stub;
    uintptr_t factory_return;  // return PC of the Hotseat background call
    uintptr_t glass_entry;
    uintptr_t strategy_frame;
    // Stock words displaced by the return gate, replayed with decoded operands.
    int32_t resume_list_slot;
    int32_t resume_result_slot;
    uint32_t resume_length_field;
    // LayoutBuilder constraints argument of the Hotseat builder frame.
    int32_t constraints_arg;
    uint32_t max_height_field;
    // EdgeInsets arithmetic tail: four unboxed slots copied into a new object.
    uintptr_t insets_entry;
    uint32_t insets_frame;
    int32_t insets_slots[4];  // left, top, right, bottom
    uint32_t insets_cid;
    // Padding tail: child returned by a call, padding read from the frame.
    uintptr_t padding_return;
    uint32_t padding_frame;
    int32_t padding_slot;
    uint32_t padding_cid;
    // Hotseat item builder frame: the center call and the unlock wrapper.
    int32_t controller_slot;     // Hotseat builder slot passed as the controller
    uint32_t build_frame;
    int32_t build_child_slot;
    uint32_t center_call;        // object pool offset of the switchable call pair
    uintptr_t center_return;
    int32_t center_item_slot;
    int32_t center_slot;
    uint32_t center_field;
    uintptr_t wrap_entry;        // allocation call of the unlock wrapper
    int32_t wrap_position_slot;
    uint32_t wrap_cid;
    int32_t title_slot;
    uint32_t title_field;
    uint32_t title_pool;         // object pool offset reloaded after the gate
    uint32_t fixed_field;        // item-cell flag; false lets the unlock animation run
    // Adaptive width: the Hotseat builder slot whose context field holds the
    // item list passed to the item builder, and the column count of the cell
    // width the builder derives from its constraints (maxWidth / columns).
    int32_t items_context_slot;
    uint32_t items_field;
    uint32_t columns;
    // The item builder's inline Dock cell width: a flag on one static
    // singleton picks config.cell_field + cell_extra or config.alt_cell_field,
    // where config is the value of an Rx field on another static singleton.
    uint32_t static_table;       // thread offset of the static field table
    uint32_t sentinel_pool;      // object pool offset of the uninitialized sentinel
    uint32_t cell_flag_static;
    uint32_t cell_flag_field;
    uint32_t config_static;
    uint32_t config_rx_field;
    uint32_t rx_value_field;
    uint32_t cell_field;
    uint32_t alt_cell_field;
    double cell_extra;
};
bool DecodeNativeDockGlass(const Image&, uintptr_t gate, uintptr_t factory, uintptr_t glass,
                           uintptr_t insets, uintptr_t padding, uintptr_t center, uintptr_t wrap,
                           NativeDockGlassLayout*);

struct IconThemeObserverLayout { uint32_t static_slot, registered_field, cid; uintptr_t registration; };
bool DecodeIconThemeObserver(const Image&,uintptr_t owner,uintptr_t bootstrap,uintptr_t refresh,IconThemeObserverLayout*);

extern const TargetSpec* const kTargetSpecs[];
extern const size_t kTargetSpecCount;

}  // namespace hypertweak::native::dart

namespace hypertweak::native::dart { bool DecodeDockEditingPool(const Image&, uintptr_t, uint32_t*); }
