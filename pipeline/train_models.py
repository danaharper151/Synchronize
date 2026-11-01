#!/usr/bin/env python3
"""
Synchronize — Model Training and Feature Ablation
=================================================
Trains Random Forest and XGBoost across the four sensor configurations
(§3.2) and reports the ablation described in §3.5.3.

    Model A   CGM + smartwatch + dietary + exercise    (full suite)
    Model B   smartwatch + dietary + exercise          (no CGM)
    Model C   CGM + smartwatch                         (no dietary)
    Model D   smartwatch only                          (minimum invasive)

Two architectures x four configurations = an 8-cell performance matrix.

--------------------------------------------------------------------------
PRE-REGISTERED DECISIONS
--------------------------------------------------------------------------
2026-09-02  Two-class ketosis evaluation: sub-ketosis (<0.5 mmol/L, n=24)
            versus in-ketosis (>=0.5, n=116). The three-band scheme in
            §3.5.2 is not evaluable: the elevated band (>3.0) holds 2
            observations, so LOOCV would train on one and test on the other,
            and a model never predicting that class would score 98.6% while
            demonstrating nothing.
2026-09-04  ROCKET replaced by XGBoost. ROCKET operates on raw time-series
            input (Pikulin et al. fed it CGM sequences); this matrix is one
            engineered feature vector per label, so applying it here would
            mean discarding the feature engineering or using the algorithm
            outside its design. Tree ensembles are additionally the
            better-supported choice at this scale: Grinsztajn et al. (2022)
            find them state-of-the-art on tabular data up to ~10k samples,
            and Mohamed et al. [2] used XGBoost for personalized smartwatch
            metabolic prediction on the same hardware class.
2026-09-04  Dual cross-validation. LOOCV is reported because §3.5.2
            pre-registered it, but with 4-5 labels per day it leaks: holding
            out one label leaves same-day labels in training, which share
            daily covariates, sleep vector, exercise flag, and overlapping
            biosignal windows. Leave-one-day-out (30 folds) holds out all
            labels from a day together and is reported as the conservative
            primary estimate. Both are reported; the gap between them
            quantifies the leakage.
2026-09-04  Missing values. Structural, not random: sleep_* is undefined for
            non-morning labels, session_* when no prior exercise matched.
            XGBoost learns a default branch natively. Random Forest receives
            a sentinel (-999) placing missing values outside every feature's
            range, letting trees isolate them as their own branch. Median
            imputation was rejected: it would fabricate a sleep architecture
            vector for the 81 labels that have none.

Usage:
    python train_models.py --features features
    python train_models.py --features features --prefix sens_no_anomaly_
"""

from __future__ import annotations

import argparse
import json
import time
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.stats import wilcoxon
from sklearn.ensemble import RandomForestRegressor
from sklearn.model_selection import LeaveOneOut, LeaveOneGroupOut
from joblib import Parallel, delayed

try:
    from xgboost import XGBRegressor
    HAS_XGB = True
except ImportError:
    HAS_XGB = False

warnings.filterwarnings('ignore', category=UserWarning)

RANDOM_SEED = 42
SENTINEL = -999.0
KETOSIS_THRESHOLD = 0.5

LABEL_COL = 'bhb_mmol_l'
META = {'bhb_mmol_l', 'timestamp_ms', 'reading_context', 'date_key'}

CONFIGS = {
    'A': ('model_A_cgm_watch_macros', 'CGM + watch + macros'),
    'B': ('model_B_watch_macros', 'watch + macros'),
    'C': ('model_C_cgm_watch', 'CGM + watch'),
    'D': ('model_D_watch_only', 'watch only'),
}


# ── Models ────────────────────────────────────────────────────

def make_rf():
    return RandomForestRegressor(
        n_estimators=300,
        min_samples_leaf=2,
        max_features='sqrt',
        random_state=RANDOM_SEED,
        n_jobs=1,          # folds are parallelised instead; see run_cv
    )


def make_xgb():
    return XGBRegressor(
        n_estimators=250,
        max_depth=3,
        learning_rate=0.05,
        subsample=0.8,
        colsample_bytree=0.8,
        reg_lambda=1.0,
        random_state=RANDOM_SEED,
        n_jobs=1,
        verbosity=0,
    )


MODELS = {'RandomForest': make_rf}
if HAS_XGB:
    MODELS['XGBoost'] = make_xgb


def prepare(X: pd.DataFrame, model_name: str) -> np.ndarray:
    """XGBoost handles NaN natively; RF needs a sentinel outside every range."""
    if model_name == 'XGBoost':
        return X.to_numpy(dtype=float)
    return X.fillna(SENTINEL).to_numpy(dtype=float)


