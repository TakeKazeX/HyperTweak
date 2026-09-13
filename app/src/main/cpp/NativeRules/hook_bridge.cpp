// SPDX-License-Identifier: Apache-2.0
#include "hook_bridge.h"

#include "image.h"
#include "logging.h"
#include "page_guard.h"

#include <stdio.h>
#include <string.h>
#include <sys/mman.h>

namespace hypertweak::native {
namespace {

constexpr size_t kMaxPltSlots = 16u;
// A 16-byte trampoline at the last 16 bytes of a page spans two pages. Both
// must be protected, so the guard covers a full 32-byte window from the entry.
constexpr size_t kInlinePatchGuardSpan = 32u;

const NativeAPIEntries* g_entries = nullptr;

// Reads the effective protection of `address` from /proc/self/maps. Deriving it
// instead of assuming RWX preserves the original flags across the write.
int ReadProtection(uintptr_t address) {
    FILE* maps = fopen("/proc/self/maps", "re");
    if (maps == nullptr) return -1;
    char line[768];
    int result = -1;
    while (fgets(line, sizeof(line), maps) != nullptr) {
        unsigned long long begin = 0u;
        unsigned long long end = 0u;
        char permissions[5];
        if (sscanf(line, "%llx-%llx %4s", &begin, &end, permissions) != 3) {
            continue;
        }
        if (address < begin || address >= end) continue;
        result = (permissions[0] == 'r' ? PROT_READ : 0) |
                 (permissions[1] == 'w' ? PROT_WRITE : 0) |
                 (permissions[2] == 'x' ? PROT_EXEC : 0);
        break;
    }
    fclose(maps);
    return result;
}

bool WritePointer(void** slot, void* value) {
    const uintptr_t page = PageStart(reinterpret_cast<uintptr_t>(slot));
    const int protection = ReadProtection(reinterpret_cast<uintptr_t>(slot));
    if (protection < 0) return false;
    if (mprotect(reinterpret_cast<void*>(page), PageSize(),
                 protection | PROT_WRITE) != 0) {
        return false;
    }
    __atomic_store_n(slot, value, __ATOMIC_RELEASE);
    return mprotect(reinterpret_cast<void*>(page), PageSize(), protection) == 0;
}

bool CollectRelocationSlots(const Image& image, const DynamicView& view,
                            const ElfW(Rela)* relocations, size_t count,
                            const char* symbol, void*** slots,
                            size_t* slot_count) {
    if (relocations == nullptr) return true;
    for (size_t index = 0u; index < count; ++index) {
        const ElfW(Rela)& relocation = relocations[index];
        const uint32_t type = ELF64_R_TYPE(relocation.r_info);
        if (type != R_AARCH64_JUMP_SLOT && type != R_AARCH64_GLOB_DAT) continue;
        const uint32_t symbol_index = ELF64_R_SYM(relocation.r_info);
        if (!SymbolNameEquals(view, symbol_index, symbol)) continue;
        const uintptr_t address = RuntimeAddress(image, relocation.r_offset);
        if (address == 0u || !Contains(image, address, sizeof(void*))) return false;
        if (*slot_count >= kMaxPltSlots) return false;
        slots[(*slot_count)++] = reinterpret_cast<void**>(address);
    }
    return true;
}

}  // namespace

bool HookApiReady() {
    return __atomic_load_n(&g_entries, __ATOMIC_ACQUIRE) != nullptr;
}

bool InitializeHookApi(const NativeAPIEntries* entries) {
    if (entries == nullptr || entries->hookFunc == nullptr ||
        entries->unhookFunc == nullptr) {
        LogError("the LSPosed native API is incomplete; refusing to continue");
        return false;
    }
    __atomic_store_n(&g_entries, entries, __ATOMIC_RELEASE);
    LogInfo("LSPosed native API accepted version=%u", entries->version);
    return true;
}

bool PltHook(void* base, const char* symbol, void* replacement, void** original) {
    if (base == nullptr || symbol == nullptr || replacement == nullptr ||
        original == nullptr) {
        return false;
    }
    if (!EnsurePageGuard()) {
        LogWarn("page guard unavailable; refusing to hook %s", symbol);
        return false;
    }
    Image image{};
    DynamicView view{};
    if (!FindImage(nullptr, reinterpret_cast<uintptr_t>(base), &image) ||
        !BuildDynamicView(image, &view)) {
        LogWarn("no ELF view for the image at %p", base);
        return false;
    }
    void** slots[kMaxPltSlots] = {};
    size_t slot_count = 0u;
    if (!CollectRelocationSlots(image, view, view.plt_rela, view.plt_rela_count,
                                symbol, slots, &slot_count) ||
        !CollectRelocationSlots(image, view, view.dyn_rela, view.dyn_rela_count,
                                symbol, slots, &slot_count) ||
        slot_count == 0u) {
        LogWarn("%s has no matching relocation slot", symbol);
        return false;
    }
    void* expected = __atomic_load_n(slots[0], __ATOMIC_ACQUIRE);
    if (expected == nullptr || expected == replacement) return false;
    // Ambiguous call sites are rejected rather than partially rewritten.
    for (size_t index = 1u; index < slot_count; ++index) {
        if (__atomic_load_n(slots[index], __ATOMIC_ACQUIRE) != expected) {
            LogWarn("%s relocation slots disagree; refusing to patch", symbol);
            return false;
        }
    }
    for (size_t index = 0u; index < slot_count; ++index) {
        if (!ProtectPage(reinterpret_cast<uintptr_t>(slots[index]))) return false;
    }
    size_t written = 0u;
    for (; written < slot_count; ++written) {
        if (!WritePointer(slots[written], replacement)) break;
    }
    if (written != slot_count) {
        while (written > 0u) {
            --written;
            WritePointer(slots[written], expected);
        }
        LogError("failed to write the %s relocation slot; rolled back", symbol);
        return false;
    }
    *original = expected;
    return true;
}

bool InlineHook(void* target, void* replacement, void** original) {
    const NativeAPIEntries* entries = __atomic_load_n(&g_entries, __ATOMIC_ACQUIRE);
    if (entries == nullptr || target == nullptr || replacement == nullptr ||
        original == nullptr) {
        return false;
    }
    if (!EnsurePageGuard()) {
        LogWarn("page guard unavailable; refusing to patch %p", target);
        return false;
    }
    const uintptr_t begin = reinterpret_cast<uintptr_t>(target);
    if (ReadProtection(begin) < 0) {
        LogWarn("%p is not mapped in this process", target);
        return false;
    }
    const uintptr_t end = begin <= UINTPTR_MAX - (kInlinePatchGuardSpan - 1u)
                              ? begin + kInlinePatchGuardSpan - 1u
                              : begin;
    if (!ProtectPage(begin) || !ProtectPage(end)) return false;
    *original = nullptr;
    if (entries->hookFunc(target, replacement, original) != 0) return false;
    if (*original == nullptr) {
        entries->unhookFunc(target);
        return false;
    }
    return true;
}

bool InlineUnhook(void* target) {
    const NativeAPIEntries* entries = __atomic_load_n(&g_entries, __ATOMIC_ACQUIRE);
    if (entries == nullptr || target == nullptr) return false;
    return entries->unhookFunc(target) == 0;
}

}  // namespace hypertweak::native
