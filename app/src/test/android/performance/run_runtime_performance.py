#!/usr/bin/env python3
"""Compile/run a detached ART benchmark against unshrunk production debug classes/APK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import uuid
import zipfile


def run(*args):
    result = subprocess.run([str(x) for x in args], text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--guard-diagnostics", action="store_true")
    parser.add_argument("--logging-check", action="store_true")
    parser.add_argument("--probe", choices=["RuntimePerformanceProbe", "RenderingPerformanceProbe", "ClockPerformanceProbe", "LifecyclePerformanceProbe"], default="RuntimePerformanceProbe")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[5]
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home()/"Library/Android/sdk"))
    android = sdk/"platforms/android-37.0/android.jar"
    output = args.output.resolve()
    if output.is_relative_to(root):
        parser.error("--output must be outside the repository")
    output.mkdir(parents=True, exist_ok=True)
    api = next((Path.home()/".gradle/caches/modules-2/files-2.1/io.github.libxposed/api/102.0.0").glob("*/*.aar"))
    with zipfile.ZipFile(api) as archive:
        (output/"api.jar").write_bytes(archive.read("classes.jar"))
    classes = output/"classes"
    dex = output/"dex"
    classes.mkdir(exist_ok=True)
    dex.mkdir(exist_ok=True)
    kotlin = root/"app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
    java = root/"app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
    stdlib = next((Path.home()/".gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib").glob("*/*/*.jar"))
    run("javac", "--release", "17", "-cp", os.pathsep.join(map(str, [android, kotlin, java, stdlib, output/"api.jar"])),
        "-d", classes, Path(__file__).with_name(args.probe+".java"))
    run(sdk/"build-tools/37.0.0/d8", "--lib", android, "--output", dex,
        output/"api.jar", *classes.glob("*.class"))
    (output/"probe-source.java").write_text(Path(__file__).with_name(args.probe+".java").read_text())
    (output/"manifest.json").write_text(json.dumps({
        "apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
        "probe_dex_sha256": hashlib.sha256((dex/"classes.dex").read_bytes()).hexdigest(),
        "guard_diagnostics": args.guard_diagnostics, "logging_check": args.logging_check,
        "repeat": args.repeat, "probe": args.probe,
        "allocation_metric": "art.gc.bytes-allocated (process-wide)",
    }, indent=2))
    remote = "/data/local/tmp/ht-perf-" + uuid.uuid4().hex
    try:
        run("adb", "push", args.apk.resolve(), remote+".apk")
        run("adb", "push", dex/"classes.dex", remote+".dex")
        for i in range(args.repeat):
            classpath = remote+".dex" + (":"+remote+".apk" if args.probe in ("RuntimePerformanceProbe", "LifecyclePerformanceProbe") else "")
            result = run("adb", "shell", "CLASSPATH="+classpath,
                         "app_process", "/system/bin", args.probe,
                         *([remote+".apk"] if args.probe not in ("RuntimePerformanceProbe", "LifecyclePerformanceProbe") else []),
                         *(["logging-check"] if args.logging_check else ["guarded"] if args.guard_diagnostics else []))
            (output/f"run-{i}.csv").write_text(result)
            print(f"saved {output/f'run-{i}.csv'}")
        (output/"battery.txt").write_text(run("adb", "shell", "dumpsys", "battery"))
    finally:
        run("adb", "shell", "rm", "-f", remote+".apk", remote+".dex")


if __name__ == "__main__":
    main()
