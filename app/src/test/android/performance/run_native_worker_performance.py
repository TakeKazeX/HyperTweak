#!/usr/bin/env python3
"""Compare saved and current production EventWorker on Android/Bionic."""
import argparse, hashlib, json, os, subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--window-ms',type=int,default=3200)
p.add_argument('--before',type=Path,required=True)
p.add_argument('--output',type=Path,required=True)
p.add_argument('--compiler',type=Path,required=True)
a=p.parse_args()
root=Path(__file__).resolve().parents[5]
out=a.output.resolve()
if out.is_relative_to(root): p.error('output must be outside repository')
out.mkdir(parents=True,exist_ok=True)
source=Path(__file__).with_name('native_worker_performance.cpp')
current=root/'app/src/main/cpp/NativeRules/runtime'
records={}
def run(*cmd):return subprocess.run(list(map(str,cmd)),check=True,capture_output=True,text=True).stdout
for name,path in [('a',a.before.resolve()),('b',current)]:
    binary=out/name
    run(a.compiler,'--target=aarch64-linux-android35','-std=c++17','-O2','-Wall','-Wextra','-Werror','-pthread','-static-libstdc++','-I',path,source,path/'dart_state_worker.cpp','-o',binary)
    records[name]={'worker_sha256':hashlib.sha256((path/'dart_state_worker.cpp').read_bytes()).hexdigest(),'binary_sha256':hashlib.sha256(binary.read_bytes()).hexdigest()}
    run('adb','push',binary,'/data/local/tmp/ht-worker-perf-'+name)
try:
    for i,name in enumerate(['a','b','b','a']):
        text=run('adb','shell','/data/local/tmp/ht-worker-perf-'+name,a.window_ms)
        (out/f'{i}-{name}.csv').write_text('phase,wall_ms,calls,cpu_ns,recovery_ns\n'+text)
        print(name,text.strip(),flush=True)
finally:
    run('adb','shell','rm','-f','/data/local/tmp/ht-worker-perf-a','/data/local/tmp/ht-worker-perf-b')
(out/'manifest.json').write_text(json.dumps(records,indent=2))
