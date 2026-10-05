#!/usr/bin/env python3
"""Run native launcher regression checks against a verified, external libapp.so."""
import argparse
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('snapshot', type=Path)
parser.add_argument('--compiler', default='clang++')
args = parser.parse_args()
snapshot = args.snapshot.resolve(strict=True)
repository = Path(__file__).resolve().parents[4]
native = repository / 'app/src/main/cpp/NativeRules'
sources = ['dart_rule_support.cpp', 'dart_resolution_cache.cpp', 'dart_image.cpp',
           'dart_targets.cpp', 'clear_button_rule.cpp', 'folder_columns_rule.cpp', 'assistant_widget_rule.cpp']
with tempfile.TemporaryDirectory(prefix='hypertweak-native-cache-') as output:
    binary = Path(output) / 'native-rule-cache-test'
    subprocess.run([args.compiler, '-std=c++17', '-O2', '-Wall', '-Wextra', '-Werror',
                    '-DHYPERTWEAK_NATIVE_VERSION=1', '-I', str(native), str(Path(__file__).with_name('dart_rule_cache_test.cpp')),
                    *[str(native / name) for name in sources], '-o', str(binary)], check=True)
    subprocess.run([str(binary), str(snapshot)], check=True)
    events = Path(output) / 'native-rule-events-test'
    subprocess.run([args.compiler, '-std=c++17', '-O2', '-Wall', '-Wextra', '-Werror',
                    '-I', str(native), str(Path(__file__).with_name('native_rule_events_test.cpp')),
                    '-o', str(events)], check=True)
    subprocess.run([str(events)], check=True)
