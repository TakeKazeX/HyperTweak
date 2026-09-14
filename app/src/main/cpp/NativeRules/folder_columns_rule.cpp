// SPDX-License-Identifier: Apache-2.0
#include "folder_columns_rule.h"

#include "clear_button_rule.h"
#include "logging.h"
#include "lsposed_hook_backend.h"
#include "native_config.h"

#include <dlfcn.h>
#include <elf.h>
#include <stdint.h>
#include <string.h>

// The assembly replacements read the tagged Dart integer directly and emulate
// the exact Dart return epilogues. Keep the cells hidden so ADRP/ADD can address
// them without a GOT lookup.
extern "C" {
__attribute__((used, visibility("hidden")))
volatile uint64_t hypertweak_folder_columns_smi = uint64_t{6};
__attribute__((used, visibility("hidden")))
volatile uint32_t hypertweak_folder_columns_hits = uint32_t{0};
__attribute__((used, visibility("hidden")))
volatile uintptr_t hypertweak_folder_preview_set_items_continuation = 0u;
}

// Implemented in folder_columns_hook.S.
extern "C" void HyperTweakFolderColumnsHook();
extern "C" void HyperTweakFolderPreviewIconColumnsHook();
extern "C" void HyperTweakFolderPreviewItemsMaxCountHook();
extern "C" void HyperTweakFolderPreviewSetItemsHook();

