#!/usr/bin/env python3
"""Run real OEM transforms against compiled production policy, without installing or operating UI."""
import argparse
import hashlib
import os
from pathlib import Path
import shlex
import subprocess
import uuid


def run(args, **kwargs):
    result = subprocess.run([str(x) for x in args], capture_output=True, text=True, **kwargs)
    if result.returncode:
        raise RuntimeError((result.stdout + result.stderr)[-15000:])
    return result.stdout.strip()


def main():
    p = argparse.ArgumentParser()
    p.add_argument('host_apk', type=Path)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--stdlib', type=Path, required=True, help='Module Kotlin runtime for isolated policy loader')
    p.add_argument('--root', action='store_true')
    p.add_argument('--transform', required=True, help='Verified unique panel combine transform class')
    args = p.parse_args()
    root = Path(__file__).resolve().parents[5]
    output = args.output.resolve()
    if output == root or root in output.parents:
        raise SystemExit('Keep probe artifacts outside repository')
    output.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
    android = sorted((sdk / 'platforms').glob('android-37*/android.jar'))[-1]
    classes, dex, module_dex = output / 'classes', output / 'dex', output / 'policy-dex'
    classes.mkdir(exist_ok=True); dex.mkdir(exist_ok=True); module_dex.mkdir(exist_ok=True)
    run(['javac', '--release', '17', '-cp', android, '-d', classes, Path(__file__).with_name('FingerprintAvoidanceProbe.java')])
    production = root / 'app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/takekazex/hypertweak/hook/rules/systemui/lockscreen'
    policy = list(production.glob('FingerprintAvoidancePolicy*.class'))
    if len(policy) != 2:
        raise SystemExit('Compile production policy first with :app:compileDebugKotlin')
    run([sdk / 'build-tools/37.0.0/d8', '--min-api', '36', '--lib', android, '--output', dex,
         *classes.rglob('*.class')])
    run([sdk / 'build-tools/37.0.0/d8', '--min-api', '36', '--lib', android, '--output', module_dex,
         *policy, args.stdlib])
    host = '/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk'
    digest = hashlib.sha256(args.host_apk.read_bytes()).hexdigest()
    if run(['adb', 'shell', 'sha256sum', host]).split()[0] != digest:
        raise SystemExit('Local/installed SystemUI identity differs')
    remote = '/data/local/tmp/ht-fingerprint-probe-' + uuid.uuid4().hex
    run(['adb', 'shell', 'mkdir', remote])
    try:
        run(['adb', 'push', dex / 'classes.dex', remote + '/classes.dex'])
        run(['adb', 'push', module_dex / 'classes.dex', remote + '/policy.dex'])
        command = 'CLASSPATH=' + shlex.quote(remote + '/classes.dex:' + host) + ' ' + shlex.join(
            ['/system/bin/app_process', '/', 'FingerprintAvoidanceProbe', args.transform, remote + '/policy.dex'])
        result = run(['adb', 'shell', 'su', '-c', command] if args.root else ['adb', 'shell', command], timeout=45)
        (output / 'result.txt').write_text('SYSTEMUI_SHA256=' + digest + '\n' + result + '\n')
        print(result)
    finally:
        run(['adb', 'shell', 'rm', '-rf', remote])


if __name__ == '__main__':
    main()
