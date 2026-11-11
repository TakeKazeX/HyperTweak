#!/usr/bin/env python3
"""Check the production Dock glass gates and idle-frame shim offline."""
import argparse
from pathlib import Path
import struct
import subprocess
import tempfile

from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM
from unicorn.arm64_const import (
    UC_ARM64_REG_FPCR, UC_ARM64_REG_FPSR, UC_ARM64_REG_NZCV, UC_ARM64_REG_PC,
    UC_ARM64_REG_Q0, UC_ARM64_REG_SP, UC_ARM64_REG_X0, UC_ARM64_REG_X29, UC_ARM64_REG_X30,
)


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('compiler')
args = parser.parse_args()
native = Path(__file__).resolve().parents[4] / 'app/src/main/cpp/NativeRules'
entry_prefix = 'HyperTweakNativeDock'
prefix = 'g_native_dock_'
base, stack, frame, device, thread = 0x100000, 0x210000, 0x212000, 0x220001, 0x221000
null, heap = 0x123400001, 1
true, false = null + 0x20, null + 0x30
returns = ['glass_skip', 'glass_continue', 'glass_stub', 'glass_entry', 'glass_return', 'reassemble']
destinations = {name: 0x300000 + 4 * index for index, name in enumerate(returns)}
data_names = ['ready', 'requested', 'recovery', 'owner', 'glass_field', 'mock_root'] + returns
# NativeDockGlassFrames (dock_native_rule.cpp); the host layout is the 9239 one.
FRAMES = {'resume_continue': 0x00, 'resume_list': 0x08, 'resume_result': 0x10, 'resume_length': 0x18,
          'constraints_arg': 0x20, 'max_height': 0x28, 'insets_entry': 0x30, 'insets_frame': 0x38,
          'insets_left': 0x40, 'insets_top': 0x48, 'insets_right': 0x50, 'insets_bottom': 0x58,
          'insets_cid': 0x60, 'padding_return': 0x68, 'padding_frame': 0x70, 'padding_slot': 0x78,
          'padding_cid': 0x80, 'height': 0x88, 'margin': 0x90, 'bottom': 0x98, 'radius': 0xa0,
          'limit': 0xa8, 'stage': 0xb0, 'thread': 0xb8, 'observed': 0xc0, 'build_frame': 0xc8,
          'build_child': 0xd0, 'controller': 0xd8, 'center_call': 0xe0, 'center_return': 0xe8,
          'center_item': 0xf0, 'center_slot': 0xf8, 'center_field': 0x100, 'center_continue': 0x108,
          'wrap_position': 0x110, 'wrap_entry': 0x118, 'title_slot': 0x120, 'title_field': 0x128,
          'title_pool': 0x130, 'title_continue': 0x138, 'center_dx': 0x140, 'center_dy': 0x148,
          'fixed_field': 0x150, 'adaptive': 0x158, 'items_slot': 0x160, 'items_field': 0x168,
          'max_width': 0x170, 'columns': 0x178, 'observed_width': 0x180, 'observed_items': 0x188,
          'observed_first': 0x190, 'observed_last': 0x198, 'observed_built': 0x1a0,
          'static_table': 0x1a8, 'sentinel_pool': 0x1b0, 'flag_static': 0x1b8, 'flag_field': 0x1c0,
          'config_static': 0x1c8, 'config_rx': 0x1d0, 'rx_value': 0x1d8, 'cell_field': 0x1e0,
          'alt_cell_field': 0x1e8, 'cell_extra': 0x1f0, 'observed_cell': 0x1f8}
FRAMES_SIZE = 0x200
resume, insets_entry, padding_return = 0x300040, 0x300060, 0x300070
center_target, center_return, center_continue = 0x300080, 0x300090, 0x3000a0
wrap_entry, title_continue = 0x3000b0, 0x3000c0
pool, center_call, title_pool, center_pair = 0x500000, 0xf3860, 0x732f8, 0x9abc
hotseat = resume - 16  # return address of the Hotseat background call
constraints, list_object, insets_object = 0x230001, 0x238001, 0x234001
insets_cid, padding_cid = 0x169a, 0x1f11


