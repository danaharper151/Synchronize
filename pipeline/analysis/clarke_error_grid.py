#!/usr/bin/env python3
"""
Synchronize — CGM Accuracy Against Paired Capillary Reference
=============================================================
Implements the Clarke Error Grid analysis specified in §3.3, using the paired
Keto-Mojo GK+ capillary glucose readings taken at each fasting betaHB
measurement as reference against the concurrent Dexcom G7 value.

Reports three complementary measures:

  Clarke Error Grid   Clinical-consequence zones (A-E). Designed for insulin
                      dosing decisions across a diabetic glucose range; for a
                      normoglycaemic subject nearly all pairs fall in Zone A,
                      so the grid confirms safety but discriminates weakly.

  MARD                Mean absolute relative difference — the standard CGM
                      accuracy metric, and the more informative one here.

  Bland-Altman        Bias and limits of agreement, which reveal systematic
                      offset that MARD alone would obscure.

Usage:
    python analysis/clarke_error_grid.py --features features
    python analysis/clarke_error_grid.py --features features --no-plot
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd


def clarke_zone(ref: float, test: float) -> str:
    """
    Standard Clarke Error Grid zone assignment.
    ref  = reference (capillary fingerstick), test = CGM value, both mg/dL.
    """
    if (ref <= 70 and test <= 70) or abs(test - ref) <= 0.2 * ref:
        return 'A'
    if (ref >= 180 and test <= 70) or (ref <= 70 and test >= 180):
        return 'E'
    if ((70 <= ref <= 290) and test >= ref + 110) or \
       ((130 <= ref <= 180) and test <= (7 / 5) * ref - 182):
        return 'C'
    if (ref >= 240 and 70 <= test <= 180) or \
       (ref <= 175 / 3 and 70 <= test <= 180) or \
       ((175 / 3 <= ref <= 70) and test >= (6 / 5) * ref):
        return 'D'
    return 'B'


def load_pairs(features_dir: Path) -> pd.DataFrame:
    path = features_dir / 'feature_matrix.csv'
    if not path.exists():
        raise SystemExit(f"not found: {path}")
    m = pd.read_csv(path)

    need = {'paired_glucose_fingerstick', 'glucose_at_label'}
    missing = need - set(m.columns)
    if missing:
        raise SystemExit(f"feature matrix lacks: {missing}")

    df = pd.DataFrame({
        'datetime': pd.to_datetime(m['datetime'], errors='coerce'),
        'context': m['reading_context'],
        'reference': pd.to_numeric(m['paired_glucose_fingerstick'],
                                   errors='coerce'),
        'cgm': pd.to_numeric(m['glucose_at_label'], errors='coerce'),
        'bhb': pd.to_numeric(m['bhb_mmol_l'], errors='coerce'),
    })
    n_all = len(df)
    df = df.dropna(subset=['reference', 'cgm'])
    df = df[(df['reference'] > 0) & (df['cgm'] > 0)]
    print(f"  {len(df)} paired observations "
          f"(of {n_all} labels; pairs require both a capillary reference and "
          f"a CGM value within the 15-minute window)")
    return df.reset_index(drop=True)


def report(df: pd.DataFrame):
    ref = df['reference'].to_numpy(float)
    cgm = df['cgm'].to_numpy(float)
    diff = cgm - ref
    rel = np.abs(diff) / ref * 100

    df = df.copy()
    df['zone'] = [clarke_zone(r, c) for r, c in zip(ref, cgm)]

    print("\n" + "=" * 66)
    print("CLARKE ERROR GRID")
    print("=" * 66)
    counts = df['zone'].value_counts().reindex(list('ABCDE')).fillna(0)
    for z, n in counts.items():
        pct = 100 * n / len(df)
        label = {'A': 'clinically accurate',
                 'B': 'benign — no inappropriate treatment',
                 'C': 'would prompt unnecessary correction',
                 'D': 'dangerous failure to detect',
                 'E': 'erroneous treatment'}[z]
        print(f"  Zone {z}  {int(n):>3}  {pct:>5.1f}%   {label}")
    ab = 100 * (counts['A'] + counts['B']) / len(df)
    print(f"\n  Zones A+B: {ab:.1f}%  "
          "(ISO 15197 / consensus expectation for CGM: >=99%)")

    print("\n" + "=" * 66)
    print("ACCURACY METRICS")
    print("=" * 66)
    print(f"  n pairs                {len(df)}")
    print(f"  reference range        {ref.min():.0f} - {ref.max():.0f} mg/dL")
    print(f"  CGM range              {cgm.min():.0f} - {cgm.max():.0f} mg/dL")
    print(f"  MARD                   {rel.mean():.1f}%")
    print(f"  median ARD             {np.median(rel):.1f}%")
    print(f"  mean bias (CGM - ref)  {diff.mean():+.1f} mg/dL")
    print(f"  SD of difference       {diff.std(ddof=1):.1f} mg/dL")
    lo, hi = diff.mean() - 1.96 * diff.std(ddof=1), \
        diff.mean() + 1.96 * diff.std(ddof=1)
    print(f"  95% limits of agreement  {lo:+.1f} to {hi:+.1f} mg/dL")
    within = lambda p: 100 * (rel <= p).mean()
    print(f"  within 15% / 20% / 30%   {within(15):.0f}% / "
          f"{within(20):.0f}% / {within(30):.0f}%")

    if len(df) > 3:
        r = np.corrcoef(ref, cgm)[0, 1]
        print(f"  Pearson r              {r:.3f}")

    print("\n  Interpretation note. The Clarke grid was developed to assess "
          "whether\n  a glucose reading would lead to an inappropriate "
          "insulin decision, over\n  a range spanning hypo- to severe "
          "hyperglycaemia. This subject's\n  reference values span a narrow "
          "normoglycaemic band, so Zone A placement\n  is expected and "
          "confirms the CGM did not mislead; it does not\n  demonstrate "
          "precision. MARD and the limits of agreement are the\n  "
          "discriminating measures at this range.")

    print("\n  Per-context pairs:")
    for ctx, g in df.groupby('context'):
        r2 = np.abs(g['cgm'] - g['reference']) / g['reference'] * 100
        print(f"    {str(ctx):<14} n={len(g):>3}  MARD {r2.mean():>5.1f}%  "
              f"bias {(g['cgm']-g['reference']).mean():+.1f}")

    worst = df.assign(ard=rel).nlargest(5, 'ard')
    print("\n  Largest discrepancies:")
    for _, r in worst.iterrows():
        print(f"    {r['datetime']:%Y-%m-%d %H:%M}  {str(r['context']):<12}"
              f" ref {r['reference']:>5.0f}  CGM {r['cgm']:>5.0f}  "
              f"{r['ard']:>5.1f}%  zone {r['zone']}")
    return df


def plot(df: pd.DataFrame, out: Path):
    try:
        import matplotlib
        matplotlib.use('Agg')
        import matplotlib.pyplot as plt
    except ImportError:
        print("\n  matplotlib not installed — skipping plot "
              "(py -m pip install matplotlib)")
        return

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(13, 6))

    hi = max(df['reference'].max(), df['cgm'].max()) * 1.15
    hi = max(hi, 200)
    ax1.plot([0, hi], [0, hi], 'k--', lw=0.8, alpha=0.5)
    ax1.plot([0, 70, 70], [56, 56, 0], 'k-', lw=0.7)
    ax1.plot([0, hi], [0, 1.2 * hi], 'k-', lw=0.7, alpha=0.4)
    ax1.plot([0, hi], [0, 0.8 * hi], 'k-', lw=0.7, alpha=0.4)
    colors = {'A': '#2a9d8f', 'B': '#e9c46a', 'C': '#f4a261',
              'D': '#e76f51', 'E': '#9d0208'}
    for z, g in df.groupby('zone'):
        ax1.scatter(g['reference'], g['cgm'], s=42, alpha=0.8,
                    c=colors[z], edgecolors='k', linewidths=0.4,
                    label=f'Zone {z} (n={len(g)})')
    ax1.set_xlabel('Reference capillary glucose (mg/dL)')
    ax1.set_ylabel('CGM glucose (mg/dL)')
    ax1.set_title('Clarke Error Grid')
    ax1.set_xlim(0, hi); ax1.set_ylim(0, hi)
    ax1.legend(loc='upper left', fontsize=9)
    ax1.grid(alpha=0.2)

    mean = (df['reference'] + df['cgm']) / 2
    diff = df['cgm'] - df['reference']
    b, s = diff.mean(), diff.std(ddof=1)
    ax2.scatter(mean, diff, s=42, alpha=0.8, c='#264653',
                edgecolors='k', linewidths=0.4)
    ax2.axhline(b, color='#e76f51', lw=1.4, label=f'bias {b:+.1f}')
    ax2.axhline(b + 1.96 * s, color='#e76f51', ls='--', lw=1,
                label=f'+1.96 SD {b+1.96*s:+.1f}')
    ax2.axhline(b - 1.96 * s, color='#e76f51', ls='--', lw=1,
                label=f'-1.96 SD {b-1.96*s:+.1f}')
    ax2.axhline(0, color='k', lw=0.6, alpha=0.4)
    ax2.set_xlabel('Mean of CGM and reference (mg/dL)')
    ax2.set_ylabel('CGM - reference (mg/dL)')
    ax2.set_title('Bland-Altman')
    ax2.legend(fontsize=9)
    ax2.grid(alpha=0.2)

    fig.tight_layout()
    fig.savefig(out / 'cgm_accuracy.png', dpi=200)
    print(f"\n  Wrote {out / 'cgm_accuracy.png'}")


def run(features_dir: str, do_plot: bool):
    fdir = Path(features_dir)
    odir = fdir / 'results'
    odir.mkdir(parents=True, exist_ok=True)

    print("=" * 66)
    print("CGM ACCURACY VS PAIRED CAPILLARY REFERENCE  (§3.3)")
    print("=" * 66)
    df = load_pairs(fdir)
    if len(df) < 3:
        raise SystemExit("too few paired observations to analyse")
    df = report(df)
    df.to_csv(odir / 'cgm_reference_pairs.csv', index=False)
    print(f"  Wrote {odir / 'cgm_reference_pairs.csv'}")
    if do_plot:
        plot(df, odir)


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--features', required=True)
    p.add_argument('--no-plot', action='store_true')
    a = p.parse_args()
    run(a.features, not a.no_plot)