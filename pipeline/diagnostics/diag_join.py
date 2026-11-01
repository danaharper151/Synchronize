#!/usr/bin/env python3
"""
diag_join.py — why session HR and step features came back empty.

Usage:
    python diagnostics/diag_join.py --samsung samsung_parsed --db <path/to/synchronize_db>
"""

import argparse
import os
import sqlite3
from pathlib import Path

import pandas as pd

_ap = argparse.ArgumentParser(description='Diagnose empty session HR / step joins.')
_ap.add_argument('--samsung', required=True, type=Path, help='parsed Samsung folder')
_ap.add_argument('--db', required=True, type=Path, help='Synchronize app database copy')
_args = _ap.parse_args()
SAMSUNG = _args.samsung
DB = _args.db
TZ = os.environ.get('SYNCHRONIZE_TZ', 'America/Los_Angeles')


def show(ms, label=''):
    try:
        return f"{pd.Timestamp(int(ms), unit='ms', tz='UTC').tz_convert(TZ):%Y-%m-%d %H:%M}"
    except Exception:
        return f"<unparseable: {ms}>"


print("=" * 70)
print("1. timestamp_ms sanity in the parsed direct streams")
print("=" * 70)
for name in ('exercise', 'step_count', 'sleep_stage'):
    p = SAMSUNG / f'{name}.csv'
    if not p.exists():
        print(f"  {name}: FILE MISSING")
        continue
    d = pd.read_csv(p, index_col=False, low_memory=False)
    print(f"\n  {name}: {len(d):,} rows")
    print(f"    has timestamp_ms : {'timestamp_ms' in d.columns}")
    print(f"    has datetime     : {'datetime' in d.columns}")
    if 'timestamp_ms' in d.columns:
        t = pd.to_numeric(d['timestamp_ms'], errors='coerce')
        print(f"    non-null         : {t.notna().sum():,}/{len(d):,}")
        if t.notna().any():
            print(f"    min -> max       : {show(t.min())}  ->  {show(t.max())}")
            print(f"    raw min/max      : {t.min():.0f} / {t.max():.0f}")
    if 'datetime' in d.columns:
        dt = pd.to_datetime(d['datetime'], errors='coerce')
        print(f"    datetime range   : {dt.min()}  ->  {dt.max()}")

print("\n" + "=" * 70)
print("2. exercise: app vs Samsung start-time offsets")
print("=" * 70)
s = pd.read_csv(SAMSUNG / 'exercise.csv', index_col=False, low_memory=False)
st = pd.to_numeric(s.get('timestamp_ms'), errors='coerce').dropna()

conn = sqlite3.connect(str(DB))
a = pd.read_sql('SELECT startTimestamp, durationMinutes, sessionType, isHiit '
                'FROM exercise_sessions ORDER BY startTimestamp', conn)
conn.close()

print(f"  app sessions: {len(a)}   samsung sessions: {len(st)}")
if len(st):
    print(f"\n  {'app session':<20} {'type':<16} {'nearest samsung':<20} {'offset':>10}")
    print("  " + "-" * 68)
    for _, r in a.head(12).iterrows():
        t = int(r['startTimestamp'])
        diff = (st - t).abs()
        j = diff.idxmin()
        off = diff.min() / 60000
        print(f"  {show(t):<20} {str(r['sessionType'])[:15]:<16} "
              f"{show(st.loc[j]):<20} {off:>8.0f} m")

    offs = [((st - int(t)).abs().min()) / 60000 for t in a['startTimestamp']]
    offs = pd.Series(offs)
    print(f"\n  offset stats (minutes): min {offs.min():.0f}  "
          f"median {offs.median():.0f}  max {offs.max():.0f}")
    print(f"  within 15 min: {(offs <= 15).sum()}/{len(offs)}")
    print(f"  within 60 min: {(offs <= 60).sum()}/{len(offs)}")

print("\n" + "=" * 70)
print("3. step_count: are the values actually steps?")
print("=" * 70)
sc = pd.read_csv(SAMSUNG / 'step_count.csv', index_col=False, low_memory=False)
cands = [c for c in sc.columns
         if 'count' in c.lower() or 'step' in c.lower()]
print(f"  candidate columns: {cands}")
for c in cands[:6]:
    v = pd.to_numeric(sc[c], errors='coerce')
    if v.notna().sum():
        print(f"    {c[:46]:<48} n={v.notna().sum():>6} "
              f"{v.min():g}..{v.max():g}  sum={v.sum():,.0f}")

if 'timestamp_ms' in sc.columns:
    t = pd.to_numeric(sc['timestamp_ms'], errors='coerce')
    inwin = ((t >= 1785999600000) & (t < 1788591600000)).sum()
    print(f"\n  rows with timestamp_ms inside 08-02..09-01: {inwin:,}/{len(sc):,}")