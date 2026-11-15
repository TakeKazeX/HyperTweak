// SPDX-License-Identifier: Apache-2.0
#include "../../main/cpp/NativeRules/rules/icon_theme_rule.cpp"
#include <cassert>
#include <fstream>
#include <iterator>
#include <vector>
using namespace hypertweak::native;
namespace {std::vector<uint8_t> bytes;dart::TargetResult result{};bool prepared=true,empty_backup=false;unsigned installs=0,fail_stage=0;}
extern "C" void HyperTweakIconThemeSupportHook(){}
extern "C" void HyperTweakIconThemeRefreshHook(){}
namespace hypertweak::native {
void LogInfo(const char*,...){}
bool ResolveDartSites(void*,const dart::TargetSpec& spec,DartResolution* out){
 *out={};out->base=bytes.data();out->reason="pending";if(!prepared)return false;
 out->site_count=spec.site_count;
 for(unsigned i=0;i<spec.site_count;i++)out->sites[i]={spec.sites[i].name,reinterpret_cast<uintptr_t>(bytes.data())+result.sites[i].offset,true,spec.sites[i].verify,0};
 return result.resolved;
}
uintptr_t DartResolution::Find(const char* name)const{for(unsigned i=0;i<site_count;i++)if(!strcmp(name,sites[i].name))return sites[i].address;return 0;}
bool DartResolution::MatchVerify(const char* name)const{for(unsigned i=0;i<site_count;i++)if(!strcmp(name,sites[i].name))return dart::MatchPatternAtAddress(sites[i].address,sites[i].verify);return false;}
}
int InstallInlineHook(void* at,void*,void** backup){if(++installs==fail_stage)return 1;memset(at,0xa5,16);*backup=empty_backup?nullptr:reinterpret_cast<void*>(1);return 0;}
int RemoveInlineHook(void* at){for(auto& h:hooks)if(h.address==at){memcpy(at,h.original,16);return 0;}return 1;}
int main(int argc,char** argv){
 assert(argc==2);std::ifstream file(argv[1],std::ios::binary);bytes.assign(std::istreambuf_iterator<char>(file),{});
 dart::Image image{};assert(dart::ParseImage(bytes.data(),&image));result=dart::ResolveTarget(image,dart::kIconThemeTarget);assert(result.resolved&&result.verified);
 const auto clean=bytes;
 // Corrupt a required SDK/comparison contract and a repeated getter call relationship.
 for(unsigned site=0;site<4;site++){
  bytes=clean;auto at=result.sites[site].offset;bytes[at]^=0x40;auto rejected=dart::ResolveTarget(image,dart::kIconThemeTarget);if(rejected.resolved)fprintf(stderr,"corrupt site retained: %s\n",result.sites[site].name);assert(!rejected.resolved);
 }
 bytes=clean;
 auto functionStart=[&](uintptr_t at){for(unsigned i=0;i<256&&at>=4;i++,at-=4)if(dart::IsDartPrologue(image,at))return at;assert(false);return uintptr_t{};};
 auto functionEnd=[&](uintptr_t at){for(uintptr_t pc=at+8;pc<at+1024;pc+=4)if(dart::IsDartPrologue(image,pc))return pc;assert(false);return uintptr_t{};};
 const auto refresh_base=functionStart(result.sites[1].offset);
 // A predecessor must never enter the ADD/BR interior of a 16-byte patch.
 // Retarget a real native TBZ without changing its condition or register.
 bool interior_tested=false;
 for(uintptr_t pc=refresh_base;pc<result.sites[1].offset+16;pc+=4){
  uint32_t word=0;memcpy(&word,bytes.data()+pc,4);
  if((word&0x7e000000u)!=0x36000000u)continue;
  const auto target=static_cast<uintptr_t>(static_cast<int64_t>(pc)+(static_cast<int32_t>(word<<13u)>>16u));
  if(target!=result.sites[1].offset&&target!=result.sites[1].offset+16)continue;
  auto changed=(word&~(0x3fffu<<5u))|((static_cast<uint32_t>((static_cast<int64_t>(result.sites[1].offset+4)-static_cast<int64_t>(pc))/4)&0x3fffu)<<5u);
  memcpy(bytes.data()+pc,&changed,4);assert(!dart::ResolveTarget(image,dart::kIconThemeTarget).resolved);bytes=clean;interior_tested=true;break;
 }
 assert(interior_tested);
 // Break one repeated Rx getter edge while keeping a valid executable BL.
 std::vector<uintptr_t> getters;
 for(uintptr_t pc=refresh_base;pc<result.sites[1].offset;pc+=4){uint32_t word=0;assert(dart::ReadInsn(image,pc,&word));if(!dart::IsBl(word))continue;
  uintptr_t target=0;assert(dart::DecodeBlTarget(pc,word,&target));unsigned count=0;
  for(uintptr_t other=refresh_base;other<result.sites[1].offset;other+=4){uint32_t next=0;uintptr_t to=0;assert(dart::ReadInsn(image,other,&next));if(dart::IsBl(next)&&dart::DecodeBlTarget(other,next,&to)&&to==target)++count;}
  if(count==4)getters.push_back(pc);
 }
 assert(getters.size()==4);uint32_t call=0;memcpy(&call,bytes.data()+getters[1],4);++call;memcpy(bytes.data()+getters[1],&call,4);
 assert(!dart::ResolveTarget(image,dart::kIconThemeTarget).resolved);bytes=clean;
 const auto support_base=functionStart(result.sites[0].offset),support_end=functionEnd(support_base);
 const size_t span=support_end-support_base;uintptr_t duplicate=0;
 for(auto segment:image.loads)if((segment.flags&dart::kFlagExec)&&segment.end-segment.start>8192){duplicate=segment.end-4096;break;}
 assert(duplicate&&span<2048);
 auto relocate=[&]{
  // Preserve the following function boundary used by incoming-edge analysis.
  memcpy(bytes.data()+duplicate,bytes.data()+support_base,span+8);
  for(size_t i=0;i<span;i+=4){
   uint32_t word=0;memcpy(&word,bytes.data()+support_base+i,4);uintptr_t target=0;unsigned bits=0;
   if((word&0x7c000000u)==0x14000000u){assert(dart::DecodeBlTarget(support_base+i,word|0x80000000u,&target));bits=26;}
   else if((word&0xff000010u)==0x54000000u){assert(dart::DecodeTestBranchTarget(support_base+i,word,&target));bits=19;}
   else if((word&0x7e000000u)==0x34000000u){target=static_cast<uintptr_t>(static_cast<int64_t>(support_base+i)+(static_cast<int32_t>(word<<8u)>>11u));bits=19;}
   else if((word&0x7e000000u)==0x36000000u){target=static_cast<uintptr_t>(static_cast<int64_t>(support_base+i)+(static_cast<int32_t>(word<<13u)>>16u));bits=14;}
   if(!bits)continue;
   if(target>=support_base&&target<support_end)target=duplicate+target-support_base;
   auto delta=static_cast<int64_t>(target)-static_cast<int64_t>(duplicate+i);
   assert(delta%4==0);auto encoded=static_cast<uint32_t>(delta/4);
   if(bits==26)word=(word&0xfc000000u)|(encoded&0x3ffffffu);
   else {auto mask=((1u<<bits)-1u)<<5u;word=(word&~mask)|((encoded<<5u)&mask);}
   memcpy(bytes.data()+duplicate+i,&word,4);
  }
 };
 // A copied connected SDK/region family is ambiguous, even at another address.
 relocate();assert(!dart::ResolveTarget(image,dart::kIconThemeTarget).resolved);bytes=clean;
 relocate();memset(bytes.data()+support_base,0,span);
 auto moved=dart::ResolveTarget(image,dart::kIconThemeTarget);assert(moved.resolved&&moved.sites[0].offset==duplicate+result.sites[0].offset-support_base);bytes=clean;
 // Derive changed static slots and guard fields from operands.
 dart::IconThemeObserverLayout decoded{};assert(dart::DecodeIconThemeObserver(image,result.sites[2].offset,result.sites[3].offset,result.sites[1].offset,&decoded));
 bool changed_slot=false;
 for(uintptr_t pc=result.sites[2].offset;pc<functionEnd(result.sites[2].offset);pc+=4){uint32_t word=0;memcpy(&word,bytes.data()+pc,4);
  if((word&0xffc003ffu)==0xf9000001u&&((word>>10u)&4095u)*8u==decoded.static_slot){word+=1u<<10u;memcpy(bytes.data()+pc,&word,4);changed_slot=true;}
 }
 assert(changed_slot);dart::IconThemeObserverLayout changed{};assert(dart::DecodeIconThemeObserver(image,result.sites[2].offset,result.sites[3].offset,result.sites[1].offset,&changed));assert(changed.static_slot==decoded.static_slot+8);bytes=clean;
 unsigned flags=0;
 for(uintptr_t pc=decoded.registration;pc<functionEnd(decoded.registration);pc+=4){uint32_t word=0;memcpy(&word,bytes.data()+pc,4);
  if(((word&0xffe00fffu)==0xb8400001u||(word&0xffe00fffu)==0xb8000001u)&&((word>>12u)&511u)==decoded.registered_field){word+=4u<<12u;memcpy(bytes.data()+pc,&word,4);++flags;if(flags==1)assert(!dart::DecodeIconThemeObserver(image,result.sites[2].offset,result.sites[3].offset,result.sites[1].offset,&changed));}
 }
 assert(flags==2);assert(dart::DecodeIconThemeObserver(image,result.sites[2].offset,result.sites[3].offset,result.sites[1].offset,&changed));assert(changed.registered_field==decoded.registered_field+4);
 assert(dart::ResolveTarget(image,dart::kIconThemeTarget).resolved);bytes=clean;
 SetIconTheme(3);prepared=false;assert(!ApplyIconThemeRule(nullptr));prepared=true;
 empty_backup=true;assert(!ApplyIconThemeRule(nullptr));assert(!hooks[0].address&&!hooks[1].address);empty_backup=false;
 fail_stage=installs+2;assert(!ApplyIconThemeRule(nullptr));assert(!hooks[0].address&&!hooks[1].address);
 fail_stage=0;assert(ApplyIconThemeRule(nullptr));assert(g_icon_theme_unlock&&g_icon_theme_refresh);

 // Adopt an already-created controller once, on its original scheduler boundary.
 assert(observer.static_slot&&observer.cid&&observer.registered_field);
 std::vector<uint8_t> statics(observer.static_slot+8),object(256);
 const uintptr_t value=reinterpret_cast<uintptr_t>(object.data())+1,null=0x20000001u,sentinel=null+0x100;
 uint32_t header=observer.cid<<12u,flag=static_cast<uint32_t>(null+0x30);
 memcpy(object.data(),&header,4);memcpy(reinterpret_cast<void*>(value+observer.registered_field),&flag,4);
 memcpy(statics.data()+observer.static_slot,&value,8);uintptr_t receiver=0;
 auto frame=[&]{return IconThemeObserverFrame(reinterpret_cast<uintptr_t>(statics.data()),null,sentinel,&receiver);};
 assert(frame()==reinterpret_cast<uintptr_t>(bytes.data())+observer.registration&&receiver==value);
 assert(!frame()); // Do not launch another async registration while one is pending.
 flag=static_cast<uint32_t>(null+0x20);memcpy(reinterpret_cast<void*>(value+observer.registered_field),&flag,4);assert(!frame());
 flag=static_cast<uint32_t>(null+0x30);memcpy(reinterpret_cast<void*>(value+observer.registered_field),&flag,4);assert(frame());
 SetIconTheme(0);assert(!frame());SetIconTheme(3);
 header^=1u<<12u;memcpy(object.data(),&header,4);assert(!frame());header^=1u<<12u;memcpy(object.data(),&header,4);
 memcpy(statics.data()+observer.static_slot,&null,8);assert(!frame());memcpy(statics.data()+observer.static_slot,&value,8);
 assert(frame());
 // Cached metadata must also validate registration code and its decoded flag relationship.
 auto registration=observer.registration;auto registration_byte=bytes[registration];bytes[registration]^=1;
 assert(!ApplyIconThemeRule(nullptr));bytes[registration]=registration_byte;assert(ApplyIconThemeRule(nullptr));
 uint32_t uri_call=0;memcpy(&uri_call,bytes.data()+registration+99u*4u,4);
 auto changed_call=uri_call+1;memcpy(bytes.data()+registration+99u*4u,&changed_call,4);
 assert(!ApplyIconThemeRule(nullptr));memcpy(bytes.data()+registration+99u*4u,&uri_call,4);assert(ApplyIconThemeRule(nullptr));
 auto evidence=bytes[support_base];bytes[support_base]^=1;
 assert(!ApplyIconThemeRule(nullptr)&&!g_icon_theme_unlock);bytes[support_base]=evidence;
 assert(ApplyIconThemeRule(nullptr));
 auto count=installs;assert(ApplyIconThemeRule(nullptr)&&installs==count);
 SetIconTheme(0);assert(ApplyIconThemeRule(nullptr)&&!g_icon_theme_unlock&&!g_icon_theme_refresh);
 SetIconTheme(1);assert(ApplyIconThemeRule(nullptr)&&g_icon_theme_unlock&&!g_icon_theme_refresh);
 ResetIconThemeAfterFork();assert(!g_icon_theme_unlock);assert(ApplyIconThemeRule(nullptr));
 memcpy(hooks[0].address,hooks[0].original,16);assert(ApplyIconThemeRule(nullptr));
 auto address=static_cast<uint8_t*>(hooks[1].address);auto saved=*address;*address=0x42;assert(!ApplyIconThemeRule(nullptr)&&!g_icon_theme_unlock);*address=saved;assert(ApplyIconThemeRule(nullptr));
 SetIconTheme(4);assert(ApplyIconThemeRule(nullptr)&&g_icon_theme_unlock&&g_icon_theme_refresh);
 puts("icon theme: matching targets, required/call corruption, ambiguity, partial/null rollback, passive disable, fork/remap and foreign ownership passed");
}
