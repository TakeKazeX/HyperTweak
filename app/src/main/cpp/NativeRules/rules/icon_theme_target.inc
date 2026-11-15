// SPDX-License-Identifier: Apache-2.0
// Discover one connected Settings/Rx/observer family. Frame, field, pool and
// static-table operands are decoded; no compiler-body fingerprints or RVAs.
namespace {
struct ThemeValue {
 enum Kind { Unknown, Owner, Field, Value, Pool, Call, Statics, Boolean, Changed } kind=Unknown;
 uintptr_t a=0,b=0;
 bool operator==(const ThemeValue& other)const{return kind==other.kind&&a==other.a&&b==other.b;}
};
struct ThemeEvent {uintptr_t pc=0,target=0;ThemeValue args[7]{};};
struct ThemeCompare {uintptr_t branch=0;ThemeValue left{},right{},destination_left{},destination_right{};};
struct ThemeChangedGate {uintptr_t pc=0;ThemeValue value{};};
struct ThemeCode {
 const Image& image;
 uintptr_t patches[2]{};
 const uint8_t* originals[2]{};
 bool Read(uintptr_t pc,uint32_t* word)const {
  for(unsigned i=0;i<2;i++)if(originals[i]&&pc>=patches[i]&&pc<patches[i]+16) {
   memcpy(word,originals[i]+pc-patches[i],4);return true;
  }
  return ReadInsn(image,pc,word);
 }
};
bool ThemeCall(const ThemeCode& code,uintptr_t pc,uintptr_t* target) {
 uint32_t w=0;return code.Read(pc,&w)&&IsBl(w)&&DecodeBlTarget(pc,w,target)&&
  Contains(code.image,*target,12,kFlagRead|kFlagExec);
}
bool ThemeUnscaled(uint32_t w,bool load,bool wide,unsigned* rt,unsigned* rn,int* offset) {
 const auto opcode=wide?(load?0xf8400000u:0xf8000000u):(load?0xb8400000u:0xb8000000u);
 if((w&0xffe00c00u)!=opcode)return false;
 *rt=w&31u;*rn=(w>>5u)&31u;*offset=static_cast<int32_t>(w<<11u)>>23u;return true;
}
bool ThemeMove(uint32_t w,unsigned* dst,unsigned* src) {
 if((w&0xffe0ffe0u)!=0xaa0003e0u)return false;
 *dst=w&31u;*src=(w>>16u)&31u;return true;
}
bool ThemeCmp(uint32_t w,unsigned* left,unsigned* right) {
 if((w&0x7fe0fc1fu)!=0x6b00001fu)return false;
 *left=(w>>5u)&31u;*right=(w>>16u)&31u;return true;
}
bool ThemeTest(uint32_t w,unsigned bit,bool nonzero,unsigned* reg) {
 if((w&0xff000000u)!=(nonzero?0x37000000u:0x36000000u)||
    ((w>>19u)&31u)!=bit)return false;
 *reg=w&31u;return true;
}
bool ThemeBranch(uintptr_t pc,uint32_t w,uintptr_t* target) {
 if((w&0xff000010u)==0x54000000u)return DecodeTestBranchTarget(pc,w,target);
 if((w&0x7e000000u)!=0x36000000u)return false;
 const int64_t delta=static_cast<int32_t>(w<<13u)>>16u;
 *target=static_cast<uintptr_t>(static_cast<int64_t>(pc)+delta);return true;
}
uintptr_t ThemeEnd(const ThemeCode& code,uintptr_t start) {
 if(!IsDartPrologue(code.image,start))return 0;
 for(uintptr_t pc=start+8;pc<start+1024;pc+=4) {
  if(!Contains(code.image,pc,4,kFlagRead|kFlagExec))return pc;
  if(IsDartPrologue(code.image,pc))return pc;
  uint32_t frame=0,move=0;
  if(code.Read(pc,&frame)&&code.Read(pc+4,&move)&&
     (frame&0xffc00000u)==0xa9800000u&&move==0xaa0f03fdu)return pc;
 }
 return 0; // Unbounded or unfamiliar bodies are not admitted.
}
uintptr_t ThemeStart(const ThemeCode& code,uintptr_t at) {
 for(unsigned i=0;i<256&&at>=4;i++,at-=4) {
  if(IsDartPrologue(code.image,at))return at;
  uint32_t frame=0,move=0;
  if(code.Read(at,&frame)&&code.Read(at+4,&move)&&
     (frame&0xffc00000u)==0xa9800000u&&move==0xaa0f03fdu)return 0;
 }
 return 0;
}
struct ThemeTrace {
 ThemeEvent calls[96]{};unsigned count=0;
 ThemeCompare compares[8]{};unsigned comparisons=0;
 ThemeChangedGate gates[8]{};unsigned gate_count=0;
 uintptr_t static_slot=0,registration=0;
 uint32_t guard_field=0,set_field=0;
 uint32_t fields[16]{};unsigned field_count=0;
};
bool ThemeTraceBody(const ThemeCode& code,uintptr_t start,ThemeTrace* out) {
 const auto end=ThemeEnd(code,start);if(!end)return false;
 ThemeValue registers[32]{};registers[1]={ThemeValue::Owner};
 struct Local {int offset=0;ThemeValue value{};bool used=false;} locals[64]{};
 auto readLocal=[&](int offset){for(const auto& v:locals)if(v.used&&v.offset==offset)return v.value;return ThemeValue{};};
 auto writeLocal=[&](int offset,ThemeValue value){for(auto& v:locals)if(!v.used||v.offset==offset){v={offset,value,true};return true;}return false;};
 ThemeValue left{},right{};bool compared=false;
 for(uintptr_t pc=start+8;pc<end;pc+=4) {
  uint32_t w=0;if(!code.Read(pc,&w))return false;
  if((w&0xff00001fu)==0x54000000u&&compared&&left.kind==ThemeValue::Value&&right.kind==ThemeValue::Value) {
   if(out->comparisons==8)return false;
   auto& comparison=out->compares[out->comparisons++];comparison.branch=pc;comparison.left=left;comparison.right=right;
   uintptr_t destination=0;uint32_t target_word=0;unsigned lhs=0,rhs=0;
   if(ThemeBranch(pc,w,&destination)&&code.Read(destination,&target_word)&&ThemeCmp(target_word,&lhs,&rhs)) {
    comparison.destination_left=registers[lhs];comparison.destination_right=registers[rhs];
   }
  }
  unsigned rt=0,rn=0;int offset=0;uint8_t d=0,n=0;uint32_t imm=0;bool shift=false;
  if(ThemeCmp(w,&rn,&rt)){left=registers[rn];right=registers[rt];compared=true;continue;}
  compared=false;
  if(ThemeMove(w,&rt,&rn)){registers[rt]=registers[rn];continue;}
  if(ThemeUnscaled(w,true,true,&rt,&rn,&offset)) {
   if(rn==29)registers[rt]=readLocal(offset);
   else if(registers[rn].kind==ThemeValue::Value&&offset==7)registers[rt]=registers[rn];
   else registers[rt]={};
   continue;
  }
  if(ThemeUnscaled(w,true,false,&rt,&rn,&offset)) {
   if(registers[rn].kind==ThemeValue::Owner&&offset>0) {
    registers[rt]={ThemeValue::Field,static_cast<uintptr_t>(offset)};
    bool known=false;for(unsigned i=0;i<out->field_count;i++)known|=out->fields[i]==static_cast<uint32_t>(offset);
    if(!known){if(out->field_count==16)return false;out->fields[out->field_count++]=offset;}
   } else registers[rt]={};
   continue;
  }
  if(ThemeUnscaled(w,false,true,&rt,&rn,&offset)) {
   if(rn==29&&!writeLocal(offset,registers[rt]))return false;
   continue;
  }
  if(ThemeUnscaled(w,false,false,&rt,&rn,&offset)) {
   if(registers[rn].kind==ThemeValue::Owner&&registers[rt].kind==ThemeValue::Boolean&&registers[rt].a==0x20)
    out->set_field=offset;
   continue;
  }
  if(IsLdrImm(w,&d,&n,&imm)) {
   // Shared scheduler ABI supplies precisely Thread::field_table_values.
   if(n==26&&imm*8u==0x78)registers[d]={ThemeValue::Statics};
   else if(n==27)registers[d]={ThemeValue::Pool,imm*8u};
   else if(registers[n].kind==ThemeValue::Pool)registers[d]={ThemeValue::Pool,registers[n].a+imm*8u};
   else registers[d]={};
   continue;
  }
  if((w&0xffc00000u)==0xf9000000u) {
   rt=w&31u;rn=(w>>5u)&31u;
   if(registers[rn].kind==ThemeValue::Statics&&registers[rt].kind==ThemeValue::Owner) {
    if(out->static_slot)return false;
    out->static_slot=((w>>10u)&4095u)*8u;
   }
   continue;
  }
  if(IsAddImm(w,&d,&n,&imm,&shift)) {
   if(n==27)registers[d]={ThemeValue::Pool,imm};
   else if(n==22&&!shift&&(imm==0x20||imm==0x30))registers[d]={ThemeValue::Boolean,imm};
   else if(d!=15)registers[d]={};
   continue;
  }
  // Decompress a Dart reference without losing its decoded owner-field role.
  if((w&0xfffffc00u)==0x8b1c8000u){rt=w&31u;rn=(w>>5u)&31u;registers[rt]=registers[rn];continue;}
  // Smi untag; the boxed path above retains the same native value provenance.
  if((w&0xfffffc00u)==0x93417c00u){rt=w&31u;rn=(w>>5u)&31u;registers[rt]=registers[rn];continue;}
  if(ThemeTest(w,4,true,&rt)&&registers[rt].kind==ThemeValue::Field) {
   if(out->guard_field)return false;out->guard_field=registers[rt].a;
  }
  if((w&0xffe00c00u)==0x9a800000u) {
   const auto yes=registers[(w>>5u)&31u],no=registers[(w>>16u)&31u];
   registers[w&31u]=((w>>12u)&15u)==1&&yes.kind==ThemeValue::Boolean&&yes.a==0x20&&
    no.kind==ThemeValue::Boolean&&no.a==0x30&&left.kind==ThemeValue::Value&&right.kind==ThemeValue::Value&&left.a==right.a
    ?ThemeValue{ThemeValue::Changed,left.b,right.b}:ThemeValue{};
   continue;
  }
  if(ThemeTest(w,4,false,&rt)&&registers[rt].kind==ThemeValue::Changed) {
   if(out->gate_count==8)return false;
   out->gates[out->gate_count++]={pc,registers[rt]};
  }
  uintptr_t target=0;
  if(IsBl(w)) {
   if(!ThemeCall(code,pc,&target)||out->count==96)return false;
   auto& event=out->calls[out->count];event.pc=pc;event.target=target;
   for(unsigned i=0;i<7;i++)event.args[i]=registers[i];
   const auto argument=registers[1];
   if(out->static_slot&&argument.kind==ThemeValue::Owner)out->registration=target;
   const auto result=argument.kind==ThemeValue::Field
    ?ThemeValue{ThemeValue::Value,argument.a,out->count}:ThemeValue{ThemeValue::Call,target,out->count};
   ++out->count;for(unsigned i=0;i<18;i++)registers[i]={};registers[0]=result;left={};right={};
  } else if((w&0x1e000000u)==0x1a000000u||(w&0x1c000000u)==0x10000000u) {
   // Unmodelled arithmetic cannot preserve a stale owner/value provenance.
   registers[w&31u]={};
  }
 }
 return true;
}
bool ThemeHasField(const ThemeTrace& trace,uint32_t field) {
 for(unsigned i=0;i<trace.field_count;i++)if(trace.fields[i]==field)return true;
 return false;
}
struct ThemeRefresh {
 uintptr_t entry=0,window=0,reload=0,effect=0,getter=0,await=0;
 uint32_t fields[2]{};uintptr_t keys[2]{},settings=0;
};
bool ThemeNoInteriorBranch(const ThemeCode& code,uintptr_t entry,uintptr_t window,size_t span) {
 const auto end=ThemeEnd(code,entry);if(!end||!Contains(code.image,window,span,kFlagRead|kFlagExec))return false;
 for(uintptr_t pc=entry;pc<end;pc+=4) {
  uint32_t w=0;uintptr_t target=0;if(!code.Read(pc,&w))return false;
  bool branch=ThemeBranch(pc,w,&target);
  if((w&0x7c000000u)==0x14000000u)branch=DecodeBlTarget(pc,w|0x80000000u,&target);
  else if((w&0x7e000000u)==0x34000000u) {
   target=static_cast<uintptr_t>(static_cast<int64_t>(pc)+(static_cast<int32_t>(w<<8u)>>11u));branch=true;
  }
  if(branch&&target>window&&target<window+span)return false;
 }
 return true;
}
bool ThemeDecisionWindow(const ThemeCode& code,uintptr_t entry,uintptr_t branch,const ThemeTrace& trace,const unsigned* reads) {
 if(branch<12)return false;
 const auto start=branch-12;uint32_t w[4]{};
 for(unsigned i=0;i<4;i++)if(!code.Read(start+i*4,&w[i]))return false;
 unsigned loaded=0,base=0,other=0;int frame=0;
 if(!ThemeUnscaled(w[0],true,true,&loaded,&base,&frame)||base!=29||frame>=0)return false;
 if(ThemeTest(w[1],4,false,&other)) {
  uintptr_t target=0;if(other!=loaded||!ThemeBranch(start+4,w[1],&target)||target!=start+16)return false;
  bool changed=false;
  for(unsigned i=0;i<trace.gate_count;i++) {
   const auto& gate=trace.gates[i];const auto& value=gate.value;
   changed|=gate.pc==start+4&&((value.a==reads[0]&&value.b==reads[2])||(value.b==reads[0]&&value.a==reads[2]));
  }
  if(!changed)return false;
 } else {
  unsigned rn=0,lhs=0,rhs=0;
  if(!ThemeUnscaled(w[1],true,true,&other,&rn,&frame)||rn!=29||frame>=0||loaded==other||
     !ThemeCmp(w[2],&lhs,&rhs)||!((lhs==loaded&&rhs==other)||(rhs==loaded&&lhs==other)))return false;
 }
 unsigned lhs=0,rhs=0;if(!ThemeCmp(w[2],&lhs,&rhs)||(w[3]&0xff00001fu)!=0x54000000u)return false;
 // The entire decision precedes the native refresh join. No predecessor may
 // land on an inline backend's ADD/BR/literal inside the overwritten window.
 if(!ThemeNoInteriorBranch(code,entry,start,16))return false;
 uint32_t effect[4]{};for(unsigned i=0;i<4;i++)if(!code.Read(start+16+i*4,&effect[i]))return false;
 uint8_t rt=0,rn=0;uint32_t offset=0;
 return effect[0]==0xf9403f40u&&IsLdrImm(effect[1],&rt,&rn,&offset)&&rt==0&&rn==0&&
  effect[2]==0xf9402370u&&effect[3]==0x6b10001fu;
}
bool ThemeDecodeRefresh(const ThemeCode& code,uintptr_t entry,ThemeRefresh* out) {
 // A settings callback must retain its controller before the first async
 // runtime call. Filter on that operand role before tracing unrelated methods.
 bool retained=false;
 for(unsigned i=2;i<8;i++) {
  uint32_t word=0;unsigned rt=0,rn=0;int offset=0;
  if(!code.Read(entry+i*4,&word)||IsBl(word))break;
  retained|=ThemeUnscaled(word,false,true,&rt,&rn,&offset)&&rt==1&&rn==29&&offset<0;
 }
 if(!retained)return false;
 ThemeTrace trace{};if(!ThemeTraceBody(code,entry,&trace))return false;
 unsigned reads[32]{},size=0;
 for(unsigned i=0;i<trace.count;i++)if(trace.calls[i].args[1].kind==ThemeValue::Field) {
  if(size==32)return false;reads[size++]=i;
 }
 if(size<4)return false;
 const auto getter=trace.calls[reads[0]].target;
 const auto f0=trace.calls[reads[0]].args[1].a,f1=trace.calls[reads[1]].args[1].a;
 if(f0==f1)return false;
 for(unsigned i=0;i<4;i++)if(trace.calls[reads[i]].target!=getter||trace.calls[reads[i]].args[1].a!=(i%2?f1:f0))return false;
 uintptr_t reload=0,effect=0,await=0;unsigned reload_index=0;
 for(unsigned i=reads[1]+1;i<reads[2];i++)if(trace.calls[i].args[1].kind==ThemeValue::Owner) {
  if(reload)return false;reload=trace.calls[i].target;reload_index=i;
 }
 if(!reload)return false;
 uintptr_t branch=0,unchanged=0;
 for(unsigned i=0;i<trace.comparisons;i++) {
  const auto& c=trace.compares[i];
  if(c.left.a!=c.right.a||c.left.b==c.right.b||c.branch<=trace.calls[reads[3]].pc)continue;
  bool pair=false;
  for(unsigned j=0;j<2;j++)pair|=(c.left.b==reads[j]&&c.right.b==reads[j+2])||(c.right.b==reads[j]&&c.left.b==reads[j+2]);
  if(!pair)continue;
  uintptr_t end=0;uint32_t word=0;
  if(!code.Read(c.branch,&word)||!ThemeBranch(c.branch,word,&end)||end<=c.branch)return false;
  uint32_t destination=0;unsigned rt=0,rn=0;int frame=0;
  if(end>=ThemeEnd(code,entry)||!code.Read(end,&destination))return false;
  // Older code checks color equality in the unchanged-enable arm. Validate
  // those live values at the split, before the other arm's calls clobber them.
  bool color_arm=false;
  if(ThemeCmp(destination,&rn,&rt)) {
   const auto& a=c.destination_left;const auto& b=c.destination_right;
   color_arm=a.kind==ThemeValue::Value&&b.kind==ThemeValue::Value&&a.a==b.a&&
    ((a.b==reads[1]&&b.b==reads[3])||(b.b==reads[1]&&a.b==reads[3]));
  }
  if(destination!=0xaa1603e0u&&!color_arm&&
     (!ThemeUnscaled(destination,true,true,&rt,&rn,&frame)||rt!=0||rn!=29))return false;
  // The equal path skips exactly the native refresh, not settings reads.
  unsigned effects=0;
  for(unsigned k=reads[3]+1;k<trace.count;k++)if(trace.calls[k].pc>c.branch&&trace.calls[k].pc<end&&trace.calls[k].args[1].kind==ThemeValue::Owner) {
   effect=trace.calls[k].target;++effects;
  }
  if(effects!=1||effect==reload||branch)return false;
  branch=c.branch;unchanged=end;
 }
 if(!branch)return false;
 if(!ThemeDecisionWindow(code,entry,branch,trace,reads))return false;
 // Older compilers read the enable Rx once more in the unchanged/logging arm.
 // Extra reads before the proven split would change the four-value contract.
 for(unsigned i=4;i<size;i++)if(trace.calls[reads[i]].pc<unchanged||trace.calls[reads[i]].target!=getter)return false;
 for(unsigned i=0;i<trace.count;i++)if(trace.calls[i].args[1].kind==ThemeValue::Call) {
  const auto source=trace.calls[i].args[1].b;
  if(source==reload_index)await=trace.calls[i].target;
 }
 bool effect_await=false;
 for(unsigned i=0;i<trace.count;i++)effect_await|=trace.calls[i].target==await&&trace.calls[i].args[1].kind==ThemeValue::Call&&trace.calls[i].args[1].a==effect;
 if(!await||!effect_await)return false;
 ThemeTrace settings{};if(!ThemeTraceBody(code,reload,&settings)||!ThemeHasField(settings,f0)||!ThemeHasField(settings,f1))return false;
 uintptr_t keys[2]{},scope=0,reader=0;unsigned count=0;
 for(unsigned i=0;i<settings.count;i++) {
  const auto& c=settings.calls[i];if(c.args[1].kind!=ThemeValue::Pool||c.args[2].kind!=ThemeValue::Pool)continue;
  if(count==2)return false;
  if(count&&(scope!=c.args[2].a||reader!=c.target||keys[0]==c.args[1].a))return false;
  keys[count++]=c.args[1].a;scope=c.args[2].a;reader=c.target;
 }
 if(count!=2)return false;
 *out={entry,branch-12,reload,effect,getter,await,{static_cast<uint32_t>(f0),static_cast<uint32_t>(f1)},{keys[0],keys[1]},scope};return true;
}
bool ThemeDecodeRegistration(const ThemeCode& code,uintptr_t entry,const ThemeRefresh& refresh,uint32_t* flag) {
 ThemeTrace trace{};if(!ThemeTraceBody(code,entry,&trace)||!trace.guard_field||trace.guard_field!=trace.set_field)return false;
 unsigned uri[2]{},observers[2]{},uris=0,observations=0;
 for(unsigned i=0;i<trace.count;i++) {
  const auto& c=trace.calls[i];
  if(c.args[1].kind==ThemeValue::Pool&&c.args[2].kind==ThemeValue::Pool&&c.args[2].a==refresh.settings) {
   if(uris==2||c.args[1].a!=refresh.keys[uris])return false;uri[uris++]=i;
  }
  if(c.args[2].kind==ThemeValue::Call&&c.args[3].kind==ThemeValue::Pool&&
     c.args[5].kind==ThemeValue::Boolean&&c.args[5].a==0x30&&c.args[6].kind==ThemeValue::Call) {
   if(observations==2)return false;observers[observations++]=i;
  }
 }
 if(uris!=2||observations!=2||!(uri[0]<observers[0]&&observers[0]<uri[1]&&uri[1]<observers[1]))return false;
 const auto& a=trace.calls[observers[0]];const auto& b=trace.calls[observers[1]];
 if(trace.calls[uri[0]].target!=trace.calls[uri[1]].target||a.target!=b.target||
    a.args[2].a!=b.args[2].a||a.args[6].a!=b.args[6].a||a.args[3].a==b.args[3].a)return false;
 // Both URI futures must be awaited using the same async runtime as the refresh.
 for(unsigned k=0;k<2;k++) {
  bool found=false;
  for(unsigned i=uri[k]+1;i<observers[k];i++)found|=trace.calls[i].target==refresh.await&&
   trace.calls[i].args[1].kind==ThemeValue::Call&&trace.calls[i].args[1].b==uri[k];
  if(!found)return false;
 }
 *flag=trace.guard_field;return *flag<128;
}
bool ThemeAllocation(const ThemeCode& code,uintptr_t at,uint32_t* cid) {
 uint32_t a=0,b=0,c=0;if(!code.Read(at,&a)||!code.Read(at+4,&b)||!code.Read(at+8,&c))return false;
 if((a&0xffe0001fu)!=0xd2800002u||(b&0xffe0001fu)!=0xf2a00002u||(c&0xfc000000u)!=0x14000000u)return false;
 *cid=((((a>>5u)&65535u)|(((b>>5u)&65535u)<<16u))>>12u)&0xfffffu;return *cid!=0;
}
struct ThemeGraph {ThemeRefresh refresh{};uintptr_t owner=0,bootstrap=0;IconThemeObserverLayout observer{};};
bool ThemeBootstrapCid(const ThemeCode& code,const ThemeTrace& trace,uintptr_t reload,uint32_t* cid) {
 unsigned matches=0;
 for(unsigned i=0;i<trace.count;i++)if(trace.calls[i].target==reload&&trace.calls[i].args[1].kind==ThemeValue::Call) {
  const auto allocation=trace.calls[i].args[1].a;
  if(!ThemeAllocation(code,allocation,cid))continue;
  bool registered=false;
  for(unsigned j=0;j<i;j++)registered|=trace.calls[j].target!=reload&&
   trace.calls[j].args[1].kind==ThemeValue::Call&&trace.calls[j].args[1].a==allocation;
  if(!registered)return false;
  ++matches;
 }
 return matches==1;
}
template<class Visitor> bool ThemeFunctions(const ThemeCode& code,Visitor visitor) {
 for(size_t i=0;i<code.image.load_count;i++) {
  const auto& s=code.image.loads[i];if(!(s.flags&kFlagExec))continue;
  for(uintptr_t pc=s.start;pc+8<=s.end;pc+=4)if(IsDartPrologue(code.image,pc)&&!visitor(pc))return false;
 }
 return true;
}
bool ThemeFindGraph(const ThemeCode& code,ThemeGraph* result,uint32_t* count) {
 ThemeRefresh refresh{};unsigned matches=0;
 ThemeFunctions(code,[&](uintptr_t pc){ThemeRefresh next{};if(ThemeDecodeRefresh(code,pc,&next)){refresh=next;++matches;}return true;});
 if(matches!=1){*count=matches;return false;}
 ThemeGraph graph{};graph.refresh=refresh;unsigned owners=0,bootstraps=0;
 ThemeFunctions(code,[&](uintptr_t pc) {
  bool publishes=false;
  for(unsigned i=2;i<24;i++) {
   uint32_t word=0;if(!code.Read(pc+i*4,&word)||IsDartPrologue(code.image,pc+i*4))break;
   publishes|=(word&0xffc003ffu)==0xf9000001u;
  }
  if(!publishes)return true;
  ThemeTrace trace{};if(!ThemeTraceBody(code,pc,&trace))return true;
  uint32_t flag=0;
  if(trace.static_slot&&trace.registration&&ThemeDecodeRegistration(code,trace.registration,refresh,&flag)) {
   graph.owner=pc;graph.observer.static_slot=trace.static_slot;graph.observer.registered_field=flag;
   graph.observer.registration=trace.registration;++owners;
  }
  return true;
 });
 for(size_t s=0;s<code.image.load_count;s++) {
  const auto& segment=code.image.loads[s];if(!(segment.flags&kFlagExec))continue;
  for(uintptr_t call=segment.start;call+4<=segment.end;call+=4) {
   uint32_t word=0;uintptr_t target=0;
   if(!code.Read(call,&word)||!IsBl(word)||!DecodeBlTarget(call,word,&target)||target!=refresh.reload)continue;
   const auto pc=ThemeStart(code,call);
   ThemeTrace trace{};if(!pc||!ThemeTraceBody(code,pc,&trace))continue;
   uint32_t cid=0;
   if(ThemeBootstrapCid(code,trace,refresh.reload,&cid)) {
   graph.bootstrap=pc;graph.observer.cid=cid;++bootstraps;
   }
  }
 }
 *count=owners==1?bootstraps:owners;
 if(owners!=1||bootstraps!=1)return false;*result=graph;return true;
}
bool ThemeDecodeSupport(const ThemeCode& code,uintptr_t sdk,uintptr_t* site) {
 // Smi/int SDK admission remains native. Only the later local region bool is replaced.
 uint32_t w[5]{};for(unsigned i=0;i<5;i++)if(!code.Read(sdk+i*4,&w[i]))return false;
 if(w[0]!=0x93417c20u||w[1]!=0x36000041u||w[2]!=0xf8407020u||
    w[3]!=0xf100841fu||(w[4]&0xff00001fu)!=0x5400000au)return false;
 const auto entry=ThemeStart(code,sdk);if(!entry)return false;
 uintptr_t admitted=0;if(!ThemeBranch(sdk+16,w[4],&admitted)||admitted<=sdk||admitted>sdk+256)return false;
 uintptr_t region=0;unsigned tests=0;
 for(uintptr_t pc=admitted;pc<admitted+256;pc+=4) {
  uint32_t word=0;unsigned reg=0;if(!code.Read(pc,&word))return false;
  if(!ThemeTest(word,4,false,&reg)||reg!=1)continue;
  unsigned rt=0,rn=0;int context=0,field=0;uint32_t a=0,b=0,c=0,d=0;
  if(!code.Read(pc-16,&a)||!code.Read(pc-12,&b)||!code.Read(pc-8,&c)||!code.Read(pc-4,&d)||
     !ThemeUnscaled(a,true,false,&rt,&rn,&field)||rt!=1||rn!=0||field<=0||b!=0x8b1c8021u||
     !ThemeUnscaled(c,true,true,&rt,&rn,&context)||rt!=2||rn!=29||
     !ThemeUnscaled(d,false,false,&rt,&rn,&field)||rt!=1||rn!=2||field<=0)continue;
  uintptr_t success=0;if(!ThemeBranch(pc,word,&success)||success<=pc||success>pc+192)return false;
  uintptr_t init=0,closure=0,log=0,async_return=0;unsigned blocks=0;
  const uintptr_t starts[]={sdk+20,pc+4,success};
  for(auto start:starts) {
   uintptr_t calls[4]{};unsigned count=0;bool returned=false;
   for(uintptr_t at=start;at<start+192;at+=4) {
    uint32_t v=0;if(!code.Read(at,&v))return false;
    if(IsBl(v)){if(count==4||!ThemeCall(code,at,&calls[count]))return false;++count;}
    if((v&0xfc000000u)==0x14000000u&&at>=start+4) {
     uint32_t before=0;if(!code.Read(at-4,&before))return false;
     if(before!=0x9100c2c0u&&before!=0x910082c0u)continue;
     uintptr_t target=0;if(!DecodeBlTarget(at,v|0x80000000u,&target))return false;
     if(count!=3)return false;
     if(!blocks){init=calls[0];closure=calls[1];log=calls[2];async_return=target;}
     else if(init!=calls[0]||closure!=calls[1]||log!=calls[2]||async_return!=target)return false;
     if((blocks<2&&before!=0x9100c2c0u)||(blocks==2&&before!=0x910082c0u))return false;
     returned=true;++blocks;break;
    }
   }
   if(!returned)return false;
  }
  if(blocks!=3)return false;
  // The region getter belongs to the admitted path, separate from logging helpers.
  unsigned getter_calls=0;
  for(uintptr_t at=admitted;at<pc-16;at+=4) {uint32_t v=0;if(!code.Read(at,&v))return false;
   if(IsBl(v)){uintptr_t target=0;if(!ThemeCall(code,at,&target))return false;if(target!=init){region=target;++getter_calls;}}
  }
  if(getter_calls!=1||!region||region==closure||region==log)return false;
  if(!ThemeNoInteriorBranch(code,entry,pc-8,16))return false;
  *site=pc-8;++tests;
 }
 return tests==1;
}
bool FindIconThemeSupport(const Image& image,uintptr_t* out,uint32_t* count) {
 ThemeCode code{image};*count=0;
 for(size_t i=0;i<image.load_count;i++) {
  const auto& s=image.loads[i];if(!(s.flags&kFlagExec))continue;
  for(uintptr_t pc=s.start;pc+20<=s.end;pc+=4) {
   uint32_t w=0;if(!ReadInsn(image,pc,&w)||w!=0x93417c20u)continue;
   uintptr_t site=0;if(ThemeDecodeSupport(code,pc,&site)){*out=site;++*count;}
  }
 }
 return *count==1;
}
bool FindIconThemeGraphSite(const Image& image,unsigned site,uintptr_t* out,uint32_t* count) {
 ThemeGraph graph{};if(!ThemeFindGraph({image},&graph,count))return false;
 *out=site==0?graph.refresh.window:site==1?graph.owner:graph.bootstrap;return true;
}
bool FindIconThemeRefresh(const Image& image,uintptr_t* out,uint32_t* count){return FindIconThemeGraphSite(image,0,out,count);}
bool FindThemeOwner(const Image& image,uintptr_t* out,uint32_t* count){return FindIconThemeGraphSite(image,1,out,count);}
bool FindThemeBootstrap(const Image& image,uintptr_t* out,uint32_t* count){return FindIconThemeGraphSite(image,2,out,count);}
constexpr uint32_t kThemeSupportVerify[]={0xf84003a2u,0xb8000041u,0x36200001u};
constexpr uint32_t kThemeSupportVerifyMask[]={0xffe00fffu,0xffe00fffu,0xfff8001fu};
constexpr uint32_t kThemeRefreshVerify=0xf84003a0u,kThemeRefreshVerifyMask=0xffe00fe0u;
const BytePattern kThemeSupportAdmission{reinterpret_cast<const uint8_t*>(kThemeSupportVerify),reinterpret_cast<const uint8_t*>(kThemeSupportVerifyMask),sizeof(kThemeSupportVerify)};
const BytePattern kThemeRefreshAdmission{reinterpret_cast<const uint8_t*>(&kThemeRefreshVerify),reinterpret_cast<const uint8_t*>(&kThemeRefreshVerifyMask),4};
const SiteSpec kIconThemeSites[]={
 {"support",{},FindIconThemeSupport,true,kThemeSupportAdmission,0},
 {"refresh",{},FindIconThemeRefresh,true,kThemeRefreshAdmission,0},
 {"owner",{},FindThemeOwner,true,{},0},
 {"bootstrap",{},FindThemeBootstrap,true,{},0}
};
}
const TargetSpec kIconThemeTarget{"desktop_icon_theme","libapp.so",kIconThemeSites,4};

