#!/usr/bin/env python3
"""Exercise the actual initializer shim with hostile native clobbering and original FP continuation."""
from pathlib import Path
import argparse,struct,subprocess,tempfile
from elftools.elf.elffile import ELFFile
from unicorn import Uc,UC_ARCH_ARM64,UC_MODE_ARM
from unicorn.arm64_const import *
p=argparse.ArgumentParser(description=__doc__);p.add_argument('compiler');a=p.parse_args()
source=Path(__file__).resolve().parents[2]/'main/cpp/NativeRules/rules/icon_scale_scope_hook.S'
def reg(i):return UC_ARM64_REG_X0+i if i<29 else UC_ARM64_REG_X29 if i==29 else UC_ARM64_REG_X30
def bits(v):return struct.unpack('<Q',struct.pack('<d',v))[0]
with tempfile.TemporaryDirectory(prefix='ht-icon-scope-abi-') as tmp:
 root=Path(tmp);stub=root/'stub.S'
 code=['.data','.balign 8','.global g_icon_scope_continue','g_icon_scope_continue: .xword continuation','.global enabled','enabled: .xword 0','.global arguments','arguments: .zero 16',
 '.text','.global HyperTweakIconScopeBounds','HyperTweakIconScopeBounds:',
 'adrp x16,arguments','add x16,x16,:lo12:arguments','stp d0,d1,[x16]',
 'adrp x16,enabled','ldr x16,[x16,:lo12:enabled]','cbz x16,1f','fmov d0,#1.0','fmov d1,#0.125','1: stp d0,d1,[x0]']
 code += [f'mov x{i},#{100+i}' for i in range(30)]+[f'movi v{i}.16b,#{100+i}' for i in range(32)]+['msr nzcv,xzr','msr fpcr,xzr','msr fpsr,xzr','ret',
 '.global continuation','continuation: fmax v1.2d,v0.2d,v2.2d','.global done','done: ret','.section .note.GNU-stack,"",%progbits']
 stub.write_text('\n'.join(code)+'\n');binary=root/'shim'
 subprocess.run([a.compiler,'--target=aarch64-linux-android35','-nostdlib','-static','-Wl,-e,HyperTweakIconScopeClampHook',str(source),str(stub),'-o',str(binary)],check=True)
 with binary.open('rb') as stream:
  elf=ELFFile(stream);syms={s.name:s['st_value'] for s in elf.get_section_by_name('.symtab').iter_symbols()};segments=[(s['p_vaddr'],s['p_memsz'],s.data()) for s in elf.iter_segments() if s['p_type']=='PT_LOAD']
 for enabled in [0,1]:
  for request in [.34,.68,.8423773]:
   m=Uc(UC_ARCH_ARM64,UC_MODE_ARM);pages=set()
   for addr,size,data in segments:
    for page in range(addr&~4095,(addr+size+4095)&~4095,4096):
     if page not in pages:m.mem_map(page,4096);pages.add(page)
    m.mem_write(addr,data)
   m.mem_map(0x50000000,0x20000);m.mem_map(0x52000000,0x1000)
   for i in range(31):m.reg_write(reg(i),0x10101010+i)
   for i in range(32):m.reg_write(UC_ARM64_REG_Q0+i,(i+1)*0x10101010101010101010101010101)
   for i,addr in [(1,0x52000001),(3,0x52000021),(4,0x52000041)]:m.reg_write(reg(i),addr)
   for addr,v in [(0x52000008,request),(0x52000028,.54),(0x52000048,.72)]:m.mem_write(addr,struct.pack('<d',v))
   m.reg_write(UC_ARM64_REG_SP,0x50010000);m.reg_write(UC_ARM64_REG_NZCV,0xA0000000);m.reg_write(UC_ARM64_REG_FPCR,0x400000);m.reg_write(UC_ARM64_REG_FPSR,1)
   m.mem_write(syms['enabled'],struct.pack('<Q',enabled))
   before=[m.reg_read(reg(i)) for i in range(31)];vectors=[m.reg_read(UC_ARM64_REG_Q0+i) for i in range(32)];objects=bytes(m.mem_read(0x52000000,96))
   m.emu_start(syms['HyperTweakIconScopeClampHook'],syms['done'],count=10000)
   assert struct.unpack('<dd',m.mem_read(syms['arguments'],16))==(.72,.54)
   for i in range(31):assert m.reg_read(reg(i))==(syms['continuation'] if i==16 else before[i]),(enabled,request,i)
   minimum,maximum=(.125,1.0) if enabled else (.54,.72)
   expected=[bits(minimum),bits(max(minimum,min(request,maximum))),bits(min(request,maximum))]
   for i in range(32):assert m.reg_read(UC_ARM64_REG_Q0+i)==(expected[i] if i<3 else vectors[i]),(enabled,request,i,hex(m.reg_read(UC_ARM64_REG_Q0+i)),hex(expected[i]) if i<3 else 'preserved')
   assert m.reg_read(UC_ARM64_REG_SP)==0x50010000
   assert m.reg_read(UC_ARM64_REG_NZCV)==0xA0000000 and m.reg_read(UC_ARM64_REG_FPCR)==0x400000 and m.reg_read(UC_ARM64_REG_FPSR)==1
   assert bytes(m.mem_read(0x52000000,96))==objects
print('initializer clamp ABI: stock/expanded sizes, original FP continuation, object isolation, registers/SIMD/flags/stacks passed')
