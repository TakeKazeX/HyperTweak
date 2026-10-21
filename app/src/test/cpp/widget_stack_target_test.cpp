// SPDX-License-Identifier: Apache-2.0
#include "dart_targets.h"
#include "dart_resolution_cache.h"
#include <assert.h>
#include <fstream>
#include <iterator>
#include <vector>
#include <stdio.h>
#include <string.h>
using namespace hypertweak::native;
using namespace hypertweak::native::dart;
uint32_t Word(const std::vector<uint8_t>& b, uintptr_t at) { uint32_t w;memcpy(&w,b.data()+at,4);return w; }
void Put(std::vector<uint8_t>& b,uintptr_t at,uint32_t w) {memcpy(b.data()+at,&w,4);}
TargetResult Resolve(std::vector<uint8_t>& bytes) {Image i{};assert(ParseImage(bytes.data(),&i));return ResolveTarget(i,kWidgetStackTarget);}
int main(int argc,char** argv) {
    assert(argc==2);std::ifstream f(argv[1],std::ios::binary);
    std::vector<uint8_t> original{std::istreambuf_iterator<char>(f),{}}; assert(!original.empty());
    auto b=original; auto result=Resolve(b); assert(result.resolved && result.verified);
    const auto drag=result.sites[0].offset,target=result.sites[1].offset,type=result.sites[2].offset;
    assert(drag==0xb80020 && target==0xb80094 && type==0x1a39144); // fixture evidence only
    for (const auto& s:result.sites) if(s.name) assert(s.candidates==1);
    Image image{};assert(ParseImage(b.data(),&image)); WidgetStackLayout layout{};
    assert(DecodeWidgetStackLayout(image,drag,target,type,&layout));
    assert(layout.field==0xfb && layout.cid==0x877);
    uint8_t identity[kDartIdentitySize]={4,0,0,0,16,0,0,0,3,0,0,0,'G','N','U',0};
    memset(identity+16,0x31,16);
    DartCacheRecord record{}; assert(EncodeDartCache(image,kWidgetStackTarget,identity,77,result,&record));
    TargetResult cached{};assert(DecodeDartCache(image,kWidgetStackTarget,identity,77,record,&cached));
    auto changed=record;changed.offsets[1]=changed.offsets[0];assert(!DecodeDartCache(image,kWidgetStackTarget,identity,77,changed,&cached));
    // Moving a field requires agreement across both gates and serialization.
    for (auto p:{drag,target,type+64}) Put(b,p,(Word(b,p)&~0x1ff000u)|(0xf7u<<12));
    for(auto p:{drag-8,target-8,type}) Put(b,p,(Word(b,p)&~0x3ffc00u)|(0x876u<<10));
    assert(Resolve(b).resolved);assert(DecodeWidgetStackLayout(image,drag,target,type,&layout));
    assert(layout.field==0xf7 && layout.cid==0x876);
    assert(!DecodeDartCache(image,kWidgetStackTarget,identity,77,record,&cached));
    b=original;Put(b,target,(Word(b,target)&~0x1ff000u)|(0xf7u<<12));assert(!Resolve(b).resolved);
    b=original;Put(b,target-8,(Word(b,target-8)&~0x3ffc00u)|(0x876u<<10));assert(!Resolve(b).resolved);
    b=original;Put(b,drag+8,Word(b,drag+8)+0x20);assert(!Resolve(b).resolved);
    b=original;Put(b,0xb80128,Word(b,0xb80128)+0x1000);assert(!Resolve(b).resolved); // spans disagree
    b=original;Put(b,0xb80224,0xf1002c3fu);assert(!Resolve(b).resolved); // capacity changed
    b=original;Put(b,type+20,0xd2800083u);assert(!Resolve(b).resolved); // wrong serialization kind
    // Relocate the whole gate/policy block. PC-relative local relationships survive.
    b=original; uintptr_t duplicate=0;
    for(size_t i=0;i<image.load_count;++i) if((image.loads[i].flags&5u)==5u) {duplicate=image.loads[i].start+8192;break;}
    assert(duplicate && Contains(image,duplicate,1024,5));
    memcpy(b.data()+duplicate,b.data()+drag-8,1024);
    assert(Resolve(b).sites[0].candidates==2 && !Resolve(b).resolved);
    memset(b.data()+drag-8,0,1024);
    result=Resolve(b);assert(result.resolved && result.sites[0].offset==duplicate+8);
    puts("PASS widget stack: unique paired gates, relocated family, adaptive CID/field, span/capacity/serializer/branch rejection and persistent cache evidence");
}
