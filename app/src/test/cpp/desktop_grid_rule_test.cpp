// SPDX-License-Identifier: Apache-2.0
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <vector>
#include <fstream>
#include <iterator>
#include <cassert>
static int persisted_code=0;
static FILE* GridTestOpen(const char*,const char*) {
 if(!persisted_code)return nullptr;
 FILE* file=tmpfile();assert(file);
 const int x=4+(persisted_code-1)/4,y=5+(persisted_code-1)%4;
 fprintf(file,"<map><int name=\"pref_key_cell_x\" value=\"%d\"/><int name=\"pref_key_cell_y\" value=\"%d\"/><string name=\"desktop_cell_count_in_db\">%dx%d</string></map>",x,y,4,6);
 rewind(file);return file;
}
#define fopen GridTestOpen
#include "../../main/cpp/NativeRules/rules/desktop_grid_rule.cpp"
#undef fopen
#include <cstdarg>
using namespace hypertweak::native;
static std::vector<uint8_t> image_bytes;
static dart::TargetResult resolved{};
static bool allow_write=false,bad_journal=false;
static unsigned installs=0,fail_at=0;
extern "C" void HyperTweakDesktopGridVariableHook(){}
extern "C" void HyperTweakDesktopGridHandlerHook(){}
extern "C" void HyperTweakDesktopGridRowsHook(){}
extern "C" void HyperTweakDesktopGridClampHook(){}

