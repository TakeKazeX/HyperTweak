// SPDX-License-Identifier: Apache-2.0
#include "icon_theme_rule.h"
#include "dart_rule_support.h"
#include "lsposed_hook_backend.h"
#include "logging.h"
#include <pthread.h>
#include <cstring>
extern "C" {
__attribute__((visibility("hidden"))) uint32_t g_icon_theme_unlock=0,g_icon_theme_refresh=0;
__attribute__((visibility("hidden"))) void* g_icon_theme_support_original=nullptr;
__attribute__((visibility("hidden"))) void* g_icon_theme_refresh_original=nullptr;
void HyperTweakIconThemeSupportHook();
void HyperTweakIconThemeRefreshHook();
}
namespace hypertweak::native {
namespace {
pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;
int requested_mode=0;
const char* reason="disabled";
struct Hook { void* address=nullptr;uint8_t original[16]{},patch[16]{}; } hooks[2];
uint8_t* base=nullptr;
dart::IconThemeObserverLayout observer{};
uint32_t observer_ready=0,observer_inflight=0;
void Disable() {
 __atomic_store_n(&observer_ready,0u,__ATOMIC_RELEASE);
 __atomic_store_n(&observer_inflight,0u,__ATOMIC_RELEASE);
 __atomic_store_n(&g_icon_theme_unlock,0u,__ATOMIC_RELEASE);
 __atomic_store_n(&g_icon_theme_refresh,0u,__ATOMIC_RELEASE);
}
}
void SetIconTheme(int mode) {
 if(mode<0||mode>4)return;
 if(__atomic_exchange_n(&requested_mode,mode,__ATOMIC_ACQ_REL)!=mode) __atomic_store_n(&observer_inflight,0u,__ATOMIC_RELEASE);
 if(!mode)__atomic_store_n(&g_icon_theme_unlock,0u,__ATOMIC_RELEASE);
 if(mode<2)__atomic_store_n(&g_icon_theme_refresh,0u,__ATOMIC_RELEASE);
}
bool IconThemeNeedsPreparation(){return __atomic_load_n(&requested_mode,__ATOMIC_ACQUIRE)!=0;}
const char* IconThemeReason(){return __atomic_load_n(&reason,__ATOMIC_ACQUIRE);}
void ResetIconThemeAfterFork(){pthread_mutex_t fresh=PTHREAD_MUTEX_INITIALIZER;lock=fresh;Disable();}
bool ApplyIconThemeRule(void* handle) {
 if(pthread_mutex_trylock(&lock)!=0)return false;
 struct Unlock{~Unlock(){pthread_mutex_unlock(&lock);}} unlock;
 if(!IconThemeNeedsPreparation()){Disable();__atomic_store_n(&reason,"disabled",__ATOMIC_RELEASE);return true;}
 DartResolution resolution{};
 if(!ResolveDartSites(handle,dart::kIconThemeTarget,&resolution)){Disable();__atomic_store_n(&reason,resolution.reason,__ATOMIC_RELEASE);return false;}
 const char* names[]={"support","refresh"};
 dart::IconThemeObserverLayout next{};
 dart::Image image{};
 if(!dart::ParseImage(resolution.base,&image)) {Disable();return false;}
 const uint8_t* owned[2]{};
 for(unsigned i=0;i<2;i++) if(base==resolution.base&&hooks[i].address==reinterpret_cast<void*>(resolution.Find(names[i]))&&
    std::memcmp(hooks[i].address,hooks[i].patch,16)==0)owned[i]=hooks[i].original;
 const auto origin=reinterpret_cast<uintptr_t>(resolution.base);
 if(!dart::ValidateIconThemeTargets(image,resolution.Find("support")-origin,resolution.Find("refresh")-origin,owned[0],owned[1])) {
  Disable();__atomic_store_n(&reason,"theme_contract_changed",__ATOMIC_RELEASE);return false;
 }

 if(!dart::DecodeIconThemeObserver(image,resolution.Find("owner")-origin,resolution.Find("bootstrap")-origin,resolution.Find("refresh")-origin,&next)) {
  Disable();__atomic_store_n(&reason,"theme_observer_contract_changed",__ATOMIC_RELEASE);return false;
 }
 bool intact=base==resolution.base;
 for(unsigned i=0;i<2;i++)intact &= hooks[i].address==reinterpret_cast<void*>(resolution.Find(names[i]))&&hooks[i].address&&std::memcmp(hooks[i].address,hooks[i].patch,16)==0;
 if(!intact){
  Disable();
  for(unsigned i=0;i<2;i++){
   auto address=reinterpret_cast<void*>(resolution.Find(names[i]));
   if(hooks[i].address){
    if(base!=resolution.base||hooks[i].address!=address||
       (std::memcmp(address,hooks[i].patch,16)!=0&&std::memcmp(address,hooks[i].original,16)!=0)){
     __atomic_store_n(&reason,"foreign_patch_detected",__ATOMIC_RELEASE);return false;
    }
   }else if(!resolution.MatchVerify(names[i])){__atomic_store_n(&reason,"verify_failed",__ATOMIC_RELEASE);return false;}
  }
  for(auto& hook:hooks)if(hook.address){
   if(RemoveInlineHook(hook.address)!=kHookSuccess){__atomic_store_n(&reason,"unhook_failed",__ATOMIC_RELEASE);return false;}hook={};
  }
  base=resolution.base;
  void* replacements[]={reinterpret_cast<void*>(HyperTweakIconThemeSupportHook),reinterpret_cast<void*>(HyperTweakIconThemeRefreshHook)};
  void** backups[]={&g_icon_theme_support_original,&g_icon_theme_refresh_original};
  unsigned installed=0;
  for(;installed<2;installed++){
   auto& hook=hooks[installed];auto address=reinterpret_cast<void*>(resolution.Find(names[installed]));
   std::memcpy(hook.original,address,16);*backups[installed]=nullptr;
   const auto status=InstallInlineHook(address,replacements[installed],backups[installed]);
   if(status==kHookSuccess){hook.address=address;std::memcpy(hook.patch,address,16);}
   if(status!=kHookSuccess||!*backups[installed])break;
  }
  if(installed!=2){
   for(auto& hook:hooks)if(hook.address&&std::memcmp(hook.address,hook.patch,16)==0&&RemoveInlineHook(hook.address)==kHookSuccess)hook={};
   __atomic_store_n(&reason,"hook_failed",__ATOMIC_RELEASE);return false;
  }
  LogInfo("desktop icon theme support/refresh installed");
 }
 observer=next;
 __atomic_store_n(&observer_ready,1u,__ATOMIC_RELEASE);
 __atomic_store_n(&g_icon_theme_unlock,IconThemeNeedsPreparation()?1u:0u,__ATOMIC_RELEASE);
 __atomic_store_n(&g_icon_theme_refresh,__atomic_load_n(&requested_mode,__ATOMIC_ACQUIRE)>=2?1u:0u,__ATOMIC_RELEASE);
 __atomic_store_n(&reason,"installed",__ATOMIC_RELEASE);return true;
}
// Called by the shared, verified scheduler epilogue on the active desktop isolate.
// No managed allocation or managed calls here; the assembly pops its Dart frame
// before transferring to the original async registration body and its own stack maps.
uintptr_t IconThemeObserverFrame(uintptr_t statics,uintptr_t null,uintptr_t sentinel,uintptr_t* receiver) {
 if(!receiver||!__atomic_load_n(&observer_ready,__ATOMIC_ACQUIRE)||!IconThemeNeedsPreparation()||pthread_mutex_trylock(&lock)!=0)return 0;
 struct Unlock{~Unlock(){pthread_mutex_unlock(&lock);}} unlock;
 uintptr_t value=0;memcpy(&value,reinterpret_cast<void*>(statics+observer.static_slot),8);
 if(!(value&1u)||value==null||value==sentinel)return 0;
 uint32_t tag=0,flag=0;memcpy(&tag,reinterpret_cast<void*>(value-1),4);
 if((tag>>12u&0xfffffu)!=observer.cid)return 0;
 memcpy(&flag,reinterpret_cast<void*>(value+observer.registered_field),4);
 if(flag==static_cast<uint32_t>(null+0x20)){__atomic_store_n(&observer_inflight,0u,__ATOMIC_RELEASE);return 0;}
 if(flag!=static_cast<uint32_t>(null+0x30)||__atomic_exchange_n(&observer_inflight,1u,__ATOMIC_ACQ_REL))return 0;
 *receiver=value;
 return reinterpret_cast<uintptr_t>(base)+observer.registration;
}

}
