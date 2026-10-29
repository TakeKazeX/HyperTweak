// SPDX-License-Identifier: Apache-2.0
#include "../../main/cpp/NativeRules/rules/icon_scale_scope.cpp"
#include <cassert>
#include <limits>
using namespace hypertweak::native;
namespace { int percent=-1;bool fail=false,empty=false;unsigned installs=0,removes=0; }
namespace hypertweak::native {
int DesktopIconPercentRequested(){return percent;}
void LogInfo(const char*,...){}
}
extern "C" void HyperTweakIconScopeClampHook(){}
int InstallInlineHook(void* target,void*,void** backup) {
 ++installs;if(fail)return kHookFailed;
 std::memset(target,0xa5,16);*backup=empty?nullptr:reinterpret_cast<void*>(1);return kHookSuccess;
}
int RemoveInlineHook(void* target) {
 ++removes;assert(scope_hook.target==target);std::memcpy(target,scope_hook.original,16);return kHookSuccess;
}
int main() {
 uint8_t code[32]{};std::memset(code,0x55,sizeof(code));dart::IconScaleLayout layout{};layout.scope_clamp=8;
 double values[2]{};percent=50;
 HyperTweakIconScopeBounds(.72,.54,values);assert(values[0]==.72&&values[1]==.54);
 fail=true;assert(!ApplyDesktopIconScope(code,layout));fail=false;
 empty=true;assert(!ApplyDesktopIconScope(code,layout));empty=false;
 assert(!scope_hook.target&&code[8]==0x55&&removes==1);
 assert(ApplyDesktopIconScope(code,layout));const auto installed=installs;
 assert(ApplyDesktopIconScope(code,layout)&&installs==installed);
 HyperTweakIconScopeBounds(.72,.54,values);assert(values[0]==1&&values[1]==.01);
 percent=-1;HyperTweakIconScopeBounds(.72,.54,values);assert(values[0]==.72&&values[1]==.54);
 percent=150;ResetDesktopIconScopeAfterFork();HyperTweakIconScopeBounds(.72,.54,values);assert(values[0]==.72&&values[1]==.54);
 assert(ApplyDesktopIconScope(code,layout));
 HyperTweakIconScopeBounds(.5,.7,values);assert(values[0]==.5&&values[1]==.7);
 HyperTweakIconScopeBounds(std::numeric_limits<double>::infinity(),.54,values);assert(std::isinf(values[0])&&values[1]==.54);
 // Remapping an owned window recovers; a foreign partial patch remains untouched.
 std::memcpy(code+8,scope_hook.original,16);assert(ApplyDesktopIconScope(code,layout));
 code[8]=0x99;const auto attempts=installs;assert(!ApplyDesktopIconScope(code,layout)&&installs==attempts&&code[8]==0x99);
 HyperTweakIconScopeBounds(.72,.54,values);assert(values[0]==.72&&values[1]==.54);
}
