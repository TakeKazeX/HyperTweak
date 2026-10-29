// SPDX-License-Identifier: Apache-2.0
#include "icon_scale_patterns.inc"
namespace {
uint32_t IconWord(const Image& image,uintptr_t at) {uint32_t value=0;ReadInsn(image,at,&value);return value;}
uint32_t IconField(uint32_t word) {return (word>>12u)&0x1ffu;}
bool IconCall(const Image& image,uintptr_t at,uintptr_t* out) {
 const auto word=IconWord(image,at);
 return IsBl(word)&&DecodeBlTarget(at,word,out)&&Contains(image,*out,16,kFlagRead|kFlagExec);
}
#define ICON_FINDER(name) bool FindIcon##name(const Image& image,uintptr_t* at,uint32_t* count) {return FindUniqueBytes(image,kIcon##name,at,count);}
ICON_FINDER(Frame) ICON_FINDER(ProviderSet) ICON_FINDER(RxGet)
#undef ICON_FINDER
const SiteSpec kIconSites[]={
 {"frame",{},FindIconFrame,true,kIconFrame,0},
 {"provider_set",{},FindIconProviderSet,true,kIconProviderSet,0},
 {"rx_get",{},FindIconRxGet,true,kIconRxGet,0},
};
// A boxed Double allocation proves the CID by its header and floating payload,
// rather than carrying a class number from a reference snapshot.
uint32_t IconDoubleCid(const uint8_t* code,size_t size) {
 uint32_t cid=0;unsigned count=0;
 for(size_t at=0;at+16<=size;at+=4) {
  uint32_t w[4]{};memcpy(w,code+at,16);
  if((w[0]&0xffe00000u)!=0xd2800000u || (w[1]&0xffe00000u)!=0xf2a00000u ||
     (w[0]&31u)!=(w[1]&31u) || (w[2]&0xffe00c00u)!=0xf8000000u ||
     (w[2]&31u)!=(w[0]&31u) || (w[3]&0xffe00c00u)!=0xfc000000u ||
     ((w[2]>>5u)&31u)!=((w[3]>>5u)&31u) || IconField(w[2])!=511 || IconField(w[3])!=7) continue;
  const auto header=((w[0]>>5u)&0xffffu)|(((w[1]>>5u)&0xffffu)<<16u);
  if((header&0xfffu)!=0x15cu || !(header>>12u))return 0;
  cid=header>>12u;++count;
 }
 return count==1?cid:0;
}
}
bool DecodeIconScaleLayout(const Image& image,const uintptr_t* sites,IconScaleLayout* out,const uint8_t* owned_frame,const uint8_t* owned_scope) {
 if(!sites||!out)return false;
 for(unsigned i=0;i<3;++i) {
  if(i==0&&owned_frame) {
   if(!Contains(image,sites[0],kIconFrame.size,kFlagRead|kFlagExec))return false;
   for(size_t j=0;j<kIconFrame.size;++j)if((owned_frame[j]&kIconFrame.mask[j])!=(kIconFrame.bytes[j]&kIconFrame.mask[j]))return false;
  } else if(!MatchBytes(image,sites[i],kIconSites[i].verify,kFlagRead|kFlagExec))return false;
 }
 IconScaleLayout next{};const auto frame=sites[0],provider=sites[1],rx=sites[2];
 next.current=IconField(IconWord(image,provider+20));next.rx_value=IconField(IconWord(image,rx+60));
 next.double_cid=IconDoubleCid(image.base+provider,kIconProviderSet.size);if(!next.double_cid)return false;
 auto frame_word=[&](size_t at){uint32_t word=0;if(owned_frame)memcpy(&word,owned_frame+at,4);else word=IconWord(image,frame+at);return word;};
 unsigned returns=0;
 for(size_t i=16;i+16<=kIconFrame.size;i+=4)if(frame_word(i)==0xaa1603e0u&&frame_word(i+4)==0xaa1d03efu&&frame_word(i+8)==0xa8c179fdu&&frame_word(i+12)==0xd65f03c0u) {
  if(returns==2)return false;next.frame_returns[returns++]=frame+i;
 }
 if(returns!=2)return false;
 uintptr_t notify=0,active=0,current_grid=0,description=0,produce=0,size_calc=0,call=0;uint32_t count=0;
 if(!IconCall(image,provider+76,&notify)||!MatchBytes(image,notify,kIconNotifyHead,kFlagRead|kFlagExec)||
    !IconCall(image,notify+16,&active)||!MatchBytes(image,active,kIconActiveProvider,kFlagRead|kFlagExec)||
    !IconCall(image,active+96,&current_grid)||!MatchBytes(image,current_grid,kIconCurrentGrid,kFlagRead|kFlagExec)||
    !IconCall(image,current_grid+84,&call)||call!=rx||
    !FindUniqueBytes(image,kIconActualDescription,&description,&count)||
    !FindUniqueBytes(image,kIconActualProduce,&produce,&count)||
    !FindUniqueBytes(image,kIconSizeCalc,&size_calc,&count)||
    !IconCall(image,size_calc+28,&call)||call!=rx)return false;
 uint8_t rt=0,rn=0;uint32_t imm=0;
 if(!IsLdrImm(IconWord(image,active+20),&rt,&rn,&imm)||rt!=0||rn!=0)return false;
 next.controller_slot=imm*8u;
 if(!IsLdrImm(IconWord(image,current_grid+16),&rt,&rn,&imm)||rt!=0||rn!=0||imm*8u!=next.controller_slot||
    !IsLdrImm(IconWord(image,notify+44),&rt,&rn,&imm)||rt!=0||rn!=0||imm*8u!=next.controller_slot)return false;
 if(!IsLdrImm(IconWord(image,notify+128),&rt,&rn,&imm)||rt!=1||rn!=1||imm*8u!=next.controller_slot||
    (IconWord(image,notify+228)&0xffe00fffu)!=0xb8400001u||IconWord(image,notify+232)!=0x8b1c8021u||
    (IconWord(image,notify+236)&0xffe00fffu)!=0xb8400023u||IconWord(image,notify+240)!=0x8b1c8063u||
    (IconWord(image,notify+548)&0xffe00fffu)!=0xfc400000u)return false;
 next.active_rx=IconField(IconWord(image,current_grid+76));
 next.actual_grid_icon=IconField(IconWord(image,active+100));next.actual_provider=IconField(IconWord(image,active+108));
 next.actual_width=IconField(IconWord(image,notify+548));next.actual_size=IconField(IconWord(image,description+200));next.actual_padding=IconField(IconWord(image,description+296));
 if(next.actual_grid_icon!=IconField(IconWord(image,notify+228))||
    next.actual_provider!=IconField(IconWord(image,notify+236))||next.actual_provider!=IconField(IconWord(image,description+488))||
    next.actual_provider!=IconField(IconWord(image,produce+48))||next.actual_size!=IconField(IconWord(image,produce+24))||
    next.actual_padding!=IconField(IconWord(image,produce+32))||next.actual_width!=IconField(IconWord(image,size_calc+12))||
    next.current!=IconField(IconWord(image,size_calc+20)))return false;
 // Find the primitive clamp window by its roles, not a function-relative RVA.
 size_t clamp=0;unsigned clamps=0;
 for(size_t at=0;at+20<=kIconInitClamp.size;at+=4) {
  const auto* words=kIconInitClampWords+at/4;
  if(words[0]==0xfc407020u&&words[1]==0xfc407081u&&words[2]==0x4ee1f402u&&words[3]==0xfc407060u&&words[4]==0x4e62f401u){clamp=at;++clamps;}
 }
 if(clamps!=1)return false;
 uint8_t scope_masks[sizeof(kIconInitClampMasks)]{};memcpy(scope_masks,kIconInitClampMasks,sizeof(scope_masks));memset(scope_masks+clamp,0,16);
 const BytePattern search{kIconInitClamp.bytes,scope_masks,kIconInitClamp.size};
 if(!FindUniqueBytes(image,search,&next.scope_begin,&count))return false;
 uint8_t scope_code[sizeof(kIconInitClampWords)]{};
 if(owned_scope)memcpy(scope_code,owned_scope,sizeof(scope_code));else memcpy(scope_code,image.base+next.scope_begin,sizeof(scope_code));
 for(size_t at=0;at<sizeof(scope_code);++at)if((scope_code[at]&kIconInitClamp.mask[at])!=(kIconInitClamp.bytes[at]&kIconInitClamp.mask[at]))return false;
 if(!IconCall(image,next.scope_begin+212,&call)||call!=rx||!IconCall(image,next.scope_begin+236,&call)||call!=rx||
    next.current!=IconField(IconWord(image,next.scope_begin+60))||next.current!=IconField(IconWord(image,next.scope_begin+148))||next.current!=IconField(IconWord(image,next.scope_begin+352))||
    IconDoubleCid(scope_code,sizeof(scope_code))!=next.double_cid)return false;
 next.scope_clamp=next.scope_begin+clamp;
 const uint32_t fields[]={next.current,next.rx_value,next.active_rx,next.actual_grid_icon,next.actual_provider,next.actual_width,next.actual_size,next.actual_padding};
 for(auto field:fields)if(field<7||field>255||(field&3u)!=3u)return false;
 *out=next;return true;
}
const TargetSpec kIconScaleTarget={"desktop_icon_scale","libapp.so",kIconSites,3};
