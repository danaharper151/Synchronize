"""
Synchronize — Measurement Stability Audit
==========================================
For each Synchronize ketone label, checks whether other meter readings
were taken within a short window of the same moment — i.e. whether it
was a consistency-check session — and reports the spread of values found.
A spread wider than the researcher's own 0.1 mmol/L threshold flags the
label as taken during a measurement-unstable session, independent of
which calendar day it fell on.

This flags specific compromised SESSIONS rather than excluding entire
days, preserving good readings taken on otherwise-difficult days.

Usage:
    python diagnostics/audit_stability.py --db <path/to/synchronize_db> --mojo <path/to/ketomojo_export.csv>
"""

import argparse
from datetime import datetime
import pandas as pd

import sys
from pathlib import Path as _Path
sys.path.insert(0, str(_Path(__file__).resolve().parent.parent))  # pipeline/
from build_features_v2 import load_app_db as load_synchronize_db, to_epoch_ms

WINDOW_MIN = 15          # look for other meter readings within this many
                          # minutes of the logged label, either direction
SPREAD_THRESHOLD = 0.1   # mmol/L — the researcher's own consistency threshold


def load_mojo_ketones(csv_path: str) -> pd.DataFrame:
    df = pd.read_csv(csv_path, sep=';', skiprows=2, quotechar='"', low_memory=False)
    df.columns = [c.strip().strip('"') for c in df.columns]
    for col in df.select_dtypes(include='object').columns:
        df[col] = df[col].astype(str).str.strip().str.strip('"')
    df = df[df['reading_type'] == 'ketone'].copy()
    df['ts_ms'] = to_epoch_ms(df['reading_timestamp'])
    df['value'] = pd.to_numeric(df['reading_value'], errors='coerce')
    df = df.dropna(subset=['ts_ms', 'value']).sort_values('ts_ms').reset_index(drop=True)
    return df


def run(db_path: str, mojo_csv: str):
    print("=" * 74)
    print("MEASUREMENT STABILITY AUDIT")
    print(f"Window: ±{WINDOW_MIN} min   Flag threshold: spread > {SPREAD_THRESHOLD} mmol/L")
    print("=" * 74)

    app = load_synchronize_db(db_path)
    sync = app.get('ketone_readings', pd.DataFrame())
    mojo = load_mojo_ketones(mojo_csv)

    window_ms = WINDOW_MIN * 60_000
    flagged = []
    clean_count = 0
    no_nearby_count = 0

    for _, s in sync.sort_values('deviceTimestamp').iterrows():
        s_ts = s['deviceTimestamp']
        nearby = mojo[(mojo['ts_ms'] >= s_ts - window_ms) &
                     (mojo['ts_ms'] <= s_ts + window_ms)]
        if nearby.empty:
            no_nearby_count += 1
            continue

        spread = float(nearby['value'].max() - nearby['value'].min())
        dt = datetime.fromtimestamp(s_ts / 1000)

        if spread > SPREAD_THRESHOLD:
            flagged.append({
                'time': dt,
                'context': s.get('readingContext'),
                'logged_value': s['betaHydroxybutyrate'],
                'cluster': nearby[['ts_ms', 'value']].copy(),
                'spread': spread,
            })
        else:
            clean_count += 1

    print(f"\n{clean_count} labels have stable nearby readings "
          f"(spread ≤ {SPREAD_THRESHOLD})")
    print(f"{no_nearby_count} labels had no meter reading within "
          f"±{WINDOW_MIN} min to compare against (single-reading sessions)")
    print(f"{len(flagged)} labels flagged for review:\n")

    for f in flagged:
        print(f"{f['time']:%Y-%m-%d %H:%M}  {str(f['context']):<12} "
              f"logged={f['logged_value']:.1f}  spread={f['spread']:.2f}")
        for _, r in f['cluster'].sort_values('ts_ms').iterrows():
            t = datetime.fromtimestamp(r['ts_ms'] / 1000)
            marker = (" ← logged this one"
                     if abs(r['value'] - f['logged_value']) < 0.001 else "")
            print(f"    {t:%H:%M}  {r['value']:.1f}{marker}")
        print()

    print("=" * 74)


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--db', required=True)
    p.add_argument('--mojo', required=True)
    a = p.parse_args()
    run(a.db, a.mojo)