def bits(value):
    return struct.unpack('<Q', struct.pack('<d', value))[0]


def slot(offset):
    return offset & 0xffffffffffffffff


frames_default = {
    'resume_continue': resume, 'resume_list': slot(-0x28), 'resume_result': slot(-0x38), 'resume_length': 0xb,
    'constraints_arg': 0x10, 'max_height': 0x1f, 'insets_entry': insets_entry, 'insets_frame': 0x20,
    'insets_left': slot(-0x20), 'insets_top': slot(-0x18), 'insets_right': slot(-0x10), 'insets_bottom': slot(-8),
    'insets_cid': insets_cid, 'padding_return': padding_return, 'padding_frame': 0x20, 'padding_slot': slot(-8),
    'padding_cid': padding_cid, 'height': bits(72.0), 'margin': bits(16.0), 'bottom': bits(8.0),
    'radius': bits(28.0), 'limit': bits(4096.0), 'stage': 0, 'thread': 0, 'observed': 0,
    'build_frame': 0xe0, 'build_child': slot(-0x68), 'controller': slot(-0x10), 'center_call': center_call,
    'center_return': center_return, 'center_item': slot(-0x60), 'center_slot': slot(-0x90),
    'center_field': 0xf, 'center_continue': center_continue, 'wrap_position': slot(-0xa8),
    'wrap_entry': wrap_entry, 'title_slot': slot(-0x78), 'title_field': 0x2b, 'title_pool': title_pool,
    'title_continue': title_continue, 'center_dx': 0, 'center_dy': 0, 'fixed_field': 0x27,
    'adaptive': 0, 'items_slot': slot(-8), 'items_field': 0x13, 'max_width': 0xf, 'columns': bits(4.0),
    'observed_width': 0, 'observed_items': 0, 'observed_first': 0, 'observed_last': 0, 'observed_built': 0,
    'static_table': 0x78, 'sentinel_pool': 0x40, 'flag_static': 0x27c0, 'flag_field': 0xe7,
    'config_static': 0x2828, 'config_rx': 0x2f, 'rx_value': 0x13, 'cell_field': 0x77, 'alt_cell_field': 0x2b,
    'cell_extra': bits(20.0), 'observed_cell': 0,
}
# Compressed references decompress above 4 GiB (heap base 1); objects read through one live here.
far = 0x100240000
gpr = [UC_ARM64_REG_X0 + index for index in range(29)] + [UC_ARM64_REG_X29, UC_ARM64_REG_X30]
simd = [UC_ARM64_REG_Q0 + index for index in range(32)]
flags = {UC_ARM64_REG_NZCV: 0xA0000000, UC_ARM64_REG_FPCR: 0x400000, UC_ARM64_REG_FPSR: 0x10}


def pack(value):
    return struct.pack('<Q', value)


def machine(segments, symbols, owner, flag=False, frames=None, area=98.0, width=400.0, **settings):
    vm = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    vm.mem_map(base, (max(va + size for va, size, _ in segments) + 4095) & ~4095)
    for va, _, data in segments:
        vm.mem_write(base + va, data)
    vm.mem_map(0x200000, 0x40000)
    vm.mem_map(0x300000, 0x1000)
    vm.mem_map(pool, 0x100000)
    vm.mem_map(far, 0x1000)
    vm.mem_write(pool + center_call, pack(center_target) + pack(center_pair))
    vm.mem_write(pool + title_pool, pack(0x123470001))
    data = dict(destinations, owner=owner, ready=0, requested=0, recovery=0, mock_root=0, glass_field=0xd7)
    data.update(settings)
    for name, value in data.items():
        vm.mem_write(base + symbols[prefix + name], pack(value))
    for index, reg in enumerate(gpr):
        vm.reg_write(reg, 0x400000 + index * 32)
    for index, reg in enumerate(simd):
        vm.reg_write(reg, (index + 1) * 0x123456789012345678901234567)
    for index, value in {0: device, 15: frame - 0x80, 22: null, 26: thread, 27: pool,
                         28: heap, 29: frame, 30: destinations['glass_return']}.items():
        vm.reg_write(gpr[index], value)
    vm.reg_write(UC_ARM64_REG_SP, stack)
    for reg, value in flags.items():
        vm.reg_write(reg, value)
    vm.mem_write(device + data['glass_field'], struct.pack('<I', (true if flag else false) & 0xffffffff))
    vm.mem_write(frame - 0x18, pack(0x123450001))
    vm.mem_write(frame, pack(0x212100) + pack(0x300100) + pack(constraints))
    vm.mem_write(constraints + 0xf, pack(bits(width)))
    vm.mem_write(constraints + 0x1f, pack(bits(area)))
    vm.mem_write(frame - 0x28, pack(list_object))
    vm.mem_write(list_object + 0xb, struct.pack('<I', 0x5678))
    vm.mem_write(insets_object - 1, struct.pack('<I', insets_cid << 12))
    for name, value in dict(frames_default, **(frames or {})).items():
        vm.mem_write(base + symbols['g_native_dock_glass_frames'] + FRAMES[name], pack(value))
    return vm


