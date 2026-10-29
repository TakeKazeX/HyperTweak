#!/usr/bin/env python3
"""Run the production grid resolver and bridge state logic against an external snapshot."""
from pathlib import Path
import argparse
import subprocess
import tempfile
from native_test_paths import native_include_flags
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('snapshot',type=Path)
args=parser.parse_args()
native=Path(__file__).resolve().parents[2]/'main/cpp/NativeRules'
with tempfile.TemporaryDirectory(prefix='ht-grid-') as folder:
    binary=Path(folder)/'grid'
    subprocess.run(['clang++','-std=c++17','-g','-Wall','-Wextra','-Werror','-fsanitize=address,undefined',
                    *native_include_flags(native),str(Path(__file__).with_name('desktop_grid_rule_test.cpp')),
                    str(native/'resolver/dart_image.cpp'),str(native/'resolver/dart_targets.cpp'),'-o',str(binary)],check=True)
    subprocess.run([str(binary),str(args.snapshot.resolve(strict=True))],check=True)
