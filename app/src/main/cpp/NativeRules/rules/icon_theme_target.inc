// SPDX-License-Identifier: Apache-2.0
// SDK gate -> local international result -> async return; Settings change comparator.
namespace {
constexpr uint32_t kIconThemeSupportWords[] = {
0x93417c20u,0x36000041u,0xf8407020u,0xf100841fu,0x5400026au,0xf9403f40u,0xf9529000u,0xf9402370u,0x6b10001fu,0x54000061u,0xf9403362u,0x9402fe7fu,0xf85b03a2u,0x9141ff61u,0xf943d021u,0xf81a83a0u,0x940302f5u,0xf85a83a1u,0xaa0003e2u,0xf9403b64u,0x97b69a56u,0x9100c2c0u,0x17f818f0u,0xf9403f40u,0xf9598800u,0xf9402370u,0x6b10001fu,0x54000061u,0xf97bd762u,0x9402fe6du,0xaa0003e1u,0xf81a83a0u,0x97b862dcu,0xf85a83a0u,0xb8423001u,0x8b1c8021u,0xf85b03a2u,0xb8013041u,0x36200261u,0xf9403f40u,0xf9529000u,0xf9402370u,0x6b10001fu,0x54000061u,0xf9403362u,0x9402fe5du,0x9141ff61u,0xf943d421u,0xaa1603e2u,0xf81a83a0u,0x940302d3u,0xf85a83a1u,0xaa0003e2u,0xf9403b64u,0x97b69a34u,0x9100c2c0u,0x17f818ceu};
constexpr uint32_t kIconThemeSupportMasks[] = {
0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffc003ffu,0xffc003ffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffc003ffu,0xffc003ffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu};
const BytePattern kIconThemeSupportPattern{reinterpret_cast<const uint8_t*>(kIconThemeSupportWords),reinterpret_cast<const uint8_t*>(kIconThemeSupportMasks),sizeof(kIconThemeSupportWords)};
constexpr uint32_t kIconThemeRefreshWords[] = {
0xf85f03a0u,0xb8423002u,0x8b1c8042u,0xaa0203e1u,0xf81e83a2u,0x97d419deu,0xaa0003e2u,0xf85f03a0u,0xf81d83a2u,0xb841f003u,0x8b1c8063u,0xaa0303e1u,0xf81e03a3u,0x97d419d6u,0xf85f03a1u,0xf81d03a0u,0x940001b1u,0xaa0003e1u,0xf81c83a1u,0x97fb0e1eu,0xf85e83a1u,0x97d419ceu,0xf85e03a1u,0xf81e03a0u,0x97d419cbu,0xaa0003e1u,0xf85d03a0u,0x93417c02u,0x36000040u,0xf8407002u,0x93417c20u,0x36000041u,0xf8407020u,0xf85d83a3u,0xf85e03a1u,0x6b01007fu,0x540002e0u,0xf9403f40u,0xf9529000u,0xf9402370u,0x6b10001fu,0x54000061u,0xf9403362u,0x9405f35cu,0x91414761u,0xf942a021u,0xaa1603e2u,0xf81e03a0u,0x9405f7d2u,0xf85e03a1u,0xaa0003e2u,0xf9403b64u,0x97b98f33u,0xf85f03a1u,0x9400001cu,0xaa0003e1u,0xf81f03a1u,0x97fb0df8u,0x14000016u};
constexpr uint32_t kIconThemeRefreshMasks[] = {
0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffc003ffu,0xffc003ffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu};
const BytePattern kIconThemeRefreshPattern{reinterpret_cast<const uint8_t*>(kIconThemeRefreshWords),reinterpret_cast<const uint8_t*>(kIconThemeRefreshMasks),sizeof(kIconThemeRefreshWords)};
bool IconThemeCall(const Image& image,uintptr_t at,unsigned index,uintptr_t* target) {
 uint32_t word=0;const auto pc=at+index*4u;
 return ReadInsn(image,pc,&word)&&IsBl(word)&&DecodeBlTarget(pc,word,target)&&Contains(image,*target,16,kFlagRead|kFlagExec);
}
bool IconThemeRelated(const Image& image,uintptr_t at,const unsigned* indices,unsigned size) {
 uintptr_t first=0,next=0;if(!size||!IconThemeCall(image,at,indices[0],&first))return false;
 for(unsigned i=1;i<size;i++)if(!IconThemeCall(image,at,indices[i],&next)||next!=first)return false;
 return true;
}
bool FindIconThemeSupport(const Image& image,uintptr_t* out,uint32_t* count) {
 uintptr_t at=0;if(!FindUniqueBytes(image,kIconThemeSupportPattern,&at,count))return false;
 const unsigned init[]={11,29,45},closure[]={16,50},log[]={20,54};uintptr_t region=0;
 if(!IconThemeRelated(image,at,init,3)||!IconThemeRelated(image,at,closure,2)||
    !IconThemeRelated(image,at,log,2)||!IconThemeCall(image,at,32,&region))return false;
 // The decoded local store precedes the domestic branch, after SDK >= 33.
 *out=at+36u*4u;return true;
}
bool FindIconThemeRefresh(const Image& image,uintptr_t* out,uint32_t* count) {
 uintptr_t at=0;if(!FindUniqueBytes(image,kIconThemeRefreshPattern,&at,count))return false;
 const unsigned getter[]={5,13,21,24},await[]={19,57};uintptr_t read=0,refresh=0;
 if(!IconThemeRelated(image,at,getter,4)||!IconThemeRelated(image,at,await,2)||
    !IconThemeCall(image,at,16,&read)||!IconThemeCall(image,at,54,&refresh)||read==refresh)return false;
 const unsigned calls[]={43u,48u,52u};for(unsigned index : calls){uintptr_t target=0;if(!IconThemeCall(image,at,index,&target))return false;}
 // Old/new reactive values have already been loaded; force only this comparator.
 *out=at+35u*4u;return true;
}
bool IconThemeWindow(const Image& image,uintptr_t at,const BytePattern& pattern,unsigned hook_index,const uint8_t* original) {
 if(!Contains(image,at,pattern.size,kFlagRead|kFlagExec))return false;
 for(size_t i=0;i<pattern.size;i++) {
  const auto byte=original&&i>=hook_index*4u&&i<hook_index*4u+16u ? original[i-hook_index*4u] : image.base[at+i];
  const auto mask=pattern.mask?pattern.mask[i]:255u;
  if((byte&mask)!=(pattern.bytes[i]&mask))return false;
 }
 return true;
}
// Existing controller adoption: lifecycle static owner, DI registration and original ContentObserver body.
constexpr uint32_t kThemeOwnerWords[] = {0xa9bf79fdu,0xaa0f03fdu,0xd10021efu,0xaa0103e0u,0xf81f83a1u,0x9401fb16u,0xf85f83a1u,0xf9403f40u,0xf914f401u,0x942e2e9fu,0xaa1603e0u,0xaa1d03efu,0xa8c179fdu,0xd65f03c0u};
constexpr uint32_t kThemeOwnerMasks[] = {0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu};
const BytePattern kThemeOwnerPattern{reinterpret_cast<const uint8_t*>(kThemeOwnerWords),reinterpret_cast<const uint8_t*>(kThemeOwnerMasks),sizeof(kThemeOwnerWords)};
constexpr uint32_t kThemeBootstrapWords[] = {0xa9bf79fdu,0xaa0f03fdu,0xd100a1efu,0xf81f83b6u,0xf9402f60u,0x97f68917u,0xf9403f40u,0xf94e8000u,0xf9402370u,0x6b10001fu,0x54000061u,0xf97bf362u,0x94016dd7u,0x91410b70u,0xf9403e10u,0xf90001f0u,0xf9402b64u,0x97b50311u,0x36200260u,0x94000014u,0xaa0003e1u,0xf81f03a0u,0x97ec0caeu,0x91410b70u,0xf9403e10u,0xf85f03beu,0xa900c1feu,0x910082d0u,0xf90001f0u,0x9140af64u,0xf9456484u,0x97b4fe65u,0xf85f03a1u,0x97fb7bfcu,0xaa0003e1u,0xf81f03a1u,0x97f68869u,0xaa1603e0u,0x17f68839u};
constexpr uint32_t kThemeBootstrapMasks[] = {0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffc003ffu,0xffffffffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xfc000000u,0xffc003ffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffc003ffu,0xffc003ffu,0xfc000000u,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu};
const BytePattern kThemeBootstrapPattern{reinterpret_cast<const uint8_t*>(kThemeBootstrapWords),reinterpret_cast<const uint8_t*>(kThemeBootstrapMasks),sizeof(kThemeBootstrapWords)};
constexpr uint32_t kThemeRegisterWords[] = {0xa9bf79fdu,0xaa0f03fdu,0xd10301efu,0xf81f83b6u,0xf81783a1u,0xf9402f60u,0x97fb0f96u,0xf85783a0u,0xb842f001u,0x8b1c8021u,0x37200061u,0xaa1603e0u,0x17fb0ed3u,0xd2800021u,0x9405f7dfu,0xf81703a0u,0xf9403f40u,0xf9543c00u,0xf9402370u,0x6b10001fu,0x54000081u,0x91402362u,0xf940e042u,0x9405f44cu,0xf81683a0u,0x97ba3697u,0x6b16001fu,0x54000061u,0xaa1603e0u,0x14000003u,0xaa0003e1u,0x97ba914cu,0xf81683a0u,0x6b16001fu,0x54000061u,0xaa1603e0u,0x14000003u,0xaa0003e1u,0x97beb65du,0xf81683a0u,0x6b16001fu,0x540001e1u,0xf9403f40u,0xf9529000u,0xf9402370u,0x6b10001fu,0x54000061u,0xf9403362u,0x9405f433u,0xaa0003e1u,0x91414762u,0xf9428042u,0xf9403b64u,0x97ba113eu,0xaa1603e0u,0x17fb0ea8u,0xf85783a3u,0xf85703a2u,0x91410b61u,0xf9403c21u,0x97fb368cu,0xf85783a3u,0xb8007003u,0xf85703a4u,0xb800f080u,0x385ff090u,0x385ff011u,0x8a500a30u,0xea5c821fu,0x54000040u,0x9405f59fu,0x91411b61u,0xf9453421u,0x91402362u,0xf941e042u,0x97bec05eu,0xaa0003e1u,0xf81603a1u,0x97fb0ebfu,0xf81603a0u,0xf90001e0u,0x97efd3c4u,0xf85703a2u,0x91414761u,0xf9428421u,0xf81583a0u,0x9405f888u,0xf85683a1u,0xaa0003e2u,0xf85583a6u,0x91414763u,0xf9426c63u,0x9100c2c5u,0xf81503a0u,0x97bebf85u,0x91411b61u,0xf9453821u,0x91402362u,0xf941e042u,0x97bec046u,0xaa0003e1u,0xf81483a1u,0x97fb0ea7u,0xf81483a0u,0xf90001e0u,0x97efd3acu,0xf85703a2u,0x91414761u,0xf9428821u,0xf81583a0u,0x9405f870u,0xf85683a1u,0xaa0003e2u,0xf85583a6u,0x91414763u,0xf9427063u,0x9100c2c5u,0xf81703a0u,0x97bebf6du,0xf85783a0u,0x910082c1u,0xb802f001u,0xf9403f40u,0xf9529000u,0xf9402370u,0x6b10001fu,0x54000061u,0xf9403362u,0x9405f3e3u,0x91414761u,0xf9428c21u,0xaa1603e2u,0xf81503a0u,0x9405f859u,0xf85503a1u,0xaa0003e2u,0xf9403b64u,0x97b98fbau,0x1400001cu,0xd10303afu,0xf81783a0u,0xf9403f40u,0xf9529000u,0xf9402370u,0x6b10001fu,0x54000061u,0xf9403362u,0x9405f3d0u,0xaa1603e1u,0xd28000c2u,0xf81703a0u,0x9405fb7au,0x91411b70u,0xf9454210u,0xb800f010u,0x91414770u,0xf9429210u,0xb8013010u,0xf85783a1u,0xb8017001u,0xf90001e0u,0x97b8e45cu,0xf85703a1u,0xaa0003e2u,0xf9403b64u,0x97ba10ceu,0xaa1603e0u,0x17fb0e38u};
constexpr uint32_t kThemeRegisterMasks[] = {0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffc003ffu,0xffc003ffu,0xfc000000u,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffc003ffu,0xffc003ffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffc003ffu,0xffc003ffu,0xfc000000u,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffc003ffu,0xffc003ffu,0xffc003ffu,0xffc003ffu,0xfc000000u,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffc003ffu,0xffc003ffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xffffffffu,0xffc003ffu,0xffc003ffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffc003ffu,0xffc003ffu,0xffc003ffu,0xffc003ffu,0xfc000000u,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffc003ffu,0xffc003ffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xffffffffu,0xffc003ffu,0xffc003ffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffc003ffu,0xffc003ffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffc003ffu,0xffffffffu,0xffffffffu,0xffc003ffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu,0xffffffffu,0xfc000000u,0xffffffffu,0xffffffffu};
const BytePattern kThemeRegisterPattern{reinterpret_cast<const uint8_t*>(kThemeRegisterWords),reinterpret_cast<const uint8_t*>(kThemeRegisterMasks),sizeof(kThemeRegisterWords)};
bool FindThemeOwner(const Image& image,uintptr_t* out,uint32_t* n){return FindUniqueBytes(image,kThemeOwnerPattern,out,n);}
bool FindThemeBootstrap(const Image& image,uintptr_t* out,uint32_t* n){return FindUniqueBytes(image,kThemeBootstrapPattern,out,n);}
const SiteSpec kIconThemeSites[] = {
 {"support",{nullptr,nullptr,0},FindIconThemeSupport,true,{reinterpret_cast<const uint8_t*>(kIconThemeSupportWords+36),nullptr,16},0},
 {"refresh",{nullptr,nullptr,0},FindIconThemeRefresh,true,{reinterpret_cast<const uint8_t*>(kIconThemeRefreshWords+35),nullptr,16},0},
 {"owner",{},FindThemeOwner,true,kThemeOwnerPattern,0},
 {"bootstrap",{},FindThemeBootstrap,true,kThemeBootstrapPattern,0}
};
}
const TargetSpec kIconThemeTarget{"desktop_icon_theme","libapp.so",kIconThemeSites,4};