# ── Cross-validation ──────────────────────────────────────────

def run_cv(X: pd.DataFrame, y: np.ndarray, groups: np.ndarray,
           model_name: str, scheme: str) -> np.ndarray:
    """Returns per-observation predictions, aligned with y."""
    Xp = prepare(X, model_name)
    preds = np.full(len(y), np.nan)

    splitter = list(LeaveOneOut().split(Xp) if scheme == 'loocv'
                    else LeaveOneGroupOut().split(Xp, y, groups))

    def one_fold(train_idx, test_idx):
        m = MODELS[model_name]()
        m.fit(Xp[train_idx], y[train_idx])
        return test_idx, m.predict(Xp[test_idx])

    # Models run single-threaded; the fold loop is parallelised. On data this
    # small, per-model threading costs more than it saves (measured: RF with
    # n_jobs=-1 was ~1.8x slower per fit than n_jobs=1).
    for test_idx, p in Parallel(n_jobs=-1, prefer='processes')(
            delayed(one_fold)(tr, te) for tr, te in splitter):
        preds[test_idx] = p
    return preds


def metrics(y: np.ndarray, pred: np.ndarray) -> dict:
    err = pred - y
    abs_err = np.abs(err)
    rmse = float(np.sqrt(np.mean(err ** 2)))
    baseline = float(np.std(y))
    true_cls = (y >= KETOSIS_THRESHOLD).astype(int)
    pred_cls = (pred >= KETOSIS_THRESHOLD).astype(int)

    # Raw accuracy is degenerate here: 116 of 140 labels are in-ketosis, so a
    # model predicting the majority class for everything scores 82.9% and
    # satisfies the >=80% criterion in §3.7.4 while having learned nothing.
    # Sensitivity/specificity and balanced accuracy are reported instead.
    tp = int(((true_cls == 1) & (pred_cls == 1)).sum())
    fn = int(((true_cls == 1) & (pred_cls == 0)).sum())
    tn = int(((true_cls == 0) & (pred_cls == 0)).sum())
    fp = int(((true_cls == 0) & (pred_cls == 1)).sum())
    sens = tp / (tp + fn) if (tp + fn) else np.nan      # in-ketosis recall
    spec = tn / (tn + fp) if (tn + fp) else np.nan      # sub-ketosis recall
    balanced = np.nanmean([sens, spec])
    majority = max(true_cls.mean(), 1 - true_cls.mean())
    ss_res = float(np.sum(err ** 2))
    ss_tot = float(np.sum((y - y.mean()) ** 2))
    return {
        'rmse': rmse,
        'mae': float(np.mean(abs_err)),
        'r2': 1 - ss_res / ss_tot if ss_tot else np.nan,
        'pct_below_baseline': 100 * (1 - rmse / baseline) if baseline else np.nan,
        'ketosis_accuracy': float((true_cls == pred_cls).mean()),
        'ketosis_sensitivity': float(sens),
        'ketosis_specificity': float(spec),
        'ketosis_balanced': float(balanced),
        'majority_rate': float(majority),
        'n_sub_predicted': int((pred_cls == 0).sum()),
        'abs_errors': abs_err,
    }


# ── Reporting ─────────────────────────────────────────────────

def performance_table(results: dict, scheme: str, baseline: float):
    print(f"\n  {scheme.upper()}")
    print(f"  {'model':<14}{'cfg':<5}{'RMSE':>8}{'MAE':>8}{'R2':>8}"
          f"{'vs base':>9}")
    print("  " + "-" * 52)
    for model in MODELS:
        for cfg in CONFIGS:
            r = results[(model, cfg, scheme)]
            print(f"  {model:<14}{cfg:<5}{r['rmse']:>8.3f}{r['mae']:>8.3f}"
                  f"{r['r2']:>8.3f}{r['pct_below_baseline']:>8.1f}%")
    print(f"  {'naive mean':<14}{'—':<5}{baseline:>8.3f}"
          f"{'—':>8}{0.0:>8.3f}{0.0:>8.1f}%")

    maj = results[(list(MODELS)[0], 'A', scheme)]['majority_rate']
    print(f"\n  Ketosis classification (threshold "
          f"{KETOSIS_THRESHOLD} mmol/L)")
    print(f"  {'model':<14}{'cfg':<5}{'raw acc':>9}{'sens':>8}{'spec':>8}"
          f"{'balanced':>10}{'n sub pred':>12}")
    print("  " + "-" * 66)
    for model in MODELS:
        for cfg in CONFIGS:
            r = results[(model, cfg, scheme)]
            print(f"  {model:<14}{cfg:<5}{r['ketosis_accuracy']*100:>8.1f}%"
                  f"{r['ketosis_sensitivity']*100:>7.1f}%"
                  f"{r['ketosis_specificity']*100:>7.1f}%"
                  f"{r['ketosis_balanced']*100:>9.1f}%"
                  f"{r['n_sub_predicted']:>12}")
    print(f"  {'majority class':<19}{maj*100:>8.1f}%{100.0:>7.1f}%"
          f"{0.0:>7.1f}%{50.0:>9.1f}%{0:>12}")
    print("  Raw accuracy at or near the majority rate with 0 sub-ketosis "
          "predictions\n  indicates the model is not discriminating; "
          "balanced accuracy is the\n  informative figure.")


