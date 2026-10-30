// SPDX-License-Identifier: Apache-2.0
#include "desktop_grid_patterns.inc"
#include "desktop_grid_compiler_shapes.inc"
namespace {
#define GRID_SITE(Name) bool FindGrid##Name(const Image& image,uintptr_t* at,uint32_t* count) {return FindUniqueBytes(image,kGrid##Name,at,count);}
GRID_SITE(Create) GRID_SITE(Change)
GRID_SITE(Fixed) GRID_SITE(Lookup)
#undef GRID_SITE
bool FindGridShapes(const Image& image,const BytePattern& first,const BytePattern& second,uintptr_t* at,uint32_t* count) {
 const BytePattern patterns[]={first,second};*count=0;
 for(const auto& pattern:patterns) {
  uintptr_t offsets[16]{};size_t stored=0;uint32_t total=0;
  if(!CollectBytes(image,pattern,offsets,16,&stored,&total)||total>16)return false;
  for(size_t i=0;i<stored;++i){*at=offsets[i];++*count;}
 }
 return *count==1;
}
bool FindGridApply(const Image& image,uintptr_t* at,uint32_t* count) {return FindGridShapes(image,kGridApply,kGridInlineApply,at,count);}
bool FindGridVariable(const Image& image,uintptr_t* at,uint32_t* count) {return FindGridShapes(image,kGridVariable,kGridCompactVariable,at,count);}
bool GridCall(const Image&,uintptr_t,uintptr_t*);
bool FindGridTo(const Image& image,uintptr_t* at,uint32_t* count){
 uintptr_t init=0,hide=0,apply=0;
 if(!FindUniqueBytes(image,kGridOnInit,&init,count)||!FindUniqueBytes(image,kGridHide,&hide,count))return false;
 uintptr_t offsets[32]{};size_t stored=0;uint32_t total=0;
 if(!CollectBytes(image,kGridTo,offsets,32,&stored,&total)||total>32)return false;
 uint32_t store=0;ReadInsn(image,init+56,&store);
 if((store&0xffc003ffu)!=0xf9000001u)return false;
 *count=0;
 for(size_t i=0;i<stored;++i) {
  uint32_t load=0;ReadInsn(image,offsets[i]+16,&load);
  if((load&0xffc003ffu)!=0xf9400000u||((load>>10)&4095u)!=((store>>10)&4095u))continue;
  *at=offsets[i];++*count;
 }
 if(*count!=1)return false;
 uint32_t matches=0;if(!FindGridApply(image,&apply,&matches))return false;
 uintptr_t call=0;if(!GridCall(image,hide+236,&call)||call!=apply)return false;
 return true;
}
bool FindGridPrepare(const Image& image,uintptr_t* at,uint32_t* count){
 uintptr_t create=0;uint32_t matches=0;
 if(!FindGridCreate(image,&create,&matches))return false;
 *count=0;
 for(size_t i=0;i<image.load_count;++i){const auto& seg=image.loads[i];if(!(seg.flags&kFlagExec))continue;
  for(uintptr_t p=seg.start;p+kGridPrepare.size<=seg.end;p+=4){
   if(!MatchBytes(image,p,kGridPrepare,kFlagRead|kFlagExec))continue;
   uintptr_t buttons=0;if(!GridCall(image,p+28,&buttons)||!Contains(image,buttons,1028,kFlagRead|kFlagExec))continue;
   unsigned calls=0;uintptr_t target=0;
   for(unsigned j=0;j<1028;j+=4)if(GridCall(image,buttons+j,&target)&&target==create)++calls;
   if(calls==3){*at=p;++*count;}
  }
 }
 return *count==1;
}
const SiteSpec kGridSites[]={
 {"to",{},FindGridTo,true,kGridTo,0}, {"prepare",{},FindGridPrepare,true,kGridPrepare,0},
 {"create",{},FindGridCreate,true,kGridCreate,0}, {"change",{},FindGridChange,true,kGridChange,0},
 {"apply",{},FindGridApply,true,{kGridApply.bytes,kGridApply.mask,8},0}, {"variable",{},FindGridVariable,true,{kGridVariable.bytes,kGridVariable.mask,16},0},
 {"fixed",{},FindGridFixed,true,kGridFixed,0}, {"lookup",{},FindGridLookup,true,kGridLookup,0},
};
uint32_t GridWord(const Image& image,uintptr_t at){uint32_t word=0;ReadInsn(image,at,&word);return word;}
uint32_t GridField(uint32_t word){return (word>>12u)&511u;}
bool GridCall(const Image& image,uintptr_t at,uintptr_t* result){const auto w=GridWord(image,at);return IsBl(w)&&DecodeBlTarget(at,w,result)&&Contains(image,*result,16,kFlagRead|kFlagExec);}
}
const TargetSpec kDesktopGridTarget={"desktop_grid_pipeline","libapp.so",kGridSites,8};
bool DecodeDesktopGridLayout(const Image& image,const uintptr_t* sites,DesktopGridLayout* out,const uint8_t* handler,const uint8_t* variable,const uint8_t* rows,const uint8_t* clamp){
 if(!sites||!out)return false;
 auto verify=[&](uintptr_t at,const BytePattern& pattern,const uint8_t* original=nullptr,size_t begin=0){
  if(!Contains(image,at,pattern.size,kFlagRead|kFlagExec))return false;
  for(size_t i=0;i<pattern.size;++i){const auto byte=original&&i>=begin&&i<begin+16?original[i-begin]:image.base[at+i];if((byte&pattern.mask[i])!=(pattern.bytes[i]&pattern.mask[i]))return false;}return true;
 };
 auto find_owned=[&](const BytePattern& pattern,size_t begin,const uint8_t* original,uintptr_t* at){
  // Search around our owned window without ignoring the rest of the function.
  uint8_t masks[8192]{};if(pattern.size>sizeof(masks)||begin+16>pattern.size)return false;
  memcpy(masks,pattern.mask,pattern.size);memset(masks+begin,0,16);
  const BytePattern search{pattern.bytes,masks,pattern.size};uint32_t matches=0;
  return FindUniqueBytes(image,search,at,&matches)&&verify(*at,pattern,original,begin);
 };
 for(unsigned i=0;i<8;++i)if(!verify(sites[i],kGridSites[i].verify,i==5?variable:i==6?rows:nullptr,i==6?32:0))return false;
 const bool inline_apply=verify(sites[4],kGridInlineApply);
 if(!inline_apply&&!verify(sites[4],kGridApply))return false;
 if(!verify(sites[5],kGridVariable,variable)&&!verify(sites[5],kGridCompactVariable,variable))return false;
 DesktopGridLayout v{};uintptr_t call=0,other=0,prepare_current=0,legacy_cal=0,init=0,clamp_begin=0,values=0,copy=0,current=0,caller=0;uint32_t count=0;uint8_t rt=0,rn=0;uint32_t imm=0;
 if(!IsLdrImm(GridWord(image,sites[0]+16),&rt,&rn,&imm)||rt!=0||rn!=0)return false;
 v.controller_slot=imm*8u;
 if(!FindUniqueBytes(image,kGridOnInit,&init,&count))return false;
 const auto store=GridWord(image,init+56);if((store&0xffc003ffu)!=0xf9000001u||((store>>10)&4095u)*8u!=v.controller_slot)return false;
 if(!GridCall(image,sites[1]+20,&prepare_current)||!Contains(image,prepare_current,420,kFlagRead|kFlagExec)||
    !GridCall(image,sites[2]+152,&legacy_cal)||!GridCall(image,sites[3]+236,&call)||call!=legacy_cal)return false;
 if(GridWord(image,sites[2]+148)!=0xd2800082u||GridWord(image,sites[3]+232)!=0xd2800162u)return false;
 v.current=GridField(GridWord(image,prepare_current+84));v.selected=GridField(GridWord(image,prepare_current+376));
 if(v.current!=GridField(GridWord(image,prepare_current+100))||v.current!=GridField(GridWord(image,sites[4]+68))||v.selected!=GridField(GridWord(image,sites[4]+32))||v.selected!=GridField(GridWord(image,sites[3]+664)))return false;
 v.grid_x=GridField(GridWord(image,prepare_current+200));v.grid_y=GridField(GridWord(image,prepare_current+316));
 if(inline_apply) {
  uintptr_t description=0;
  if(!FindUniqueBytes(image,kGridDescription,&description,&count)||
     v.grid_x!=GridField(GridWord(image,description+0x104))||v.grid_y!=GridField(GridWord(image,description+0x150)))return false;
 } else if(v.grid_x!=GridField(GridWord(image,sites[4]+0x2ac))||v.grid_y!=GridField(GridWord(image,sites[4]+0x308)))return false;
 v.options=GridField(GridWord(image,sites[7]));v.list_length=GridField(GridWord(image,sites[7]+8));v.list_data=GridField(GridWord(image,sites[7]+16));v.array_data=GridField(GridWord(image,sites[7]+40));
 v.x=GridField(GridWord(image,sites[2]+48));v.y=GridField(GridWord(image,sites[2]+40));v.index=GridField(GridWord(image,sites[2]+56));v.layout=GridField(GridWord(image,sites[2]+64));
 if(v.x!=GridField(GridWord(image,sites[4]+96))||v.y!=GridField(GridWord(image,sites[4]+112))||v.index!=GridField(GridWord(image,sites[7]+52))||v.layout!=GridField(GridWord(image,sites[4]+128)))return false;
 uintptr_t allocate=0;if(!GridCall(image,sites[2]+24,&allocate))return false;
 const auto lo=GridWord(image,allocate),hi=GridWord(image,allocate+4);
 if((lo&0xffe0001fu)!=0xd2800002u||(hi&0xffe0001fu)!=0xf2a00002u)return false;
 const auto header=((lo>>5)&65535u)|(((hi>>5)&65535u)<<16);if((header&4095u)!=0x31cu)return false;v.cell_cid=header>>12;if(!v.cell_cid)return false;
 // The legacy calculator validates the CellCountXY ABI only. It is NOT the
 // producer of current GridConfig instances; those come from ComputePipeline.
 if(!Contains(image,legacy_cal,7372,kFlagRead|kFlagExec))return false;
 const auto guard0=GridWord(image,legacy_cal+0x3b4),guard1=GridWord(image,legacy_cal+0xc84);
 if((guard0&0xffc003ffu)!=0xf100003fu||(guard1&0xffc003ffu)!=0xf100001fu||((guard0>>10)&4095u)!=v.cell_cid||((guard1>>10)&4095u)!=v.cell_cid)return false;
 if(!find_owned(kGridHandler,0,handler,&v.handler)||!GridCall(image,v.handler+0x8c,&call)||call!=sites[6]||!GridCall(image,v.handler+0x9c,&call)||call!=sites[5]||!GridCall(image,v.handler+0xa8,&values)||!verify(values,kGridValues))return false;
 if(!GridCall(image,values+0x714,&copy))return false;
 const bool spilled_copy=verify(copy,kGridSpilledCopy);
 if(!spilled_copy&&!verify(copy,kGridCopy))return false;
 v.context_x=GridField(GridWord(image,sites[5]+0x3c));v.context_y=GridField(GridWord(image,copy+0x71c));
 if(v.context_x!=GridField(GridWord(image,sites[6]+0x4c))||v.context_x!=GridField(GridWord(image,copy+0x6fc))||v.context_x!=GridField(GridWord(image,copy+(spilled_copy?0x8e8:0x8d0)))||v.context_y!=GridField(GridWord(image,copy+(spilled_copy?0x8f0:0x8d8))))return false;
 v.input_rows=GridField(GridWord(image,sites[6]+0x20));v.rows_window=sites[6]+0x20;
 uint32_t row_word=rows?0:GridWord(image,v.rows_window),row_branch=rows?0:GridWord(image,v.rows_window+12);
 if(rows){memcpy(&row_word,rows,4);memcpy(&row_branch,rows+12,4);}
 if((row_word&0xffe00fffu)!=0xb8400003u||(row_branch&0xff00001fu)!=0x54000000u)return false;
 v.input_rows=GridField(row_word);const int64_t delta=static_cast<int32_t>(row_branch<<8)>>11;v.rows_null=static_cast<uintptr_t>(static_cast<int64_t>(v.rows_window+12)+delta);
 if(!Contains(image,v.rows_null,4,kFlagRead|kFlagExec)||!IsBl(GridWord(image,v.rows_null)))return false;
 if(!GridCall(image,sites[2]+248,&v.load)||!GridCall(image,sites[3]+340,&call)||call!=v.load)return false;
 const bool expanded_load=verify(v.load,kGridExpandedLoad);
 if(!expanded_load&&!verify(v.load,kGridLoad))return false;
 if(GridWord(image,v.load+(expanded_load?0xa94:0xa0c))!=0x7100681fu||!GridCall(image,v.load+(expanded_load?0xaa0:0xa18),&call))return false;
 if(!GridCall(image,v.load+(expanded_load?0x1330:0x120c),&v.migrate)||!verify(v.migrate,kGridMigrate))return false;
 if(inline_apply) {
  // The apply body inlines currentConfig. Recover its separate getter by the
  // same controller slot and Rx field, rejecting any duplicate consumer.
  uintptr_t offsets[64]{};size_t stored=0;uint32_t total=0,matched=0;
  if(!CollectBytes(image,kGridCurrent,offsets,64,&stored,&total)||total>64)return false;
  for(size_t i=0;i<stored;++i) {
   if(GridWord(image,offsets[i]+16)!=GridWord(image,sites[4]+0x250)||
      GridField(GridWord(image,offsets[i]+76))!=GridField(GridWord(image,sites[4]+0x2cc)))continue;
   current=offsets[i];++matched;
  }
  if(matched!=1)return false;
 } else if(!GridCall(image,sites[4]+0x2a8,&current)||!verify(current,kGridCurrent))return false;
 if(!IsLdrImm(GridWord(image,current+16),&rt,&rn,&imm)||rt!=0||rn!=0)return false;v.grid_controller_slot=imm*8u;
 if(!FindUniqueBytes(image,kGridLoadCaller,&caller,&count)||!GridCall(image,caller+0x130,&call)||call!=v.load||!IsLdrImm(GridWord(image,caller+0x12c),&rt,&rn,&imm)||rt!=4||rn!=27)return false;v.load_descriptor=imm*8u;
 // Keep the preference loader from truncating six columns back to five. This
 // guard does not calculate geometry or replace arbitrary Rx setters.
 uint8_t masks[sizeof(kGridClampMasks)]{};memcpy(masks,kGridClampMasks,sizeof(masks));memset(masks+92,0,16);
 const BytePattern search{kGridClamp.bytes,masks,kGridClamp.size};if(!FindUniqueBytes(image,search,&clamp_begin,&count)||!verify(clamp_begin,kGridClamp,clamp,92))return false;
 v.clamp=clamp_begin+92;uint32_t maximum=GridWord(image,clamp_begin+96),unset=GridWord(image,clamp_begin+104);if(clamp){memcpy(&maximum,clamp+4,4);memcpy(&unset,clamp+12,4);}
 v.maximum_field=GridField(maximum);v.unset_field=GridField(unset);
 if(v.unset_field!=GridField(GridWord(image,clamp_begin+1112))||v.maximum_field!=GridField(GridWord(image,clamp_begin+304)))return false;
 uintptr_t async_init=0;if(!GridCall(image,sites[3]+76,&async_init)||!GridCall(image,sites[4]+24,&other)||async_init!=other)return false;
 const uint32_t fields[]={v.current,v.selected,v.options,v.x,v.y,v.index,v.layout,v.list_length,v.list_data,v.array_data,v.grid_x,v.grid_y,v.context_x,v.context_y,v.input_rows};
 for(auto f:fields)if(f<7||f>255||(f&3u)!=3u)return false;
 v.to=sites[0];v.prepare=sites[1];v.change=sites[3];v.apply=sites[4];v.variable=sites[5];v.fixed=sites[6];*out=v;return true;
}
