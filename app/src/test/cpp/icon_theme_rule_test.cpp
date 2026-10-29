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
  bytes=clean;auto at=result.sites[site].offset;bytes[at]^=0x40;assert(!dart::ResolveTarget(image,dart::kIconThemeTarget).resolved);
 }
 bytes=clean;
 const auto refresh_base=result.sites[1].offset-35u*4u;
 uint32_t call=0;memcpy(&call,bytes.data()+refresh_base+13u*4u,4);call+=1;memcpy(bytes.data()+refresh_base+13u*4u,&call,4);
 assert(!dart::ResolveTarget(image,dart::kIconThemeTarget).resolved);bytes=clean;
 // Duplicate the full relation into executable padding: ambiguity must reject.
 const auto support_base=result.sites[0].offset-36u*4u;const size_t span=57u*4u;
 uintptr_t duplicate=0;
 for(auto segment:image.loads)if((segment.flags&dart::kFlagExec)&&segment.end-segment.start>4096){duplicate=segment.end-1024;break;}
 assert(duplicate);memcpy(bytes.data()+duplicate,bytes.data()+support_base,span);
 assert(!dart::ResolveTarget(image,dart::kIconThemeTarget).resolved);bytes=clean;
 // Relocate the entire candidate and retain all original call destinations.
 memcpy(bytes.data()+duplicate,bytes.data()+support_base,span);
 for(size_t i=0;i<span;i+=4){uint32_t word=0;memcpy(&word,bytes.data()+support_base+i,4);
  if(dart::IsBl(word)){uintptr_t target=0;assert(dart::DecodeBlTarget(support_base+i,word,&target));
   const auto delta=static_cast<int64_t>(target)-static_cast<int64_t>(duplicate+i);
   word=0x94000000u|(static_cast<uint32_t>(delta/4)&0x3ffffffu);memcpy(bytes.data()+duplicate+i,&word,4);}
 }
 memset(bytes.data()+support_base,0,span);
 auto moved=dart::ResolveTarget(image,dart::kIconThemeTarget);assert(moved.resolved&&moved.sites[0].offset==duplicate+36u*4u);bytes=clean;
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
