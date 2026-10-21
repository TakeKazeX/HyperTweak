#!/usr/bin/env python3
"""Exercise both production stack shims with Unicorn; no phone execution."""
import argparse
from pathlib import Path
import struct
import subprocess
import tempfile
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM
from unicorn.arm64_const import *
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('compiler')
a = p.parse_args()
native = Path(__file__).resolve().parents[4] / 'app/src/main/cpp/NativeRules'
names = ['requested', 'ready', 'field', 'drag_success', 'drag_reject', 'target_success', 'target_reject']
with tempfile.TemporaryDirectory(prefix='ht-widget-stack-abi-') as directory:
    root = Path(directory)
    (root / 'data.S').write_text('.bss\n.p2align 3\n' + '\n'.join(
        f'.global hypertweak_widget_stack_{n}\n.hidden hypertweak_widget_stack_{n}\nhypertweak_widget_stack_{n}: .zero 8' for n in names))
    binary = root / 'shim.so'
    subprocess.run([a.compiler, '--target=aarch64-linux-android35', '-shared', '-nostdlib', '-Wl,--no-undefined',
                    str(native / 'widget_stack_hook.S'), str(root / 'data.S'), '-o', str(binary)], check=True)
    with binary.open('rb') as f:
        elf = ELFFile(f)
        segments = [(s['p_vaddr'], s['p_memsz'], s.data()) for s in elf.iter_segments() if s['p_type'] == 'PT_LOAD']
        symbols = {s.name: s['st_value'] for s in elf.get_section_by_name('.symtab').iter_symbols()}
    base = 0x100000
    size = (max(va + n for va, n, _ in segments) + 4095) & ~4095
    cases = 0
    for side in ['drag', 'target']:
        for field in [0xf7, 0xfb]:
            for enabled in [0, 1]:
                for ready in [0, 1]:
                    for miui in [False, True]:
                        for nzcv in [0, 0xa0000000, 0xf0000000]:
                            vm = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
                            vm.mem_map(base, size)
                            for va, _, data in segments:
                                vm.mem_write(base + va, data)
                            vm.mem_map(0x200000, 0x20000)
                            vm.mem_map(0x300000, 0x10000)
                            success, reject = 0x300000, 0x300004
                            for n, value in [('requested', enabled), ('ready', ready), ('field', field),
                                             (side + '_success', success), (side + '_reject', reject)]:
                                vm.mem_write(base + symbols['hypertweak_widget_stack_' + n], struct.pack('<Q', value))
                            gpr = [UC_ARM64_REG_X0 + i for i in range(29)] + [UC_ARM64_REG_X29, UC_ARM64_REG_X30]
                            fp = [UC_ARM64_REG_Q0 + i for i in range(32)]
                            for i, reg in enumerate(gpr):
                                vm.reg_write(reg, 0x500000 + i * 32)
                            for i, reg in enumerate(fp):
                                vm.reg_write(reg, (i + 1) * 0x123456789012345678901234567)
                            obj, thread = 0x200101, 0x201000
                            # Nonzero heap base proves correct compressed-pointer restoration.
                            heap = 0x123
                            flag = 0x400001 + (0x20 if miui else 0x30)
                            vm.mem_write(obj + field, struct.pack('<I', flag))
                            vm.mem_write(thread + 0x78, struct.pack('<Q', 0x123456789abc))
                            receiver = UC_ARM64_REG_X1 if side == 'drag' else UC_ARM64_REG_X0
                            vm.reg_write(receiver, obj)
                            vm.reg_write(UC_ARM64_REG_X26, thread)
                            vm.reg_write(UC_ARM64_REG_X28, heap)
                            vm.reg_write(UC_ARM64_REG_SP, 0x210000)
                            vm.reg_write(UC_ARM64_REG_X15, 0x211000)
                            vm.reg_write(UC_ARM64_REG_NZCV, nzcv)
                            vm.reg_write(UC_ARM64_REG_FPCR, 0x400000)
                            vm.reg_write(UC_ARM64_REG_FPSR, 0x10)
                            before = {reg: vm.reg_read(reg) for reg in gpr + fp}
                            object_before = bytes(vm.mem_read(obj - 1, 512))
                            vm.reg_write(UC_ARM64_REG_PC, base + symbols['HyperTweakWidgetStack' + side.title() + 'Hook'])
                            for _ in range(64):
                                pc = vm.reg_read(UC_ARM64_REG_PC)
                                if pc in [success, reject]:
                                    break
                                vm.emu_start(pc, 0, count=1)
                            else:
                                raise AssertionError('no continuation')
                            accepted = miui or (enabled and ready)
                            assert vm.reg_read(UC_ARM64_REG_PC) == (success if accepted else reject)
                            value = flag + (heap << 32)
                            outputs = {UC_ARM64_REG_X0: value} if side == 'drag' else {UC_ARM64_REG_X4: value}
                            if not accepted:
                                outputs[UC_ARM64_REG_X0] = 0x123456789abc
                            for reg, value in before.items():
                                if reg not in [UC_ARM64_REG_X16, UC_ARM64_REG_X17]:
                                    assert vm.reg_read(reg) == outputs.get(reg, value), (side, reg)
                            assert vm.reg_read(UC_ARM64_REG_SP) == 0x210000
                            assert vm.reg_read(UC_ARM64_REG_NZCV) == nzcv
                            assert vm.reg_read(UC_ARM64_REG_FPCR) == 0x400000
                            assert vm.reg_read(UC_ARM64_REG_FPSR) == 0x10
                            assert bytes(vm.mem_read(obj - 1, 512)) == object_before
                            cases += 1
print(f'PASS {cases} production-shim cases: two gates, enable/disable/partial, MIUI/native flags, shifted field, compressed heap, GPR/SIMD/NZCV/FP and both stacks')