bool ValidateIconThemeTargets(const Image& image,uintptr_t support,uintptr_t refresh,const uint8_t* support_original,const uint8_t* refresh_original) {
 ThemeCode code{image,{support,refresh},{support_original,refresh_original}};
 unsigned matches=0;
 for(uintptr_t pc=support>256?support-256:0;pc<support;pc+=4) {
  uint32_t word=0;if(!code.Read(pc,&word)||word!=0x93417c20u)continue;
  uintptr_t found=0;if(ThemeDecodeSupport(code,pc,&found)&&found==support)++matches;
 }
 if(matches!=1)return false;
 uintptr_t entry=refresh;
 for(unsigned i=0;i<256&&entry>=4;i++,entry-=4)if(IsDartPrologue(image,entry)) {
  ThemeRefresh decoded{};return ThemeDecodeRefresh(code,entry,&decoded)&&decoded.window==refresh;
 }
 return false;
}
bool DecodeIconThemeObserver(const Image& image,uintptr_t owner,uintptr_t bootstrap,uintptr_t refresh,IconThemeObserverLayout* out,const uint8_t* refresh_original) {
 if(!out)return false;
 ThemeCode code{image,{0,refresh},{nullptr,refresh_original}};ThemeTrace life{},init{};
 if(!ThemeTraceBody(code,owner,&life)||!life.static_slot||!life.registration||!ThemeTraceBody(code,bootstrap,&init))return false;
 // Revalidate the whole relation after installation by overlaying only the
 // owned original decision window, including its native branch destinations.
 ThemeRefresh relation{};uintptr_t entry=refresh;
 for(unsigned i=0;i<256&&entry>=4;i++,entry-=4)if(IsDartPrologue(image,entry))break;
 if(!ThemeDecodeRefresh(code,entry,&relation)||relation.window!=refresh)return false;
 uint32_t flag=0,cid=0;
 if(!ThemeDecodeRegistration(code,life.registration,relation,&flag))return false;
 if(!ThemeBootstrapCid(code,init,relation.reload,&cid))return false;
 *out={static_cast<uint32_t>(life.static_slot),flag,cid,life.registration};return true;
}
