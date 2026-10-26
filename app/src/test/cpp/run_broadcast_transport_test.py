#!/usr/bin/env python3
"""Compile the production transport and publication owners with consuming/failing host APIs."""
from native_test_paths import native_include_flags
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2] / "main/cpp/NativeRules"
with tempfile.TemporaryDirectory(prefix="ht-transport-") as temp:
    for directory, name in (("transport", "broadcast_transport"), ("runtime", "dart_state_publisher")):
        binary = Path(temp) / name
        subprocess.run(["clang++", "-std=c++17", "-Wall", "-Wextra", "-Werror", "-fsanitize=address,undefined",
                        *native_include_flags(root), str(root / directory / f"{name}.cpp"),
                        str(Path(__file__).with_name(f"{name}_test.cpp")), "-o", str(binary)], check=True)
        subprocess.run([str(binary)], check=True)
        print(f"production {name} sanitizer regressions passed")