bool ValidateIconThemeTargets(const Image& image,uintptr_t support,uintptr_t refresh,const uint8_t* support_original,const uint8_t* refresh_original) {
 if(support<36u*4u||refresh<35u*4u)return false;
 const auto a=support-36u*4u,b=refresh-35u*4u;
 if(!IconThemeWindow(image,a,kIconThemeSupportPattern,36,support_original)||
    !IconThemeWindow(image,b,kIconThemeRefreshPattern,35,refresh_original))return false;
 for(unsigned family=0;family<2;family++) {
  const auto at=family?b:a;const auto size=family?kIconThemeRefreshPattern.size:kIconThemeSupportPattern.size;
  for(unsigned i=0;i<size/4u;i++){uint32_t word=0;if(!ReadInsn(image,at+i*4u,&word))return false;
   if(IsBl(word)){uintptr_t target=0;if(!IconThemeCall(image,at,i,&target))return false;}
  }
 }
 const unsigned init[]={11,29,45},closure[]={16,50},log[]={20,54},getter[]={5,13,21,24},await[]={19,57};
 return IconThemeRelated(image,a,init,3)&&IconThemeRelated(image,a,closure,2)&&IconThemeRelated(image,a,log,2)&&
        IconThemeRelated(image,b,getter,4)&&IconThemeRelated(image,b,await,2);
}

