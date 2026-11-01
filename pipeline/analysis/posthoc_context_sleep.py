#!/usr/bin/env python3
"""
Synchronize — Post-hoc: per-context error and sleep-vector contribution
=======================================================================
Two exploratory questions not covered by the pre-registered analysis:

  1. Are morning (FASTING / PRE_LUNCH) labels predicted more accurately than
     others?
  2. Does the eight-feature sleep architecture vector (§3.4) contribute
     anything on the labels where it is defined?

EXPLORATORY. Neither question was pre-registered. Both are reported as
observations, not as tested hypotheses, and no criterion is attached to either.

Per-context RMSE is compared against a WITHIN-CONTEXT naive baseline, not
against overall RMSE. A context whose labels vary less will show lower RMSE
without the model performing better on it, so the raw figure is not comparable
across contexts; the percentage below each context's own baseline is.

Usage:
    python analysis/posthoc_context_sleep.py --features features
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd
from joblib import Parallel, delayed
from sklearn.ensemble import RandomForestRegressor
from sklearn.model_selection import LeaveOneGroupOut

try:
    from xgboost import XGBRegressor
    HAS_XGB = True
except ImportError:
    HAS_XGB = False

RANDOM_SEED = 42
SENTINEL = -999.0
MORNING = ['FASTING', 'PRE_LUNCH']
META = {'bhb_mmol_l', 'timestamp_ms', 'reading_context', 'date_key'}


def make_rf():
    return RandomForestRegressor(n_estimators=300, min_samples_leaf=2,
                                 max_features='sqrt',
                                 random_state=RANDOM_SEED, n_jobs=1)


def make_xgb():
    return XGBRegressor(n_estimators=250, max_depth=3, learning_rate=0.05,
                        subsample=0.8, colsample_bytree=0.8,
                        random_state=RANDOM_SEED, n_jobs=1, verbosity=0)


MODELS = {'RandomForest': make_rf}
if HAS_XGB:
    MODELS['XGBoost'] = make_xgb


def prep(X, name):
    return (X.to_numpy(float) if name == 'XGBoost'
            else X.fillna(SENTINEL).to_numpy(float))


def cv_predict(X, y, groups, name):
    Xp = prep(X, name)
    preds = np.full(len(y), np.nan)
    folds = list(LeaveOneGroupOut().split(Xp, y, groups))

    def one(tr, te):
        m = MODELS[name]()
        m.fit(Xp[tr], y[tr])
        return te, m.predict(Xp[te])

    for te, p in Parallel(n_jobs=-1, prefer='processes')(
            delayed(one)(tr, te) for tr, te in folds):
        preds[te] = p
    return preds


def rmse(a, b):
    return float(np.sqrt(np.mean((a - b) ** 2)))


def run(features_dir: str):
    fdir = Path(features_dir)
    df = pd.read_csv(fdir / 'model_A_cgm_watch_macros.csv')
    feats = [c for c in df.columns if c not in META]
    X = df[feats].select_dtypes(include=[np.number])
    y = df['bhb_mmol_l'].to_numpy(float)
    groups = df['date_key'].to_numpy()
    ctx = df['reading_context'].to_numpy()

    sleep_cols = [c for c in X.columns if c.startswith('sleep_')]

    print("=" * 70)
    print("POST-HOC: PER-CONTEXT ERROR AND SLEEP-VECTOR CONTRIBUTION")
    print("=" * 70)
    print("  EXPLORATORY — neither question was pre-registered.\n")
    print(f"  {len(y)} labels, {len(X.columns)} features "
          f"({len(sleep_cols)} sleep-vector features)")
    print(f"  Cross-validation: leave-one-day-out\n")

    # ---- Q1: per-context error --------------------------------------
    print("=" * 70)
    print("1. PREDICTION ERROR BY READING CONTEXT  (Model A)")
    print("=" * 70)
    print("  Each context compared against its OWN naive baseline (the SD of")
    print("  that context's labels). Raw RMSE is not comparable across")
    print("  contexts with different label spread.\n")

    for name in MODELS:
        pred = cv_predict(X, y, groups, name)
        print(f"  {name}")
        print(f"    {'context':<14}{'n':>4}{'label SD':>10}{'RMSE':>8}"
              f"{'vs own base':>13}{'MAE':>8}")
        print("    " + "-" * 55)
        for c in ['FASTING', 'PRE_LUNCH', 'POST_LUNCH', 'PRE_DINNER',
                  'PRE_SLEEP']:
            m = ctx == c
            if m.sum() < 2:
                continue
            base = float(np.std(y[m]))
            r = rmse(y[m], pred[m])
            pct = 100 * (1 - r / base) if base else np.nan
            print(f"    {c:<14}{m.sum():>4}{base:>10.3f}{r:>8.3f}"
                  f"{pct:>12.1f}%{np.mean(np.abs(y[m]-pred[m])):>8.3f}")
        mm = np.isin(ctx, MORNING)
        for lab, m in (('morning (F+PL)', mm), ('other', ~mm)):
            base = float(np.std(y[m]))
            r = rmse(y[m], pred[m])
            print(f"    {lab:<14}{m.sum():>4}{base:>10.3f}{r:>8.3f}"
                  f"{100*(1-r/base):>12.1f}%"
                  f"{np.mean(np.abs(y[m]-pred[m])):>8.3f}")
        print()

    # ---- Q2: sleep vector ablation ----------------------------------
    print("=" * 70)
    print("2. SLEEP VECTOR CONTRIBUTION")
    print("=" * 70)
    print("  Model A trained with and without the eight sleep-architecture")
    print("  features, evaluated on morning labels only — the labels where")
    print("  the vector is defined. Training set is the full 140 labels in")
    print("  both arms, so the comparison isolates the features rather than")
    print("  changing sample size.\n")

    X_no = X.drop(columns=sleep_cols)
    mm = np.isin(ctx, MORNING)
    base_m = float(np.std(y[mm]))
    print(f"  morning labels: {mm.sum()}   their naive baseline RMSE: "
          f"{base_m:.3f}\n")
    print(f"  {'model':<14}{'features':<12}{'RMSE':>8}{'MAE':>8}"
          f"{'vs base':>10}{'delta':>9}")
    print("  " + "-" * 61)

    from scipy.stats import wilcoxon
    for name in MODELS:
        p_with = cv_predict(X, y, groups, name)
        p_no = cv_predict(X_no, y, groups, name)
        r_with = rmse(y[mm], p_with[mm])
        r_no = rmse(y[mm], p_no[mm])
        for lab, r, p in (('with sleep', r_with, p_with),
                          ('without', r_no, p_no)):
            d = f"{r - r_with:+.3f}" if lab == 'without' else ''
            print(f"  {name:<14}{lab:<12}{r:>8.3f}"
                  f"{np.mean(np.abs(y[mm]-p[mm])):>8.3f}"
                  f"{100*(1-r/base_m):>9.1f}%{d:>9}")
        try:
            _, pv = wilcoxon(np.abs(y[mm] - p_with[mm]),
                             np.abs(y[mm] - p_no[mm]))
            verdict = ('sleep vector helps' if pv < 0.05 and r_no > r_with
                       else 'sleep vector hurts' if pv < 0.05
                       else 'no measurable contribution')
            print(f"  {'':<14}Wilcoxon on paired absolute errors: "
                  f"p = {pv:.4f}  ({verdict})\n")
        except ValueError:
            print(f"  {'':<14}Wilcoxon: not computable\n")

    print("  Note. With 59 morning labels this comparison has limited power.")
    print("  A null result indicates the contribution is not detectable at")
    print("  this sample size, not that it is absent.")


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--features', required=True)
    a = p.parse_args()
    run(a.features)