namespace hypertweak::native {
bool TryNotifyDesktopGridPreparation(){return true;}
void LogInfo(const char*,...){}
void LogWarn(const char*,...){}
bool ReadNativeRecord(const char*,void* data,size_t size){if(!bad_journal)return false;Journal bad{};bad.original=2;bad.active=1;memcpy(data,&bad,size);return true;}
bool WriteNativeRecord(const char*,const void*,size_t){return allow_write;}
void DeleteNativeRecord(const char*){}
uintptr_t DartResolution::Find(const char* name)const{for(unsigned i=0;i<site_count;++i)if(!strcmp(name,sites[i].name))return sites[i].address;return 0;}
bool ResolveDartSites(void*,const dart::TargetSpec& spec,DartResolution* out){*out={};out->base=image_bytes.data();out->located=true;out->reason="resolved";out->site_count=spec.site_count;for(unsigned i=0;i<spec.site_count;++i)out->sites[i]={spec.sites[i].name,reinterpret_cast<uintptr_t>(out->base)+resolved.sites[i].offset,true,spec.sites[i].verify,0};return true;}
}
int InstallInlineHook(void* target,void*,void** backup){if(++installs==fail_at)return 1;memset(target,0xa5,16);*backup=reinterpret_cast<void*>(1);return 0;}
int RemoveInlineHook(void* target){for(auto& h:hooks)if(h.target==target){memcpy(target,h.original,16);return 0;}return 1;}
int main(int argc,char** argv){
 assert(argc==2);std::ifstream f(argv[1],std::ios::binary);image_bytes.assign(std::istreambuf_iterator<char>(f),{});dart::Image image{};assert(dart::ParseImage(image_bytes.data(),&image));resolved=dart::ResolveTarget(image,dart::kDesktopGridTarget);assert(resolved.resolved&&resolved.verified);

 // Corrupt each required entry: absence or a failed verification must fail closed.
 for(unsigned i=0;i<8;++i){const auto at=resolved.sites[i].offset;const auto saved=image_bytes[at];image_bytes[at]^=0xff;const auto bad=dart::ResolveTarget(image,dart::kDesktopGridTarget);assert(!bad.resolved||!bad.verified);image_bytes[at]=saved;}
 uintptr_t sites[8]{};for(unsigned i=0;i<8;++i)sites[i]=resolved.sites[i].offset;
 dart::DesktopGridLayout decoded{};assert(dart::DecodeDesktopGridLayout(image,sites,&decoded));
 // Preview reads and the independent live-geometry consumer must agree.
 uint32_t prepare_word=0;uintptr_t prepare_current=0;
 assert(dart::ReadInsn(image,sites[1]+20,&prepare_word)&&dart::DecodeBlTarget(sites[1]+20,prepare_word,&prepare_current));
 uint32_t geometry_field=0;assert(dart::ReadInsn(image,prepare_current+200,&geometry_field));
 const auto conflicting_geometry=geometry_field^(4u<<12u);
 memcpy(image_bytes.data()+prepare_current+200,&conflicting_geometry,4);
 assert(!dart::DecodeDesktopGridLayout(image,sites,&decoded));
 memcpy(image_bytes.data()+prepare_current+200,&geometry_field,4);
 assert(dart::DecodeDesktopGridLayout(image,sites,&decoded));
 // A field producer cannot diverge from its consumer, even when its opcode still matches.
 const auto field_at=decoded.clamp+4;uint32_t field_word=0;memcpy(&field_word,image_bytes.data()+field_at,4);const auto changed=field_word^(4u<<12u);memcpy(image_bytes.data()+field_at,&changed,4);assert(!dart::DecodeDesktopGridLayout(image,sites,&decoded));memcpy(image_bytes.data()+field_at,&field_word,4);
 // Shape-compatible operands must still preserve the input/output relationships.
 uintptr_t migrate_call=0;unsigned migrate_calls=0;
 for(size_t offset=0;offset<8192;offset+=4){uint32_t word=0;uintptr_t target=0;if(dart::ReadInsn(image,decoded.load+offset,&word)&&dart::IsBl(word)&&dart::DecodeBlTarget(decoded.load+offset,word,&target)&&target==decoded.migrate){migrate_call=decoded.load+offset;++migrate_calls;}}
 assert(migrate_calls==1);
 for(const auto at:{decoded.variable+0x3c,migrate_call}){
  uint32_t before=0;memcpy(&before,image_bytes.data()+at,4);
  const uint32_t corrupt=at==decoded.variable+0x3c?before^(4u<<12u):0x94000000u;
  memcpy(image_bytes.data()+at,&corrupt,4);dart::DesktopGridLayout rejected{};
  assert(!dart::DecodeDesktopGridLayout(image,sites,&rejected));memcpy(image_bytes.data()+at,&before,4);
 }
 // A second matching change body is ambiguous, regardless of its address.
 const auto& change_pattern=dart::kDesktopGridTarget.sites[3].verify;
 for(size_t i=0;i<image.load_count;++i)if((image.loads[i].flags&dart::kFlagExec)&&image.loads[i].end-image.loads[i].start>16384){const auto at=image.loads[i].end-8192;std::vector<uint8_t> saved(image_bytes.begin()+at,image_bytes.begin()+at+change_pattern.size);memcpy(image_bytes.data()+at,image_bytes.data()+sites[3],change_pattern.size);const auto duplicate=dart::ResolveTarget(image,dart::kDesktopGridTarget);assert(!duplicate.resolved);memcpy(image_bytes.data()+at,saved.data(),saved.size());break;}
 PrepareDesktopGridStorage();SetDesktopGrid(12);assert(PrepareDesktopGridLayout(nullptr));fail_at=2;assert(!ApplyDesktopGridRule(nullptr));for(auto& h:hooks)assert(!h.target);fail_at=0;assert(ApplyDesktopGridRule(nullptr));const auto count=installs;assert(ApplyDesktopGridRule(nullptr)&&installs==count);
 auto memory=static_cast<uint8_t*>(aligned_alloc(65536,65536));assert(memory);memset(memory,0,65536);
 const uintptr_t origin=reinterpret_cast<uintptr_t>(memory),heap=origin>>32,null=origin+0x101,sentinel=origin+0x201,thread=origin+0x3000,pool=origin+0x4000,statics=origin+0x8000;
 auto ptr=[&](uintptr_t p,size_t off,uintptr_t value){uint32_t low=static_cast<uint32_t>(value);memcpy(reinterpret_cast<void*>(p+off),&low,4);};
 auto raw=[&](uintptr_t p,size_t off,uintptr_t value){memcpy(reinterpret_cast<void*>(p+off),&value,8);};
 const auto controller=origin+0x1001,grid=origin+0x1201,current=origin+0x1401,option=origin+0x1601,list=origin+0x1801,array=origin+0x1a01,grid_controller=origin+0x1c01,descriptor=origin+0x1e01;
 raw(thread,0x78,statics);raw(pool,0x40,sentinel);raw(pool,layout.load_descriptor,descriptor);raw(statics,layout.controller_slot,controller);raw(statics,layout.grid_controller_slot,grid_controller);
 ptr(controller,layout.current,current);ptr(controller,layout.selected,current);ptr(controller,layout.options,list);ptr(list,layout.list_length,2);ptr(list,layout.list_data,array);ptr(array,layout.array_data,option);
 const uint32_t header=layout.cell_cid<<12;memcpy(reinterpret_cast<void*>(current-1),&header,4);memcpy(reinterpret_cast<void*>(option-1),&header,4);
 auto geometry=[&](int code){Integer(grid,layout.grid_x,DesktopGridColumns(code));Integer(grid,layout.grid_y,DesktopGridRows(code));};
 auto prepared=[&](){Integer(current,layout.x,Integer(grid,layout.grid_x));Integer(current,layout.y,Integer(grid,layout.grid_y));Integer(current,layout.index,-1);Integer(current,layout.layout,0);ptr(controller,layout.selected,current);};
 geometry(2);prepared();DesktopGridDispatch d{};
 auto tick=[&](){d={};return DesktopGridFrame(thread,heap,null,pool,grid,&d);};
 assert(!tick()&&journal.original==2&&DesktopGridObserved()==2);
 PrepareDesktopGridStorage();assert(!tick()); // No host mutation until journal is durable.
 allow_write=true;PrepareDesktopGridStorage();
 auto begin=[&](int target){assert(tick()==reinterpret_cast<uintptr_t>(base+layout.prepare));prepared();assert(tick()==reinterpret_cast<uintptr_t>(base+layout.change));assert(d.x1==controller&&d.x2==current&&d.x3==option&&d.x5==current);assert(Integer(option,layout.x)==DesktopGridColumns(target)&&Integer(option,layout.y)==DesktopGridRows(target));};
 auto rebuild=[&](){ptr(controller,layout.selected,option);assert(tick()==reinterpret_cast<uintptr_t>(base+layout.load));assert(stage==4&&d.x1==grid_controller&&d.x2==26&&d.x4==descriptor);};
 auto apply=[&](int target){geometry(target);assert(tick()==reinterpret_cast<uintptr_t>(base+layout.apply));assert(stage==5&&!migration_sent);PrepareDesktopGridStorage();assert(stage==5);assert(tick()==reinterpret_cast<uintptr_t>(base+layout.migrate));assert(d.x1==grid_controller&&d.x2==grid&&migration_sent&&DesktopGridReadbackDelay()==100);};
 begin(12);assert(Integer(current,layout.x)==4&&Integer(current,layout.y)==6);assert(!tick()&&stage==3);
 rebuild();assert(!tick()&&stage==4); // A completed preview does not refresh the cached real grid.
 apply(12);PrepareDesktopGridStorage();assert(stage==5); // Sending apply/migrate is not persistence.
 persisted_code=12;PrepareDesktopGridStorage();assert(stage==6&&DesktopGridReadbackDelay()==-1);
 SetDesktopGrid(9);assert(tick()==reinterpret_cast<uintptr_t>(base+layout.prepare));prepared();SetDesktopGrid(8);assert(tick()==reinterpret_cast<uintptr_t>(base+layout.change));assert(Integer(option,layout.y)==5&&transaction==9);rebuild();apply(9);persisted_code=9;PrepareDesktopGridStorage();assert(stage==1&&requested==8);
 begin(8);for(unsigned i=0;i<1201;++i)tick();assert(stage==1&&rolling_back);begin(9);rebuild();apply(9);PrepareDesktopGridStorage();assert(stage==7&&observed==9);
 SetDesktopGrid(0);begin(2);rebuild();apply(2);persisted_code=2;PrepareDesktopGridStorage();assert(stage==4&&!journal.active&&!DesktopGridGeometryActive());assert(tick()==reinterpret_cast<uintptr_t>(base+layout.load));assert(stage==0&&!stock_reload_pending);
 // Reusing an open 4x6 DB at 6x8 must not cause a false persistence rollback.
 journal.original=2;journal.active=1;storage_ready=true;geometry(12);boot_baseline=2;
 SetDesktopGrid(12);begin(12);assert(Integer(current,layout.x)==4&&Integer(current,layout.y)==6);
 rebuild();apply(12);persisted_code=12;PrepareDesktopGridStorage();assert(stage==6);
 prepared();assert(!tick()&&stage==6&&observed==12&&Integer(current,layout.y)==8);
 // A failed restoration terminates instead of repeatedly re-entering edit mode.
 rolling_back=true;previous=12;stage=5;migration_sent=true;persisted_code=2;readback_deadline=1;
 PrepareDesktopGridStorage();assert(stage==7&&!strcmp(reason,"restore_persistence_unconfirmed"));
 SetDesktopGrid(1);auto* patch=static_cast<uint8_t*>(hooks[0].target);const auto saved=*patch;*patch^=1;assert(!ApplyDesktopGridRule(nullptr));*patch=saved;assert(ApplyDesktopGridRule(nullptr));
 bad_journal=true;storage_read=false;PrepareDesktopGridStorage();assert(stage==7&&invalid_journal&&!journal.active);assert(!tick());
 free(memory);puts("desktop grid: real pipeline contract, corrupt/ambiguous targets, journal gate, preview/cache separation, load ABI, backend parameters, preference ACK independent of old DB suffix, cold reflow baseline, queued requests, restore and ownership passed");
}
