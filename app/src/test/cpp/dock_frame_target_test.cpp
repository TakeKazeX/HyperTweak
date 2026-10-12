#include "dart_targets.h"
#include <cassert>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <vector>
#include <cstring>
using namespace hypertweak::native::dart;
int main(int argc, char** argv) {
    assert(argc == 2);
    std::ifstream input(argv[1], std::ios::binary);
    std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(input)), {});
    Image image{}; assert(ParseImage(bytes.data(), &image));
    auto resolved = ResolveTarget(image, kDockFrameTarget); assert(resolved.resolved && resolved.verified);
    const auto site = resolved.sites[0].offset;
    DockFrameLayout layout{}; assert(DecodeDockFrameLayout(image, site, &layout));
    DockTickLayout tick{};assert(DecodeDockTickLayout(image,resolved.sites[5].offset,&tick));
    assert(tick.receiver==-16 && tick.scale>0 && tick.y==tick.scale+8 && tick.value==0x13);
    assert(layout.alpha == -48 && layout.offset == -16 && layout.scale == -24 && layout.origin == -40);
    assert(resolved.site_count == 7);
    const auto unlock = resolved.sites[6].offset;
    assert(VerifyDockUnlockLayout(image, unlock));
    bytes[unlock-36]^=1;
    assert(!ResolveTarget(image,kDockFrameTarget).resolved);
    bytes[unlock-36]^=1;
    uint32_t disabled=0;
    assert(DecodeDockEditingPool(image,resolved.sites[4].offset,&disabled) && disabled!=0);
    // Entry bytes do not identify the observer family. Discovery must survive
    // arbitrary entry ownership while its saved-value body/returns stay intact.
    const uintptr_t controller_begin = resolved.sites[1].offset - 64;
    uint32_t call=0; uintptr_t sync=0;
    assert(ReadInsn(image,resolved.sites[2].offset-4,&call));
    assert(DecodeBlTarget(resolved.sites[2].offset-4,call,&sync));
    uint8_t saved_controller[16]{}, saved_sync[16]{};
    memcpy(saved_controller,bytes.data()+controller_begin,16);
    memcpy(saved_sync,bytes.data()+sync,16);
    constexpr uint32_t detour[]={0x901b3071,0x91324231,0xd61f0220,0xd503201f};
    memcpy(bytes.data()+controller_begin,detour,16);
    memcpy(bytes.data()+sync,detour,16);
    auto shared=ResolveTarget(image,kDockFrameTarget);
    assert(shared.resolved && shared.verified && shared.site_count==resolved.site_count);
    for(size_t i=0;i<shared.site_count;++i)assert(shared.sites[i].offset==resolved.sites[i].offset);
    assert(DecodeDockTickLayout(image,resolved.sites[5].offset,&tick));
    // No jump encoding or module identity is part of this contract.
    memset(bytes.data()+controller_begin,0,16);
    memset(bytes.data()+sync,0xff,16);
    assert(ResolveTarget(image,kDockFrameTarget).resolved);
    // The scalar body and the return we actually patch remain mandatory.
    bytes[controller_begin+40]^=1;
    assert(!ResolveTarget(image,kDockFrameTarget).resolved);
    bytes[controller_begin+40]^=1;
    bytes[resolved.sites[1].offset]^=1;
    assert(!ResolveTarget(image,kDockFrameTarget).resolved);
    bytes[resolved.sites[1].offset]^=1;
    memcpy(bytes.data()+controller_begin,saved_controller,16);
    memcpy(bytes.data()+sync,saved_sync,16);
    // A duplicated scalar consumer family is ambiguous even though every
    // individual return and shared call target is valid.
    uintptr_t controller_copy = 0;
    for (size_t i=0;i<image.load_count;++i) if (image.loads[i].flags & kFlagExec) {
        controller_copy=image.loads[i].start+0x400; break;
    }
    const size_t controller_size=resolved.sites[2].offset+16-controller_begin;
    assert(controller_copy!=0 && controller_copy+controller_size<controller_begin);
    std::vector<uint8_t> saved_copy(bytes.begin()+controller_copy,bytes.begin()+controller_copy+controller_size);
    memcpy(bytes.data()+controller_copy,bytes.data()+controller_begin,controller_size);
    for (size_t n=0;n<controller_size;n+=4) {
        uint32_t instruction=0; uintptr_t destination=0;
        assert(ReadInsn(image,controller_begin+n,&instruction));
        if (!DecodeBlTarget(controller_begin+n,instruction,&destination)) continue;
        const int64_t displacement=static_cast<int64_t>(destination)-static_cast<int64_t>(controller_copy+n);
        assert(displacement>=-(1ll<<27) && displacement<(1ll<<27));
        instruction=0x94000000u | (static_cast<uint32_t>(displacement/4)&0x03ffffffu);
        memcpy(bytes.data()+controller_copy+n,&instruction,4);
    }
    assert(!ResolveTarget(image,kDockFrameTarget).resolved);
    memcpy(bytes.data()+controller_copy,saved_copy.data(),controller_size);
    assert(ResolveTarget(image,kDockFrameTarget).resolved);
    for(size_t i=1;i<6;++i) {
        const auto end=resolved.sites[i].offset;
        bytes[end+12]^=1;
        assert(!ResolveTarget(image,kDockFrameTarget).resolved);
        bytes[end+12]^=1;
    }
    // Breaking a displaced epilogue or a captured slot must reject the entire family.
    bytes[site + 12] ^= 1;
    assert(!ResolveTarget(image, kDockFrameTarget).resolved);
    bytes[site + 12] ^= 1;
    bytes[site - 24 + 2] ^= 0x80;
    assert(!ResolveTarget(image, kDockFrameTarget).resolved);
    bytes[site - 24 + 2] ^= 0x80;
    // A second otherwise valid family is ambiguity, never a guessed first match.
    const size_t size = site + 16 - layout.begin;
    uintptr_t duplicate = 0;
    for (size_t i = 0; i < image.load_count; ++i) if ((image.loads[i].flags & kFlagExec) != 0u) {
        duplicate = image.loads[i].start + 0x100; break;
    }
    assert(duplicate != 0);
    assert(duplicate + size < layout.begin);
    std::vector<uint8_t> saved(bytes.begin() + duplicate, bytes.begin() + duplicate + size);
    memcpy(bytes.data() + duplicate, bytes.data() + layout.begin, size);
    assert(!ResolveTarget(image, kDockFrameTarget).resolved);
    memcpy(bytes.data() + duplicate, saved.data(), size);
    assert(ResolveTarget(image, kDockFrameTarget).resolved);
    printf("Dock family, slots, corruption and ambiguity: passed (%s)\n", argv[1]);
}