namespace hypertweak::native {
namespace {

// These profiles are verified against the exact Dart AOT snapshots stored in
// /Users/ink/developer/reverse/:
//   6264: 1723fc1114a70c88f863d509d6f6183a5bc217b9d5dd0b806aa2c7eeb0d7650c
//   7653: a747a7ef8c65cde838b1d8e9686af437e4e08b88446725e6c892e764182ad33c
// The upstream framework still owns image discovery, lifecycle, and remap
// handling; this table only describes this feature's exact target.
constexpr char kDartLibraryName[] = "libapp.so";
constexpr size_t kDartBuildIdSize = 16u;
constexpr size_t kDartBuildNoteHeaderSize = 16u;
constexpr char kFolderColumnsKey[] = "opened_folder_columns";
constexpr int32_t kDefaultFolderColumns = 3;
constexpr int32_t kMinFolderColumns = 3;
constexpr int32_t kMaxFolderColumns = 5;
constexpr size_t kPreviewTargetCount = 3u;

constexpr uint8_t kDartBuildId6264[kDartBuildIdSize] = {
    0x4f, 0x1b, 0xda, 0xed, 0xdc, 0x7d, 0x86, 0x06,
    0xde, 0x7d, 0x3e, 0xbc, 0x69, 0x99, 0x4a, 0x81,
};
constexpr uint8_t kDartBuildId7653[kDartBuildIdSize] = {
    0x4f, 0x1b, 0xda, 0xed, 0xd4, 0x7b, 0xeb, 0x4e,
    0xde, 0x7d, 0x3e, 0xbc, 0xd2, 0x92, 0x50, 0x4e,
};

// The following four-instruction epilogues are stable across the verified
// snapshots. Hooking at the epilogue leaves the complete original Getter body
// (including its state/cache reads) intact for folder open/close animation.
constexpr uint8_t kFolderGridReturnEpilogue[16] = {
    0x00, 0x18, 0x00, 0x91, 0xef, 0x03, 0x1d, 0xaa,
    0xfd, 0x79, 0xc1, 0xa8, 0xc0, 0x03, 0x5f, 0xd6,
};

constexpr uint8_t kPreviewIconReturnEpilogue[16] = {
    0xe0, 0x03, 0x01, 0xaa, 0xef, 0x03, 0x1d, 0xaa,
    0xfd, 0x79, 0xc1, 0xa8, 0xc0, 0x03, 0x5f, 0xd6,
};
constexpr uint8_t kPreviewItemsReturnEpilogue[16] = {
    0x20, 0x7c, 0x01, 0x9b, 0xef, 0x03, 0x1d, 0xaa,
    0xfd, 0x79, 0xc1, 0xa8, 0xc0, 0x03, 0x5f, 0xd6,
};
constexpr uint8_t kPreviewSetItemsReadPrologue6264[16] = {
    0x40, 0x3f, 0x40, 0xf9, 0x00, 0xac, 0x63, 0xf9,
    0x70, 0x23, 0x40, 0xf9, 0x1f, 0x00, 0x10, 0x6b,
};
constexpr uint8_t kPreviewSetItemsReadPrologue7653[16] = {
    0x40, 0x3f, 0x40, 0xf9, 0x00, 0xc0, 0x64, 0xf9,
    0x70, 0x23, 0x40, 0xf9, 0x1f, 0x00, 0x10, 0x6b,
};

struct FolderColumnsTarget {
    uintptr_t rva;
    const uint8_t* prologue;
    size_t prologue_size;
};

struct FolderColumnsProfile {
    const char* id;
    const uint8_t* build_id;
    FolderColumnsTarget grid_return;
    FolderColumnsTarget preview_icon;
    FolderColumnsTarget preview_items;
    FolderColumnsTarget preview_set_items_read;
    uintptr_t preview_set_items_continuation_rva;
};

constexpr FolderColumnsProfile kFolderColumnsProfiles[] = {
    {"6264", kDartBuildId6264,
            {0x00930404u, kFolderGridReturnEpilogue,
                    sizeof(kFolderGridReturnEpilogue)},
            {0x01781de8u, kPreviewIconReturnEpilogue,
                    sizeof(kPreviewIconReturnEpilogue)},
            {0x01782778u, kPreviewItemsReturnEpilogue,
                    sizeof(kPreviewItemsReturnEpilogue)},
            {0x0152dcb4u, kPreviewSetItemsReadPrologue6264,
                    sizeof(kPreviewSetItemsReadPrologue6264)},
            0x0152dcd4u},
    {"7653", kDartBuildId7653,
            {0x008fec50u, kFolderGridReturnEpilogue,
                    sizeof(kFolderGridReturnEpilogue)},
            {0x0182e840u, kPreviewIconReturnEpilogue,
                    sizeof(kPreviewIconReturnEpilogue)},
            {0x0182f538u, kPreviewItemsReturnEpilogue,
                    sizeof(kPreviewItemsReturnEpilogue)},
            {0x015e4d20u, kPreviewSetItemsReadPrologue7653,
                    sizeof(kPreviewSetItemsReadPrologue7653)},
            0x015e4d40u},
};

volatile int32_t g_requested_columns = kDefaultFolderColumns;
volatile uint32_t g_installed = 0u;
volatile uint32_t g_applying = 0u;
const char* volatile g_reason = "not_attempted";
void* g_target = nullptr;
void* g_preview_targets[kPreviewTargetCount] = {};
void* g_last_dart_handle = nullptr;
volatile uintptr_t g_target_address = 0u;

void SetReason(const char* reason) {
    __atomic_store_n(&g_reason, reason, __ATOMIC_RELEASE);
}

class ApplyGuard {
  public:
    ApplyGuard() : acquired_(false) {
        uint32_t expected = 0u;
        acquired_ = __atomic_compare_exchange_n(
                &g_applying, &expected, uint32_t{1}, false,
                __ATOMIC_ACQUIRE, __ATOMIC_RELAXED);
    }
    ~ApplyGuard() {
        if (acquired_) {
            __atomic_store_n(&g_applying, uint32_t{0}, __ATOMIC_RELEASE);
        }
    }

    bool acquired() const { return acquired_; }

