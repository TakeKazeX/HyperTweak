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
uint32_t Word(const std::vector<uint8_t>& b,uintptr_t p) {uint32_t w;memcpy(&w,b.data()+p,4);return w;}
void Put(std::vector<uint8_t>& b,uintptr_t p,uint32_t w) {memcpy(b.data()+p,&w,4);}
TargetResult Resolve(std::vector<uint8_t>& b) {Image i{};assert(ParseImage(b.data(),&i));return ResolveTarget(i,kXiaoaiGestureOptionsTarget);}
int main(int argc,char** argv) {
    assert(argc==2);std::ifstream f(argv[1],std::ios::binary);
    std::vector<uint8_t> original{std::istreambuf_iterator<char>(f),{}};
    auto b=original;auto result=Resolve(b);assert(result.resolved&&result.verified);
    auto predicate=result.sites[0].offset,decoder=result.sites[1].offset;
    Image image{};assert(ParseImage(b.data(),&image));XiaoaiGestureLayout layout{};
    assert(DecodeXiaoaiGestureLayout(image,predicate,decoder,&layout));
    const auto old_mask=layout.mask_field;
    // Change the field consistently in the SSE producer and predicate consumer.
    for(auto p:{predicate+28,decoder+232}) Put(b,p,(Word(b,p)&~0x1ff000u)|((old_mask+8)<<12));
    assert(Resolve(b).resolved);assert(DecodeXiaoaiGestureLayout(image,predicate,decoder,&layout));
    assert(layout.mask_field==old_mask+8);
    b=original;Put(b,decoder+232,(Word(b,decoder+232)&~0x1ff000u)|((old_mask+8)<<12));assert(!Resolve(b).resolved);
    for(auto field:{0u,3u,256u,511u}) {
        b=original;
        for(auto p:{predicate+28,decoder+232}) Put(b,p,(Word(b,p)&~0x1ff000u)|(field<<12));
        assert(!Resolve(b).resolved);
    }
    // Individually aligned fields must still not overlap the eight-byte mask.
    b=original;Put(b,decoder+224,(Word(b,decoder+224)&~0x1ff000u)|(old_mask<<12));
    assert(!Resolve(b).resolved);
    b=original;uintptr_t trigger=0;assert(DecodeBlTarget(predicate+48,Word(b,predicate+48),&trigger));
    Put(b,trigger+116,0xd2800100u);assert(!Resolve(b).resolved); // double-tap mask no longer four
    b=original;Put(b,predicate+56,0x8a000002u);assert(!Resolve(b).resolved); // changed mask intersection
    b=original;uintptr_t allocation=0;assert(DecodeBlTarget(decoder+216,Word(b,decoder+216),&allocation));
    Put(b,allocation+8,0xd65f03c0u);assert(!Resolve(b).resolved);
    b=original;uintptr_t dest=0;
    for(size_t i=0;i<image.load_count;++i) if((image.loads[i].flags&5)==5) {dest=image.loads[i].start+4096;break;}
    assert(dest&&Contains(image,dest,88,5));memcpy(b.data()+dest,b.data()+predicate,88);
    const auto delta=static_cast<int64_t>(trigger)-static_cast<int64_t>(dest+48);
    Put(b,dest+48,0x94000000u|(static_cast<uint32_t>(delta/4)&0x3ffffffu));
    assert(!Resolve(b).resolved); // ambiguity is never first-match selection
    memset(b.data()+predicate,0,88);result=Resolve(b);assert(result.resolved&&result.sites[0].offset==dest);
    uint8_t identity[kDartIdentitySize]={4,0,0,0,16,0,0,0,3,0,0,0,'G','N','U',0};memset(identity+16,0x31,16);
    DartCacheRecord record{};assert(EncodeDartCache(image,kXiaoaiGestureOptionsTarget,identity,77,result,&record));
    TargetResult cached{};assert(DecodeDartCache(image,kXiaoaiGestureOptionsTarget,identity,77,record,&cached));
    assert(!DecodeDartCache(image,kXiaoaiGestureOptionsTarget,identity,78,record,&cached));
    Put(b,dest+4,0);assert(!DecodeDartCache(image,kXiaoaiGestureOptionsTarget,identity,77,record,&cached));
    puts("PASS XiaoAI: unique predicate/producer/trigger family, field relocation, function relocation, ambiguity/corruption and cache identity rejection");
}
