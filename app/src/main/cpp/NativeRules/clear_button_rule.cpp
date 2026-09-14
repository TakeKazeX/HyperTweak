// SPDX-License-Identifier: Apache-2.0
#include "clear_button_rule.h"

#include "logging.h"
#include "lsposed_hook_backend.h"
#include "native_config.h"

#include <dlfcn.h>
#include <elf.h>
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

// Verified against MiuiHome RELEASE-8.01.02.6264 and 7653 (libapp.so, Dart
// 3.10.1). The upstream framework owns image discovery and lifecycle repair;
// this table only describes the feature target for each verified snapshot.
constexpr char kDartLibraryName[] = "libapp.so";
constexpr uint8_t kDartBuildId6264[16] = {
    0x4f, 0x1b, 0xda, 0xed, 0xdc, 0x7d, 0x86, 0x06,
    0xde, 0x7d, 0x3e, 0xbc, 0x69, 0x99, 0x4a, 0x81,
};
constexpr uint8_t kDartBuildId7653[16] = {
    0x4f, 0x1b, 0xda, 0xed, 0xd4, 0x7b, 0xeb, 0x4e,
    0xde, 0x7d, 0x3e, 0xbc, 0xd2, 0x92, 0x50, 0x4e,
};
constexpr uint8_t kInsertClearButtonOverlayPrologue[16] = {
    0xfd, 0x79, 0xbf, 0xa9, 0xfd, 0x03, 0x0f, 0xaa,
    0xef, 0xe1, 0x00, 0xd1, 0xa1, 0x83, 0x1f, 0xf8,
};
constexpr size_t kDartBuildNoteHeaderSize = 16u;
constexpr char kHideClearButtonKey[] = "hide_recents_clear";

struct ClearButtonProfile {
    const char* id;
    const uint8_t* build_id;
    uintptr_t target_rva;
    const uint8_t* target_prologue;
    size_t target_prologue_size;
};

constexpr ClearButtonProfile kClearButtonProfiles[] = {
    {"6264", kDartBuildId6264, 0x01354b74u,
            kInsertClearButtonOverlayPrologue,
            sizeof(kInsertClearButtonOverlayPrologue)},
    {"7653", kDartBuildId7653, 0x013f0e54u,
            kInsertClearButtonOverlayPrologue,
            sizeof(kInsertClearButtonOverlayPrologue)},
};

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

struct DartTarget {
    uint8_t* base;
    uintptr_t address;
    const ClearButtonProfile* profile;
};

const ClearButtonProfile* FindClearButtonProfile(const uint8_t* build_id) {
    if (build_id == nullptr) return nullptr;
    for (const auto& profile : kClearButtonProfiles) {
        if (memcmp(build_id, profile.build_id, sizeof(kDartBuildId6264)) == 0) {
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
    const ClearButtonProfile* profile = FindClearButtonProfile(
            build_id + kDartBuildNoteHeaderSize);
    if (profile == nullptr) {
        SetReason("build_id_mismatch");
        return false;
    }
    if (profile->target_rva > UINTPTR_MAX -
            reinterpret_cast<uintptr_t>(base)) {
        SetReason("target_overflow");
        return false;
    }
    if (!MiuiHomeHyosDartRangeHasFlags(
                base, profile->target_rva, profile->target_prologue_size,
                PF_R | PF_X)) {
        SetReason("target_unmapped");
        return false;
    }
    output->base = base;
    output->address = reinterpret_cast<uintptr_t>(base) +
            profile->target_rva;
    output->profile = profile;
    return true;
}

bool MatchesTargetPrologue(const DartTarget& target) {
    if (target.address == 0u || target.profile == nullptr) return false;
    return memcmp(reinterpret_cast<const void*>(target.address),
                  target.profile->target_prologue,
                  target.profile->target_prologue_size) == 0;
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
    const bool original_prologue = MatchesTargetPrologue(current);
    if (installed && same_target && !original_prologue) {
        SetReason("installed");
        return true;
    }
    if (!original_prologue) {
        SetReason("prologue_mismatch");
        LogWarn("_insertClearButtonOverlay prologue mismatch for %s at 0x%zx; not patching",
                current.profile == nullptr ? "unknown" : current.profile->id,
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
            current.profile == nullptr ? "unknown" : current.profile->id);
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
