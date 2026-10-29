#!/usr/bin/env python3
"""Execute production icon-theme shims, checking every retained register and original compare inputs."""
from pathlib import Path
import argparse,struct,subprocess,tempfile
from elftools.elf.elffile import ELFFile
from unicorn import Uc,UC_ARCH_ARM64,UC_MODE_ARM
from unicorn.arm64_const import *
p=argparse.ArgumentParser(description=__doc__);p.add_argument('compiler');a=p.parse_args()
source=Path(__file__).resolve().parents[2]/'main/cpp/NativeRules/rules/icon_theme_hook.S'
def reg(i):return UC_ARM64_REG_X0+i if i<29 else UC_ARM64_REG_X29 if i==29 else UC_ARM64_REG_X30
with tempfile.TemporaryDirectory(prefix='ht-icon-theme-abi-') as temporary:
 root=Path(temporary);stub=root/'stub.S';binary=root/'shim'
 stub.write_text('''.data
.balign 8
.global g_icon_theme_unlock
g_icon_theme_unlock: .word 0
.global g_icon_theme_refresh
g_icon_theme_refresh: .word 0
.balign 8
.global g_icon_theme_support_original
g_icon_theme_support_original: .xword continuation
.global g_icon_theme_refresh_original
g_icon_theme_refresh_original: .xword continuation
.text
.global continuation
continuation: ret
.section .note.GNU-stack,"",%progbits
''')
 subprocess.run([a.compiler,'--target=aarch64-linux-android35','-nostdlib','-static','-Wl,-e,HyperTweakIconThemeSupportHook',str(source),str(stub),'-o',str(binary)],check=True)
 with binary.open('rb') as stream:
  elf=ELFFile(stream);symbols={s.name:s['st_value'] for s in elf.get_section_by_name('.symtab').iter_symbols()};segments=[(s['p_vaddr'],s['p_memsz'],s.data()) for s in elf.iter_segments() if s['p_type']=='PT_LOAD']
 for kind in ('Support','Refresh'):
  for enabled in (0,1):
   for boolean in (0x20000021,0x20000031):
    m=Uc(UC_ARCH_ARM64,UC_MODE_ARM);pages=set()
    for addr,size,data in segments:
     for page in range(addr&~4095,(addr+size+4095)&~4095,4096):
      if page not in pages:m.mem_map(page,4096);pages.add(page)
     m.mem_write(addr,data)
    m.mem_map(0x50000000,0x20000);sp=0x50010000
    for i in range(31):m.reg_write(reg(i),0x10101010+i)
    m.reg_write(reg(1),boolean);m.reg_write(reg(22),0x20000001)
    for i in range(32):m.reg_write(UC_ARM64_REG_Q0+i,(i+1)*0x10101010101010101010101010101)
    m.reg_write(UC_ARM64_REG_SP,sp);m.reg_write(UC_ARM64_REG_NZCV,0xa0000000);m.reg_write(UC_ARM64_REG_FPCR,0x400000);m.reg_write(UC_ARM64_REG_FPSR,1)
    before=[m.reg_read(reg(i)) for i in range(31)];vectors=[m.reg_read(UC_ARM64_REG_Q0+i) for i in range(32)]
    m.mem_write(symbols['g_icon_theme_unlock' if kind=='Support' else 'g_icon_theme_refresh'],struct.pack('<I',enabled))
    m.emu_start(symbols[f'HyperTweakIconTheme{kind}Hook'],symbols['continuation'],count=1000)
    expected={16:symbols['continuation']}
    if enabled:expected[1 if kind=='Support' else 3]=0x20000021 if kind=='Support' else boolean^0x10
    for i in range(31):assert m.reg_read(reg(i))==expected.get(i,before[i]),(kind,enabled,i)
    for i in range(32):assert m.reg_read(UC_ARM64_REG_Q0+i)==vectors[i],(kind,enabled,f'q{i}')
    assert m.reg_read(UC_ARM64_REG_SP)==sp
    assert m.reg_read(UC_ARM64_REG_NZCV)==0xa0000000
    assert m.reg_read(UC_ARM64_REG_FPCR)==0x400000 and m.reg_read(UC_ARM64_REG_FPSR)==1
print('icon theme ABI: both booleans, enable/disable, original continuations, GPR/SIMD, both stacks, NZCV and FP state passed')
