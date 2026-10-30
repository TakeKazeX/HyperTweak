// SPDX-License-Identifier: Apache-2.0
// Compile with dart_image.cpp/dart_targets.cpp and ASan/UBSan; pass verified libapp.so.
#include "../../main/cpp/NativeRules/rules/recents_haptic_rule.cpp"
#include <assert.h>
#include <fstream>
#include <vector>
#include <iterator>
using namespace hypertweak::native;
namespace {
std::vector<uint8_t> image_bytes;
unsigned installs = 0, removes = 0, signals = 0;
bool prepared = true;
dart::TargetResult resolved{};
}
extern "C" void HyperTweakRecentsScrollHook() {}
extern "C" bool HyperTweakSendRecentsHaptic() { ++signals; return true; }
namespace hypertweak::native {
void LogInfo(const char*, ...) {}
void LogWarn(const char*, ...) {}
bool ResolveDartSites(void*, const dart::TargetSpec& spec, DartResolution* out) {
    *out = {}; out->base = image_bytes.data();
    if (!prepared) { out->reason = "dart_preparation_pending"; return false; }
    out->located = resolved.resolved; out->site_count = spec.site_count;
    for (size_t i = 0; i < spec.site_count; ++i) {
        out->sites[i] = {spec.sites[i].name,
            reinterpret_cast<uintptr_t>(out->base) + resolved.sites[i].offset,
            true, spec.sites[i].verify, spec.sites[i].verify_delta};
    }
    return out->located;
}
uintptr_t DartResolution::Find(const char* name) const {
    for (size_t i = 0; i < site_count; ++i) if (!strcmp(name, sites[i].name)) return sites[i].address;
    return 0;
}
bool DartResolution::MatchVerify(const char* name) const {
    for (size_t i = 0; i < site_count; ++i) if (!strcmp(name, sites[i].name))
        return dart::MatchPatternAtAddress(sites[i].address, sites[i].verify);
    return false;
}
}
int InstallInlineHook(void* at, void*, void** backup) {
    ++installs; memset(at, 0xa5, 16); *backup = reinterpret_cast<void*>(1); return 0;
}
int RemoveInlineHook(void* at) { ++removes; memcpy(at, original, 16); return 0; }
int main(int argc, char** argv) {
    assert(argc == 2);
    std::ifstream file(argv[1], std::ios::binary);
    image_bytes.assign(std::istreambuf_iterator<char>(file), {});
    assert(!image_bytes.empty());
    dart::Image image{}; assert(dart::ParseImage(image_bytes.data(), &image));
    resolved = dart::ResolveTarget(image, dart::kRecentsHapticTarget);
    if(!resolved.resolved || !resolved.verified) {
        for(size_t i=0;i<resolved.site_count;++i) fprintf(stderr,"%s found=%u verified=%u candidates=%u\n",
            resolved.sites[i].name,resolved.sites[i].found,resolved.sites[i].verified,resolved.sites[i].candidates);
    }
    assert(resolved.resolved && resolved.verified);
    for (size_t i = 0; i < 4; ++i) assert(resolved.sites[i].candidates == 1);
    DartResolution r{}; assert(ResolveDartSites(nullptr, dart::kRecentsHapticTarget, &r));
    Layout decoded{}; assert(Decode(r, &decoded));
    assert(decoded.observer_entry&&decoded.closure_entry==(decoded.observer_entry!=resolved.sites[0].offset));
    if(decoded.closure_entry) {
        // The shared function must remain untouched; only StackList's own
        // paired closure may feed its state/widget layout to the observer.
        assert(decoded.observer_entry+40==resolved.sites[1].offset);
        uint32_t call=0;assert(dart::ReadInsn(image,decoded.observer_entry+24,&call));
        const auto wrong=0x94000000u;
        memcpy(image_bytes.data()+decoded.observer_entry+24,&wrong,4);
        Layout rejected{};assert(!Decode(r,&rejected));
        memcpy(image_bytes.data()+decoded.observer_entry+24,&call,4);
    }
    assert(decoded.start != decoded.update && decoded.end != decoded.start);
    for (size_t i = 0; i < 4; ++i) {
        const auto offset = resolved.sites[i].offset + (i == 0 ? 16 : 0);
        const auto saved = image_bytes[offset]; image_bytes[offset] ^= 1;
        assert(!dart::ResolveTarget(image, dart::kRecentsHapticTarget).resolved);
        image_bytes[offset] = saved;
    }
    // Duplicated ABI families must fail rather than select the first match.
    const auto& builder = dart::kRecentsHapticTarget.sites[1];
    const auto first = resolved.sites[1].offset;
    const auto duplicate = image.loads[image.load_count - 1].start;
    // Select an executable area that does not overlap any of these four sites.
    uintptr_t spare = 0;
    for (size_t i = 0; i < image.load_count; ++i)
        if (image.loads[i].flags & dart::kFlagExec) spare = image.loads[i].end - 2048;
    assert(spare && spare != duplicate);
    // Both lazy and constant receiver dispatches keep one unique scroll entry.
    // A copied entry is ambiguous even though its call operands can relocate.
    const auto scroll=resolved.sites[0].offset;
    uint32_t scroll_receiver=0;assert(dart::ReadInsn(image,scroll+36,&scroll_receiver));
    const auto scroll_size=scroll_receiver==0xf9403f40u?size_t{152}:size_t{92};
    std::vector<uint8_t> scroll_saved(image_bytes.begin()+spare,image_bytes.begin()+spare+scroll_size);
    memcpy(image_bytes.data()+spare,image_bytes.data()+scroll,scroll_size);
    assert(!dart::ResolveTarget(image,dart::kRecentsHapticTarget).resolved);
    memcpy(image_bytes.data()+spare,scroll_saved.data(),scroll_size);
    if(scroll_size==92) {
        uint32_t receiver=0;memcpy(&receiver,image_bytes.data()+scroll+68,4);
        const auto different_receiver=receiver^(1u<<10);
        memcpy(image_bytes.data()+scroll+68,&different_receiver,4);
        assert(!dart::ResolveTarget(image,dart::kRecentsHapticTarget).resolved);
        memcpy(image_bytes.data()+scroll+68,&receiver,4);
    }
    std::vector<uint8_t> old(image_bytes.begin() + spare, image_bytes.begin() + spare + builder.pattern.size);
    memcpy(image_bytes.data() + spare, image_bytes.data() + first, builder.pattern.size);
    assert(!dart::ResolveTarget(image, dart::kRecentsHapticTarget).resolved);
    memcpy(image_bytes.data() + spare, old.data(), old.size());
    // Semantic producer discovery survives relocation and follows repaired calls,
    // including the out-of-line constructor. A duplicated complete producer is ambiguous.
    const auto body_size=[&](uintptr_t start) {
        for(size_t i=12;i<1024;i+=4) {
            uint32_t word=0,restore=0,pop=0;
            assert(dart::ReadInsn(image,start+i,&word));
            assert(dart::ReadInsn(image,start+i-4,&pop));
            assert(dart::ReadInsn(image,start+i-8,&restore));
            if(word==dart::kDartReturn && pop==dart::kDartPopFrame && restore==dart::kDartRestoreFrame)return i+4;
        }
        assert(false);return size_t{0};
    };
    for(unsigned index : {2u,3u}) {
        const auto source=resolved.sites[index].offset;
        const auto size=body_size(source);
        std::vector<uint8_t> saved(image_bytes.begin()+spare,image_bytes.begin()+spare+size);
        std::vector<uint8_t> copy(image_bytes.begin()+source,image_bytes.begin()+source+size);
        for(size_t i=0;i<size;i+=4) {
            uint32_t word=0;memcpy(&word,copy.data()+i,4);uintptr_t destination=0;
            if(!dart::IsBl(word) || !dart::DecodeBlTarget(source+i,word,&destination))continue;
            if(destination>=source && destination<source+size)destination=spare+destination-source;
            const int64_t displacement=static_cast<int64_t>(destination)-static_cast<int64_t>(spare+i);
            word=0x94000000u|(static_cast<uint32_t>(displacement/4)&0x03ffffffu);memcpy(copy.data()+i,&word,4);
        }
        memcpy(image_bytes.data()+spare,copy.data(),size);
        assert(!dart::ResolveTarget(image,dart::kRecentsHapticTarget).resolved);
        uint32_t original_entry=0;memcpy(&original_entry,image_bytes.data()+source,4);
        memset(image_bytes.data()+source,0,4);
        const auto relocated=dart::ResolveTarget(image,dart::kRecentsHapticTarget);
        assert(relocated.resolved && relocated.verified && relocated.sites[index].offset==spare);
        Layout moved{};assert(dart::DecodeRecentsHapticLayout(image,relocated.sites[0].offset,relocated.sites[1].offset,
            relocated.sites[2].offset,relocated.sites[3].offset,&moved));
        assert(moved.update==decoded.update && moved.metrics==decoded.metrics && moved.delta==decoded.delta);
        memcpy(image_bytes.data()+source,&original_entry,4);
        memcpy(image_bytes.data()+spare,saved.data(),size);
    }
    // A new CID is decoded through the allocator rather than a ROM/CID allowlist.
    uint32_t instruction; assert(dart::ReadInsn(image, decoded.metrics_allocation, &instruction));
    uintptr_t stub; assert(dart::DecodeBlTarget(decoded.metrics_allocation, instruction, &stub));
    uint32_t allocator=0;assert(dart::ReadInsn(image,stub,&allocator));
    auto changed = allocator ^ (1u << 17); // Change a CID bit within MOVZ's immediate.
    memcpy(image_bytes.data() + stub, &changed, 4);
    Layout shifted{}; assert(Decode(r, &shifted)); assert(shifted.metrics != decoded.metrics);
    memcpy(image_bytes.data() + stub, &allocator, 4);
    prepared = false; SetRecentsHapticEnabled(true);
    assert(!ApplyRecentsHapticRule(nullptr)); assert(installs == 0);
    prepared = true; assert(ApplyRecentsHapticRule(nullptr)); assert(installs == 1);
    assert(ApplyRecentsHapticRule(nullptr)); assert(installs == 1);
    SetRecentsHapticEnabled(false); assert(ApplyRecentsHapticRule(nullptr)); assert(removes == 0);
    SetRecentsHapticEnabled(true); assert(ApplyRecentsHapticRule(nullptr)); assert(installs == 1);
    // Synthetic Dart objects follow the instruction-verified host layout.
    std::vector<uint8_t> heap_bytes(4096);
    const auto origin = reinterpret_cast<uintptr_t>(heap_bytes.data());
    const auto heap = origin >> 32;
    auto object = [&](size_t offset, uint32_t cid) { uintptr_t p = origin + offset + 1; uint32_t tag = cid << 12; memcpy(reinterpret_cast<void*>(p - 1), &tag, 4); return p; };
    const auto null = object(0, 170), state = object(128, 7207), widget = object(256, 8216);
    const auto metrics = object(384, layout.metrics);
    const auto pixels = object(512, layout.number), min = object(640, layout.number), max = object(768, layout.number);
    const auto notification = object(896, layout.start);
    const auto delta = object(1024, layout.number);
    auto pointer = [](uintptr_t p, size_t offset, uintptr_t value) { uint32_t word = static_cast<uint32_t>(value); memcpy(reinterpret_cast<void*>(p + offset), &word, 4); };
    auto number = [](uintptr_t p, size_t offset, double value) { memcpy(reinterpret_cast<void*>(p + offset), &value, 8); };
    pointer(state, 0xb, widget); number(widget, 0x13, 100);
    pointer(notification, 0xf, metrics); pointer(notification, 0x17, state);
    pointer(notification, 0x1b, delta); number(delta, 7, 0);
    pointer(metrics, 0xf, pixels); pointer(metrics, 7, min); pointer(metrics, 0xb, max);
    number(min, 7, -400); number(max, 7, 0); number(pixels, 7, 0);
    HyperTweakObserveRecentsScroll(state, notification, heap, null); assert(signals == 0);
    object(896, layout.update); number(pixels, 7, -60);
    HyperTweakObserveRecentsScroll(state, notification, heap, null); assert(signals == 1);
    number(pixels, 7, -65); HyperTweakObserveRecentsScroll(state, notification, heap, null); assert(signals == 1);
    SetRecentsHapticEnabled(false); number(pixels, 7, -200);
    HyperTweakObserveRecentsScroll(state, notification, heap, null); assert(signals == 1);
    SetRecentsHapticEnabled(true); HyperTweakObserveRecentsScroll(state, notification, heap, null); assert(signals == 1);
    // A drag update after a programmatic entry start has its own valid previous pixels.
    object(896, layout.start); pointer(notification, 0x17, null); number(pixels, 7, 0);
    HyperTweakObserveRecentsScroll(state, notification, heap, null);
    object(896, layout.update); pointer(notification, 0x17, state);
    number(pixels, 7, -60); number(delta, 7, -60);
    HyperTweakObserveRecentsScroll(state, notification, heap, null); assert(signals == 2);
    SetRecentsHapticEnabled(true); // Identical settings replay must keep this drag session.
    number(pixels, 7, -160); number(delta, 7, NAN);
    HyperTweakObserveRecentsScroll(state, notification, heap, null); assert(signals == 3);
    // Foreign patches are preserved; a remapped original can be repaired.
    auto site = image_bytes.data()+layout.observer_entry;
    memset(site, 0xb6, 16); assert(!ApplyRecentsHapticRule(nullptr)); assert(removes == 0);
    memcpy(site, original, 16); assert(ApplyRecentsHapticRule(nullptr)); assert(removes == 1 && installs == 2);
    puts("recents target, ambiguity, CID, gesture, generation and hook ownership checks passed");
}
