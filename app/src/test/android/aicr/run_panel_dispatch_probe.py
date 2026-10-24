#!/usr/bin/env python3
"""Run production presentation/request code with real AICR classes in an isolated ART VM.

Requires a built Release APK. Does not install APKs, attach to AICR, or operate device UI.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import uuid


def run(args, **kwargs):
    result = subprocess.run([str(item) for item in args], text=True, capture_output=True, **kwargs)
    if result.returncode:
        detail = result.stdout + result.stderr
        if len(detail) > 18000:
            detail = detail[:14000] + "\n[diagnostic truncated]\n" + detail[-4000:]
        raise RuntimeError(detail)
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("host_apk", type=Path)
    parser.add_argument("--module-apk", type=Path, required=True)
    parser.add_argument("--remote-apk", default="/product/priv-app/MIUIAICR/MIUIAICR.apk")
    parser.add_argument("--browsers", nargs="+", required=True, help="Current default first, then other installed browsers")
    parser.add_argument("--output", type=Path, required=True, help="Directory outside the repository")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[5]
    output = args.output.resolve()
    if output == root or root in output.parents:
        raise SystemExit("Probe artifacts must be outside the repository")
    output.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
    android = sorted((sdk / "platforms").glob("android-37*/android.jar"))[-1]
    kotlin = sorted((Path.home() / ".gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib").glob("*/*/*.jar"))[-1]
    classes = output / "classes"
    dex = output / "dex"
    classes.mkdir(exist_ok=True)
    dex.mkdir(exist_ok=True)
    java = Path(__file__).with_name("AicrPanelDispatchProbe.java")
    run([shutil.which("javac"), "--release", "17", "-cp", str(android), "-d", classes, java])
    files = list(classes.rglob("*.class"))
    run([sdk / "build-tools/37.0.0/d8", "--min-api", "36", "--lib", android, "--classpath", kotlin, "--output", dex, *files])
    digest = hashlib.sha256(args.host_apk.read_bytes()).hexdigest()
    remote_digest = run(["adb", "shell", "sha256sum", args.remote_apk]).split()[0]
    if digest != remote_digest:
        raise SystemExit("Local and installed AICR APKs differ")
    remote = "/data/local/tmp/ht-aicr-probe-" + uuid.uuid4().hex
    run(["adb", "shell", "mkdir", remote])
    try:
        run(["adb", "push", dex / "classes.dex", remote + "/classes.dex"])
        run(["adb", "push", args.module_apk.resolve(), remote + "/module.apk"])
        command = "CLASSPATH=" + shlex.quote(remote + "/classes.dex:" + args.remote_apk) + " " + shlex.join(
            ["/system/bin/app_process", "/", "AicrPanelDispatchProbe", remote + "/module.apk", *args.browsers])
        result = run(["adb", "shell", command], timeout=45)
        (output / "result.txt").write_text("AICR_SHA256=" + digest + "\nMODULE_SHA256=" + hashlib.sha256(args.module_apk.read_bytes()).hexdigest() + "\n" + result + "\n")
        print(result)
    finally:
        run(["adb", "shell", "rm", "-rf", remote])


if __name__ == "__main__":
    main()
