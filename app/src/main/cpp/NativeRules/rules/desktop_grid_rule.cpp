// SPDX-License-Identifier: Apache-2.0
#include "desktop_grid_rule.h"
#include "dart_rule_support.h"
#include "native_store.h"
#include "native_rule_runtime.h"
#include "lsposed_hook_backend.h"
#include "logging.h"
#include <pthread.h>
#include <cstring>
#include <cstdio>
#include <time.h>
#include <unistd.h>
extern "C" {
__attribute__((visibility("hidden"))) uint32_t hypertweak_grid_geometry=0;
__attribute__((visibility("hidden"))) uintptr_t hypertweak_grid_handler_continue=0,hypertweak_grid_variable_continue=0,hypertweak_grid_fixed_entry=0;
__attribute__((visibility("hidden"))) uintptr_t hypertweak_grid_rows_continue=0,hypertweak_grid_rows_null=0,hypertweak_grid_clamp_continue=0;
__attribute__((visibility("hidden"))) uint32_t hypertweak_grid_context_x=0,hypertweak_grid_context_y=0,hypertweak_grid_input_rows=0,hypertweak_grid_maximum_field=0,hypertweak_grid_unset_field=0;
void HyperTweakDesktopGridHandlerHook();void HyperTweakDesktopGridVariableHook();void HyperTweakDesktopGridRowsHook();void HyperTweakDesktopGridClampHook();
}
namespace hypertweak::native {
namespace {
pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;
struct Unlock {~Unlock(){pthread_mutex_unlock(&lock);}};
dart::DesktopGridLayout layout{},prepared_layout{};
uint8_t* base=nullptr;uint8_t* prepared_base=nullptr;
struct Hook {void* target=nullptr;uint8_t original[16]{},patch[16]{};} hooks[4];
uint8_t prepared_original[4][16]{};
struct Journal {uint32_t schema=1,original=0,active=0,checksum=0;} journal{};
bool metadata_ready=false,ready=false,storage_read=false,storage_ready=false,storage_queued=false,invalid_journal=false;
bool rolling_back=false,migration_sent=false,stock_reload_pending=false;
unsigned storage_failures=0;
int requested=0,transaction=0,previous=0,observed=0,stage=0,frames=0,boot_baseline=0;
uint64_t readback_deadline=0;
const char* reason="disabled";
uint64_t Millis(){timespec t{};clock_gettime(CLOCK_MONOTONIC,&t);return static_cast<uint64_t>(t.tv_sec)*1000u+static_cast<uint64_t>(t.tv_nsec)/1000000u;}
uintptr_t Field(uintptr_t object,uint32_t off,uintptr_t heap){uint32_t v=0;memcpy(&v,reinterpret_cast<void*>(object+off),4);return v&1?(heap<<32)+v:static_cast<uintptr_t>(static_cast<int64_t>(static_cast<int32_t>(v)));}
bool Object(uintptr_t p,uintptr_t null,uintptr_t sentinel){return (p&1)&&p!=null&&p!=sentinel;}
int64_t Integer(uintptr_t p,uint32_t off){int64_t v=0;memcpy(&v,reinterpret_cast<void*>(p+off),8);return v;}
void Integer(uintptr_t p,uint32_t off,int64_t v){memcpy(reinterpret_cast<void*>(p+off),&v,8);}
bool Cell(uintptr_t p,uintptr_t null,uintptr_t sentinel){if(!Object(p,null,sentinel))return false;uint32_t h=0;memcpy(&h,reinterpret_cast<void*>(p-1),4);return ((h>>12)&0xfffff)==layout.cell_cid;}
void Geometry(int code){__atomic_store_n(&hypertweak_grid_geometry,static_cast<uint32_t>(DesktopGridColumns(code)|(DesktopGridRows(code)<<8)),__ATOMIC_RELEASE);}
uint32_t Checksum(const Journal& j){return 0x48544731u^j.schema^(j.original<<8)^(j.active<<16);}
int Target(){return rolling_back?previous:transaction?transaction:static_cast<int>(journal.original);}
void Queue(int code){transaction=code;stage=code==0&&!journal.active?0:1;frames=0;rolling_back=false;migration_sent=false;readback_deadline=0;reason="queued";}
void Restore(const char* why){rolling_back=true;stage=1;frames=0;migration_sent=false;readback_deadline=0;reason=why;}
int ReadHostGrid(){
 char path[160];snprintf(path,sizeof(path),"/data/user_de/%u/com.miui.home/shared_prefs/launcher_sharedpreference.xml",getuid()/100000u);
 FILE* f=fopen(path,"re");if(!f)return 0;
 char xml[65536];const auto n=fread(xml,1,sizeof(xml)-1,f);const bool complete=feof(f)&&!ferror(f);fclose(f);if(!complete)return 0;xml[n]=0;
 const char* xp=strstr(xml,"name=\"pref_key_cell_x\" value=\"");const char* yp=strstr(xml,"name=\"pref_key_cell_y\" value=\"");int x=0,y=0;
 if(!xp||!yp||sscanf(xp,"name=\"pref_key_cell_x\" value=\"%d\"",&x)!=1||sscanf(yp,"name=\"pref_key_cell_y\" value=\"%d\"",&y)!=1)return 0;
 // desktop_cell_count_in_db names the database selected at load time. Stock
 // preview/apply keeps updating that open DB; its suffix is not current geometry.
 return DesktopGridCode(x,y);
}
bool Persisted(int code){return ReadHostGrid()==code;}

bool Owned(const Hook& hook){return !hook.target||!memcmp(hook.target,hook.patch,16)||!memcmp(hook.target,hook.original,16);}
bool Intact(){if(!ready)return false;for(const auto& h:hooks)if(!h.target||memcmp(h.target,h.patch,16))return false;return true;}
}
int DesktopGridRequested(){pthread_mutex_lock(&lock);Unlock unlock;return requested;}
int DesktopGridStage(){pthread_mutex_lock(&lock);Unlock unlock;return stage;}
int DesktopGridObserved(){pthread_mutex_lock(&lock);Unlock unlock;return observed;}
const char* DesktopGridReason(){pthread_mutex_lock(&lock);Unlock unlock;return reason;}
bool DesktopGridGeometryActive(){return __atomic_load_n(&hypertweak_grid_geometry,__ATOMIC_ACQUIRE)!=0;}
bool DesktopGridNeedsPreparation(){pthread_mutex_lock(&lock);Unlock unlock;return requested!=0||stage!=0;}
int DesktopGridReadbackDelay(){pthread_mutex_lock(&lock);Unlock unlock;return stage==5&&migration_sent?100:-1;}
void SetDesktopGrid(int code){if(!ValidDesktopGrid(code))return;pthread_mutex_lock(&lock);Unlock unlock;if(requested==code)return;requested=code;if(invalid_journal){stage=7;reason="invalid_restore_journal";}else if(stage>=2&&stage<=5)reason="next_layout_queued";else Queue(code);}
void PrepareDesktopGridStorage(){
 pthread_mutex_lock(&lock);Unlock unlock;
 if(!storage_read){storage_read=true;boot_baseline=ReadHostGrid();Journal old{};if(ReadNativeRecord("desktop_grid",&old,sizeof(old))){if(old.schema!=1||old.original<1||old.original>12||old.active>1||old.checksum!=Checksum(old)){invalid_journal=true;journal={};Geometry(0);stage=7;reason="invalid_restore_journal";}else{journal=old;if(journal.active){Geometry(requested?requested:static_cast<int>(journal.original));if(!requested)Queue(0);}}}}
 if(invalid_journal)return;
 if(journal.active&&!storage_ready){journal.checksum=Checksum(journal);storage_ready=WriteNativeRecord("desktop_grid",&journal,sizeof(journal));storage_queued=false;if(!storage_ready){++storage_failures;reason="restore_journal_failed";}}
 if(stage==5&&migration_sent){
  if(observed==Target()&&Persisted(Target())){
   readback_deadline=0;
   if(rolling_back){stage=7;reason="previous_layout_restored";}
   else if(requested!=transaction)Queue(requested);
   else if(transaction){stage=6;reason="host_persisted";}
   else{Geometry(0);journal={};storage_ready=false;DeleteNativeRecord("desktop_grid");stock_reload_pending=true;stage=4;reason="restoring_stock_pipeline";}
  }else if(readback_deadline&&Millis()>=readback_deadline){if(rolling_back){stage=7;reason="restore_persistence_unconfirmed";}else Restore("persistence_unconfirmed_restoring");}
 }
 static int last_stage=-1,last_requested=-1,last_observed=-1;
 if(stage!=last_stage||requested!=last_requested||observed!=last_observed){last_stage=stage;last_requested=requested;last_observed=observed;LogInfo("desktop grid stage=%d request=%d observed=%d reason=%s",stage,requested,observed,reason);}
}
bool PrepareDesktopGridLayout(void* handle){
 pthread_mutex_lock(&lock);Unlock unlock;
 DartResolution r{};if(!ResolveDartSites(handle,dart::kDesktopGridTarget,&r)){reason=r.reason;return false;}
 if(metadata_ready&&prepared_base==r.base&&base==r.base&&Intact())return true;
 metadata_ready=false;
 for(const auto& h:hooks)if(h.target&&(base!=r.base||!Owned(h))){reason="grid_foreign_or_remapped_patch";return false;}
 dart::Image image{};if(!dart::ParseImage(r.base,&image))return false;
 uintptr_t sites[8]{};const char* names[]={"to","prepare","create","change","apply","variable","fixed","lookup"};
 for(unsigned i=0;i<8;++i){const auto address=r.Find(names[i]);if(address<reinterpret_cast<uintptr_t>(r.base))return false;sites[i]=address-reinterpret_cast<uintptr_t>(r.base);}
 dart::DesktopGridLayout next{};
 if(!dart::DecodeDesktopGridLayout(image,sites,&next,hooks[0].target?hooks[0].original:nullptr,hooks[1].target?hooks[1].original:nullptr,hooks[2].target?hooks[2].original:nullptr,hooks[3].target?hooks[3].original:nullptr)){reason="grid_pipeline_contract_rejected";LogWarn("desktop grid pipeline metadata rejected");return false;}
 const uintptr_t points[]={next.handler,next.variable,next.rows_window,next.clamp};
 for(unsigned i=0;i<4;++i){if(hooks[i].target&&hooks[i].target!=r.base+points[i]){reason="grid_owned_site_changed";return false;}memcpy(prepared_original[i],hooks[i].target?hooks[i].original:r.base+points[i],16);}
 prepared_layout=next;prepared_base=r.base;metadata_ready=true;return true;
}
bool ApplyDesktopGridRule(void* handle){
 if(!DesktopGridNeedsPreparation())return true;
 if(pthread_mutex_trylock(&lock))return false;Unlock unlock;
 DartResolution r{};if(!ResolveDartSites(handle,dart::kDesktopGridTarget,&r)){reason=r.reason;return false;}
 if(base==r.base&&Intact())return true;
 ready=false;if(!metadata_ready||prepared_base!=r.base){reason="grid_metadata_pending";return false;}
 const auto next=prepared_layout;const uintptr_t points[]={next.handler,next.variable,next.rows_window,next.clamp};
 for(unsigned i=0;i<4;++i)if(hooks[i].target&&(base!=r.base||hooks[i].target!=r.base+points[i]||!Owned(hooks[i]))){reason="grid_foreign_patch";Geometry(0);return false;}
 for(auto& h:hooks)if(h.target){if(RemoveInlineHook(h.target)!=kHookSuccess){reason="grid_retirement_failed";return false;}h={};}
 for(unsigned i=0;i<4;++i)if(memcmp(r.base+points[i],prepared_original[i],16)){reason="grid_prepared_window_changed";return false;}
 base=r.base;layout=next;
 __atomic_store_n(&hypertweak_grid_handler_continue,reinterpret_cast<uintptr_t>(base+layout.handler+16),__ATOMIC_RELEASE);
 __atomic_store_n(&hypertweak_grid_variable_continue,reinterpret_cast<uintptr_t>(base+layout.variable+16),__ATOMIC_RELEASE);
 __atomic_store_n(&hypertweak_grid_fixed_entry,reinterpret_cast<uintptr_t>(base+layout.fixed),__ATOMIC_RELEASE);
 __atomic_store_n(&hypertweak_grid_rows_continue,reinterpret_cast<uintptr_t>(base+layout.rows_window+16),__ATOMIC_RELEASE);
 __atomic_store_n(&hypertweak_grid_rows_null,reinterpret_cast<uintptr_t>(base+layout.rows_null),__ATOMIC_RELEASE);
 __atomic_store_n(&hypertweak_grid_clamp_continue,reinterpret_cast<uintptr_t>(base+layout.clamp+16),__ATOMIC_RELEASE);
 __atomic_store_n(&hypertweak_grid_context_x,layout.context_x,__ATOMIC_RELEASE);__atomic_store_n(&hypertweak_grid_context_y,layout.context_y,__ATOMIC_RELEASE);__atomic_store_n(&hypertweak_grid_input_rows,layout.input_rows,__ATOMIC_RELEASE);
 __atomic_store_n(&hypertweak_grid_maximum_field,layout.maximum_field,__ATOMIC_RELEASE);__atomic_store_n(&hypertweak_grid_unset_field,layout.unset_field,__ATOMIC_RELEASE);
 void* shims[]={reinterpret_cast<void*>(HyperTweakDesktopGridHandlerHook),reinterpret_cast<void*>(HyperTweakDesktopGridVariableHook),reinterpret_cast<void*>(HyperTweakDesktopGridRowsHook),reinterpret_cast<void*>(HyperTweakDesktopGridClampHook)};
 for(unsigned i=0;i<4;++i){auto& h=hooks[i];void* target=base+points[i];memcpy(h.original,target,16);void* backup=nullptr;const auto result=InstallInlineHook(target,shims[i],&backup);if(result==kHookSuccess){h.target=target;memcpy(h.patch,target,16);}if(result!=kHookSuccess||!backup){for(auto& owned:hooks)if(owned.target&&RemoveInlineHook(owned.target)==kHookSuccess)owned={};reason="grid_install_failed";return false;}}
 ready=true;reason="waiting_launcher_frame";LogInfo("desktop grid ComputePipeline hooks installed");return true;
}
uintptr_t DesktopGridFrame(uintptr_t thread,uintptr_t heap,uintptr_t null,uintptr_t pool,uintptr_t grid,DesktopGridDispatch* out){
 if(!out||pthread_mutex_trylock(&lock))return 0;Unlock unlock;*out={};
 if(!ready||!grid||invalid_journal)return 0;
 observed=DesktopGridCode(static_cast<int>(Integer(grid,layout.grid_x)),static_cast<int>(Integer(grid,layout.grid_y)));
 if(stage==0||stage==6||stage==7)return 0;
 if((stage==2||stage==3||stage==4)&&!stock_reload_pending&&++frames>1200){Restore("host_future_timeout_restoring");return 0;}
 uintptr_t statics=0,sentinel=0,controller=0,grid_controller=0;memcpy(&statics,reinterpret_cast<void*>(thread+0x78),8);memcpy(&sentinel,reinterpret_cast<void*>(pool+0x40),8);if(!statics)return 0;
 memcpy(&controller,reinterpret_cast<void*>(statics+layout.controller_slot),8);memcpy(&grid_controller,reinterpret_cast<void*>(statics+layout.grid_controller_slot),8);
 if(!Object(controller,null,sentinel)){out->target=reinterpret_cast<uintptr_t>(base+layout.to);return out->target;}
 if(!Object(grid_controller,null,sentinel))return 0;
 auto reload=[&](){out->x1=grid_controller;out->x2=26;memcpy(&out->x4,reinterpret_cast<void*>(pool+layout.load_descriptor),8);out->target=reinterpret_cast<uintptr_t>(base+layout.load);return out->target;};
 if(stock_reload_pending){stock_reload_pending=false;stage=0;reason="original_layout_restored";return reload();}
 if(!journal.active){if(!observed){stage=7;reason="unsupported_original_grid";return 0;}journal.original=observed;journal.active=1;storage_ready=false;storage_queued=TryNotifyDesktopGridPreparation();reason="waiting_restore_journal";return 0;}
 if(!storage_ready){if(storage_failures>=3){stage=7;reason="restore_journal_unavailable";}else if(!storage_queued)storage_queued=TryNotifyDesktopGridPreparation();return 0;}
 const int target=Target();if(!target)return 0;
 if(stage==1){if(!rolling_back)previous=boot_baseline?boot_baseline:observed;boot_baseline=0;Geometry(target);out->x1=controller;out->target=reinterpret_cast<uintptr_t>(base+layout.prepare);stage=2;frames=0;return out->target;}
 if(stage==4){if(observed!=target)return 0;out->x1=controller;out->target=reinterpret_cast<uintptr_t>(base+layout.apply);stage=5;migration_sent=false;frames=0;reason="waiting_native_apply";return out->target;}
 if(stage==5){if(!migration_sent&&observed==target){out->x1=grid_controller;out->x2=grid;out->target=reinterpret_cast<uintptr_t>(base+layout.migrate);migration_sent=true;readback_deadline=Millis()+10000u;(void)TryNotifyDesktopGridPreparation();reason="waiting_host_persistence";return out->target;}return 0;}
 const auto current=Field(controller,layout.current,heap),selected=Field(controller,layout.selected,heap);if(!Cell(current,null,sentinel)||!Cell(selected,null,sentinel))return 0;
 if(stage==2){
  const auto list=Field(controller,layout.options,heap);if(!Object(list,null,sentinel))return 0;
  const auto length=Field(list,layout.list_length,heap);if((length&1)||length<2||length>64)return 0;
  const auto array=Field(list,layout.list_data,heap);if(!Object(array,null,sentinel))return 0;
  uintptr_t candidate=0;for(uintptr_t i=0;i<length/2;++i){const auto cell=Field(array,layout.array_data+static_cast<uint32_t>(i*4),heap);if(Cell(cell,null,sentinel)&&cell!=current&&cell!=selected){candidate=cell;break;}}
  if(!candidate)return 0;
  // On a cold load the override may already have built the target grid while
  // the open database still contains the previous layout. Reflow from its
  // captured preference counts, rather than treating that change as a no-op.
  if(previous){Integer(current,layout.x,DesktopGridColumns(previous));Integer(current,layout.y,DesktopGridRows(previous));}
  Integer(candidate,layout.x,DesktopGridColumns(target));Integer(candidate,layout.y,DesktopGridRows(target));Integer(candidate,layout.index,-1);Integer(candidate,layout.layout,Integer(current,layout.layout));
  out->x1=controller;out->x2=current;out->x3=candidate;out->x5=current;out->target=reinterpret_cast<uintptr_t>(base+layout.change);stage=3;frames=0;reason="waiting_native_preview";return out->target;
 }
 if(stage==3&&selected!=current&&DesktopGridCode(static_cast<int>(Integer(selected,layout.x)),static_cast<int>(Integer(selected,layout.y)))==target){stage=4;frames=0;reason="rebuilding_compute_pipeline";return reload();}
 return 0;
}
void ResetDesktopGridAfterFork(){pthread_mutex_t fresh=PTHREAD_MUTEX_INITIALIZER;lock=fresh;ready=false;metadata_ready=false;storage_read=false;storage_ready=false;storage_queued=false;storage_failures=0;invalid_journal=false;migration_sent=false;stock_reload_pending=false;readback_deadline=0;boot_baseline=0;stage=requested?1:0;transaction=requested;observed=0;frames=0;rolling_back=false;Geometry(0);}
}
