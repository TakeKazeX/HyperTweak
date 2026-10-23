#!/usr/bin/env python3
"""Execute the production Dart entry observer, including hostile native register clobbering."""
from pathlib import Path
import argparse
import struct
import subprocess
import tempfile
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM
from unicorn.arm64_const import *

parser = argparse.ArgumentParser()
parser.add_argument('compiler')
args = parser.parse_args()
source = Path(__file__).resolve().parents[2] / 'main/cpp/NativeRules/xiaoai_gesture_hook.S'
with tempfile.TemporaryDirectory(prefix='ht-recents-abi-') as tmp:
    root = Path(tmp)
    stub = root / 'stub.S'
    text = ['.data', '.balign 8', '.global g_xiaoai_gesture_enabled',
            'g_xiaoai_gesture_enabled: .word 1', '.balign 8',
            '.global g_xiaoai_gesture_original', 'g_xiaoai_gesture_original: .xword original',
            '.global decision', 'decision: .word 0', '.balign 8', '.global arguments', 'arguments: .zero 32', '.global hits', 'hits: .xword 0',
            '.text', '.global HyperTweakAllowXiaoaiGesture', 'HyperTweakAllowXiaoaiGesture:',
            'adrp x16, arguments', 'add x16, x16, :lo12:arguments',
            'stp x0, x1, [x16]', 'stp x2, x3, [x16, #16]',
            'adrp x16, hits', 'add x16, x16, :lo12:hits',
            'ldr x17, [x16]', 'add x17, x17, #1', 'str x17, [x16]']
    text += [f'mov x{i}, #{i + 100}' for i in range(30)]
    text += [f'movi v{i}.16b, #{i + 100}' for i in range(32)]
    text += ['adrp x0, decision', 'ldr w0, [x0, :lo12:decision]', 'msr nzcv, xzr', 'msr fpcr, xzr', 'msr fpsr, xzr', 'ret',
             '.global original', 'original:', 'ret', '.section .note.GNU-stack,"",%progbits']
    stub.write_text('\n'.join(text) + '\n')
    binary = root / 'observer'
    subprocess.run([args.compiler, '--target=aarch64-linux-android35', '-nostdlib', '-static',
                    '-Wl,-e,HyperTweakXiaoaiGestureHook', str(source), str(stub), '-o', str(binary)], check=True)
    with binary.open('rb') as stream:
        elf = ELFFile(stream)
        symbols = {s.name: s['st_value'] for s in elf.get_section_by_name('.symtab').iter_symbols()}
        segments = [(s['p_vaddr'], s['p_memsz'], s.data()) for s in elf.iter_segments() if s['p_type'] == 'PT_LOAD']
    for enabled, decision in ((False, False), (True, False), (True, True)):
        machine = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        pages = set()
        for address, size, data in segments:
            for page in range(address & ~4095, (address + size + 4095) & ~4095, 4096):
                if page not in pages:
                    machine.mem_map(page, 4096)
                    pages.add(page)
            machine.mem_write(address, data)
        machine.mem_map(0x50000000, 0x10000)
        machine.mem_map(0x60000000, 4096)
        stop = 0x60000000
        reg = lambda i: UC_ARM64_REG_X0 + i if i < 29 else (UC_ARM64_REG_X29 if i == 29 else UC_ARM64_REG_X30)
        for i in range(31):
            machine.reg_write(reg(i), 0x10101010 + i)
        machine.reg_write(UC_ARM64_REG_X30, stop)
        machine.reg_write(UC_ARM64_REG_X15, 0x50001000)
        machine.mem_write(0x50001000, struct.pack('<QQ', 0x12345679, 0x12345879))
        machine.reg_write(UC_ARM64_REG_SP, 0x50008000)
        machine.reg_write(UC_ARM64_REG_NZCV, 0xA0000000)
        machine.reg_write(UC_ARM64_REG_FPCR, 0x400000)
        machine.reg_write(UC_ARM64_REG_FPSR, 1)
        for i in range(32):
            machine.reg_write(UC_ARM64_REG_Q0 + i, (i + 1) * 0x10101010101010101010101010101)
        before = [machine.reg_read(reg(i)) for i in range(31)]
        vector = [machine.reg_read(UC_ARM64_REG_Q0 + i) for i in range(32)]
        machine.mem_write(symbols['decision'], struct.pack('<I', decision))
        machine.mem_write(symbols['g_xiaoai_gesture_enabled'], struct.pack('<I', enabled))
        machine.emu_start(symbols['HyperTweakXiaoaiGestureHook'], stop, count=10000)
        for i in range(31):
            expected = before[i]
            if i == 16: expected = 1 if enabled and decision else symbols['original']
            if i == 0 and enabled and decision: expected = before[22] + 0x20
            assert machine.reg_read(reg(i)) == expected, f'x{i}'
        assert machine.reg_read(UC_ARM64_REG_SP) == 0x50008000
        assert machine.reg_read(UC_ARM64_REG_NZCV) == 0xA0000000
        assert machine.reg_read(UC_ARM64_REG_FPCR) == 0x400000
        assert machine.reg_read(UC_ARM64_REG_FPSR) == 1
        assert vector == [machine.reg_read(UC_ARM64_REG_Q0 + i) for i in range(32)]
        assert int.from_bytes(machine.mem_read(symbols['hits'], 8), 'little') == enabled
        if enabled:
            assert struct.unpack('<QQQQ', machine.mem_read(symbols['arguments'], 32)) == (0x12345679, 0x12345879, before[28], before[27])
print('production XiaoAI predicate: disabled/native-fallback/expanded-result, arguments, registers, SIMD, SP, NZCV, FPCR/FPSR passed')
