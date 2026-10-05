// SPDX-License-Identifier: Apache-2.0
// Real-image negative controls and adaptive operand changes. No target RVA is
// encoded in production; this test asserts the independently researched baseline.
#include "dart_targets.h"
#include <assert.h>
#include <fstream>
#include <iterator>
#include <vector>
#include <string.h>
#include <stdio.h>
using namespace hypertweak::native::dart;
static uint32_t Read(const std::vector<uint8_t>& data, uintptr_t at) {
    uint32_t value; memcpy(&value, data.data() + at, 4); return value;
}
static void Put(std::vector<uint8_t>& data, uintptr_t at, uint32_t value) {
    memcpy(data.data() + at, &value, 4);
}
static TargetResult Resolve(std::vector<uint8_t>& data) {
    Image image{}; assert(ParseImage(data.data(), &image));
    return ResolveTarget(image, kAssistantWidgetTarget);
}
int main(int argc, char** argv) {
    assert(argc == 2);
    std::ifstream stream(argv[1], std::ios::binary);
    std::vector<uint8_t> original{std::istreambuf_iterator<char>(stream), {}};
    assert(!original.empty());
    auto data = original;
    auto result = Resolve(data);
    assert(result.resolved && result.verified && result.site_count == 2);
    const auto gate = result.sites[0].offset, type = result.sites[1].offset;
    assert(gate == 0xc9bd18 && type == 0x19ad8f8);
    assert(result.sites[0].tier == FindTier::kStructural);
    Image image{}; assert(ParseImage(data.data(), &image));
    uintptr_t helper = 0; assert(DecodeBlTarget(gate + 20, Read(data, gate + 20), &helper));
    assert(IsDartPrologue(image, helper)); // signed pre-index displacement

    // Independently moving either endpoint's flag breaks the data contract.
    Put(data, gate, (Read(data, gate) & ~0x1ff000u) | (0xf7u << 12));
    assert(!Resolve(data).resolved);
    // Moving both readers and the compiler's class id remains resolvable.
    Put(data, type + 64, (Read(data, type + 64) & ~0x1ff000u) | (0xf7u << 12));
    Put(data, type, (Read(data, type) & ~0x3ffc00u) | (2040u << 10));
    result = Resolve(data);
    assert(result.resolved && result.verified);

    data = original;
    Put(data, helper + 0x24, 0xf100143fu); // width=2 comparison changed to 5
    assert(!Resolve(data).resolved);
    data = original;
    Put(data, gate + 24, Read(data, gate + 24) + 0x20u); // reject targets disagree
    assert(!Resolve(data).resolved);
    data = original;
    Put(data, type + 20, 0xd2800083u); // serializer no longer sends AppWidget type=1
    assert(!Resolve(data).resolved);

    data = original;
    Put(data, gate + 28, 0xf85f83a1u); // success no longer restores x0 receiver
    assert(!Resolve(data).resolved);

    // A second complete gate sharing the serializer must reject both sites.
    data = original;
    uintptr_t duplicate = 0;
    for (size_t i = 0; i < image.load_count; ++i) {
        if ((image.loads[i].flags & 5u) == 5u) { duplicate = image.loads[i].start + 4096; break; }
    }
    assert(duplicate != 0 && duplicate != gate);
    memcpy(data.data() + duplicate, data.data() + gate, 200);
    const intptr_t delta = static_cast<intptr_t>(helper) - static_cast<intptr_t>(duplicate + 20);
    Put(data, duplicate + 20, 0x94000000u | (static_cast<uint32_t>(delta / 4) & 0x3ffffffu));
    result = Resolve(data);
    assert(!result.resolved && result.sites[0].candidates == 2);
    puts("PASS: unique structural family, adaptive field/CID, rejected mismatched readers, altered dimensions/protocol/branches and ambiguous gates");
}
