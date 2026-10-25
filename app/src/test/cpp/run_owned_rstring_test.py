#!/usr/bin/env python3
"""Owned strings now belong to the production transport; its full send path covers failure cleanup."""
from pathlib import Path
import runpy

runpy.run_path(str(Path(__file__).with_name("run_broadcast_transport_test.py")), run_name="__main__")
