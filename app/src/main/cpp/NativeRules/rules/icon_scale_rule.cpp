// SPDX-License-Identifier: Apache-2.0
#include "icon_scale_rule.h"
#include "desktop_grid_rule.h"
#include "icon_scale_policy.h"
#include "icon_scale_gate.h"
#include "icon_scale_scope.h"
#include "dart_rule_support.h"
#include "lsposed_hook_backend.h"
#include "logging.h"
#include <pthread.h>
#include <cstring>
#include <limits>
extern "C" void HyperTweakDesktopIconFrameHook();
namespace hypertweak::native {
// Primitive-only diagnostics; no Dart heap object or pool references survive a frame.
extern "C" {
__attribute__((visibility("hidden"))) uint32_t g_desktop_icon_stage=0;
__attribute__((visibility("hidden"))) uint64_t g_desktop_icon_frames=0, g_desktop_icon_dispatches=0;
__attribute__((visibility("hidden"))) volatile uint64_t g_desktop_icon_current_bits=0, g_desktop_icon_desired_bits=0;
}
namespace {
pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;
int requested=-1;
uint32_t ready=0;
uint32_t engaged=0;
const char* reason="disabled";
dart::IconScaleLayout layout{};
uint8_t* base=nullptr;
struct Hook { void* target=nullptr; uint8_t original[16]{},patch[16]{}; } hooks[2];
const char* names[]={"frame","provider_set","rx_get"};
uint8_t scope_code[412]{};bool scope_code_valid=false;
bool Object(uintptr_t value,uintptr_t null,uintptr_t sentinel) {
    return (value&1u) && value!=null && value!=sentinel;
}
uintptr_t Field(uintptr_t object,uint32_t field,uintptr_t heap) {
    uint32_t value=0; std::memcpy(&value,reinterpret_cast<void*>(object+field),4);
    return value&1u ? (heap<<32)+value : static_cast<uintptr_t>(static_cast<int64_t>(static_cast<int32_t>(value)));
}
uint32_t Cid(uintptr_t object) { uint32_t value=0; std::memcpy(&value,reinterpret_cast<void*>(object-1),4); return (value>>12)&0xfffffu; }
bool Number(uintptr_t rx,uintptr_t heap,uintptr_t null,uintptr_t sentinel,double* out) {
    if (!Object(rx,null,sentinel)) return false;
    auto value=Field(rx,layout.rx_value,heap);
    if (!Object(value,null,sentinel) || Cid(value)!=layout.double_cid) return false;
    std::memcpy(out,reinterpret_cast<void*>(value+7),8);
    return std::isfinite(*out);
}
}
void SetDesktopIconPercent(int percent) {
    if (ValidIconPercent(percent) && __atomic_exchange_n(&requested,percent,__ATOMIC_ACQ_REL)!=percent)
        __atomic_store_n(&g_desktop_icon_stage,0u,__ATOMIC_RELAXED);
}
int DesktopIconPercentRequested() { return __atomic_load_n(&requested,__ATOMIC_ACQUIRE); }
bool DesktopIconScaleNeedsPreparation() { return DesktopIconPercentRequested()!=-1 || __atomic_load_n(&engaged,__ATOMIC_ACQUIRE)!=0; }
const char* DesktopIconScaleReason() {
    const auto status=__atomic_load_n(&reason,__ATOMIC_ACQUIRE);
    if (std::strcmp(status,"installed")!=0) return status;
    static const char* stages[]={"waiting_frame","inactive_or_foreign_thread","statics_unavailable",
        "device_unavailable","grid_unavailable","icon_grid_mismatch","layout_unavailable",
        "columns_unavailable","numbers_unavailable","setter_changed","geometry_rejected",
        "setter_dispatched","scale_observed"};
    const auto stage=__atomic_load_n(&g_desktop_icon_stage,__ATOMIC_RELAXED);
    return stage<sizeof(stages)/sizeof(stages[0]) ? stages[stage] : "installed";
}
void ResetDesktopIconScaleAfterFork() {
    pthread_mutex_t fresh=PTHREAD_MUTEX_INITIALIZER; lock=fresh;
    __atomic_store_n(&ready,0u,__ATOMIC_RELEASE);
    ResetDesktopIconGateAfterFork();
    ResetDesktopIconScopeAfterFork();
    __atomic_store_n(&g_desktop_icon_stage,0u,__ATOMIC_RELAXED);
    __atomic_store_n(&g_desktop_icon_frames,uint64_t{0},__ATOMIC_RELAXED);
    __atomic_store_n(&g_desktop_icon_dispatches,uint64_t{0},__ATOMIC_RELAXED);
    __atomic_store_n(&g_desktop_icon_current_bits,uint64_t{0},__ATOMIC_RELAXED);
    __atomic_store_n(&g_desktop_icon_desired_bits,uint64_t{0},__ATOMIC_RELAXED);
}
bool ApplyDesktopIconScaleRule(void* handle) {
    if (pthread_mutex_trylock(&lock)!=0) return false;
    struct Unlock { ~Unlock(){pthread_mutex_unlock(&lock);} } unlock;
    if (!ApplyDesktopIconGate()) { __atomic_store_n(&reason,"native_icon_gate_pending",__ATOMIC_RELEASE); return false; }
    if (DesktopIconPercentRequested()==-1 && !DesktopGridNeedsPreparation() && !hooks[0].target && !hooks[1].target) {
        __atomic_store_n(&reason,"disabled",__ATOMIC_RELEASE); return true;
    }
    DartResolution resolution{};
    if (!ResolveDartSites(handle,dart::kIconScaleTarget,&resolution)) {
        __atomic_store_n(&ready,0u,__ATOMIC_RELEASE); __atomic_store_n(&reason,resolution.reason,__ATOMIC_RELEASE); return false;
    }
    bool intact=base==resolution.base;
    for(auto& hook:hooks) intact &= hook.target && std::memcmp(hook.target,hook.patch,16)==0;
    if (intact) {
        if (!ApplyDesktopIconScope(base,layout)) return false;
        __atomic_store_n(&ready,1u,__ATOMIC_RELEASE); return true;
    }
    __atomic_store_n(&ready,0u,__ATOMIC_RELEASE);
    dart::Image image{}; if(!dart::ParseImage(resolution.base,&image)) return false;
    // The complete frame pattern contains our two patches. Validate a private
    // copy with only owned windows restored; never restore or scan executable memory.
    uintptr_t sites[3]{};
    for(unsigned i=0;i<3;++i) sites[i]=resolution.Find(names[i])-reinterpret_cast<uintptr_t>(resolution.base);
    dart::IconScaleLayout next{};
    uint8_t frame_code[336]{};
    if(!dart::Contains(image,sites[0],sizeof(frame_code),dart::kFlagRead|dart::kFlagExec)) return false;
    std::memcpy(frame_code,resolution.base+sites[0],sizeof(frame_code));
    for(unsigned i=0;i<2;++i) if(hooks[i].target) {
        if(base!=resolution.base || hooks[i].target!=resolution.base+layout.frame_returns[i]) {
            __atomic_store_n(&reason,"icon_remap_rejected",__ATOMIC_RELEASE); return false;
        }
        if(std::memcmp(hooks[i].target,hooks[i].patch,16)!=0 && std::memcmp(hooks[i].target,hooks[i].original,16)!=0) {
            __atomic_store_n(&reason,"foreign_patch_detected",__ATOMIC_RELEASE); return false;
        }
        const auto offset=layout.frame_returns[i]-sites[0];
        if(offset>sizeof(frame_code)-16) return false;
        std::memcpy(frame_code+offset,hooks[i].original,16);
    }
    if (!dart::DecodeIconScaleLayout(image,sites,&next,frame_code,scope_code_valid && base==resolution.base ? scope_code : nullptr)) { __atomic_store_n(&reason,"icon_layout_rejected",__ATOMIC_RELEASE); return false; }
    for(unsigned i=0;i<2;++i) {
        auto address=resolution.base+next.frame_returns[i];
        if(hooks[i].target && (base!=resolution.base || hooks[i].target!=address || (std::memcmp(address,hooks[i].original,16)!=0 && std::memcmp(address,hooks[i].patch,16)!=0))) {
            __atomic_store_n(&reason,"foreign_patch_detected",__ATOMIC_RELEASE); return false;
        }
    }
    for(auto& hook:hooks) if(hook.target) {
        if(RemoveInlineHook(hook.target)!=kHookSuccess) { __atomic_store_n(&reason,"icon_unhook_failed",__ATOMIC_RELEASE); return false; }
        hook={};
    }
    if (!scope_code_valid || base!=resolution.base) {std::memcpy(scope_code,resolution.base+next.scope_begin,sizeof(scope_code));scope_code_valid=true;}
    layout=next; base=resolution.base;
    if (!ApplyDesktopIconScope(base,layout)) return false;
    __atomic_store_n(&engaged,1u,__ATOMIC_RELEASE);
    unsigned installed=0;
    for(;installed<2;++installed) {
        auto& hook=hooks[installed]; auto address=base+layout.frame_returns[installed];
        std::memcpy(hook.original,address,16);
        void* backup=nullptr;
        const int status=InstallInlineHook(address,reinterpret_cast<void*>(HyperTweakDesktopIconFrameHook),&backup);
        if(status==kHookSuccess) { hook.target=address; std::memcpy(hook.patch,address,16); }
        if(status!=kHookSuccess || !backup) break;
    }
    if(installed!=2) {
        for(auto& hook:hooks) if(hook.target && RemoveInlineHook(hook.target)==kHookSuccess) hook={};
        __atomic_store_n(&reason,"icon_hook_failed",__ATOMIC_RELEASE); return false;
    }
    __atomic_store_n(&ready,1u,__ATOMIC_RELEASE); __atomic_store_n(&reason,"installed",__ATOMIC_RELEASE);
    LogInfo("desktop icon size frame boundary installed"); return true;
}
// Runs on the original Dart UI thread, without allocation, managed calls, IO,
// blocking waits or retained heap references. The assembly performs the tail call.
extern "C" uintptr_t HyperTweakDesktopIconFrame(uintptr_t thread,uintptr_t heap,uintptr_t null,uintptr_t pool,uintptr_t* receiver,double* scale) {
    if(!receiver || !scale || !__atomic_load_n(&ready,__ATOMIC_ACQUIRE) || pthread_mutex_trylock(&lock)!=0) return 0;
    struct Unlock { ~Unlock(){pthread_mutex_unlock(&lock);} } unlock;
    __atomic_store_n(&g_desktop_icon_stage,1u,__ATOMIC_RELAXED);
    __atomic_add_fetch(&g_desktop_icon_frames,uint64_t{1},__ATOMIC_RELAXED);
    uintptr_t sentinel=0,statics=0,controller=0;
    std::memcpy(&sentinel,reinterpret_cast<void*>(pool+0x40),8);
    std::memcpy(&statics,reinterpret_cast<void*>(thread+0x78),8);
    if (!statics) return 0;
    std::memcpy(&controller,reinterpret_cast<void*>(statics+layout.controller_slot),8);
    if (!Object(controller,null,sentinel)) return 0;
    const auto active=Field(controller,layout.active_rx,heap);
    if (!Object(active,null,sentinel)) return 0;
    const auto grid=Field(active,layout.rx_value,heap);
    if (!Object(grid,null,sentinel)) return 0;
    const auto icon=Field(grid,layout.actual_grid_icon,heap);
    if (!Object(icon,null,sentinel)) return 0;
    const auto provider=Field(icon,layout.actual_provider,heap);
    double current=0,width=0,size=0,padding=0;
    if (!Object(provider,null,sentinel) || !Number(Field(provider,layout.current,heap),heap,null,sentinel,&current)) return 0;
    std::memcpy(&width,reinterpret_cast<void*>(grid+layout.actual_width),8);
    std::memcpy(&size,reinterpret_cast<void*>(icon+layout.actual_size),8);
    std::memcpy(&padding,reinterpret_cast<void*>(icon+layout.actual_padding),8);
    const double maximum=DesktopIconMaximum(current,width,size,padding);
    if (maximum<=0) return 0;
    SetDesktopIconMaximum(maximum);
    uint64_t bits=0;std::memcpy(&bits,&current,8);
    __atomic_store_n(&g_desktop_icon_current_bits,bits,__ATOMIC_RELAXED);
    std::memcpy(&bits,&maximum,8);
    __atomic_store_n(&g_desktop_icon_desired_bits,bits,__ATOMIC_RELAXED);
    __atomic_store_n(&g_desktop_icon_stage,12u,__ATOMIC_RELAXED);
    return 0;
}
extern "C" uintptr_t HyperTweakDesktopGridFrame(uintptr_t thread,uintptr_t heap,uintptr_t null,uintptr_t pool,DesktopGridDispatch* out) {
 if(!out||!__atomic_load_n(&ready,__ATOMIC_ACQUIRE)||pthread_mutex_trylock(&lock)!=0)return 0;
 struct Unlock{~Unlock(){pthread_mutex_unlock(&lock);}} unlock;
 uintptr_t statics=0,sentinel=0,controller=0;
 memcpy(&statics,reinterpret_cast<void*>(thread+0x78),8);memcpy(&sentinel,reinterpret_cast<void*>(pool+0x40),8);if(!statics)return 0;
 memcpy(&controller,reinterpret_cast<void*>(statics+layout.controller_slot),8);
 if(!Object(controller,null,sentinel))return 0;
 const auto active=Field(controller,layout.active_rx,heap);if(!Object(active,null,sentinel))return 0;
 const auto grid=Field(active,layout.rx_value,heap);if(!Object(grid,null,sentinel))return 0;
 return DesktopGridFrame(thread,heap,null,pool,grid,out);
}

}