def field(vm, symbols, name):
    return struct.unpack('<Q', bytes(vm.mem_read(base + symbols['g_native_dock_glass_frames'] + FRAMES[name], 8)))[0]


def run(vm, symbols, entry, destination, outputs, stores=(), vectors=None, keep_flags=True, scratch=()):
    before = {reg: vm.reg_read(reg) for reg in gpr + simd}
    vm.emu_start(base + symbols[entry_prefix + entry], destination, count=220)
    assert vm.reg_read(UC_ARM64_REG_PC) == destination, (entry, hex(vm.reg_read(UC_ARM64_REG_PC)))
    expected = {gpr[index]: value for index, value in outputs.items()}
    expected.update({simd[index]: value for index, value in (vectors or {}).items()})
    for reg, value in before.items():
        if reg != gpr[16] and reg not in scratch:  # IP scratch for the LSPosed branch veneer.
            assert vm.reg_read(reg) == expected.get(reg, value), (entry, reg)
    # A call target may clobber NZCV; FPCR/FPSR and every unlisted register stay.
    for reg, value in flags.items():
        assert keep_flags is not True or reg != UC_ARM64_REG_NZCV or vm.reg_read(reg) == value, (entry, reg)
        assert reg == UC_ARM64_REG_NZCV or vm.reg_read(reg) == value, (entry, reg)
    for address, value in stores:
        assert bytes(vm.mem_read(address, 8)) == pack(value), (entry, address)
    assert vm.reg_read(UC_ARM64_REG_SP) == stack


