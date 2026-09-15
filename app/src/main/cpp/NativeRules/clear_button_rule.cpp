// SPDX-License-Identifier: Apache-2.0
#include "clear_button_rule.h"

#include "dart_rule_support.h"
#include "logging.h"
#include "lsposed_hook_backend.h"
#include "native_config.h"

#include <dlfcn.h>
#include <stdint.h>
#include <string.h>

// Defined here so the assembly replacement can reach it with adrp/add.
extern "C" {
volatile uint32_t hypertweak_clear_button_hits = 0u;
}

// Implemented in clear_button_hook.S.
extern "C" void HyperTweakRecentsClearButtonInsertHook();

namespace hypertweak::native {
namespace {

// The target is found structurally, not by build id: a launcher update relocates
// the function, and the registry re-derives it from instruction shape and
// call-graph relationships. See dart_targets.cpp for the evidence and
// dart_rule_support.h for the boundary. Nothing here carries an RVA.
constexpr char kDartLibraryName[] = "libapp.so";
constexpr char kHideClearButtonKey[] = "hide_recents_clear";

// Registry site name. Kept as a named constant so the lookup and the diagnostics
// cannot drift apart.
constexpr char kSiteInsertOverlay[] = "insert_overlay";

volatile uint32_t g_hidden = 0u;
volatile uint32_t g_installed = 0u;
volatile uint32_t g_applying = 0u;
const char* volatile g_reason = "not_attempted";
void* g_target = nullptr;
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
        if (acquired_) __atomic_store_n(&g_applying, uint32_t{0},
                                        __ATOMIC_RELEASE);
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

// A resolved target: the image base, the absolute address to patch, and the
// verification contract for the bytes that must be there.
struct DartTarget {
    uint8_t* base;
    uintptr_t address;
    dart::BytePattern verify;
    size_t verify_delta;
};

bool ResolveDartTarget(void* handle, DartTarget* output) {
    if (handle == nullptr || output == nullptr) return false;
    output->base = nullptr;
    output->address = 0u;
    output->verify = {nullptr, nullptr, 0u};
    output->verify_delta = 0u;

    DartResolution resolution{};
    if (!ResolveDartSites(handle, dart::kRecentsClearButtonTarget,
                          &resolution)) {
        // The reason is a static string chosen by the adapter; the failing site
        // is static too, so both are safe to keep and log.
        SetReason(resolution.reason);
        if (resolution.failing_site != nullptr) {
            LogWarn("clear button target unresolved: %s (%s site %s)",
                    resolution.reason, dart::kRecentsClearButtonTarget.id,
                    resolution.failing_site);
        }
        return false;
    }
    for (size_t index = 0u; index < resolution.site_count; ++index) {
        const ResolvedDartSite& site = resolution.sites[index];
        if (site.name == nullptr ||
                strcmp(site.name, kSiteInsertOverlay) != 0) {
            continue;
        }
        output->base = resolution.base;
        output->address = site.address;
        output->verify = site.verify;
        output->verify_delta = site.verify_delta;
        return true;
    }
    SetReason("dart_site_missing");
    return false;
}

// True while the original bytes are still in place. After the inline hook is
// installed they are not, which is how an already-patched target -- and a
// relocation of the target after a launcher remap -- are told apart.
bool IsOriginalBytesPresent(const DartTarget& target) {
    if (target.address == 0u || target.verify.bytes == nullptr) return false;
    return dart::MatchPatternAtAddress(target.address + target.verify_delta,
                                      target.verify);
}

void* ResolveHandleForApply(void* supplied_handle, bool* close_handle) {
    if (close_handle != nullptr) *close_handle = false;
    if (supplied_handle != nullptr) return supplied_handle;
    if (g_last_dart_handle != nullptr) return g_last_dart_handle;
    void* handle = MiuiHomeHyosCurrentDartHandle();
    if (handle != nullptr) return handle;
    handle = dlopen(kDartLibraryName, RTLD_NOW | RTLD_NOLOAD);
    if (handle != nullptr && close_handle != nullptr) *close_handle = true;
    return handle;
}

}  // namespace

void SetClearButtonHidden(bool hidden) {
    __atomic_store_n(&g_hidden, hidden ? uint32_t{1} : uint32_t{0},
                     __ATOMIC_RELEASE);
    LogInfo("clear button rule requested hidden=%d", hidden ? 1 : 0);
}

bool ClearButtonHiddenRequested() {
    return __atomic_load_n(&g_hidden, __ATOMIC_ACQUIRE) != 0u;
}

uint32_t ClearButtonHookHits() {
    return __atomic_load_n(&hypertweak_clear_button_hits, __ATOMIC_RELAXED);
}

const char* ClearButtonRuleReason() {
    return __atomic_load_n(&g_reason, __ATOMIC_ACQUIRE);
}

uintptr_t ClearButtonTargetAddress() {
    return __atomic_load_n(&g_target_address, __ATOMIC_ACQUIRE);
}

bool ApplyClearButtonRule(void* dart_handle) {
    ApplyGuard guard;
    if (!guard.acquired()) return false;
    const bool hidden = ClearButtonHiddenRequested();
    const bool installed = __atomic_load_n(&g_installed, __ATOMIC_ACQUIRE) != 0u;

    if (!hidden) {
        if (!installed) {
            SetReason("disabled");
            return true;
        }
        if (RemoveInlineHook(g_target) != kHookSuccess) {
            SetReason("unhook_failed");
            return false;
        }
        __atomic_store_n(&g_installed, uint32_t{0}, __ATOMIC_RELEASE);
        __atomic_store_n(&g_target_address, uintptr_t{0}, __ATOMIC_RELEASE);
        g_target = nullptr;
        g_last_dart_handle = nullptr;
        SetReason("removed");
        return true;
    }

    bool close_handle = false;
    void* handle = ResolveHandleForApply(dart_handle, &close_handle);
    DartTarget current{};
    const bool resolved = ResolveDartTarget(handle, &current);
    if (close_handle && handle != nullptr) dlclose(handle);
    if (!resolved) return false;
    if (handle != nullptr && !close_handle) g_last_dart_handle = handle;

    const bool same_target = installed &&
            __atomic_load_n(&g_target_address, __ATOMIC_ACQUIRE) ==
                    current.address;
    // The target's original bytes are gone once our hook is written, so "stale
    // bytes present" means the patch was lost and has to be reapplied.
    const bool original_bytes_present = IsOriginalBytesPresent(current);
    if (installed && same_target && !original_bytes_present) {
        SetReason("installed");
        return true;
    }
    if (!original_bytes_present) {
        SetReason("target_bytes_mismatch");
        LogWarn("_insertClearButtonOverlay target bytes mismatch for %s at 0x%zx; not patching",
                dart::kRecentsClearButtonTarget.id,
                static_cast<size_t>(current.address));
        return false;
    }

    if (installed) {
        if (RemoveInlineHook(g_target) != kHookSuccess) {
            SetReason("remap_unhook_failed");
            return false;
        }
        __atomic_store_n(&g_installed, uint32_t{0}, __ATOMIC_RELEASE);
        __atomic_store_n(&g_target_address, uintptr_t{0}, __ATOMIC_RELEASE);
        g_target = nullptr;
        SetReason("remap_detected");
    }

    void* original = nullptr;
    if (InstallInlineHook(
                reinterpret_cast<void*>(current.address),
                reinterpret_cast<void*>(&HyperTweakRecentsClearButtonInsertHook),
                &original) != kHookSuccess || original == nullptr) {
        SetReason("hook_failed");
        return false;
    }
    __atomic_store_n(&g_target_address, current.address, __ATOMIC_RELEASE);
    g_target = reinterpret_cast<void*>(current.address);
    __atomic_store_n(&g_installed, uint32_t{1}, __ATOMIC_RELEASE);
    SetReason("installed");
    LogInfo("clear button rule installed for %s; recents overlay insertion is suppressed",
            dart::kRecentsClearButtonTarget.id);
    return true;
}

void OnClearButtonLibraryLoaded(const char* name, void* handle) {
    if (name == nullptr || handle == nullptr || !IsDartLibraryPath(name) ||
            !ClearButtonHiddenRequested()) {
        return;
    }
    ApplyClearButtonRule(handle);
}

void MaintainClearButtonRuleOnActionDown(void* dart_handle) {
    ApplyClearButtonRule(dart_handle);
}

void ResetClearButtonStateAfterFork() {
    __atomic_store_n(&g_applying, uint32_t{0}, __ATOMIC_RELEASE);
    g_last_dart_handle = nullptr;
}

void RefreshClearButtonConfig() {
    ProbeConfigChannel();
    SetClearButtonHidden(ReadConfigFlag(kHideClearButtonKey, false));
}

}  // namespace hypertweak::native
