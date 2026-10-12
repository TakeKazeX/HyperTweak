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

// Observe completed native consumers. Entry instructions are deliberately outside
// the contract: another hook can own a function entry, but these observers only
// read the native caller's saved values immediately before its original return.
bool DockWords(const Image& image, uintptr_t pc, const uint32_t* pattern, size_t size) {
    for (size_t n = 0; n < size; ++n) {
        uint32_t word = 0;
        if (!ReadInsn(image, pc + 4 * n, &word) || (pattern[n] && pattern[n] != word)) return false;
    }
    return true;
}
struct DockController { uintptr_t scale, alpha, sync; int rx_value; uintptr_t setter; };
bool ReadDockController(const Image& image, uintptr_t tail, DockController* out) {
    // The scale consumer saves its receiver/value, writes the same value into
    // adjacent Rx cells through the same setter and returns Dart null.
    uint32_t words[16]{};
    constexpr uint32_t scaleBody[] = {
        0xaa0203e0, 0xf81f83a1, 0xf81f03a2, 0, 0x8b1c8021, 0xaa0003e2, 0,
        0xf85f83a0, 0, 0x8b1c8021, 0xf85f03a2, 0, 0xaa1603e0,
        0xaa1d03ef, 0xa8c179fd, 0xd65f03c0
    };
    const uintptr_t body = tail - 48;
    if (!ReadInsns(image, body, words, 16) || !DockWords(image, body, scaleBody, 16) ||
        !DockLoad(words[3], 0xb8400061) || !DockLoad(words[8], 0xb8400001)) return false;
    const int scaleField = DockDisplacement(words[3]);
    if (scaleField < 7 || DockDisplacement(words[8]) != scaleField + 4) return false;
    uintptr_t setter = 0, secondSetter = 0;
    if (!DecodeBlTarget(body + 24, words[6], &setter) ||
        !DecodeBlTarget(body + 44, words[11], &secondSetter) || setter != secondSetter) return false;
    DockController result{};
    result.scale = tail;
    result.setter = setter;
    unsigned consumers = 0;
    // Sibling alpha consumer: a saved alpha, a native Rx read of this scale,
    // and the shared root-pose synchronization call establish the family.
    for (uintptr_t pc = tail + 32; pc < tail + 208; pc += 4) {
        uint32_t alpha[12]{};
        constexpr uint32_t alphaBody[] = {
            0xaa0203e0, 0xf81f83a1, 0xf81f03a2, 0, 0x8b1c8021, 0xaa0003e2, 0,
            0xf85f03a0, 0xfc407000, 0xfc1e83a0, 0x6e211c21, 0x1e602020
        };
        uintptr_t call = 0;
        if (!ReadInsns(image, pc, alpha, 12)) return false;
        if (!DockWords(image, pc, alphaBody, 12) || !DockLoad(alpha[3], 0xb8400061) ||
            DockDisplacement(alpha[3]) != scaleField - 4 ||
            !DecodeBlTarget(pc + 24, alpha[6], &call) || call != setter) continue;
        for (uintptr_t end = pc + 48; end < pc + 496; end += 4) {
            uint32_t completed[12]{};
            if (!ReadInsns(image, end - 32, completed, 12)) return false;
            if (completed[0] != 0xf85f83a0 || !DockLoad(completed[1], 0xb8400001) ||
                DockDisplacement(completed[1]) != scaleField || completed[2] != 0x8b1c8021 ||
                completed[4] != 0xfc407001 || completed[5] != 0xf85f83a1 ||
                completed[6] != 0xfc5e83a0 || completed[8] != 0xaa1603e0 ||
                completed[9] != 0xaa1d03ef || completed[10] != 0xa8c179fd ||
                completed[11] != 0xd65f03c0) continue;
            uintptr_t getter = 0;
            if (!DecodeBlTarget(end - 4, completed[7], &result.sync) ||
                !DecodeBlTarget(end - 20, completed[3], &getter)) continue;
            unsigned values = 0;
            for (uintptr_t read = getter; read < getter + 128; read += 4) {
                uint32_t value[8]{};
                if (!ReadInsns(image, read, value, 8)) return false;
                if (DockLoad(value[0], 0xb8400020) && value[1] == 0x8b1c8000 &&
                    value[3] == 0x6b10001f && value[5] == 0xaa1d03ef &&
                    value[6] == 0xa8c179fd && value[7] == 0xd65f03c0) {
                    result.rx_value = DockDisplacement(value[0]);
                    ++values;
                }
            }
            if (values != 1 || result.rx_value < 7) continue;
            result.alpha = end;
            ++consumers;
        }
    }
    if (consumers != 1) return false;
    *out = result;
    return true;
}
bool FindController(const Image& image, int site, uintptr_t* offset, uint32_t* count) {
    *count = 0;
    for (size_t n = 0; n < image.load_count; ++n) {
        const auto& segment = image.loads[n];
        if (!(segment.flags & kFlagExec)) continue;
        for (uintptr_t tail = segment.start + 48; tail + 704 < segment.end; tail += 4) {
            uint32_t instruction = 0;
            if (!ReadInsn(image, tail, &instruction) || instruction != 0xaa1603e0) continue;
            DockController controller{};
            if (!ReadDockController(image, tail, &controller)) continue;
            *offset = site == 0 ? controller.scale : site == 1 ? controller.alpha : controller.sync;
            ++*count;
        }
    }
    return *count == 1;
}
bool FindControllerScale(const Image& image, uintptr_t* offset, uint32_t* count) {
    return FindController(image, 0, offset, count);
}
bool FindControllerAlpha(const Image& image, uintptr_t* offset, uint32_t* count) {
    return FindController(image, 1, offset, count);
}
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
    if(!DockWords(image,begin+16,prefix+4,7)||(w[5]&0xffe0001f)!=0xd2800011||(w[9]&0xffe0001f)!=0xd2800011)return false;
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
    DockController controller{};if(!ReadDockController(image,scale,&controller))return false;
    *count=0;
    for(size_t n=0;n<image.load_count;++n){const auto& seg=image.loads[n];if(!(seg.flags&kFlagExec))continue;
        for(uintptr_t pc=seg.start;pc+352<seg.end;pc+=4){uint32_t saved=0;if(!ReadInsn(image,pc+16,&saved)||saved!=0xf81f03a1)continue;
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
    DockController controller{};if(!ReadDockController(image,scale,&controller))return false;
    for(uintptr_t begin=site;begin+352>site && begin>=4;begin-=4){
        DockTickLayout layout{};if(ReadDockTick(image,begin,controller,&layout)&&layout.site==site){*out=layout;return true;}
        if(site-begin>352)break;
    }
    return false;
}