with tempfile.TemporaryDirectory(prefix='ht-native-dock-abi-') as directory:
    root = Path(directory)
    stub = root / 'stub.S'
    stub.write_text('.bss\n.p2align 3\n' + '\n'.join(
        f'.global {prefix}{name}\n.hidden {prefix}{name}\n{prefix}{name}: .zero 8'
        for name in data_names
    ) + f"""
.global g_native_dock_glass_frames
.hidden g_native_dock_glass_frames
g_native_dock_glass_frames: .zero {FRAMES_SIZE}
.text
.global HyperTweakNativeDockAtIdle
.hidden HyperTweakNativeDockAtIdle
HyperTweakNativeDockAtIdle:
    mov x1, #0
    mov x8, #0
    movi v0.16b, #0
    movi v31.16b, #0
    msr nzcv, xzr
    msr fpcr, xzr
    msr fpsr, xzr
    adrp x0, g_native_dock_mock_root
    ldr x0, [x0, :lo12:g_native_dock_mock_root]
    ret
""")
    binary = root / 'shim.so'
    subprocess.run([args.compiler, '--target=aarch64-linux-android36', '-shared', '-nostdlib',
                    '-Wl,--no-undefined', str(native / 'rules/dock_native_hook.S'),
                    str(stub), '-o', str(binary)], check=True)
    with binary.open('rb') as stream:
        elf = ELFFile(stream)
        segments = [(s['p_vaddr'], s['p_memsz'], s.data()) for s in elf.iter_segments()
                    if s['p_type'] == 'PT_LOAD']
        symbols = {s.name: s['st_value'] for s in elf.get_section_by_name('.symtab').iter_symbols()}
    for owner in (0, thread, thread + 8):
        for flag in (False, True):
            forced = owner == thread
            vm = machine(segments, symbols, owner, flag)
            show = forced or flag
            run(vm, symbols, 'GlassBranch', destinations['glass_continue' if show else 'glass_skip'],
                {**({} if forced else {1: true if flag else false}), **({3: 0x123450001} if show else {})})
        for caller in (destinations['glass_return'], 0x300110):
            if owner == thread and caller == destinations['glass_return']:
                continue
            vm = machine(segments, symbols, owner)
            vm.reg_write(gpr[30], caller)
            run(vm, symbols, 'GlassFactory', destinations['glass_stub'], {})
    # Selected factory: insets frame with left/top/right/bottom = margin/top/margin/bottom.
    dart_sp = frame - 0x80
    fp = dart_sp - 16
    caller = destinations['glass_return']
    for area, height, top in ((98.0, 72.0, 18.0), (98.0, 0.0, 0.0), (60.0, 72.0, 0.0), (float('inf'), 72.0, 0.0)):
        vm = machine(segments, symbols, thread, frames={'height': bits(height)}, area=area)
        run(vm, symbols, 'GlassFactory', insets_entry,
            {9: 1, 10: constraints, 11: 0x20, 15: fp - 0x20, 29: fp},
            ((fp, frame), (fp + 8, caller), (fp - 0x20, bits(16.0)), (fp - 0x18, bits(top)),
             (fp - 0x10, bits(16.0)), (fp - 8, bits(8.0))),
            {0: bits(area), 1: bits(height), 2: bits(8.0), 3: bits(16.0), 4: bits(top), 5: 0,
             6: bits(400.0)}, keep_flags=False)
        assert field(vm, symbols, 'stage') == 1 and field(vm, symbols, 'thread') == thread
        assert field(vm, symbols, 'observed') == bits(area)
        assert field(vm, symbols, 'observed_width') == bits(400.0) and field(vm, symbols, 'observed_items') == 0
    # Adaptive width: each icon below a full row widens both margins by half a host Dock cell, capped at
    # the center; an empty row keeps one cell. The item list must be a Smi-length list of the child-list
    # class. The cell width mirrors the host's bit-4 bool test: flag ? config.cell + 20 : config.alt.
    context, items, list_cid = 0x23f001, far + 0x101, 0x5f
    table, flag_holder, config_holder = 0x226000, 0x227001, 0x227801
    rx, config, sentinel = far + 0x301, far + 0x401, 0x123480001
    adaptive_scratch = {gpr[12], gpr[13], gpr[14], simd[7], simd[16], simd[17], simd[18]}
    for count, columns, enabled, same_cid, width, margin, expected, host in (
            (3, 4.0, 1, True, 400.0, 16.0, 63.2, {}), (1, 4.0, 1, True, 400.0, 16.0, 157.6, {}),
            (0, 4.0, 1, True, 400.0, 16.0, 157.6, {}), (2, 5.0, 1, True, 400.0, 16.0, 157.6, {}),
            (3, 4.0, 1, True, 400.0, 16.0, 61.0, {'flag': False}),
            (1, 4.0, 1, True, 200.0, 64.0, 100.0, {}),
            (4, 4.0, 1, True, 400.0, 16.0, 16.0, {}), (5, 4.0, 1, True, 400.0, 16.0, 16.0, {}),
            (3, 4.0, 1, False, 400.0, 16.0, 16.0, {}),
            (3, 0.0, 1, True, 400.0, 16.0, 16.0, {}), (3, 4.0, 0, True, 400.0, 16.0, 16.0, {}),
            (3, 4.0, 1, True, float('inf'), 16.0, 16.0, {}), (3, 4.0, 1, True, float('nan'), 16.0, 16.0, {}),
            (3, 4.0, 1, True, -1.0, 16.0, 16.0, {}),
            (3, 4.0, 1, True, 400.0, 16.0, 16.0, {'table': 0}),
            (3, 4.0, 1, True, 400.0, 16.0, 16.0, {'flag_holder': null}),
            (3, 4.0, 1, True, 400.0, 16.0, 16.0, {'config_holder': sentinel}),
            (3, 4.0, 1, True, 400.0, 16.0, 16.0, {'config': sentinel}),
            (3, 4.0, 1, True, 400.0, 16.0, 16.0, {'config': null}),
            (3, 4.0, 1, True, 80.0, 16.0, 16.0, {})):
        vm = machine(segments, symbols, thread, width=width,
                     frames={'adaptive': enabled, 'columns': bits(columns), 'margin': bits(margin),
                             'observed_built': 7})
        vm.mem_write(thread + 0x78, pack(host.get('table', table)))
        vm.mem_write(pool + 0x40, pack(sentinel))
        vm.mem_write(table + 0x27c0, pack(host.get('flag_holder', flag_holder)))
        vm.mem_write(table + 0x2828, pack(host.get('config_holder', config_holder)))
        vm.mem_write(flag_holder + 0xe7, struct.pack('<I', (true if host.get('flag', True) else false) & 0xffffffff))
        vm.mem_write(config_holder + 0x2f, struct.pack('<I', rx & 0xffffffff))
        vm.mem_write(rx + 0x13, struct.pack('<I', host.get('config', config) & 0xffffffff))
        vm.mem_write(config + 0x77, pack(bits(74.4)))
        vm.mem_write(config + 0x2b, pack(bits(90.0)))
        vm.mem_write(frame - 8, pack(context))
        vm.mem_write(context + 0x13, struct.pack('<I', items & 0xffffffff))
        vm.mem_write(list_object - 1, struct.pack('<I', list_cid << 12))
        vm.mem_write(items - 1, struct.pack('<I', (list_cid if same_cid else list_cid + 1) << 12))
        vm.mem_write(items + 0xb, struct.pack('<I', count << 1))
        run(vm, symbols, 'GlassFactory', insets_entry,
            {9: 1, 10: constraints, 11: 0x20, 15: fp - 0x20, 29: fp},
            ((fp - 0x18, bits(18.0)), (fp - 8, bits(8.0))),
            {0: bits(98.0), 1: bits(72.0), 2: bits(8.0), 4: bits(18.0), 5: 0,
             6: bits(width)}, keep_flags=False, scratch=adaptive_scratch | {simd[3]})
        margin_out = struct.unpack('<d', struct.pack('<Q', vm.reg_read(simd[3]) & (2**64 - 1)))[0]
        assert abs(margin_out - expected) < 1e-9, (count, columns, width, host, margin_out)
        counted = enabled and columns and same_cid and 0 < width < 4096
        assert field(vm, symbols, 'observed_items') == (count if counted else 0), (count, columns, width)
        assert field(vm, symbols, 'observed_built') == 0 and field(vm, symbols, 'stage') == 1
    # Return gate, stage 1: Padding frame returning to the Hotseat, glass frame returning into it.
    vm = machine(segments, symbols, thread, frames={'stage': 1, 'thread': thread})
    vm.reg_write(gpr[0], insets_object)
    padding_fp = dart_sp - 16
    glass_fp = padding_fp - 0x20 - 16
    run(vm, symbols, 'GlassReturn', destinations['glass_entry'],
        {9: 2, 10: insets_cid, 11: 0x20, 15: glass_fp - 0x20, 17: 1, 29: glass_fp, 30: padding_return},
        ((padding_fp, frame), (padding_fp + 8, resume - 16), (padding_fp - 8, insets_object),
         (padding_fp - 0x10, null), (padding_fp - 0x20, null), (glass_fp, padding_fp),
         (glass_fp + 8, padding_return), (glass_fp - 8, null), (glass_fp - 0x20, bits(28.0))),
        {0: bits(28.0)}, keep_flags=False)
    assert field(vm, symbols, 'stage') == 2
    # Everything else resumes the displaced Hotseat code and clears the stage.
    stock = 0x23c001
    for stage, owner_thread, result, keep in ((0, 0, stock, True), (1, thread + 8, insets_object, False),
                                              (1, thread, 0x2468, False), (1, thread, null, False),
                                              (1, thread, stock, False), (2, thread, insets_object, False),
                                              (2, thread, null, False), (3, thread, stock, False),
                                              (4, thread, stock, False)):
        vm = machine(segments, symbols, thread, frames={'stage': stage, 'thread': owner_thread})
        vm.mem_write(stock - 1, struct.pack('<I', padding_cid << 12))
        vm.reg_write(gpr[0], result)
        expected = {0: list_object, 1: 0x5678, 2: result, 9: 0xb, 17: stage}
        if stage == 1 and owner_thread == thread and result == stock:
            expected[10] = insets_cid  # read for the class-id comparison
        if stage == 2 and owner_thread == thread and result != null:
            expected[10] = padding_cid
        run(vm, symbols, 'GlassReturn', resume, expected, ((frame - 0x38, result),), keep_flags=keep)
        assert field(vm, symbols, 'stage') == (stage if owner_thread != thread else 0)
    # Return gate, stage 2: the Padding opens an item builder frame returning to the Hotseat and calls
    # the Dock center getter on the captured controller through the switchable-call pool pair.
    controller, padding_object = 0x23a801, 0x23c001
    vm = machine(segments, symbols, thread, frames={'stage': 2, 'thread': thread})
    vm.mem_write(padding_object - 1, struct.pack('<I', padding_cid << 12))
    vm.mem_write(frame - 0x10, pack(controller))
    vm.reg_write(gpr[0], padding_object)
    build_fp = dart_sp - 16
    run(vm, symbols, 'GlassReturn', center_target,
        {0: controller, 4: 0, 5: center_pair, 9: center_call, 10: controller, 11: 0xe0,
         15: build_fp - 0xe0, 17: center_target, 29: build_fp, 30: center_return},
        ((build_fp, frame), (build_fp + 8, hotseat), (build_fp - 0x68, padding_object),
         (build_fp - 0xe0, controller)) + tuple((build_fp - offset, null) for offset in range(8, 0xe0, 8)
                                                if offset != 0x68),
        keep_flags=False)
    assert field(vm, symbols, 'stage') == 3
    # Center gate. The owned frame takes the center as the wrapper position.
    offset_object, item, wrapper = 0x23e001, 0x23a001, 0x236001
    def center_machine(stage, owner_thread, saved):
        vm = machine(segments, symbols, thread, frames={'stage': stage, 'thread': owner_thread})
        vm.mem_write(frame + 8, pack(saved))
        vm.mem_write(frame - 0x68, pack(padding_object))
        vm.mem_write(frame - 0x60, pack(item))
        vm.mem_write(item + 0xf, struct.pack('<I', 0x1111))
        vm.mem_write(offset_object + 7, pack(bits(540.0)) + pack(bits(2300.0)))
        return vm
    vm = center_machine(3, thread, hotseat)
    vm.reg_write(gpr[3], offset_object)
    run(vm, symbols, 'GlassCenter', wrap_entry, {9: 4, 10: hotseat, 17: 3},
        ((frame - 0xa8, offset_object),), {0: bits(2300.0)}, keep_flags=False)
    assert field(vm, symbols, 'stage') == 4
    assert (field(vm, symbols, 'center_dx'), field(vm, symbols, 'center_dy')) == (bits(540.0), bits(2300.0))
    # No center object: return the plain Padding to the Hotseat and drop the frame.
    for result in (null, 0x2468):
        vm = center_machine(3, thread, hotseat)
        vm.reg_write(gpr[3], result)
        run(vm, symbols, 'GlassCenter', hotseat,
            {0: padding_object, 9: slot(-0x68), 10: hotseat, 15: frame + 16, 17: 3, 29: 0x212100,
             30: hotseat}, keep_flags=False)
        assert field(vm, symbols, 'stage') == 0
    # Host frames, foreign threads and idle stages replay the displaced center code.
    for stage, owner_thread, saved, outputs in ((0, thread, hotseat, {}),
                                                 (3, thread + 8, hotseat, {9: thread + 8}),
                                                 (3, thread, 0x300100, {10: hotseat}),
                                                 (4, thread, hotseat, {})):
        vm = center_machine(stage, owner_thread, saved)
        vm.reg_write(gpr[3], offset_object)
        run(vm, symbols, 'GlassCenter', center_continue,
            {0: 0x1111 + (heap << 32), 2: item, 9: 0xf, 17: stage, **{k: v for k, v in outputs.items() if k != 9}},
            ((frame - 0x90, offset_object),), keep_flags=False)
        assert field(vm, symbols, 'stage') == stage
    # Wrap gate: the title store always replays; the owned frame returns the wrapper.
    title = 0x123460001
    def wrap_machine(stage, saved):
        vm = machine(segments, symbols, thread, frames={'stage': stage, 'thread': thread})
        vm.mem_write(frame + 8, pack(saved))
        vm.mem_write(frame - 0x78, pack(title))
        vm.reg_write(gpr[2], wrapper)
        vm.mem_write(wrapper + 0x27, struct.pack('<I', true & 0xffffffff))
        return vm
    vm = wrap_machine(4, hotseat)
    run(vm, symbols, 'GlassWrap', hotseat,
        {0: wrapper, 1: title, 2: wrapper, 9: 0x27, 10: false, 15: frame + 16, 17: 4,
         29: 0x212100, 30: hotseat}, keep_flags=False)
    assert bytes(vm.mem_read(wrapper + 0x2b, 4)) == struct.pack('<I', title & 0xffffffff)
    assert bytes(vm.mem_read(wrapper + 0x27, 4)) == struct.pack('<I', false & 0xffffffff)
    assert field(vm, symbols, 'stage') == 0
    for stage, saved, outputs in ((0, hotseat, {}), (4, 0x300100, {10: hotseat}), (3, hotseat, {})):
        vm = wrap_machine(stage, saved)
        run(vm, symbols, 'GlassWrap', title_continue,
            {1: 0x123470001, 2: wrapper, 9: title_pool, 17: stage, **outputs}, keep_flags=False)
        assert bytes(vm.mem_read(wrapper + 0x2b, 4)) == struct.pack('<I', title & 0xffffffff)
        assert bytes(vm.mem_read(wrapper + 0x27, 4)) == struct.pack('<I', true & 0xffffffff)
        assert field(vm, symbols, 'stage') == stage
        assert field(vm, symbols, 'observed_built') == 0
    # Host items record their unlock center x (diagnostics): first and last since the factory.
    position = far + 0x201
    vm = wrap_machine(0, hotseat)
    vm.mem_write(wrapper + 0xf, struct.pack('<I', position & 0xffffffff))
    for index, dx in enumerate((150.0, 250.0, 350.0)):
        vm.mem_write(position + 7, pack(bits(dx)))
        vm.reg_write(gpr[2], wrapper)
        before = vm.reg_read(gpr[10])
        run(vm, symbols, 'GlassWrap', title_continue,
            {1: 0x123470001, 2: wrapper, 9: title_pool, 10: index + 1, 17: 0}, keep_flags=False)
        assert (field(vm, symbols, 'observed_first'), field(vm, symbols, 'observed_last'),
                field(vm, symbols, 'observed_built')) == (bits(150.0), bits(dx), index + 1)
    for ready in (0, 1):
        for requested in (0, 1):
            for owner in (0, thread, thread + 8):
                for mock_root in (0, 0x123410001):
                    vm = machine(segments, symbols, owner, ready=ready, requested=requested,
                                 mock_root=mock_root)
                    changes = ready and (owner != thread if requested else owner != 0)
                    tail = changes and mock_root
                    run(vm, symbols, 'Idle', destinations['reassemble'] if tail else 0x300100,
                        {0: null, 15: frame + 16, 29: 0x212100, 30: 0x300100,
                         **({1: mock_root} if tail else {})})
    root_element = 0x123410001
    for owner, requested in ((0, 0), (thread, 1)):
        vm = machine(segments, symbols, owner, ready=1, requested=requested, recovery=1, mock_root=root_element)
        run(vm, symbols, 'Idle', destinations['reassemble'],
            {0: null, 1: root_element, 15: frame + 16, 29: 0x212100, 30: 0x300100})
print('PASS: Dock glass branch, scoped factory insets, adaptive width, Padding and unlock-wrapper return '
      'chain, center and wrap gates, item diagnostics, resume, idle recovery and ABI preservation')
