// SPDX-License-Identifier: Apache-2.0
#include "dart_image.h"
#include <cassert>
#include <fstream>
#include <iterator>
#include <vector>
#include <cstring>
#include <cstdio>
using namespace hypertweak::native;
#include "../../main/cpp/NativeRules/rules/icon_scale_gate_target.inc"
int main(int argc,char** argv) {
 assert(argc==2);std::ifstream in(argv[1],std::ios::binary);
 std::vector<uint8_t> bytes(std::istreambuf_iterator<char>(in),{});
 dart::Image image{};assert(dart::ParseImage(bytes.data(),&image));GateLayout layout{};
 assert(DecodeIconGate(image,&layout));
 printf("native icon gate uniquely resolved; slots max=%x min=%x current=%x\n",layout.maximum,layout.minimum,layout.current);
 const auto site=layout.patch-44;uint32_t original=0;memcpy(&original,bytes.data()+site+17*4,4);
 uint32_t changed=original^(1u<<5);memcpy(bytes.data()+site+17*4,&changed,4);
 assert(!DecodeIconGate(image,&layout));memcpy(bytes.data()+site+17*4,&original,4);
 assert(DecodeIconGate(image,&layout));
 auto at=layout.patch;bytes[at]^=0x10;assert(!DecodeIconGate(image,&layout));
 puts("native icon gate rejects changed FP operations and unrelated rejection branches");
}
