"""
Diagnose timestamp alignment and column naming across sources.
Answers three questions at once:
  1. Are Dexcom timestamps offset from app timestamps?
  2. What columns do the Samsung streams actually have?
  3. Why are sleep features empty?

Usage:
    python diagnostics/diagnose_alignment.py --db <db> --samsung <folder> --dexcom <csv>
"""

import argparse
import sqlite3
from pathlib import Path
from datetime import datetime

import pandas as pd
import numpy as np



def show(label, ms):
    if pd.isna(ms):
        return f"{label}: (none)"
    return f"{label}: {datetime.fromtimestamp(ms/1000):%Y-%m-%d %H:%M:%S}"


def run(db, samsung_dir, dexcom_csv):
    print("=" * 66)
    print("TIMESTAMP ALIGNMENT DIAGNOSTIC")
    print("=" * 66)

    # ── App labels ────────────────────────────────────────────
    conn = sqlite3.connect(db)
    k = pd.read_sql_query(
        "SELECT deviceTimestamp, readingContext, betaHydroxybutyrate "
        "FROM ketone_readings ORDER BY deviceTimestamp", conn)
    conn.close()

    print("\nAPP KETONE LABELS")
    print("-" * 66)
    print(f"  count: {len(k)}")
    print(f"  {show('earliest', k['deviceTimestamp'].min())}")
    print(f"  {show('latest  ', k['deviceTimestamp'].max())}")
    print("\n  All labels:")
    for _, r in k.iterrows():
        t = datetime.fromtimestamp(r['deviceTimestamp']/1000)
        print(f"    {t:%m-%d %H:%M}  {str(r['readingContext']):<12} "
              f"{r['betaHydroxybutyrate']:.2f}")

    label_min = k['deviceTimestamp'].min()
    label_max = k['deviceTimestamp'].max()

    # ── Dexcom ────────────────────────────────────────────────
    print("\n" + "=" * 66)
    print("DEXCOM")
    print("-" * 66)
    d = pd.read_csv(dexcom_csv, low_memory=False)
    ts_col = next((c for c in d.columns if 'Timestamp' in c), None)
    type_col = next((c for c in d.columns if c.strip() == 'Event Type'), None)
    val_col = next((c for c in d.columns if 'Glucose Value' in c), None)

    print(f"  timestamp column: {ts_col}")
    print(f"  raw sample values:")
    for v in d[ts_col].dropna().head(3):
        print(f"    {repr(v)}")

    egv = d[d[type_col].astype(str).str.strip() == 'EGV'] if type_col else d

    # Interpretation A: naive string treated as UTC (current behaviour)
    as_utc = pd.to_datetime(egv[ts_col], errors='coerce').astype('int64') // 1_000_000
    # Interpretation B: naive string treated as local time
    as_local = pd.to_datetime(egv[ts_col], errors='coerce') \
                 .dt.tz_localize('America/Los_Angeles', ambiguous='NaT',
                                 nonexistent='NaT') \
                 .dt.tz_convert('UTC').astype('int64') // 1_000_000

    print(f"\n  EGV rows: {len(egv)}")
    print(f"\n  If parsed as UTC (current):")
    print(f"    {show('earliest', as_utc.min())}")
    print(f"    {show('latest  ', as_utc.max())}")
    overlap_utc = ((as_utc >= label_min - 7200000) &
                   (as_utc <= label_max)).sum()
    print(f"    readings within label range: {overlap_utc}")

    print(f"\n  If parsed as local time (America/Los_Angeles):")
    print(f"    {show('earliest', as_local.min())}")
    print(f"    {show('latest  ', as_local.max())}")
    overlap_local = ((as_local >= label_min - 7200000) &
                     (as_local <= label_max)).sum()
    print(f"    readings within label range: {overlap_local}")

    if overlap_local > overlap_utc:
        print("\n  → Dexcom timestamps are LOCAL time. Loader needs fixing.")
    elif overlap_utc > 0:
        print("\n  → Dexcom timestamps align as UTC. Something else is wrong.")
    else:
        print("\n  → No overlap either way — check export date range.")

    # ── Samsung streams ───────────────────────────────────────
    print("\n" + "=" * 66)
    print("SAMSUNG STREAMS")
    print("=" * 66)

    sd = Path(samsung_dir)
    for f in sorted(sd.glob('*.csv')):
        df = pd.read_csv(f, low_memory=False, nrows=2000)
        name = f.stem
        print(f"\n  {name}  ({len(pd.read_csv(f, low_memory=False)):,} rows)")

        # Columns
        cols = [c for c in df.columns
                if not c.startswith('Unnamed')][:12]
        print(f"    columns: {', '.join(cols)}")

        # Timestamp range
        ts = None
        if 'timestamp_ms' in df.columns:
            ts = pd.to_numeric(df['timestamp_ms'], errors='coerce')
        elif 'start_time' in df.columns:
            num = pd.to_numeric(df['start_time'], errors='coerce')
            if num.notna().mean() > 0.5:
                ts = num
            else:
                ts = pd.to_datetime(df['start_time'], errors='coerce') \
                       .astype('int64') // 1_000_000

        if ts is not None and ts.notna().any():
            print(f"    {show('earliest', ts.min())}")
            print(f"    {show('latest  ', ts.max())}")
            inrange = ((ts >= label_min - 7200000) & (ts <= label_max)).sum()
            print(f"    rows within label range: {inrange}")
        else:
            print("    no usable timestamp column")

        # Sleep stage codes
        if name == 'sleep_stage' and 'stage' in df.columns:
            print(f"    stage codes present: "
                  f"{sorted(df['stage'].dropna().unique().tolist())}")

    print("\n" + "=" * 66)


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--db', required=True)
    p.add_argument('--samsung', required=True)
    p.add_argument('--dexcom', required=True)
    a = p.parse_args()
    run(a.db, a.samsung, a.dexcom)