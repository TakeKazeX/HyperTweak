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
    parser.add_argument("--probe", choices=("PanelGlyphProbe", "WifiActivityReloadProbe", "StackedArtworkReloadProbe", "KeyguardAirplaneProbe", "AirplaneEndpointProbe", "VolteOs3FlowProbe"), default="PanelGlyphProbe")
    parser.add_argument("--large-font", action="store_true", help="Use a cropped 48px label in KeyguardAirplaneProbe")
    parser.add_argument("--host-apk", type=Path, help="Extracted OS3 SystemUI for VolteOs3FlowProbe")
    args = parser.parse_args()
    if (args.probe == "VolteOs3FlowProbe") != (args.host_apk is not None):
        parser.error("--host-apk is required for VolteOs3FlowProbe and only applies to that probe")
    if args.large_font and args.probe != "KeyguardAirplaneProbe":
        parser.error("--large-font requires --probe KeyguardAirplaneProbe")
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
        Path(__file__).with_name("StackedArtworkReloadProbe.java"),
        Path(__file__).with_name("KeyguardAirplaneProbe.java"),
        Path(__file__).with_name("AirplaneEndpointProbe.java"),
        Path(__file__).with_name("VolteOs3FlowProbe.java"))
    run(d8, "--lib", android, "--output", dex, *classes.glob("*.class"))
    remote = f"/data/local/tmp/ht-panel-probe-{uuid.uuid4().hex}"
    try:
        run("adb", "push", args.apk.resolve(), remote + ".apk")
        run("adb", "push", dex / "classes.dex", remote + ".dex")
        probe_args = ["large-font"] if args.large_font else []
        if args.host_apk:
            run("adb", "push", args.host_apk.resolve(), remote + "-host.apk")
            probe_args.append(remote + "-host.apk")
        result = run("adb", "shell", "CLASSPATH=" + remote + ".dex", "app_process",
                     "/system/bin", args.probe, remote + ".apk", *probe_args)
        (output / "result.txt").write_text(result)
        print(result, end="")
    finally:
        run("adb", "shell", "rm", "-f", remote + ".apk", remote + ".dex", remote + "-host.apk")


if __name__ == "__main__":
    main()
