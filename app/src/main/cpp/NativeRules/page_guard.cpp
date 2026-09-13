// SPDX-License-Identifier: Apache-2.0
#include "page_guard.h"

#include "image.h"
#include "logging.h"

#include <errno.h>
#include <lsplt.hpp>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>

namespace hypertweak::native {
namespace {

// The runtime that discards launcher code pages. Only the first path that
// exists and is mapped is used.
constexpr const char* kRuntimeCandidates[] = {
    "/system_ext/lib64/libhyper_os_flutter.so",
    "/system/lib64/libhyper_os_flutter.so",
};

constexpr size_t kMaxProtectedPages = 256u;

enum GuardState : uint32_t {
    kIdle = 0,
    kInstalling = 1,
    kReady = 2,
    // The runtime is not loaded yet. Retrying later is safe because nothing has
    // been registered with LSPlt.
    kNotMappedYet = 3,
    // LSPlt already saw a partial registration; retrying could duplicate it.
    kFailed = 4,
};

using MadviseFn = int (*)(void*, size_t, int);

volatile uint32_t g_guard_state = kIdle;
volatile uint32_t g_protected_page_lock = 0u;
uintptr_t g_protected_pages[kMaxProtectedPages];
volatile uint32_t g_protected_page_count = 0u;
MadviseFn g_original_madvise = madvise;

void LockProtectedPages() {
    while (__atomic_exchange_n(&g_protected_page_lock, uint32_t{1},
                               __ATOMIC_ACQUIRE) != 0u) {
    }
}

void UnlockProtectedPages() {
    __atomic_store_n(&g_protected_page_lock, uint32_t{0}, __ATOMIC_RELEASE);
}

int GuardedMadvise(void* address, size_t length, int advice) {
    const MadviseFn original = __atomic_load_n(&g_original_madvise, __ATOMIC_ACQUIRE);
    if (original == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    if (advice != MADV_DONTNEED || length == 0u) {
        return original(address, length, advice);
    }
    const uintptr_t begin = reinterpret_cast<uintptr_t>(address);
    if (begin % PageSize() != 0u || length > UINTPTR_MAX - begin) {
        return original(address, length, advice);
    }
    const uintptr_t end = begin + length;
    uintptr_t cursor = begin;
    bool preserved_any = false;
    while (cursor < end) {
        uintptr_t next_page = end;
        const uint32_t count =
            __atomic_load_n(&g_protected_page_count, __ATOMIC_ACQUIRE);
        for (uint32_t index = 0u; index < count; ++index) {
            const uintptr_t page = g_protected_pages[index];
            if (page >= cursor && page < end && page < next_page) {
                next_page = page;
            }
        }
        if (next_page == end) break;
        if (next_page > cursor &&
            original(reinterpret_cast<void*>(cursor), next_page - cursor, advice) != 0) {
            return -1;
        }
        if (next_page > UINTPTR_MAX - PageSize()) {
            errno = EINVAL;
            return -1;
        }
        cursor = next_page + PageSize();
        if (cursor > end) cursor = end;
        preserved_any = true;
    }
    if (!preserved_any) return original(address, length, advice);
    if (cursor < end &&
        original(reinterpret_cast<void*>(cursor), end - cursor, advice) != 0) {
        return -1;
    }
    LogInfo("preserved hook page in MADV_DONTNEED range %p-%p", address,
            reinterpret_cast<void*>(end));
    return 0;
}

enum class InstallOutcome {
    kInstalled,
    // The runtime exists on this build but has not been loaded yet. Nothing has
    // been handed to LSPlt, so a later attempt is safe.
    kNotMapped,
    // LSPlt has seen a partial registration; retrying could duplicate it.
    kTerminal,
};

InstallOutcome InstallGuardForPath(const char* path) {
    struct stat library_stat {};
    if (stat(path, &library_stat) != 0) return InstallOutcome::kNotMapped;
    // LSPlt resolves the symbol against the process's own maps, so it already
    // answers "is this library loaded with an madvise import to patch?".
    // Requiring a full ELF view first would be a stricter test than the guard
    // needs and would reject a library whose segments are not all resident.
    //
    // A failed registration records nothing, so it stays retryable; only a
    // failed commit leaves LSPlt state behind.
    if (!lsplt::RegisterHook(library_stat.st_dev, library_stat.st_ino, "madvise",
                             reinterpret_cast<void*>(GuardedMadvise),
                             reinterpret_cast<void**>(&g_original_madvise))) {
        return InstallOutcome::kNotMapped;
    }
    if (!lsplt::CommitHook() || g_original_madvise == nullptr) {
        return InstallOutcome::kTerminal;
    }
    LogInfo("madvise guard installed for %s", path);
    return InstallOutcome::kInstalled;
}

}  // namespace

bool PageGuardReady() {
    return __atomic_load_n(&g_guard_state, __ATOMIC_ACQUIRE) == kReady;
}

bool PageGuardFailed() {
    return __atomic_load_n(&g_guard_state, __ATOMIC_ACQUIRE) == kFailed;
}

bool ProtectPage(uintptr_t address) {
    const uintptr_t page = PageStart(address);
    LockProtectedPages();
    const uint32_t count = __atomic_load_n(&g_protected_page_count, __ATOMIC_RELAXED);
    for (uint32_t index = 0u; index < count; ++index) {
        if (g_protected_pages[index] == page) {
            UnlockProtectedPages();
            return true;
        }
    }
    if (count >= kMaxProtectedPages) {
        UnlockProtectedPages();
        LogError("protected page table is full (%u pages)", count);
        return false;
    }
    g_protected_pages[count] = page;
    __atomic_store_n(&g_protected_page_count, count + 1u, __ATOMIC_RELEASE);
    UnlockProtectedPages();
    return true;
}

bool EnsurePageGuard() {
    const uint32_t state = __atomic_load_n(&g_guard_state, __ATOMIC_ACQUIRE);
    if (state == kReady) return true;
    if (state == kInstalling || state == kFailed) return false;
    uint32_t expected = state;
    if (!__atomic_compare_exchange_n(&g_guard_state, &expected, uint32_t{kInstalling},
                                     false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        return expected == kReady;
    }
    bool saw_candidate = false;
    bool terminal_failure = false;
    for (const char* path : kRuntimeCandidates) {
        struct stat library_stat {};
        if (stat(path, &library_stat) != 0) continue;
        saw_candidate = true;
        switch (InstallGuardForPath(path)) {
            case InstallOutcome::kInstalled:
                __atomic_store_n(&g_guard_state, uint32_t{kReady}, __ATOMIC_RELEASE);
                return true;
            case InstallOutcome::kNotMapped:
                continue;
            case InstallOutcome::kTerminal:
                terminal_failure = true;
                LogError("failed to install the madvise guard for %s", path);
                continue;
        }
    }
    if (terminal_failure || !saw_candidate) {
        // Either LSPlt already holds a partial registration, or no known
        // runtime exists on this build. Both are terminal for the process: the
        // payload must not patch pages it cannot protect.
        __atomic_store_n(&g_guard_state, uint32_t{kFailed}, __ATOMIC_RELEASE);
        return false;
    }
    __atomic_store_n(&g_guard_state, uint32_t{kNotMappedYet}, __ATOMIC_RELEASE);
    LogWarn("the Flutter runtime is not mapped yet; page guard deferred");
    return false;
}

}  // namespace hypertweak::native
