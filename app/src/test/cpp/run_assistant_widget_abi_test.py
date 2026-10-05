#!/usr/bin/env python3
"""Execute the production AArch64 shim offline with Unicorn + pyelftools.

Pass the Android NDK clang path. No executable is installed on the phone.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile
import struct
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM
from unicorn.arm64_const import UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2, UC_ARM64_REG_X4, UC_ARM64_REG_X15, UC_ARM64_REG_X16, UC_ARM64_REG_X17, UC_ARM64_REG_X22, UC_ARM64_REG_X28, UC_ARM64_REG_SP, UC_ARM64_REG_PC, UC_ARM64_REG_NZCV

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('compiler')
args = parser.parse_args()
repo = Path(__file__).resolve().parents[4]
native = repo / 'app/src/main/cpp/NativeRules'
metadata = ['continue', 'accepted', 'reject', 'field', 'cid', 'hits']
with tempfile.TemporaryDirectory(prefix='ht-widget-abi-') as directory:
    path = Path(directory)
    definitions = '.bss\n.p2align 3\n' + '\n'.join(
        f'.global hypertweak_assistant_widget_{name}\n.hidden hypertweak_assistant_widget_{name}\nhypertweak_assistant_widget_{name}: .zero 8' for name in metadata)
    (path / 'globals.S').write_text(definitions)
    binary = path / 'shim.so'
    subprocess.run([args.compiler, '--target=aarch64-linux-android35', '-shared', '-nostdlib',
                    '-Wl,--no-undefined', str(native / 'assistant_widget_hook.S'),
                    str(path / 'globals.S'), '-o', str(binary)], check=True)
    with binary.open('rb') as stream:
        elf = ELFFile(stream)
        segments = [(s['p_vaddr'], s['p_memsz'], s.data()) for s in elf.iter_segments() if s['p_type'] == 'PT_LOAD']
        symbols = {s.name: s['st_value'] for s in elf.get_section_by_name('.symtab').iter_symbols()}
    base = 0x100000
    limit = (max(va + size for va, size, _ in segments) + 4095) & ~4095
    for field in (0xf7, 0xfb):
        for cid in (2033, 2040):
            for actual_cid, miui, passes in [(cid, False, True), (cid, True, True), (cid + 1, False, False), (cid + 1, True, True)]:
                for width, height in [(1, 1), (2, 1), (4, 1), (1, 2), (2, 3), (3, 2), (4, 5)]:
                    vm = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
                    vm.mem_map(base, limit)
                    for va, _, data in segments:
                        vm.mem_write(base + va, data)
                    vm.mem_map(0x200000, 0x10000)
                    vm.mem_map(0x300000, 0x10000)
                    continuation, rejection, accepted = 0x300000, 0x300004, 0x300008
                    # The emulator stops before entering the continuation, just as a
                    # native ABI harness returns control at a synthetic boundary.
                    for name, value in [('continue', continuation), ('accepted', accepted), ('reject', rejection), ('field', field), ('cid', cid)]:
                        vm.mem_write(base + symbols['hypertweak_assistant_widget_' + name], struct.pack('<Q', value))
                    null, object_address = 0x400000, 0x200001
                    flag = null + (0x20 if miui else 0x30)
                    vm.mem_write(object_address - 1, struct.pack('<Q', actual_cid << 12))
                    vm.mem_write(object_address + field, struct.pack('<I', flag))
                    registers = [UC_ARM64_REG_X0 + i for i in range(29)]
                    # Unicorn's register ids are not contiguous for x29/x30; all
                    # tested application and reserved x0..x28 registers are.
                    sentinels = {reg: 0x500000 + i * 32 for i, reg in enumerate(registers)}
                    for reg, value in sentinels.items():
                        vm.reg_write(reg, value)
                    vm.reg_write(UC_ARM64_REG_X2, object_address)
                    vm.reg_write(UC_ARM64_REG_X4, width)
                    vm.reg_write(UC_ARM64_REG_X0 + 6, height)
                    vm.reg_write(UC_ARM64_REG_X22, null)
                    vm.reg_write(UC_ARM64_REG_X28, 0)
                    vm.reg_write(UC_ARM64_REG_SP, 0x20f000)
                    vm.reg_write(UC_ARM64_REG_NZCV, 0xa0000000)
                    before = {reg: vm.reg_read(reg) for reg in registers}
                    # Stop whichever destination is taken using a bounded single
                    # instruction loop; a broken shim cannot hang the verifier.
                    vm.reg_write(UC_ARM64_REG_PC, base + symbols['HyperTweakAssistantWidgetGateHook'])
                    for _ in range(80):
                        pc = vm.reg_read(UC_ARM64_REG_PC)
                        if pc in (continuation, rejection, accepted):
                            break
                        vm.emu_start(pc, 0, count=1)
                    else:
                        raise AssertionError('shim did not reach a continuation')
                    assert vm.reg_read(UC_ARM64_REG_PC) == (accepted if actual_cid == cid and not miui else continuation if passes else rejection)
                    assert vm.reg_read(UC_ARM64_REG_X0) == (null + 0x20 if actual_cid == cid and not miui else flag)
                    assert vm.reg_read(UC_ARM64_REG_X1) == (before[UC_ARM64_REG_X4] if passes else before[UC_ARM64_REG_X1])
                    assert vm.reg_read(UC_ARM64_REG_X2) == (before[UC_ARM64_REG_X0 + 6] if actual_cid == cid and not miui else object_address)
                    for reg in registers:
                        if reg not in (UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2, UC_ARM64_REG_X16, UC_ARM64_REG_X17):
                            assert vm.reg_read(reg) == before[reg], reg
                    assert vm.reg_read(UC_ARM64_REG_SP) == 0x20f000
                    assert vm.reg_read(UC_ARM64_REG_NZCV) == 0xa0000000
                    assert vm.mem_read(object_address + field, 4) == struct.pack('<I', flag)
                    hits = struct.unpack('<I', vm.mem_read(base + symbols['hypertweak_assistant_widget_hits'], 4))[0]
                    assert hits == int(actual_cid == cid and not miui)
print('PASS: actual AArch64 shim across seven dimension pairs, AppWidget-only size bypass, true/false flags, AppWidget/other CID, adaptive field/CID, original flag, Dart registers/SP and NZCV')