bool DecodeIconThemeObserver(const Image& image,uintptr_t owner,uintptr_t bootstrap,uintptr_t refresh,IconThemeObserverLayout* out) {
 if(!out||!MatchBytes(image,owner,kThemeOwnerPattern,kFlagRead|kFlagExec)||
    !MatchBytes(image,bootstrap,kThemeBootstrapPattern,kFlagRead|kFlagExec)||refresh<35u*4u)return false;
 uintptr_t registration=0,allocation=0,load=0,expected_load=0;
 if(!IconThemeCall(image,owner,9,&registration)||!MatchBytes(image,registration,kThemeRegisterPattern,kFlagRead|kFlagExec)||
    !IconThemeCall(image,bootstrap,19,&allocation)||!Contains(image,allocation,12,kFlagRead|kFlagExec)||
    !IconThemeCall(image,bootstrap,33,&load)||!IconThemeCall(image,refresh-35u*4u,16,&expected_load)||load!=expected_load)return false;
 // Reject changed callback/register relationships even when their BL opcodes remain valid.
 const unsigned uri[]={75,99},await[]={78,102},string_uri[]={81,105},closure[]={86,110,133},observe[]={94,118};
 if(!IconThemeRelated(image,registration,uri,2)||!IconThemeRelated(image,registration,await,2)||
    !IconThemeRelated(image,registration,string_uri,2)||!IconThemeRelated(image,registration,closure,3)||
    !IconThemeRelated(image,registration,observe,2))return false;
 const uintptr_t starts[]={owner,bootstrap,registration};
 const size_t sizes[]={kThemeOwnerPattern.size,kThemeBootstrapPattern.size,kThemeRegisterPattern.size};
 for(unsigned family=0;family<3;++family)for(unsigned i=0;i<sizes[family]/4u;++i) {
  uint32_t word=0;if(!ReadInsn(image,starts[family]+i*4u,&word))return false;
  if(IsBl(word)){uintptr_t target=0;if(!IconThemeCall(image,starts[family],i,&target))return false;}
 }
 // Constructor allocation headers carry the current class id. Never select a heap CID from a build table.
 uint32_t w[3]{};for(unsigned i=0;i<3;++i)if(!ReadInsn(image,allocation+4u*i,&w[i]))return false;
 if((w[0]&0xffe0001fu)!=0xd2800002u||(w[1]&0xffe0001fu)!=0xf2a00002u||(w[2]&0xfc000000u)!=0x14000000u)return false;
 const uint32_t header=((w[0]>>5u)&65535u)|(((w[1]>>5u)&65535u)<<16u);
 uint32_t store=0,guard=0,set=0;uint32_t imm=0;
 if(!ReadInsn(image,owner+32,&store)||(store&0xffc003ffu)!=0xf9000001u||((imm=(store>>10u)&4095u)==0))return false;
 if(!ReadInsn(image,registration+32,&guard)||!ReadInsn(image,registration+484,&set))return false;
 // Both the initial guard and the success store must refer to the same controller flag.
 if((guard&0xffe00fffu)!=0xb8400001u||(set&0xffe00fffu)!=0xb8000001u||
    ((guard>>12u)&511u)!=((set>>12u)&511u))return false;
 *out={imm*8u,(guard>>12u)&511u,header>>12u,registration};
 return out->cid!=0&&out->registered_field<128;
}
