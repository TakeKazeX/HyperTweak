// SPDX-License-Identifier: Apache-2.0
#include "../../main/cpp/NativeRules/rules/dock_native_rule.cpp"
#include <cassert>
#include <vector>
using namespace hypertweak::native;

extern "C" {
void HyperTweakNativeDockGlassBranch(){}
void HyperTweakNativeDockGlassFactory(){}
void HyperTweakNativeDockGlassReturn(){}
void HyperTweakNativeDockGlassCenter(){}
void HyperTweakNativeDockGlassWrap(){}
void HyperTweakNativeDockIdle(){}
}
bool MiuiHomeHyosResolveDartImage(void*,uint8_t**,const uint8_t**){return false;}
int InstallInlineHook(void*,void*,void**){return 1;}
int RemoveInlineHook(void*){return 1;}
namespace hypertweak::native {
void LogInfo(const char*,...){}
void LogWarn(const char*,...){}
bool ValidDartCacheIdentity(const uint8_t*){return false;}
bool PrepareDartTargetSet(void*,const dart::TargetSpec* const*,DartResolution*,size_t){return false;}
uintptr_t DartResolution::Find(const char*)const{return 0;}
bool DartResolution::MatchVerify(const char*)const{return false;}
}

int main(){
 std::vector<uint8_t> arena(0x40000);const auto start=reinterpret_cast<uintptr_t>(arena.data());
 const auto heap=start>>32u,null=(heap<<32u)+0x101,sentinel=null+0x100;
 size_t next=0;
 auto allocate=[&](bool object=false){next=(next+15)&~size_t{15};const auto at=start+next+(object?1:0);next+=256;return at;};
 auto write=[](uintptr_t at,auto value){memcpy(reinterpret_cast<void*>(at),&value,sizeof(value));};
 plan.route.static_table_field=16;plan.route.device_config_slot=8;
 plan.route.home_state_slot=16;plan.route.widgets_binding_slot=24;
 plan.route.pad_field=3;plan.route.fold_field=7;
 plan.refresh.binding_frame_slot=-8;plan.refresh.root_field=3;plan.refresh.build_owner_field=7;
 plan.interaction.manager_slot=32;plan.interaction.sentinel_pool_offset=8;
 struct Context{uintptr_t thread,frame,pool,root,table;};
 auto make=[&]{
  const auto thread=allocate(),frame=allocate()+16,pool=allocate(),table=allocate();
  const auto binding=allocate(true),home=allocate(true),config=allocate(true),root=allocate(true),build=allocate(true);
  write(thread+16,table);write(table+8,config);write(table+16,home);write(table+24,binding);
  write(table+32,sentinel);write(pool+8,sentinel);write(frame-8,binding);
  write(config+3,static_cast<uint32_t>(null+0x30));write(config+7,static_cast<uint32_t>(null+0x30));
  write(binding+3,static_cast<uint32_t>(root));write(binding+7,static_cast<uint32_t>(build));
  return Context{thread,frame,pool,root,table};
 };
 auto idle=[&](const Context& c){return HyperTweakNativeDockAtIdle(c.thread,c.frame,heap,null,c.pool);};
 const auto a=make(),b=make();
 SetNativeDockEnabled(true);g_native_dock_ready=1;
 assert(idle(a)==a.root);
 g_native_dock_context_frames[0].stage=4;
 assert(idle(b)==b.root);
 assert(g_native_dock_context_frames[0].stage==4&&g_native_dock_context_frames[1].stage==0);
 // Reproduce two eligible engines alternating every frame: neither may steal
 // selection and reassemble the whole Launcher again.
 for(unsigned i=0;i<100;i++){assert(!idle(a));assert(!idle(b));}
 assert(SetNativeDockGlassGeometry(70u|(5u<<8u)|(5u<<16u)|(24u<<24u)));
 assert(idle(b)==b.root&&!idle(b));
 assert(g_native_dock_context_frames[0].stage==4);
 assert(idle(a)==a.root&&!idle(a));
 assert(g_native_dock_context_frames[0].height==70&&g_native_dock_context_frames[1].height==70);
 // Disable/re-enable can race the next frame; geometry changed in between
 // must still invalidate both existing selections.
 SetNativeDockEnabled(false);
 assert(SetNativeDockGlassGeometry(68u|(5u<<8u)|(5u<<16u)|(24u<<24u)));
 SetNativeDockEnabled(true);
 assert(idle(a)==a.root&&!idle(a));assert(idle(b)==b.root&&!idle(b));
 assert(g_native_dock_context_frames[0].height==68&&g_native_dock_context_frames[1].height==68);
 // Remap recovery and disable are consumed once by each original engine.
 Passivate(true);assert(!idle(a));g_native_dock_ready=1;
 assert(idle(a)==a.root&&!idle(a));assert(idle(b)==b.root&&!idle(b));
 SetNativeDockEnabled(false);
 assert(idle(b)==b.root&&!idle(b));assert(idle(a)==a.root&&!idle(a));assert(!OwnerMask());
 // A missing live HomePage is rejected and can be retried after initialization.
 SetNativeDockEnabled(true);write(b.table+16,null);assert(!idle(b));
 write(b.table+16,b.root);assert(idle(b)==b.root);
 ResetNativeDockAfterFork();assert(!OwnerMask()&&!g_native_dock_ready);
 g_native_dock_ready=1;
 // A full bank rejects additional engines without evicting a current one.
 std::vector<Context> contexts;
 for(size_t i=0;i<kNativeDockContextCount+1;i++)contexts.push_back(make());
 for(size_t i=0;i<kNativeDockContextCount;i++)assert(idle(contexts[i])==contexts[i].root);
 assert(!idle(contexts.back()));
 for(const auto& c:contexts)assert(!idle(c));
 SetNativeDockEnabled(false);
 for(size_t i=0;i<kNativeDockContextCount;i++)assert(idle(contexts[i])==contexts[i].root);
 assert(!OwnerMask());SetNativeDockEnabled(true);assert(idle(contexts.back())==contexts.back().root);
 puts("native Dock contexts: alternating engines, isolated frames, one-shot geometry/remap/disable, missing roots, fork and bounded admission passed");
}
