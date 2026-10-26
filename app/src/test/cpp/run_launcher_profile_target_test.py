#!/usr/bin/env python3
"""Run the production structural resolver against mapped launcher ELF fixtures."""
import os
from native_test_paths import native_include_flags
import pathlib
import platform
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[4]
NATIVE = ROOT / 'app/src/main/cpp/NativeRules'


def main():
    if len(sys.argv) < 2:
        raise SystemExit('usage: run_launcher_profile_target_test.py <libapp_launcher.so> [...]')
    with tempfile.TemporaryDirectory(prefix='launcher-profile-test-') as temporary:
        directory = pathlib.Path(temporary)
        includes = []
        if platform.system() == 'Darwin':
            # macOS lacks elf.h. Use the installed NDK's unchanged ELF declarations;
            # substitute only fixed-width integer typedefs for Linux include dependencies.
            sdk = pathlib.Path(os.environ.get('ANDROID_HOME', str(pathlib.Path.home() / 'Library/Android/sdk')))
            roots = list((sdk / 'ndk').glob('*/toolchains/llvm/prebuilt/*/sysroot/usr/include'))
            if not roots:
                raise SystemExit('Install an Android NDK or set ANDROID_HOME to its SDK')
            headers = sorted(roots)[-1]
            types = '#include <stdint.h>\n' + '\n'.join(
                f'typedef {signed}int{bits}_t __{prefix}{bits};'
                for bits in (8, 16, 32, 64) for signed, prefix in (('u', 'u'), ('', 's'))
            ) + '\n'
            elf = (headers / 'linux/elf-em.h').read_text() + (headers / 'linux/elf.h').read_text()
            elf = '\n'.join(line for line in elf.splitlines() if not line.startswith('#include'))
            (directory / 'elf.h').write_text(types + elf)
            includes = ['-I', str(directory)]
        executable = directory / 'launcher_profile_target_test'
        subprocess.run([
            'clang++', '-std=c++17', '-O2', '-Wall', '-Wextra', '-Werror',
            *includes, *native_include_flags(NATIVE), str(pathlib.Path(__file__).with_name('launcher_profile_target_test.cpp')),
            str(NATIVE / 'resolver/runtime_profile_resolver.cpp'), '-o', str(executable),
        ], check=True)
        for fixture in sys.argv[1:]:
            subprocess.run([str(executable), str(pathlib.Path(fixture).resolve())], check=True)


if __name__ == '__main__':
    main()
