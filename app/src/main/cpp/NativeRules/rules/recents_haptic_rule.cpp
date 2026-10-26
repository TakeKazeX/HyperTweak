// SPDX-License-Identifier: Apache-2.0
#include "recents_haptic_rule.h"
#include "recents_haptic_policy.h"
#include "dart_rule_support.h"
#include "clear_button_rule.h"
#include "lsposed_hook_backend.h"
#include "logging.h"
#include <string.h>
#include <pthread.h>
extern "C" {
__attribute__((visibility("hidden"))) uint32_t g_recents_haptic_enabled = 0;
__attribute__((visibility("hidden"))) void* g_recents_haptic_original = nullptr;
void HyperTweakRecentsScrollHook();
bool HyperTweakSendRecentsHaptic();
}
namespace hypertweak::native {
namespace {
pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
void* target = nullptr;
uint8_t* base = nullptr;
uint8_t original[16]{}, patch[16]{};
const char* reason = "disabled";
uint32_t generation = 0;
struct Layout { uint32_t start, end, update, metrics, number; };
Layout layout{};
uint32_t Word(const uint8_t* image, uintptr_t at) {
    uint32_t out; memcpy(&out, image + at, sizeof(out)); return out;
}
uint32_t Immediate(uint32_t insn) { return (insn >> 5u) & 0xffffu; }
bool AllocationCid(const dart::Image& image, uintptr_t call, uint32_t* cid) {
    uint32_t insn = 0; uintptr_t stub = 0;
    if (!dart::ReadInsn(image, call, &insn) || !dart::IsBl(insn) ||
        !dart::DecodeBlTarget(call, insn, &stub) ||
        !dart::Contains(image, stub, 12, dart::kFlagRead | dart::kFlagExec)) return false;
    const auto first = Word(image.base, stub), second = Word(image.base, stub + 4);
    if ((first & 0xffe0001fu) != 0xd2800002u ||
        (second & 0xffe0001fu) != 0xf2a00002u ||
        (Word(image.base, stub + 8) & 0xfc000000u) != 0x14000000u) return false;
    *cid = (Immediate(first) | (Immediate(second) << 16u)) >> 12u;
    return *cid > 0 && *cid < 65536;
}
bool Decode(const DartResolution& result, Layout* out) {
    dart::Image image{};
    if (!dart::ParseImage(result.base, &image)) return false;
    const auto scroll = result.Find("scroll") - reinterpret_cast<uintptr_t>(result.base);
    const auto update = result.Find("update") - reinterpret_cast<uintptr_t>(result.base);
    const auto metrics = result.Find("metrics") - reinterpret_cast<uintptr_t>(result.base);
    // The complete contracts validate the notification depth/metrics/drag fields,
    // boxed metrics and the StackList widget's unboxed itemExtent at +0x13.
    out->start = Immediate(Word(result.base, scroll + 0x18));
    out->end = Immediate(Word(result.base, scroll + 0x54));
    const uint32_t lo = Word(result.base, update + 0xc0), hi = Word(result.base, update + 0xc4);
    if ((lo & 0xffe0001fu) != 0xd2800003u || (hi & 0xffe0001fu) != 0xf2a00003u) return false;
    out->number = (Immediate(lo) | (Immediate(hi) << 16u)) >> 12u;
    return AllocationCid(image, update + 0xd4, &out->update) &&
           AllocationCid(image, metrics + 0xc8, &out->metrics) &&
           out->start != out->end && out->start != out->update && out->end != out->update;
}
uint32_t ClassId(uintptr_t tagged) { uint32_t tag; memcpy(&tag, reinterpret_cast<void*>(tagged - 1), 4); return tag >> 12u; }
uintptr_t Field(uintptr_t object, size_t offset, uintptr_t heap) {
    uint32_t value; memcpy(&value, reinterpret_cast<void*>(object + offset), 4);
    return (heap << 32u) + value;
}
bool Number(uintptr_t object, uintptr_t null, double* out) {
    if (object == null || (object & 1u) == 0u || ClassId(object) != layout.number) return false;
    memcpy(out, reinterpret_cast<void*>(object + 7), sizeof(*out)); return std::isfinite(*out);
}
}
void SetRecentsHapticEnabled(bool enabled) {
    const auto next = enabled ? 1u : 0u;
    if (__atomic_exchange_n(&g_recents_haptic_enabled, next, __ATOMIC_ACQ_REL) != next)
        __atomic_add_fetch(&generation, 1u, __ATOMIC_RELEASE);
}
bool RecentsHapticRequested() { return __atomic_load_n(&g_recents_haptic_enabled, __ATOMIC_ACQUIRE) != 0; }
const char* RecentsHapticRuleReason() { return __atomic_load_n(&reason, __ATOMIC_ACQUIRE); }
void ResetRecentsHapticAfterFork() { pthread_mutex_t fresh = PTHREAD_MUTEX_INITIALIZER; lock = fresh; __atomic_add_fetch(&generation, 1u, __ATOMIC_RELEASE); }
bool ApplyRecentsHapticRule(void* handle) {
    if (pthread_mutex_trylock(&lock) != 0) return false;
    struct Unlock { ~Unlock() { pthread_mutex_unlock(&lock); } } unlock;
    // Keep a passive observer after disable. Its entry tests the live atomic flag;
    // no callback/object access occurs, and no executing trampoline is freed.
    if (!RecentsHapticRequested()) { __atomic_store_n(&reason, "disabled", __ATOMIC_RELEASE); return true; }
    DartResolution resolution{};
    if (!ResolveDartSites(handle, dart::kRecentsHapticTarget, &resolution)) {
        __atomic_store_n(&reason, resolution.reason, __ATOMIC_RELEASE); return false;
    }
    void* next = reinterpret_cast<void*>(resolution.Find("scroll"));
    if (target == next && base == resolution.base && memcmp(next, patch, 16) == 0) {
        __atomic_store_n(&reason, "installed", __ATOMIC_RELEASE); return true;
    }
    if (!resolution.MatchVerify("scroll")) {
        __atomic_store_n(&reason, "foreign_patch_detected", __ATOMIC_RELEASE); return false;
    }
    Layout next_layout{};
    if (!Decode(resolution, &next_layout)) {
        __atomic_store_n(&reason, "layout_rejected", __ATOMIC_RELEASE); return false;
    }
    if (target != nullptr) {
        // Only repair a remapped original at the same image/address. A different
        // image or another owner's patch must never inherit our old trampoline.
        if (next != target || base != resolution.base || memcmp(next, original, 16) != 0 ||
            RemoveInlineHook(target) != kHookSuccess) {
            __atomic_store_n(&reason, "remap_rejected", __ATOMIC_RELEASE); return false;
        }
        target = nullptr;
    }
    layout = next_layout;
    memcpy(original, next, 16);
    if (InstallInlineHook(next, reinterpret_cast<void*>(HyperTweakRecentsScrollHook),
                          &g_recents_haptic_original) != kHookSuccess || g_recents_haptic_original == nullptr) {
        __atomic_store_n(&reason, "hook_failed", __ATOMIC_RELEASE); return false;
    }
    target = next; base = resolution.base; memcpy(patch, target, 16);
    __atomic_store_n(&reason, "installed", __ATOMIC_RELEASE);
    LogInfo("recents card haptic installed start=%u update=%u end=%u metrics=%u", layout.start, layout.update, layout.end, layout.metrics);
    return true;
}
extern "C" void HyperTweakObserveRecentsScroll(uintptr_t state, uintptr_t notification, uintptr_t heap, uintptr_t null) {
    thread_local RecentsHapticPolicy policy{};
    thread_local uint32_t seen_generation = 0;
    const auto current = __atomic_load_n(&generation, __ATOMIC_ACQUIRE);
    if (seen_generation != current) { policy.Reset(); seen_generation = current; }
    if (!RecentsHapticRequested()) { policy.Reset(); return; }
    const auto cid = ClassId(notification);
    const int kind = cid == layout.start ? 0 : cid == layout.update ? 1 : cid == layout.end ? 2 : -1;
    if (kind < 0) return;
    int64_t depth; memcpy(&depth, reinterpret_cast<void*>(notification + 7), sizeof(depth));
    if (depth != 0) return;
    const auto metrics = Field(notification, 0xf, heap);
    if (metrics == null || ClassId(metrics) != layout.metrics) { policy.Reset(); return; }
    const auto widget = Field(state, 0xb, heap);
    if (widget == null) { policy.Reset(); return; }
    double width, pixels, min, max;
    memcpy(&width, reinterpret_cast<void*>(widget + 0x13), sizeof(width));
    if (!Number(Field(metrics, 0xf, heap), null, &pixels) ||
        !Number(Field(metrics, 7, heap), null, &min) ||
        !Number(Field(metrics, 0xb, heap), null, &max)) { policy.Reset(); return; }
    const bool user_drag = kind != 2 && Field(notification, 0x17, heap) != null;
    double delta = NAN;
    // didUpdateScrollPositionBy's verified producer stores scrollDelta at +0x1b.
    // It proves a real user drag even if the entry animation already owned ScrollStart.
    if (kind == 1) (void)Number(Field(notification, 0x1b, heap), null, &delta);
    if (policy.Sample(state, kind, user_drag, pixels, width, min, max, delta)) {
        if (!HyperTweakSendRecentsHaptic()) LogWarn("recents card haptic signal unavailable");
    }
}
}
