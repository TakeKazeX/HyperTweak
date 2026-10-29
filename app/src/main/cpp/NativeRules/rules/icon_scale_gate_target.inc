// SPDX-License-Identifier: Apache-2.0
// Native provider validation: cache result -> max/min/current -> paired bounds checks.
namespace {
constexpr uint32_t kGateWords[]={
0x910a43e0,0x97ffc470,0xb94293e8,0x1e2e1000,0xbd4297e1,0xbd429be2,0xbd42a3e4,0x7100011f,0x1e201c23,0x1e201c41,0x1e201c80,0x1e232100,0xbd027be3,0xbd0133e1,0xbd015be0,0x540095cc,0x1e212100,0x54009584,0x7ea0d500,0xb0ffd568,0xbd4f3d01,0x529ae148,0x72a78468,0xb9017be8,0x1e212000,0x5400be89};
constexpr uint32_t kGateMasks[]={
 0xffc003ff,0xfc000000,0xffc003ff,0xffffffff,0xffc003ff,0xffc003ff,0xffc003ff,0xffffffff,
 0xffffffff,0xffffffff,0xffffffff,0xffffffff,0xffc003ff,0xffc003ff,0xffc003ff,0xff00001f,
 0xffffffff,0xff00001f,0xffffffff,0x9f00001f,0xffc003ff,0xffffffff,0xffffffff,0xffc003ff,
 0xffffffff,0xff00001f};
const dart::BytePattern kGatePattern{reinterpret_cast<const uint8_t*>(kGateWords),reinterpret_cast<const uint8_t*>(kGateMasks),sizeof(kGateWords)};
struct GateLayout { uintptr_t patch=0,continuation=0; uint32_t maximum=0,minimum=0,current=0; };
bool DecodeIconGate(const dart::Image& image,GateLayout* out) {
 uintptr_t at=0;uint32_t matches=0;
 if (!dart::FindUniqueBytes(image,kGatePattern,&at,&matches) || matches!=1) return false;
 auto word=[&](size_t n){uint32_t w=0;dart::ReadInsn(image,at+n*4,&w);return w;};
 const auto result=(word(0)>>10)&0xfffu;
 if (((word(2)>>10)&0xfffu)*4!=result || ((word(4)>>10)&0xfffu)*4!=result+4 ||
     ((word(5)>>10)&0xfffu)*4!=result+8 || ((word(6)>>10)&0xfffu)*4!=result+16) return false;
 auto branch=[&](size_t n){int32_t delta=static_cast<int32_t>(word(n)<<8)>>13;return at+n*4+static_cast<int64_t>(delta)*4;};
 const auto reject=branch(15);
 if (reject!=branch(17) || !dart::Contains(image,reject,16,dart::kFlagRead|dart::kFlagExec)) return false;
 *out={at+44,at+60,((word(12)>>10)&0xfffu)*4,((word(13)>>10)&0xfffu)*4,((word(14)>>10)&0xfffu)*4};
 return out->maximum!=out->minimum && out->maximum!=out->current && out->minimum!=out->current;
}
}
