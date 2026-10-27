// SPDX-License-Identifier: Apache-2.0
// Compile with dart_image.cpp/dart_targets.cpp and ASan/UBSan; pass verified libapp.so.
#include "../../main/cpp/NativeRules/rules/folder_stack_rule.cpp"
#include <assert.h>
#include "dart_resolution_cache.h"
#include <fstream>
#include <vector>
#include <iterator>
#include <map>
#include <array>
using namespace hypertweak::native;
namespace {
std::vector<uint8_t> image_bytes;
unsigned installs = 0, removes = 0, fail_at = 0, null_backup_at = 0;
std::map<void*,std::array<uint8_t,16>> saved_hooks;
bool prepared = true;
dart::TargetResult resolved{};
}
extern "C" void HyperTweakFolderStackHook() {}
extern "C" void HyperTweakFolderStackCountHook() {}
extern "C" void HyperTweakFolderStackOrderHook() {}
extern "C" void HyperTweakFolderStackFrameHook() {}
extern "C" void HyperTweakFolderStackIdentityHook() {}
extern "C" void HyperTweakFolderStackRefreshReturnHook() {}
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
        return dart::MatchPatternAtAddress(sites[i].address + sites[i].verify_delta, sites[i].verify);
    return false;
}
}
int InstallInlineHook(void* at, void*, void** backup) {
    ++installs; if (installs==fail_at) return 1;
    std::array<uint8_t,16> bytes{}; memcpy(bytes.data(),at,16); saved_hooks[at]=bytes;
    memset(at,0xa5,16); *backup=installs==null_backup_at ? nullptr : reinterpret_cast<void*>(1); return 0;
}
int RemoveInlineHook(void* at) { ++removes; assert(saved_hooks.count(at)); memcpy(at,saved_hooks.at(at).data(),16); saved_hooks.erase(at); return 0; }
int main(int argc, char** argv) {
    assert(argc == 2);
    std::ifstream file(argv[1], std::ios::binary);
    image_bytes.assign(std::istreambuf_iterator<char>(file), {});
    dart::Image image{}; assert(dart::ParseImage(image_bytes.data(), &image));
    resolved = dart::ResolveTarget(image, dart::kFolderStackTarget);
    if (!resolved.resolved || !resolved.verified) fprintf(stderr,"target rejected: %s %s\n",resolved.reason,resolved.failure);
    assert(resolved.resolved && resolved.verified);
    for (size_t i=0;i<8;++i) assert(resolved.sites[i].candidates == 1);
    uint8_t identity[kDartIdentitySize]={4,0,0,0,16,0,0,0,3,0,0,0,'G','N','U',0};
    memset(identity+16,0x31,16);
    DartCacheRecord cache{}; dart::TargetResult cached{};
    assert(EncodeDartCache(image,dart::kFolderStackTarget,identity,1,resolved,&cache));
    assert(DecodeDartCache(image,dart::kFolderStackTarget,identity,1,cache,&cached));
    assert(!DecodeDartCache(image,dart::kFolderStackTarget,identity,2,cache,&cached));
    const auto evidence=resolved.sites[0].offset;
    image_bytes[evidence]^=1;
    assert(!DecodeDartCache(image,dart::kFolderStackTarget,identity,1,cache,&cached));
    image_bytes[evidence]^=1;
    DartResolution resolution{}; assert(ResolveDartSites(nullptr,dart::kFolderStackTarget,&resolution));
    Layout decoded{}; assert(Decode(resolution,&decoded));
    assert(decoded.strategy != decoded.item);
    // Fixture evidence is permitted in tests; production decodes scaled LDR operands.
    const bool earlier_fixture = decoded.model_slot==0x3340 && decoded.registry_slot==0x1d00 &&
        decoded.layout_id_pool==0xef10 && decoded.preview_id_pool==0xf128 && decoded.typeargs_pool==0x1318;
    const bool current_fixture = decoded.model_slot==0x3250 && decoded.registry_slot==0x1cd8 &&
        decoded.layout_id_pool==0xf240 && decoded.preview_id_pool==0xff68 && decoded.typeargs_pool==0x1318;
    assert(earlier_fixture || current_fixture);
    assert(dart::VerifyFolderStackIdentity(image,decoded.refresh_id,decoded));
    assert(dart::VerifyFolderStackFrame(reinterpret_cast<uintptr_t>(image_bytes.data())+decoded.refresh_return));
    // A saved frame slot must match its reload across the native builder call.
    for (const auto offset : {resolved.sites[7].offset + 0x50}) {
        uint32_t original = 0; assert(dart::ReadInsn(image, offset, &original));
        const auto changed = original + (8u << 12u);
        memcpy(image_bytes.data()+offset, &changed, 4);
        Layout invalid{}; assert(!Decode(resolution, &invalid));
        memcpy(image_bytes.data()+offset, &original, 4);
    }
    // The GetBuilder ID and the normal preview ID both move with their pool slots.
    const auto owner=resolved.sites[7].offset;
    bool moved_id=false;
    for(size_t i=8;i<dart::FolderStackSiteSpan(image,7,owner);i+=4) {
        uint32_t store=0,load=0;assert(dart::ReadInsn(image,owner+i,&store));
        if(store!=0xb8017002u)continue;
        assert(dart::ReadInsn(image,owner+i-4,&load));
        const auto shifted=load+(1u<<10u);memcpy(image_bytes.data()+owner+i-4,&shifted,4);
        Layout shifted_id{};assert(Decode(resolution,&shifted_id));assert(shifted_id.layout_id_pool==decoded.layout_id_pool+8);
        memcpy(image_bytes.data()+owner+i-4,&load,4);moved_id=true;
    }
    assert(moved_id);
    const auto refresh=resolved.sites[6].offset;
    uint32_t slot_word=0; assert(dart::ReadInsn(image,refresh+16,&slot_word));
    const auto shifted_slot=slot_word+(1u<<10u);
    memcpy(image_bytes.data()+refresh+16,&shifted_slot,4);
    Layout shifted_slots{}; assert(Decode(resolution,&shifted_slots));
    assert(shifted_slots.model_slot==decoded.model_slot+8);
    memcpy(image_bytes.data()+refresh+16,&slot_word,4);
    // A correct frame wrapper calling an unrelated body must be rejected.
    const auto frame_call=decoded.frame_return-4;
    uint32_t frame_bl=0; assert(dart::ReadInsn(image,frame_call,&frame_bl));
    const auto wrong_bl=frame_bl+1; memcpy(image_bytes.data()+frame_call,&wrong_bl,4);
    Layout wrong_scheduler{}; assert(!Decode(resolution,&wrong_scheduler));
    memcpy(image_bytes.data()+frame_call,&frame_bl,4);
    // Allocation-class relocation is decoded from the current image, never from a CID table.
    uint32_t call=0, allocatorWord=0; uintptr_t allocation=0;
    const auto factory=resolved.sites[2].offset;
    // A compiler may exchange the two argument spill slots in every branch.
    // Aliasing them, reading an unsaved slot or changing only one branch must
    // still fail even though the instruction masks admit displacement changes.
    std::vector<std::pair<uintptr_t,uint32_t>> arguments;
    for (size_t i=0;i+8<=dart::kFolderStackTarget.sites[2].pattern.size;i+=4) {
        uint32_t a=0,b=0;assert(dart::ReadInsn(image,factory+i,&a));
        assert(dart::ReadInsn(image,factory+i+4,&b));
        if ((a&0xffe00fffu)!=0xf84003a3u || (b&0xffe00fffu)!=0xf84003a5u) continue;
        arguments.push_back({factory+i,a});arguments.push_back({factory+i+4,b});
    }
    assert(arguments.size()>=4);
    const auto replace_displacement=[](uint32_t word,uint32_t other) {
        return (word&0xffe00fffu)|(other&0x001ff000u);
    };
    for (size_t i=0;i<arguments.size();i+=2) {
        const auto a=replace_displacement(arguments[i].second,arguments[i+1].second);
        const auto b=replace_displacement(arguments[i+1].second,arguments[i].second);
        memcpy(image_bytes.data()+arguments[i].first,&a,4);
        memcpy(image_bytes.data()+arguments[i+1].first,&b,4);
    }
    Layout exchanged{};assert(Decode(resolution,&exchanged));
    for (const auto& value:arguments) memcpy(image_bytes.data()+value.first,&value.second,4);
    auto bad_argument=replace_displacement(arguments[0].second,arguments[1].second);
    memcpy(image_bytes.data()+arguments[0].first,&bad_argument,4);
    Layout bad_arguments{};assert(!Decode(resolution,&bad_arguments));
    memcpy(image_bytes.data()+arguments[0].first,&arguments[0].second,4);
    // Swapping just one branch preserves distinct slots, but breaks the shared
    // constructor's argument order across the factory.
    auto swapped_second=replace_displacement(arguments[1].second,arguments[0].second);
    memcpy(image_bytes.data()+arguments[0].first,&bad_argument,4);
    memcpy(image_bytes.data()+arguments[1].first,&swapped_second,4);
    assert(!Decode(resolution,&bad_arguments));
    memcpy(image_bytes.data()+arguments[1].first,&arguments[1].second,4);
    bad_argument=replace_displacement(arguments[0].second,0u);
    memcpy(image_bytes.data()+arguments[0].first,&bad_argument,4);
    assert(!Decode(resolution,&bad_arguments));
    memcpy(image_bytes.data()+arguments[0].first,&arguments[0].second,4);
    for (size_t i=0; i+4<dart::kFolderStackTarget.sites[2].pattern.size; i+=4) {
        uintptr_t stub=0; uint32_t lo=0, hi=0;
        assert(dart::ReadInsn(image,factory+i,&call));
        if (!dart::IsBl(call) || !dart::DecodeBlTarget(factory+i,call,&stub) ||
            !dart::ReadInsn(image,stub,&lo) || !dart::ReadInsn(image,stub+4,&hi)) continue;
        if ((lo&0xffe0001fu)==0xd2800002u && (hi&0xffe0001fu)==0xf2a00002u &&
            ((((lo>>5u)&0xffffu)|(((hi>>5u)&0xffffu)<<16u))>>12u)==decoded.strategy) {
            allocation=stub; allocatorWord=lo; break;
        }
    }
    assert(allocation);
    const auto changedWord=allocatorWord^(1u<<20u);
    memcpy(image_bytes.data()+allocation,&changedWord,4);
    Layout moved{}; assert(Decode(resolution,&moved)); assert(moved.strategy!=decoded.strategy);
    memcpy(image_bytes.data()+allocation,&allocatorWord,4);
    // Strategy fields shift with a changed class layout. Decode operands from the new
    // producer/getter together; a one-sided shift is rejected as a broken relationship.
    std::vector<std::pair<uintptr_t,uint32_t>> shiftedWords;
    for (unsigned siteIndex : {0u,1u}) {
        const auto span=dart::FolderStackSiteSpan(image,siteIndex,resolved.sites[siteIndex].offset);
        for (size_t i=0;i<span;i+=4) {
            uint32_t word=0; assert(dart::ReadInsn(image,resolved.sites[siteIndex].offset+i,&word));
            const auto kind=word&0xffe00c00u;
            const auto shape=word&0xffe00fffu;
            const bool strategy_field=siteIndex==0 ? shape==0xb8400002u :
                (kind==0xfc400000u && ((word>>5)&31u)!=29u) || shape==0xf8400040u || shape==0xf8400043u || shape==0xb8400001u;
            if (strategy_field) {
                const uintptr_t at=resolved.sites[siteIndex].offset+i;
                shiftedWords.emplace_back(at,word);
                const auto shifted=word+(8u<<12u); memcpy(image_bytes.data()+at,&shifted,4);
            }
        }
    }
    assert(!shiftedWords.empty());
    auto shiftedTarget=dart::ResolveTarget(image,dart::kFolderStackTarget);
    assert(shiftedTarget.resolved && shiftedTarget.verified);
    Layout shiftedLayout{}; assert(Decode(resolution,&shiftedLayout));
    assert(shiftedLayout.list==decoded.list+8 && shiftedLayout.count==decoded.count+8);
    assert(shiftedLayout.large_w==decoded.large_w+8 && shiftedLayout.small_h==decoded.small_h+8);
    assert(shiftedLayout.x==decoded.x+8 && shiftedLayout.padding_y==decoded.padding_y+8);
    // A consistently shifted list field must still not alias primitive geometry.
    for (const auto& change:shiftedWords) {
        if ((change.second&0xffe00fffu)==0xb8400002u ||
            (change.second&0xffe00fffu)==0xb8400001u) {
            const auto aliased=(change.second&~0x001ff000u) | (static_cast<uint32_t>(shiftedLayout.large_w)<<12u);
            memcpy(image_bytes.data()+change.first,&aliased,4);
        }
    }
    Layout aliased{}; assert(!Decode(resolution,&aliased));
    for (const auto& change:shiftedWords) {
        const auto shifted=change.second+(8u<<12u); memcpy(image_bytes.data()+change.first,&shifted,4);
    }
    for (const auto& change:shiftedWords) {
        if (change.first>=resolved.sites[0].offset && change.first<resolved.sites[0].offset+dart::FolderStackSiteSpan(image,0,resolved.sites[0].offset)) {
            memcpy(image_bytes.data()+change.first,&change.second,4);
            Layout inconsistent{}; assert(!Decode(resolution,&inconsistent));
            const auto shifted=change.second+(8u<<12u); memcpy(image_bytes.data()+change.first,&shifted,4);
        }
    }
    for (const auto& change:shiftedWords) memcpy(image_bytes.data()+change.first,&change.second,4);
    // Relocate every feature function and repair BL targets, removing all old candidates.
    // This exercises real discovery/operand decoding with no original function addresses.
    uintptr_t relocation=0;
    for(size_t i=0;i<image.load_count;++i)
        if(image.loads[i].flags & dart::kFlagExec) relocation=image.loads[i].end-16384;
    std::vector<uint8_t> spareBytes(image_bytes.begin()+relocation,image_bytes.begin()+relocation+8192);
    std::vector<std::vector<uint8_t>> functions;
    uintptr_t relocated[8]{}; size_t used=0;
    for(unsigned i=0;i<8;++i){
        const auto size=dart::FolderStackSiteSpan(image,i,resolved.sites[i].offset);
        const auto at=resolved.sites[i].offset;
        functions.emplace_back(image_bytes.begin()+at,image_bytes.begin()+at+size);
        relocated[i]=relocation+used; used=(used+size+15u)&~size_t{15u};
    }
    for(unsigned i=0;i<8;++i){
        const auto at=resolved.sites[i].offset; auto copy=functions[i];
        for(size_t j=0;j<copy.size();j+=4){
            uint32_t word=0; memcpy(&word,copy.data()+j,4); uintptr_t destination=0;
            if(!dart::IsBl(word) || !dart::DecodeBlTarget(at+j,word,&destination)) continue;
            for(unsigned k=0;k<8;++k)
                if(destination>=resolved.sites[k].offset && destination<resolved.sites[k].offset+functions[k].size())
                    destination=relocated[k]+destination-resolved.sites[k].offset;
            const int64_t delta=static_cast<int64_t>(destination)-static_cast<int64_t>(relocated[i]+j);
            word=0x94000000u | (static_cast<uint32_t>(delta/4)&0x03ffffffu);
            memcpy(copy.data()+j,&word,4);
        }
        memcpy(image_bytes.data()+relocated[i],copy.data(),copy.size());
        memset(image_bytes.data()+at,0,functions[i].size());
    }
    // The controller wrapper remains in place; repair its calls just as a linker
    // does when the strategy getter moves. The decoder must follow the new call.
    std::vector<std::pair<uintptr_t,uint32_t>> movedCallers;
    for(size_t segment=0;segment<image.load_count;++segment) {
        if (!(image.loads[segment].flags & dart::kFlagExec)) continue;
        for(uintptr_t at=image.loads[segment].start;at+4<=image.loads[segment].end;at+=4) {
            uint32_t word=0; uintptr_t destination=0;
            if(!dart::ReadInsn(image,at,&word) || !dart::IsBl(word) || !dart::DecodeBlTarget(at,word,&destination) || destination!=resolved.sites[0].offset) continue;
            movedCallers.emplace_back(at,word);
            const auto delta=static_cast<int64_t>(relocated[0])-static_cast<int64_t>(at);
            word=0x94000000u|(static_cast<uint32_t>(delta/4)&0x03ffffffu); memcpy(image_bytes.data()+at,&word,4);
        }
    }
    auto relocatedTarget=dart::ResolveTarget(image,dart::kFolderStackTarget);
    assert(relocatedTarget.resolved && relocatedTarget.verified);
    for(unsigned i=0;i<8;++i) assert(relocatedTarget.sites[i].offset==relocated[i]);
    Layout relocatedLayout{};
    assert(dart::DecodeFolderStackLayout(image,relocated[0],relocated[1],relocated[2],relocated[3],relocated[4],&relocatedLayout));
    assert(dart::DecodeFolderStackRefresh(image,relocated[5],relocated[6],relocated[7],&relocatedLayout));
    assert(relocatedLayout.frame_return-relocated[5]==decoded.frame_return-resolved.sites[5].offset);
    assert(relocatedLayout.refresh==relocated[6]);
    assert(relocatedLayout.strategy==decoded.strategy && relocatedLayout.item==decoded.item);
    assert(relocatedLayout.patch-relocated[0]==decoded.patch-resolved.sites[0].offset);
    for(unsigned i=0;i<8;++i) memcpy(image_bytes.data()+resolved.sites[i].offset,functions[i].data(),functions[i].size());
    for(const auto& caller:movedCallers) memcpy(image_bytes.data()+caller.first,&caller.second,4);
    memcpy(image_bytes.data()+relocation,spareBytes.data(),spareBytes.size());
    // Corruption at every dependency refuses activation. A duplicate complete getter is ambiguous.
    for (size_t i=0;i<8;++i) {
        const auto at=resolved.sites[i].offset;
        const auto saved=image_bytes[at]; image_bytes[at]^=1;
        assert(!dart::ResolveTarget(image,dart::kFolderStackTarget).resolved);
        image_bytes[at]=saved;
    }
    uintptr_t spare=0;
    for(size_t i=0;i<image.load_count;++i) if(image.loads[i].flags & dart::kFlagExec) spare=image.loads[i].end-4096;
    const auto count=dart::FolderStackSiteSpan(image,0,resolved.sites[0].offset);
    std::vector<uint8_t> old(image_bytes.begin()+spare,image_bytes.begin()+spare+count);
    memcpy(image_bytes.data()+spare,image_bytes.data()+resolved.sites[0].offset,count);
    assert(!dart::ResolveTarget(image,dart::kFolderStackTarget).resolved);
    memcpy(image_bytes.data()+spare,old.data(),count);
    // Only actual value changes request a refresh; equal snapshot replay is idle.
    const auto initial_epoch=g_folder_stack_refresh_epoch;
    SetFolderStackEnabled(false); assert(g_folder_stack_refresh_epoch==initial_epoch);
    SetFolderStackEnabled(true); assert(g_folder_stack_refresh_epoch==initial_epoch+1);
    SetFolderStackEnabled(true); assert(g_folder_stack_refresh_epoch==initial_epoch+1);
    SetFolderStackEnabled(false); assert(g_folder_stack_refresh_epoch==initial_epoch+2);
    prepared=false; SetFolderStackEnabled(true); assert(!ApplyFolderStackRule(nullptr)); assert(!installs);
    prepared=true;
    for(unsigned failure=1;failure<=6;++failure) {
        fail_at=installs+failure;
        assert(!ApplyFolderStackRule(nullptr)); assert(g_folder_stack_ready==0);
        for(const auto& hook:hooks) assert(!hook.target);
        assert(saved_hooks.empty());
    }
    fail_at=0;
    for(unsigned failure=1;failure<=6;++failure) {
        null_backup_at=installs+failure;
        assert(!ApplyFolderStackRule(nullptr)); assert(g_folder_stack_ready==0 && saved_hooks.empty());
        for(const auto& hook:hooks) assert(!hook.target);
    }
    null_backup_at=0; const auto before=installs; assert(ApplyFolderStackRule(nullptr)); assert(installs==before+6);
    assert(ApplyFolderStackRule(nullptr)); assert(installs==before+6);
    ResetFolderStackAfterFork(); assert(g_folder_stack_ready==0);
    assert(ApplyFolderStackRule(nullptr)); assert(installs==before+6 && g_folder_stack_ready==1);
    const auto old_removes=removes;
    SetFolderStackEnabled(false); assert(ApplyFolderStackRule(nullptr)); assert(removes==old_removes);
    SetFolderStackEnabled(true); assert(ApplyFolderStackRule(nullptr)); assert(installs==before+6);
    // Existing heap metadata can be enabled/disabled repeatedly without retaining a pointer
    // or accumulating transformations; the first two apps and native list order stay intact.
    std::vector<uint8_t> arena(4096);
    const uintptr_t root=reinterpret_cast<uintptr_t>(arena.data()), heap=root>>32;
    auto obj=[&](size_t off,uint32_t cid) { const uintptr_t p=root+off+1; const uint32_t tag=cid<<12; memcpy(reinterpret_cast<void*>(p-1),&tag,4); return p; };
    auto put=[&](uintptr_t p,size_t off,auto value) { memcpy(reinterpret_cast<void*>(p+off),&value,sizeof(value)); };
    auto ref=[&](uintptr_t p,size_t off,uintptr_t value) { put(p,off,static_cast<uint32_t>(value)); };
    const auto null=obj(0,170), strategy=obj(128,layout.strategy), list=obj(384,100), data=obj(512,101);
    ref(strategy,0x4b,list); ref(list,0xf,data); put(list,0xb,int32_t{12}); put(data,0xb,int32_t{16});
    put(strategy,7,int64_t{6});
    for(auto pair : {std::pair<size_t,double>{0x4f,100},{0x57,100},{0x5f,45},{0x67,45},
        {0x37,10},{0x6f,0},{0x2f,10},{0x77,0},{0x7f,10},{0x8f,10}}) put(strategy,pair.first,pair.second);
    uintptr_t items[6]; Rect stock[6]={{100,100,10,10},{100,100,120,10},
        {45,45,230,10},{45,45,285,10},{45,45,230,65},{45,45,285,65}};
    for(unsigned i=0;i<6;++i){ items[i]=obj(768+i*64,layout.item); ref(data,0xf+i*4,items[i]); put(items[i],7,stock[i]); }
    auto update=[&]{HyperTweakUpdateFolderStack(strategy,heap,null);};
    // The stack is derived from the native preview rectangle, so the expectation is too.
    const double areaW=100, areaH=100;
    auto expected=[&](unsigned i){ const double k=4.0-i;
        return i<5 ? Rect{areaW*kStackCard,areaH*kStackCard,230+areaW*kStackStep*k,10+areaH*kStackStep*k} : stock[i]; };
    for(int cycle=0;cycle<3;++cycle){
        SetFolderStackEnabled(true); update(); update();
        for(unsigned i=0;i<6;++i){ auto got=Read<Rect>(items[i],7);
            if(i<2) assert(Same(got,stock[i])); else {
                assert(Same(got,expected(i)));
                // A visible sliver, not a full card and not a sub-pixel offset.
                assert(kStackStep/kStackCard > 0.1 && kStackStep/kStackCard < 0.2);
                assert(got.x>=230 && got.x+got.w<=330 && got.y>=10 && got.y+got.h<=110);
            }
        }
        SetFolderStackEnabled(false); update();
        for(unsigned i=0;i<6;++i) assert(Same(Read<Rect>(items[i],7),stock[i]));
    }
    SetFolderStackEnabled(true);
    put(strategy,7,int64_t{3}); update(); assert(Same(Read<Rect>(items[2],7),stock[2]));
    put(strategy,7,int64_t{6});
    put(strategy,0x4b,uint32_t{0}); update(); assert(Same(Read<Rect>(items[2],7),stock[2])); ref(strategy,0x4b,list);
    put(items[2],7,Rect{44,45,230,10}); update(); assert(Read<Rect>(items[2],7).w==44); put(items[2],7,stock[2]);
    put(strategy,-1,static_cast<uint32_t>(layout.item<<12)); update(); assert(Same(Read<Rect>(items[2],7),stock[2]));
    put(strategy,-1,static_cast<uint32_t>(layout.strategy<<12));
    // Fresh host after upgrade: no prior native feature state is required.
    update(); assert(Same(Read<Rect>(items[2],7),expected(2)));
    SetFolderStackEnabled(false); update();
    // Execute the production callback with the shifted layout, not only its decoder.
    const auto currentLayout=layout; layout=shiftedLayout;
    const auto futureStrategy=obj(1536,layout.strategy);
    ref(futureStrategy,layout.list,list); put(futureStrategy,layout.count,int64_t{6});
    for(auto pair : {std::pair<size_t,double>{layout.large_w,100},{layout.large_h,100},
        {layout.small_w,45},{layout.small_h,45},{layout.x,10},{layout.padding_x,0},
        {layout.y,10},{layout.padding_y,0},{layout.gap_x,10},{layout.small_gap,10}})
        put(futureStrategy,pair.first,pair.second);
    SetFolderStackEnabled(true); HyperTweakUpdateFolderStack(futureStrategy,heap,null);
    assert(Same(Read<Rect>(items[2],7),expected(2)));
    SetFolderStackEnabled(false); HyperTweakUpdateFolderStack(futureStrategy,heap,null);
    for(unsigned i=0;i<6;++i) assert(Same(Read<Rect>(items[i],7),stock[i]));
    layout=currentLayout;
    const auto closure=obj(2560,100), context=obj(2688,101), parent=obj(2816,102), controller=obj(2048,103);
    ref(closure,0x17,context); ref(context,0xb,parent); ref(parent,0x13,controller);
    ref(controller,layout.controller_strategy,strategy);
    SetFolderStackEnabled(true);
    pthread_mutex_lock(&lock);
    assert(HyperTweakFolderStackCount(closure,12,heap,null)==6);
    assert(HyperTweakFolderStackIndex(closure,2,5,heap,null)==4);
    assert(!HyperTweakUpdateFolderStack(strategy,heap,null));
    pthread_mutex_unlock(&lock);
    assert(HyperTweakFolderStackCount(closure,12,heap,null)==5);
    const int64_t order[]={0,2,8,6,4};
    for(int64_t i=0;i<5;++i) assert(HyperTweakFolderStackIndex(closure,i,5,heap,null)==order[i]);
    assert(HyperTweakFolderStackCount(closure,8,heap,null)==4);
    assert(HyperTweakFolderStackIndex(closure,2,4,heap,null)==6);
    assert(HyperTweakFolderStackIndex(closure,3,4,heap,null)==4);
    // Count clamp changes only the fresh Widget output. Model/list contents are untouched.
    assert(Read<int32_t>(list,0xb)==12);
    for(unsigned i=0;i<6;++i) assert(Field(data,0xf+i*4,heap)==items[i]);
    assert(Same(Read<Rect>(items[5],7),stock[5]));
    SetFolderStackEnabled(false);
    assert(HyperTweakFolderStackCount(closure,12,heap,null)==6);
    for(int64_t i=0;i<6;++i) assert(HyperTweakFolderStackIndex(closure,i,6,heap,null)==i*2);
    update();
    // Recover both earlier prototype geometries without old feature state or heap roots.
    for(const auto ratios:{std::pair<double,double>{0.55,0.15},{0.82,0.06}}) {
        for(unsigned i=0;i<4;++i) put(items[i+2],7,Rect{100*ratios.first,100*ratios.first,230+100*ratios.second*i,10+100*ratios.second*i});
        SetFolderStackEnabled(true); update();
        for(unsigned i=2;i<6;++i) assert(Same(Read<Rect>(items[i],7),expected(i)));
        SetFolderStackEnabled(false); update();
        for(unsigned i=0;i<6;++i) assert(Same(Read<Rect>(items[i],7),stock[i]));
    }
    // Same production callbacks over every decoded family member and several
    // actual native grids: horizontal, vertical, 2x2/3x3 and wide 6x3.
    const auto exercise_grid=[&](uint32_t cid,size_t columns,size_t large,size_t visible) {
        std::vector<uint8_t> memory(8192);const auto b=reinterpret_cast<uintptr_t>(memory.data()),h=b>>32;
        const auto make=[&](size_t at,uint32_t kind) { const auto p=b+at+1;const auto tag=kind<<12;memcpy(reinterpret_cast<void*>(p-1),&tag,4);return p; };
        const auto store=[&](uintptr_t p,size_t at,auto value) {memcpy(reinterpret_cast<void*>(p+at),&value,sizeof(value));};
        const auto link=[&](uintptr_t p,size_t at,uintptr_t value) {store(p,at,static_cast<uint32_t>(value));};
        const auto nil=make(0,170),s=make(128,cid),l=make(384,100),a=make(512,101);
        const size_t length=large+4;
        link(s,layout.list,l);link(l,0xf,a);store(l,0xb,static_cast<int32_t>(length*2));store(a,0xb,static_cast<int32_t>(length*2));
        store(s,layout.count,static_cast<int64_t>(visible));
        for(auto pair:{std::pair<size_t,double>{layout.large_w,100},{layout.large_h,100},{layout.small_w,45},{layout.small_h,45},
            {layout.x,10},{layout.y,20},{layout.padding_x,0},{layout.padding_y,0},{layout.gap_x,10},{layout.gap_y,15},{layout.small_gap,10}})store(s,pair.first,pair.second);
        uintptr_t refs[32]{};Rect originals[32]{};
        const double tail_x=10+(large%columns)*110,tail_y=20+(large/columns)*115;
        for(size_t i=0;i<length;++i) {
            refs[i]=make(768+i*64,layout.item);link(a,0xf+i*4,refs[i]);
            originals[i]=i<large ? Rect{100,100,10+(i%columns)*110.0,20+(i/columns)*115.0} :
                Rect{45,45,tail_x+((i-large)%2)*55,tail_y+((i-large)/2)*55};
            store(refs[i],7,originals[i]);
        }
        const auto c=make(4096,100),ctx=make(4224,100),parent=make(4352,100),owner=make(4480,100);
        link(c,0x17,ctx);link(ctx,0xb,parent);link(parent,0x13,owner);link(owner,layout.controller_strategy,s);
        SetFolderStackEnabled(true);
        assert(HyperTweakUpdateFolderStack(s,h,nil));
        assert(HyperTweakFolderStackCount(c,static_cast<int64_t>(visible*2),h,nil)==static_cast<int64_t>(visible>large+3 ? large+3 : visible));
        const size_t output=visible>large+3 ? large+3 : visible;
        for(size_t i=0;i<output;++i) assert(HyperTweakFolderStackIndex(c,i,output,h,nil)==static_cast<int64_t>((i<large ? i : large+output-1-i)*2));
        for(size_t i=0;i<large;++i) assert(Same(Read<Rect>(refs[i],7),originals[i]));
        for(size_t j=0;j<3;++j) assert(Same(Read<Rect>(refs[large+j],7),Rect{80,80,tail_x+(2-j)*10,tail_y+(2-j)*10}));
        assert(Same(Read<Rect>(refs[large+3],7),originals[large+3]));
        assert(Read<int32_t>(l,0xb)==static_cast<int32_t>(length*2));
        // Reject a foreign large-icon layout before writing any small rectangle.
        store(refs[0],7,Rect{99,100,10,20});SetFolderStackEnabled(false);
        assert(!HyperTweakUpdateFolderStack(s,h,nil));store(refs[0],7,originals[0]);
        assert(HyperTweakUpdateFolderStack(s,h,nil));
        assert(HyperTweakFolderStackCount(c,visible*2,h,nil)==static_cast<int64_t>(visible));
        for(size_t i=0;i<length;++i) assert(Same(Read<Rect>(refs[i],7),originals[i]));
        // Lone small-cell app remains the vendor's full-size special case.
        store(s,layout.count,static_cast<int64_t>(large+1));SetFolderStackEnabled(true);
        assert(!HyperTweakUpdateFolderStack(s,h,nil));
    };
    assert(layout.strategy_count>=2);
    for(unsigned i=0;i<layout.strategy_count;++i) {
        exercise_grid(layout.strategies[i],3,2,6);
        exercise_grid(layout.strategies[i],1,2,4);
        exercise_grid(layout.strategies[i],2,3,7);
        exercise_grid(layout.strategies[i],3,8,10);
        exercise_grid(layout.strategies[i],6,17,21);
    }
    const auto old_installs=installs, old_removed=removes;
    memcpy(hooks[0].target,hooks[0].original,16); SetFolderStackEnabled(true);
    assert(ApplyFolderStackRule(nullptr)); assert(removes==old_removed+6 && installs==old_installs+6);
    memset(hooks[2].target,0x77,16); assert(!ApplyFolderStackRule(nullptr));
    assert(!strcmp(FolderStackRuleReason(),"foreign_patch_detected")); assert(g_folder_stack_ready==0);
    puts("folder stack: relocated discovery, shifted fields/CIDs, cache evidence, corruption/ambiguity, geometry, restore, upgrade, refresh generations, scaled singleton slots, scheduler relationship and ownership passed");
}
