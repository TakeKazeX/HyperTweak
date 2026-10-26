// SPDX-License-Identifier: Apache-2.0
#include "assistant_widget_rule.h"
#include "clear_button_rule.h"

#include "dart_rule_support.h"
#include "logging.h"
#include "lsposed_hook_backend.h"
#include "native_config.h"
#include "native_rule_runtime.h"

#include <dlfcn.h>
#include <stdint.h>
#include <string.h>

// Defined here so the assembly replacement can reach it with adrp/add.
extern "C" {
volatile uint32_t hypertweak_assistant_widget_hits = 0u;
uintptr_t hypertweak_assistant_widget_continue = 0u;
uintptr_t hypertweak_assistant_widget_accepted = 0u;
uintptr_t hypertweak_assistant_widget_reject = 0u;
uintptr_t hypertweak_assistant_widget_field = 0u;
uint32_t hypertweak_assistant_widget_cid = 0u;
}

// Implemented in assistant_widget_hook.S.
extern "C" void HyperTweakAssistantWidgetGateHook();

namespace hypertweak::native {
namespace {

// The target is found structurally, not by build id: a launcher update relocates
// the function, and the registry re-derives it from instruction shape and
// call-graph relationships. See dart_targets.cpp for the evidence and
// dart_rule_support.h for the boundary. Nothing here carries an RVA.
constexpr char kDartLibraryName[] = "libapp.so";
constexpr char kAllowAssistantWidgetsKey[] = "allow_android_widgets_to_assistant";

// Registry site name. Kept as a named constant so the lookup and the diagnostics
// cannot drift apart.
constexpr char kSiteEligibility[] = "widget_eligibility";

volatile uint32_t g_enabled = 0u;
volatile uint32_t g_installed = 0u;
volatile uint32_t g_applying = 0u;
const char* volatile g_reason = "not_attempted";
void* g_target = nullptr;
uint8_t g_installed_patch[16]{};
uint8_t g_original_site[16]{};
const uint8_t* g_target_base = nullptr;
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
    uintptr_t type_site;
    size_t verify_delta;
};

bool ResolveDartTarget(void* handle, DartTarget* output) {
    if (output == nullptr) return false;
    if (handle == nullptr) { SetReason("dart_image_unresolved"); return false; }
    output->base = nullptr;
    output->address = 0u;
    output->verify = {nullptr, nullptr, 0u};
    output->verify_delta = 0u;

    DartResolution resolution{};
    if (!ResolveDartSites(handle, dart::kAssistantWidgetTarget,
                          &resolution)) {
        // The reason is a static string chosen by the adapter; the failing site
        // is static too, so both are safe to keep and log.
        const char* previous = __atomic_load_n(&g_reason, __ATOMIC_ACQUIRE);
        SetReason(resolution.reason);
        if (resolution.failing_site != nullptr && previous != resolution.reason) {
            LogWarn("assistant widget target unresolved: %s (%s site %s)",
                    resolution.reason, dart::kAssistantWidgetTarget.id,
                    resolution.failing_site);
        }
        return false;
    }
    output->type_site = resolution.Find("app_widget_type");
    if (output->type_site == 0u) { SetReason("dart_site_missing"); return false; }
    for (size_t index = 0u; index < resolution.site_count; ++index) {
        const ResolvedDartSite& site = resolution.sites[index];
        if (site.name == nullptr ||
                strcmp(site.name, kSiteEligibility) != 0) {
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
    if (void* current = NativeRuleCurrentDartHandle()) return current;
    if (g_last_dart_handle != nullptr) return g_last_dart_handle;
    void* handle = MiuiHomeHyosCurrentDartHandle();
    if (handle != nullptr) return handle;
    return nullptr; // Only the preparation worker may query the loader.
}

}  // namespace

void SetAssistantWidgetAllowed(bool enabled) {
    __atomic_store_n(&g_enabled, enabled ? uint32_t{1} : uint32_t{0},
                     __ATOMIC_RELEASE);
    LogInfo("assistant widget rule requested enabled=%d", enabled ? 1 : 0);
}

bool AssistantWidgetAllowedRequested() {
    return __atomic_load_n(&g_enabled, __ATOMIC_ACQUIRE) != 0u;
}

uint32_t AssistantWidgetHookHits() {
    return __atomic_load_n(&hypertweak_assistant_widget_hits, __ATOMIC_RELAXED);
}

const char* AssistantWidgetRuleReason() {
    return __atomic_load_n(&g_reason, __ATOMIC_ACQUIRE);
}

uintptr_t AssistantWidgetTargetAddress() {
    return __atomic_load_n(&g_target_address, __ATOMIC_ACQUIRE);
}

bool ApplyAssistantWidgetRule(void* dart_handle) {
    ApplyGuard guard;
    if (!guard.acquired()) return false;
    const bool enabled = AssistantWidgetAllowedRequested();
    const bool installed = __atomic_load_n(&g_installed, __ATOMIC_ACQUIRE) != 0u;

    if (!enabled) {
        if (!installed) {
            SetReason("disabled");
            return true;
        }
        const uintptr_t address = reinterpret_cast<uintptr_t>(g_target);
        const uintptr_t base = reinterpret_cast<uintptr_t>(g_target_base);
        if (g_target_base == nullptr || address < base ||
            !MiuiHomeHyosDartRangeHasFlags(g_target_base, address - base, 16u, 5u) ||
            (memcmp(g_target, g_installed_patch, 16u) != 0 &&
             memcmp(g_target, g_original_site, 16u) != 0)) {
            SetReason("foreign_patch_detected");
            return false;
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
        const bool owned = memcmp(g_installed_patch,
                                  reinterpret_cast<const void*>(current.address),
                                  sizeof(g_installed_patch)) == 0;
        SetReason(owned ? "installed" : "foreign_patch_detected");
        return owned;
    }
    if (!original_bytes_present) {
        SetReason("target_bytes_mismatch");
        LogWarn("assistant widget eligibility target bytes mismatch for %s at 0x%zx; not patching",
                dart::kAssistantWidgetTarget.id,
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

    uint32_t flag_read = 0u, branch = 0u, type_test = 0u;
    memcpy(&flag_read, reinterpret_cast<const void*>(current.address), 4u);
    memcpy(&branch, reinterpret_cast<const void*>(current.address + 8u), 4u);
    memcpy(&type_test, reinterpret_cast<const void*>(current.type_site), 4u);
    const uint32_t field = (flag_read >> 12u) & 0x1ffu;
    if (field >= 0x100u || (type_test & 0xffc003ffu) != 0xf100003fu ||
        (branch & 0xfff8001fu) != 0x37200000u) {
        SetReason("widget_contract_mismatch"); return false;
    }
    int32_t displacement = static_cast<int32_t>((branch >> 5u) & 0x3fffu);
    if ((displacement & 0x2000) != 0) displacement -= 0x4000;
    const uintptr_t reject = current.address + 8u + static_cast<intptr_t>(displacement) * 4;
    const uintptr_t base = reinterpret_cast<uintptr_t>(current.base);
    if (reject < base || !MiuiHomeHyosDartRangeHasFlags(current.base, reject - base, 4u, 5u)) {
        SetReason("widget_contract_mismatch"); return false;
    }
    __atomic_store_n(&hypertweak_assistant_widget_field, static_cast<uintptr_t>(field), __ATOMIC_RELEASE);
    __atomic_store_n(&hypertweak_assistant_widget_cid, (type_test >> 10u) & 0xfffu, __ATOMIC_RELEASE);
    __atomic_store_n(&hypertweak_assistant_widget_continue, current.address + 16u, __ATOMIC_RELEASE);
    __atomic_store_n(&hypertweak_assistant_widget_reject, reject, __ATOMIC_RELEASE);
    __atomic_store_n(&hypertweak_assistant_widget_accepted, current.address + 28u, __ATOMIC_RELEASE);
    memcpy(g_original_site, reinterpret_cast<const void*>(current.address), 16u);
    g_target_base = current.base;
    void* original = nullptr;
    if (InstallInlineHook(
                reinterpret_cast<void*>(current.address),
                reinterpret_cast<void*>(&HyperTweakAssistantWidgetGateHook),
                &original) != kHookSuccess || original == nullptr) {
        SetReason("hook_failed");
        return false;
    }
    __atomic_store_n(&g_target_address, current.address, __ATOMIC_RELEASE);
    g_target = reinterpret_cast<void*>(current.address);
    memcpy(g_installed_patch, g_target, sizeof(g_installed_patch));
    __atomic_store_n(&g_installed, uint32_t{1}, __ATOMIC_RELEASE);
    SetReason("installed");
    LogInfo("assistant widget rule installed for %s; Android AppWidget identity/size eligibility is relaxed; provider metadata and requested spans are preserved",
            dart::kAssistantWidgetTarget.id);
    return true;
}

void OnAssistantWidgetLibraryLoaded(const char* name, void* handle) {
    if (name == nullptr || handle == nullptr || !IsDartLibraryPath(name) ||
            !AssistantWidgetAllowedRequested()) {
        return;
    }
    ApplyAssistantWidgetRule(handle);
}

void MaintainAssistantWidgetRuleOnActionDown(void* dart_handle) {
    ApplyAssistantWidgetRule(dart_handle);
}

void ResetAssistantWidgetStateAfterFork() {
    __atomic_store_n(&g_applying, uint32_t{0}, __ATOMIC_RELEASE);
    g_last_dart_handle = nullptr;
}

void RefreshAssistantWidgetConfig() {
    ProbeConfigChannel();
    SetAssistantWidgetAllowed(ReadConfigFlag(kAllowAssistantWidgetsKey, false));
}

}  // namespace hypertweak::native
