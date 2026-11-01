#!/usr/bin/env python3
"""
diag_calibration_bias.py

Did the five user calibrations of 2026-08-20 to 08-24 shift the CGM's bias
relative to the capillary reference?

Usage:
    python analysis/diag_calibration_bias.py --pairs features/results/cgm_reference_pairs.csv
"""

import argparse
from pathlib import Path

import pandas as pd

_ap = argparse.ArgumentParser(description='CGM bias before vs after a calibration date.')
_ap.add_argument('--pairs', required=True, type=Path,
                 help='cgm_reference_pairs.csv written by clarke_error_grid.py')
_ap.add_argument('--cal-start', default='2026-08-20',
                 help='first calibration date (default: %(default)s, this study)')
_args = _ap.parse_args()
PAIRS = _args.pairs
CAL_START = pd.Timestamp(_args.cal_start)

d = pd.read_csv(PAIRS)
d['dt'] = pd.to_datetime(d['datetime'], errors='coerce')
d['diff'] = d['cgm'] - d['reference']
d['ard'] = (d['diff'].abs() / d['reference'] * 100)
d = d.dropna(subset=['dt']).sort_values('dt')

pre = d[d['dt'] < CAL_START]
post = d[d['dt'] >= CAL_START]

print("=" * 60)
print("CGM BIAS BEFORE AND AFTER THE CALIBRATION WINDOW")
print("=" * 60)
print(f"  calibrations were entered 2026-08-20 through 2026-08-24\n")
print(f"  {'period':<22}{'n':>4}{'bias':>9}{'SD':>8}{'MARD':>8}")
print("  " + "-" * 49)
for name, g in (('before 08-20', pre), ('08-20 onward', post)):
    if len(g):
        print(f"  {name:<22}{len(g):>4}{g['diff'].mean():>+9.1f}"
              f"{g['diff'].std(ddof=1):>8.1f}{g['ard'].mean():>7.1f}%")
    else:
        print(f"  {name:<22}{0:>4}       —       —       —")

if len(pre) > 1 and len(post) > 1:
    shift = post['diff'].mean() - pre['diff'].mean()
    print(f"\n  shift in mean bias: {shift:+.1f} mg/dL")
    try:
        from scipy.stats import mannwhitneyu
        stat, p = mannwhitneyu(pre['diff'], post['diff'])
        print(f"  Mann-Whitney U on the difference distributions: p = {p:.4f}")
        print("  " + ("distributions differ (alpha = 0.05)" if p < 0.05
                      else "no significant difference at this sample size"))
    except ImportError:
        pass

print("\n" + "=" * 60)
print("EVERY PAIR, IN ORDER")
print("=" * 60)
print(f"  {'date':<12}{'ref':>6}{'CGM':>6}{'diff':>7}{'ARD':>8}  zone")
print("  " + "-" * 47)
for _, r in d.iterrows():
    mark = '  <- calibration window' if CAL_START <= r['dt'] < pd.Timestamp('2026-08-25') else ''
    print(f"  {r['dt']:%Y-%m-%d}{r['reference']:>6.0f}{r['cgm']:>6.0f}"
          f"{r['diff']:>+7.0f}{r['ard']:>7.1f}%  {r['zone']}{mark}")