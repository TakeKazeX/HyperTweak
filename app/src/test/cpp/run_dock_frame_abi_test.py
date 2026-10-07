#!/usr/bin/env python3
"""Execute the production AOT return observers with Unicorn, including their raw write syscall."""
import argparse, pathlib, subprocess, tempfile, struct
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_INTR
from unicorn.arm64_const import *
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('compiler')
args=parser.parse_args()
source=pathlib.Path(__file__).resolve().parents[2]/'main/cpp/NativeRules/dock_frame_hook.S'
with tempfile.TemporaryDirectory(prefix='ht-dock-abi-') as tmp:
    tmp=pathlib.Path(tmp)
    globals=tmp/'globals.S'
    globals.write_text('''.data
.global g_dock_frame_fd, g_dock_frame_enabled, g_dock_frame_tokens, g_dock_frame_sequence, g_dock_frame_offsets, g_dock_frame_pool, g_dock_edit_disabled_pool, g_dock_frame_samples, g_dock_frame_double_cid, g_dock_frame_source_epoch, g_dock_tick_layout
.balign 8
g_dock_frame_fd: .word 3
g_dock_frame_enabled: .word 1
g_dock_frame_tokens: .xword 101, -202
g_dock_frame_sequence: .xword 0
g_dock_frame_offsets: .xword -48, -16, -24, -40
g_dock_frame_pool: .xword 32
g_dock_edit_disabled_pool: .xword 32
g_dock_frame_samples: .zero 448
g_dock_frame_double_cid: .xword 62
g_dock_frame_source_epoch: .word 305419896
.balign 8
g_dock_tick_layout: .xword -16, 271, 279, 19
.section .note.GNU-stack,"",%progbits
''')
    binary=tmp/'observer'
    subprocess.run([args.compiler,'--target=aarch64-linux-android35','-nostdlib','-static',
                    '-Wl,-e,HyperTweakDockVisibleReturn',str(source),str(globals),'-o',str(binary)],check=True)
    with binary.open('rb') as f:
        elf=ELFFile(f)
        symbols={s.name:s['st_value'] for s in elf.get_section_by_name('.symtab').iter_symbols()}
        segments=[(s['p_vaddr'],s['p_memsz'],s.data()) for s in elf.iter_segments() if s['p_type']=='PT_LOAD']
    def run(hidden=False, enabled=True, null_scale=False, kind=0, editing=False, bad_scalar=None, edit_name=None):
        uc=Uc(UC_ARCH_ARM64,UC_MODE_ARM)
        pages=set()
        for addr,size,data in segments:
            for page in range(addr&~4095,(addr+size+4095)&~4095,4096):
                if page not in pages:uc.mem_map(page,4096);pages.add(page)
            uc.mem_write(addr,data)
        for addr,size in [(0x50000000,0x10000),(0x51000000,0x10000),(0x52000000,0x10000),
                          (0x53000000,0x10000),(0x55000000,0x10000),(0x60000000,0x10000),
                          (0x70000000,0x10000),(0x40000000,4096)]:uc.mem_map(addr,size)
        reg=lambda i:UC_ARM64_REG_X0+i if i<29 else (UC_ARM64_REG_X29 if i==29 else UC_ARM64_REG_X30)
        for i in range(31):uc.reg_write(reg(i),0x10101000+i)
        fp=0x60001000; stop=0x40000000; child=0x12345678
        uc.reg_write(UC_ARM64_REG_SP,0x70001000)
        uc.reg_write(UC_ARM64_REG_X29,fp);uc.reg_write(UC_ARM64_REG_X15,fp-80)
        uc.reg_write(UC_ARM64_REG_X0,0x51000001);uc.reg_write(UC_ARM64_REG_X1,child)
        uc.reg_write(UC_ARM64_REG_X22,0x55000001);uc.reg_write(UC_ARM64_REG_X27,0x60004000)
        uc.reg_write(UC_ARM64_REG_NZCV,0xa0000000)
        for i in range(32):uc.reg_write(UC_ARM64_REG_Q0+i,(i+1)*0x10101010101010101010101010101)
        uc.mem_write(fp,struct.pack('<QQ',0x60003000,stop))
        uc.mem_write(fp-48,struct.pack('<d',.6))
        uc.mem_write(fp-16,struct.pack('<Q',0x50000001))
        uc.mem_write(fp-24,struct.pack('<Q',0x55000001 if null_scale else 0x52000001))
        uc.mem_write(fp-40,struct.pack('<Q',0x53000001))
        uc.mem_write(0x50000008,struct.pack('<dd',-125.5,31.25))
        uc.mem_write(0x52000008,struct.pack('<d',.92))
        uc.mem_write(0x53000008,struct.pack('<dd',0,9.5))
        uc.mem_write(0x60004020,struct.pack('<Q',0xabcdef01))
        uc.mem_write(0x51000010,struct.pack('<d',.6))
        uc.mem_write(0x5100001c,struct.pack('<d',.65))
        uc.mem_write(fp+16,struct.pack('<Q',0xabcdef01 if not editing else 0xbcdef001))
        if kind==2:
            name=edit_name or ('normal' if editing else 'disabled')
            uc.reg_write(UC_ARM64_REG_X28,0)
            for enum,string,text in [(0x50001001,0x53001001,'disabled'),(0x50001101,0x53001101,name)]:
                uc.mem_write(enum-1,struct.pack('<Q',2000<<12))
                uc.mem_write(enum+15,struct.pack('<I',string))
                uc.mem_write(string-1,struct.pack('<Q',94<<12))
                uc.mem_write(string+7,struct.pack('<Q',len(text)*2))
                uc.mem_write(string+15,text.encode())
            uc.mem_write(0x60004020,struct.pack('<Q',0x50001001))
            uc.mem_write(fp+16,struct.pack('<Q',0x50001001 if name=='disabled' else 0x50001101))

        if kind==3:
            uc.mem_write(0x52000000,struct.pack('<Q',62<<12 if bad_scalar!='foreign' else 63<<12))
            uc.mem_write(fp-16,struct.pack('<Q',2 if bad_scalar=='smi' else 0x55000001 if bad_scalar=='null' else 0x52000001))
        if kind==6:
            uc.reg_write(UC_ARM64_REG_X28,0)
            uc.mem_write(fp-16,struct.pack('<Q',0x50003001))
            for field,rx,boxed,value in [(271,0x51003001,0x52003001,.86),(279,0x51004001,0x52004001,-18.5)]:
                uc.mem_write(0x50003001+field,struct.pack('<I',rx))
                uc.mem_write(rx+19,struct.pack('<I',2 if bad_scalar=='smi' else 0x55000001 if bad_scalar=='null' else boxed))
                uc.mem_write(boxed-1,struct.pack('<Q',(63 if bad_scalar=='foreign' else 62)<<12))
                uc.mem_write(boxed+7,struct.pack('<d',value))
        if kind==6:
            uc.reg_write(UC_ARM64_REG_X28,0)
            uc.mem_write(fp-16,struct.pack('<Q',0x50003001))
            for field,rx,boxed,value in [(271,0x51003001,0x52003001,.86),(279,0x51004001,0x52004001,-18.5)]:
                uc.mem_write(0x50003001+field,struct.pack('<I',rx))
                uc.mem_write(rx+19,struct.pack('<I',2 if bad_scalar=='smi' else 0x55000001 if bad_scalar=='null' else boxed))
                uc.mem_write(boxed-1,struct.pack('<Q',(63 if bad_scalar=='foreign' else 62)<<12))
                uc.mem_write(boxed+7,struct.pack('<d',value))
        if kind==4:uc.mem_write(fp-24,struct.pack('<d',.6))
        if kind==5:
            uc.mem_write(fp-40,struct.pack('<d',.65))
            uc.mem_write(fp-48,struct.pack('<d',.6))
        uc.mem_write(symbols['g_dock_frame_enabled'],struct.pack('<I',int(enabled)))
        before=[uc.reg_read(reg(i)) for i in range(31)]
        floats=[uc.reg_read(UC_ARM64_REG_Q0+i) for i in range(32)]
        packets=[]
        def syscall(emu,_number,_context):
            assert emu.reg_read(UC_ARM64_REG_X8)==64
            assert emu.reg_read(UC_ARM64_REG_X0)==3
            length=emu.reg_read(UC_ARM64_REG_X2);assert length==72
            packets.append(bytes(emu.mem_read(emu.reg_read(UC_ARM64_REG_X1),length)))
            emu.reg_write(UC_ARM64_REG_X0,length)
        uc.hook_add(UC_HOOK_INTR,syscall)
        name={2:'HyperTweakDockEditingReturn',3:'HyperTweakDockControllerScaleReturn',4:'HyperTweakDockControllerAlphaReturn',5:'HyperTweakDockControllerSetReturn',6:'HyperTweakDockLocalTickReturn'}.get(kind,'HyperTweakDockHiddenReturn' if hidden else 'HyperTweakDockVisibleReturn')
        uc.emu_start(symbols[name],stop,count=1000)
        assert uc.reg_read(UC_ARM64_REG_PC)==stop
        assert uc.reg_read(UC_ARM64_REG_SP)==0x70001000
        assert uc.reg_read(UC_ARM64_REG_X15)==fp+16
        assert uc.reg_read(UC_ARM64_REG_X29)==0x60003000
        assert uc.reg_read(UC_ARM64_REG_X30)==stop
        assert uc.reg_read(UC_ARM64_REG_X0)==(0x55000001 if kind>=2 else 0xabcdef01 if hidden else before[0])
        for i in list(range(1,15))+list(range(16,29)):assert uc.reg_read(reg(i))==before[i],i
        assert uc.reg_read(UC_ARM64_REG_NZCV)==0xa0000000
        assert floats==[uc.reg_read(UC_ARM64_REG_Q0+i) for i in range(32)]
        if not hidden and kind<2:assert bytes(uc.mem_read(0x5100000c,4))==struct.pack('<I',child)
        if enabled and bad_scalar is None:
            assert len(packets)==1
            magic,t0,t1,sequence,dx,dy,scale,origin,alpha=struct.unpack('<QqqQddddd',packets[0])
            assert (magic,t0,t1,sequence)==(0x444b+((7 if hidden else kind)<<16)+(305419896<<32),101,-202,1)
            assert (dx,dy,scale,origin,alpha)==((0,-18.5,.86,0,1) if kind==6 else (0,0,.92,0,1) if kind==3 else (0,0,1,0,.6) if kind==4 else (0,0,.65,0,.6) if kind==5 else (0,0,1,1 if edit_name in ['quick','shortcutMenu'] else 0,1 if (edit_name or ('normal' if editing else 'disabled')) in ['disabled','quick','shortcutMenu'] else 0) if kind==2 else (0,0,1,0,0) if hidden else (-125.5,31.25,1 if null_scale else .92,9.5,.6))
        else:assert not packets
        sample=struct.unpack('<7Q',bytes(uc.mem_read(symbols['g_dock_frame_samples']+(7 if hidden else kind)*56,56)))
        assert sample[0]==(0 if bad_scalar else 2) and sample[6]==(0 if bad_scalar else 1)
        assert uc.reg_read(UC_ARM64_REG_X0)==(0x55000001 if kind>=2 else 0xabcdef01 if hidden else before[0])
    for hidden in [False,True]:
        for enabled in [False,True]:run(hidden,enabled)
    run(null_scale=True)
    for name in ["normal","quick","shortcutMenu","multiselect","pinchingIn","pinchingOut","preview","quickX"]:run(kind=2,edit_name=name)
    for bad in ["smi","null","foreign"]:
        run(kind=3,bad_scalar=bad)
        run(kind=6,bad_scalar=bad)
    for enabled in [False,True]:
        for kind in [3,4,5,6]:run(kind=kind,enabled=enabled)
        for editing in [False,True]:run(kind=2,editing=editing,enabled=enabled)
    print('PASS: production Dock epilogues, packet ABI, all live registers, vectors, SP, Dart SP, NZCV, null scale and disabled syscall gate')