def ablation_tests(results: dict, scheme: str):
    """Wilcoxon signed-rank on paired per-observation absolute errors."""
    print(f"\n  Ablation significance ({scheme}, Wilcoxon signed-rank, "
          "paired absolute errors)")
    print(f"  {'model':<14}{'comparison':<12}{'ΔRMSE':>9}{'p':>10}  verdict")
    print("  " + "-" * 58)
    out = []
    for model in MODELS:
        base = results[(model, 'A', scheme)]
        for cfg in ('B', 'C', 'D'):
            r = results[(model, cfg, scheme)]
            d_rmse = r['rmse'] - base['rmse']
            try:
                stat, p = wilcoxon(base['abs_errors'], r['abs_errors'])
            except ValueError:
                p = np.nan
            verdict = ('worse without' if p < 0.05 and d_rmse > 0
                       else 'no sig. loss' if p >= 0.05
                       else 'better without')
            print(f"  {model:<14}A->{cfg:<9}{d_rmse:>+9.3f}{p:>10.4f}"
                  f"  {verdict}")
            out.append(dict(model=model, scheme=scheme, comparison=f'A->{cfg}',
                            delta_rmse=d_rmse, p_value=p))
    return out


def baseline_test(results: dict, scheme: str, y: np.ndarray):
    """Model D vs the naive mean-predictor — the §3.7.4 criterion."""
    naive_err = np.abs(y - y.mean())
    print(f"\n  Model D vs naive mean-predictor ({scheme})")
    for model in MODELS:
        r = results[(model, 'D', scheme)]
        try:
            stat, p = wilcoxon(r['abs_errors'], naive_err)
        except ValueError:
            p = np.nan
        sig = 'significant' if p < 0.05 else 'not significant'
        print(f"    {model:<14} RMSE {r['rmse']:.3f} vs {np.std(y):.3f}"
              f"   p={p:.4f}  ({sig})")


def importance_report(X_by_cfg: dict, y: np.ndarray, out_dir: Path):
    """RF importance on the full data, per configuration (§3.5.3)."""
    print("\n" + "=" * 66)
    print("FEATURE IMPORTANCE  (Random Forest, full-data fit)")
    print("=" * 66)
    print("  Correlated features split importance between them; treat "
          "per-feature\n  ranking as indicative and the group-level ablation "
          "above as primary.")
    rows = []
    for cfg, (X, _) in X_by_cfg.items():
        m = make_rf()
        m.fit(prepare(X, 'RandomForest'), y)
        imp = pd.Series(m.feature_importances_, index=X.columns) \
                .sort_values(ascending=False)
        print(f"\n  Model {cfg} — top 12 of {len(imp)}")
        for name, v in imp.head(12).items():
            bar = '#' * int(round(v * 200))
            print(f"    {name:<34}{v:>7.4f}  {bar}")
        for name, v in imp.items():
            rows.append(dict(config=cfg, feature=name, importance=v))
    pd.DataFrame(rows).to_csv(out_dir / 'feature_importance.csv', index=False)


# ── Main ──────────────────────────────────────────────────────

