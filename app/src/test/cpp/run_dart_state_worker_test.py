#!/usr/bin/env python3
"""Exercise the production eventfd worker and remap fence on host or Android/Bionic."""
from native_test_paths import native_include_flags
import argparse
from pathlib import Path
import subprocess
import tempfile
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--android-compiler', type=Path)
args = parser.parse_args()
if sys.platform != "linux" and not args.android_compiler:
    parser.error("eventfd requires Linux; use --android-compiler <NDK-clang++> to run on Android/Bionic")
tests = Path(__file__).resolve().parent
native = tests.parents[1] / 'main/cpp/NativeRules'
with tempfile.TemporaryDirectory(prefix='hypertweak-state-worker-') as tmp:
    binary = Path(tmp) / 'dart-state-worker-test'
    flags = ['--target=aarch64-linux-android35', '-static-libstdc++'] if args.android_compiler else ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
    subprocess.run([str(args.android_compiler or 'clang++'), '-std=c++17', '-O1', '-g', '-Wall', '-Wextra', '-Werror', '-pthread', *flags, *native_include_flags(native), str(tests / 'dart_state_worker_test.cpp'), str(native / 'runtime/dart_state_worker.cpp'), str(native / 'runtime/dart_observer_retirement.cpp'), '-o', str(binary)], check=True)
    if args.android_compiler:
        remote = '/data/local/tmp/hypertweak-dart-state-worker-test'
        try:
            subprocess.run(['adb', 'push', str(binary), remote], check=True)
            subprocess.run(['adb', 'shell', 'chmod', '700', remote], check=True)
            subprocess.run(['adb', 'shell', remote], check=True, timeout=30)
        finally:
            subprocess.run(['adb', 'shell', 'rm', '-f', remote], check=True)
    else:
        subprocess.run([str(binary)], check=True, timeout=30)
