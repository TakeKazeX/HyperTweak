#!/usr/bin/env python3
"""Execute actual host decisions through the production shim and a relocated trampoline."""
from pathlib import Path
import argparse
import struct
import subprocess
import tempfile
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM
from unicorn.arm64_const import *
from native_test_paths import native_include_flags

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('compiler')
p.add_argument('snapshot', type=Path)
a = p.parse_args()
native = Path(__file__).resolve().parents[2] / 'main/cpp/NativeRules'

def x(i):
    return UC_ARM64_REG_X0 + i if i < 29 else UC_ARM64_REG_X29 if i == 29 else UC_ARM64_REG_X30

def signed(value, bits):
    return value - (1 << bits) if value & (1 << (bits - 1)) else value

def words(values):
    return struct.pack('<' + 'I' * len(values), *values)

def relative(word, source, destination, bits, shift):
    delta = (destination - source) // 4
    assert -(1 << (bits - 1)) <= delta < (1 << (bits - 1))
    mask = ((1 << bits) - 1) << shift
    return (word & ~mask) | ((delta << shift) & mask)

with tempfile.TemporaryDirectory(prefix='ht-icon-theme-flow-') as temporary:
    root = Path(temporary)
    probe = root / 'probe.cpp'
    probe.write_text('''#include "dart_targets.h"
#include <fstream>
#include <iterator>
#include <vector>
#include <cstdio>
using namespace hypertweak::native::dart;
int main(int argc,char**argv){if(argc!=2)return 1;
std::ifstream f(argv[1],std::ios::binary);std::vector<unsigned char>b{std::istreambuf_iterator<char>(f),{}};
Image im{};if(!ParseImage(b.data(),&im))return 2;auto r=ResolveTarget(im,kIconThemeTarget);
if(!r.resolved||!r.verified)return 3;printf("%zx",r.sites[1].offset);}
''')
    executable = root / 'probe'
    subprocess.run(['clang++', '-std=c++17', *native_include_flags(native), str(probe),
                    str(native / 'resolver/dart_image.cpp'), str(native / 'resolver/dart_targets.cpp'),
                    '-o', str(executable)], check=True)
    offset = int(subprocess.check_output([str(executable), str(a.snapshot.resolve())]), 16)
    data = a.snapshot.read_bytes()
    original = struct.unpack_from('<4I', data, offset)
    host = 0x10000000 + offset
    join = host + 16
    unchanged = host + 12 + signed((original[3] >> 5) & 0x7ffff, 19) * 4
    trampoline = host + 0x400
    stub = root / 'stub.S'
    stub.write_text('''.data
.balign 8
.global g_icon_theme_unlock
g_icon_theme_unlock: .word 0
.global g_icon_theme_refresh
g_icon_theme_refresh: .word 0
.global g_icon_theme_support_original
g_icon_theme_support_original: .xword 0
.global g_icon_theme_refresh_original
g_icon_theme_refresh_original: .xword 0
.global g_icon_theme_refresh_continue
g_icon_theme_refresh_continue: .xword 0
.section .note.GNU-stack,"",%progbits
''')
    binary = root / 'shim'
    subprocess.run([a.compiler, '-nostdlib', '-static', '-Wl,-e,HyperTweakIconThemeRefreshHook',
                    str(native / 'rules/icon_theme_hook.S'), str(stub), '-o', str(binary)], check=True)
    with binary.open('rb') as stream:
        elf = ELFFile(stream)
        symbols = {s.name: s['st_value'] for s in elf.get_section_by_name('.symtab').iter_symbols()}
        segments = [(s['p_vaddr'], s['p_memsz'], s.data()) for s in elf.iter_segments() if s['p_type'] == 'PT_LOAD']
    shim = symbols['HyperTweakIconThemeRefreshHook']
    # The observed inline backend uses ADRP/ADD/BR through x17. Any native
    # predecessor entering +4 skips ADRP and branches to a tagged Dart value.
    pages = ((shim & ~4095) - (host & ~4095)) // 4096
    assert -(1 << 20) <= pages < (1 << 20)
    patch = [0x90000011 | ((pages & 3) << 29) | (((pages >> 2) & 0x7ffff) << 5),
             0x91000231 | ((shim & 4095) << 10), 0xd61f0220, 0xd503201f]
    relocated = []
    for i, word in enumerate(original):
        if (word & 0xff000010) == 0x54000000:
            target = host + i * 4 + signed((word >> 5) & 0x7ffff, 19) * 4
            word = relative(word, trampoline + i * 4, target, 19, 5)
        elif (word & 0x7e000000) == 0x36000000:
            target = host + i * 4 + signed((word >> 5) & 0x3fff, 14) * 4
            word = relative(word, trampoline + i * 4, target, 14, 5)
        relocated.append(word)
    relocated.append(relative(0x14000000, trampoline + 16, join, 26, 0))

    def run(changed, colors_differ, enabled, patched):
        m = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        mapped = set()
        def put(address, size, blob):
            for page in range(address & ~4095, (address + size + 4095) & ~4095, 4096):
                if page not in mapped:
                    m.mem_map(page, 4096)
                    mapped.add(page)
            m.mem_write(address, blob)
        for address, size, blob in segments:
            put(address, size, blob)
        put(host, 0x1000, words(patch if patched else original))
        put(trampoline, 20, words(relocated))
        put(0x50000000, 0x20000, b'\0' * 0x20000)
        for i in range(31):
            m.reg_write(x(i), 0x7700008001 + i * 16)
        frame = 0x50010000
        m.reg_write(x(29), frame)
        m.reg_write(UC_ARM64_REG_SP, frame - 0x100)
        m.reg_write(UC_ARM64_REG_NZCV, 0xa0000000)
        # Real FP-relative enable values; newer compilers spill a changed flag.
        loads = [w for w in original[:2] if (w & 0xffe00c00) == 0xf8400000]
        assert loads
        for i, word in enumerate(loads):
            displacement = signed((word >> 12) & 511, 9)
            value = (0x7700008021 if changed else 0x7700008031) if len(loads) == 1 else (0x7700008021 if i == 0 or not changed else 0x7700008031)
            m.mem_write(frame + displacement, struct.pack('<Q', value))
        if len(loads) == 1:
            # CMP operands come directly from unboxed old/new color values.
            cmp = original[2]
            m.reg_write(x((cmp >> 5) & 31), 7)
            m.reg_write(x((cmp >> 16) & 31), 11 if colors_differ else 7)
        m.mem_write(symbols['g_icon_theme_refresh'], struct.pack('<I', enabled))
        m.mem_write(symbols['g_icon_theme_refresh_original'], struct.pack('<Q', trampoline))
        m.mem_write(symbols['g_icon_theme_refresh_continue'], struct.pack('<Q', join))
        before = [m.reg_read(x(i)) for i in range(31)]
        stopped = []
        from unicorn import UC_HOOK_CODE
        def stop(machine, address, size, _):
            if address in (join, unchanged):
                stopped.append(address)
                machine.emu_stop()
        m.hook_add(UC_HOOK_CODE, stop)
        m.emu_start(host, 0, count=100)
        assert len(stopped) == 1, 'Native decision did not reach a valid boundary'
        after = [m.reg_read(x(i)) for i in range(31)]
        assert m.reg_read(UC_ARM64_REG_SP) == frame - 0x100
        if enabled:
            assert stopped[0] == join
            assert all(after[i] == before[i] for i in range(31) if i not in (16, 17))
            assert m.reg_read(UC_ARM64_REG_NZCV) == 0xa0000000
        return stopped[0], [v for i, v in enumerate(after) if i not in (16, 17)], m.reg_read(UC_ARM64_REG_NZCV)

    count = 0
    for changed in (False, True):
        for colors_differ in (False, True):
            expected = run(changed, colors_differ, 0, False)
            assert run(changed, colors_differ, 0, True) == expected
            run(changed, colors_differ, 1, True)
            count += 1
    print(f'icon theme host flow: {count} native decisions, passive trampoline parity and active join passed')
