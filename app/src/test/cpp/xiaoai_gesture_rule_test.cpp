// SPDX-License-Identifier: Apache-2.0
// Exercise the production callback against live, compressed-pointer-shaped fixtures.
#include "../../main/cpp/NativeRules/xiaoai_gesture_rule.cpp"
#include <assert.h>
#include <stdlib.h>
#include <stdio.h>
int InstallInlineHook(void*,void*,void**) { return 1; }
int RemoveInlineHook(void*) { return 1; }
extern "C" void HyperTweakXiaoaiGestureHook() {}
namespace hypertweak::native {
bool ResolveDartSites(void*,const dart::TargetSpec&,DartResolution*) {return false;}
uintptr_t DartResolution::Find(const char*) const {return 0;}
bool DartResolution::MatchVerify(const char*) const {return false;}
void LogInfo(const char*,...) {}
void LogWarn(const char*,...) {}
}
int main() {
    using namespace hypertweak::native;
    // All fixtures share one compressed heap segment, as on Dart's owning thread.
    auto* arena=static_cast<uint8_t*>(calloc(1,8192));assert(arena);
    const auto heap=reinterpret_cast<uintptr_t>(arena)>>32;
    auto object=[&](size_t off,uint32_t cid) {auto p=reinterpret_cast<uintptr_t>(arena+off+1);uint32_t tag=cid<<12;memcpy(reinterpret_cast<void*>(p-1),&tag,4);return p;};
    auto string=[&](size_t off,const char* value,uint32_t cid=94) {auto p=object(off,cid);uint32_t n=strlen(value)*2;memcpy(reinterpret_cast<void*>(p+7),&n,4);memcpy(reinterpret_cast<void*>(p+15),value,strlen(value));return p;};
    auto field=[](uintptr_t p,uint32_t off,uintptr_t value) {uint32_t w=value;memcpy(reinterpret_cast<void*>(p+off),&w,4);};
    const auto lp=string(512,"NavLongPress"),dc=string(768,"NavDoubleClick");
    const auto text=string(1024,"VoiceAssistant"),voice=string(1280,"VoiceAssistantVoiceInput"),screen=string(1536,"VoiceAssistantScreenRecognizer");
    const auto ball=string(1792,"VoiceAssistantIntentBall"),other=string(2048,"NavLongPress",95);
    const auto item=object(2304,3000),closure=object(2560,2000),context=object(2816,1000);
    const auto pool=reinterpret_cast<uintptr_t>(arena+4096);
    memcpy(reinterpret_cast<void*>(pool+32),&lp,8);memcpy(reinterpret_cast<void*>(pool+40),&dc,8);
    layout={3000,23,15,11,7,32,40};field(closure,23,context);
    auto allow=[&](bool enabled,uintptr_t trigger,uintptr_t entity,int64_t mask) {
        SetXiaoaiGestureOptionsEnabled(enabled);field(context,15,trigger);field(item,7,entity);
        memcpy(reinterpret_cast<void*>(item+11),&mask,8);
        return HyperTweakAllowXiaoaiGesture(item,closure,heap,pool)!=0;
    };
    assert(allow(true,lp,text,4));assert(allow(true,dc,screen,2));assert(allow(true,dc,voice,2));
    assert(!allow(false,lp,text,4));assert(!allow(false,dc,voice,2));
    assert(!allow(true,lp,ball,4));assert(!allow(true,dc,ball,2));
    assert(!allow(true,other,text,4));assert(!allow(true,lp,text,64));
    // Original allowed cases are deliberately handled by the original Dart predicate.
    assert(!allow(true,dc,text,4));assert(!allow(true,lp,voice,2));
    assert(!HyperTweakAllowXiaoaiGesture(0,closure,heap,pool));
    free(arena);puts("PASS production XiaoAI callback: exactly three additions, disable, native fallback, field/CID guards");
}
