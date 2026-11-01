"""
Synchronize — Keto-Mojo Reading List
======================================
Prints every reading from the Keto-Mojo export, grouped by day and sorted
chronologically, for direct comparison against the meter's own on-device
scroll history.

Usage:
    python diagnostics/list_mojo_readings.py --mojo <path/to/ketomojo_export.csv>
    python diagnostics/list_mojo_readings.py --mojo "..." --date 2026-08-06
"""

import argparse
from datetime import datetime

import pandas as pd

import sys
from pathlib import Path as _Path
sys.path.insert(0, str(_Path(__file__).resolve().parent.parent))  # pipeline/
from build_features_v2 import to_epoch_ms


def load_all_readings(csv_path: str) -> pd.DataFrame:
    df = pd.read_csv(csv_path, sep=';', skiprows=2, quotechar='"', low_memory=False)
    df.columns = [c.strip().strip('"') for c in df.columns]
    for col in df.select_dtypes(include='object').columns:
        df[col] = df[col].astype(str).str.strip().str.strip('"')

    df['ts_ms'] = to_epoch_ms(df['reading_timestamp'])
    df['value'] = pd.to_numeric(df['reading_value'], errors='coerce')
    df = df.dropna(subset=['ts_ms'])
    df = df.sort_values('ts_ms').reset_index(drop=True)
    return df


def run(csv_path: str, date_filter: str):
    df = load_all_readings(csv_path)
    print(f"Loaded {len(df)} total readings from {csv_path}\n")

    # Group by LOCAL calendar date, matching the meter's own on-device display
    df['local_dt'] = df['ts_ms'].apply(lambda ms: datetime.fromtimestamp(ms / 1000))
    df['date_key'] = df['local_dt'].dt.strftime('%Y-%m-%d')

    if date_filter:
        df = df[df['date_key'] == date_filter]
        if df.empty:
            print(f"No readings found for {date_filter}")
            return

    for date_key, group in df.groupby('date_key'):
        print(f"{date_key}  ({len(group)} readings)")
        for _, r in group.iterrows():
            t = r['local_dt'].strftime('%H:%M')
            rtype = r.get('reading_type', '?')
            unit = r.get('reading_unit', '')
            val = r['value']
            print(f"  {t}  {rtype:<10} {val:>6.1f} {unit}")
        print()


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--mojo', required=True)
    p.add_argument('--date', default=None,
                   help='Optional: show only one date, e.g. 2026-08-06')
    a = p.parse_args()
    run(a.mojo, a.date)