def run(features_dir: str, prefix: str, out_dir: str | None):
    fdir = Path(features_dir)
    odir = Path(out_dir) if out_dir else fdir / 'results'
    odir.mkdir(parents=True, exist_ok=True)

    print("=" * 66)
    print("SYNCHRONIZE — MODEL TRAINING AND ABLATION")
    print(f"Features: {fdir.resolve()}")
    if prefix:
        print(f"Variant : {prefix}")
    print(f"Seed    : {RANDOM_SEED}")
    print(f"Models  : {', '.join(MODELS)}"
          + ("" if HAS_XGB else "   (xgboost not installed — RF only)"))
    print("=" * 66)

    X_by_cfg, y, groups = {}, None, None
    for cfg, (fname, desc) in CONFIGS.items():
        path = fdir / f'{prefix}{fname}.csv'
        if not path.exists():
            raise SystemExit(f"missing: {path}")
        df = pd.read_csv(path)
        feats = [c for c in df.columns if c not in META]
        X = df[feats].select_dtypes(include=[np.number])
        X_by_cfg[cfg] = (X, desc)
        if y is None:
            y = df[LABEL_COL].to_numpy(dtype=float)
            groups = df['date_key'].to_numpy()
        print(f"  Model {cfg}  {len(X.columns):>3} features  ({desc})")

    baseline = float(np.std(y))
    n_days = len(np.unique(groups))
    print(f"\n  Labels {len(y)} across {n_days} days   "
          f"BHB mean {y.mean():.2f}  SD {baseline:.2f}")
    print(f"  Naive baseline RMSE {baseline:.3f}   "
          f"Model A criterion (30% below) {baseline*0.7:.3f}")
    n_sub = int((y < KETOSIS_THRESHOLD).sum())
    print(f"  Two-class split: sub-ketosis {n_sub}, "
          f"in-ketosis {len(y)-n_sub}")

    results = {}
    print("\n  Running cross-validation...")
    for scheme in ('loocv', 'leave_one_day_out'):
        for model in MODELS:
            for cfg, (X, _) in X_by_cfg.items():
                t0 = time.time()
                pred = run_cv(X, y, groups, model, scheme)
                results[(model, cfg, scheme)] = metrics(y, pred)
                print(f"    {scheme:<18}{model:<14}{cfg}  "
                      f"{time.time()-t0:5.1f}s", flush=True)

    print("\n" + "=" * 66)
    print("PERFORMANCE MATRIX")
    print("=" * 66)
    for scheme in ('leave_one_day_out', 'loocv'):
        performance_table(results, scheme, baseline)

    # leakage gap
    print("\n  LOOCV minus leave-one-day-out RMSE "
          "(negative = LOOCV optimistic; lower RMSE is better):")
    for model in MODELS:
        gaps = [results[(model, c, 'loocv')]['rmse']
                - results[(model, c, 'leave_one_day_out')]['rmse']
                for c in CONFIGS]
        print(f"    {model:<14}" + "  ".join(
            f"{c}:{g:+.3f}" for c, g in zip(CONFIGS, gaps)))

    print("\n" + "=" * 66)
    print("ABLATION")
    print("=" * 66)
    tests = []
    for scheme in ('leave_one_day_out', 'loocv'):
        tests += ablation_tests(results, scheme)
        baseline_test(results, scheme, y)

    importance_report(X_by_cfg, y, odir)

    rows = []
    for (model, cfg, scheme), r in results.items():
        rows.append(dict(model=model, config=cfg, cv=scheme,
                         n_features=len(X_by_cfg[cfg][0].columns),
                         rmse=r['rmse'], mae=r['mae'], r2=r['r2'],
                         pct_below_baseline=r['pct_below_baseline'],
                         ketosis_accuracy=r['ketosis_accuracy'],
                         ketosis_sensitivity=r['ketosis_sensitivity'],
                         ketosis_specificity=r['ketosis_specificity'],
                         ketosis_balanced=r['ketosis_balanced'],
                         n_sub_predicted=r['n_sub_predicted']))
    pd.DataFrame(rows).to_csv(odir / 'performance_matrix.csv', index=False)
    pd.DataFrame(tests).to_csv(odir / 'ablation_tests.csv', index=False)
    with open(odir / 'run_config.json', 'w') as f:
        json.dump({'seed': RANDOM_SEED, 'sentinel': SENTINEL,
                   'ketosis_threshold': KETOSIS_THRESHOLD,
                   'n_labels': len(y), 'n_days': int(n_days),
                   'naive_rmse': baseline, 'prefix': prefix,
                   'models': list(MODELS)}, f, indent=2)

    print("\n" + "=" * 66)
    print(f"Wrote performance_matrix.csv, ablation_tests.csv, "
          f"feature_importance.csv,\nrun_config.json to {odir.resolve()}")
    print("=" * 66)


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--features', required=True)
    p.add_argument('--prefix', default='',
                   help="e.g. 'sens_no_anomaly_' or 'sens_extended_'")
    p.add_argument('--out', default=None)
    a = p.parse_args()
    run(a.features, a.prefix, a.out)