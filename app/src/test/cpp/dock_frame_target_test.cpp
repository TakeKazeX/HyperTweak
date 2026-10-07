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
    assert(resolved.site_count == 6);
    uint32_t disabled=0;
    assert(DecodeDockEditingPool(image,resolved.sites[4].offset,&disabled) && disabled!=0);
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
