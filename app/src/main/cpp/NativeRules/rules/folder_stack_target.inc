// SPDX-License-Identifier: Apache-2.0
// Instruction/data-flow contracts. Runtime locations, allocation CIDs and strategy fields
// are decoded from the current image; there is no version/build-ID/address table.
// Discovery uses bounded semantic bodies and cross-call/field relationships.
// Short entry seeds enumerate candidates; they do not establish target identity.
// The constructor's two local argument spills may exchange slots; their pairing
// is validated below instead of treating the compiler's spill order as an ABI.
namespace {
constexpr uint32_t kStackGetterWords[] = {
    0xa9bf79fdu, 0xaa0f03fdu, 0xd10081efu, 0xaa0103e3u,
    0xf81f03a1u, 0xf81e83a2u, 0x937f7840u, 0xeb80045fu,
};
constexpr uint32_t kStackGetterMasks[] = {
    0xffffffffu, 0xffffffffu, 0xffc003ffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
};
constexpr uint32_t kStackProducerWords[] = {
    0xa9bf79fdu, 0xaa0f03fdu, 0xd10161efu, 0xaa0103e0u,
    0xf81f83a1u, 0x94000000u, 0xd2800003u, 0xf85f83a2u,
};
constexpr uint32_t kStackProducerMasks[] = {
    0xffffffffu, 0xffffffffu, 0xffc003ffu, 0xffffffffu,
    0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu,
};
constexpr uint32_t kStackFactoryWords[] = {
    0xa9bf79fdu, 0xaa0f03fdu, 0xd10081efu, 0xf81e83a2u, 0xb8413080u, 0xb841f083u,
    0x8b1c8063u, 0x91400370u, 0xf9400210u, 0x6b10007fu, 0x54000121u, 0xb8423083u,
    0x8b1c8063u, 0x4b030005u, 0x8b25cba3u, 0xf9400463u, 0xaa0303e5u, 0xd2800023u,
    0x14000003u, 0x9100c2c5u, 0xd2800003u, 0xf81f03a5u, 0xd37ff866u, 0x531f78c3u,
    0x11002066u, 0x8b26c490u, 0xb840f207u, 0x8b1c80e7u, 0x91400370u, 0xf9400210u,
    0x6b1000ffu, 0x54000141u, 0x11002866u, 0x8b26c490u, 0xb840f203u, 0x8b1c8063u,
    0x4b030004u, 0x8b24cba0u, 0xf9400400u, 0xaa0003e3u, 0x14000002u, 0x9100c2c3u,
    0xf81f83a3u, 0xf8407024u, 0xf1005c9fu, 0x540007ecu, 0xf1004c9fu, 0x5400046cu,
    0xf100089fu, 0x5400028cu, 0x937f7880u, 0xeb80049fu, 0x54000060u, 0x94000000u,
    0xf8007004u, 0x7100101fu, 0x54000c01u, 0x94000000u, 0xf81e03a0u, 0xf806f01fu,
    0xaa0003e1u, 0xf85e83a2u, 0xf85f03a3u, 0xf85f83a5u, 0x94000000u, 0xf85e03a0u,
    0xaa1d03efu, 0xa8c179fdu, 0xd65f03c0u, 0xf1004c9fu, 0x54000a4bu, 0x94000000u,
    0xaa0003e1u, 0xf85e83a2u, 0xf85f03a3u, 0xf85f83a5u, 0xf81e03a0u, 0x94000000u,
    0xf85e03a0u, 0xaa1d03efu, 0xa8c179fdu, 0xd65f03c0u, 0xf100509fu, 0x5400018cu,
    0x94000000u, 0xaa0003e1u, 0xf85e83a2u, 0xf85f03a3u, 0xf85f83a5u, 0xf81e03a0u,
    0x94000000u, 0xf85e03a0u, 0xaa1d03efu, 0xa8c179fdu, 0xd65f03c0u, 0xf1005c9fu,
    0x5400070bu, 0x94000000u, 0xaa0003e1u, 0xf85e83a2u, 0xf85f03a3u, 0xf85f83a5u,
    0xf81e03a0u, 0x94000000u, 0xf85e03a0u, 0xaa1d03efu, 0xa8c179fdu, 0xd65f03c0u,
    0xf100649fu, 0x5400032cu, 0xf100609fu, 0x5400018cu, 0x94000000u, 0xaa0003e1u,
    0xf85e83a2u, 0xf85f03a3u, 0xf85f83a5u, 0xf81e03a0u, 0x94000000u, 0xf85e03a0u,
    0xaa1d03efu, 0xa8c179fdu, 0xd65f03c0u, 0x94000000u, 0xaa0003e1u, 0xf85e83a2u,
    0xf85f03a3u, 0xf85f83a5u, 0xf81e03a0u, 0x94000000u, 0xf85e03a0u, 0xaa1d03efu,
    0xa8c179fdu, 0xd65f03c0u, 0x937f7880u, 0xeb80049fu, 0x54000060u, 0x94000000u,
    0xf8007004u, 0x7100d01fu, 0x54000181u, 0x94000000u, 0xaa0003e1u, 0xf85e83a2u,
    0xf85f03a3u, 0xf85f83a5u, 0xf81e03a0u, 0x94000000u, 0xf85e03a0u, 0xaa1d03efu,
    0xa8c179fdu, 0xd65f03c0u, 0x94000000u, 0xf81e03a0u, 0xf806f01fu, 0xaa0003e1u,
    0xf85e83a2u, 0xf85f03a3u, 0xf85f83a5u, 0x94000000u, 0xf85e03a0u, 0xaa1d03efu,
    0xa8c179fdu, 0xd65f03c0u,
};
constexpr uint32_t kStackFactoryMasks[] = {
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffc003ffu, 0xffc003ffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffc003ffu, 0xffc003ffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xfc000000u,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffe00fffu, 0xffe00fffu, 0xfc000000u, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xfc000000u,
    0xffffffffu, 0xffffffffu, 0xffe00fffu, 0xffe00fffu, 0xffffffffu, 0xfc000000u,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffe00fffu, 0xffe00fffu, 0xffffffffu,
    0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffe00fffu, 0xffe00fffu,
    0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xfc000000u, 0xffffffffu,
    0xffffffffu, 0xffe00fffu, 0xffe00fffu, 0xffffffffu, 0xfc000000u, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu,
    0xffe00fffu, 0xffe00fffu, 0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xfc000000u,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu,
    0xffe00fffu, 0xffe00fffu, 0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffe00fffu, 0xffe00fffu, 0xfc000000u, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu,
};
constexpr uint32_t kStackBuilderWords[] = {
    0xa9bf79fdu, 0xaa0f03fdu, 0xd10241efu, 0xf9400fa0u,
    0xb8417001u, 0x8b1c8021u, 0xf81f83a1u, 0xd2800021u,
};
constexpr uint32_t kStackBuilderMasks[] = {
    0xffffffffu, 0xffffffffu, 0xffc003ffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
};
constexpr uint32_t kStackChildrenWords[] = {
    0xa9bf79fdu, 0xaa0f03fdu, 0xd10101efu, 0xf9400ba0u,
    0xb8417001u, 0x8b1c8021u, 0xf81f83a1u, 0xd2800021u,
};
constexpr uint32_t kStackChildrenMasks[] = {
    0xffffffffu, 0xffffffffu, 0xffc003ffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
};
constexpr uint8_t kStackCountWindow[] = {0xe3,0x03,0x00,0xaa,0xa0,0x83,0x5f,0xf8,0xa3,0x03,0x1f,0xf8,0x02,0x7c,0x41,0x93};
constexpr uint8_t kStackLoopWindow[] = {0x80,0xf8,0x7f,0xd3,0xb0,0x03,0x5f,0xf8,0xe0,0x41,0x00,0xa9,0xa0,0x03,0x5f,0xf8};
constexpr uint8_t kStackReturn[] = {0x00,0x80,0x1c,0x8b,0xef,0x03,0x1d,0xaa,0xfd,0x79,0xc1,0xa8,0xc0,0x03,0x5f,0xd6};
constexpr uint32_t kStackFrameWords[] = {
    0xa9bf79fdu, 0xaa0f03fdu, 0xb846b020u, 0x8b1c8000u, 0x372000e0u, 0x910082c0u,
    0xb807f020u, 0xaa1603e0u, 0xaa1d03efu, 0xa8c179fdu, 0xd65f03c0u, 0x94000000u,
    0x00000000u, 0x00000000u, 0x00000000u, 0x00000000u,
};
constexpr uint32_t kStackFrameMasks[] = {
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xfc000000u,
    0x00000000u, 0x00000000u, 0x00000000u, 0x00000000u,
};
constexpr uint32_t kStackRefreshWords[] = {
    0xa9bf79fdu, 0xaa0f03fdu, 0xd102a1efu, 0xf9400340u, 0xf9400000u, 0xf9400370u,
    0x6b10001fu, 0x54000061u, 0xf9400362u, 0x94000000u, 0xaa0003e1u, 0x94000000u,
    0xaa0003e2u, 0xf81983a2u, 0xb840b041u, 0x8b1c8021u, 0xf85ff020u, 0xd34c7c00u,
    0xd28e7e11u, 0xf2a00051u, 0x8b11001eu, 0xf87e7abeu, 0xd63f03c0u, 0xf85ff001u,
    0xd34c7c21u, 0xaa0003f0u, 0xaa0103e0u, 0xaa1003e1u, 0xd2902e11u, 0xf2a00031u,
    0x8b11001eu, 0xf87e7abeu, 0xd63f03c0u, 0xaa0003e2u, 0xf81983a2u, 0xf85ff040u,
    0xd34c7c00u, 0xaa0203e1u, 0x9118f81eu, 0xf87e7abeu, 0xd63f03c0u, 0x37200e60u,
    0xf85983a2u, 0xf85ff040u, 0xd34c7c00u, 0xaa0203e1u, 0x9128501eu, 0xf87e7abeu,
    0xd63f03c0u, 0xaa0003e1u, 0xf81903a1u, 0xb840b020u, 0x8b1c8000u, 0xd2800782u,
    0x36000060u, 0xf85ff002u, 0xd34c7c42u, 0xf90001e0u, 0xaa0203e0u, 0xf9400364u,
    0xd28c2ab1u, 0x8b11001eu, 0xf87e7abeu, 0xd63f03c0u, 0xf81883a0u, 0xf9400340u,
    0xf9400000u, 0xf9400370u, 0x6b10001fu, 0x54000061u, 0xf9400362u, 0x94000000u,
    0xf81803a0u, 0xf9400370u, 0xf85883beu, 0xa90041feu, 0xf9400364u, 0x94000000u,
    0x37200980u, 0xf85883a1u, 0x94000000u, 0xaa1603e1u, 0xd2800042u, 0xf81903a0u,
    0x94000000u, 0xf81803a0u, 0x00000000u, 0x00000000u, 0x00000000u, 0x00000000u,
    0x94000000u, 0xaa0003e2u, 0xf85803a0u, 0xf81783a2u, 0xb800f040u, 0xd2800040u,
    0xb800b040u, 0xf9400361u, 0x94000000u, 0xaa0003e4u, 0xf85783a3u, 0xf81703a4u,
    0xb800b083u, 0xd2800025u, 0xf800f085u, 0xf801709fu, 0xb840b060u, 0x93417c01u,
    0xf100043fu, 0x54000be1u, 0xf8417082u, 0xeb01005fu, 0x5400050au, 0xaa0103e0u,
    0xaa0203e1u, 0xeb00003fu, 0x54000c42u, 0xb840f060u, 0x8b1c8000u, 0x8b020810u,
    0xb840f206u, 0x8b1c80c6u, 0xaa0603e0u, 0xf81803a6u, 0xb801f080u, 0x360000e0u,
    0x385ff090u, 0x385ff011u, 0x8a500a30u, 0xea5c821fu, 0x54000040u, 0x94000000u,
    0x91000440u, 0xf8017080u, 0x6b1600dfu, 0x54000141u, 0xaa0603e0u, 0xaa1603e2u,
    0xaa1603e1u, 0x6b16001fu, 0x540000a1u, 0xf9400368u, 0x91400363u, 0xf9400063u,
    0x94000000u, 0xf85903a1u, 0xf85803a2u, 0x94000000u, 0xf85783a3u, 0xf85703a4u,
    0xd2800025u, 0x17ffffd3u, 0xaa0403e0u, 0xb801f016u, 0xf85983a2u, 0x17ffff88u,
    0xf9400340u, 0xf9400000u, 0xf9400370u, 0x6b10001fu, 0x54000061u, 0xf9400362u,
    0x94000000u, 0x91400361u, 0xf9400021u, 0xaa1603e2u, 0xf81803a0u, 0x94000000u,
    0xf85803a1u, 0xaa0003e2u, 0xf9400364u, 0x94000000u, 0x1400001cu, 0xd102a3afu,
    0xf81803a0u, 0xf9400340u, 0xf9400000u, 0xf9400370u, 0x6b10001fu, 0x54000061u,
    0xf9400362u, 0x94000000u, 0xaa1603e1u, 0xd28000c2u, 0xf81683a0u, 0x94000000u,
    0x91400370u, 0xf9400210u, 0xb800f010u, 0x91400370u, 0xf9400210u, 0xb8013010u,
    0xf85803a1u, 0xb8017001u, 0xf90001e0u, 0x94000000u, 0xf85683a1u, 0xaa0003e2u,
    0xf9400364u, 0x94000000u, 0x00000000u, 0x00000000u, 0x00000000u, 0x00000000u,
    0xaa0303e1u, 0xaa0403e0u, 0x94000000u, 0xaa0003e2u, 0xf85783a1u, 0xf81803a2u,
    0xb800b041u, 0xaa0203e0u, 0x94000000u, 0xd4200000u, 0x94000000u,
};
constexpr uint32_t kStackRefreshMasks[] = {
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffc003ffu, 0xffc003ffu, 0xffc003ffu,
    0xffffffffu, 0xffffffffu, 0xffc003ffu, 0xfc000000u, 0xffffffffu, 0xfc000000u,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffe0001fu, 0xffe0001fu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffe0001fu, 0xffe0001fu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffc003ffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffc003ffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffc003ffu,
    0xffe0001fu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffc003ffu,
    0xffc003ffu, 0xffc003ffu, 0xffffffffu, 0xffffffffu, 0xffc003ffu, 0xfc000000u,
    0xffffffffu, 0xffc003ffu, 0xffffffffu, 0xffffffffu, 0xffc003ffu, 0xfc000000u,
    0xffffffffu, 0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xfc000000u, 0xffffffffu, 0x00000000u, 0x00000000u, 0x00000000u, 0x00000000u,
    0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffc003ffu, 0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xfc000000u,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffc003ffu, 0xffc003ffu, 0xffc003ffu,
    0xfc000000u, 0xffffffffu, 0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffc003ffu, 0xffc003ffu, 0xffc003ffu, 0xffffffffu, 0xffffffffu, 0xffc003ffu,
    0xfc000000u, 0xffc003ffu, 0xffc003ffu, 0xffffffffu, 0xffffffffu, 0xfc000000u,
    0xffffffffu, 0xffffffffu, 0xffc003ffu, 0xfc000000u, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffc003ffu, 0xffc003ffu, 0xffc003ffu, 0xffffffffu, 0xffffffffu,
    0xffc003ffu, 0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xfc000000u,
    0xffc003ffu, 0xffc003ffu, 0xffffffffu, 0xffc003ffu, 0xffc003ffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu,
    0xffc003ffu, 0xfc000000u, 0x00000000u, 0x00000000u, 0x00000000u, 0x00000000u,
    0xffffffffu, 0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xfc000000u, 0xffffffffu, 0xfc000000u,
};
constexpr uint32_t kStackBeginWords[] = {
    0xa9bf79fdu, 0xaa0f03fdu, 0xd10201efu, 0xaa0203e0u, 0xf81a83a1u, 0xf81a03a2u,
    0xd2800021u, 0x94000000u, 0xaa0003e3u, 0xf85a83a0u, 0xf81983a3u, 0xb800f060u,
    0xb8433001u, 0x8b1c8021u, 0x37200301u, 0xaa1603e1u, 0xd28000c2u, 0x94000000u,
    0xf81903a0u, 0xf9400370u, 0xb800f010u, 0xf9400340u, 0xf9400000u, 0xf9400370u,
    0x6b10001fu, 0x54000061u, 0xf9400362u, 0x94000000u, 0xb8423001u, 0x8b1c8021u,
    0xf85903a0u, 0xb8013001u, 0xf9400370u, 0xb8017010u, 0xf90001e0u, 0x94000000u,
    0xaa0003e1u, 0x94000000u, 0xf85a83a3u, 0xb846f060u, 0x8b1c8000u, 0x6b16001fu,
    0x54000121u, 0xf85a03a0u, 0xb806f060u, 0x385ff070u, 0x385ff011u, 0x8a500a30u,
    0xea5c821fu, 0x54000040u, 0x94000000u, 0xf85a03a0u, 0x6b16001fu, 0x540000a1u,
    0xb8477061u, 0x8b1c8021u, 0xaa0103e2u, 0x14000002u, 0xaa0003e2u, 0xaa0303e1u,
    0x94000000u, 0xf85a83a2u, 0xb807b040u, 0x385ff050u, 0x385ff011u, 0x8a500a30u,
    0xea5c821fu, 0x54000040u, 0x94000000u, 0xf85a03a0u, 0x6b16001fu, 0x54000100u,
    0xb8077040u, 0x385ff050u, 0x385ff011u, 0x8a500a30u, 0xea5c821fu, 0x54000040u,
    0x94000000u, 0x9100c2c0u, 0xb805f040u, 0xf9400360u, 0xb8063040u, 0xd2800001u,
    0x94000000u, 0xf85a83a0u, 0xb844b001u, 0x8b1c8021u, 0xf81a03a1u, 0xf9400370u,
    0xf940035eu, 0xa90041feu, 0x94000000u, 0xf85a83a3u, 0xb804b060u, 0x385ff070u,
    0x385ff011u, 0x8a500a30u, 0xea5c821fu, 0x54000040u, 0x94000000u, 0xf85983a2u,
    0xf9400361u, 0x94000000u, 0xf85a03a1u, 0xaa0003e2u, 0x94000000u, 0xf85a83a0u,
    0xb844f001u, 0x8b1c8021u, 0x94000000u, 0xf85a83a0u, 0x94000000u, 0xf85a83a2u,
    0xf9400363u, 0xb8063043u, 0xaa1603e0u, 0xaa1d03efu, 0xa8c179fdu, 0xd65f03c0u,
    0xd10203afu, 0xf85a83a2u, 0xf9400363u, 0xf81a03a0u, 0xf81983a1u, 0x94000000u,
    0xf85a83a0u, 0xf9400361u, 0xb8063001u, 0xf85a03a0u, 0xf85983a1u, 0x94000000u,
    0xd4200000u,
};
constexpr uint32_t kStackBeginMasks[] = {
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xfc000000u,
    0xffffffffu, 0xffc003ffu, 0xffffffffu, 0xffc003ffu, 0xffc003ffu, 0xffc003ffu,
    0xffffffffu, 0xffffffffu, 0xffc003ffu, 0xfc000000u, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffc003ffu, 0xffffffffu, 0xffffffffu, 0xfc000000u,
    0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffc003ffu, 0xffffffffu, 0xffffffffu,
    0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffc003ffu,
    0xffc003ffu, 0xffffffffu, 0xfc000000u, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xfc000000u, 0xffffffffu,
    0xffc003ffu, 0xfc000000u, 0xffffffffu, 0xffffffffu, 0xfc000000u, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xfc000000u, 0xffffffffu, 0xfc000000u, 0xffffffffu,
    0xffc003ffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xffc003ffu, 0xffffffffu, 0xffffffffu, 0xfc000000u,
    0xffffffffu, 0xffc003ffu, 0xffffffffu, 0xffffffffu, 0xffffffffu, 0xfc000000u,
    0xffffffffu,
};
constexpr uint32_t kStackLayoutOwnerWords[] = {
    0xa9bf79fdu, 0xaa0f03fdu, 0xd10081efu, 0xf81f83a1u,
    0xf81f03a2u, 0xd28000a1u, 0x94000000u, 0xaa0003e2u,
};
constexpr uint32_t kStackLayoutOwnerMasks[] = {
    0xffffffffu, 0xffffffffu, 0xffc003ffu, 0xffffffffu,
    0xffffffffu, 0xffffffffu, 0xfc000000u, 0xffffffffu,
};
bool FindStackGetter(const Image&,uintptr_t*,uint32_t*);
bool FindStackProducer(const Image&,uintptr_t*,uint32_t*);
bool FindStackBuilder(const Image&,uintptr_t*,uint32_t*);
bool FindStackChildren(const Image&,uintptr_t*,uint32_t*);
bool FindStackOwner(const Image&,uintptr_t*,uint32_t*);
bool ValidateStackSite(const Image&,unsigned,uintptr_t);
const SiteSpec kFolderStackSites[] = {
    {"getter", {}, FindStackGetter, true, {reinterpret_cast<const uint8_t*>(kStackGetterWords), reinterpret_cast<const uint8_t*>(kStackGetterMasks), 32u}, 0u},
    {"producer", {}, FindStackProducer, true, {reinterpret_cast<const uint8_t*>(kStackProducerWords), reinterpret_cast<const uint8_t*>(kStackProducerMasks), 32u}, 0u},
    {"factory", {reinterpret_cast<const uint8_t*>(kStackFactoryWords), reinterpret_cast<const uint8_t*>(kStackFactoryMasks), sizeof(kStackFactoryWords)}, nullptr, true, {reinterpret_cast<const uint8_t*>(kStackFactoryWords), reinterpret_cast<const uint8_t*>(kStackFactoryMasks), sizeof(kStackFactoryWords)}, 0u},
    {"builder", {}, FindStackBuilder, true, {reinterpret_cast<const uint8_t*>(kStackBuilderWords), reinterpret_cast<const uint8_t*>(kStackBuilderMasks), 32u}, 0u},
    {"children", {}, FindStackChildren, true, {reinterpret_cast<const uint8_t*>(kStackChildrenWords), reinterpret_cast<const uint8_t*>(kStackChildrenMasks), 32u}, 0u},
    {"frame", {reinterpret_cast<const uint8_t*>(kStackFrameWords), reinterpret_cast<const uint8_t*>(kStackFrameMasks), sizeof(kStackFrameWords)}, nullptr, true, {reinterpret_cast<const uint8_t*>(kStackFrameWords), reinterpret_cast<const uint8_t*>(kStackFrameMasks), 16u}, 0u},
    {"refresh", {reinterpret_cast<const uint8_t*>(kStackRefreshWords), reinterpret_cast<const uint8_t*>(kStackRefreshMasks), sizeof(kStackRefreshWords)}, nullptr, true, {reinterpret_cast<const uint8_t*>(kStackRefreshWords), reinterpret_cast<const uint8_t*>(kStackRefreshMasks), 16u}, 0u},
    {"layout_owner", {}, FindStackOwner, true, {reinterpret_cast<const uint8_t*>(kStackLayoutOwnerWords), reinterpret_cast<const uint8_t*>(kStackLayoutOwnerMasks), 32u}, 0u},
};
} // namespace
const TargetSpec kFolderStackTarget = {"folder_stack_preview", "libapp.so", kFolderStackSites, 8};

