#!/usr/bin/env python3
"""Execute the production Dart state observer and validate task-card exit and ABI."""
import argparse
import pathlib
import struct
import subprocess
import tempfile

from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_INTR
from unicorn.arm64_const import *


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("compiler")
    args = parser.parse_args()
    source = pathlib.Path(__file__).resolve().parents[2] / "main/cpp/NativeRules/runtime/dart_state_hooks.S"
    with tempfile.TemporaryDirectory(prefix="ht-dart-state-abi-") as folder:
        folder = pathlib.Path(folder)
        # All external symbols are actual observer storage; no production branch is stubbed.
        declarations = {
            "g_dart_state_owner_epoch": (8, 5),
            "g_dart_state_publish_event_fd": (4, 3),
            "g_dart_state_publish_pending": (4, 0),
            "g_drawer_state_observation": (8, 0),
            "g_overview_state_observation": (8, 22),
            "g_editing_state_observation": (8, 21),
        }
        for group in ("drawer", "overview_enter", "overview_exit", "editing"):
            declarations[f"g_dart_{group}_active_count"] = (4, 0)
            declarations[f"g_dart_{group}_retiring"] = (4, 0)
        for name in ("g_overview_dart_enter_count", "g_overview_dart_exit_count",
                     "g_editing_dart_observe_count"):
            declarations[name] = (4, 0)
        globals_file = folder / "globals.S"
        globals_file.write_text(".data\n" + "\n".join(
            f".global {name}\n.balign {size}\n{name}: "
            f"{'.xword' if size == 8 else '.word'} {value}"
            for name, (size, value) in declarations.items()
        ) + '\n.section .note.GNU-stack,"",%progbits\n')
        binary = folder / "observer"
        subprocess.run([args.compiler, "--target=aarch64-linux-android35", "-nostdlib", "-static",
                        "-Wl,-e,MiuiHomeHyosDartHomeSurfacePublishedEpilogueHook",
                        str(source), str(globals_file), "-o", str(binary)], check=True)
        with binary.open("rb") as stream:
            elf = ELFFile(stream)
            symbols = {s.name: s["st_value"] for s in elf.get_section_by_name(".symtab").iter_symbols()}
            segments = [(s["p_vaddr"], s["p_memsz"], s.data())
                        for s in elf.iter_segments() if s["p_type"] == "PT_LOAD"]

        def run(source_name="typefrom_home", value=False, readable=True, cid=94,
                source_pointer=None, retiring=False, overview=22, editing=21):
            uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
            pages = set()
            for address, size, data in segments:
                for page in range(address & ~4095, (address + size + 4095) & ~4095, 4096):
                    if page not in pages:
                        uc.mem_map(page, 4096)
                        pages.add(page)
                uc.mem_write(address, data)
            for address in (0x40000000, 0x50000000, 0x53000000, 0x55000000, 0x60000000, 0x70000000):
                uc.mem_map(address, 0x10000)
            reg = lambda i: UC_ARM64_REG_X0 + i if i < 29 else (UC_ARM64_REG_X29 if i == 29 else UC_ARM64_REG_X30)
            fp, stop, null = 0x60001000, 0x40000000, 0x55000001
            for i in range(31):
                uc.reg_write(reg(i), 0x10101000 + i)
            uc.reg_write(UC_ARM64_REG_SP, 0x70001000)
            uc.reg_write(UC_ARM64_REG_X29, fp)
            uc.reg_write(UC_ARM64_REG_X22, null)
            uc.reg_write(UC_ARM64_REG_X28, 0)
            uc.reg_write(UC_ARM64_REG_NZCV, 0xA0000000)
            for i in range(32):
                uc.reg_write(UC_ARM64_REG_Q0 + i, (i + 1) * 0x10101010101010101010101010101)
            uc.mem_write(fp, struct.pack("<QQ", 0x60003000, stop))
            uc.mem_write(fp - 16, struct.pack("<QQ", 0x50000001, 0x12345))
            uc.mem_write(0x50000010, struct.pack("<I", 0x12345 if readable else 0x12346))
            decision = null + (0x20 if value is True else 0x30 if value is False else 0x40)
            uc.mem_write(0x5000001C, struct.pack("<I", decision))
            uc.mem_write(0x50000020, struct.pack("<I", source_pointer if source_pointer is not None else 0x53000001))
            uc.mem_write(0x53000000, struct.pack("<Q", cid << 12))
            uc.mem_write(0x53000008, struct.pack("<Q", len(source_name) * 2))
            uc.mem_write(0x53000010, source_name.encode())
            uc.mem_write(symbols["g_overview_state_observation"], struct.pack("<Q", overview))
            uc.mem_write(symbols["g_editing_state_observation"], struct.pack("<Q", editing))
            uc.mem_write(symbols["g_dart_editing_retiring"], struct.pack("<I", retiring))
            before = [uc.reg_read(reg(i)) for i in range(31)]
            vector_before = [uc.reg_read(UC_ARM64_REG_Q0 + i) for i in range(32)]
            wakes = []

            def syscall(machine, number, _):
                assert machine.reg_read(UC_ARM64_REG_X8) == 64
                assert machine.reg_read(UC_ARM64_REG_X0) == 3
                assert machine.reg_read(UC_ARM64_REG_X2) == 8
                assert bytes(machine.mem_read(machine.reg_read(UC_ARM64_REG_X1), 8)) == struct.pack("<Q", 1)
                wakes.append(number)
                machine.reg_write(UC_ARM64_REG_X0, 8)

            uc.hook_add(UC_HOOK_INTR, syscall)
            uc.emu_start(symbols["MiuiHomeHyosDartHomeSurfacePublishedEpilogueHook"], stop, count=10000)
            assert uc.reg_read(UC_ARM64_REG_PC) == stop
            for i in range(31):
                expected = {0: null, 15: fp + 16, 29: 0x60003000, 30: stop}.get(i, before[i])
                assert uc.reg_read(reg(i)) == expected, f"register x{i} corrupted"
            assert uc.reg_read(UC_ARM64_REG_SP) == 0x70001000
            assert uc.reg_read(UC_ARM64_REG_NZCV) == 0xA0000000
            assert [uc.reg_read(UC_ARM64_REG_Q0 + i) for i in range(32)] == vector_before
            read = lambda name, size=8: int.from_bytes(uc.mem_read(symbols[name], size), "little")
            assert read("g_dart_editing_active_count", 4) == 0
            return read("g_overview_state_observation"), read("g_editing_state_observation"), read("g_dart_state_publish_pending", 4), len(wakes)

        # Crucial regression: Home-child is already false, but Overview exit still publishes.
        assert run() == (21, 21, 2, 1)
        assert run(editing=22) == (21, 21, 6, 2)
        assert run(overview=21) == (21, 21, 0, 0)
        assert run(overview=26) == (26, 21, 0, 0)  # A newer owner cannot be overwritten.
        assert run(source_name="typefrom_home_sub", value=True) == (22, 22, 4, 1)
        assert run(source_name="typefrom_home_sub", value=False) == (22, 21, 0, 0)
        for options in (dict(value=True), dict(value=None), dict(readable=False),
                        dict(source_name="typefrom_homX"), dict(source_name="other_source"),
                        dict(cid=95), dict(source_pointer=0x55000001), dict(source_pointer=2),
                        dict(retiring=True)):
            assert run(**options) == (22, 21, 0, 0), options
        print("Dart state ABI: 15 scenarios passed; owner fencing, independent exit publication, registers and NZCV preserved")


if __name__ == "__main__":
    main()
