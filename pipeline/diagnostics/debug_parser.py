#!/usr/bin/env python3
"""
debug_parser.py — does a Samsung stream CSV's pointer column match the JSON
files actually on disk?

Prints the first few pointer values from a stream's index CSV next to the
first few binned-data filenames under jsons/<stream>/, so a mismatch between
what the CSV claims and what the export contains is visible at a glance.

Usage:
    python diagnostics/debug_parser.py --export <path/to/samsunghealth_export>
    python diagnostics/debug_parser.py --export <...> --stream com.samsung.health.hrv
"""

import argparse
from pathlib import Path

import pandas as pd


def run(export: Path, stream: str) -> None:
    # What the CSV points to
    csv = next(export.glob(f"{stream}.*.csv"))
    df = pd.read_csv(csv, skiprows=1, low_memory=False)
    print("CSV columns:", list(df.columns)[:6])
    pointers = df['binning_data'].dropna().tolist()[:3]
    print("\nPointers from CSV:")
    for p in pointers:
        print(f"  raw:  {repr(p)}")
        print(f"  uuid: {repr(p.split('.')[0])}")

    # What's actually on disk
    folder = export / "jsons" / stream
    files = [f for f in folder.rglob('*') if f.is_file()][:3]
    print(f"\nFiles on disk ({sum(1 for f in folder.rglob('*') if f.is_file())} total):")
    for f in files:
        print(f"  name: {repr(f.name)}")
        print(f"  uuid: {repr(f.name.split('.')[0])}")


if __name__ == '__main__':
    ap = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    ap.add_argument('--export', required=True, type=Path,
                    help='Samsung Health export folder')
    ap.add_argument('--stream', default='com.samsung.health.hrv',
                    help='stream name prefix (default: %(default)s)')
    a = ap.parse_args()
    run(a.export, a.stream)
