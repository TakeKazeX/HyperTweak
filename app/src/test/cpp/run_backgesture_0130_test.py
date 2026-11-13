#!/usr/bin/env python3
"""Exercise 0.13.0 native contracts and negative controls against verified launcher ELF inputs."""
import os
from pathlib import Path
import platform
import subprocess
import sys
import tempfile
from native_test_paths import native_include_flags

ROOT = Path(__file__).resolve().parents[4]
NATIVE = ROOT / 'app/src/main/cpp/NativeRules'
TESTS = Path(__file__).resolve().parent

def main():
    if len(sys.argv) != 3:
        raise SystemExit('usage: run_backgesture_0130_test.py <libapp_launcher.so> <libapp.so>')
    with tempfile.TemporaryDirectory(prefix='ht-backgesture-0130-') as folder:
        folder = Path(folder)
        includes = []
        if platform.system() == 'Darwin':
            sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
            headers = sorted((sdk / 'ndk').glob('*/toolchains/llvm/prebuilt/*/sysroot/usr/include'))[-1]
            types = '#include <stdint.h>\n#define NT_GNU_BUILD_ID 3\n' + '\n'.join(
                f'typedef {signed}int{bits}_t __{prefix}{bits};'
                for bits in (8, 16, 32, 64) for signed, prefix in (('u', 'u'), ('', 's'))
            ) + '\n'
            elf = (headers / 'linux/elf-em.h').read_text() + (headers / 'linux/elf.h').read_text()
            (folder / 'elf.h').write_text(types + '\n'.join(
                line for line in elf.splitlines() if not line.startswith('#include')))
            includes = ['-I', str(folder)]
        common = ['clang++', '-std=c++17', '-O1', '-g', '-Wall', '-Wextra', '-Werror',
                  '-fsanitize=address,undefined', *includes, *native_include_flags(NATIVE)]
        for name in ['contextual_motion_gate_test', 'xiaoai_visibility_resolver_test']:
            executable = folder / name
            subprocess.run([*common, str(TESTS / (name + '.cpp')), '-o', str(executable)], check=True)
            subprocess.run([str(executable)], check=True)
        executable = folder / 'contracts'
        subprocess.run([*common, str(TESTS / 'backgesture_0130_contract_test.cpp'),
                        str(NATIVE / 'resolver/runtime_profile_resolver.cpp'),
                        str(NATIVE / 'resolver/dart_runtime_resolver.cpp'), '-o', str(executable)], check=True)
        subprocess.run([str(executable), *map(str, map(Path.resolve, map(Path, sys.argv[1:])))], check=True)

if __name__ == '__main__':
    main()
