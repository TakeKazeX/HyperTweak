#!/usr/bin/env python3
"""Render the unshrunk production Duo drawable offscreen; never installs or operates UI."""
import argparse
import hashlib
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import uuid


def run(args, **kwargs):
    result = subprocess.run([str(v) for v in args], capture_output=True, text=True, **kwargs)
    if result.returncode:
        raise RuntimeError((result.stdout + result.stderr)[-16000:])
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--module-apk', type=Path, required=True, help='assembleDebug output, not an R8 release')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    root = Path(__file__).resolve().parents[5]
    if output == root or root in output.parents:
        raise SystemExit('Keep render artifacts outside the repository')
    output.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
    android = sorted((sdk / 'platforms').glob('android-37*/android.jar'))[-1]
    classes = output / 'classes'; classes.mkdir(exist_ok=True)
    dex = output / 'dex'; dex.mkdir(exist_ok=True)
    run([shutil.which('javac'), '--release', '17', '-cp', android, '-d', classes, *Path(__file__).parent.glob('*.java')])
    run([sdk / 'build-tools/37.0.0/d8', '--min-api', '36', '--lib', android, '--output', dex, *classes.rglob('*.class')])
    adb = ['adb', '-s', args.serial]
    remote = '/data/local/tmp/ht-duo-render-' + uuid.uuid4().hex
    run(adb + ['shell', 'mkdir', remote])
    try:
        run(adb + ['push', dex / 'classes.dex', remote + '/classes.dex'])
        run(adb + ['push', args.module_apk.resolve(), remote + '/module.apk'])
        command = 'CLASSPATH=' + shlex.quote(remote + '/classes.dex') + ' ' + shlex.join(
            ['/system/bin/app_process', '/', 'DuoRenderProbe', remote + '/module.apk', remote + '/duo-render.png'])
        result = run(adb + ['shell', command], timeout=45)
        run(adb + ['pull', remote + '/duo-render.png', output / 'duo-render.png'])
        run(adb + ['pull', remote + '/duo-render.png.motion.png', output / 'duo-power-motion.png'])
        run(adb + ['pull', remote + '/duo-render.png.dots.png', output / 'duo-dot-motion.png'])
        run(adb + ['pull', remote + '/duo-render.png.network.png', output / 'duo-network-motion.png'])
        run(adb + ['pull', remote + '/duo-render.png.network.png.transport.png', output / 'duo-transport-motion.png'])
        run(adb + ['pull', remote + '/duo-render.png.network.png.transport.png.composite.png', output / 'duo-transport-composite.png'])
        run(adb + ['pull', remote + '/duo-render.png.network.png.stacked.png', output / 'duo-stacked-endpoint.png'])
        run(adb + ['pull', remote + '/duo-render.png.network.png.others.png', output / 'duo-other-status-icons.png'])
        run(adb + ['pull', remote + '/duo-render.png.network.png.left.png', output / 'duo-left-icons.png'])
        digest = hashlib.sha256(args.module_apk.read_bytes()).hexdigest()
        (output / 'result.txt').write_text('DEBUG_APK_SHA256=' + digest + '\n' + result + '\n')
        print(result)
        print(output / 'duo-render.png')
    finally:
        run(adb + ['shell', 'rm', '-rf', remote])


if __name__ == '__main__':
    main()
