// SPDX-License-Identifier: Apache-2.0
#include "clear_button_rule.h"

#include "logging.h"
#include "lsposed_hook_backend.h"

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

// Verified against MiuiHome RELEASE-8.01.02.6264 (libapp.so, Dart 3.10.1).
// The build id pins the exact snapshot; the prologue pins the exact function.
// The image/base lookup follows the upstream payload: RTLD_NOLOAD acquires the
// already-loaded AOT image, and dladdr ties the target to that current mapping.
constexpr char kDartLibraryName[] = "libapp.so";
constexpr char kDartSnapshotInstructionsSymbol[] =
    "_kDartIsolateSnapshotInstructions";
constexpr char kDartSnapshotBuildIdSymbol[] = "_kDartSnapshotBuildId";
constexpr uint8_t kDartBuildId[16] = {
    0x4f, 0x1b, 0xda, 0xed, 0xdc, 0x7d, 0x86, 0x06,
    0xde, 0x7d, 0x3e, 0xbc, 0x69, 0x99, 0x4a, 0x81,
};
// The custom HyperOS loader maps the AOT ELF header at the start of its
// executable base.apk range, but does not publish a libapp.so link-map entry.
// This is the note location in the same verified AOT image.
constexpr uintptr_t kDartBuildIdOffset = 0x1d8u;
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
volatile uint32_t g_mapped_locator_logged = 0u;
const char* volatile g_reason = "not_attempted";
void* g_target = nullptr;
volatile uintptr_t g_target_address = 0u;

void SetReason(const char* reason) {
    __atomic_store_n(&g_reason, reason, __ATOMIC_RELEASE);
}

class ApplyLock {
  public:
    explicit ApplyLock(bool try_only) : acquired_(false) {
        if (try_only) {
            uint32_t expected = 0u;
            acquired_ = __atomic_compare_exchange_n(
                &g_applying, &expected, uint32_t{1}, false,
                __ATOMIC_ACQUIRE, __ATOMIC_RELAXED);
            return;
        }
        while (__atomic_exchange_n(&g_applying, uint32_t{1}, __ATOMIC_ACQUIRE) != 0u) {
        }
        acquired_ = true;
    }
    ~ApplyLock() {
        if (acquired_) __atomic_store_n(&g_applying, uint32_t{0}, __ATOMIC_RELEASE);
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
    uintptr_t address;
};

bool ResolveDartTargetViaMappedImage(DartTarget* output) {
    if (output == nullptr) return false;
    void* target = nullptr;
    if (!FindMappedImageTargetByBuildId(
                kDartBuildId, sizeof(kDartBuildId), kDartBuildIdOffset,
                kInsertClearButtonOverlayVa,
                sizeof(kInsertClearButtonOverlayPrologue), &target) ||
            target == nullptr) {
        return false;
    }
    output->address = reinterpret_cast<uintptr_t>(target);
    if (__atomic_exchange_n(&g_mapped_locator_logged, uint32_t{1},
                            __ATOMIC_ACQ_REL) == 0u) {
        LogInfo("resolved mapped libapp.so target at 0x%zx",
                static_cast<size_t>(output->address));
    }
    return true;
}

bool ResolveDartTarget(void* supplied_handle, DartTarget* output) {
    if (output == nullptr) return false;
    output->address = 0u;

    // The Flutter engine may own the AOT load rather than the launcher's own
    // loader boundary. RTLD_NOLOAD is intentional: never manufacture a second
    // AOT mapping while looking for the current image. A load callback passes
    // its existing handle so this path never re-enters the linker.
    const bool close_handle = supplied_handle == nullptr;
    void* handle = supplied_handle != nullptr
        ? supplied_handle
        : dlopen(kDartLibraryName, RTLD_NOW | RTLD_NOLOAD);
    if (handle == nullptr) {
        if (ResolveDartTargetViaMappedImage(output)) return true;
        SetReason("no_dart_image");
        return false;
    }
    void* instructions = dlsym(handle, kDartSnapshotInstructionsSymbol);
    void* build_id = dlsym(handle, kDartSnapshotBuildIdSymbol);
    Dl_info info{};
    const bool mapped = instructions != nullptr && build_id != nullptr &&
        dladdr(instructions, &info) != 0 && info.dli_fbase != nullptr &&
        IsDartLibraryPath(info.dli_fname);
    if (!mapped || memcmp(build_id, kDartBuildId, sizeof(kDartBuildId)) != 0) {
        if (mapped) {
            SetReason("build_id_mismatch");
        } else {
            if (ResolveDartTargetViaMappedImage(output)) {
                if (close_handle) dlclose(handle);
                return true;
            }
            SetReason("no_dart_image");
        }
        if (close_handle) dlclose(handle);
        return false;
    }

    const uintptr_t base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    if (kInsertClearButtonOverlayVa > UINTPTR_MAX - base) {
        if (close_handle) dlclose(handle);
        SetReason("target_overflow");
        return false;
    }
    const uintptr_t target = base + kInsertClearButtonOverlayVa;
    Dl_info target_info{};
    if (dladdr(reinterpret_cast<void*>(target), &target_info) == 0 ||
        target_info.dli_fbase != info.dli_fbase) {
        if (close_handle) dlclose(handle);
        if (ResolveDartTargetViaMappedImage(output)) return true;
        SetReason("target_unmapped");
        return false;
    }
    output->address = target;
    if (close_handle) dlclose(handle);
    return true;
}

bool MatchesTargetPrologue(uintptr_t target) {
    // ResolveDartTarget has already tied this address to the current libapp.so
    // mapping and exact build id. The 16-byte read is therefore bounded by the
    // same loaded AOT image that the upstream resolver validates before use.
    uint8_t actual[sizeof(kInsertClearButtonOverlayPrologue)];
    memcpy(actual, reinterpret_cast<const void*>(target), sizeof(actual));
    return memcmp(actual, kInsertClearButtonOverlayPrologue,
                  sizeof(actual)) == 0;
}

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

bool ApplyClearButtonRule(void* dart_handle, bool try_lock) {
    ApplyLock lock(try_lock);
    // A library-load callback cannot wait for the config worker: the worker may
    // be inside RTLD_NOLOAD while this callback owns the linker load lock.
    if (!lock.acquired()) return false;
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
        SetReason("removed");
        return true;
    }

