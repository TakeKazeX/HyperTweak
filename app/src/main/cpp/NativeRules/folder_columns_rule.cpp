// SPDX-License-Identifier: Apache-2.0
#include "folder_columns_rule.h"

#include "clear_button_rule.h"
#include "dart_rule_support.h"
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

// Targets are found structurally, not by build id: a launcher update relocates
// them, and the registry re-derives each one from instruction shape and
// call-graph relationships. See dart_targets.cpp for the evidence and
// dart_rule_support.h for the boundary. No RVA appears in this file.
constexpr char kDartLibraryName[] = "libapp.so";
constexpr char kFolderColumnsKey[] = "opened_folder_columns";
constexpr int32_t kDefaultFolderColumns = 3;
constexpr int32_t kMinFolderColumns = 3;
constexpr int32_t kMaxFolderColumns = 5;
constexpr size_t kPreviewTargetCount = 3u;

// Registry site names, shared by the resolution look-ups, the hook table and the
// diagnostics so they cannot drift apart.
constexpr char kSiteGridReturn[] = "grid_return";
constexpr char kSitePreviewIcon[] = "preview_icon";
constexpr char kSitePreviewItems[] = "preview_items";
constexpr char kSitePreviewSetItems[] = "preview_set_items";
constexpr char kSitePreviewContinuation[] = "preview_continuation";

// The three preview sites, in the order PreviewReplacement() expects: icon,
// item-count, item-list.
constexpr const char* kPreviewSites[kPreviewTargetCount] = {
    kSitePreviewIcon,
    kSitePreviewItems,
    kSitePreviewSetItems,
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

// A resolved target: image base, the per-site addresses, and the resolution
// itself so the apply path can re-check the bytes it is about to patch.
struct DartTarget {
    uint8_t* base;
    uintptr_t address;              // grid return epilogue
    uintptr_t preview[kPreviewTargetCount];
    uintptr_t preview_continuation;
    DartResolution resolution;
};

bool ResolveDartTarget(void* handle, DartTarget* output) {
    if (handle == nullptr || output == nullptr) return false;
    output->base = nullptr;
    output->address = 0u;
    output->preview_continuation = 0u;
    for (size_t index = 0u; index < kPreviewTargetCount; ++index) {
        output->preview[index] = 0u;
    }
    output->resolution = DartResolution{};

    if (!ResolveDartSites(handle, dart::kFolderColumnsTarget,
                          &output->resolution)) {
        SetReason(output->resolution.reason);
        if (output->resolution.failing_site != nullptr) {
            LogWarn("opened folder columns target unresolved: %s (%s site %s)",
                    output->resolution.reason, dart::kFolderColumnsTarget.id,
                    output->resolution.failing_site);
        }
        return false;
    }
    output->base = output->resolution.base;
    output->address = output->resolution.Find(kSiteGridReturn);
    output->preview_continuation =
            output->resolution.Find(kSitePreviewContinuation);
    for (size_t index = 0u; index < kPreviewTargetCount; ++index) {
        output->preview[index] = output->resolution.Find(kPreviewSites[index]);
    }
    // Find() returns 0 for a missing name. The registry resolves every required
    // site or fails, so a zero here means the spec and this table disagree.
    if (output->address == 0u || output->preview_continuation == 0u) {
        SetReason("dart_site_missing");
        return false;
    }
    for (size_t index = 0u; index < kPreviewTargetCount; ++index) {
        if (output->preview[index] == 0u) {
            SetReason("dart_site_missing");
            return false;
        }
    }
    return true;
}

// True while the original bytes are still in place at `site`. After the inline
// hook is installed they are not, which is how an already-patched target -- and a
// relocation after a launcher remap -- are told apart.
bool IsOriginalBytesPresent(const DartResolution& resolution,
                            const char* site_name) {
    for (size_t index = 0u; index < resolution.site_count; ++index) {
        const ResolvedDartSite& site = resolution.sites[index];
        if (site.name == nullptr || strcmp(site.name, site_name) != 0) continue;
        if (site.address == 0u || site.verify.bytes == nullptr) return false;
        return dart::MatchPatternAtAddress(site.address + site.verify_delta,
                                           site.verify);
    }
    return false;
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

    // Addresses come straight from the registry, which already proved each site
    // unique and verified its bytes. Copying them into a local array keeps the
    // comparison below readable.
    uintptr_t preview_addresses[kPreviewTargetCount] = {};
    for (size_t index = 0u; index < kPreviewTargetCount; ++index) {
        preview_addresses[index] = current.preview[index];
    }
    const uintptr_t preview_set_items_continuation =
            current.preview_continuation;

    bool same_installation = installed &&
            g_target == reinterpret_cast<void*>(current.address);
    for (size_t index = 0u; index < kPreviewTargetCount; ++index) {
        same_installation = same_installation &&
                g_preview_targets[index] ==
                        reinterpret_cast<void*>(preview_addresses[index]);
    }
    // Every site our hook overwrites must now differ from its original bytes;
    // otherwise part of the installation was lost and it has to be redone.
    bool all_targets_patched = same_installation &&
            !IsOriginalBytesPresent(current.resolution, kSiteGridReturn);
    all_targets_patched = all_targets_patched &&
            !IsOriginalBytesPresent(current.resolution, kSitePreviewIcon);
    all_targets_patched = all_targets_patched &&
            !IsOriginalBytesPresent(current.resolution, kSitePreviewItems);
    all_targets_patched = all_targets_patched &&
            !IsOriginalBytesPresent(current.resolution, kSitePreviewSetItems);
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

    if (!IsOriginalBytesPresent(current.resolution, kSiteGridReturn)) {
        SetReason("target_bytes_mismatch");
        LogWarn("GridController.folderGridViewCol return epilogue mismatch for %s at 0x%zx; not patching",
                dart::kFolderColumnsTarget.id,
                static_cast<size_t>(current.address));
        return false;
    }
    for (size_t index = 0u; index < kPreviewTargetCount; ++index) {
        if (IsOriginalBytesPresent(current.resolution, kPreviewSites[index])) {
            continue;
        }
        SetReason("preview_bytes_mismatch");
        LogWarn("opened folder preview target %s mismatch for %s at 0x%zx; not patching",
                kPreviewSites[index], dart::kFolderColumnsTarget.id,
                static_cast<size_t>(preview_addresses[index]));
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
            dart::kFolderColumnsTarget.id, columns);
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
