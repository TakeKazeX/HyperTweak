#!/usr/bin/env python3
"""Build and run production desktop-icon policy/resolver/rule/record tests with sanitizers."""
from pathlib import Path
import argparse
import subprocess
import tempfile
from native_test_paths import native_include_flags

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('snapshot', type=Path)
parser.add_argument('--comparison', type=Path)
parser.add_argument('--compiler', default='clang++')
args = parser.parse_args()
snapshot = args.snapshot.resolve(strict=True)
native = Path(__file__).resolve().parents[2] / 'main/cpp/NativeRules'
with tempfile.TemporaryDirectory(prefix='ht-icon-test-') as temporary:
    for name in ('icon_scale_target', 'icon_scale_rule', 'icon_scale_policy', 'icon_scale_scope', 'native_rule_settings_record'):
        print(f'Running {name}', flush=True)
        executable = Path(temporary) / name
        sources = [Path(__file__).with_name(name + '_test.cpp')]
        host = name in ('icon_scale_target', 'icon_scale_rule')
        if host:
            sources += [native / 'resolver/dart_image.cpp', native / 'resolver/dart_targets.cpp']
        subprocess.run([args.compiler, '-std=c++17', '-g', '-Wall', '-Wextra', '-Werror',
                        '-fsanitize=address,undefined', *native_include_flags(native),
                        *map(str, sources), '-o', str(executable)], check=True)
        subprocess.run([str(executable)] + ([str(snapshot)] if host else []), check=True)
        if name == 'icon_scale_target' and args.comparison:
            subprocess.run([str(executable), str(args.comparison.resolve(strict=True)), '--inspect'], check=True)
print('Desktop icon target/rule/policy/native record tests passed.')
