#!/usr/bin/env python3
"""Execute the production eventfd worker under Bionic, without changing launcher state."""
from pathlib import Path
import argparse,subprocess,tempfile,uuid
from native_test_paths import native_include_flags
p=argparse.ArgumentParser(description=__doc__);p.add_argument('compiler');args=p.parse_args()
native=Path(__file__).resolve().parents[2]/'main/cpp/NativeRules'
remote='/data/local/tmp/ht-rule-worker-'+uuid.uuid4().hex
with tempfile.TemporaryDirectory(prefix='ht-rule-worker-') as tmp:
 binary=Path(tmp)/'worker'
 subprocess.run([args.compiler,'--target=aarch64-linux-android35','-std=c++17','-static-libstdc++',
                 *native_include_flags(native),str(Path(__file__).with_name('native_rule_runtime_test.cpp')),
                 str(native/'runtime/native_rule_runtime.cpp'),'-Wl,--wrap=dlopen','-Wl,--wrap=dlclose','-o',str(binary)],check=True)
 try:
  subprocess.run(['adb','push',str(binary),remote],check=True,stdout=subprocess.DEVNULL)
  subprocess.run(['adb','shell','chmod','700',remote],check=True)
  subprocess.run(['adb','shell',remote],check=True)
 finally:subprocess.run(['adb','shell','rm','-f',remote],check=False)
