// SPDX-License-Identifier: Apache-2.0
#include "clear_button_rule.h"

#include "hook_bridge.h"
#include "image.h"
#include "logging.h"
#include "page_guard.h"

#include <string.h>

// Defined here so the assembly replacement can reach it with adrp/add.
extern "C" {
volatile uint32_t hypertweak_clear_button_hits = 0u;
}

// Implemented in clear_button_hook.S.
extern "C" void HyperTweakRecentsClearButtonInsertHook();

namespace hypertweak::native {
namespace {

// Verified against MiuiHome RELEASE-8.01.02.6264 (libapp.so, Dart 3.10.1).
// The build id pins the exact snapshot; the prologue pins the exact function.
constexpr uint8_t kDartBuildId[16] = {
    0x4f, 0x1b, 0xda, 0xed, 0xdc, 0x7d, 0x86, 0x06,
    0xde, 0x7d, 0x3e, 0xbc, 0x69, 0x99, 0x4a, 0x81,
};
constexpr uintptr_t kDartBuildIdVa = 0x1d8u;
// RecentsPageState._insertClearButtonOverlay, VA in the snapshot's address
// space (Ghidra shows this as 0x01354b74: subtract its 0x100000 PIE image base).
constexpr uintptr_t kInsertClearButtonOverlayVa = 0x01354b74u;
constexpr uint8_t kInsertClearButtonOverlayPrologue[16] = {
    0xfd, 0x79, 0xbf, 0xa9, 0xfd, 0x03, 0x0f, 0xaa,
    0xef, 0xe1, 0x00, 0xd1, 0xa1, 0x83, 0x1f, 0xf8,
};

volatile uint32_t g_hidden = 0u;
volatile uint32_t g_installed = 0u;
volatile uint32_t g_applying = 0u;
volatile uint32_t g_target_ready = 0u;
const char* volatile g_reason = "not_attempted";
void* g_target = nullptr;
volatile uintptr_t g_target_address = 0u;
// Latched once the snapshot is present but does not match the reviewed build.
// Retrying cannot change that, and each retry would re-scan the mapping table.
volatile uint32_t g_unsupported = 0u;
void* g_original = nullptr;

void SetReason(const char* reason) {
    __atomic_store_n(&g_reason, reason, __ATOMIC_RELEASE);
}

// Serializes apply attempts so the installer thread and the JNI setter cannot
// patch the same entry twice.
class ApplyLock {
  public:
    ApplyLock() {
        while (__atomic_exchange_n(&g_applying, uint32_t{1}, __ATOMIC_ACQUIRE) != 0u) {
        }
    }
    ~ApplyLock() { __atomic_store_n(&g_applying, uint32_t{0}, __ATOMIC_RELEASE); }
};

}  // namespace

void SetClearButtonHidden(bool hidden) {
    __atomic_store_n(&g_hidden, hidden ? uint32_t{1} : uint32_t{0}, __ATOMIC_RELEASE);
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

bool ApplyClearButtonRule() {
    // A build the prologue check rejected will not start matching on a retry.
    if (__atomic_load_n(&g_unsupported, __ATOMIC_ACQUIRE) != 0u) return true;
    const bool hidden = ClearButtonHiddenRequested();
    const bool installed = __atomic_load_n(&g_installed, __ATOMIC_ACQUIRE) != 0u;
    if (hidden == installed) return true;

    ApplyLock lock;
    // Re-read under the lock: another thread may have finished the transition.
    const bool now_hidden = ClearButtonHiddenRequested();
    const bool now_installed = __atomic_load_n(&g_installed, __ATOMIC_ACQUIRE) != 0u;
    if (now_hidden == now_installed) return true;

    if (!now_hidden) {
        if (!InlineUnhook(g_target)) {
            SetReason("unhook_failed");
            return false;
        }
        __atomic_store_n(&g_installed, uint32_t{0}, __ATOMIC_RELEASE);
        SetReason("removed");
        return true;
    }

    Image image{};
    // The Dart snapshot is mapped out of the launcher's APK, so it has no name of
    // its own in /proc/self/maps and the linker may not list it in this
    // namespace. Its build-id note is the one reliable identifier.
    if (!FindImageByBuildId(kDartBuildId, sizeof(kDartBuildId), kDartBuildIdVa, &image)) {
        SetReason("no_dart_image");
        return false;
    }
    const uintptr_t target = image.base + kInsertClearButtonOverlayVa;
    if (!MatchesBytes(image, target, kInsertClearButtonOverlayPrologue,
                      sizeof(kInsertClearButtonOverlayPrologue))) {
        SetReason("prologue_mismatch");
        LogWarn("_insertClearButtonOverlay prologue mismatch at 0x%zx; not patching",
                static_cast<size_t>(target));
        __atomic_store_n(&g_unsupported, uint32_t{1}, __ATOMIC_RELEASE);
        return false;
    }
    if (!__atomic_load_n(&g_target_ready, __ATOMIC_ACQUIRE)) {
        LogInfo("clear button target resolved at 0x%zx", static_cast<size_t>(target));
        __atomic_store_n(&g_target_ready, uint32_t{1}, __ATOMIC_RELEASE);
    }
    void* original = nullptr;
    if (!InlineHook(reinterpret_cast<void*>(target),
                    reinterpret_cast<void*>(&HyperTweakRecentsClearButtonInsertHook),
                    &original)) {
        SetReason("hook_failed");
        return false;
    }
    __atomic_store_n(&g_target_address, target, __ATOMIC_RELEASE);
    g_target = reinterpret_cast<void*>(target);
    g_original = original;
    __atomic_store_n(&g_installed, uint32_t{1}, __ATOMIC_RELEASE);
    SetReason("installed");
    LogInfo("clear button rule installed; recents overlay insertion is suppressed");
    return true;
}

}  // namespace hypertweak::native
