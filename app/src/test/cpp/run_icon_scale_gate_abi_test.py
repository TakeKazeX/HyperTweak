#!/usr/bin/env python3
"""Execute native provider shim with hostile clobbering and decoded replay slots."""
from pathlib import Path
import argparse,struct,subprocess,tempfile
from elftools.elf.elffile import ELFFile
from unicorn import Uc,UC_ARCH_ARM64,UC_MODE_ARM
from unicorn.arm64_const import *
p=argparse.ArgumentParser(description=__doc__);p.add_argument('compiler');a=p.parse_args()
source=Path(__file__).resolve().parents[2]/'main/cpp/NativeRules/rules/icon_scale_gate_hook.S'
def bits(v):return struct.unpack('<I',struct.pack('<f',v))[0]
def reg(i):return UC_ARM64_REG_X0+i if i<29 else UC_ARM64_REG_X29 if i==29 else UC_ARM64_REG_X30
with tempfile.TemporaryDirectory(prefix='ht-icon-gate-abi-') as tmp:
 root=Path(tmp);stub=root/'stub.S'
 code=['.data','.balign 8','.global g_icon_gate_continue','g_icon_gate_continue: .xword continuation']
 for name in ['max','min','current']:code += [f'.global g_icon_gate_{name}_slot',f'g_icon_gate_{name}_slot: .word 0']
 code += ['.global arguments','arguments: .zero 12','.text','.global HyperTweakDesktopIconGate','HyperTweakDesktopIconGate:',
 'adrp x16, arguments','add x16,x16,:lo12:arguments','stp s0,s1,[x16]','str s2,[x16,#8]']
 for off,v in [(0,.84),(4,.01),(8,.84)]:
  n=bits(v);code += [f'mov w16,#{n&65535}',f'movk w16,#{n>>16},lsl #16',f'str w16,[x0,#{off}]']
 code += [f'mov x{i},#{100+i}' for i in range(30)] + [f'movi v{i}.16b,#{100+i}' for i in range(32)]
 code += ['msr nzcv,xzr','msr fpcr,xzr','msr fpsr,xzr','ret','.global continuation','continuation: ret','.section .note.GNU-stack,"",%progbits']
 stub.write_text('\n'.join(code)+'\n');binary=root/'shim'
 subprocess.run([a.compiler,'--target=aarch64-linux-android35','-nostdlib','-static','-Wl,-e,HyperTweakDesktopIconGateHook',str(source),str(stub),'-o',str(binary)],check=True)
 with binary.open('rb') as stream:
  elf=ELFFile(stream);syms={s.name:s['st_value'] for s in elf.get_section_by_name('.symtab').iter_symbols()};segments=[(s['p_vaddr'],s['p_memsz'],s.data()) for s in elf.iter_segments() if s['p_type']=='PT_LOAD']
 for slots in [(0x278,0x130,0x158),(0x88,0x1a0,0x300)]:
  m=Uc(UC_ARCH_ARM64,UC_MODE_ARM);pages=set()
  for addr,size,data in segments:
   for page in range(addr&~4095,(addr+size+4095)&~4095,4096):
    if page not in pages:m.mem_map(page,4096);pages.add(page)
   m.mem_write(addr,data)
  m.mem_map(0x50000000,0x20000);sp=0x50010000
  for i in range(31):m.reg_write(reg(i),0x10101010+i)
  for i in range(32):m.reg_write(UC_ARM64_REG_Q0+i,(i+1)*0x10101010101010101010101010101)
  for i,v in [(0,.68),(1,.54),(3,.72),(8,1.02)]:m.reg_write(UC_ARM64_REG_Q0+i,bits(v))
  m.reg_write(UC_ARM64_REG_SP,sp);m.reg_write(UC_ARM64_REG_FPCR,0x400000);m.reg_write(UC_ARM64_REG_FPSR,1)
  before=[m.reg_read(reg(i)) for i in range(31)];vectors=[m.reg_read(UC_ARM64_REG_Q0+i) for i in range(32)]
  for name,off in zip(['max','min','current'],slots):m.mem_write(syms[f'g_icon_gate_{name}_slot'],struct.pack('<I',off))
  m.emu_start(syms['HyperTweakDesktopIconGateHook'],syms['continuation'],count=10000)
  for i in range(31):assert m.reg_read(reg(i))==(syms['continuation'] if i==17 else before[i]),i
  for i in range(32):assert m.reg_read(UC_ARM64_REG_Q0+i)==({1:bits(.01),3:bits(.84),8:bits(.84)}.get(i,vectors[i])),f'q{i}'
  assert m.reg_read(UC_ARM64_REG_SP)==sp
  for off,v in zip(slots,[.84,.01,.68]):assert struct.unpack('<I',m.mem_read(sp+off,4))[0]==bits(v)
  assert list(struct.unpack('<III',m.mem_read(syms['arguments'],12)))==[bits(1.02),bits(.54),bits(.72)]
  assert m.reg_read(UC_ARM64_REG_NZCV)==0x60000000
  assert m.reg_read(UC_ARM64_REG_FPCR)==0x400000 and m.reg_read(UC_ARM64_REG_FPSR)==1
print('native icon gate ABI: input lanes, all retained registers/SIMD, decoded SP stores, flags, FPCR/FPSR and continuation passed')
