#!/usr/bin/env python3
"""Run artifact-free native owner/transport regressions under ASan and UBSan."""
import argparse
from pathlib import Path
import subprocess
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--include-linux-worker", action="store_true", help="Also execute the Linux eventfd worker")
args = parser.parse_args()
if args.include_linux_worker and sys.platform != "linux":
    parser.error("--include-linux-worker requires Linux; use the dedicated Bionic runner on macOS")
tests = ["backgesture_ipc", "backgesture_owner", "backgesture_broadcast",
         "backgesture_status_query", "broadcast_transport"]
if args.include_linux_worker:
    tests.append("dart_state_worker")
for name in tests:
    print(f"Running native regression: {name}", flush=True)
    subprocess.run([sys.executable, str(Path(__file__).with_name(f"run_{name}_test.py"))], check=True, timeout=180)
print(f"All {len(tests)} native regression runners passed.")