  private:
    bool acquired_;
};

bool IsDartLibraryPath(const char* path) {
    if (path == nullptr) return false;
    const char* slash = strrchr(path, '/');
    const char* basename = slash == nullptr ? path : slash + 1;
    return strcmp(basename, kDartLibraryName) == 0;
}

struct DartTarget {
    uint8_t* base;
    uintptr_t address;
    const FolderColumnsProfile* profile;
};

const FolderColumnsProfile* FindFolderColumnsProfile(
        const uint8_t* build_id) {
    if (build_id == nullptr) return nullptr;
    for (const auto& profile : kFolderColumnsProfiles) {
        if (memcmp(build_id, profile.build_id, kDartBuildIdSize) == 0) {
            return &profile;
        }
    }
    return nullptr;
}

bool ResolveDartTarget(void* handle, DartTarget* output) {
    if (handle == nullptr || output == nullptr) return false;
    output->base = nullptr;
    output->address = 0u;
    output->profile = nullptr;

    const uint8_t* build_id = nullptr;
    uint8_t* base = nullptr;
    if (!MiuiHomeHyosResolveDartImage(handle, &base, &build_id) ||
            base == nullptr || build_id == nullptr) {
        SetReason("build_id_mismatch");
        return false;
    }
    const FolderColumnsProfile* profile = FindFolderColumnsProfile(
            build_id + kDartBuildNoteHeaderSize);
    if (profile == nullptr) {
        SetReason("build_id_mismatch");
        return false;
    }
    if (profile->grid_return.rva > UINTPTR_MAX -
            reinterpret_cast<uintptr_t>(base)) {
        SetReason("target_overflow");
        return false;
    }
    if (!MiuiHomeHyosDartRangeHasFlags(
                base, profile->grid_return.rva,
                profile->grid_return.prologue_size,
                PF_R | PF_X)) {
        SetReason("target_unmapped");
        return false;
    }
    output->base = base;
    output->address = reinterpret_cast<uintptr_t>(base) +
            profile->grid_return.rva;
    output->profile = profile;
    return true;
}

bool ResolveDartRange(const DartTarget& dart, uintptr_t rva, size_t size,
                      uintptr_t* address) {
    if (dart.base == nullptr || address == nullptr ||
            rva > UINTPTR_MAX - reinterpret_cast<uintptr_t>(dart.base) ||
            !MiuiHomeHyosDartRangeHasFlags(
                    dart.base, rva, size, PF_R | PF_X)) {
        return false;
    }
    *address = reinterpret_cast<uintptr_t>(dart.base) + rva;
    return true;
}

bool ResolveTargetAddress(const DartTarget& dart, const FolderColumnsTarget& target,
                          uintptr_t* address) {
    return ResolveDartRange(dart, target.rva, target.prologue_size, address);
}

bool MatchesTargetPrologue(uintptr_t address, const FolderColumnsTarget& target) {
    return address != 0u &&
            memcmp(reinterpret_cast<const void*>(address),
                   target.prologue, target.prologue_size) == 0;
}

void* PreviewReplacement(size_t index) {
    switch (index) {
        case 0u:
            return reinterpret_cast<void*>(&HyperTweakFolderPreviewIconColumnsHook);
        case 1u:
            return reinterpret_cast<void*>(&HyperTweakFolderPreviewItemsMaxCountHook);
        case 2u:
            return reinterpret_cast<void*>(&HyperTweakFolderPreviewSetItemsHook);
        default:
            return nullptr;
    }
}

bool HasInstalledHookState() {
    if (g_target != nullptr) return true;
    for (void* target : g_preview_targets) {
        if (target != nullptr) return true;
    }
    return false;
}

bool RemoveInstalledHooks() {
    bool success = true;
    if (g_target != nullptr) {
        if (RemoveInlineHook(g_target) == kHookSuccess) {
            g_target = nullptr;
        } else {
            success = false;
        }
    }
    for (size_t index = 0u; index < kPreviewTargetCount; ++index) {
        if (g_preview_targets[index] == nullptr) continue;
        if (RemoveInlineHook(g_preview_targets[index]) == kHookSuccess) {
            g_preview_targets[index] = nullptr;
        } else {
            success = false;
        }
    }
    if (!success) return false;
    __atomic_store_n(&g_installed, uint32_t{0}, __ATOMIC_RELEASE);
    __atomic_store_n(&g_target_address, uintptr_t{0}, __ATOMIC_RELEASE);
    __atomic_store_n(&hypertweak_folder_preview_set_items_continuation,
                     uintptr_t{0}, __ATOMIC_RELEASE);
    return true;
}

void* ResolveHandleForApply(void* supplied_handle, bool* close_handle) {
    if (close_handle != nullptr) *close_handle = false;
    if (supplied_handle != nullptr) return supplied_handle;
    if (g_last_dart_handle != nullptr) return g_last_dart_handle;
    void* current = MiuiHomeHyosCurrentDartHandle();
    if (current != nullptr) return current;
    current = dlopen(kDartLibraryName, RTLD_NOW | RTLD_NOLOAD);
    if (current != nullptr && close_handle != nullptr) *close_handle = true;
    return current;
}

int32_t NormalizeColumns(int32_t columns) {
    return columns >= kMinFolderColumns && columns <= kMaxFolderColumns
            ? columns : kDefaultFolderColumns;
}

}  // namespace

void SetFolderColumns(int32_t columns) {
    const int32_t normalized = NormalizeColumns(columns);
    __atomic_store_n(&g_requested_columns, normalized, __ATOMIC_RELEASE);
    __atomic_store_n(&hypertweak_folder_columns_smi,
                     static_cast<uint64_t>(normalized) << 1u,
                     __ATOMIC_RELEASE);
    LogInfo("opened folder columns requested=%d", normalized);
}

int32_t FolderColumnsRequested() {
    return __atomic_load_n(&g_requested_columns, __ATOMIC_ACQUIRE);
}

uint32_t FolderColumnsHookHits() {
    return __atomic_load_n(&hypertweak_folder_columns_hits,
                           __ATOMIC_RELAXED);
}

const char* FolderColumnsRuleReason() {
    return __atomic_load_n(&g_reason, __ATOMIC_ACQUIRE);
}

uintptr_t FolderColumnsTargetAddress() {
    return __atomic_load_n(&g_target_address, __ATOMIC_ACQUIRE);
}

bool ApplyFolderColumnsRule(void* dart_handle) {
    ApplyGuard guard;
    if (!guard.acquired()) return false;

    const int32_t columns = FolderColumnsRequested();
    const bool installed = __atomic_load_n(&g_installed, __ATOMIC_ACQUIRE) != 0u ||
            HasInstalledHookState();
    if (columns == kDefaultFolderColumns) {
        if (!installed) {
            SetReason("disabled");
            return true;
        }
        if (!RemoveInstalledHooks()) {
            SetReason("unhook_failed");
            return false;
        }
        g_last_dart_handle = nullptr;
        SetReason("removed");
        LogInfo("opened folder columns hook removed; native default restored");
        return true;
    }

    bool close_handle = false;
    void* handle = ResolveHandleForApply(dart_handle, &close_handle);
    DartTarget current{};
    const bool resolved = ResolveDartTarget(handle, &current);
    if (close_handle && handle != nullptr) dlclose(handle);
    if (!resolved) return false;
    if (handle != nullptr && !close_handle) g_last_dart_handle = handle;

    const FolderColumnsProfile& profile = *current.profile;
    const FolderColumnsTarget* preview_targets[kPreviewTargetCount] = {
        &profile.preview_icon,
        &profile.preview_items,
        &profile.preview_set_items_read,
    };
    uintptr_t preview_addresses[kPreviewTargetCount] = {};
    for (size_t index = 0u; index < kPreviewTargetCount; ++index) {
        if (!ResolveTargetAddress(current, *preview_targets[index],
                                  &preview_addresses[index])) {
            SetReason("preview_target_unmapped");
            LogWarn("opened folder preview target %zu is unmapped for %s; not patching",
                    index, profile.id);
            return false;
        }
    }
    uintptr_t preview_set_items_continuation = 0u;
    if (!ResolveDartRange(
                current, profile.preview_set_items_continuation_rva,
                sizeof(uint32_t), &preview_set_items_continuation)) {
        SetReason("preview_continuation_unmapped");
        LogWarn("opened folder preview continuation is unmapped for %s; not patching",
                profile.id);
        return false;
    }

    bool same_installation = installed &&
            g_target == reinterpret_cast<void*>(current.address);
    for (size_t index = 0u; index < kPreviewTargetCount; ++index) {
        same_installation = same_installation &&
                g_preview_targets[index] ==
                        reinterpret_cast<void*>(preview_addresses[index]);
    }
    bool all_targets_patched = same_installation &&
            !MatchesTargetPrologue(current.address, profile.grid_return);
    for (size_t index = 0u; index < kPreviewTargetCount; ++index) {
        all_targets_patched = all_targets_patched &&
                !MatchesTargetPrologue(preview_addresses[index],
                                       *preview_targets[index]);
    }
    if (all_targets_patched) {
        SetReason("installed");
        return true;
    }

    if (installed) {
        if (!RemoveInstalledHooks()) {
            SetReason("remap_unhook_failed");
            return false;
        }
        SetReason("remap_detected");
    }

    if (!MatchesTargetPrologue(current.address, profile.grid_return)) {
        SetReason("prologue_mismatch");
        LogWarn("GridController.folderGridViewCol return epilogue mismatch for %s at 0x%zx; not patching",
                profile.id, static_cast<size_t>(current.address));
        return false;
    }
    for (size_t index = 0u; index < kPreviewTargetCount; ++index) {
        if (MatchesTargetPrologue(preview_addresses[index],
                                  *preview_targets[index])) {
            continue;
        }
        SetReason("preview_prologue_mismatch");
        LogWarn("opened folder preview target %zu prologue mismatch for %s at 0x%zx; not patching",
                index, profile.id, static_cast<size_t>(preview_addresses[index]));
        return false;
    }

    __atomic_store_n(&hypertweak_folder_preview_set_items_continuation,
                     preview_set_items_continuation, __ATOMIC_RELEASE);
    void* original = nullptr;
    if (InstallInlineHook(
                reinterpret_cast<void*>(current.address),
                reinterpret_cast<void*>(&HyperTweakFolderColumnsHook),
                &original) != kHookSuccess || original == nullptr) {
        SetReason("hook_failed");
        return false;
    }
    g_target = reinterpret_cast<void*>(current.address);

    for (size_t index = 0u; index < kPreviewTargetCount; ++index) {
        void* unused_original = nullptr;
        if (InstallInlineHook(
                    reinterpret_cast<void*>(preview_addresses[index]),
                    PreviewReplacement(index), &unused_original) !=
                        kHookSuccess || unused_original == nullptr) {
            RemoveInstalledHooks();
            SetReason("preview_hook_failed");
            return false;
        }
        g_preview_targets[index] =
                reinterpret_cast<void*>(preview_addresses[index]);
    }

    __atomic_store_n(&g_target_address, current.address, __ATOMIC_RELEASE);
    __atomic_store_n(&g_installed, uint32_t{1}, __ATOMIC_RELEASE);
    SetReason("installed");
    LogInfo("opened folder columns hook installed for %s: %d columns; preview held at 3",
            profile.id, columns);
    return true;
}

void OnFolderColumnsLibraryLoaded(const char* name, void* handle) {
    if (name == nullptr || handle == nullptr || !IsDartLibraryPath(name)) {
        return;
    }
    // Apply the default path as well: a HYOS fork can inherit a previous
    // installation, and the default must be able to remove that stale hook.
    ApplyFolderColumnsRule(handle);
}

void MaintainFolderColumnsRuleOnActionDown(void* dart_handle) {
    ApplyFolderColumnsRule(dart_handle);
}

void ResetFolderColumnsStateAfterFork() {
    __atomic_store_n(&g_applying, uint32_t{0}, __ATOMIC_RELEASE);
    g_last_dart_handle = nullptr;
}

void RefreshFolderColumnsConfig() {
    ProbeConfigChannel();
    SetFolderColumns(ReadConfigInt(kFolderColumnsKey,
                                   kDefaultFolderColumns));
}

}  // namespace hypertweak::native
