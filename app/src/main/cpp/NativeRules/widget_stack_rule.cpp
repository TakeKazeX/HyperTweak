// SPDX-License-Identifier: Apache-2.0
#include "widget_stack_rule.h"
#include "dart_rule_support.h"
#include "lsposed_hook_backend.h"
#include "logging.h"
#include <pthread.h>
#include <string.h>
extern "C" {
__attribute__((visibility("hidden"))) uint32_t hypertweak_widget_stack_requested=0;
__attribute__((visibility("hidden"))) uint32_t hypertweak_widget_stack_ready=0;
__attribute__((visibility("hidden"))) uintptr_t hypertweak_widget_stack_field=0;
__attribute__((visibility("hidden"))) uintptr_t hypertweak_widget_stack_drag_success=0;
__attribute__((visibility("hidden"))) uintptr_t hypertweak_widget_stack_drag_reject=0;
__attribute__((visibility("hidden"))) uintptr_t hypertweak_widget_stack_target_success=0;
__attribute__((visibility("hidden"))) uintptr_t hypertweak_widget_stack_target_reject=0;
void HyperTweakWidgetStackDragHook();
void HyperTweakWidgetStackTargetHook();
}
namespace hypertweak::native {
namespace {
pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;
const char* reason="disabled";
struct Site { void* target=nullptr; uint8_t original[16]{},patch[16]{}; };
Site sites[2]{};
uint8_t* base=nullptr;
void Reason(const char* value) { __atomic_store_n(&reason,value,__ATOMIC_RELEASE); }
// Roll back all owned sites. Failed removals remain recorded/passive and can
// never enable half a rule. No pointer is forgotten after a backend failure.
bool Rollback() {
    __atomic_store_n(&hypertweak_widget_stack_ready,0u,__ATOMIC_RELEASE);
    bool ok=true;
    for (auto& site:sites) {
        if (!site.target) continue;
        if ((memcmp(site.target,site.patch,16)!=0 && memcmp(site.target,site.original,16)!=0) ||
            RemoveInlineHook(site.target)!=kHookSuccess) { ok=false; continue; }
        site=Site{};
    }
    return ok;
}
}
void SetAndroidWidgetStackEnabled(bool value) {
    __atomic_store_n(&hypertweak_widget_stack_requested,value?1u:0u,__ATOMIC_RELEASE);
}
bool AndroidWidgetStackRequested() { return __atomic_load_n(&hypertweak_widget_stack_requested,__ATOMIC_ACQUIRE)!=0; }
const char* AndroidWidgetStackRuleReason() { return __atomic_load_n(&reason,__ATOMIC_ACQUIRE); }
void ResetAndroidWidgetStackAfterFork() {
    pthread_mutex_t fresh=PTHREAD_MUTEX_INITIALIZER; lock=fresh;
    __atomic_store_n(&hypertweak_widget_stack_ready,0u,__ATOMIC_RELEASE);
}
bool ApplyAndroidWidgetStackRule(void* handle) {
    if (pthread_mutex_trylock(&lock)!=0) return false;
    struct Unlock { ~Unlock() { pthread_mutex_unlock(&lock); } } unlock;
    // Passive shims read the live request at each native eligibility check.
    // Disable/reset needs no Dart call or freeing an executing trampoline; only
    // the ready bit is cleared so "ready" can never outlive "requested".
    if (!AndroidWidgetStackRequested()) {
        __atomic_store_n(&hypertweak_widget_stack_ready,0u,__ATOMIC_RELEASE);
        Reason("disabled"); return true;
    }
    DartResolution resolution{};
    if (!ResolveDartSites(handle,dart::kWidgetStackTarget,&resolution)) {
        Reason(resolution.reason); return false;
    }
    const uintptr_t addresses[]={resolution.Find("drag_gate"),resolution.Find("target_gate")};
    const uintptr_t serializer=resolution.Find("serializer");
    if (!addresses[0] || !addresses[1] || !serializer) { Reason("dart_site_missing"); return false; }
    bool intact=base==resolution.base;
    for (size_t i=0;i<2;++i) intact=intact && sites[i].target==reinterpret_cast<void*>(addresses[i]) &&
        memcmp(sites[i].target,sites[i].patch,16)==0;
    if (intact) {
        __atomic_store_n(&hypertweak_widget_stack_ready,1u,__ATOMIC_RELEASE);
        Reason("installed"); return true;
    }
    __atomic_store_n(&hypertweak_widget_stack_ready,0u,__ATOMIC_RELEASE);
    // A changed image/address is never allowed to reuse old continuations.
    for (size_t i=0;i<2;++i) if (sites[i].target &&
        (base!=resolution.base || sites[i].target!=reinterpret_cast<void*>(addresses[i]))) {
        Reason("image_change_rejected"); return false;
    }
    // Validate ownership of EVERY site before rolling back or installing any.
    for (size_t i=0;i<2;++i) {
        const auto& site=sites[i];
        if (site.target) {
            if (memcmp(site.target,site.original,16)!=0 && memcmp(site.target,site.patch,16)!=0) {
                Reason("foreign_patch_detected"); return false;
            }
        } else if (!resolution.MatchVerify(i==0?"drag_gate":"target_gate")) {
            Reason("foreign_patch_detected"); return false;
        }
    }
    if (!Rollback()) { Reason("unhook_failed"); return false; }
    dart::Image image{}; dart::WidgetStackLayout layout{};
    const auto bias=reinterpret_cast<uintptr_t>(resolution.base);
    if (!dart::ParseImage(resolution.base,&image) ||
        !dart::DecodeWidgetStackLayout(image,addresses[0]-bias,addresses[1]-bias,
            serializer-bias,&layout)) { Reason("layout_rejected"); return false; }
    __atomic_store_n(&hypertweak_widget_stack_field,static_cast<uintptr_t>(layout.field),__ATOMIC_RELEASE);
    __atomic_store_n(&hypertweak_widget_stack_drag_success,bias+layout.drag_success,__ATOMIC_RELEASE);
    __atomic_store_n(&hypertweak_widget_stack_target_success,bias+layout.target_success,__ATOMIC_RELEASE);
    __atomic_store_n(&hypertweak_widget_stack_drag_reject,addresses[0]+16,__ATOMIC_RELEASE);
    __atomic_store_n(&hypertweak_widget_stack_target_reject,addresses[1]+16,__ATOMIC_RELEASE);
    base=resolution.base;
    void* replacements[]={reinterpret_cast<void*>(HyperTweakWidgetStackDragHook),reinterpret_cast<void*>(HyperTweakWidgetStackTargetHook)};
    for (size_t i=0;i<2;++i) {
        auto& site=sites[i]; void* next=reinterpret_cast<void*>(addresses[i]);
        uint8_t original[16]; memcpy(original,next,16);
        void* backup=nullptr;
        const int status=InstallInlineHook(next,replacements[i],&backup);
        // Track even a backend error if it changed bytes; rollback must not
        // leave an unowned active patch or forget a failed null-backup install.
        if (status==kHookSuccess || memcmp(original,next,16)!=0) {
            site.target=next; memcpy(site.original,original,16); memcpy(site.patch,next,16);
        }
        if (status!=kHookSuccess || !backup) {
            const bool restored=Rollback(); Reason(restored?"hook_failed":"rollback_failed"); return false;
        }
    }
    __atomic_store_n(&hypertweak_widget_stack_ready,1u,__ATOMIC_RELEASE);
    Reason("installed");
    LogInfo("android widget stack installed: paired identity gates, native span/capacity unchanged");
    return true;
}
}
