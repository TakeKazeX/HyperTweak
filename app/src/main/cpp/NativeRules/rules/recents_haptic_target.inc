// SPDX-License-Identifier: Apache-2.0
// Verified instruction contracts, not launcher version/address tables.
namespace {
const uint32_t kScrollWords[] = {
    0x0u, 0x0u, 0x0u, 0x0u,
    0xd34c7c00u, 0xf81f83a0u, 0xd2800011u, 0xeb11001fu,
    0x54000181u, 0xf9403f40u, 0xf9400000u, 0xf9400370u,
    0x6b10001fu, 0x54000081u, 0x91400362u, 0xf9400042u,
    0x94000000u, 0xaa0003e1u, 0xf9400364u, 0x94000000u,
    0xf85f83a0u, 0xd2800011u, 0xeb11001fu, 0x54000161u,
    0xf9403f40u, 0xf9400000u, 0xf9400370u, 0x6b10001fu,
    0x54000081u, 0x91400362u, 0xf9400042u, 0x94000000u,
    0xaa0003e1u, 0x94000000u, 0x9100c2c0u, 0xaa1d03efu,
    0xa8c179fdu, 0xd65f03c0u,
};
const uint32_t kScrollMasks[] = {
    0x0u, 0x0u, 0x0u, 0x0u,
    0xffffffffu, 0xffffffffu, 0xffe0001fu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffc003ffu, 0xffc003ffu,
    0xffffffffu, 0xffffffffu, 0xffc003ffu, 0xffc003ffu,
    0xfc000000u, 0xffffffffu, 0xffc003ffu, 0xfc000000u,
    0xffffffffu, 0xffe0001fu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffc003ffu, 0xffc003ffu, 0xffffffffu,
    0xffffffffu, 0xffc003ffu, 0xffc003ffu, 0xfc000000u,
    0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu,
};
const uint32_t kBuilderWords[] = {
    0xa9bf79fdu, 0xaa0f03fdu, 0xd100a1efu, 0xf94013a0u,
    0xb8417001u, 0x8b1c8021u, 0xb840f020u, 0x8b1c8000u,
    0xb840b001u, 0x8b1c8021u, 0x6b16003fu, 0x54000480u,
    0xb844f020u, 0x8b1c8000u, 0xf81e83a0u, 0xfc413020u,
    0xfc1d83a0u, 0xb841b022u, 0x8b1c8042u, 0xf81f03a2u,
    0xfc43b021u, 0xfc1e03a1u, 0xb8453023u, 0x8b1c8063u,
    0xf81f83a3u, 0x94000000u, 0xfc5d83a0u, 0xfc00b000u,
    0xf9400ba1u, 0xb8013001u, 0xf85f03a1u, 0xb8017001u,
    0x91400361u, 0xf9400021u, 0xb801b001u, 0x91400361u,
    0xf9400021u, 0xb801f001u, 0xfc5e03a0u, 0xfc027000u,
    0xf85f83a1u, 0xb802f001u, 0xf85e83a1u, 0xb8007001u,
    0xaa1d03efu, 0xa8c179fdu, 0xd65f03c0u, 0x94000000u,
};
const uint32_t kBuilderMasks[] = {
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffc003ffu, 0xffc003ffu, 0xffffffffu, 0xffc003ffu,
    0xffc003ffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xfc000000u,
};
const uint32_t kScrollEntry[] = {
    0xa9bf79fdu, 0xaa0f03fdu, 0xd10021efu, 0xf85ff040u,
};
uint32_t HapticWord(const Image& image, uintptr_t at) {
    uint32_t word = 0; ReadInsn(image, at, &word); return word;
}
bool HapticEnd(const Image& image, uintptr_t begin, uintptr_t* end) {
    if (!IsDartPrologue(image, begin)) return false;
    for (size_t i=12; i<1024; i+=4) {
        if (!Contains(image,begin+i,4,kFlagRead|kFlagExec)) return false;
        if (HapticWord(image,begin+i)==kDartReturn &&
            HapticWord(image,begin+i-4)==kDartPopFrame && HapticWord(image,begin+i-8)==kDartRestoreFrame) {
            *end=begin+i+4; return true;
        }
    }
    return false;
}
bool HapticAllocation(const Image& image, uintptr_t call, uint32_t* cid) {
    uintptr_t target=0;
    if (!IsBl(HapticWord(image,call)) || !DecodeBlTarget(call,HapticWord(image,call),&target) ||
        !Contains(image,target,12,kFlagRead|kFlagExec)) return false;
    const auto lo=HapticWord(image,target), hi=HapticWord(image,target+4);
    if ((lo&0xffe0001fu)!=0xd2800002u || (hi&0xffe0001fu)!=0xf2a00002u ||
        (HapticWord(image,target+8)&0xfc000000u)!=0x14000000u) return false;
    *cid=((((lo>>5)&0xffffu)|(((hi>>5)&0xffffu)<<16))>>12)&0xfffffu;
    return *cid>0;
}
bool HapticStore(uint32_t word, uint32_t opcode, unsigned reg, unsigned base, uint16_t* field) {
    if ((word&0xffe00fffu)!=(opcode|(base<<5)|reg)) return false;
    *field=(word>>12)&0x1ffu;
    return *field>=7 && *field<256 && *field%4==3;
}
struct HapticUpdate {
    uint32_t cid=0, number=0;
    uint16_t depth=0, metrics=0, drag=0, delta=0;
};
bool HapticFields(uint16_t depth, uint16_t metrics, uint16_t drag, uint16_t delta) {
    return depth>=7 && depth%8==7 && metrics>=depth+8 && metrics%4==3 &&
        drag>=metrics+8 && drag%4==3 && delta==drag+4 && delta<256;
}
// Out-of-line notification constructor: follow the actual BL, validate the
// receiver/argument moves, write barriers and inherited depth/metrics stores.
// The optional-argument parser can grow without moving these semantic stores.
bool HapticConstructor(const Image& image, uintptr_t at, HapticUpdate* out) {
    uintptr_t end=0;
    if (!HapticEnd(image,at,&end) || HapticWord(image,at+8)!=0xaa0303f0u ||
        HapticWord(image,at+12)!=0xaa0103e3u || HapticWord(image,at+16)!=0xaa1003e1u) return false;
    unsigned found=0;
    for (uintptr_t p=at+20;p+168<=end;p+=4) {
        uint16_t drag=0,delta=0,metrics=0,context=0,depth=0;
        if (!HapticStore(HapticWord(image,p),0xb8000000u,0,3,&drag)) continue;
        const uint32_t barrier[]={0x385ff070u,0x385ff011u,0x8a500a30u,0xea5c821fu,0x54000040u};
        bool valid=true;
        uintptr_t barrier_target=0;
        for (unsigned block=0;block<4;++block) {
            for (unsigned j=0;j<5;++j) valid &= HapticWord(image,p+block*32+4+j*4)==barrier[j];
            uintptr_t target=0;
            valid &= IsBl(HapticWord(image,p+block*32+24)) &&
                DecodeBlTarget(p+block*32+24,HapticWord(image,p+block*32+24),&target) &&
                Contains(image,target,4,kFlagRead|kFlagExec);
            if(block && target!=barrier_target)valid=false;
            barrier_target=target;
        }
        if (!valid || HapticWord(image,p+28)!=0xaa0403e0u ||
            HapticWord(image,p+60)!=0xaa0103e0u || HapticWord(image,p+92)!=0xaa0203e0u ||
            !HapticStore(HapticWord(image,p+32),0xb8000000u,0,3,&delta) ||
            !HapticStore(HapticWord(image,p+64),0xb8000000u,0,3,&metrics) ||
            !HapticStore(HapticWord(image,p+96),0xb8000000u,0,3,&context) ||
            !HapticStore(HapticWord(image,p+124),0xf8000000u,31,3,&depth) ||
            context!=metrics+4 || !HapticFields(depth,metrics,drag,delta)) continue;
        // Optional depth is unboxed before writing the very same primitive slot.
        if (HapticWord(image,p+128)!=0x6b1600ffu || HapticWord(image,p+132)!=0x540000a0u ||
            HapticWord(image,p+136)!=0x93417ce1u || HapticWord(image,p+140)!=0x36000047u || HapticWord(image,p+144)!=0xf84070e1u ||
            HapticWord(image,p+148)!=(0xf8000061u|(static_cast<uint32_t>(depth)<<12))) continue;
        out->depth=depth;out->metrics=metrics;out->drag=drag;out->delta=delta;++found;
    }
    return found==1;
}
bool HapticUpdateContract(const Image& image, uintptr_t at, HapticUpdate* out) {
    uintptr_t end=0;
    if (!out || !HapticEnd(image,at,&end)) return false;
    HapticUpdate result{};
    unsigned allocations=0, boxes=0;
    uintptr_t constructor=0;
    uintptr_t constructor_dispatch=0;
    unsigned constructor_calls=0;
    for (uintptr_t p=at;p+4<=end;p+=4) {
        uint32_t cid=0;
        if (HapticAllocation(image,p,&cid)) {
            if (result.cid && result.cid!=cid) return false;
            result.cid=cid;++allocations;
        }
        const auto lo=HapticWord(image,p),hi=HapticWord(image,p+4);
        if (p+16<=end && (lo&0xffe00000u)==0xd2800000u && (hi&0xffe00000u)==0xf2a00000u && (lo&31)==(hi&31)) {
            const auto store=HapticWord(image,p+8), payload=HapticWord(image,p+12);
            const auto base=(store>>5)&31u;
            if (store==(0xf81ff000u|(base<<5)|(lo&31)) && payload==(0xfc007000u|(base<<5))) {
                const auto number=((((lo>>5)&0xffffu)|(((hi>>5)&0xffffu)<<16))>>12)&0xfffffu;
                if (!number || (result.number && result.number!=number)) return false;
                result.number=number;++boxes;
            }
        }
        uintptr_t destination=0;
        HapticUpdate fields{};
        if (IsBl(lo) && DecodeBlTarget(p,lo,&destination) && HapticConstructor(image,destination,&fields)) {
            if (constructor && constructor!=destination) return false;
            uint8_t rt=0,rn=0;uint32_t pool=0;uintptr_t dispatch=0;
            if(p<at+16 || p+16>end || HapticWord(image,p-16)!=0xaa0003e1u ||
                (HapticWord(image,p-12)&0xffe00fffu)!=0xf84003a2u ||
                (HapticWord(image,p-8)&0xffe00fffu)!=0xf84003a3u ||
                !IsLdrImm(HapticWord(image,p-4),&rt,&rn,&pool) || rt!=4 || rn!=27 ||
                (HapticWord(image,p+4)&0xffe00fffu)!=0xf84003a1u ||
                (HapticWord(image,p+8)&0xffe00fffu)!=0xf84003a2u ||
                !IsBl(HapticWord(image,p+12)) || !DecodeBlTarget(p+12,HapticWord(image,p+12),&dispatch) ||
                (constructor_dispatch && constructor_dispatch!=dispatch))return false;
            constructor_dispatch=dispatch;
            constructor=destination;++constructor_calls;
            result.depth=fields.depth;result.metrics=fields.metrics;result.drag=fields.drag;result.delta=fields.delta;
        }
    }
    if (allocations!=2 || boxes!=2 || result.cid==result.number) return false;
    if (constructor_calls) {
        if (constructor_calls!=2) return false;
    } else {
        // Inlined constructor: each producer branch writes delta, metrics,
        // context and depth into the same allocation, then dispatches it.
        unsigned stores=0,drag_candidates=0;uintptr_t dispatch=0;
        uint16_t possible_drag[4]{};
        for (uintptr_t p=at+20;p+16<=end;p+=4) {
            uint16_t depth=0,metrics=0,delta=0,context=0,drag=0;
            if (HapticStore(HapticWord(image,p),0xf8000000u,31,1,&depth)) {
                const auto a=HapticWord(image,p-20),b=HapticWord(image,p-12),c=HapticWord(image,p-4);
                if (!HapticStore(a,0xb8000000u,a&31,1,&delta) ||
                    !HapticStore(b,0xb8000000u,b&31,1,&metrics) ||
                    !HapticStore(c,0xb8000000u,c&31,1,&context) || context!=metrics+4 ||
                    HapticWord(image,p+4)!=0xaa0103e2u || !IsBl(HapticWord(image,p+12))) return false;
                uintptr_t target=0;if(!DecodeBlTarget(p+12,HapticWord(image,p+12),&target))return false;
                if (stores && (result.depth!=depth || result.metrics!=metrics || result.delta!=delta || dispatch!=target)) return false;
                result.depth=depth;result.metrics=metrics;result.delta=delta;dispatch=target;++stores;
            }
            if (HapticWord(image,p-8)==0xaa0003e1u &&
                (HapticWord(image,p-4)&0xffe00fffu)==0xf84003a0u &&
                HapticStore(HapticWord(image,p),0xb8000000u,0,1,&drag)) {
                if(drag_candidates==4)return false;
                possible_drag[drag_candidates++]=drag;
            }
        }
        unsigned drag_stores=0;
        for(unsigned i=0;i<drag_candidates;++i) if(possible_drag[i]!=result.delta) {
            result.drag=possible_drag[i];++drag_stores;
        }
        if (stores!=2 || drag_stores!=1 || !HapticFields(result.depth,result.metrics,result.drag,result.delta)) return false;
    }
    *out=result;return true;
}
struct HapticMetrics { uint32_t cid=0; uint16_t minimum=0,maximum=0,pixels=0; uintptr_t allocation=0; };
bool HapticMetricsContract(const Image& image, uintptr_t at, HapticMetrics* out) {
    uintptr_t end=0;
    if (!out || !HapticEnd(image,at,&end)) return false;
    HapticMetrics result{};unsigned allocations=0,tails=0;
    int slots[4]{};bool saved[4]{};
    const unsigned sources[]={2,0,3,4};
    for (uintptr_t p=at;p+4<=end;p+=4) {
        uint32_t cid=0;
        if (HapticAllocation(image,p,&cid)) { result.cid=cid;result.allocation=p;++allocations; }
        if(!allocations) for(unsigned i=0;i<4;++i) {
            const auto word=HapticWord(image,p);
            if((word&0xffe00fffu)==(0xf80003a0u|sources[i])) {
                const auto raw=(word>>12)&0x1ffu;
                const int displacement=raw<256 ? raw : static_cast<int>(raw)-512;
                if(displacement>=0 || saved[i])return false;
                slots[i]=displacement;saved[i]=true;
            }
        }
        if (p+44!=end || HapticWord(image,p+32)!=kDartRestoreFrame) continue;
        uint16_t fields[4]{};bool valid=true;
        for(unsigned i=0;i<4;++i) {
            valid &= (HapticWord(image,p+i*8)&0xffe00fffu)==0xf84003a1u;
            const auto raw=(HapticWord(image,p+i*8)>>12)&0x1ffu;
            const int displacement=raw<256 ? raw : static_cast<int>(raw)-512;
            valid &= saved[i] && displacement==slots[i];
            valid &= HapticStore(HapticWord(image,p+i*8+4),0xb8000000u,1,0,&fields[i]);
        }
        if (!valid || fields[1]!=fields[0]+4 || fields[2]!=fields[1]+4 || fields[3]!=fields[2]+4) continue;
        // The unboxed device-pixel-ratio store precedes these boxed range fields.
        uint16_t ratio=0;
        if (!HapticStore(HapticWord(image,p-4),0xfc000000u,0,0,&ratio) || ratio!=fields[3]+8) continue;
        result.minimum=fields[0];result.maximum=fields[1];result.pixels=fields[2];++tails;
    }
    if(allocations!=1 || tails!=1)return false;
    const auto frame_size=(HapticWord(image,at+8)>>10)&0xfffu;
    for(unsigned i=0;i<4;++i) {
        if(!saved[i] || static_cast<unsigned>(-slots[i])>frame_size)return false;
        for(unsigned j=0;j<i;++j)if(slots[i]==slots[j])return false;
    }
    // A page-position subtype has the same inherited fields plus another
    // unboxed page value. It is not FixedScrollMetrics: reject the extra store.
    for(uintptr_t p=at;p<end;p+=4) {
        const auto word=HapticWord(image,p), kind=word&0xffe00c00u;
        if (((word>>5)&31u)==0 && (kind==0xfc000000u || kind==0xb8000000u || kind==0xf8000000u) &&
            ((word>>12)&0x1ffu)>result.pixels+12u) return false;
    }
    *out=result;return true;
}
const uint32_t kUpdateSeed[]={0xa9bf79fdu,0xaa0f03fdu,0xd10001efu,0xaa0103e2u,0xf80003a1u,0xfc0003a0u,0xb8400043u,0x8b1c8063u};
const uint32_t kUpdateSeedMasks[]={0xffffffffu,0xffffffffu,0xffc003ffu,0xffffffffu,0xffe00fffu,0xffe00fffu,0xffe00fffu,0xffffffffu};
const uint32_t kMetricsSeed[]={0xa9bf79fdu,0xaa0f03fdu,0xd10001efu,0xb8400020u,0x8b1c8000u,0x6b16001fu,0x540000e0u,0xb8400022u,0x8b1c8042u,0x6b16005fu,0x54000060u,0xaa0003e2u,0x14000002u,0xaa1603e2u};
const uint32_t kMetricsSeedMasks[]={0xffffffffu,0xffffffffu,0xffc003ffu,0xffe00fffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffe00fffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu};
const BytePattern kUpdateSeedPattern={reinterpret_cast<const uint8_t*>(kUpdateSeed),reinterpret_cast<const uint8_t*>(kUpdateSeedMasks),sizeof(kUpdateSeed)};
const BytePattern kMetricsSeedPattern={reinterpret_cast<const uint8_t*>(kMetricsSeed),reinterpret_cast<const uint8_t*>(kMetricsSeedMasks),sizeof(kMetricsSeed)};
template<class Contract> bool FindHapticContract(const Image& image,const BytePattern& seed,
    bool (*decode)(const Image&,uintptr_t,Contract*),uintptr_t* output,uint32_t* candidates) {
    uintptr_t offsets[64]{};size_t count=0;uint32_t total=0;
    *candidates=0;
    if(!CollectBytes(image,seed,offsets,64,&count,&total) || total>64)return false;
    for(size_t i=0;i<count;++i) { Contract value{};if(decode(image,offsets[i],&value)){*output=offsets[i];++*candidates;} }
    return *candidates==1;
}
bool FindHapticUpdate(const Image& image,uintptr_t* output,uint32_t* candidates) {
    return FindHapticContract(image,kUpdateSeedPattern,HapticUpdateContract,output,candidates);
}
bool FindHapticMetrics(const Image& image,uintptr_t* output,uint32_t* candidates) {
    return FindHapticContract(image,kMetricsSeedPattern,HapticMetricsContract,output,candidates);
}
const SiteSpec kRecentsHapticSites[] = {
{"scroll", {reinterpret_cast<const uint8_t*>(kScrollWords),reinterpret_cast<const uint8_t*>(kScrollMasks),sizeof(kScrollWords)},nullptr,true,{reinterpret_cast<const uint8_t*>(kScrollEntry),nullptr,sizeof(kScrollEntry)},0},
{"builder", {reinterpret_cast<const uint8_t*>(kBuilderWords),reinterpret_cast<const uint8_t*>(kBuilderMasks),sizeof(kBuilderWords)},nullptr,true,{reinterpret_cast<const uint8_t*>(kBuilderWords),reinterpret_cast<const uint8_t*>(kBuilderMasks),sizeof(kBuilderWords)},0},
{"update", {},FindHapticUpdate,true,kUpdateSeedPattern,0},
{"metrics", {},FindHapticMetrics,true,kMetricsSeedPattern,0},
};
}
const TargetSpec kRecentsHapticTarget = {"recents_card_haptic", "libapp.so", kRecentsHapticSites, 4};
bool DecodeRecentsHapticLayout(const Image& image,uintptr_t scroll,uintptr_t builder,
                              uintptr_t update,uintptr_t metrics,RecentsHapticLayout* output) {
    if(!output || !MatchBytes(image,scroll,{reinterpret_cast<const uint8_t*>(kScrollWords),reinterpret_cast<const uint8_t*>(kScrollMasks),sizeof(kScrollWords)},kFlagRead|kFlagExec) ||
        !MatchBytes(image,builder,kRecentsHapticSites[1].pattern,kFlagRead|kFlagExec))return false;
    HapticUpdate u{};HapticMetrics m{};
    if(!HapticUpdateContract(image,update,&u) || !HapticMetricsContract(image,metrics,&m))return false;
    RecentsHapticLayout result{};
    result.start=(HapticWord(image,scroll+0x18)>>5)&0xffffu;
    result.end=(HapticWord(image,scroll+0x54)>>5)&0xffffu;
    result.update=u.cid;result.metrics=m.cid;result.number=u.number;
    result.depth=u.depth;result.notification_metrics=u.metrics;result.drag=u.drag;result.delta=u.delta;
    result.minimum=m.minimum;result.maximum=m.maximum;result.pixels=m.pixels;result.metrics_allocation=m.allocation;
    if(!result.start || !result.end || result.start==result.end || result.start==result.update || result.end==result.update)return false;
    *output=result;return true;
}
