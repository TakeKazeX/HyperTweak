// SPDX-License-Identifier: Apache-2.0
#include "icon_scale_gate.h"
#include "icon_scale_rule.h"
#include "icon_scale_policy.h"
#include "dart_image.h"
#include "lsposed_hook_backend.h"
#include "logging.h"
#include <link.h>
#include <cstring>
#include <cmath>
#include <algorithm>
#include <pthread.h>
extern "C" void HyperTweakDesktopIconGateHook();
extern "C" {
__attribute__((visibility("hidden"))) uintptr_t g_icon_gate_continue=0;
__attribute__((visibility("hidden"))) uint32_t g_icon_gate_max_slot=0,g_icon_gate_min_slot=0,g_icon_gate_current_slot=0;
}
namespace hypertweak::native {
#include "icon_scale_gate_target.inc"
namespace {
pthread_mutex_t gate_lock=PTHREAD_MUTEX_INITIALIZER;
uint64_t maximum_bits=0;
uint32_t gate_ready=0;
void* gate_target=nullptr;
uint8_t gate_patch[16]{};
struct Library { const uint8_t* base=nullptr; unsigned count=0; };
int FindLibrary(dl_phdr_info* info,size_t,void* data) {
 auto& library=*static_cast<Library*>(data);
 const auto name=info->dlpi_name; if(!name) return 0;
 auto tail=std::strrchr(name,'/');tail=tail?tail+1:name;
 if (std::strcmp(tail,"libapp_launcher.so")!=0) return 0;
 ++library.count;library.base=reinterpret_cast<const uint8_t*>(info->dlpi_addr);return 0;
}
}
void SetDesktopIconMaximum(double value) {
 uint64_t bits=0;std::memcpy(&bits,&value,8);__atomic_store_n(&maximum_bits,bits,__ATOMIC_RELEASE);
}
void ResetDesktopIconGateAfterFork() {
 pthread_mutex_t fresh=PTHREAD_MUTEX_INITIALIZER;gate_lock=fresh;
 __atomic_store_n(&maximum_bits,uint64_t{0},__ATOMIC_RELEASE);
 __atomic_store_n(&gate_ready,0u,__ATOMIC_RELEASE);
}
bool ApplyDesktopIconGate() {
 if (DesktopIconPercentRequested()==-1 && !gate_target) return true;
 if (pthread_mutex_trylock(&gate_lock)!=0) return false;
 struct Unlock {~Unlock(){pthread_mutex_unlock(&gate_lock);}} unlock;
 if (gate_target && std::memcmp(gate_target,gate_patch,16)==0) {
  __atomic_store_n(&gate_ready,1u,__ATOMIC_RELEASE);return true;
 }
 __atomic_store_n(&gate_ready,0u,__ATOMIC_RELEASE);
 Library library{};dl_iterate_phdr(FindLibrary,&library);
 dart::Image image{};GateLayout layout{};
 if (library.count!=1 || !dart::ParseImage(library.base,&image) || !DecodeIconGate(image,&layout)) return false;
 auto target=const_cast<uint8_t*>(library.base)+layout.patch;
 if (gate_target && gate_target!=target) return false;
 if (gate_target && RemoveInlineHook(gate_target)!=kHookSuccess) return false;
 gate_target=nullptr;
 // Publish replay operands before patching. The shim can replay stock behavior
 // even if another thread enters while the hook backend is still installing.
 g_icon_gate_continue=reinterpret_cast<uintptr_t>(library.base)+layout.continuation;
 g_icon_gate_max_slot=layout.maximum;g_icon_gate_min_slot=layout.minimum;g_icon_gate_current_slot=layout.current;
 void* backup=nullptr;
 const int result=InstallInlineHook(target,reinterpret_cast<void*>(HyperTweakDesktopIconGateHook),&backup);
 if(result==kHookSuccess) {gate_target=target;std::memcpy(gate_patch,target,16);}
 if(result!=kHookSuccess || !backup) {
  if(gate_target && RemoveInlineHook(gate_target)==kHookSuccess) gate_target=nullptr;
  return false;
 }
 __atomic_store_n(&gate_ready,1u,__ATOMIC_RELEASE);
 LogInfo("desktop icon provider bounds installed from unique native validation data flow");return true;
}
extern "C" void HyperTweakDesktopIconGate(float requested,float minimum,float maximum,float* output) {
 output[0]=requested;output[1]=minimum;output[2]=maximum;
 if (DesktopIconPercentRequested()==-1 || !__atomic_load_n(&gate_ready,__ATOMIC_ACQUIRE)) return;
 const uint64_t bits=__atomic_load_n(&maximum_bits,__ATOMIC_ACQUIRE);double available=0;std::memcpy(&available,&bits,8);
 const auto bounded=BoundDesktopIconRequest(requested,minimum,maximum,available);
 output[0]=bounded.requested;output[1]=bounded.minimum;output[2]=bounded.maximum;
}
}
