#!/usr/bin/env python3
"""
Synchronize — Direction of the glucose-βHB relationship
=======================================================
§4.9 reports that minimum glucose over short windows is the highest-ranked
feature in Model A. Importance establishes that a feature matters; it does not
establish which way the relationship runs.

This script answers that two ways:

  Correlation       The raw association between each top CGM feature and βHB.
                    Simple, but ignores everything else in the model.

  Partial dependence  How the fitted Random Forest's prediction changes as the
                    feature varies, holding the others at their observed
                    distribution. This is what the model actually learned.

Both are reported because they can disagree — a raw correlation can be
confounded by other features, and partial dependence can be misleading where
the feature is strongly correlated with others (as the CGM block is).

Usage:
    python analysis/check_glucose_direction.py --features features
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.stats import pearsonr, spearmanr
from sklearn.ensemble import RandomForestRegressor
from sklearn.inspection import partial_dependence

SEED, SENTINEL = 42, -999.0
META = {'bhb_mmol_l', 'timestamp_ms', 'reading_context', 'date_key'}
MORNING = ['FASTING', 'PRE_LUNCH']

TOP_CGM = ['glucose_min_30min', 'glucose_mean_30min', 'glucose_min_60min',
           'glucose_at_label', 'glucose_max_30min', 'glucose_mean_60min',
           'glucose_min_120min', 'glucose_slope_30min', 'glucose_roc']


def run(features_dir: str, plot: bool):
    fdir = Path(features_dir)
    df = pd.read_csv(fdir / 'model_A_cgm_watch_macros.csv')
    y = df['bhb_mmol_l'].to_numpy(float)
    ctx = df['reading_context'].to_numpy()

    print("=" * 70)
    print("DIRECTION OF THE GLUCOSE-βHB RELATIONSHIP")
    print("=" * 70)
    print(f"  n = {len(y)} labels\n")

    # ---- 1. raw correlations ----------------------------------------
    print("=" * 70)
    print("1. RAW CORRELATION WITH βHB")
    print("=" * 70)
    print("  Negative r = lower glucose associates with HIGHER ketones,")
    print("  the opposite of the DKA relationship reported by Cichosz.\n")
    print(f"  {'feature':<26}{'n':>5}{'Pearson r':>11}{'p':>10}"
          f"{'Spearman':>11}{'p':>10}")
    print("  " + "-" * 71)

    rows = []
    for f in TOP_CGM:
        if f not in df.columns:
            continue
        v = pd.to_numeric(df[f], errors='coerce')
        ok = v.notna()
        if ok.sum() < 10:
            continue
        pr, pp = pearsonr(v[ok], y[ok])
        sr, sp = spearmanr(v[ok], y[ok])
        star = ' *' if pp < 0.05 else ''
        print(f"  {f:<26}{ok.sum():>5}{pr:>11.3f}{pp:>10.4f}"
              f"{sr:>11.3f}{sp:>10.4f}{star}")
        rows.append(dict(feature=f, n=int(ok.sum()), pearson_r=pr,
                         pearson_p=pp, spearman_r=sr, spearman_p=sp))

    # ---- 2. by context ----------------------------------------------
    print("\n" + "=" * 70)
    print("2. BY READING CONTEXT  (glucose_min_30min)")
    print("=" * 70)
    print("  Fasting and postprandial states may differ; a pooled")
    print("  correlation could mask or manufacture a relationship.\n")
    f = 'glucose_min_30min'
    if f in df.columns:
        v = pd.to_numeric(df[f], errors='coerce')
        print(f"  {'context':<16}{'n':>5}{'mean glucose':>14}"
              f"{'mean βHB':>11}{'Pearson r':>11}{'p':>9}")
        print("  " + "-" * 66)
        for c in ['FASTING', 'PRE_LUNCH', 'POST_LUNCH', 'PRE_DINNER',
                  'PRE_SLEEP']:
            m = (ctx == c) & v.notna()
            if m.sum() < 5:
                continue
            pr, pp = pearsonr(v[m], y[m])
            print(f"  {c:<16}{m.sum():>5}{v[m].mean():>14.1f}"
                  f"{y[m].mean():>11.2f}{pr:>11.3f}{pp:>9.4f}")
        mm = np.isin(ctx, MORNING) & v.notna().to_numpy()
        for lab, msk in (('morning', mm), ('other', ~mm & v.notna().to_numpy())):
            pr, pp = pearsonr(v[msk], y[msk])
            print(f"  {lab:<16}{msk.sum():>5}{v[msk].mean():>14.1f}"
                  f"{y[msk].mean():>11.2f}{pr:>11.3f}{pp:>9.4f}")

    # ---- 3. partial dependence --------------------------------------
    print("\n" + "=" * 70)
    print("3. PARTIAL DEPENDENCE  (what the fitted model learned)")
    print("=" * 70)

    X = df[[c for c in df.columns if c not in META]].select_dtypes(np.number)
    Xf = X.fillna(SENTINEL)
    rf = RandomForestRegressor(n_estimators=300, min_samples_leaf=2,
                               max_features='sqrt', random_state=SEED,
                               n_jobs=-1)
    rf.fit(Xf, y)

    targets = [f for f in TOP_CGM[:4] if f in Xf.columns]
    pd_results = {}
    for f in targets:
        idx = list(Xf.columns).index(f)
        res = partial_dependence(rf, Xf, [idx], grid_resolution=25,
                                 kind='average')
        grid = res['grid_values'][0]
        vals = res['average'][0]
        keep = grid > 0          # exclude the sentinel region
        grid, vals = grid[keep], vals[keep]
        pd_results[f] = (grid, vals)

        slope = np.polyfit(grid, vals, 1)[0]
        direction = ('DECREASING — higher glucose, lower predicted βHB'
                     if slope < 0 else
                     'INCREASING — higher glucose, higher predicted βHB')
        print(f"\n  {f}")
        print(f"    glucose range   {grid.min():.0f} – {grid.max():.0f} mg/dL")
        print(f"    predicted βHB   {vals.min():.2f} – {vals.max():.2f} mmol/L")
        print(f"    overall slope   {slope:+.5f} mmol/L per mg/dL")
        print(f"    {direction}")
        lo = vals[:len(vals)//3].mean()
        hi = vals[-len(vals)//3:].mean()
        print(f"    lowest third of glucose  -> mean predicted βHB {lo:.2f}")
        print(f"    highest third            -> mean predicted βHB {hi:.2f}")
        print(f"    difference               {hi-lo:+.2f} mmol/L")

    print("\n" + "=" * 70)
    print("INTERPRETATION")
    print("=" * 70)
    print("""  A consistently negative correlation and a decreasing partial
  dependence together support the statement in §5.3: in this subject,
  lower recent glucose associates with higher βHB.

  Caveats to carry into the text:
    - The CGM features correlate 0.96-0.99 with one another, so partial
      dependence for any one of them is difficult to isolate.
    - Correlation here is observational within a single subject; it
      describes co-movement, not a causal mechanism.
    - Reporting the by-context breakdown guards against a pooled
      correlation driven entirely by fasting-vs-postprandial differences
      in both variables.""")

    out = fdir / 'results'
    out.mkdir(exist_ok=True)
    pd.DataFrame(rows).to_csv(out / 'glucose_direction.csv', index=False)
    print(f"\n  Wrote {out / 'glucose_direction.csv'}")

    if plot and pd_results:
        try:
            import matplotlib
            matplotlib.use('Agg')
            import matplotlib.pyplot as plt
            fig, axes = plt.subplots(1, len(pd_results),
                                     figsize=(4.6*len(pd_results), 4.2),
                                     squeeze=False)
            for ax, (f, (grid, vals)) in zip(axes[0], pd_results.items()):
                ax.plot(grid, vals, lw=2.4, color='#1f6f8b')
                ax.set_xlabel(f.replace('_', ' ') + '  (mg/dL)')
                ax.set_ylabel('predicted βHB (mmol/L)')
                ax.set_title(f.replace('_', ' '), fontsize=11)
                ax.grid(alpha=0.2)
                ax.spines[['top', 'right']].set_visible(False)
            fig.suptitle('Partial dependence of predicted βHB on CGM features',
                         fontsize=13, fontweight='semibold', y=1.02)
            fig.tight_layout()
            fig.savefig(out / 'fig15_partial_dependence.png', dpi=300,
                        bbox_inches='tight')
            print(f"  Wrote {out / 'fig15_partial_dependence.png'}")
        except ImportError:
            print('  matplotlib not available — plot skipped')


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--features', required=True)
    p.add_argument('--no-plot', action='store_true')
    a = p.parse_args()
    run(a.features, not a.no_plot)
    