namespace {
uint32_t StackWord(const Image& image, uintptr_t at) {
    uint32_t word = 0; (void)ReadInsn(image, at, &word); return word;
}
bool StackLoad(uint32_t word, uint32_t opcode, unsigned rt, unsigned rn, uint16_t* field) {
    if ((word & 0xffe00fffu) != (opcode | (rn << 5u) | rt)) return false;
    const unsigned immediate = (word >> 12u) & 0x1ffu;
    if (immediate >= 256u) return false;
    *field = static_cast<uint16_t>(immediate); return true;
}
bool StackAllocation(const Image& image, uintptr_t call, uint32_t* cid) {
    uint32_t word = 0; uintptr_t target = 0;
    if (!ReadInsn(image, call, &word) || !IsBl(word) ||
        !DecodeBlTarget(call, word, &target) || !Contains(image, target, 12, kFlagRead | kFlagExec)) return false;
    const auto lo = StackWord(image, target), hi = StackWord(image, target + 4);
    if ((lo & 0xffe0001fu) != 0xd2800002u || (hi & 0xffe0001fu) != 0xf2a00002u ||
        (StackWord(image, target + 8) & 0xfc000000u) != 0x14000000u) return false;
    *cid = (((lo >> 5u) & 0xffffu) | (((hi >> 5u) & 0xffffu) << 16u)) >> 12u;
    return *cid > 0u && *cid < 0x100000u;
}
int StackFrameDisplacement(uint32_t word) {
    const int immediate = static_cast<int>((word >> 12u) & 0x1ffu);
    return immediate < 256 ? immediate : immediate - 512;
}
size_t StackBodySpan(const Image& image,uintptr_t at) {
    if(!IsDartPrologue(image,at))return 0;
    for(size_t i=12;i<4096;i+=4) {
        if(!Contains(image,at+i,4,kFlagRead|kFlagExec))return 0;
        if(StackWord(image,at+i)==kDartReturn && StackWord(image,at+i-4)==kDartPopFrame &&
            StackWord(image,at+i-8)==kDartRestoreFrame)return i+4;
    }
    return 0;
}
size_t StackGetterSpan(const Image& image,uintptr_t at) {
    if(!IsDartPrologue(image,at))return 0;
    for(size_t i=12;i<512;i+=4) {
        if(!Contains(image,at+i,32,kFlagRead|kFlagExec))return 0;
        if(StackWord(image,at+i)==0xb840f041u && StackWord(image,at+i+4)==0x8b1c8021u &&
            StackWord(image,at+i+8)==0x8b050830u && StackWord(image,at+i+12)==0xb840f200u)return i+32;
    }
    return 0;
}
bool ValidateStackSite(const Image& image,unsigned site,uintptr_t at) {
    const auto span=site==0 ? StackGetterSpan(image,at) : StackBodySpan(image,at);
    if(!span)return false;
    unsigned matches=0,secondary=0;
    for(size_t i=0;i+20<=span;i+=4) {
        const auto word=StackWord(image,at+i);
        uint16_t field=0;
        if(site==0) {
            if(StackLoad(word,0xb8400000u,2,0,&field))++secondary;
            if(word==0xb840f041u && StackWord(image,at+i+4)==0x8b1c8021u &&
                StackWord(image,at+i+8)==0x8b050830u && StackWord(image,at+i+12)==0xb840f200u)++matches;
        } else if(site==1) {
            uint16_t x=0,padding=0;
            if(StackLoad(word,0xfc400000u,0,2,&x) && StackLoad(StackWord(image,at+i+4),0xfc400000u,1,2,&padding) &&
                StackWord(image,at+i+8)==0x1e612802u)++matches;
            if(StackLoad(word,0xb8400000u,1,0,&field)) {
                for(size_t d=4;d<=16 && i+d<span;d+=4) {
                    uint32_t cid=0;if(StackAllocation(image,at+i+d,&cid))++secondary;
                }
            }
        } else if(site==3) {
            if(word!=0xaa0603e2u || (StackWord(image,at+i+4)&0xffe00fffu)!=0xf80003a6u)continue;
            uintptr_t wrapper=0;
            if(!IsBl(StackWord(image,at+i+8)) || !DecodeBlTarget(at+i+8,StackWord(image,at+i+8),&wrapper) ||
                !IsDartPrologue(image,wrapper))continue;
            unsigned fields=0,links=0;
            for(size_t j=0;j+12<=128 && Contains(image,wrapper+j,12,kFlagRead|kFlagExec);j+=4) {
                const auto w=StackWord(image,wrapper+j);uintptr_t getter=0;
                if(w==kDartReturn)break;
                if((w&0xffe0001fu)==0xd2800011u && StackWord(image,wrapper+j+4)==0xb8716820u &&
                    StackWord(image,wrapper+j+8)==0x8b1c8000u)++fields;
                if(IsBl(w) && DecodeBlTarget(wrapper+j,w,&getter) && ValidateStackSite(image,0,getter))++links;
            }
            if(fields==1 && links==1)++matches;
        } else if(site==4) {
            PoolRef pool{};
            if(word==0xb840b020u && StackWord(image,at+i+4)==0xf81f83a0u &&
                DecodePoolRef(StackWord(image,at+i+8),StackWord(image,at+i+12),&pool) && pool.dest_reg==1 &&
                IsBl(StackWord(image,at+i+16)))++matches;
            if(word==0xd2800004u && StackWord(image,at+i+4)==0xf81e83a4u && StackWord(image,at+i+8)==0xeb02009fu &&
                (StackWord(image,at+i+12)&0xff00001fu)==0x5400000au)++secondary;
        } else if(site==7) {
            PoolRef id{};
            if(i>=8 && word==0xb8017002u && DecodePoolRef(StackWord(image,at+i-8),StackWord(image,at+i-4),&id) &&
                id.dest_reg==2 && StackWord(image,at+i+4)==0xf85e83a2u && StackWord(image,at+i+8)==0xb8017040u)++matches;
            // The cached builder is saved across the GetBuilder constructor.
            if(word==0xaa0003e3u && StackWord(image,at+i+4)==0x910082c0u &&
                (StackWord(image,at+i+8)&0xffe00fffu)==0xf80003a3u && StackWord(image,at+i+12)==0xb8013060u) {
                const int saved=StackFrameDisplacement(StackWord(image,at+i+8));
                const auto frame=(StackWord(image,at+8)>>10)&0xfffu;
                if(saved>=0 || static_cast<unsigned>(-saved)>frame)continue;
                for(size_t d=16;d<=48 && i+d+8<=span;d+=4) {
                    if(StackWord(image,at+i+d)==0xaa0003e1u &&
                        (StackWord(image,at+i+d+4)&0xffe00fffu)==0xf84003a0u &&
                        StackFrameDisplacement(StackWord(image,at+i+d+4))==saved &&
                        StackWord(image,at+i+d+8)==0xb800f001u)++secondary;
                }
            }
        }
    }
    return matches==1 && ((site==3) || secondary==1);
}
bool FindStackContract(const Image& image,unsigned site,const uint32_t* words,const uint32_t* masks,
                       uintptr_t* output,uint32_t* candidates) {
    uint32_t seed_masks[8]{};
    memcpy(seed_masks,masks,sizeof(seed_masks));
    seed_masks[2]=0xffc003ffu; // Compiler frame allocation is not target identity.
    const BytePattern seed{reinterpret_cast<const uint8_t*>(words),reinterpret_cast<const uint8_t*>(seed_masks),sizeof(seed_masks)};
    uintptr_t offsets[512]{};size_t count=0;uint32_t total=0;*candidates=0;
    if(!CollectBytes(image,seed,offsets,512,&count,&total) || total>512)return false;
    for(size_t i=0;i<count;++i)if(ValidateStackSite(image,site,offsets[i])){*output=offsets[i];++*candidates;}
    return *candidates==1;
}
bool FindStackGetter(const Image& image,uintptr_t* out,uint32_t* n){return FindStackContract(image,0,kStackGetterWords,kStackGetterMasks,out,n);}
bool FindStackProducer(const Image& image,uintptr_t* out,uint32_t* n){return FindStackContract(image,1,kStackProducerWords,kStackProducerMasks,out,n);}
bool FindStackBuilder(const Image& image,uintptr_t* out,uint32_t* n){return FindStackContract(image,3,kStackBuilderWords,kStackBuilderMasks,out,n);}
bool FindStackChildren(const Image& image,uintptr_t* out,uint32_t* n){return FindStackContract(image,4,kStackChildrenWords,kStackChildrenMasks,out,n);}
bool FindStackOwner(const Image& image,uintptr_t* out,uint32_t* n){return FindStackContract(image,7,kStackLayoutOwnerWords,kStackLayoutOwnerMasks,out,n);}
bool StackFactoryArguments(const Image& image, uintptr_t factory) {
    int saved[2] = {}, argument[2] = {};
    unsigned stores = 0, pairs = 0;
    // The two values are saved before reading the layout enum. Decode their
    // actual frame slots, then require every constructor branch to use both.
    for (size_t i = 0; i < sizeof(kStackFactoryWords); i += 4) {
        const auto word = StackWord(image, factory + i);
        if (word == 0xf8407024u) break;
        if ((word & 0xffe00fffu) != 0xf80003a3u &&
            (word & 0xffe00fffu) != 0xf80003a5u) continue;
        if (stores == 2) return false;
        saved[stores++] = StackFrameDisplacement(word);
    }
    if (stores != 2 || saved[0] >= 0 || saved[1] >= 0 || saved[0] == saved[1]) return false;
    for (size_t i = 0; i + 8 <= sizeof(kStackFactoryWords); i += 4) {
        const auto first = StackWord(image, factory + i);
        const auto second = StackWord(image, factory + i + 4);
        if ((first & 0xffe00fffu) != 0xf84003a3u ||
            (second & 0xffe00fffu) != 0xf84003a5u) continue;
        const int a = StackFrameDisplacement(first), b = StackFrameDisplacement(second);
        if (!((a == saved[0] && b == saved[1]) || (a == saved[1] && b == saved[0]))) return false;
        if (pairs != 0 && (a != argument[0] || b != argument[1])) return false;
        argument[0] = a; argument[1] = b; ++pairs;
    }
    return pairs >= 2;
}
}
size_t FolderStackSiteSpan(const Image& image,unsigned site,uintptr_t offset) {
    if(site>=8)return 0;
    if(site==0)return StackGetterSpan(image,offset);
    return site==2 || site==5 || site==6 ? kFolderStackSites[site].pattern.size : StackBodySpan(image,offset);
}
bool VerifyFolderStackReturn(uintptr_t address) {
    return MatchPatternAtAddress(address, {kStackReturn, nullptr, sizeof(kStackReturn)});
}
bool DecodeFolderStackLayout(const Image& image, uintptr_t getter, uintptr_t producer,
                            uintptr_t factory, uintptr_t builder, uintptr_t children, FolderStackLayout* output) {
    if (output == nullptr) return false;
    const uintptr_t locations[] = {getter, producer, factory};
    for (unsigned i = 0; i < 3; ++i)
        if (i==2 ? !MatchBytes(image, locations[i], kFolderStackSites[i].pattern,kFlagRead|kFlagExec) :
            !ValidateStackSite(image,i,locations[i])) return false;
    if (!ValidateStackSite(image,3,builder) || !ValidateStackSite(image,4,children)) return false;
    if (!StackFactoryArguments(image, factory)) return false;
    const auto getter_span=FolderStackSiteSpan(image,0,getter);
    const auto producer_span=FolderStackSiteSpan(image,1,producer);
    const auto builder_span=FolderStackSiteSpan(image,3,builder);
    const auto children_span=FolderStackSiteSpan(image,4,children);
    FolderStackLayout out{};
    unsigned patch_count = 0, list_count = 0, factory_count = 0, allocation_count = 0;
    // Derive the return site from the validated indexed compressed-array read,
    // rather than carrying a research-time function-relative address.
    for (size_t i = 0; i + 32 <= getter_span; i += 4) {
        const auto w = StackWord(image, getter + i);
        uint16_t field = 0;
        if (StackLoad(w, 0xb8400000u, 2, 0, &field)) { out.list = field; ++list_count; }
        if (w == 0xb840f041u && StackWord(image, getter+i+4) == 0x8b1c8021u &&
            StackWord(image, getter+i+8) == 0x8b050830u && StackWord(image, getter+i+12) == 0xb840f200u) {
            out.patch = getter+i+16; ++patch_count;
        }
    }
    // Enum ordinal 24 is the semantic folder_2x1 branch of this validated switch,
    // not a launcher version/CID. A changed enum/switch contract is rejected.
    for (size_t i = 0; i + 12 <= sizeof(kStackFactoryWords); i += 4) {
        if (StackWord(image, factory+i) == 0xf100609fu &&
            (StackWord(image, factory+i+4) & 0xff00001fu) == 0x5400000cu &&
            StackAllocation(image, factory+i+8, &out.strategy)) ++factory_count;
    }
    constexpr uint32_t ld = 0xfc400000u;
    unsigned found = 0;
    uint16_t producer_list = 0, gap_width = 0, gap_height = 0, small_gap_again = 0, small_width_again = 0;
    for (size_t i = 0; i + 12 <= producer_span; i += 4) {
        const auto w = StackWord(image, producer+i), next = StackWord(image, producer+i+4);
        const auto third = StackWord(image, producer+i+8);
        uint16_t a=0, b=0;
        if (StackLoad(w, ld, 0, 2, &a) && StackLoad(next, ld, 1, 2, &b)) {
            if (third == 0x1e612802u) { out.x=a; out.padding_x=b; found |= 1u; }
            if (third == 0x1e612803u) { out.y=a; out.padding_y=b; found |= 2u; }
        }
        if (StackLoad(w, ld, 1, 3, &a) && StackLoad(next, ld, 2, 3, &b) &&
            (third & 0xffe00fffu) == (ld | (29u << 5u) | 3u)) {
            out.large_w=a; out.large_h=b; found |= 4u;
        }
        if (StackLoad(w, ld, 0, 3, &a) && StackLoad(next, ld, 1, 3, &b) && third == 0x1e612802u) {
            out.gap_x=a; gap_width=b; found |= 8u;
        }
        if (StackLoad(w, ld, 0, 3, &a) && StackLoad(next, ld, 1, 3, &b) && third == 0x1e612803u) {
            out.gap_y=a; gap_height=b; found |= 256u;
        }
        if (StackLoad(w, ld, 0, 0, &a) && StackLoad(next, ld, 2, 0, &b) && third == 0x1e622803u) {
            out.small_gap=a; out.small_h=b; found |= 16u;
        }
        if (StackLoad(w, ld, 0, 2, &a) && StackLoad(next, ld, 2, 2, &b) && third == 0x1e622803u) {
            small_gap_again=a; small_width_again=b; found |= 128u;
        }
        if (StackLoad(w, ld, 1, 0, &a)) { out.small_w=a; found |= 32u; }
        if (StackLoad(w, 0xf8400000u, 0, 2, &a) && next == 0xd1000401u && third == 0xeb01007fu) {
            out.count=a; found |= 64u;
        }
        if (StackLoad(w, 0xb8400000u, 1, 0, &a)) {
            producer_list=a;
            // Producer loads its list immediately before the PreviewItemInfo allocator.
            for (size_t distance=4; distance<=16 && i+distance<producer_span; distance+=4)
                if (StackAllocation(image, producer+i+distance, &out.item)) ++allocation_count;
        }
    }
    if (patch_count != 1 || list_count != 1 || factory_count != 1 || allocation_count != 1 ||
        found != 511u || gap_height != out.large_h || producer_list != out.list || gap_width != out.large_w ||
        small_gap_again != out.small_gap || small_width_again != out.small_w || out.strategy == out.item) return false;
    // Layout relations, not a known-ROM displacement list.
    const uint16_t doubles[] = {out.x,out.y,out.padding_x,out.padding_y,out.large_w,out.large_h,
                               out.small_w,out.small_h,out.gap_x,out.gap_y,out.small_gap};
    if (out.count < 7u || out.count % 8u != 7u || out.list < 7u || out.list % 4u != 3u ||
        (out.list < out.count + 8u && out.count < out.list + 4u)) return false;
    for (unsigned i=0;i<sizeof(doubles)/sizeof(doubles[0]);++i) {
        if (doubles[i] < 7u || doubles[i] % 8u != 7u) return false;
        if ((doubles[i] < out.list + 4u && out.list < doubles[i] + 8u) ||
            (doubles[i] < out.count + 8u && out.count < doubles[i] + 8u)) return false;
        for (unsigned j=0;j<i;++j) if (doubles[i] == doubles[j]) return false;
    }

    unsigned count_sites=0, loop_sites=0, controller_links=0;
    for (size_t i=0;i+36<=children_span;i+=4) {
        const auto w=StackWord(image,children+i);
        PoolRef pool{};
        if (w==0xb840b020u && StackWord(image,children+i+4)==0xf81f83a0u &&
            DecodePoolRef(StackWord(image,children+i+8),StackWord(image,children+i+12),&pool) &&
            pool.dest_reg==1 && IsBl(StackWord(image,children+i+16))) {
            out.children_count=children+i+20; ++count_sites;
        }
        if (w==0xd2800004u && StackWord(image,children+i+4)==0xf81e83a4u &&
            StackWord(image,children+i+8)==0xeb02009fu &&
            (StackWord(image,children+i+12)&0xff00001fu)==0x5400000au) {
            out.children_loop=children+i+16; ++loop_sites;
        }
    }
    // The builder calls controller.previewIconInfo. Follow that wrapper to the
    // already resolved strategy getter, decoding its controller field operand.
    for (size_t i=0;i+12<=builder_span;i+=4) {
        if (StackWord(image,builder+i)!=0xaa0603e2u || StackWord(image,builder+i+4)!=0xf81f03a6u) continue;
        const auto call=StackWord(image,builder+i+8); uintptr_t wrapper=0;
        if (!IsBl(call) || !DecodeBlTarget(builder+i+8,call,&wrapper) || !IsDartPrologue(image,wrapper)) return false;
        unsigned fields=0, links=0;
        for (size_t j=0;j+12<=128 && Contains(image,wrapper+j,12,kFlagRead|kFlagExec);j+=4) {
            const auto word=StackWord(image,wrapper+j);
            if (word==kDartReturn) break;
            if ((word&0xffe0001fu)==0xd2800011u &&
                StackWord(image,wrapper+j+4)==0xb8716820u && StackWord(image,wrapper+j+8)==0x8b1c8000u) {
                out.controller_strategy=static_cast<uint16_t>((word>>5u)&0xffffu); ++fields;
            }
            uintptr_t destination=0;
            if (IsBl(word) && DecodeBlTarget(wrapper+j,word,&destination) && destination==getter) ++links;
        }
        if (fields==1 && links==1) ++controller_links;
    }
    if (count_sites!=1 || loop_sites!=1 || controller_links!=1 ||
        out.controller_strategy<7 || out.controller_strategy%4!=3 || out.controller_strategy>4095) return false;
    // The output-list allocator receives unboxed length in x2. Its entry kills
    // x0 before use, so the source SMI may remain unchanged in the caller's x0.
    uint8_t rt=0, rn=0; uint32_t immediate=0; uintptr_t allocator=0;
    const auto allocation_call=StackWord(image,out.children_count+20);
    if (!IsLdrImm(StackWord(image,out.children_count+16),&rt,&rn,&immediate) || rt!=1 || rn!=27 ||
        !IsBl(allocation_call) || !DecodeBlTarget(out.children_count+20,allocation_call,&allocator) ||
        !IsDartPrologue(image,allocator) || !Contains(image,allocator,28,kFlagRead|kFlagExec) ||
        StackWord(image,allocator+12)!=0xaa0103e0u || StackWord(image,allocator+20)!=0xaa0203e1u) return false;
    // All big-folder variants share a constructor; the normal 1x1 layout uses
    // another constructor. Follow the verified 2x1 branch to identify the family,
    // then decode each member CID from its own current-image allocation stub.
    uintptr_t common_ctor=0;
    const auto ctor_after=[&](uintptr_t call,uintptr_t* ctor) {
        for(size_t d=4;d<=48 && call+d+4<=factory+sizeof(kStackFactoryWords);d+=4) {
            const auto w=StackWord(image,call+d);
            if(!IsBl(w)) continue;
            return DecodeBlTarget(call+d,w,ctor) && IsDartPrologue(image,*ctor);
        }
        return false;
    };
    for(size_t i=0;i+12<=sizeof(kStackFactoryWords);i+=4) {
        if(StackWord(image,factory+i)==0xf100609fu &&
           (StackWord(image,factory+i+4)&0xff00001fu)==0x5400000cu &&
           !ctor_after(factory+i+8,&common_ctor)) return false;
    }
    if(!common_ctor || !Contains(image,common_ctor,48,kFlagRead|kFlagExec)) return false;
    // Big-folder constructor initializes five independent primitive double slots.
    unsigned initialized=0;
    for(size_t j=8;j<28;j+=4)
        if((StackWord(image,common_ctor+j)&0xffe00fffu)==0xf800003fu) ++initialized;
    if(initialized!=5 || !IsBl(StackWord(image,common_ctor+28))) return false;
    for(size_t i=0;i+4<=sizeof(kStackFactoryWords);i+=4) {
        uint32_t cid=0;uintptr_t ctor=0;
        if(!StackAllocation(image,factory+i,&cid) || !ctor_after(factory+i,&ctor) || ctor!=common_ctor) continue;
        for(unsigned j=0;j<out.strategy_count;++j) if(out.strategies[j]==cid) return false;
        if(out.strategy_count>=sizeof(out.strategies)/sizeof(out.strategies[0])) return false;
        out.strategies[out.strategy_count++]=cid;
    }
    bool family_member=false;
    for(unsigned i=0;i<out.strategy_count;++i) family_member |= out.strategies[i]==out.strategy;
    if(!family_member || out.strategy_count<2) return false;
    *output=out;
    return true;
}

