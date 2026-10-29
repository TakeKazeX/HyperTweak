// SPDX-License-Identifier: Apache-2.0
#include "icon_scale_scope.h"
#include "icon_scale_rule.h"
#include "lsposed_hook_backend.h"
#include "logging.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <pthread.h>
extern "C" void HyperTweakIconScopeClampHook();
extern "C" { __attribute__((visibility("hidden"))) uintptr_t g_icon_scope_continue=0; }
namespace hypertweak::native {
namespace {
pthread_mutex_t scope_lock=PTHREAD_MUTEX_INITIALIZER;
struct ScopeHook {void* target=nullptr;uint8_t original[16]{},patch[16]{};} scope_hook;
uint32_t scope_ready=0;
}
void ResetDesktopIconScopeAfterFork() {
 pthread_mutex_t fresh=PTHREAD_MUTEX_INITIALIZER;scope_lock=fresh;
 __atomic_store_n(&scope_ready,0u,__ATOMIC_RELEASE);
}
bool ApplyDesktopIconScope(uint8_t* base,const dart::IconScaleLayout& layout) {
 if(pthread_mutex_trylock(&scope_lock)!=0) return false;
 struct Unlock {~Unlock(){pthread_mutex_unlock(&scope_lock);}} unlock;
 auto& hook=scope_hook;void* address=base+layout.scope_clamp;
 if(hook.target==address && std::memcmp(address,hook.patch,16)==0) {
  __atomic_store_n(&scope_ready,1u,__ATOMIC_RELEASE);return true;
 }
 __atomic_store_n(&scope_ready,0u,__ATOMIC_RELEASE);
 if(hook.target && (hook.target!=address ||
   (std::memcmp(address,hook.patch,16)!=0 && std::memcmp(address,hook.original,16)!=0))) return false;
 if(hook.target) {if(RemoveInlineHook(hook.target)!=kHookSuccess) return false;hook={};}
 g_icon_scope_continue=reinterpret_cast<uintptr_t>(address)+16;
 std::memcpy(hook.original,address,16);void* backup=nullptr;
 const int result=InstallInlineHook(address,reinterpret_cast<void*>(HyperTweakIconScopeClampHook),&backup);
 if(result==kHookSuccess){hook.target=address;std::memcpy(hook.patch,address,16);}
 if(result!=kHookSuccess || !backup) {
  if(hook.target && RemoveInlineHook(hook.target)==kHookSuccess) hook={};
  return false;
 }
 __atomic_store_n(&scope_ready,1u,__ATOMIC_RELEASE);
 LogInfo("desktop icon initializer bounds installed: primitive override, original Ranges preserved");return true;
}
extern "C" void HyperTweakIconScopeBounds(double maximum,double minimum,double* values) {
 values[0]=maximum;values[1]=minimum;
 if(!__atomic_load_n(&scope_ready,__ATOMIC_ACQUIRE) || DesktopIconPercentRequested()==-1 ||
    !std::isfinite(minimum) || !std::isfinite(maximum) || minimum>maximum) return;
 values[0]=std::max(maximum,1.0);values[1]=std::min(minimum,0.01);
}
}