    DartTarget current{};
    if (!ResolveDartTarget(dart_handle, &current)) return false;
    const bool same_target = installed &&
        __atomic_load_n(&g_target_address, __ATOMIC_ACQUIRE) == current.address;
    const bool original_prologue = MatchesTargetPrologue(current.address);

    // A valid inline hook replaces the prologue. If the current target is still
    // patched, leave it alone. If the original prologue has returned, the AOT
    // text was remapped (or MADV_DONTNEED won a race) and must be retired and
    // rebound just as the upstream Dart remap repair does.
    if (installed && same_target && !original_prologue) {
        SetReason("installed");
        return true;
    }
    if (!original_prologue) {
        SetReason("prologue_mismatch");
        LogWarn("_insertClearButtonOverlay prologue mismatch at 0x%zx; not patching",
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
    if (InstallInlineHook(reinterpret_cast<void*>(current.address),
                          reinterpret_cast<void*>(&HyperTweakRecentsClearButtonInsertHook),
                          &original) != kHookSuccess) {
        SetReason("hook_failed");
        return false;
    }
    __atomic_store_n(&g_target_address, current.address, __ATOMIC_RELEASE);
    g_target = reinterpret_cast<void*>(current.address);
    __atomic_store_n(&g_installed, uint32_t{1}, __ATOMIC_RELEASE);
    SetReason("installed");
    LogInfo("clear button rule installed; recents overlay insertion is suppressed");
    return true;
}

void OnClearButtonLibraryLoaded(const char* name, void* handle) {
    if (name == nullptr || !IsDartLibraryPath(name) ||
        !ClearButtonHiddenRequested()) {
        return;
    }
    // The upstream payload resolves AOT hooks at the library-load boundary and
    // also retries through its remap path. Reuse that boundary for this rule;
    // ConfigPollThread remains the fallback for remaps without a callback.
    // Pass the callback handle through and never call dlopen or spin here.
    ApplyClearButtonRule(handle, true);
}

}  // namespace hypertweak::native