bool VerifyFolderStackChildren(uintptr_t address, bool loop) {
    return MatchPatternAtAddress(address,{loop ? kStackLoopWindow : kStackCountWindow,nullptr,16});
}

// The frame wrapper calls the scheduler and returns null after transient callbacks,
// before build/layout. Refresh is an original zero-argument Dart body (receiver unused).
// Both functions must match; no Dart call from a C++ frame or synthetic GC frame.
bool DecodeFolderStackRefresh(const Image& image, uintptr_t frame, uintptr_t refresh, uintptr_t owner,
                             FolderStackLayout* out, const uint8_t* identity) {
    if (!out || !MatchBytes(image,frame,kFolderStackSites[5].pattern,kFlagRead|kFlagExec) ||
        !MatchBytes(image,refresh,kFolderStackSites[6].pattern,kFlagRead|kFlagExec) ||
        !ValidateStackSite(image,7,owner)) return false;
    unsigned returns=0;
    for(size_t i=4;i+16<=sizeof(kStackFrameWords);i+=4) {
        if (!IsBl(StackWord(image,frame+i-4))) continue;
        // Patched epilogue bytes are checked separately by the owner at installation.
        if (i+16==sizeof(kStackFrameWords)) {
            uintptr_t begin=0;
            if (!DecodeBlTarget(frame+i-4,StackWord(image,frame+i-4),&begin) ||
                !MatchBytes(image,begin,{reinterpret_cast<const uint8_t*>(kStackBeginWords),
                    reinterpret_cast<const uint8_t*>(kStackBeginMasks),sizeof(kStackBeginWords)},kFlagRead|kFlagExec)) return false;
            out->frame_return=frame+i; ++returns;
        }
    }
    // Decode the first model singleton slot, guarding it before any initialization.
    uint8_t rt=0,rn=0; uint32_t offset=0;
    if (StackWord(image,refresh+12)!=0xf9403f40u ||
        !IsLdrImm(StackWord(image,refresh+16),&rt,&rn,&offset) || rt!=0 || rn!=0 ||
        offset==0 || offset>4095) return false;
    out->model_slot=offset*8u;
    // The native method handles all folders and only notifies preview listeners.
    // Guard the DI singleton as well; do not initialize it from a background isolate.
    unsigned singleton=0;
    for(size_t i=20;i+8<sizeof(kStackRefreshWords);i+=4) {
        if (StackWord(image,refresh+i)!=0xf9403f40u ||
            !IsLdrImm(StackWord(image,refresh+i+4),&rt,&rn,&offset) || rt!=0 || rn!=0) continue;
        out->registry_slot=offset*8u; ++singleton; break;
    }
    if (returns!=1 || singleton!=1 || out->registry_slot==0 || out->registry_slot%8 ||
        out->registry_slot>65528 || out->registry_slot==out->model_slot) return false;
    const auto owner_span=FolderStackSiteSpan(image,7,owner);
    unsigned ids=0, windows=0, exits=0;
    for(size_t i=8;i+4<=owner_span;i+=4) {
        PoolRef id{};
        if(StackWord(image,owner+i)==0xb8017002u &&
           DecodePoolRef(StackWord(image,owner+i-8),StackWord(image,owner+i-4),&id) && id.dest_reg==2) {
            out->layout_id_pool=id.add_bytes+id.ldr_offset; ++ids;
        }
    }
    // Allocation of the original single-ID array precedes this window. The
    // following native stores/calls remain untouched, including Dart barriers.
    for(size_t i=20;i+24<=sizeof(kStackRefreshWords);i+=4) {
        if(StackWord(image,refresh+i-20)==0xaa1603e1u &&
           StackWord(image,refresh+i-16)==0xd2800042u &&
           (StackWord(image,refresh+i-12)&0xffe00fffu)==0xf80003a0u &&
           IsBl(StackWord(image,refresh+i-8)) &&
           (StackWord(image,refresh+i-4)&0xffe00fffu)==0xf80003a0u) {
            out->refresh_id=refresh+i; ++windows;
        }
        // Shared normal/catch join is the sole null-return epilogue at the end
        // of the function's main body, followed by a cold collection error path.
        if(i+20<sizeof(kStackRefreshWords) &&
           StackWord(image,refresh+i+16)==0xaa0303e1u &&
           StackWord(image,refresh+i+20)==0xaa0403e0u &&
           IsBl(StackWord(image,refresh+i-4))) {
            out->refresh_return=refresh+i; ++exits;
        }
    }
    if(ids!=1 || windows!=1 || exits!=1) return false;
    // Read the original pool offsets before installation. They are only used by
    // the identity shim; a relocated contract must decode them from new code.
    const auto identity_word=[&](size_t i) {
        uint32_t w=0;
        if(identity) memcpy(&w,identity+i,4); else w=StackWord(image,out->refresh_id+i);
        return w;
    };
    PoolRef original{};
    if(!DecodePoolRef(identity_word(0),identity_word(4),&original) || original.dest_reg!=16 ||
       identity_word(8)!=0xb800f010u ||
       !IsLdrImm(identity_word(12),&rt,&rn,&offset) || rt!=1 || rn!=27) return false;
    out->preview_id_pool=original.add_bytes+original.ldr_offset; out->typeargs_pool=offset*8u;
    out->refresh=refresh;
    return true;
}
bool VerifyFolderStackFrame(uintptr_t address) {
    constexpr uint8_t bytes[]={0xe0,0x03,0x16,0xaa,0xef,0x03,0x1d,0xaa,0xfd,0x79,0xc1,0xa8,0xc0,0x03,0x5f,0xd6};
    return MatchPatternAtAddress(address,{bytes,nullptr,sizeof(bytes)});
}

bool VerifyFolderStackIdentity(const Image& image, uintptr_t address, const FolderStackLayout& layout) {
    PoolRef id{}; uint8_t rt=0,rn=0; uint32_t offset=0;
    return DecodePoolRef(StackWord(image,address),StackWord(image,address+4),&id) &&
        id.dest_reg==16 && id.add_bytes+id.ldr_offset==layout.preview_id_pool &&
        StackWord(image,address+8)==0xb800f010u &&
        IsLdrImm(StackWord(image,address+12),&rt,&rn,&offset) && rt==1 && rn==27 && offset*8u==layout.typeargs_pool;
}
