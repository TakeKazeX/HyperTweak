#!/usr/bin/env python3
"""Run detached View/Canvas checks against an unshrunk debug APK; never install it."""
import argparse
import os
from pathlib import Path
import subprocess
import uuid


def run(*args):
    result = subprocess.run([str(arg) for arg in args], text=True, capture_output=True)
    if result.returncode:
        print(result.stdout + result.stderr)
        result.check_returncode()
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="assembleDebug output (reflection names required)")
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME"))
    parser.add_argument("--output", type=Path, required=True, help="directory outside the repository")
    parser.add_argument("--probe", choices=("PanelGlyphProbe", "WifiActivityReloadProbe", "StackedArtworkReloadProbe"), default="PanelGlyphProbe")
    args = parser.parse_args()
    sdk = args.sdk or Path.home() / "Library/Android/sdk"
    sdk = Path(sdk)
    android = sdk / "platforms/android-37.0/android.jar"
    d8 = sdk / "build-tools/37.0.0/d8"
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    classes = output / "classes"
    dex = output / "dex"
    classes.mkdir(exist_ok=True)
    dex.mkdir(exist_ok=True)
    run("javac", "--release", "17", "-cp", android, "-d", classes,
        Path(__file__).with_name("PanelGlyphProbe.java"),
        Path(__file__).with_name("WifiActivityReloadProbe.java"),
        Path(__file__).with_name("StackedArtworkReloadProbe.java"))
    run(d8, "--lib", android, "--output", dex, *classes.glob("*.class"))
    remote = f"/data/local/tmp/ht-panel-probe-{uuid.uuid4().hex}"
    try:
        run("adb", "push", args.apk.resolve(), remote + ".apk")
        run("adb", "push", dex / "classes.dex", remote + ".dex")
        result = run("adb", "shell", "CLASSPATH=" + remote + ".dex", "app_process",
                     "/system/bin", args.probe, remote + ".apk")
        (output / "result.txt").write_text(result)
        print(result, end="")
    finally:
        run("adb", "shell", "rm", "-f", remote + ".apk", remote + ".dex")


if __name__ == "__main__":
    main()
