// SPDX-License-Identifier: Apache-2.0
// Paired MIUI eligibility gates, native span/capacity policy and independent
// AppWidget serializer. All class/field/branch operands come from this image.
namespace {
constexpr uint32_t kStackDragWords[] = {
    0xf100001fu, 0x54000001u, 0xb8400020u, 0x8b1c8000u,
    0x36200000u, 0xf9403f40u,
};
constexpr uint32_t kStackDragMasks[] = {
    0xffc003ffu, 0xff00001fu, 0xffe00fffu, 0xffffffffu,
    0xfff8001fu, 0xffffffffu,
};
const BytePattern kStackDragPattern{reinterpret_cast<const uint8_t*>(kStackDragWords),
    reinterpret_cast<const uint8_t*>(kStackDragMasks), sizeof(kStackDragWords)};
constexpr uint32_t kStackTargetWords[] = {0xb8400040u,0x8b1c8000u,0xf85ff003u,
    0xd34c7c63u,0xf100007fu,0x54000001u,0xb8400004u,0x8b1c8084u,0x36200004u,0xf9403f40u};
constexpr uint32_t kStackTargetMasks[] = {0xffe00fffu,~0u,~0u,~0u,0xffc003ffu,
    0xff00001fu,0xffe00fffu,~0u,0xfff8001fu,~0u};
const BytePattern kStackTargetPattern{reinterpret_cast<const uint8_t*>(kStackTargetWords),
    reinterpret_cast<const uint8_t*>(kStackTargetMasks), sizeof(kStackTargetWords)};
const BytePattern kStackDragVerify{reinterpret_cast<const uint8_t*>(kStackDragWords + 2),
    reinterpret_cast<const uint8_t*>(kStackDragMasks + 2), 16};
const BytePattern kStackTargetVerify{reinterpret_cast<const uint8_t*>(kStackTargetWords + 6),
    reinterpret_cast<const uint8_t*>(kStackTargetMasks + 6), 16};
bool StackMatch(const Image& image, uintptr_t at, const BytePattern& p) {
    return Contains(image, at, p.size, kFlagRead | kFlagExec) && MatchPatternAtAddress(
        reinterpret_cast<uintptr_t>(image.base) + at, p);
}
uint32_t StackInsn(const Image& image, uintptr_t at) {
    uint32_t w = 0; ReadInsn(image, at, &w); return w;
}
bool StackBranch(uintptr_t pc, uint32_t w, uintptr_t* out) {
    return DecodeTestBranchTarget(pc, w, out);
}
bool StackBoolBranch(uintptr_t pc, uint32_t w, uintptr_t* out) {
    if ((w & 0xfff80000u) != 0x36200000u) return false;
    int32_t delta = (w >> 5u) & 0x3fffu;
    if (delta & 0x2000) delta -= 0x4000;
    const intptr_t target = static_cast<intptr_t>(pc) + delta * 4;
    if (target < 0) return false;
    *out = static_cast<uintptr_t>(target); return true;
}
bool StackFalseTail(const Image& image, uintptr_t end) {
    const uint32_t tail[] = {0x9100c2c0u,kDartRestoreFrame,kDartPopFrame,kDartReturn};
    return end >= sizeof(tail) && StackMatch(image,end-sizeof(tail),
        {reinterpret_cast<const uint8_t*>(tail),nullptr,sizeof(tail)});
}
bool DecodeStackPair(const Image& image, uintptr_t drag, uintptr_t target,
                     uintptr_t serializer, WidgetStackLayout* out) {
    if (!out || drag < 8 || target < 24 ||
        !StackMatch(image,drag-8,kStackDragPattern) ||
        !StackMatch(image,target-24,kStackTargetPattern)) return false;
    const uint32_t field = (StackInsn(image,drag) >> 12u) & 0x1ffu;
    const uint32_t cid = (StackInsn(image,drag-8) >> 10u) & 0xfffu;
    if (!cid || field < 7 || field > 255 ||
        ((StackInsn(image,target) >> 12u) & 0x1ffu) != field ||
        ((StackInsn(image,target-8) >> 10u) & 0xfffu) != cid ||
        !Contains(image,serializer,88,kFlagRead|kFlagExec) ||
        ((StackInsn(image,serializer) >> 10u) & 0xfffu) != cid ||
        ((StackInsn(image,serializer+64) >> 12u) & 0x1ffu) != field) return false;
    uintptr_t first=0, first_bool=0, second=0, second_bool=0;
    if (!StackBranch(drag-4,StackInsn(image,drag-4),&first) ||
        !StackBoolBranch(drag+8,StackInsn(image,drag+8),&first_bool) ||
        first != first_bool || first != target-24 || !StackFalseTail(image,first) ||
        !StackBranch(target-4,StackInsn(image,target-4),&second) ||
        !StackBoolBranch(target+8,StackInsn(image,target+8),&second_bool) ||
        second != second_bool || second <= target+16 || second > target+256 ||
        !StackFalseTail(image,second)) return false;
    // The target accepts either the widget family or a stack container, then
    // compares both unboxed dimensions and rejects only a mismatch.
    uint32_t w[21]{};
    if (!ReadInsns(image,second,w,21) ||
        (w[0]&0xffc003ffu)!=0xd1000070u || w[1]!=0xf1001a1fu ||
        (w[2]&0xff00001fu)!=0x54000009u ||
        (w[3]&0xffc003ffu)!=0xf100007fu || (w[4]&0xff00001fu)!=0x54000000u ||
        w[5]!=0x9100c2c0u || w[6]!=kDartRestoreFrame || w[7]!=kDartPopFrame || w[8]!=kDartReturn ||
        (w[9]&0xffe00fffu)!=0xf8400023u || (w[10]&0xffe00fffu)!=0xf8400004u ||
        ((w[9]>>12u)&0x1ffu)!=((w[10]>>12u)&0x1ffu) || w[11]!=0xeb04007fu ||
        (w[12]&0xff00001fu)!=0x54000001u ||
        (w[13]&0xffe00fffu)!=0xf8400023u || (w[14]&0xffe00fffu)!=0xf8400004u ||
        ((w[13]>>12u)&0x1ffu)!=((w[14]>>12u)&0x1ffu) ||
        ((w[9]>>12u)&0x1ffu)==((w[13]>>12u)&0x1ffu) || w[15]!=0xeb04007fu ||
        (w[16]&0xff00001fu)!=0x54000000u || !StackFalseTail(image,second+84)) return false;
    uintptr_t span=0, span2=0, bad=0, good=0;
    if (!StackBranch(second+8,w[2],&span) || span!=second+36 ||
        !StackBranch(second+16,w[4],&span2) || span2!=span ||
        !StackBranch(second+48,w[12],&bad) || bad!=second+68 ||
        !StackBranch(second+64,w[16],&good) || good!=second+84) return false;
    const uint32_t stack_cid=(w[3]>>10u)&0xfffu;
    const uint32_t family=(w[0]>>10u)&0xfffu;
    if (cid < family || cid > family+6 || stack_cid==cid) return false;
    // Confirm the same stack CID feeds the native list-length <10 gate.
    bool capacity=false;
    for (uintptr_t pc=second+84; pc<second+512; pc+=4) {
        uint32_t p[8]{};
        if (!ReadInsns(image,pc,p,8)) return false;
        if ((p[0]&0xffc003ffu)!=0xf100003fu || ((p[0]>>10u)&0xfffu)!=stack_cid ||
            (p[1]&0xff00001fu)!=0x54000001u ||
            (p[2]&0xffe00fffu)!=0xb8400001u || p[3]!=0x8b1c8021u ||
            p[4]!=0xb840b020u || p[5]!=0x93417c01u || p[6]!=0xf100283fu ||
            (p[7]&0xff00001fu)!=0x5400000bu) continue;
        uintptr_t a=0,b=0;
        capacity=StackBranch(pc+4,p[1],&a) && StackBranch(pc+28,p[7],&b) && a==b &&
            a>pc+32 && a<pc+256 && StackFalseTail(image,a);
        if (capacity) break;
    }
    if (!capacity) return false;
    *out={field,cid,first,second}; return true;
}
struct StackFamily { uintptr_t drag=0,target=0,serializer=0; uint32_t count=0; };
StackFamily FindStackFamily(const Image& image) {
    StackFamily family{};
    uintptr_t candidates[64]{}; size_t count=0; uint32_t total=0;
    if (!CollectBytes(image,kStackDragPattern,candidates,64,&count,&total) || total>count) return family;
    for (size_t i=0;i<count;++i) {
        const uintptr_t drag=candidates[i]+8;
        uintptr_t target_begin=0,serializer=0; uint32_t serializers=0;
        if (!StackBranch(drag-4,StackInsn(image,drag-4),&target_begin) ||
            target_begin<=drag+16 || target_begin>drag+256 ||
            !FindWidgetSerializer(image,(StackInsn(image,drag)>>12u)&0x1ffu,&serializer,&serializers)) continue;
        WidgetStackLayout layout{};
        if (!DecodeStackPair(image,drag,target_begin+24,serializer,&layout)) continue;
        ++family.count; family.drag=drag; family.target=target_begin+24; family.serializer=serializer;
    }
    return family;
}
bool FindStackDrag(const Image& image, uintptr_t* out, uint32_t* count) {
    const auto f=FindStackFamily(image); *count=f.count; *out=f.drag; return f.count==1;
}
bool FindStackTarget(const Image& image, uintptr_t* out, uint32_t* count) {
    const auto f=FindStackFamily(image); *count=f.count; *out=f.target; return f.count==1;
}
bool FindStackSerializer(const Image& image, uintptr_t* out, uint32_t* count) {
    const auto f=FindStackFamily(image); *count=f.count; *out=f.serializer; return f.count==1;
}
const SiteSpec kWidgetStackSites[] = {
    {"drag_gate",{},FindStackDrag,true,kStackDragVerify,0},
    {"target_gate",{},FindStackTarget,true,kStackTargetVerify,0},
    {"serializer",{},FindStackSerializer,true,{reinterpret_cast<const uint8_t*>(&kWidgetTypeWord),
        reinterpret_cast<const uint8_t*>(&kWidgetTypeMask),4},0},
};
}
bool DecodeWidgetStackLayout(const Image& image, uintptr_t drag, uintptr_t target,
                             uintptr_t serializer, WidgetStackLayout* out) {
    return DecodeStackPair(image,drag,target,serializer,out);
}
const TargetSpec kWidgetStackTarget{"android_widget_stack","libapp.so",kWidgetStackSites,3};
