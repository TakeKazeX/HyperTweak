#!/usr/bin/env python3
"""Run real ComputePipeline count/row/clamp assembly, including null row hints."""
from pathlib import Path
import argparse,struct,subprocess,tempfile
from elftools.elf.elffile import ELFFile
from unicorn import Uc,UC_ARCH_ARM64,UC_MODE_ARM,UC_HOOK_CODE
from unicorn.arm64_const import *
p=argparse.ArgumentParser(description=__doc__);p.add_argument('compiler');args=p.parse_args()
source=Path(__file__).resolve().parents[2]/'main/cpp/NativeRules/rules/desktop_grid_hook.S'
def reg(i):return UC_ARM64_REG_X0+i if i<29 else UC_ARM64_REG_X29 if i==29 else UC_ARM64_REG_X30
def cmp_flags(a,b):
 result=(a-b)&0xffffffff
 return ((result>>31)<<31)|((result==0)<<30)|((a>=b)<<29)|((((a^b)&(a^result))>>31&1)<<28)
with tempfile.TemporaryDirectory(prefix='ht-grid-abi-') as tmp:
 root=Path(tmp);stub=root/'stub.S'
 names={'geometry':0,'handler_continue':'done','variable_continue':'done','fixed_entry':'done','rows_continue':'done','rows_null':'null_done','clamp_continue':'done','context_x':0x83,'context_y':0x8b,'input_rows':0x43,'maximum_field':0x83,'unset_field':0x8b}
 lines=['.data','.balign 8']
 for name,value in names.items():lines.extend(['.global hypertweak_grid_'+name,'hypertweak_grid_'+name+': .xword '+str(value)])
 lines+=['.text','.global done','done: ret','.global null_done','null_done: ret','.section .note.GNU-stack,"",%progbits']
 stub.write_text('\n'.join(lines)+'\n');binary=root/'shim'
 subprocess.run([args.compiler,'--target=aarch64-linux-android35','-nostdlib','-static','-Wl,-e,HyperTweakDesktopGridHandlerHook',str(source),str(stub),'-o',str(binary)],check=True)
 with binary.open('rb') as f:
  elf=ELFFile(f);symbols={s.name:s['st_value'] for s in elf.get_section_by_name('.symtab').iter_symbols()};segments=[(s['p_vaddr'],s['p_memsz'],s.data()) for s in elf.iter_segments() if s['p_type']=='PT_LOAD']
 cases=0
 for kind in ['Handler','Variable','Rows','Clamp']:
  for columns in range(4,7):
   for rows in range(5,9):
    for enabled in [False,True]:
     for flags in [0,0xa0000000,0xf0000000]:
      for null_hint in ([False,True] if kind=='Rows' else [False]):
       m=Uc(UC_ARCH_ARM64,UC_MODE_ARM);pages=set()
       for at,size,data in segments:
        for page in range(at&~4095,(at+size+4095)&~4095,4096):
         if page not in pages:m.mem_map(page,4096);pages.add(page)
        m.mem_write(at,data)
       m.mem_map(0x50000000,0x20000);m.mem_map(0x52000000,0x1000)
       for i in range(31):m.reg_write(reg(i),0x10101010+i)
       for i in range(32):m.reg_write(UC_ARM64_REG_Q0+i,(i+1)*0x10101010101010101010101010101)
       m.reg_write(reg(0),0x52000601);m.reg_write(reg(1),0x52000001);m.reg_write(reg(2),0x52000401);m.reg_write(reg(22),0x52000901);m.reg_write(reg(28),0)
       m.reg_write(reg(15),0x50010000);m.reg_write(UC_ARM64_REG_SP,0x50018000)
       m.reg_write(UC_ARM64_REG_NZCV,flags);m.reg_write(UC_ARM64_REG_FPCR,0x400000);m.reg_write(UC_ARM64_REG_FPSR,1)
       before=[m.reg_read(reg(i)) for i in range(31)];vectors=[m.reg_read(UC_ARM64_REG_Q0+i) for i in range(32)]
       hint=before[22] if null_hint else 12;m.mem_write(before[0]+0x43,struct.pack('<I',hint))
       m.mem_write(symbols['hypertweak_grid_geometry'],struct.pack('<I',columns+(rows<<8) if enabled else 0))
       def stop(machine,address,size,user):
        if address in (symbols['done'],symbols['null_done']):machine.emu_stop()
       m.hook_add(UC_HOOK_CODE,stop);m.emu_start(symbols['HyperTweakDesktopGrid'+kind+'Hook'],0,count=10000)
       expected=before[:];expected_flags=flags;expected_stop=symbols['done']
       if kind=='Clamp':
        expected[2]=6 if enabled else 5;expected[3]=(1<<64)-1
        assert struct.unpack('<QQ',m.mem_read(before[1]+0x83,16))==(expected[2],expected[3])
       elif kind=='Rows':
        expected[3]=2*rows if enabled else hint;expected_flags=cmp_flags(expected[3],before[22])
        if not enabled and null_hint:expected_stop=symbols['null_done']
       elif kind=='Handler' or not enabled:
        frame_size=0x20 if kind=='Handler' else 0x38;expected[15]=before[15]-16-frame_size;expected[29]=before[15]-16
        assert struct.unpack('<QQ',m.mem_read(before[15]-16,16))==(before[29],before[30])
        assert struct.unpack('<Q',m.mem_read(expected[29]-8,8))[0]==before[22 if kind=='Handler' else 1]
       if kind=='Handler':
        assert struct.unpack('<QQ',m.mem_read(before[2]+0x83,16))==((columns,rows) if enabled else (0,0))
        assert bytes(m.mem_read(before[2]+0x1b,16))==bytes(16)
       assert m.reg_read(UC_ARM64_REG_PC)==expected_stop
       for i in range(31):
        if i not in [16,17]:assert m.reg_read(reg(i))==expected[i],(kind,columns,rows,enabled,null_hint,i)
       for i in range(32):assert m.reg_read(UC_ARM64_REG_Q0+i)==vectors[i]
       assert m.reg_read(UC_ARM64_REG_SP)==0x50018000
       assert m.reg_read(UC_ARM64_REG_NZCV)==expected_flags,(kind,hex(m.reg_read(UC_ARM64_REG_NZCV)),hex(expected_flags))
       assert m.reg_read(UC_ARM64_REG_FPCR)==0x400000 and m.reg_read(UC_ARM64_REG_FPSR)==1
       cases+=1
 print(f'production ComputePipeline ABI: {cases} cases passed; 12 grids, primitive context fields, variable/fixed tail transfer, null hints, stock replay, registers/SIMD/stacks/flags')
