#!/usr/bin/env python3
"""
cgm_range_summary.py

Verifies the glucose range statement in §3.4 against the full Clarity export.

The current text reads: "an observed range of approximately 70-113 mg/dL, with
a single excursion to 130." That figure was recorded mid-collection and needs
checking against the completed record.

Reports the distribution twice: over all EGVs in the training window, and after
excluding the first 12 hours of each sensor session, which is the exclusion the
feature pipeline applies (§3.7.6).

Usage:
    python analysis/cgm_range_summary.py --dexcom <path/to/clarity_export.csv>
"""

from __future__ import annotations

import argparse

import numpy as np
import pandas as pd

WINDOW = ('2026-08-02', '2026-09-01')
WARMUP_H = 12


def load(path: str) -> pd.DataFrame:
    df = pd.read_csv(path, index_col=False, low_memory=False)
    ts = next(c for c in df.columns if 'Timestamp' in c)
    val = next(c for c in df.columns if 'Glucose Value' in c)
    typ = next((c for c in df.columns if c.strip() == 'Event Type'), None)
    tx = next((c for c in df.columns if 'Transmitter ID' in c), None)

    if typ:
        df = df[df[typ].astype(str).str.strip() == 'EGV']

    out = pd.DataFrame({
        'dt': pd.to_datetime(df[ts], errors='coerce'),
        'glucose': pd.to_numeric(df[val], errors='coerce'),
        'tx': df[tx] if tx else 'unknown',
    }).dropna(subset=['dt', 'glucose']).sort_values('dt').reset_index(drop=True)

    out['session_start'] = out.groupby('tx')['dt'].transform('min')
    out['sensor_age_h'] = (out['dt'] - out['session_start']).dt.total_seconds() / 3600
    return out


def describe(g: pd.Series, label: str):
    print(f'\n  {label}   n = {len(g):,}')
    print('  ' + '-' * 58)
    print(f'    min                {g.min():>6.0f} mg/dL')
    print(f'    max                {g.max():>6.0f} mg/dL')
    print(f'    mean               {g.mean():>6.1f}')
    print(f'    median             {g.median():>6.0f}')
    print(f'    SD                 {g.std():>6.1f}')
    print()
    for p in (1, 5, 25, 50, 75, 95, 99, 99.9):
        print(f'    {p:>5}th percentile {np.percentile(g, p):>6.0f}')
    print()
    for t in (55, 70, 100, 113, 120, 130, 140):
        n = int((g > t).sum())
        print(f'    above {t:>3}          {n:>6,}  ({100*n/len(g):.2f}%)')


def run(path: str):
    d = load(path)
    lo, hi = pd.Timestamp(WINDOW[0]), pd.Timestamp(WINDOW[1])
    win = d[(d['dt'] >= lo) & (d['dt'] < hi)]
    kept = win[win['sensor_age_h'] >= WARMUP_H]

    print('=' * 64)
    print('CGM RANGE SUMMARY')
    print('=' * 64)
    print(f'  full export      {len(d):,} EGVs   '
          f'{d["dt"].min():%Y-%m-%d} to {d["dt"].max():%Y-%m-%d}')
    print(f'  training window  {len(win):,} EGVs   '
          f'{WINDOW[0]} to {WINDOW[1]}')
    print(f'  after warmup     {len(kept):,} EGVs   '
          f'(excluding first {WARMUP_H}h of each sensor session)')
    print(f'  sensor sessions  {d["tx"].nunique()}')

    describe(win['glucose'], 'TRAINING WINDOW, all EGVs')
    describe(kept['glucose'], f'TRAINING WINDOW, excluding {WARMUP_H}h warmup')

    print('\n' + '=' * 64)
    print('HIGHEST READINGS  (after warmup exclusion)')
    print('=' * 64)
    top = kept.nlargest(23, 'glucose')
    for _, r in top.iterrows():
        print(f'    {r["dt"]:%Y-%m-%d %H:%M}   {r["glucose"]:>3.0f} mg/dL'
              f'   sensor age {r["sensor_age_h"]:>5.1f} h')

    print('\n' + '=' * 64)
    print('FOR THE §3.4 STATEMENT')
    print('=' * 64)
    g = kept['glucose']
    p5, p95 = np.percentile(g, 5), np.percentile(g, 95)
    n140 = int((g >= 140).sum())
    n130 = int((g >= 130).sum())
    n100 = int((g > 100).sum())
    print(f'  central range (5th-95th percentile): {p5:.0f}-{p95:.0f} mg/dL')
    print(f'  full range:                          {g.min():.0f}-{g.max():.0f} mg/dL')
    print(f'  readings at or above 140:            {n140}')
    print(f'  readings at or above 130:            {n130}')
    print(f'  readings above 100 (the AUC threshold): {n100:,} '
          f'({100*n100/len(g):.1f}%)')
    if n140 == 0:
        print('\n  "never exceeded 140" holds.')
    else:
        print(f'\n  "never exceeded 140" does NOT hold — {n140} readings at or '
              'above 140.')
    print('  Note: AUC above 100 was computed on windowed features, so the')
    print('  percentage above is indicative of how often the threshold')
    print('  discriminates, not identical to the feature values themselves.')


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--dexcom', required=True)
    a = p.parse_args()
    run(a.dexcom)
