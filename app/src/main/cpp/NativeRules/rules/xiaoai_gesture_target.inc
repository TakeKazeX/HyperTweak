// SPDX-License-Identifier: Apache-2.0
// Config-item mask predicate, its SSE producer and the seven-way trigger decoder.
namespace {
constexpr uint32_t kXiaoaiPredicateWords[] = {
    0xa9bf79fdu, 0xaa0f03fdu, 0xd10021efu, 0xf9400fa0u, 0xb8400001u, 0x8b1c8021u,
    0xf9400ba0u, 0xf8400002u, 0xf81f83a2u, 0xb8400020u, 0x8b1c8000u, 0xaa0003e1u,
    0x94000000u, 0xf85f83a1u, 0x8a000022u, 0xb5000062u, 0x9100c2c0u, 0x14000002u,
    0x910082c0u, 0xaa1d03efu, 0xa8c179fdu, 0xd65f03c0u};
constexpr uint32_t kXiaoaiPredicateMasks[] = {
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffe00fffu, 0xffffffffu,
    0xffffffffu, 0xffe00fffu, 0xffffffffu, 0xffe00fffu, 0xffffffffu, 0xffffffffu,
    0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu};
const BytePattern kXiaoaiPredicatePattern{reinterpret_cast<const uint8_t*>(kXiaoaiPredicateWords), reinterpret_cast<const uint8_t*>(kXiaoaiPredicateMasks), sizeof(kXiaoaiPredicateWords)};
constexpr uint32_t kXiaoaiDecoderWords[] = {
    0xa9bf79fdu, 0xaa0f03fdu, 0xd10181efu, 0xaa0103e3u, 0xaa0203e0u, 0xf81f83a1u,
    0xf81f03a2u, 0x94000000u, 0xf85f03a2u, 0xf81e83a0u, 0xb8407041u, 0x8b1c8021u,
    0x94000000u, 0xf85f83a1u, 0xf85f03a2u, 0xf81e03a0u, 0x94000000u, 0xf85f83a1u,
    0xf85f03a2u, 0xf81d83a0u, 0x94000000u, 0xf85f83a1u, 0xf85f03a2u, 0xf81d03a0u,
    0x94000000u, 0xf85f83a1u, 0xf85f03a2u, 0xf81c83a0u, 0x94000000u, 0xf85f83a1u,
    0xf85f03a2u, 0xf81c03a0u, 0x94000000u, 0xf85f83a1u, 0xf85f03a2u, 0xf81b83a0u,
    0x94000000u, 0xf85f83a1u, 0xf85f03a2u, 0xf81b03a0u, 0x94000000u, 0xf85f83a1u,
    0xf85f03a2u, 0xf81a83a0u, 0x94000000u, 0xf85f83a1u, 0xf85f03a2u, 0xf81a03a0u,
    0x94000000u, 0xf85f83a1u, 0xf85f03a2u, 0xf81f83a0u, 0x94000000u, 0xf81f03a0u,
    0x94000000u, 0xf85e83a1u, 0xb8000001u, 0xf85e03a1u, 0xf8000001u, 0xf85d83a1u,
    0xb8013001u, 0xf85d03a1u, 0xb8017001u, 0xf85c83a1u, 0xb801b001u, 0xf85c03a1u,
    0xb801f001u, 0xf85b83a1u, 0xb8023001u, 0xf85b03a1u, 0xb8027001u, 0xf85a83a1u,
    0xb802b001u, 0xf85a03a1u, 0xb802f001u, 0xf85f83a1u, 0xb8033001u, 0xf85f03a1u,
    0xb8037001u, 0xaa1d03efu, 0xa8c179fdu, 0xd65f03c0u};
constexpr uint32_t kXiaoaiDecoderMasks[] = {
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xfc000000u, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xfc000000u, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xfc000000u, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xfc000000u, 0xffffffffu,
    0xfc000000u, 0xffffffffu, 0xffe00fffu, 0xffffffffu, 0xffe00fffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu};
const BytePattern kXiaoaiDecoderPattern{reinterpret_cast<const uint8_t*>(kXiaoaiDecoderWords), reinterpret_cast<const uint8_t*>(kXiaoaiDecoderMasks), sizeof(kXiaoaiDecoderWords)};
constexpr uint32_t kXiaoaiTriggersWords[] = {
    0xa9bf79fdu, 0xaa0f03fdu, 0xd10061efu, 0xf81f83a1u, 0x91400370u, 0xf9400210u,
    0xa90041e1u, 0x94000000u, 0x372000a0u, 0xd2800020u, 0xaa1d03efu, 0xa8c179fdu,
    0xd65f03c0u, 0x91400370u, 0xf9400210u, 0xf85f83beu, 0xa90041feu, 0x94000000u,
    0x372000a0u, 0xd2800040u, 0xaa1d03efu, 0xa8c179fdu, 0xd65f03c0u, 0x91400370u,
    0xf9400210u, 0xf85f83beu, 0xa90041feu, 0x94000000u, 0x372000a0u, 0xd2800080u,
    0xaa1d03efu, 0xa8c179fdu, 0xd65f03c0u, 0x91400370u, 0xf9400210u, 0xf85f83beu,
    0xa90041feu, 0x94000000u, 0x372000a0u, 0xd2800100u, 0xaa1d03efu, 0xa8c179fdu,
    0xd65f03c0u, 0x91400370u, 0xf9400210u, 0xf85f83beu, 0xa90041feu, 0x94000000u,
    0x372000a0u, 0xd2800200u, 0xaa1d03efu, 0xa8c179fdu, 0xd65f03c0u, 0x91400370u,
    0xf9400210u, 0xf85f83beu, 0xa90041feu, 0x94000000u, 0x372000a0u, 0xd2800400u,
    0xaa1d03efu, 0xa8c179fdu, 0xd65f03c0u, 0x91400370u, 0xf9400210u, 0xf85f83beu,
    0xa90041feu, 0x94000000u, 0x372000a0u, 0xd2800800u, 0xaa1d03efu, 0xa8c179fdu,
    0xd65f03c0u, 0x92800000u, 0xaa1d03efu, 0xa8c179fdu, 0xd65f03c0u};
constexpr uint32_t kXiaoaiTriggersMasks[] = {
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffc003ffu, 0xffc003ffu,
    0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffc003ffu, 0xffc003ffu, 0xffffffffu, 0xffffffffu, 0xfc000000u,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffc003ffu,
    0xffc003ffu, 0xffffffffu, 0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffc003ffu, 0xffc003ffu, 0xffffffffu,
    0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffc003ffu, 0xffc003ffu, 0xffffffffu, 0xffffffffu, 0xfc000000u,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffc003ffu,
    0xffc003ffu, 0xffffffffu, 0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffc003ffu, 0xffc003ffu, 0xffffffffu,
    0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu};
const BytePattern kXiaoaiTriggersPattern{reinterpret_cast<const uint8_t*>(kXiaoaiTriggersWords), reinterpret_cast<const uint8_t*>(kXiaoaiTriggersMasks), sizeof(kXiaoaiTriggersWords)};
uint32_t XiaoaiWord(const Image& image, uintptr_t at) { uint32_t w=0; ReadInsn(image,at,&w);return w; }
bool XiaoaiCall(const Image& image, uintptr_t at, uintptr_t* target) {
    const auto w=XiaoaiWord(image,at);return IsBl(w)&&DecodeBlTarget(at,w,target)&&Contains(image,*target,12,kFlagRead|kFlagExec);
}
bool XiaoaiMatch(const Image& image, uintptr_t at, const BytePattern& p) {return MatchBytes(image,at,p,kFlagRead|kFlagExec);}
bool FindXiaoaiPredicate(const Image& image, uintptr_t* at, uint32_t* count) {
    uintptr_t decoder=0,predicate=0,trigger=0;
    if(!FindUniqueBytes(image,kXiaoaiPredicatePattern,&predicate,count)||
       !FindUniqueBytes(image,kXiaoaiDecoderPattern,&decoder,count)||
       !XiaoaiCall(image,predicate+48,&trigger)||!XiaoaiMatch(image,trigger,kXiaoaiTriggersPattern)) return false;
    XiaoaiGestureLayout layout{};
    if(!DecodeXiaoaiGestureLayout(image,predicate,decoder,&layout))return false;
    *count=1;*at=predicate;return true;
}
bool FindXiaoaiDecoder(const Image& image, uintptr_t* at, uint32_t* count) {
    uintptr_t predicate=0;
    return FindXiaoaiPredicate(image,&predicate,count)&&FindUniqueBytes(image,kXiaoaiDecoderPattern,at,count);
}
const SiteSpec kXiaoaiSites[] = {
    {"predicate",{nullptr,nullptr,0},FindXiaoaiPredicate,true,
        {reinterpret_cast<const uint8_t*>(kXiaoaiPredicateWords),reinterpret_cast<const uint8_t*>(kXiaoaiPredicateMasks),16},0},
    {"decoder",{nullptr,nullptr,0},FindXiaoaiDecoder,true,kXiaoaiDecoderPattern,0}
};
}
bool DecodeXiaoaiGestureLayout(const Image& image, uintptr_t predicate, uintptr_t decoder, XiaoaiGestureLayout* out) {
    if(!out||!XiaoaiMatch(image,decoder,kXiaoaiDecoderPattern))return false;
    // The predicate prologue may already contain our owned inline patch on cache replay.
    if(!XiaoaiMatch(image,predicate+16,{reinterpret_cast<const uint8_t*>(kXiaoaiPredicateWords+4),
        reinterpret_cast<const uint8_t*>(kXiaoaiPredicateMasks+4),sizeof(kXiaoaiPredicateWords)-16}))return false;
    uintptr_t trigger=0,allocation=0,equality=0;
    if(!XiaoaiCall(image,predicate+48,&trigger)||!XiaoaiMatch(image,trigger,kXiaoaiTriggersPattern)||
       !XiaoaiCall(image,decoder+216,&allocation)||!XiaoaiCall(image,trigger+28,&equality))return false;
    for(unsigned i=1;i<7;++i) {uintptr_t call=0;if(!XiaoaiCall(image,trigger+68+(i-1)*40,&call)||call!=equality)return false;}
    const uint32_t lo=XiaoaiWord(image,allocation),hi=XiaoaiWord(image,allocation+4);
    if((lo&0xffe0001fu)!=0xd2800002u||(hi&0xffe0001fu)!=0xf2a00002u||
       (XiaoaiWord(image,allocation+8)&0xfc000000u)!=0x14000000u)return false;
    out->cid=(((lo>>5)&0xffffu)|(((hi>>5)&0xffffu)<<16))>>12;
    out->context_field=(XiaoaiWord(image,predicate+16)>>12)&0x1ffu;
    out->trigger_field=(XiaoaiWord(image,predicate+36)>>12)&0x1ffu;
    out->mask_field=(XiaoaiWord(image,predicate+28)>>12)&0x1ffu;
    out->entity_field=(XiaoaiWord(image,decoder+224)>>12)&0x1ffu;
    // LDUR/STUR offsets are signed. This object contract only permits positive,
    // tagged-object field displacements; never reinterpret a negative operand as
    // a large positive offset in the native callback.
    const uint32_t fields[] = {out->context_field, out->trigger_field, out->mask_field, out->entity_field};
    for (uint32_t field : fields) {
        if (field < 7u || field > 255u || (field & 3u) != 3u) return false;
    }
    const bool fields_overlap = out->entity_field < out->mask_field + 8u &&
                                out->mask_field < out->entity_field + 4u;
    if(out->mask_field!=((XiaoaiWord(image,decoder+232)>>12)&0x1ffu)||
       !out->cid||out->cid>65535||fields_overlap)return false;
    PoolRef long_press{},double_click{};
    if(!DecodePoolRef(XiaoaiWord(image,trigger+52),XiaoaiWord(image,trigger+56),&long_press)||
       !DecodePoolRef(XiaoaiWord(image,trigger+92),XiaoaiWord(image,trigger+96),&double_click)||
       long_press.base_reg!=27||double_click.base_reg!=27)return false;
    out->long_press_pool=long_press.add_bytes+long_press.ldr_offset;
    out->double_click_pool=double_click.add_bytes+double_click.ldr_offset;
    return true;
}
const TargetSpec kXiaoaiGestureOptionsTarget={"xiaoai_gesture_options","libapp.so",kXiaoaiSites,2};
