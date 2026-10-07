// Included inside hypertweak::native::dart. No build IDs, names or fixed RVAs.
namespace {
int DockDisplacement(uint32_t insn) { return static_cast<int32_t>(insn << 11u) >> 23u; }
bool DockLoad(uint32_t insn, uint32_t shape) { return (insn & 0xffe00fffu) == shape; }
using DockLayout = DockFrameLayout;
bool ReadDockLayout(const Image& image, uintptr_t tail, DockLayout* out) {
    uint32_t words[10]{};
    if (!ReadInsns(image, tail - 24u, words, 10u) || !DockLoad(words[0], 0xfc4003a0u) ||
        words[1] != 0xfc00f000u || words[2] != 0x9100c2c1u || words[3] != 0xb8017001u ||
        words[4] != 0xb801b001u || !DockLoad(words[5], 0xf84003a1u) ||
        words[6] != 0xb800b001u || words[7] != 0xaa1d03efu ||
        words[8] != 0xa8c179fdu || words[9] != 0xd65f03c0u) return false;
    DockLayout result{}; result.visible = tail; result.alpha = DockDisplacement(words[0]);
    uintptr_t begin = tail;
    for (size_t n = 0; n < 256u && begin >= 4u; ++n) {
        begin -= 4u; uint32_t p[2]{};
        if (!ReadInsns(image, begin, p, 2u)) return false;
        if (p[0] == 0xa9bf79fdu && p[1] == 0xaa0f03fdu) break;
    }
    uint32_t prologue[3]{};
    if (!ReadInsns(image, begin, prologue, 3u) || prologue[0] != 0xa9bf79fdu || prologue[1] != 0xaa0f03fdu ||
        (prologue[2] & 0xffc003ffu) != 0xd10001efu) return false;
    const int frame_size = static_cast<int>((prologue[2] >> 10u) & 0xfffu);
    result.begin = begin;
    size_t hidden = 0, fp16 = 0, fp3 = 0, offsets = 0, doubles = 0, zero_origin = 0;
    for (uintptr_t pc = begin; pc < tail; pc += 4u) {
        uint32_t w[4]{}; if (!ReadInsns(image, pc, w, 4u)) return false;
        if ((w[0] & 0xffc003ffu) == 0xf9400360u && w[1] == 0xaa1d03efu && w[2] == 0xa8c179fdu && w[3] == 0xd65f03c0u) {
            if (hidden >= 2u) return false;
            result.hidden[hidden++] = pc;
            const uint32_t pool = ((w[0] >> 10u) & 0xfffu) * 8u;
            if (result.pool != 0u && result.pool != pool) return false;
            result.pool = pool;
        }
        if (DockLoad(w[0], 0xf84003b0u)) {
            if (fp16 == 0u) result.scale = DockDisplacement(w[0]);
            else if (fp16 == 1u) result.origin = DockDisplacement(w[0]);
            ++fp16;
        }
        if (DockLoad(w[0], 0xf84003a3u)) { result.offset = DockDisplacement(w[0]); ++fp3; }
        if (w[0] == 0xfc007000u && w[1] != 0u) ++offsets;
        if ((w[0] & 0xffc003ffu) == 0xf100009fu) {
            const uint32_t cid=(w[0]>>10u)&0xfffu;
            if(cid==0 || (result.double_cid && result.double_cid!=cid))return false;
            result.double_cid=cid; ++doubles;
        } // Repeated Double ABI checks derive the boxed scalar CID.
        if (w[0] == 0xf800701fu) ++zero_origin;
    }
    if (hidden != 2u || fp16 != 2u || fp3 != 1u || offsets < 1u || doubles != 3u || zero_origin != 1u ||
        result.alpha >= 0 || result.offset >= 0 || result.scale >= 0 || result.origin >= 0) return false;
    const int slots[] = {result.alpha, result.offset, result.scale, result.origin};
    for (size_t i = 0; i < 4; ++i) {
        if (slots[i] < -frame_size || (slots[i] & 7) != 0) return false;
        for (size_t j = 0; j < i; ++j) if (slots[j] == slots[i]) return false;
    }
    *out = result; return true;
}
bool FindDockVisible(const Image& image, uintptr_t* offset, uint32_t* count) {
    *count = 0u; uintptr_t match = 0;
    for (size_t s = 0; s < image.load_count; ++s) {
        const auto& seg = image.loads[s]; if ((seg.flags & kFlagExec) == 0u) continue;
        for (uintptr_t pc = seg.start + 24u; pc + 16u <= seg.end; pc += 4u) {
            uint32_t instruction = 0; if (!ReadInsn(image, pc, &instruction) || instruction != 0xb800b001u) continue;
            DockLayout layout{}; if (!ReadDockLayout(image, pc, &layout)) continue;
            match = pc; ++*count;
        }
    }
    if (*count != 1u) return false; *offset = match; return true;
}

// Native animation controller: the scale listener publishes the same boxed
// scalar through two adjacent Rx fields. The alpha listener shares that setter,
// reads back that scale and calls the same synchronization consumer as setTo.
bool DockWords(const Image& image, uintptr_t pc, const uint32_t* pattern, size_t size) {
    for(size_t n=0;n<size;++n){uint32_t w=0;if(!ReadInsn(image,pc+4*n,&w)||(pattern[n]&&pattern[n]!=w))return false;}
    return true;
}
struct DockController { uintptr_t scale, alpha, sync; int rx_value; uintptr_t setter; };
bool ReadDockController(const Image& image,uintptr_t begin,DockController* out) {
    uint32_t w[20]{};if(!ReadInsns(image,begin,w,20))return false;
    constexpr uint32_t shape[]={0xa9bf79fd,0xaa0f03fd,0xd10041ef,0xaa0103e3,0xaa0203e0,
        0xf81f83a1,0xf81f03a2,0,0x8b1c8021,0xaa0003e2,0,0xf85f83a0,0,
        0x8b1c8021,0xf85f03a2,0,0xaa1603e0,0xaa1d03ef,0xa8c179fd,0xd65f03c0};
    if(!DockWords(image,begin,shape,20)||!DockLoad(w[7],0xb8400061)||!DockLoad(w[12],0xb8400001))return false;
    const int sx=DockDisplacement(w[7]),sy=DockDisplacement(w[12]);
    if(sx<7||sy!=sx+4)return false;
    uintptr_t setter=0,setter2=0;
    if(!DecodeBlTarget(begin+40,w[10],&setter)||!DecodeBlTarget(begin+60,w[15],&setter2)||setter!=setter2)return false;
    DockController result{};result.scale=begin+64;result.setter=setter;
    // Match the sibling alpha consumer by its full context and scalar dataflow.
    unsigned alphas=0;
    for(uintptr_t pc=begin+80;pc<begin+256;pc+=4){uint32_t q[16]{};if(!ReadInsns(image,pc,q,16))return false;
        constexpr uint32_t alphaPrefix[]={0xa9bf79fd,0xaa0f03fd,0xd100a1ef,0xaa0103e3,0xaa0203e0,
            0xf81f83a1,0xf81f03a2,0,0x8b1c8021,0xaa0003e2,0,0xf85f03a0,
            0xfc407000,0xfc1e83a0,0x6e211c21,0x1e602020};
        uintptr_t call=0;
        if(!DockWords(image,pc,alphaPrefix,16)||!DockLoad(q[7],0xb8400061)||DockDisplacement(q[7])!=sx-4||
            !DecodeBlTarget(pc+40,q[10],&call)||call!=setter)continue;
        for(uintptr_t tail=pc+64;tail<pc+512;tail+=4){uint32_t t[12]{};if(!ReadInsns(image,tail-32,t,12))return false;
            if(t[0]!=0xf85f83a0||!DockLoad(t[1],0xb8400001)||DockDisplacement(t[1])!=sx||t[2]!=0x8b1c8021||
                t[4]!=0xfc407001||t[5]!=0xf85f83a1||t[6]!=0xfc5e83a0||
                t[8]!=0xaa1603e0||t[9]!=0xaa1d03ef||t[10]!=0xa8c179fd||t[11]!=0xd65f03c0)continue;
            if(!DecodeBlTarget(tail-4,t[7],&result.sync))continue;
            uintptr_t getter=0;
            if(!DecodeBlTarget(tail-20,t[3],&getter))continue;
            unsigned values=0;
            for(uintptr_t p=getter;p<getter+128;p+=4){uint32_t g[8]{};if(!ReadInsns(image,p,g,8))return false;
                if(DockLoad(g[0],0xb8400020)&&g[1]==0x8b1c8000&&g[3]==0x6b10001f&&
                   g[5]==0xaa1d03ef&&g[6]==0xa8c179fd&&g[7]==0xd65f03c0){result.rx_value=DockDisplacement(g[0]);++values;}}
            if(values!=1 || result.rx_value<7)continue;
            result.alpha=tail;++alphas;
        }
    }
    if(alphas!=1)return false;
    uint32_t sync[6]{};
    if(!ReadInsns(image,result.sync,sync,6)||sync[0]!=0xa9bf79fd||sync[1]!=0xaa0f03fd||
        !((sync[3]==0xf81f83a1 && DockLoad(sync[4],0xfc0003a0) && DockLoad(sync[5],0xfc0003a1)) ||
          (DockLoad(sync[3],0xfc0003a0) && DockLoad(sync[4],0xfc0003a1) && sync[5]==0xf9403f40)))return false;
    // Match the common consumer even when the optimizer omits its unused receiver.
    *out=result;return true;
}
bool FindController(const Image& image,int site,uintptr_t* offset,uint32_t* count){
    *count=0;
    for(size_t n=0;n<image.load_count;++n){const auto& seg=image.loads[n];if(!(seg.flags&kFlagExec))continue;
        for(uintptr_t pc=seg.start;pc+768<seg.end;pc+=4){uint32_t first=0;
            if(!ReadInsn(image,pc,&first)||first!=0xa9bf79fd)continue;
            DockController c{};if(!ReadDockController(image,pc,&c))continue;
            *offset=site==0?c.scale:site==1?c.alpha:c.sync;++*count;
        }
    }
    return *count==1;
}
bool FindControllerScale(const Image&i,uintptr_t*o,uint32_t*c){return FindController(i,0,o,c);}
bool FindControllerAlpha(const Image&i,uintptr_t*o,uint32_t*c){return FindController(i,1,o,c);}
bool FindControllerSet(const Image& image,uintptr_t* offset,uint32_t* count){
    uintptr_t sync=0;uint32_t families=0;if(!FindController(image,2,&sync,&families)){*count=families;return false;}
    *count=0;
    for(size_t n=0;n<image.load_count;++n){const auto& seg=image.loads[n];if(!(seg.flags&kFlagExec))continue;
        for(uintptr_t pc=seg.start+12;pc+16<seg.end;pc+=4){uint32_t w[7]{};
            if(!ReadInsns(image,pc-12,w,7)||w[0]!=0xfc5d03a0||w[1]!=0xfc5d83a1||
                w[3]!=0xaa1603e0||w[4]!=0xaa1d03ef||w[5]!=0xa8c179fd||w[6]!=0xd65f03c0)continue;
            uintptr_t call=0;if(!DecodeBlTarget(pc-4,w[2],&call)||call!=sync)continue;
            *offset=pc;++*count;
        }
    }
    return *count==1;
}
bool ReadDockTick(const Image& image,uintptr_t begin,const DockController& controller,DockTickLayout* out){
    uint32_t w[11]{};if(!ReadInsns(image,begin,w,11))return false;
    constexpr uint32_t prefix[]={0xa9bf79fd,0xaa0f03fd,0xd10061ef,0xaa0103e2,0xf81f03a1,
        0,0xb8716843,0x8b1c8063,0xf81f83a3,0,0xb8716840};
    if(!DockWords(image,begin,prefix,11)||(w[5]&0xffe0001f)!=0xd2800011||(w[9]&0xffe0001f)!=0xd2800011)return false;
    DockTickLayout layout{};layout.receiver=-16;layout.scale=(w[5]>>5)&0xffff;layout.value=controller.rx_value;
    unsigned mid=0,math=0,returns=0,shared=0;
    for(uintptr_t pc=begin+44;pc<begin+320;pc+=4){uint32_t q[9]{};if(!ReadInsns(image,pc,q,9))return false;
        uintptr_t call=0;if(DecodeBlTarget(pc,q[0],&call)&&call==controller.setter)++shared;
        if(q[0]==0xf85f03a0&&(q[1]&0xffe0001f)==0xd2800011&&q[2]==0xb8716802&&q[3]==0x8b1c8042&&
            q[4]==0xf81f83a2&&DockLoad(q[5],0xfc400000)&&q[6]==0x1e7e1001&&q[7]==0x1e610802&&q[8]==0xfc1e83a2){
            layout.y=(q[1]>>5)&0xffff;++mid;}
        if(q[0]==0xfc407000&&q[1]==0x1e6e1001&&q[2]==0x1e603822&&q[3]==0xfc5e83a0&&q[4]==0x1e620801)++math;
        if(q[0]==0xfc007041&&q[1]==0xf85f83a1&&DecodeBlTarget(pc+8,q[2],&call)&&call==controller.setter&&
            q[3]==0xaa1603e0&&q[4]==0xaa1d03ef&&q[5]==0xa8c179fd&&q[6]==0xd65f03c0){layout.site=pc+12;++returns;break;}
    }
    if(mid!=1||math!=1||returns!=1||shared!=1||layout.scale==layout.y||layout.scale<7||layout.y<7)return false;
    *out=layout;return true;
}
bool FindDockTick(const Image& image,uintptr_t* offset,uint32_t* count){
    uintptr_t scale=0;uint32_t families=0;if(!FindController(image,0,&scale,&families)){*count=families;return false;}
    DockController controller{};if(!ReadDockController(image,scale-64,&controller))return false;
    *count=0;
    for(size_t n=0;n<image.load_count;++n){const auto& seg=image.loads[n];if(!(seg.flags&kFlagExec))continue;
        for(uintptr_t pc=seg.start;pc+352<seg.end;pc+=4){uint32_t first=0;if(!ReadInsn(image,pc,&first)||first!=0xa9bf79fd)continue;
            DockTickLayout layout{};if(ReadDockTick(image,pc,controller,&layout)){*offset=layout.site;++*count;}}
    }
    return *count==1;
}
// The edit-state listener captures the incoming enum, writes lastEditState with
// the Dart write barrier, and compares that same captured enum to disabled.
bool ReadEditing(const Image& image,uintptr_t tail,uint32_t* disabledPool) {
    constexpr uint32_t end[]={0xb840f023,0x8b1c8063,0xb840f040,0x8b1c8000,
        0,0x385ff070,0x385ff011,0x8a500a30,0xea5c821f,0,0,
        0xaa1603e0,0xaa1d03ef,0xa8c179fd,0xd65f03c0};
    if(!DockWords(image,tail-44,end,sizeof(end)/4))return false;
    uint32_t store=0;ReadInsn(image,tail-28,&store);
    if(!DockLoad(store,0xb8000060) || DockDisplacement(store)<0x80)return false;
    uintptr_t begin=tail;
    for(unsigned n=0;n<320 && begin>=4;++n){begin-=4;uint32_t w[2]{};
        if(!ReadInsns(image,begin,w,2))return false;
        if(w[0]==0xa9bf79fd&&w[1]==0xaa0f03fd)break;}
    constexpr uint32_t prefix[]={0xa9bf79fd,0xaa0f03fd,0,0xf9400fa0,0xb8417001,0x8b1c8021};
    if(!DockWords(image,begin,prefix,sizeof(prefix)/4))return false;
    unsigned found=0;
    for(uintptr_t pc=begin;pc+32<tail;pc+=4){uint32_t w[8]{};if(!ReadInsns(image,pc,w,8))return false;
        PoolRef ref{};
        if(DockLoad(w[0],0xf84003a2) && w[1]==0xb840f040 && w[2]==0x8b1c8000 &&
           DecodePoolRef(w[3],w[4],&ref)&&ref.base_reg==27&&ref.dest_reg==16&&
           w[5]==0x6b10001f && (w[6]&0xff00001f)==0x54000001) {
            *disabledPool=ref.add_bytes+ref.ldr_offset;++found;
        }
    }
    return found==1;
}
bool FindEditing(const Image& image,uintptr_t* offset,uint32_t* count){
    *count=0;
    for(size_t s=0;s<image.load_count;++s){const auto& seg=image.loads[s];if(!(seg.flags&kFlagExec))continue;
        for(uintptr_t pc=seg.start+44;pc+16<=seg.end;pc+=4){uint32_t w=0;
            if(!ReadInsn(image,pc,&w)||w!=0xaa1603e0)continue;
            uint32_t pool=0;if(ReadEditing(image,pc,&pool)){*offset=pc;++*count;}
        }
    }
    return *count==1;
}
constexpr uint8_t kDockReturn[] = {1,0xb0,0,0xb8,0xef,3,0x1d,0xaa,0xfd,0x79,0xc1,0xa8,0xc0,3,0x5f,0xd6};
constexpr uint8_t kEditReturn[] = {0xe0,3,0x16,0xaa,0xef,3,0x1d,0xaa,0xfd,0x79,0xc1,0xa8,0xc0,3,0x5f,0xd6};
const SiteSpec kDockSites[] = {
 {"hotseat_motion", {nullptr,nullptr,0}, FindDockVisible, true, {kDockReturn,nullptr,sizeof(kDockReturn)}, 0},
 {"controller_scale", {nullptr,nullptr,0}, FindControllerScale, true, {kEditReturn,nullptr,sizeof(kEditReturn)}, 0},
 {"controller_alpha", {nullptr,nullptr,0}, FindControllerAlpha, true, {kEditReturn,nullptr,sizeof(kEditReturn)}, 0},
 {"controller_set", {nullptr,nullptr,0}, FindControllerSet, true, {kEditReturn,nullptr,sizeof(kEditReturn)}, 0},
 {"editing_state", {nullptr,nullptr,0}, FindEditing, true, {kEditReturn,nullptr,sizeof(kEditReturn)}, 0},
 {"hotseat_scale_tick", {nullptr,nullptr,0}, FindDockTick, true, {kEditReturn,nullptr,sizeof(kEditReturn)}, 0}};
}
const TargetSpec kDockFrameTarget = {"dock_frame", "libapp.so", kDockSites, 6};
bool DecodeDockFrameLayout(const Image& image, uintptr_t offset, DockFrameLayout* out) {
    return out != nullptr && ReadDockLayout(image, offset, out);
}

bool DecodeDockEditingPool(const Image& image,uintptr_t offset,uint32_t* out) { return out && ReadEditing(image,offset,out); }

bool DecodeDockTickLayout(const Image& image,uintptr_t site,DockTickLayout* out){
    uintptr_t scale=0;uint32_t count=0;
    if(!out || !FindController(image,0,&scale,&count))return false;
    DockController controller{};if(!ReadDockController(image,scale-64,&controller))return false;
    for(uintptr_t begin=site;begin+352>site && begin>=4;begin-=4){
        DockTickLayout layout{};if(ReadDockTick(image,begin,controller,&layout)&&layout.site==site){*out=layout;return true;}
        if(site-begin>352)break;
    }
    return false;
}
