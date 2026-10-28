#!/usr/bin/env python3
"""Run native launcher regression checks against a verified, external libapp.so."""
from native_test_paths import native_include_flags
import argparse
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('snapshot', type=Path)
parser.add_argument('--compiler', default='clang++')
parser.add_argument('--size-optimized', action='store_true', help='Exercise the production release Oz/ThinLTO policy')
args = parser.parse_args()
optimization = ['-Oz', '-flto=thin'] if args.size_optimized else ['-O2']
snapshot = args.snapshot.resolve(strict=True)
repository = Path(__file__).resolve().parents[4]
native = repository / 'app/src/main/cpp/NativeRules'
sources = ['runtime/dart_rule_support.cpp', 'runtime/dart_resolution_cache.cpp', 'resolver/dart_image.cpp',
           'resolver/dart_targets.cpp', 'rules/clear_button_rule.cpp', 'rules/folder_columns_rule.cpp', 'rules/assistant_widget_rule.cpp',
           'rules/widget_stack_rule.cpp']
with tempfile.TemporaryDirectory(prefix='hypertweak-native-cache-') as output:
    binary = Path(output) / 'native-rule-cache-test'
    subprocess.run([args.compiler, '-std=c++17', *optimization, '-Wall', '-Wextra', '-Werror',
                    '-DHYPERTWEAK_NATIVE_VERSION=1', *native_include_flags(native), str(Path(__file__).with_name('dart_rule_cache_test.cpp')),
                    *[str(native / name) for name in sources], '-o', str(binary)], check=True)
    subprocess.run([str(binary), str(snapshot)], check=True)
    events = Path(output) / 'native-rule-events-test'
    subprocess.run([args.compiler, '-std=c++17', *optimization, '-Wall', '-Wextra', '-Werror',
                    *native_include_flags(native), str(Path(__file__).with_name('native_rule_events_test.cpp')),
                    '-o', str(events)], check=True)
    subprocess.run([str(events)], check=True)
