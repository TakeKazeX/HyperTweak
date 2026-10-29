// SPDX-License-Identifier: Apache-2.0
#include "../../main/cpp/NativeRules/rules/icon_scale_rule.cpp"
#include <cassert>
#include <fstream>
#include <iterator>
#include <vector>
#include <cstdlib>
using namespace hypertweak::native;
namespace {
std::vector<uint8_t> image_bytes;
dart::TargetResult resolved{};
bool prepared=true;
unsigned installs=0,fail_stage=0;
bool empty_backup=false;
}
extern "C" void HyperTweakDesktopIconFrameHook() {}
namespace hypertweak::native {
bool IconThemeNeedsPreparation(){return false;}
uintptr_t IconThemeObserverFrame(uintptr_t,uintptr_t,uintptr_t,uintptr_t*){return 0;}
bool DesktopGridNeedsPreparation(){return false;}
uintptr_t DesktopGridFrame(uintptr_t,uintptr_t,uintptr_t,uintptr_t,uintptr_t,DesktopGridDispatch*){return 0;}
void LogInfo(const char*,...) {}
bool ApplyDesktopIconGate() { return true; }
bool ApplyDesktopIconScope(uint8_t*,const dart::IconScaleLayout&) { return true; }
void ResetDesktopIconScopeAfterFork() {}
void ResetDesktopIconGateAfterFork() {}
double observed_maximum=0;
void SetDesktopIconMaximum(double value) { observed_maximum=value; }
bool ResolveDartSites(void*,const dart::TargetSpec& spec,DartResolution* out) {
    *out={}; out->base=image_bytes.data(); out->reason=prepared ? "resolved" : "dart_preparation_pending";
    if(!prepared) return false;
    out->site_count=spec.site_count; out->located=resolved.resolved;
    for(unsigned i=0;i<3;++i) out->sites[i]={spec.sites[i].name,reinterpret_cast<uintptr_t>(out->base)+resolved.sites[i].offset,true,spec.sites[i].verify,0};
    return out->located;
}
uintptr_t DartResolution::Find(const char* name) const {
    for(unsigned i=0;i<site_count;++i) if(!strcmp(name,sites[i].name)) return sites[i].address;
    return 0;
}
}
int InstallInlineHook(void* at,void*,void** backup) {
    if(++installs==fail_stage) return 1;
    memset(at,0xa5,16); *backup=empty_backup ? nullptr : reinterpret_cast<void*>(1); return 0;
}
int RemoveInlineHook(void* at) {
    for(const auto& hook:hooks) if(hook.target==at) { memcpy(at,hook.original,16); return 0; }
    return 1;
}
int main(int argc,char** argv) {
    assert(argc==2);
    std::ifstream file(argv[1],std::ios::binary); image_bytes.assign(std::istreambuf_iterator<char>(file),{});
    dart::Image image{}; assert(dart::ParseImage(image_bytes.data(),&image));
    resolved=dart::ResolveTarget(image,dart::kIconScaleTarget); assert(resolved.resolved && resolved.verified);
    SetDesktopIconPercent(150); prepared=false; assert(!ApplyDesktopIconScaleRule(nullptr));
    prepared=true; empty_backup=true; assert(!ApplyDesktopIconScaleRule(nullptr));
    assert(!hooks[0].target && !hooks[1].target); empty_backup=false;
    fail_stage=installs+2; assert(!ApplyDesktopIconScaleRule(nullptr));
    assert(!hooks[0].target && !hooks[1].target);
    fail_stage=0; assert(ApplyDesktopIconScaleRule(nullptr));
    auto count=installs; assert(ApplyDesktopIconScaleRule(nullptr) && installs==count);
    // Partial and complete remaps must be repaired without scanning patched code.
    memcpy(hooks[0].target,hooks[0].original,16); assert(ApplyDesktopIconScaleRule(nullptr));
    for(auto& hook:hooks) memcpy(hook.target,hook.original,16);
    assert(ApplyDesktopIconScaleRule(nullptr));
    auto first=reinterpret_cast<uint8_t*>(hooks[0].target); auto saved=*first; *first=0x42;
    assert(!ApplyDesktopIconScaleRule(nullptr)); *first=saved; assert(ApplyDesktopIconScaleRule(nullptr));
    // All simulated compressed objects share one 4 GiB heap window.
    auto memory=static_cast<uint8_t*>(aligned_alloc(65536,65536)); assert(memory); memset(memory,0,65536);
    const auto origin=reinterpret_cast<uintptr_t>(memory), heap=origin>>32;
    assert((origin+65535)>>32==heap);
    const uintptr_t null=origin+1, sentinel=origin+33, thread=origin+0x8000, pool=origin+0x9000, statics=origin+0xa000;
    const uintptr_t provider=origin+0x1401,cur=origin+0x1601;
    auto put64=[](uintptr_t at,uintptr_t value){memcpy(reinterpret_cast<void*>(at),&value,8);};
    auto put32=[](uintptr_t at,uintptr_t value){auto small=static_cast<uint32_t>(value);memcpy(reinterpret_cast<void*>(at),&small,4);};
    put64(thread+0x78,statics);put64(pool+0x40,sentinel);
    put32(provider+layout.current,cur);put32(cur+layout.rx_value,origin+0x1901);
    put32(origin+0x1900,layout.double_cid<<12);double scale_value=.68;
    memcpy(reinterpret_cast<void*>(origin+0x1908),&scale_value,8);
    // The template provider is deliberately unrelated to the live immutable grid.
    const uintptr_t controller=origin+0x2001, active=origin+0x2101, actual=origin+0x2201, actual_icon=origin+0x2301;
    put64(statics+layout.controller_slot,controller); put32(controller+layout.active_rx,active);
    put32(active+layout.rx_value,actual);put32(actual+layout.actual_grid_icon,actual_icon);
    put32(actual_icon+layout.actual_provider,provider);
    double width=90,rendered=61.2,top_padding=10;
    memcpy(reinterpret_cast<void*>(actual+layout.actual_width),&width,8);
    memcpy(reinterpret_cast<void*>(actual_icon+layout.actual_size),&rendered,8);
    memcpy(reinterpret_cast<void*>(actual_icon+layout.actual_padding),&top_padding,8);
    uintptr_t receiver=0;double scale=0;
    assert(!HyperTweakDesktopIconFrame(thread,heap,null,pool,&receiver,&scale));
    assert(std::abs(observed_maximum-(rendered+2*top_padding-4)/width)<0.000001);
    // No Dart setter is returned, and no template Rx/heap object is changed.
    assert(receiver==0);assert(g_desktop_icon_dispatches==0);
    put32(controller+layout.active_rx,sentinel);observed_maximum=0;
    assert(!HyperTweakDesktopIconFrame(thread,heap,null,pool,&receiver,&scale));assert(observed_maximum==0);
    put32(controller+layout.active_rx,active);
    ResetDesktopIconScaleAfterFork();assert(ApplyDesktopIconScaleRule(nullptr));SetDesktopIconPercent(-1);
    assert(!HyperTweakDesktopIconFrame(thread,heap,null,pool,&receiver,&scale));
    assert(observed_maximum>0);
    free(memory);
    puts("desktop icon active-grid geometry: template isolation, safe bounds, disabled observer and hook rollback passed");
}
