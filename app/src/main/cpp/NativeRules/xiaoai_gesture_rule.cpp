// SPDX-License-Identifier: Apache-2.0
#include "xiaoai_gesture_rule.h"
#include "dart_rule_support.h"
#include "clear_button_rule.h"
#include "lsposed_hook_backend.h"
#include "logging.h"
#include <string.h>
#include <pthread.h>
extern "C" {
__attribute__((visibility("hidden"))) uint32_t g_xiaoai_gesture_enabled = 0;
__attribute__((visibility("hidden"))) void* g_xiaoai_gesture_original = nullptr;
void HyperTweakXiaoaiGestureHook();

}
namespace hypertweak::native {
namespace {
pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
void* target = nullptr;
uint8_t* base = nullptr;
uint8_t original[16]{}, patch[16]{};
const char* reason = "disabled";
uint32_t generation = 0;
dart::XiaoaiGestureLayout layout{};
bool Decode(const DartResolution& result, dart::XiaoaiGestureLayout* out) {
    dart::Image image{};if(!dart::ParseImage(result.base,&image))return false;
    const auto base=reinterpret_cast<uintptr_t>(result.base);
    return dart::DecodeXiaoaiGestureLayout(image,result.Find("predicate")-base,result.Find("decoder")-base,out);
}
uint32_t ClassId(uintptr_t tagged) {uint32_t w;memcpy(&w,reinterpret_cast<void*>(tagged-1),4);return w>>12;}
uintptr_t Field(uintptr_t tagged,uint32_t offset,uintptr_t heap) {uint32_t w;memcpy(&w,reinterpret_cast<void*>(tagged+offset),4);return (heap<<32)+w;}
bool StringEquals(uintptr_t value,uintptr_t reference,const char* text) {
    if(!(value&1u)||!(reference&1u)||ClassId(value)!=ClassId(reference))return false;
    uint32_t length=0;memcpy(&length,reinterpret_cast<void*>(value+7),4);
    const auto size=strlen(text);return length==size*2 && memcmp(reinterpret_cast<void*>(value+15),text,size)==0;
}
}
void SetXiaoaiGestureOptionsEnabled(bool enabled) {
    const auto next = enabled ? 1u : 0u;
    if (__atomic_exchange_n(&g_xiaoai_gesture_enabled, next, __ATOMIC_ACQ_REL) != next)
        __atomic_add_fetch(&generation, 1u, __ATOMIC_RELEASE);
}
bool XiaoaiGestureOptionsRequested() { return __atomic_load_n(&g_xiaoai_gesture_enabled, __ATOMIC_ACQUIRE) != 0; }
const char* XiaoaiGestureOptionsReason() { return __atomic_load_n(&reason, __ATOMIC_ACQUIRE); }
void ResetXiaoaiGestureOptionsAfterFork() { pthread_mutex_t fresh = PTHREAD_MUTEX_INITIALIZER; lock = fresh; __atomic_add_fetch(&generation, 1u, __ATOMIC_RELEASE); }
bool ApplyXiaoaiGestureOptionsRule(void* handle) {
    if (pthread_mutex_trylock(&lock) != 0) return false;
    struct Unlock { ~Unlock() { pthread_mutex_unlock(&lock); } } unlock;
    // Keep a passive observer after disable. Its entry tests the live atomic flag;
    // no callback/object access occurs, and no executing trampoline is freed.
    if (!XiaoaiGestureOptionsRequested()) { __atomic_store_n(&reason, "disabled", __ATOMIC_RELEASE); return true; }
    DartResolution resolution{};
    if (!ResolveDartSites(handle, dart::kXiaoaiGestureOptionsTarget, &resolution)) {
        __atomic_store_n(&reason, resolution.reason, __ATOMIC_RELEASE); return false;
    }
    void* next = reinterpret_cast<void*>(resolution.Find("predicate"));
    if (target == next && base == resolution.base && memcmp(next, patch, 16) == 0) {
        __atomic_store_n(&reason, "installed", __ATOMIC_RELEASE); return true;
    }
    if (!resolution.MatchVerify("predicate")) {
        __atomic_store_n(&reason, "foreign_patch_detected", __ATOMIC_RELEASE); return false;
    }
    dart::XiaoaiGestureLayout next_layout{};
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
    if (InstallInlineHook(next, reinterpret_cast<void*>(HyperTweakXiaoaiGestureHook),
                          &g_xiaoai_gesture_original) != kHookSuccess || g_xiaoai_gesture_original == nullptr) {
        __atomic_store_n(&reason, "hook_failed", __ATOMIC_RELEASE); return false;
    }
    target = next; base = resolution.base; memcpy(patch, target, 16);
    __atomic_store_n(&reason, "installed", __ATOMIC_RELEASE);
    LogInfo("xiaoai gesture options installed cid=%u", layout.cid);
    return true;
}

extern "C" uint32_t HyperTweakAllowXiaoaiGesture(uintptr_t item,uintptr_t closure,uintptr_t heap,uintptr_t pool) {
    if(!XiaoaiGestureOptionsRequested()||!(item&1u)||!(closure&1u)||ClassId(item)!=layout.cid)return 0;
    uintptr_t long_press=0,double_click=0;
    memcpy(&long_press,reinterpret_cast<void*>(pool+layout.long_press_pool),8);
    memcpy(&double_click,reinterpret_cast<void*>(pool+layout.double_click_pool),8);
    // Verify the decoded pool roles before using their one-byte-string representation.
    if(!StringEquals(long_press,long_press,"NavLongPress")||!StringEquals(double_click,long_press,"NavDoubleClick"))return 0;
    const auto context=Field(closure,layout.context_field,heap);
    if(!(context&1u))return 0;
    const auto trigger=Field(context,layout.trigger_field,heap);
    const bool is_long=StringEquals(trigger,long_press,"NavLongPress");
    const bool is_double=StringEquals(trigger,long_press,"NavDoubleClick");
    if(!is_long&&!is_double)return 0;
    int64_t mask=0;memcpy(&mask,reinterpret_cast<void*>(item+layout.mask_field),8);
    const auto entity=Field(item,layout.entity_field,heap);
    return (is_long && mask==4 && StringEquals(entity,long_press,"VoiceAssistant")) ||
           (is_double && mask==2 && (StringEquals(entity,long_press,"VoiceAssistantScreenRecognizer") ||
                                    StringEquals(entity,long_press,"VoiceAssistantVoiceInput")));
}
}
