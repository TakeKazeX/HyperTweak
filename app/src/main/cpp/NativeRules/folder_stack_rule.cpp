// SPDX-License-Identifier: Apache-2.0
#include "folder_stack_rule.h"
#include "dart_rule_support.h"
#include "clear_button_rule.h"
#include "lsposed_hook_backend.h"
#include "logging.h"
#include <cmath>
#include <cstring>
#include <pthread.h>
extern "C" {
__attribute__((visibility("hidden"))) uint32_t g_folder_stack_enabled = 0;
void HyperTweakFolderStackHook();
void HyperTweakFolderStackCountHook();
void HyperTweakFolderStackOrderHook();
void HyperTweakFolderStackFrameHook();
void HyperTweakFolderStackIdentityHook();
void HyperTweakFolderStackRefreshReturnHook();
__attribute__((visibility("hidden"))) uint64_t g_folder_stack_refresh_inflight = 0;
__attribute__((visibility("hidden"))) uintptr_t g_folder_stack_refresh_owner = 0;
__attribute__((visibility("hidden"))) uintptr_t g_folder_stack_identity_continue = 0;
__attribute__((visibility("hidden"))) uint32_t g_folder_stack_layout_id_pool = 0;
__attribute__((visibility("hidden"))) uint32_t g_folder_stack_preview_id_pool = 0;
__attribute__((visibility("hidden"))) uint32_t g_folder_stack_typeargs_pool = 0;
__attribute__((visibility("hidden"))) uint64_t g_folder_stack_refresh_layouts = 0;
// Epochs are primitive native state, never Dart roots. Initial pending generation
// also repairs existing hosts on first activation without old feature state.
__attribute__((visibility("hidden"))) uint64_t g_folder_stack_refresh_epoch = 1;
__attribute__((visibility("hidden"))) uint64_t g_folder_stack_refresh_applied = 0;
__attribute__((visibility("hidden"))) uintptr_t g_folder_stack_refresh_target = 0;
__attribute__((visibility("hidden"))) uint32_t g_folder_stack_model_slot = 0;
__attribute__((visibility("hidden"))) uint32_t g_folder_stack_registry_slot = 0;
__attribute__((visibility("hidden"))) uintptr_t g_folder_stack_count_continue = 0;
__attribute__((visibility("hidden"))) uintptr_t g_folder_stack_order_continue = 0;
__attribute__((visibility("hidden"))) uint32_t g_folder_stack_ready = 0;
}
namespace hypertweak::native {
namespace {
pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
struct Hook { void* target=nullptr; uint8_t original[16]{}, patch[16]{}; };
Hook hooks[6]{};
uint8_t* base = nullptr;
const char* reason = "disabled";
using Layout = dart::FolderStackLayout;
Layout layout{};
uintptr_t getter_address = 0;
bool Decode(const DartResolution& result, Layout* out) {
    dart::Image image{};
    if (!dart::ParseImage(result.base, &image)) return false;
    const auto origin = reinterpret_cast<uintptr_t>(result.base);
    const uint8_t* identity=nullptr;
    if(base==result.base && hooks[4].target==result.base+layout.refresh_id &&
       memcmp(hooks[4].target,hooks[4].patch,16)==0) identity=hooks[4].original;
    return dart::DecodeFolderStackLayout(image, result.Find("getter")-origin,
        result.Find("producer")-origin, result.Find("factory")-origin,
        result.Find("builder")-origin, result.Find("children")-origin, out) &&
        dart::DecodeFolderStackRefresh(image,result.Find("frame")-origin,result.Find("refresh")-origin,result.Find("layout_owner")-origin,out,identity);
}

uint32_t ClassId(uintptr_t tagged) {
    uint32_t tag; memcpy(&tag, reinterpret_cast<void*>(tagged - 1), 4);
    return (tag >> 12u) & 0xfffffu;
}
template<class T> T Read(uintptr_t object, size_t offset) {
    T value; memcpy(&value, reinterpret_cast<void*>(object + offset), sizeof(value)); return value;
}
uintptr_t Field(uintptr_t object, size_t offset, uintptr_t heap) {
    const uint32_t value = Read<uint32_t>(object, offset);
    // The list/object fields in these contracts are heap references, never SMIs.
    return (value & 1u) != 0u ? (heap << 32u) + value : 0u;
}
// Harmony reference: three upright cards, ~80% of the third slot, with each
// rear layer exposed by ~10% of the slot along both axes. No artificial rotation.
constexpr double kStackCard = 0.80;
constexpr double kStackStep = 0.10;
static_assert(kStackCard + 2.0*kStackStep <= 1.0, "three cards must fit the native slot");
bool StackActive() { return FolderStackRequested() && __atomic_load_n(&g_folder_stack_ready,__ATOMIC_ACQUIRE)!=0; }
struct CallbackLock {
    bool acquired=pthread_mutex_trylock(&lock)==0;
    ~CallbackLock() { if (acquired) pthread_mutex_unlock(&lock); }
};
struct Rect { double w, h, x, y; };
bool Same(const Rect& a, const Rect& b) {
    return std::fabs(a.w-b.w)<1e-6 && std::fabs(a.h-b.h)<1e-6 &&
           std::fabs(a.x-b.x)<1e-6 && std::fabs(a.y-b.y)<1e-6;
}
bool Valid(const Rect& r) {
    return std::isfinite(r.w) && std::isfinite(r.h) && std::isfinite(r.x) && std::isfinite(r.y) &&
           r.w>0 && r.w<4096 && r.h>0 && r.h<4096 && std::fabs(r.x)<16384 && std::fabs(r.y)<16384;
}
}
void SetFolderStackEnabled(bool enabled) {
    const uint32_t next=enabled ? 1u : 0u;
    if (__atomic_exchange_n(&g_folder_stack_enabled,next,__ATOMIC_ACQ_REL)!=next)
        __atomic_add_fetch(&g_folder_stack_refresh_epoch,uint64_t{1},__ATOMIC_RELEASE);
}
bool FolderStackRequested() { return __atomic_load_n(&g_folder_stack_enabled, __ATOMIC_ACQUIRE) != 0; }
const char* FolderStackRuleReason() { return __atomic_load_n(&reason, __ATOMIC_ACQUIRE); }
void ResetFolderStackAfterFork() {
    pthread_mutex_t fresh = PTHREAD_MUTEX_INITIALIZER; lock = fresh;
    __atomic_add_fetch(&g_folder_stack_refresh_epoch,uint64_t{1},__ATOMIC_RELEASE);
    __atomic_store_n(&g_folder_stack_refresh_inflight,uint64_t{0},__ATOMIC_RELEASE);
    __atomic_store_n(&g_folder_stack_refresh_owner,uintptr_t{0},__ATOMIC_RELEASE);
    __atomic_store_n(&g_folder_stack_ready,0u,__ATOMIC_RELEASE);
}
bool ApplyFolderStackRule(void* handle) {
    if (pthread_mutex_trylock(&lock)!=0) return false;
    struct Unlock { ~Unlock() { pthread_mutex_unlock(&lock); } } unlock;
    if (!FolderStackRequested()) { __atomic_store_n(&reason,"disabled",__ATOMIC_RELEASE); return true; }
    DartResolution resolution{};
    if (!ResolveDartSites(handle,dart::kFolderStackTarget,&resolution)) {
        __atomic_store_n(&g_folder_stack_ready,0u,__ATOMIC_RELEASE);
        __atomic_store_n(&reason,resolution.reason,__ATOMIC_RELEASE); return false;
    }
    bool intact=base==resolution.base && getter_address==resolution.Find("getter");
    for (const auto& hook:hooks) intact &= hook.target && memcmp(hook.target,hook.patch,16)==0;
    if (intact) { __atomic_store_n(&g_folder_stack_ready,1u,__ATOMIC_RELEASE); __atomic_store_n(&reason,"installed",__ATOMIC_RELEASE); return true; }
    Layout next_layout{};
    if (!Decode(resolution,&next_layout)) {
        __atomic_store_n(&g_folder_stack_ready,0u,__ATOMIC_RELEASE);
        __atomic_store_n(&reason,"layout_rejected",__ATOMIC_RELEASE); return false;
    }
    dart::Image image{}; if(!dart::ParseImage(resolution.base,&image)) return false;
    void* addresses[]={resolution.base+next_layout.patch,resolution.base+next_layout.children_count,resolution.base+next_layout.children_loop,resolution.base+next_layout.frame_return,resolution.base+next_layout.refresh_id,resolution.base+next_layout.refresh_return};
    void* replacements[]={reinterpret_cast<void*>(HyperTweakFolderStackHook),reinterpret_cast<void*>(HyperTweakFolderStackCountHook),reinterpret_cast<void*>(HyperTweakFolderStackOrderHook),reinterpret_cast<void*>(HyperTweakFolderStackFrameHook),reinterpret_cast<void*>(HyperTweakFolderStackIdentityHook),reinterpret_cast<void*>(HyperTweakFolderStackRefreshReturnHook)};
    for (unsigned i=0;i<6;++i) {
        if (hooks[i].target && (base!=resolution.base || hooks[i].target!=addresses[i])) {
            __atomic_store_n(&g_folder_stack_ready,0u,__ATOMIC_RELEASE);
            __atomic_store_n(&reason,"remap_rejected",__ATOMIC_RELEASE); return false;
        }
        const bool ours=hooks[i].target && memcmp(addresses[i],hooks[i].patch,16)==0;
        const bool stock=i==0 ? dart::VerifyFolderStackReturn(reinterpret_cast<uintptr_t>(addresses[i])) :
            i<3 ? dart::VerifyFolderStackChildren(reinterpret_cast<uintptr_t>(addresses[i]),i==2) :
            i!=4 ? dart::VerifyFolderStackFrame(reinterpret_cast<uintptr_t>(addresses[i])) :
            dart::VerifyFolderStackIdentity(image,next_layout.refresh_id,next_layout);
        if (!ours && !stock) {
            __atomic_store_n(&g_folder_stack_ready,0u,__ATOMIC_RELEASE);
            __atomic_store_n(&reason,"foreign_patch_detected",__ATOMIC_RELEASE); return false;
        }
    }
    __atomic_store_n(&g_folder_stack_ready,0u,__ATOMIC_RELEASE);
    for (auto& hook:hooks) if (hook.target) {
        if (RemoveInlineHook(hook.target)!=kHookSuccess) { __atomic_store_n(&reason,"remap_rejected",__ATOMIC_RELEASE); return false; }
        hook={};
    }
    layout=next_layout;
    getter_address=resolution.Find("getter"); base=resolution.base;
    __atomic_store_n(&g_folder_stack_count_continue,reinterpret_cast<uintptr_t>(addresses[1])+16,__ATOMIC_RELEASE);
    __atomic_store_n(&g_folder_stack_order_continue,reinterpret_cast<uintptr_t>(addresses[2])+16,__ATOMIC_RELEASE);
    __atomic_store_n(&g_folder_stack_refresh_target,reinterpret_cast<uintptr_t>(resolution.base)+next_layout.refresh,__ATOMIC_RELEASE);
    __atomic_store_n(&g_folder_stack_model_slot,next_layout.model_slot,__ATOMIC_RELEASE);
    __atomic_store_n(&g_folder_stack_registry_slot,next_layout.registry_slot,__ATOMIC_RELEASE);
    __atomic_store_n(&g_folder_stack_identity_continue,reinterpret_cast<uintptr_t>(resolution.base)+next_layout.refresh_id+16,__ATOMIC_RELEASE);
    __atomic_store_n(&g_folder_stack_layout_id_pool,next_layout.layout_id_pool,__ATOMIC_RELEASE);
    __atomic_store_n(&g_folder_stack_preview_id_pool,next_layout.preview_id_pool,__ATOMIC_RELEASE);
    __atomic_store_n(&g_folder_stack_typeargs_pool,next_layout.typeargs_pool,__ATOMIC_RELEASE);
    for (unsigned i=0;i<6;++i) {
        Hook next{}; memcpy(next.original,addresses[i],16);
        void* unused=nullptr;
        const int status=InstallInlineHook(addresses[i],replacements[i],&unused);
        if (status!=kHookSuccess || !unused) {
            if (status==kHookSuccess) (void)RemoveInlineHook(addresses[i]);
            for (unsigned j=0;j<i;++j) if (RemoveInlineHook(hooks[j].target)==kHookSuccess) hooks[j]={};
            __atomic_store_n(&reason,"hook_failed",__ATOMIC_RELEASE); return false;
        }
        next.target=addresses[i]; memcpy(next.patch,addresses[i],16); hooks[i]=next;
    }
    __atomic_store_n(&g_folder_stack_ready,1u,__ATOMIC_RELEASE);
    __atomic_store_n(&reason,"installed",__ATOMIC_RELEASE);
    __atomic_add_fetch(&g_folder_stack_refresh_epoch,uint64_t{1},__ATOMIC_RELEASE);
    LogInfo("folder stack preview installed: three front-ordered layers strategy=%u item=%u",layout.strategy,layout.item);
    return true;
}
// Called on the owning Dart thread, between lookup and return. No allocation, Dart call,
// GC roots, timers, or persistent heap pointers. The native large-icon entries are never written.
static bool KnownStrategy(uintptr_t strategy,uintptr_t null) {
    if(!strategy || strategy==null || !(strategy&1u)) return false;
    const auto cid=ClassId(strategy);
    for(unsigned i=0;i<layout.strategy_count;++i) if(cid==layout.strategies[i]) return true;
    return false;
}
// All verified big-folder family members use one producer. The cached list has
// a native large-icon grid followed by four small positions in its final cell.
// Infer the grid from validated rectangles, never from a size/version/CID table.
static bool UpdateGeometry(uintptr_t strategy,uintptr_t heap,uintptr_t null,size_t* large_count=nullptr) {
    if(!KnownStrategy(strategy,null)) return false;
    const auto list=Field(strategy,layout.list,heap);
    if(!list || list==null) return false;
    const int32_t length_smi=Read<int32_t>(list,0xb);
    constexpr size_t capacity=32; // Bounded native stack use; not a launcher size table.
    if((length_smi&1) || length_smi<12 || length_smi>static_cast<int32_t>(capacity*2)) return false;
    const size_t length=static_cast<size_t>(length_smi/2), large=length-4;
    // Native special case: a lone remaining app is a full-size icon.
    if(Read<int64_t>(strategy,layout.count)<=static_cast<int64_t>(large+1)) return false;
    const auto data=Field(list,0xf,heap);
    if(!data || data==null || Read<int32_t>(data,0xb)<length_smi) return false;
    uintptr_t items[capacity]{}; Rect current[capacity]{};
    for(size_t i=0;i<length;++i) {
        items[i]=Field(data,0xf+i*4,heap);
        if(!items[i] || items[i]==null || ClassId(items[i])!=layout.item) return false;
        current[i]=Read<Rect>(items[i],7);if(!Valid(current[i])) return false;
    }
    const double largeW=Read<double>(strategy,layout.large_w),largeH=Read<double>(strategy,layout.large_h);
    const double smallW=Read<double>(strategy,layout.small_w),smallH=Read<double>(strategy,layout.small_h);
    const double x=Read<double>(strategy,layout.x)+Read<double>(strategy,layout.padding_x);
    const double y=Read<double>(strategy,layout.y)+Read<double>(strategy,layout.padding_y);
    const double gapX=Read<double>(strategy,layout.gap_x),gapY=Read<double>(strategy,layout.gap_y);
    const double smallGap=Read<double>(strategy,layout.small_gap);
    size_t columns=large+1;
    for(size_t i=1;i<large;++i) {
        if(std::fabs(current[i].y-y)>=1e-6) { columns=i;break; }
    }
    for(size_t i=0;i<large;++i) {
        const Rect canonical{largeW,largeH,x+(i%columns)*(largeW+gapX),y+(i/columns)*(largeH+gapY)};
        if(!Valid(canonical) || !Same(canonical,current[i])) return false;
    }
    const double start=x+(large%columns)*(largeW+gapX),top=y+(large/columns)*(largeH+gapY);
    const double areaW=2*smallW+smallGap,areaH=2*smallH+smallGap;
    if(!Valid({areaW,areaH,start,top}) || areaW>largeW*1.25 || areaH>largeH*1.25) return false;
    Rect stock[4],stacked[4];bool allStock=true,allStacked=true,legacy=true,early=true;
    for(size_t i=0;i<4;++i) {
        stock[i]={smallW,smallH,start+(i%2)*(smallW+smallGap),top+(i/2)*(smallH+smallGap)};
        const double rank=2.0-static_cast<double>(i);
        stacked[i]=i<3 ? Rect{areaW*kStackCard,areaH*kStackCard,start+areaW*kStackStep*rank,top+areaH*kStackStep*rank} : stock[i];
        const Rect old{areaW*0.55,areaH*0.55,start+areaW*0.15*i,top+areaH*0.15*i};
        const Rect first{areaW*0.82,areaH*0.82,start+areaW*0.06*i,top+areaH*0.06*i};
        if(!Valid(stock[i]) || !Valid(stacked[i])) return false;
        allStock &= Same(current[large+i],stock[i]);allStacked &= Same(current[large+i],stacked[i]);
        legacy &= Same(current[large+i],old);early &= Same(current[large+i],first);
    }
    if(!allStock && !allStacked && !legacy && !early) return false;
    if(large_count) *large_count=large;
    const bool enabled=StackActive();
    if((enabled && allStacked) || (!enabled && allStock)) return true;
    for(size_t i=0;i<4;++i) {
        const auto& next=enabled ? stacked[i] : stock[i];
        memcpy(reinterpret_cast<void*>(items[large+i]+7),&next,sizeof(next));
    }
    return true;
}
namespace {
uintptr_t StackStrategy(uintptr_t closure,uintptr_t heap,uintptr_t null) {
    if (!closure || closure==null || !(closure&1u)) return 0;
    const auto context=Field(closure,0x17,heap);
    if (!context || context==null) return 0;
    const auto parent=Field(context,0xb,heap);
    if (!parent || parent==null) return 0;
    const auto controller=Field(parent,0x13,heap);
    if (!controller || controller==null) return 0;
    const auto strategy=Field(controller,layout.controller_strategy,heap);
    if (!KnownStrategy(strategy,null)) return 0;
    return strategy;
}
}
extern "C" bool HyperTweakUpdateFolderStack(uintptr_t strategy,uintptr_t heap,uintptr_t null) {
    CallbackLock guard;
    return guard.acquired && UpdateGeometry(strategy,heap,null);
}
// These callbacks change primitive indices/counts before the compiler's own builder
// call and array stores. Application lists and all GC references remain native-owned.
extern "C" int64_t HyperTweakFolderStackCount(uintptr_t closure,int64_t count_smi,uintptr_t heap,uintptr_t null) {
    const int64_t count=count_smi/2;
    if ((count_smi&1) || !StackActive() || count<4 || count>32) return count;
    CallbackLock guard;
    if (!guard.acquired) return count;
    const auto strategy=StackStrategy(closure,heap,null);
    size_t large=0;
    if(!strategy || !UpdateGeometry(strategy,heap,null,&large) || count<=static_cast<int64_t>(large+1) || count>static_cast<int64_t>(large+4)) return count;
    return count>static_cast<int64_t>(large+3) ? static_cast<int64_t>(large+3) : count;
}
extern "C" int64_t HyperTweakFolderStackIndex(uintptr_t closure,int64_t index,int64_t count,uintptr_t heap,uintptr_t null) {
    if (!StackActive() || index<0 || index>=count || count<4 || count>32) return index*2;
    CallbackLock guard;
    if (!guard.acquired) return index*2;
    const auto strategy=StackStrategy(closure,heap,null);
    size_t large=0;
    if(!strategy || !UpdateGeometry(strategy,heap,null,&large) || index<static_cast<int64_t>(large) ||
       count<static_cast<int64_t>(large+2) || count>static_cast<int64_t>(large+3)) return index*2;
    return (static_cast<int64_t>(large)+count-1-index)*2;
}
} // namespace hypertweak::native
