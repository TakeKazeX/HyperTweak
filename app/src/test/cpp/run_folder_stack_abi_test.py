#!/usr/bin/env python3
"""Execute all production folder shims, including hostile native register clobbering."""
from pathlib import Path
import argparse
import struct
import subprocess
import tempfile
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM
from unicorn.arm64_const import *

parser=argparse.ArgumentParser()
parser.add_argument('compiler')
args=parser.parse_args()
source=Path(__file__).resolve().parents[2]/'main/cpp/NativeRules/rules/folder_stack_hook.S'
with tempfile.TemporaryDirectory(prefix='ht-folder-stack-abi-') as tmp:
    root=Path(tmp)
    stub=root/'stub.S'
    text=['.data','.balign 8','.global arguments','arguments: .zero 48',
          '.global hits','hits: .xword 0','.global result','result: .xword 0',
          '.global g_folder_stack_count_continue','g_folder_stack_count_continue: .xword count_continue',
          '.global g_folder_stack_order_continue','g_folder_stack_order_continue: .xword order_continue',
          '.global g_folder_stack_ready','g_folder_stack_ready: .word 1','.balign 8',
          '.global g_folder_stack_refresh_epoch','g_folder_stack_refresh_epoch: .xword 1',
          '.global g_folder_stack_refresh_applied','g_folder_stack_refresh_applied: .xword 0',
          '.global g_folder_stack_refresh_inflight','g_folder_stack_refresh_inflight: .xword 0',
          '.global g_folder_stack_refresh_owner','g_folder_stack_refresh_owner: .xword 0',
          '.global g_folder_stack_refresh_layouts','g_folder_stack_refresh_layouts: .xword 0',
          '.global g_folder_stack_layout_id_pool','g_folder_stack_layout_id_pool: .word 128',
          '.global g_folder_stack_preview_id_pool','g_folder_stack_preview_id_pool: .word 136',
          '.global g_folder_stack_typeargs_pool','g_folder_stack_typeargs_pool: .word 144','.balign 8',
          '.global g_folder_stack_identity_continue','g_folder_stack_identity_continue: .xword identity_continue',
          '.global g_folder_stack_refresh_target','g_folder_stack_refresh_target: .xword refresh_body',
          '.global g_folder_stack_model_slot','g_folder_stack_model_slot: .word 24',
          '.global g_folder_stack_registry_slot','g_folder_stack_registry_slot: .word 40','.text',
          '.global refresh_body','refresh_body:',
          'stp x29, x30, [x15, #-16]!','mov x29, x15','b HyperTweakFolderStackRefreshReturnHook',
          '.global identity_continue','identity_continue: ret']
    for name in ['HyperTweakUpdateFolderStack','HyperTweakFolderStackCount','HyperTweakFolderStackIndex']:
        text += [f'.global {name}',f'{name}:','b hostile']
    text += ['hostile:','adrp x16, arguments','add x16, x16, :lo12:arguments',
             'stp x0, x1, [x16]','stp x2, x3, [x16, #16]','str x4, [x16, #32]',
             'adrp x16, hits','add x16, x16, :lo12:hits','ldr x17, [x16]',
             'add x17, x17, #1','str x17, [x16]']
    text += [f'mov x{i}, #{100+i}' for i in range(30)]
    text += [f'movi v{i}.16b, #{100+i}' for i in range(32)]
    text += ['msr nzcv, xzr','msr fpcr, xzr','msr fpsr, xzr',
             'adrp x16, result','ldr x0, [x16, :lo12:result]','ret',
             '.global count_continue','count_continue: ret',
             '.global order_continue','order_continue: ret','.section .note.GNU-stack,"",%progbits']
    stub.write_text('\n'.join(text)+'\n')
    binary=root/'shims'
    subprocess.run([args.compiler,'--target=aarch64-linux-android35','-nostdlib','-static',
                    '-Wl,-e,HyperTweakFolderStackHook',str(source),str(stub),'-o',str(binary)],check=True)
    with binary.open('rb') as stream:
        elf=ELFFile(stream)
        symbols={s.name:s['st_value'] for s in elf.get_section_by_name('.symtab').iter_symbols()}
        segments=[(s['p_vaddr'],s['p_memsz'],s.data()) for s in elf.iter_segments() if s['p_type']=='PT_LOAD']
    for kind,returned in [('getter',1),('count',5),('count',6),('order',8),('order',4)]:
        machine=Uc(UC_ARCH_ARM64,UC_MODE_ARM)
        pages=set()
        for address,size,data in segments:
            for page in range(address&~4095,(address+size+4095)&~4095,4096):
                if page not in pages: machine.mem_map(page,4096); pages.add(page)
            machine.mem_write(address,data)
        machine.mem_map(0x50000000,0x10000)
        machine.mem_map(0x60000000,4096)
        frame=0x50006000; stop=0x60000000
        reg=lambda i: UC_ARM64_REG_X0+i if i<29 else (UC_ARM64_REG_X29 if i==29 else UC_ARM64_REG_X30)
        for i in range(31): machine.reg_write(reg(i),0x10101010+i)
        machine.reg_write(UC_ARM64_REG_X30,stop)
        machine.reg_write(UC_ARM64_REG_SP,0x50008000)
        machine.reg_write(UC_ARM64_REG_X29,frame)
        machine.reg_write(UC_ARM64_REG_X15,frame-64)
        machine.reg_write(UC_ARM64_REG_NZCV,0xA0000000)
        machine.reg_write(UC_ARM64_REG_FPCR,0x400000)
        machine.reg_write(UC_ARM64_REG_FPSR,1)
        for i in range(32): machine.reg_write(UC_ARM64_REG_Q0+i,(i+1)*0x10101010101010101010101010101)
        machine.mem_write(frame,struct.pack('<QQ',0x12345678,stop))
        machine.mem_write(frame-16,struct.pack('<Q',0x77777777))
        machine.mem_write(frame-8,struct.pack('<Q',12))
        if kind=='order':
            machine.reg_write(UC_ARM64_REG_X2,5)
            machine.reg_write(UC_ARM64_REG_X4,2)
        before=[machine.reg_read(reg(i)) for i in range(31)]
        vectors=[machine.reg_read(UC_ARM64_REG_Q0+i) for i in range(32)]
        machine.mem_write(symbols['result'],struct.pack('<Q',returned))
        expected={}
        if kind=='getter':
            entry='HyperTweakFolderStackHook'
            expected={0:before[0]+(before[28]<<32),15:frame+16,29:0x12345678}
            call_args=(0x77777777,before[28],before[22])
        elif kind=='count':
            entry='HyperTweakFolderStackCountHook'; stop=symbols['count_continue']
            expected={0:12,2:returned,3:before[0],16:stop}
            call_args=(before[0],12,before[28],before[22])
        else:
            entry='HyperTweakFolderStackOrderHook'; stop=symbols['order_continue']
            expected={0:0x77777777,16:stop}
            call_args=(0x77777777,2,5,before[28],before[22])
        machine.emu_start(symbols[entry],stop,count=10000)
        for i in range(31): assert machine.reg_read(reg(i))==expected.get(i,before[i]),f'{kind} x{i}'
        assert machine.reg_read(UC_ARM64_REG_SP)==0x50008000
        assert machine.reg_read(UC_ARM64_REG_NZCV)==0xA0000000
        assert machine.reg_read(UC_ARM64_REG_FPCR)==0x400000
        assert machine.reg_read(UC_ARM64_REG_FPSR)==1
        assert vectors==[machine.reg_read(UC_ARM64_REG_Q0+i) for i in range(32)]
        assert int.from_bytes(machine.mem_read(symbols['hits'],8),'little')==1
        assert struct.unpack('<'+'Q'*len(call_args),machine.mem_read(symbols['arguments'],8*len(call_args)))==call_args
        if kind=='count': assert int.from_bytes(machine.mem_read(frame-16,8),'little')==before[0]
        if kind=='order': assert struct.unpack('<QQ',machine.mem_read(frame-64,16))==(returned,0x77777777)
    # Frame refresh uses a tail transfer after popping the original Dart frame.
    # It may run when disabled (restoration), but never before readiness/startup.
    for case in ['pending','equal','disabled_restore','not_ready','model_sentinel',
                 'model_null','model_smi','registry_sentinel','registry_null','registry_smi','no_target','newer_during_refresh']:
        machine=Uc(UC_ARCH_ARM64,UC_MODE_ARM); pages=set()
        for address,size,data in segments:
            for page in range(address&~4095,(address+size+4095)&~4095,4096):
                if page not in pages: machine.mem_map(page,4096); pages.add(page)
            machine.mem_write(address,data)
        machine.mem_map(0x50000000,0x10000); machine.mem_map(0x60000000,4096)
        frame=0x50006000; stop=0x60000000
        reg=lambda i: UC_ARM64_REG_X0+i if i<29 else (UC_ARM64_REG_X29 if i==29 else UC_ARM64_REG_X30)
        write=lambda at,value:machine.mem_write(at,struct.pack('<Q',value))
        for i in range(31):machine.reg_write(reg(i),0x10101010+i)
        machine.reg_write(UC_ARM64_REG_SP,0x50008000)
        for r,v in [(15,frame-64),(22,0x50000101),(26,0x50002000),(27,0x50003000),(29,frame),(30,stop)]:machine.reg_write(reg(r),v)
        write(frame,0x12345678); write(frame+8,stop)
        write(0x50002078,0x50001000); write(0x50003040,0x50000201)
        model={'model_sentinel':0x50000201,'model_null':0x50000101,'model_smi':8}.get(case,0x50000301)
        registry={'registry_sentinel':0x50000201,'registry_null':0x50000101,'registry_smi':8}.get(case,0x50000401)
        write(0x50001018,model); write(0x50001028,registry)
        if case=='equal':write(symbols['g_folder_stack_refresh_applied'],1)
        if case=='not_ready':machine.mem_write(symbols['g_folder_stack_ready'],struct.pack('<I',0))
        if case=='no_target':write(symbols['g_folder_stack_refresh_target'],0)
        for i in range(32):machine.reg_write(UC_ARM64_REG_Q0+i,i+123)
        machine.reg_write(UC_ARM64_REG_NZCV,0xA0000000)
        machine.reg_write(UC_ARM64_REG_FPCR,0x400000);machine.reg_write(UC_ARM64_REG_FPSR,1)
        before=[machine.reg_read(reg(i)) for i in range(31)]
        tail=case in ['pending','disabled_restore','newer_during_refresh']
        destination=symbols['refresh_body'] if tail else stop
        machine.emu_start(symbols['HyperTweakFolderStackFrameHook'],destination,count=10000)
        expected={0:before[22],15:frame+16,29:0x12345678}
        if tail:expected[16]=destination
        for i in range(31):assert machine.reg_read(reg(i))==expected.get(i,before[i]),f'frame {case} x{i}'
        assert machine.reg_read(UC_ARM64_REG_SP)==0x50008000
        assert machine.reg_read(UC_ARM64_REG_NZCV)==0xA0000000
        assert machine.reg_read(UC_ARM64_REG_FPCR)==0x400000 and machine.reg_read(UC_ARM64_REG_FPSR)==1
        assert [machine.reg_read(UC_ARM64_REG_Q0+i) for i in range(32)]==list(range(123,155))
        read=lambda name:int.from_bytes(machine.mem_read(symbols[name],8),'little')
        assert read('g_folder_stack_refresh_applied')==(1 if case=='equal' else 0),case
        assert read('g_folder_stack_refresh_inflight')==(1 if tail else 0),case
        assert read('g_folder_stack_refresh_owner')==(before[26] if tail else 0),case
        if tail:
            if case=='newer_during_refresh':write(symbols['g_folder_stack_refresh_epoch'],2)
            machine.emu_start(destination,stop,count=10000)
            assert machine.reg_read(UC_ARM64_REG_X15)==frame+16
            assert machine.reg_read(UC_ARM64_REG_X29)==0x12345678
            assert read('g_folder_stack_refresh_applied')==1
            assert read('g_folder_stack_refresh_inflight')==0 and read('g_folder_stack_refresh_owner')==0
            if case=='newer_during_refresh':assert read('g_folder_stack_refresh_epoch')>read('g_folder_stack_refresh_applied')
    # Run the real identity shim: normal monochrome keeps its ID, module refresh
    # selects the parent GetBuilder ID, and a foreign isolate cannot borrow it.
    for case in ['module_refresh','normal_monochrome','foreign_isolate']:
        machine=Uc(UC_ARCH_ARM64,UC_MODE_ARM); pages=set()
        for address,size,data in segments:
            for page in range(address&~4095,(address+size+4095)&~4095,4096):
                if page not in pages:machine.mem_map(page,4096);pages.add(page)
            machine.mem_write(address,data)
        machine.mem_map(0x50000000,0x10000)
        reg=lambda i: UC_ARM64_REG_X0+i if i<29 else (UC_ARM64_REG_X29 if i==29 else UC_ARM64_REG_X30)
        write=lambda at,value:machine.mem_write(at,struct.pack('<Q',value))
        for i in range(31):machine.reg_write(reg(i),0x10101010+i)
        machine.reg_write(UC_ARM64_REG_SP,0x50008000)
        machine.reg_write(UC_ARM64_REG_X0,0x50004001)
        machine.reg_write(UC_ARM64_REG_X27,0x50003000)
        before=[machine.reg_read(reg(i)) for i in range(31)]
        write(0x50003080,0x50000101);write(0x50003088,0x50000201);write(0x50003090,0x50000301)
        write(symbols['g_folder_stack_refresh_inflight'],0 if case=='normal_monochrome' else 1)
        write(symbols['g_folder_stack_refresh_owner'],before[26]+(1 if case=='foreign_isolate' else 0))
        machine.reg_write(UC_ARM64_REG_NZCV,0xA0000000)
        for i in range(32):machine.reg_write(UC_ARM64_REG_Q0+i,123+i)
        destination=symbols['identity_continue']
        machine.emu_start(symbols['HyperTweakFolderStackIdentityHook'],destination,count=10000)
        selected=0x50000101 if case=='module_refresh' else 0x50000201
        expected={1:0x50000301,16:selected,17:destination}
        for i in range(31):assert machine.reg_read(reg(i))==expected.get(i,before[i]),f'identity {case} x{i}'
        assert int.from_bytes(machine.mem_read(before[0]+15,4),'little')==selected
        assert machine.reg_read(UC_ARM64_REG_SP)==0x50008000
        assert machine.reg_read(UC_ARM64_REG_NZCV)==0xA0000000
        assert [machine.reg_read(UC_ARM64_REG_Q0+i) for i in range(32)]==list(range(123,155))
        assert int.from_bytes(machine.mem_read(symbols['g_folder_stack_refresh_layouts'],8),'little')==(1 if case=='module_refresh' else 0)
print('production getter/count/order shims: arguments, fallback/remap results, frame stores, continuations, Dart/native stacks, GPR/SIMD/NZCV/FPCR/FPSR passed')
print('production frame shim: once per generation, disabled restoration, readiness/isolate guards, tail-call frame and register/stack preservation passed')
print('production identity/return shims: parent layout ID, original monochrome ID, isolate scope, original stores, completion ordering and register/stack preservation passed')
