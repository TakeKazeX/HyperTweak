#!/usr/bin/env python3
"""Exercise production Dock admission/restoration across multiple Dart engines."""
from pathlib import Path
import subprocess
import tempfile
from native_test_paths import native_include_flags

native = Path(__file__).resolve().parents[2] / 'main/cpp/NativeRules'
with tempfile.TemporaryDirectory(prefix='ht-native-dock-context-') as temporary:
    binary = Path(temporary) / 'contexts'
    subprocess.run(['clang++', '-std=c++17', '-g', '-Wall', '-Wextra', '-Werror',
                    '-fsanitize=address,undefined', *native_include_flags(native),
                    str(Path(__file__).with_name('native_dock_context_test.cpp')),
                    str(native / 'resolver/dart_image.cpp'), str(native / 'resolver/dart_targets.cpp'),
                    '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
