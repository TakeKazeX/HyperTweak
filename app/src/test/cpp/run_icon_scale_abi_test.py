#!/usr/bin/env python3
"""Execute the production frame shim, including hostile native clobbering and host tail transfer."""
from pathlib import Path
import argparse
import struct
import subprocess
import tempfile
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM
from unicorn.arm64_const import *

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('compiler')
args = parser.parse_args()
source = Path(__file__).resolve().parents[2] / 'main/cpp/NativeRules/rules/icon_scale_hook.S'
with tempfile.TemporaryDirectory(prefix='ht-icon-abi-') as tmp:
    root = Path(tmp)
    stub = root / 'stub.S'
    code = ['.data', '.balign 8', '.global enabled', 'enabled: .xword 0',
            '.global arguments', 'arguments: .zero 40', '.text',
            '.global HyperTweakDesktopIconFrame', 'HyperTweakDesktopIconFrame:',
            'adrp x16, arguments', 'add x16, x16, :lo12:arguments',
            'stp x0, x1, [x16]', 'stp x2, x3, [x16, #16]', 'mov x17, sp', 'str x17, [x16, #32]',
            'mov x16, #0x1235', 'str x16, [x4]',
            'mov x16, #0x3ff0000000000000', 'str x16, [x5]']
    code += [f'mov x{i}, #{100 + i}' for i in range(30)]
    code += [f'movi v{i}.16b, #{100 + i}' for i in range(32)]
    code += ['msr nzcv, xzr', 'msr fpcr, xzr', 'msr fpsr, xzr',
             'adrp x16, enabled', 'ldr x16, [x16, :lo12:enabled]', 'cbz x16, 1f',
             'adrp x0, host_setter', 'add x0, x0, :lo12:host_setter', 'ret',
             '1: mov x0, xzr', 'ret', '.global host_setter', 'host_setter: ret', '.global HyperTweakDesktopGridFrame', 'HyperTweakDesktopGridFrame:',
             'adrp x16, enabled', 'ldr x16,[x16,:lo12:enabled]', 'cmp x16,#2', 'b.ne 2f',
             'adrp x16,host_grid', 'add x16,x16,:lo12:host_grid', 'str x16,[x4]',
             'mov x16,#0x2235', 'str x16,[x4,#8]', 'mov x16,#0x3235', 'str x16,[x4,#16]',
             'mov x16,#0x4235', 'str x16,[x4,#24]', 'mov x16,#0x5235', 'str x16,[x4,#32]', 'mov x16,#0x6235', 'str x16,[x4,#40]',
             'adrp x0,host_grid', 'add x0,x0,:lo12:host_grid', 'ret',
             '2: mov x0,xzr', 'ret', '.global host_grid', 'host_grid: ret',
             '.section .note.GNU-stack,"",%progbits']
    stub.write_text('\n'.join(code) + '\n')
    binary = root / 'shim'
    subprocess.run([args.compiler, '--target=aarch64-linux-android35', '-nostdlib', '-static',
                    '-Wl,-e,HyperTweakDesktopIconFrameHook', str(source), str(stub), '-o', str(binary)], check=True)
    with binary.open('rb') as stream:
        elf = ELFFile(stream)
        symbols = {s.name: s['st_value'] for s in elf.get_section_by_name('.symtab').iter_symbols()}
        segments = [(s['p_vaddr'], s['p_memsz'], s.data()) for s in elf.iter_segments() if s['p_type'] == 'PT_LOAD']
    def reg(i):
        return UC_ARM64_REG_X0 + i if i < 29 else (UC_ARM64_REG_X29 if i == 29 else UC_ARM64_REG_X30)
    for enabled in (0, 1, 2):
        for nzcv in (0, 0xA0000000, 0xF0000000):
            machine = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
            pages = set()
            for address, size, data in segments:
                for page in range(address & ~4095, (address + size + 4095) & ~4095, 4096):
                    if page not in pages:
                        machine.mem_map(page, 4096); pages.add(page)
                machine.mem_write(address, data)
            machine.mem_map(0x50000000, 0x20000)
            machine.mem_map(0x51000000, 0x10000)
            machine.mem_map(0x60000000, 4096)
            frame, native_sp, stop, old_fp = 0x50010000, 0x51008000, 0x60000000, 0x50018000
            for i in range(31): machine.reg_write(reg(i), 0x10101010 + i)
            machine.reg_write(reg(15), frame - 32)
            machine.reg_write(reg(29), frame)
            machine.reg_write(reg(30), 0x11111111)
            machine.reg_write(UC_ARM64_REG_SP, native_sp)
            machine.mem_write(frame, struct.pack('<QQ', old_fp, stop))
            machine.reg_write(UC_ARM64_REG_NZCV, nzcv)
            machine.reg_write(UC_ARM64_REG_FPCR, 0x400000)
            machine.reg_write(UC_ARM64_REG_FPSR, 1)
            for i in range(32): machine.reg_write(UC_ARM64_REG_Q0 + i, (i + 1) * 0x10101010101010101010101010101)
            before = [machine.reg_read(reg(i)) for i in range(31)]
            vectors = [machine.reg_read(UC_ARM64_REG_Q0 + i) for i in range(32)]
            machine.mem_write(symbols['enabled'], struct.pack('<Q', enabled))
            machine.emu_start(symbols['HyperTweakDesktopIconFrameHook'], symbols['host_grid'] if enabled == 2 else symbols['host_setter'] if enabled == 1 else stop, count=10000)
            for i in range(31):
                expected = {0: before[22], 15: frame + 16, 29: old_fp, 30: stop}.get(i, before[i])
                if enabled == 2:
                    expected = {1:0x2235,2:0x3235,3:0x4235,5:0x5235,4:0x6235,16:symbols['host_grid']}.get(i,expected)
                elif enabled == 1:
                    expected = {1: 0x1235, 16: symbols['host_setter']}.get(i, expected)
                assert machine.reg_read(reg(i)) == expected, (enabled, i)
            for i in range(32):
                expected = 0x3ff0000000000000 if enabled == 1 and i == 0 else vectors[i]
                assert machine.reg_read(UC_ARM64_REG_Q0 + i) == expected, (enabled, f'q{i}')
            assert machine.reg_read(UC_ARM64_REG_SP) == native_sp
            assert machine.reg_read(UC_ARM64_REG_NZCV) == nzcv
            assert machine.reg_read(UC_ARM64_REG_FPCR) == 0x400000
            assert machine.reg_read(UC_ARM64_REG_FPSR) == 1
            assert struct.unpack('<QQQQQ', machine.mem_read(symbols['arguments'], 40)) == (before[26], before[28], before[22], before[27], native_sp - 896)
print('production icon/grid frame shim: passive/host tail transfer, caller frame, registers, SIMD, both stacks, NZCV, FPCR/FPSR passed')
