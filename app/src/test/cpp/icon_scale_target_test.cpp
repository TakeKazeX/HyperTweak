// SPDX-License-Identifier: Apache-2.0
#include "dart_targets.h"
#include <cassert>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <vector>
#include <cstring>
using namespace hypertweak::native::dart;
namespace fixture {
using namespace hypertweak::native::dart;
#include "../../main/cpp/NativeRules/rules/icon_scale_patterns.inc"
}

int main(int argc,char** argv) {
    assert(argc==2 || (argc==3 && strcmp(argv[2],"--inspect")==0));
    std::ifstream input(argv[1],std::ios::binary);
    std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(input)),{});
    Image image{}; assert(ParseImage(bytes.data(),&image));
    auto target=ResolveTarget(image,kIconScaleTarget);
    uintptr_t sites[3]{};
    for(unsigned i=0;i<3;++i) {
        std::printf("%s found=%d verified=%d candidates=%u offset=%zx\n",target.sites[i].name,target.sites[i].found,target.sites[i].verified,target.sites[i].candidates,target.sites[i].offset);
        sites[i]=target.sites[i].offset;
    }
    if(argc==3 && (!target.resolved || !target.verified)) { puts("unsupported image rejected without patching"); return 0; }
    assert(target.resolved && target.verified);
    IconScaleLayout layout{}; assert(DecodeIconScaleLayout(image,sites,&layout));
    if(argc==2) {
        // Metamorphic inputs exercise operand adaptation, not another build table.
        auto find=[&](const BytePattern& pattern){uintptr_t at=0;uint32_t count=0;assert(FindUniqueBytes(image,pattern,&at,&count));return at;};
        auto call=[&](uintptr_t at){uint32_t word=0;memcpy(&word,bytes.data()+at,4);uintptr_t target=0;assert(IsBl(word)&&DecodeBlTarget(at,word,&target));return target;};
        const auto notify=call(sites[1]+76),active=call(notify+16),grid=call(active+96),description=find(fixture::kIconActualDescription),
            produce=find(fixture::kIconActualProduce),calc=find(fixture::kIconSizeCalc);
        std::vector<std::pair<uintptr_t,uint32_t>> changes;
        auto patch=[&](uintptr_t at,uint32_t mask,uint32_t value){uint32_t old=0;memcpy(&old,bytes.data()+at,4);changes.push_back({at,old});auto word=(old&~mask)|value;memcpy(bytes.data()+at,&word,4);};
        auto field=[&](uintptr_t at,uint32_t value){patch(at,0x1ffu<<12,value<<12);};
        const auto expected=layout;
        for(auto at:{sites[1]+20,layout.scope_begin+60,layout.scope_begin+148,layout.scope_begin+352,calc+20})field(at,0x33);
        field(sites[2]+60,0x23);field(grid+76,0x3b);
        for(auto at:{active+100,notify+228})field(at,0x7b);
        for(auto at:{active+108,notify+236,description+488,produce+48})field(at,0x4f);
        for(auto at:{notify+548,calc+12})field(at,0x3b);
        for(auto at:{description+200,produce+24})field(at,0x37);
        for(auto at:{description+296,produce+32})field(at,0x3f);
        for(auto at:{active+20,grid+16,notify+44,notify+128})patch(at,0xfffu<<10,((expected.controller_slot+16)/8)<<10);
        const auto header=((expected.double_cid+1)<<12)|0x15cu;
        for(auto at:{sites[1]+52,layout.scope_begin+384}) {
            patch(at,0xffffu<<5,(header&0xffffu)<<5);patch(at+4,0xffffu<<5,(header>>16u)<<5);
        }
        // A late-error object-pool operand is irrelevant to the host field contract.
        patch(sites[2]+92,0xfffu<<10,2u<<10);
        assert(DecodeIconScaleLayout(image,sites,&layout));
        assert(layout.current==0x33&&layout.rx_value==0x23&&layout.controller_slot==expected.controller_slot+16);
        assert(layout.actual_grid_icon==0x7b&&layout.actual_provider==0x4f&&layout.actual_size==0x37&&layout.actual_padding==0x3f&&layout.double_cid==expected.double_cid+1);
        field(produce+24,0x47);assert(!DecodeIconScaleLayout(image,sites,&layout));
        for(auto it=changes.rbegin();it!=changes.rend();++it)memcpy(bytes.data()+it->first,&it->second,4);
        assert(DecodeIconScaleLayout(image,sites,&layout));
        puts("operand adaptation: static slots, pool operands, Rx/geometry fields and CID changed together; inconsistent producer rejected");
    }
    if(argc==2) {
        // Masked calls must still satisfy their actual target relationships.
        uint32_t original=0; memcpy(&original,bytes.data()+sites[1]+76,4);
        uint32_t wrong=0x94000000u; memcpy(bytes.data()+sites[1]+76,&wrong,4);
        assert(!DecodeIconScaleLayout(image,sites,&layout)); memcpy(bytes.data()+sites[1]+76,&original,4);
        assert(DecodeIconScaleLayout(image,sites,&layout));
        // Missing and duplicate complete patterns must never pick an arbitrary site.
        uintptr_t spare=0;
        for(size_t i=0;i<image.load_count;++i) if(image.loads[i].flags&kFlagExec) spare=image.loads[i].end-8192;
        // The secondary initializer target owns exactly one verified window.
        std::vector<uint8_t> scope_code(bytes.begin()+layout.scope_begin,bytes.begin()+layout.scope_begin+412);
        memset(bytes.data()+layout.scope_clamp,0xa5,16);
        assert(!DecodeIconScaleLayout(image,sites,&layout));
        assert(DecodeIconScaleLayout(image,sites,&layout,nullptr,scope_code.data()));
        scope_code[312]^=1;
        assert(!DecodeIconScaleLayout(image,sites,&layout,nullptr,scope_code.data()));
        scope_code[312]^=1;
        bytes[layout.scope_begin+4]^=1;
        assert(!DecodeIconScaleLayout(image,sites,&layout,nullptr,scope_code.data()));
        bytes[layout.scope_begin+4]^=1;
        memcpy(bytes.data()+layout.scope_clamp,scope_code.data()+312,16);
        std::vector<uint8_t> old_scope(bytes.begin()+spare,bytes.begin()+spare+scope_code.size());
        memcpy(bytes.data()+spare,scope_code.data(),scope_code.size());
        assert(!DecodeIconScaleLayout(image,sites,&layout));
        memcpy(bytes.data()+spare,old_scope.data(),old_scope.size());
        assert(DecodeIconScaleLayout(image,sites,&layout));
        for(unsigned i=0;i<3;++i) {
            const auto saved=bytes[sites[i]]; bytes[sites[i]]^=1;
            assert(!ResolveTarget(image,kIconScaleTarget).resolved); bytes[sites[i]]=saved;
            const auto size=kIconScaleTarget.sites[i].verify.size;
            std::vector<uint8_t> old(bytes.begin()+spare,bytes.begin()+spare+size);
            memcpy(bytes.data()+spare,bytes.data()+sites[i],size);
            assert(!ResolveTarget(image,kIconScaleTarget).resolved);
            memcpy(bytes.data()+spare,old.data(),old.size());
        }
        // Relocate whole scheduler/provider functions and repair PC-relative BLs.
        for(unsigned index : {0u,1u}) {
            const auto from=sites[index], size=kIconScaleTarget.sites[index].verify.size;
            std::vector<uint8_t> old(bytes.begin()+spare,bytes.begin()+spare+size);
            std::vector<uint8_t> source(bytes.begin()+from,bytes.begin()+from+size);
            memcpy(bytes.data()+spare,source.data(),size);
            for(size_t i=0;i<size;i+=4) {
                uint32_t word=0; memcpy(&word,source.data()+i,4); uintptr_t destination=0;
                if(!IsBl(word) || !DecodeBlTarget(from+i,word,&destination)) continue;
                if(destination>=from && destination<from+size) destination=spare+destination-from;
                word=0x94000000u | (static_cast<uint32_t>((static_cast<int64_t>(destination)-static_cast<int64_t>(spare+i))/4)&0x03ffffffu);
                memcpy(bytes.data()+spare+i,&word,4);
            }
            bytes[from]^=1;
            auto moved=ResolveTarget(image,kIconScaleTarget); assert(moved.resolved && moved.verified);
            uintptr_t moved_sites[3]{}; for(unsigned i=0;i<3;++i) moved_sites[i]=moved.sites[i].offset;
            assert(DecodeIconScaleLayout(image,moved_sites,&layout));
            memcpy(bytes.data()+from,source.data(),size); memcpy(bytes.data()+spare,old.data(),size);
        }
        assert(DecodeIconScaleLayout(image,sites,&layout));
    }
    std::printf("controller=%x current=%x value=%x cid=%x size=%x padding=%x returns=%zx,%zx\n",
        layout.controller_slot,layout.current,layout.rx_value,layout.double_cid,layout.actual_size,layout.actual_padding,layout.frame_returns[0],layout.frame_returns[1]);
}
