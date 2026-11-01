#!/usr/bin/env python3
"""
Synchronize — Feature Engineering Pipeline  (v2)
=================================================
Builds the ML training matrix described in thesis §3.4.

One row per blood ketone reading (the label). Features are extracted from the
period preceding each reading and joined across three sources:

  1. Synchronize app database  — ketone labels, meals, exercise, covariates
  2. Samsung Health export     — HRV, HR, SpO2, skin temp, respiratory, stress,
                                 sleep stages, step count, exercise session HR
  3. Dexcom Clarity export     — 5-minute CGM readings (source of record)

--------------------------------------------------------------------------
PRE-REGISTERED DECISIONS  (dated; do not change without recording a deviation)
--------------------------------------------------------------------------
2026-08-14  Training window is 2026-08-02 .. 2026-08-31 inclusive. Replicate
            betaHB spread was 0.20 mmol/L (max 0.40) on 07-30..07-31 versus
            0.08 (max 0.20) from 08-02, indicating technique proficiency
            lagged the 07-30 protocol revision by ~2 days. Selection is on
            instrument replicate spread, measured independently of any
            feature-label relationship.
2026-08-14  Sleep-gated streams (HRV, SpO2, skin temperature, respiratory
            rate) are represented as most-recent-value + hours-since, not as
            pre-label windows. Measured: 90.7% of HRV samples fall 23:00-06:00
            and 0.4% fall 08:00-20:00; pre-label windows returned no data for
            100% of non-fasting labels.
2026-08-14  Glucose AUC threshold 140 -> 100 mg/dL. 140 is a diabetic-
            population convention never exceeded by this subject.
2026-08-14  Zero-variance features dropped at write time, detected not
            hardcoded, so a covariate that begins varying is retained.
2026-09-02  day_of_study and day_of_week removed. day_of_study is confounded
            with deliberate dietary variation across the window; day_of_week
            carries no signal as the exercise and meal schedule was uniform.
2026-09-02  CGM source of record is the Clarity export. The app database
            stopped receiving EGVs on 08-12 (OAuth token expiry, not
            reauthorized during collection) and covers only 8 of 30 days.
2026-09-02  First CGM_WARMUP_EXCLUDE_H hours of each sensor session excluded
            from CGM features. Sub-55 readings occur at 10.1% in the first 6
            hours of a session versus 0% beyond 120 hours; sensors were
            consistently replaced in the afternoon, producing an apparent
            evening clustering with no physiological basis.
2026-09-02  2026-08-23 flagged (not deleted) as a CGM anomaly: 48 sub-55
            readings at ~72h sensor age with a concurrent capillary reference
            of 96 mg/dL. Evaluated by sensitivity variant.
2026-09-02  glucose_tbr_* and glucose_min_dev_* removed. Added 08-14 on the
            inference that overnight lows were physiological; they are
            predominantly warmup artifact, so these features would encode
            sensor mechanics rather than metabolism.
2026-09-02  AGEs Index excluded. 30/30 day coverage but varies over 402-412
            with <=2 units between consecutive days: a smoothed trailing
            aggregate, confounded with dietary variation on the same grounds
            as day_of_study.
2026-09-02  respiratory_rate == 0 and stress score == 0 treated as null.
            Respiratory: non-zero minimum 9.51, nothing below 6, so 0 is a
            clean sentinel. Stress: wake-gated (0-33% valid overnight, 84-96%
            at 06:00-09:00); 0 denotes an unscoreable measurement.

Usage:
    python build_features_v2.py --db <path/to/synchronize_db> \
                         --samsung samsung_parsed \
                         --dexcom <path/to/clarity_export.csv> \
                         --out features
"""

from __future__ import annotations

import argparse
import os
import re
import sqlite3
from datetime import datetime
from pathlib import Path

import numpy as np
import pandas as pd

# ── Configuration ─────────────────────────────────────────────

LOCAL_TZ = os.environ.get('SYNCHRONIZE_TZ', 'America/Los_Angeles')

WINDOW_START = '2026-08-02 00:00:00'
WINDOW_END = '2026-09-01 00:00:00'          # half-open: < this instant
EXTENDED_START = '2026-07-30 00:00:00'      # sensitivity variant
CGM_ANOMALY_DAYS = ['2026-08-23']           # flagged, not deleted

WINDOWS_MIN = [30, 60, 120]
DIETARY_WINDOW_MIN = 240
MORNING_CONTEXTS = ['FASTING', 'PRE_LUNCH']

GLUCOSE_TIR_LOW = 70
GLUCOSE_TIR_HIGH = 140
GLUCOSE_AUC_THRESHOLD = 100
CGM_WARMUP_EXCLUDE_H = 12

SLEEP_GATED_LOOKBACK_H = 24
EXERCISE_MATCH_TOLERANCE_MIN = 15
STEPS_EXTENDED_MIN = 240          # 4h, matching the dietary window
INCIDENTAL_WINDOW_MIN = 240       # unlogged (auto-detected) activity lookback
MORNING_ONLY_PREFIXES = ('sleep_',)
CORRELATION_FLAG = 0.95

MS_PER_MIN = 60_000
DATE_PAT = re.compile(r'^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}')


# ── Timestamp handling ────────────────────────────────────────

def to_epoch_ms(series, assume_local: bool = False) -> pd.Series:
    """
    Convert datetime-like values to epoch milliseconds regardless of the
    resolution pandas infers. assume_local=True for sources exporting naive
    local timestamps (Clarity, Samsung date strings); app timestamps are
    already true epoch.
    """
    dt = pd.to_datetime(series, errors='coerce')
    if assume_local:
        try:
            dt = dt.dt.tz_localize(LOCAL_TZ, ambiguous='NaT',
                                   nonexistent='NaT').dt.tz_convert('UTC')
        except (TypeError, AttributeError):
            pass
    if getattr(dt.dtype, 'tz', None) is not None:
        dt = dt.dt.tz_convert('UTC').dt.tz_localize(None)
    return (dt - pd.Timestamp('1970-01-01')) // pd.Timedelta('1ms')


def ms(literal: str) -> int:
    return int(to_epoch_ms(pd.Series([literal]), assume_local=True).iloc[0])


def local_dt(ms_value) -> datetime:
    return pd.Timestamp(ms_value, unit='ms', tz='UTC').tz_convert(
        LOCAL_TZ).tz_localize(None).to_pydatetime()


# ── Loaders ───────────────────────────────────────────────────

def load_app_db(db_path: str) -> dict:
    posix = Path(db_path).resolve().as_posix()
    if len(posix) > 1 and posix[1] == ':':
        posix = '/' + posix
    conn = sqlite3.connect(f'file:{posix}?mode=ro&immutable=1', uri=True)
    out = {}
    for table in ('ketone_readings', 'meal_entries',
                  'exercise_sessions', 'daily_covariates'):
        try:
            out[table] = pd.read_sql_query(f'SELECT * FROM {table}', conn)
            print(f"  [{table}] {len(out[table]):,} rows")
        except Exception as e:
            print(f"  [{table}] unavailable: {e}")
            out[table] = pd.DataFrame()
    conn.close()
    return out


def _detect_time_column(df: pd.DataFrame):
    for col in df.columns:
        s = df[col].dropna()
        if not len(s):
            continue
        n = pd.to_numeric(s, errors='coerce').dropna()
        if len(n) / len(s) > 0.8 and n.between(1.5e12, 2.0e12).mean() > 0.8:
            return col, 'epoch_ms'
    best, score = None, 0.0
    for col in df.columns:
        s = df[col].dropna()
        if not len(s):
            continue
        frac = s.astype(str).str.match(DATE_PAT).mean()
        if frac > score:
            best, score = col, frac
    return (best, 'datetime_str') if score > 0.8 else (None, None)


# Streams whose zero values are sentinels, not measurements (§ pre-registered)
NULL_ZERO = {'respiratory_rate': ['respiratory_rate'],
             'stress': ['score', 'score_max', 'score_min']}


def load_samsung(parsed_dir: str) -> dict:
    d = Path(parsed_dir)
    wanted = ['hrv', 'heart_rate', 'spo2', 'skin_temperature', 'stress',
              'respiratory_rate', 'sleep_stage', 'step_count', 'exercise']
    streams = {}
    for name in wanted:
        path = d / f'{name}.csv'
        if not path.exists():
            print(f"  [{name}] not found")
            streams[name] = pd.DataFrame()
            continue

        df = pd.read_csv(path, index_col=False, low_memory=False)

        if 'timestamp_ms' in df.columns:
            df['ts'] = pd.to_numeric(df['timestamp_ms'], errors='coerce')
        else:
            col, kind = _detect_time_column(df)
            if col is None:
                print(f"  [{name}] no timestamp column — unusable")
                streams[name] = pd.DataFrame()
                continue
            df['ts'] = (pd.to_numeric(df[col], errors='coerce')
                        if kind == 'epoch_ms'
                        else to_epoch_ms(df[col], assume_local=True))

        nulled = 0
        for col in NULL_ZERO.get(name, []):
            if col in df.columns:
                v = pd.to_numeric(df[col], errors='coerce')
                nulled += int((v == 0).sum())
                df[col] = v.replace(0, np.nan)

        df = df.dropna(subset=['ts']).sort_values('ts').reset_index(drop=True)
        streams[name] = df
        note = f"  ({nulled:,} sentinel zeros nulled)" if nulled else ""
        print(f"  [{name}] {len(df):,} rows{note}")
    return streams


def load_dexcom(csv_path: str) -> tuple[pd.DataFrame, pd.DataFrame]:
    """
    Clarity export. Returns (egvs, calibrations).

    Sensor sessions are identified by Transmitter ID so that the warmup
    exclusion can be applied per session.
    """
    if not csv_path or not Path(csv_path).exists():
        print("  [dexcom] no file provided")
        return pd.DataFrame(), pd.DataFrame()

    df = pd.read_csv(csv_path, index_col=False, low_memory=False)
    ts_col = next((c for c in df.columns if 'Timestamp' in c), None)
    val_col = next((c for c in df.columns if 'Glucose Value' in c), None)
    type_col = next((c for c in df.columns if c.strip() == 'Event Type'), None)
    tx_col = next((c for c in df.columns if 'Transmitter ID' in c), None)

    if ts_col is None or val_col is None:
        print(f"  [dexcom] unexpected columns: {list(df.columns)[:6]}")
        return pd.DataFrame(), pd.DataFrame()

    etype = df[type_col].astype(str).str.strip() if type_col else None

    cal = pd.DataFrame()
    if etype is not None:
        c = df[etype == 'Calibration']
        if len(c):
            cal = pd.DataFrame({
                'ts': to_epoch_ms(c[ts_col], assume_local=True),
                'reference_mgdl': pd.to_numeric(c[val_col], errors='coerce'),
                'transmitter': c[tx_col] if tx_col else np.nan,
            }).dropna(subset=['ts'])

    egv = df[etype == 'EGV'] if etype is not None else df
    out = pd.DataFrame({
        'ts': to_epoch_ms(egv[ts_col], assume_local=True),
        'glucose': pd.to_numeric(egv[val_col], errors='coerce'),
        'transmitter': egv[tx_col] if tx_col else 'unknown',
    }).dropna(subset=['ts', 'glucose']).sort_values('ts').reset_index(drop=True)

    # Sensor age, per session, for the warmup exclusion
    out['session_start'] = out.groupby('transmitter')['ts'].transform('min')
    out['sensor_age_h'] = (out['ts'] - out['session_start']) / MS_PER_MIN / 60
    warm = out['sensor_age_h'] < CGM_WARMUP_EXCLUDE_H
    n_warm = int(warm.sum())
    out = out[~warm].reset_index(drop=True)

    # Rate of change from consecutive readings; Clarity's own column is blank
    dt_min = out['ts'].diff() / MS_PER_MIN
    out['roc'] = out['glucose'].diff() / dt_min
    out.loc[dt_min < 1, 'roc'] = np.nan
    out.loc[dt_min > 15, 'roc'] = np.nan      # don't bridge gaps

    print(f"  [dexcom] {len(out):,} EGVs after excluding {n_warm:,} within "
          f"{CGM_WARMUP_EXCLUDE_H}h of sensor insertion "
          f"({out['transmitter'].nunique()} sensor sessions)")
    if len(cal):
        print(f"  [dexcom] {len(cal)} user calibrations recorded")
    return out, cal


# ── Window helpers ────────────────────────────────────────────

def window(df: pd.DataFrame, center_ms: int, minutes: int,
           ts_col: str = 'ts') -> pd.DataFrame:
    if df.empty or ts_col not in df.columns:
        return df.iloc[0:0]
    return df[(df[ts_col] >= center_ms - minutes * MS_PER_MIN) &
              (df[ts_col] <= center_ms)]


def safe(series, fn) -> float:
    s = pd.to_numeric(pd.Series(series), errors='coerce').dropna()
    return float(fn(s)) if len(s) else np.nan


def most_recent(df: pd.DataFrame, label_ms: int, col: str,
                lookback_h: float = SLEEP_GATED_LOOKBACK_H) -> tuple:
    """
    Latest non-null value at or before label_ms, with its age in hours.

    A pre-label window is the wrong instrument for a signal the watch records
    only during sleep: it returns NaN for four of five daily reading contexts.
    Carrying the last real measurement forward, with staleness explicit,
    lets the model discount an old reading rather than hiding the gap.
    """
    if df.empty or col not in df.columns:
        return np.nan, np.nan
    floor = label_ms - lookback_h * 60 * MS_PER_MIN
    w = df[(df['ts'] <= label_ms) & (df['ts'] >= floor)]
    if w.empty:
        return np.nan, np.nan
    v = pd.to_numeric(w[col], errors='coerce')
    w = w[v.notna()]
    if w.empty:
        return np.nan, np.nan
    last = w.iloc[-1]
    return (float(pd.to_numeric(last[col])),
            float((label_ms - last['ts']) / MS_PER_MIN / 60))


# ── Feature builders ──────────────────────────────────────────

def cgm_features(glucose: pd.DataFrame, label_ms: int) -> dict:
    f = {}
    for mins in WINDOWS_MIN:
        w = window(glucose, label_ms, mins)
        tag = f'{mins}min'
        vals = pd.to_numeric(w['glucose'], errors='coerce').dropna() \
            if len(w) else pd.Series(dtype=float)

        f[f'glucose_mean_{tag}'] = safe(vals, np.mean)
        f[f'glucose_std_{tag}'] = safe(vals, np.std)
        f[f'glucose_min_{tag}'] = safe(vals, np.min)
        f[f'glucose_max_{tag}'] = safe(vals, np.max)

        m, s = f[f'glucose_mean_{tag}'], f[f'glucose_std_{tag}']
        f[f'glucose_cv_{tag}'] = (s / m * 100
                                  if m and not np.isnan(m) and m != 0
                                  else np.nan)

        if len(w) >= 3:
            x = (w['ts'] - w['ts'].min()) / MS_PER_MIN
            y = pd.to_numeric(w['glucose'], errors='coerce')
            ok = y.notna()
            f[f'glucose_slope_{tag}'] = (float(np.polyfit(x[ok], y[ok], 1)[0])
                                         if ok.sum() >= 3 else np.nan)
        else:
            f[f'glucose_slope_{tag}'] = np.nan

        if len(vals):
            f[f'glucose_tir_{tag}'] = float(
                ((vals >= GLUCOSE_TIR_LOW) & (vals <= GLUCOSE_TIR_HIGH)).mean())
            f[f'glucose_auc_{tag}'] = float(
                (vals - GLUCOSE_AUC_THRESHOLD).clip(lower=0).sum() * 5)
        else:
            f[f'glucose_tir_{tag}'] = np.nan
            f[f'glucose_auc_{tag}'] = np.nan

        f[f'glucose_n_{tag}'] = int(len(vals))

    w = window(glucose, label_ms, 15)
    f['glucose_roc'] = safe(w['roc'], np.mean) if len(w) else np.nan
    f['glucose_at_label'] = float(w.iloc[-1]['glucose']) if len(w) else np.nan
    return f


def biosignal_features(streams: dict, label_ms: int) -> dict:
    """
    Continuous-during-wear streams use the §3.4 multi-resolution windows.
    Sleep-gated streams use most-recent-value + staleness.

    Stress is wake-gated (the mirror case): valid 84-96% of samples at
    06:00-09:00, 0-33% overnight. It keeps its windows, with a most-recent
    fallback for labels whose window contains only unscoreable samples.
    """
    f = {}

    for mins in WINDOWS_MIN:
        tag = f'{mins}min'

        hr = window(streams.get('heart_rate', pd.DataFrame()), label_ms, mins)
        hr_col = next((c for c in ('heart_rate', 'value', 'mean')
                    if c in hr.columns), None)
        if hr_col and len(hr):
            f[f'hr_mean_{tag}'] = safe(hr[hr_col], np.mean)
            f[f'hr_min_{tag}'] = safe(hr[hr_col], np.min)
            f[f'hr_max_{tag}'] = safe(hr[hr_col], np.max)
            f[f'hr_std_{tag}'] = safe(hr[hr_col], np.std)
        else:
            for k in ('mean', 'min', 'max', 'std'):
                f[f'hr_{k}_{tag}'] = np.nan

        st = window(streams.get('stress', pd.DataFrame()), label_ms, mins)
        st_col = next((c for c in ('score', 'stress_score', 'value')
                    if c in st.columns), None)
        f[f'stress_mean_{tag}'] = (safe(st[st_col], np.mean)
                                if st_col and len(st) else np.nan)

        steps = window(streams.get('step_count', pd.DataFrame()), label_ms, mins)
        sc = next((c for c in ('com.samsung.health.step_count.count',
                            'count', 'walk_step') if c in steps.columns), None)
        f[f'steps_{tag}'] = (safe(steps[sc], np.sum)
                            if sc and len(steps) else 0.0)
    # Extended step window. 30/60/120 min capture activity immediately before
    # a reading; 4h matches the dietary window and the metabolic-lag reasoning
    # in §3.4, and captures walks that ended earlier in the day.
    steps = window(streams.get('step_count', pd.DataFrame()), label_ms,
                        STEPS_EXTENDED_MIN) 
    sc = next((c for c in ('com.samsung.health.step_count.count',
                            'count', 'walk_step') if c in steps.columns), None)
    f[f'steps_{STEPS_EXTENDED_MIN}min'] = (safe(steps[sc], np.sum)
                                           if sc and len(steps) else 0.0)
                                            
        



    # stress fallback when the window held only unscoreable samples
    sdf = streams.get('stress', pd.DataFrame())
    s_col = next((c for c in ('score', 'stress_score', 'value')
                  
                  if c in sdf.columns), None)
    if s_col:
        v, age = most_recent(sdf, label_ms, s_col)
        f['stress_recent'] = v
        f['stress_hours_since'] = age
    else:
        f['stress_recent'] = f['stress_hours_since'] = np.nan

    # All four sleep-gated streams are measured in the same overnight window,
    # so their staleness columns were identical (r = 1.000 across all pairs).
    # Emitting one per stream counted a single signal four times, dividing its
    # importance four ways in the ablation ranking. Collapsed 2026-09-02.
    gated = {
        'hrv': [('rmssd', 'hrv_rmssd'), ('sdnn', 'hrv_sdnn')],
        'spo2': [(['spo2', 'oxygen_saturation', 'value'], 'spo2')],
        'skin_temperature': [(['mean', 'temperature', 'value'], 'skin_temp')],
        'respiratory_rate': [(['respiratory_rate', 'value'],
                            'respiratory_rate')],
    }
    ages = []
    for stream, specs in gated.items():
        df = streams.get(stream, pd.DataFrame())
        for spec, out_name in specs:
            col = (next((c for c in spec if c in df.columns), None
                        
                        )
                    if isinstance(spec, list)
                    else (spec if spec in df.columns else None))
            if col is None:
                f[f'{out_name}_recent'] = np.nan
                continue
            v, age = most_recent(df, label_ms, col)
            f[f'{out_name}_recent'] = v
            if not np.isnan(age):
                ages.append(age)

    f['overnight_hours_since'] = float(np.median(ages)) if ages else np.nan
    return f



def dietary_features(meals: pd.DataFrame, cov: pd.DataFrame,
                    label_ms: int) -> dict:
    keys = ['protein_g', 'net_carb_g', 'fiber_g', 'fat_g', 'calories',
            'ketogenic_ratio', 'minutes_since_meal', 'meal_count_4h',
            'is_fasted_4h', 'caffeine_mg', 'meditation_am_min',
            'meditation_pm_min']
    if meals.empty:
        return {k: np.nan for k in keys}

    f = {}
    w = window(meals, label_ms, DIETARY_WINDOW_MIN, ts_col='deviceTimestamp')

    f['protein_g'] = float(w['proteinGrams'].sum()) if len(w) else 0.0
    f['net_carb_g'] = float(w['netCarbGrams'].sum()) if len(w) else 0.0
    f['fiber_g'] = float(w['fiberGrams'].sum()) if len(w) else 0.0
    f['fat_g'] = float(w['fatGrams'].sum()) if len(w) else 0.0
    f['calories'] = float(w['calories'].sum()) if len(w) else 0.0
    f['meal_count_4h'] = int(len(w))

    # Explicit fasted state. Without this, "no meal in 4h" is encoded twice:
    # macros at 0.0 and ketogenic_ratio at NaN. The binary makes the
    # physiological state a fact the model can use rather than a hole.
    f['is_fasted_4h'] = int(len(w) == 0)

    denom = f['net_carb_g'] + f['protein_g']
    f['ketogenic_ratio'] = f['fat_g'] / denom if denom > 0 else np.nan

    prior = meals[meals['deviceTimestamp'] <= label_ms]
    f['minutes_since_meal'] = (
        float((label_ms - prior['deviceTimestamp'].max()) / MS_PER_MIN)
        if len(prior) else np.nan)

    f['caffeine_mg'] = f['meditation_am_min'] = f['meditation_pm_min'] = np.nan
    if not cov.empty:
        key = local_dt(label_ms).strftime('%Y-%m-%d')
        row = cov[cov['dateKey'] == key]
        if len(row):
            r = row.iloc[0]
            f['caffeine_mg'] = float(r['caffeineMg'])
            f['meditation_am_min'] = float(r['meditationAmMinutes'])
            f['meditation_pm_min'] = float(r['meditationPmMinutes'])
    return f


def exercise_features(ex: pd.DataFrame, samsung_ex: pd.DataFrame,
                      samsung_unmatched: pd.DataFrame, label_ms: int) -> dict:
    """
    Structured activity type and the is_hiit flag come from the app (the
    training-relevant labels, §3.3). Session physiology comes from the Samsung
    export, matched on start time within EXERCISE_MATCH_TOLERANCE_MIN.
    """
    f = {'is_hiit_day': 0, 'exercise_duration_min': 0.0,       
         
         'minutes_since_exercise': np.nan, 'exercise_today': 0,
         'session_mean_hr': np.nan, 'session_max_hr': np.nan,
         'session_min_hr': np.nan, 'samsung_active_min_4h': 0.0}
    if ex.empty:
        return f

    # Incidental activity: watch-recorded sessions with no app-logged
    # counterpart, i.e. auto-detected walking. Kept separate from the
    # structured protocol so the same session is never counted twice.
    if not samsung_unmatched.empty:
        w = samsung_unmatched[
            (samsung_unmatched['ts'] <= label_ms) &
            (samsung_unmatched['ts'] >= label_ms
             - INCIDENTAL_WINDOW_MIN * MS_PER_MIN)]
        dur = next((c for c in ('com.samsung.health.exercise.duration',
                                'duration') if c in w.columns), None)
        if dur is not None and len(w):
            f['samsung_active_min_4h'] = float(
                 pd.to_numeric(w[dur], errors='coerce').sum() / MS_PER_MIN)
    if ex.empty:
        return f
            
    key = local_dt(label_ms).strftime('%Y-%m-%d')
    e = ex.copy()
    e['date_key'] = e['startTimestamp'].apply(
        lambda t: local_dt(t).strftime('%Y-%m-%d'))

    today = e[e['date_key'] == key]
    if len(today):
        f['exercise_today'] = 1
        f['is_hiit_day'] = int(today['isHiit'].max())
        f['exercise_duration_min'] = float(today['durationMinutes'].sum())

    prior = e[e['startTimestamp'] <= label_ms]
    if len(prior):
        last = prior.loc[prior['startTimestamp'].idxmax()]
        f['minutes_since_exercise'] = float(
            (label_ms - last['startTimestamp']) / MS_PER_MIN)

        if not samsung_ex.empty:
            tol = EXERCISE_MATCH_TOLERANCE_MIN * MS_PER_MIN
            near = samsung_ex[
                (samsung_ex['ts'] - last['startTimestamp']).abs() <= tol]
            if len(near):
                s = near.iloc[
                    (near['ts'] - last['startTimestamp']).abs().argmin()]
                for out, cands in (
                        ('session_mean_hr',
                         ['com.samsung.health.exercise.mean_heart_rate']),
                        ('session_max_hr',
                         ['com.samsung.health.exercise.max_heart_rate']),
                        ('session_min_hr',
                         ['com.samsung.health.exercise.min_heart_rate'])):
                    col = next((c for c in cands if c in near.columns), None)
                    if col is not None:
                        f[out] = float(pd.to_numeric(s[col], errors='coerce'))
    return f


def sleep_features(streams: dict, label_ms: int, context: str) -> dict:
    """
    §3.4 sleep architecture vector — morning readings only.

    sleep_stage carries several timestamp columns under misleading names
    (stage bounds, record create/update). Both the stage column and the
    start/end pair are located by content.
    """
    keys = ['sleep_total_min', 'sleep_deep_pct', 'sleep_rem_pct',
            'sleep_light_pct', 'sleep_awake_pct', 'sleep_overnight_rmssd',
            'sleep_skin_temp_mean', 'sleep_skin_temp_delta']
    f = {k: np.nan for k in keys}
    if context not in MORNING_CONTEXTS:
        return f

    stages = streams.get('sleep_stage', pd.DataFrame())
    if stages.empty:
        return f
    stages = stages.copy()

    VALID = {1, 2, 3, 4, 5, 6, 40001, 40002, 40003, 40004}
    stage_col, best = None, 0.0
    for c in stages.columns:
        n = pd.to_numeric(stages[c], errors='coerce').dropna()
        if not len(n):
            continue
        frac = n.astype(int).isin(VALID).mean()
        if frac > best:
            stage_col, best = c, frac
    if stage_col is None or best < 0.8:
        return f

    date_cols = []
    for c in stages.columns:
        p = pd.to_datetime(stages[c], errors='coerce')
        if p.notna().mean() > 0.8:
            y = p.dt.year.dropna()
            if len(y) and y.between(2020, 2030).mean() > 0.8:
                date_cols.append(c)
    if len(date_cols) < 2:
        return f

    pair, gap_best = None, None
    for i, c1 in enumerate(date_cols):
        for c2 in date_cols[i + 1:]:
            a = to_epoch_ms(stages[c1], assume_local=True)
            b = to_epoch_ms(stages[c2], assume_local=True)
            g = (b - a).median()
            if pd.isna(g):
                continue
            g = abs(g)
            if MS_PER_MIN <= g <= 4 * 60 * MS_PER_MIN:
                if gap_best is None or g < gap_best:
                    pair, gap_best = (c1, c2), g
    if pair is None:
        return f

    a = to_epoch_ms(stages[pair[0]], assume_local=True)
    b = to_epoch_ms(stages[pair[1]], assume_local=True)
    stages['start_ms'] = np.minimum(a, b)
    stages['end_ms'] = np.maximum(a, b)

    recent = stages[(stages['end_ms'] <= label_ms) &
                    (stages['end_ms'] >= label_ms - 18 * 60 * MS_PER_MIN)].copy()
    if recent.empty:
        return f

    recent['dur'] = (recent['end_ms'] - recent['start_ms']) / MS_PER_MIN
    total = recent['dur'].sum()
    if total <= 0:
        return f
    f['sleep_total_min'] = float(total)

    codes = set(pd.to_numeric(recent[stage_col], errors='coerce')
                .dropna().astype(int))
    smap = ({40001: 'awake', 40002: 'light', 40003: 'deep', 40004: 'rem'}
            if codes & {40001, 40002, 40003, 40004}
            else {1: 'awake', 2: 'light', 3: 'deep', 4: 'rem', 5: 'rem',
                  6: 'light'})
    recent['name'] = pd.to_numeric(recent[stage_col], errors='coerce').map(smap)
    for nm in ('deep', 'rem', 'light', 'awake'):
        f[f'sleep_{nm}_pct'] = float(
            recent[recent['name'] == nm]['dur'].sum() / total * 100)

    s0, s1 = recent['start_ms'].min(), recent['end_ms'].max()

    hrv = streams.get('hrv', pd.DataFrame())
    if not hrv.empty and 'rmssd' in hrv.columns:
        o = hrv[(hrv['ts'] >= s0) & (hrv['ts'] <= s1)]
        f['sleep_overnight_rmssd'] = safe(o['rmssd'], np.mean)

    skin = streams.get('skin_temperature', pd.DataFrame())
    sc = next((c for c in ('mean', 'temperature', 'value')
               if c in skin.columns), None)
    if sc and not skin.empty:
        o = skin[(skin['ts'] >= s0) & (skin['ts'] <= s1)]
        v = pd.to_numeric(o[sc], errors='coerce').dropna()
        if len(v):
            f['sleep_skin_temp_mean'] = float(v.mean())
        if len(v) >= 2:
            f['sleep_skin_temp_delta'] = float(v.iloc[-1] - v.iloc[0])
    return f


def temporal_features(label_ms: int) -> dict:
    """
    hour_of_day only. day_of_study is confounded with deliberate dietary
    variation; day_of_week carries no signal under a uniform weekly schedule.
    Both removed 2026-09-02 (pre-registered).
    """
    dt = local_dt(label_ms)
    return {'hour_of_day': dt.hour + dt.minute / 60.0}


# ── Matrix assembly ───────────────────────────────────────────

def build_matrix(app: dict, samsung: dict, dexcom: pd.DataFrame,
                 start_ms: int, end_ms: int, label: str) -> pd.DataFrame:
    ketones = app['ketone_readings'].copy()
    if ketones.empty:
        raise SystemExit('No ketone readings — nothing to build.')

    n0 = len(ketones)
    ketones = ketones[(ketones['deviceTimestamp'] >= start_ms) &
                      (ketones['deviceTimestamp'] < end_ms)]
    print(f"  [{label}] {len(ketones)} labels "
          f"({n0 - len(ketones)} outside window excluded)")

    ketones = ketones.sort_values('deviceTimestamp').reset_index(drop=True)
    samsung_ex = samsung.get('exercise', pd.DataFrame())

    # Samsung's exercise_type codes identify the activity directly, which is
    # robust to the pauses and resumes that make timestamp matching
    # unreliable (calls, breaks mid-session). Verified against the logged
    # routine: 12001 ~ aerobics (20 sessions, mean 35 min), 10023 ~ abs (33,
    # mean 10 min), 10001 ~ stretch (31, mean 13 min) — all median hour
    # 10-11 — versus 1001 ~ walking (51 sessions, mean 26 min, median hour
    # 15). Structured segments are already represented by is_hiit_day and
    # session HR; only walking contributes incidental activity.
    INCIDENTAL_TYPES = {1001}
    samsung_unmatched = pd.DataFrame()
    if not samsung_ex.empty:
        tcol = next((c for c in samsung_ex.columns
                     if c.endswith('exercise_type')), None)
        if tcol is not None:
            types = pd.to_numeric(samsung_ex[tcol], errors='coerce')
            samsung_unmatched = samsung_ex[
                types.isin(INCIDENTAL_TYPES)].reset_index(drop=True)
            print(f"  [{label}] samsung sessions: {len(samsung_ex)} total, "
                  f"{len(samsung_unmatched)} incidental (walking)")

    anomaly_days = set(CGM_ANOMALY_DAYS)

    rows = []
    
    for _, k in ketones.iterrows():
        t = int(k['deviceTimestamp'])
        ctx = k.get('readingContext') or 'UNKNOWN'
        day = local_dt(t).strftime('%Y-%m-%d')

        row = {
            'bhb_mmol_l': float(k['betaHydroxybutyrate']),
            'timestamp_ms': t,
            'datetime': local_dt(t),
            'date_key': day,
            'reading_context': ctx,
            'source': k.get('source'),
            'paired_glucose_fingerstick': k.get('glucoseMgDl'),
            'cgm_anomalous_day': int(day in anomaly_days),
        }
        row.update(cgm_features(dexcom, t))
        row.update(biosignal_features(samsung, t))
        row.update(dietary_features(app['meal_entries'],
                                    app['daily_covariates'], t))
        row.update(exercise_features(app['exercise_sessions'], samsung_ex,
                                    samsung_unmatched, t))
                                    
        row.update(sleep_features(samsung, t, ctx))
        row.update(temporal_features(t))
        rows.append(row)

    return pd.DataFrame(rows)


META_COLS = ('bhb_mmol_l', 'timestamp_ms', 'datetime', 'date_key',
            'reading_context', 'source', 'paired_glucose_fingerstick',
            'cgm_anomalous_day')


def feature_groups(matrix: pd.DataFrame) -> dict:
    cols = [c for c in matrix.columns if c not in META_COLS]
    return {
        'cgm': [c for c in cols if c.startswith('glucose_')],
        'smartwatch': [c for c in cols if c.startswith(
            ('hrv_', 'hr_', 'spo2_', 'skin_temp', 'stress_',
            'respiratory_', 'sleep_', 'steps_', 'session_',
            'samsung_', 'overnight_'))],
        'dietary': [c for c in cols if c in (
            'protein_g', 'net_carb_g', 'fiber_g', 'fat_g', 'calories',
            'ketogenic_ratio', 'minutes_since_meal', 'meal_count_4h',
            'is_fasted_4h', 'caffeine_mg', 'meditation_am_min',
            'meditation_pm_min')],
        'exercise': [c for c in cols if c in (
            'is_hiit_day', 'exercise_duration_min', 'minutes_since_exercise',
            'exercise_today')],
        'temporal': ['hour_of_day'],
    }


def zero_variance(matrix: pd.DataFrame, feats: list) -> list:
    num = matrix[feats].select_dtypes(include=[np.number])
    return [c for c in num.columns
            if num[c].notna().sum() > 1 and num[c].std() == 0]


def write_subsets(matrix: pd.DataFrame, out_dir: Path, prefix: str = ''):
    g = feature_groups(matrix)
    label = ['bhb_mmol_l', 'timestamp_ms', 'reading_context', 'date_key']

    all_feats = sorted({c for grp in g.values() for c in grp})
    # Dropped 2026-09-02 after correlation audit. Each is >=0.985 correlated
    # with a retained feature carrying the same information more
    # interpretably; keeping both would split importance between them.
    REDUNDANT = {
        'ketogenic_ratio',
        'overnight_hours_since',   # r=0.999 with hour_of_day
        'exercise_duration_min',   # r=1.000 with is_hiit_day (60 vs 23 min)
        'meal_count_4h',           # r=0.985 with is_fasted_4h
        # Reading counts per window — a data-density diagnostic, not a
        # physiological feature. Retained in feature_matrix.csv for QA.
        'glucose_n_30min',
        'glucose_n_60min',
        'glucose_n_120min',
        
    }
    removed = [c for c in all_feats if c in REDUNDANT]
    if removed:
        print("\n  Dropped (redundant, correlation audit 2026-09-02):")
        for c in removed:
            print(f"    {c}")
        g = {k: [c for c in v if c not in REDUNDANT] for k, v in g.items()}
        all_feats = [c for c in all_feats if c not in REDUNDANT]
    dropped = zero_variance(matrix, all_feats)
    if dropped:
        print("\n  Dropped (zero variance):")
        for c in dropped:
            print(f"    {c}")
        g = {k: [c for c in v if c not in dropped] for k, v in g.items()}

    configs = {
        'model_A_cgm_watch_macros': g['cgm'] + g['smartwatch'] + g['dietary']
                                    + g['exercise'] + g['temporal'],
        'model_B_watch_macros': g['smartwatch'] + g['dietary'] + g['exercise']
                                + g['temporal'],
        'model_C_cgm_watch': g['cgm'] + g['smartwatch'] + g['temporal'],
        'model_D_watch_only': g['smartwatch'] + g['temporal'],
    }
    print("\n  MODEL CONFIGURATIONS")
    for name, feats in configs.items():
        matrix[label + feats].to_csv(out_dir / f'{prefix}{name}.csv',
                                     index=False)
        print(f"    {name:<28} {len(feats):>3} features")
    return dropped


# ── Reporting ─────────────────────────────────────────────────

def report(matrix: pd.DataFrame, cal: pd.DataFrame):
    print("\n" + "=" * 66)
    print("FEATURE MATRIX")
    print("=" * 66)
    feats = [c for c in matrix.columns if c not in META_COLS]
    print(f"  Labels   : {len(matrix)}")
    print(f"  Features : {len(feats)}")
    print(f"  Range    : {matrix['datetime'].min():%Y-%m-%d} -> "
          f"{matrix['datetime'].max():%Y-%m-%d}  "
          f"({matrix['date_key'].nunique()} days)")

    print("\n  Labels by context:")
    for c, n in matrix['reading_context'].value_counts().items():
        print(f"    {str(c):<16} {n:>4}")

    b = matrix['bhb_mmol_l']
    print(f"\n  BHB: min {b.min():.2f}  mean {b.mean():.2f}  max {b.max():.2f}"
          f"  SD {b.std():.2f}")
    print(f"  Naive baseline RMSE = {b.std():.3f}; "
          f"Model A criterion (30% below) = {b.std()*0.7:.3f}")

    if matrix['cgm_anomalous_day'].sum():
        print(f"\n  CGM anomalous-day labels flagged: "
              f"{int(matrix['cgm_anomalous_day'].sum())}")
    if len(cal):
        print(f"  CGM user calibrations in export: {len(cal)}")

    morning = [c for c in feats if c.startswith(MORNING_ONLY_PREFIXES)]
    general = [c for c in feats if c not in morning]
    mrows = matrix[matrix['reading_context'].isin(MORNING_CONTEXTS)]

    def block(title, cols, frame, note):
        if not cols or frame.empty:
            return
        cov = frame[cols].notna().mean().sort_values()
        empty, partial = cov[cov == 0], cov[(cov > 0) & (cov < 0.5)]
        print(f"\n  {title}  ({note})")
        print(f"    {len(cov[cov>=0.5])} good, {len(partial)} partial, "
              f"{len(empty)} empty")
        for c in empty.index:
            print(f"      EMPTY    {c}")
        for c, v in partial.items():
            print(f"      PARTIAL  {c:<34} {v*100:>5.1f}%")

    block('All-label features', general, matrix, f'n={len(matrix)}')
    block('Morning-only features', morning, mrows,
          f'n={len(mrows)} FASTING/PRE_LUNCH')

    num = matrix[feats].select_dtypes(include=[np.number])
    num = num.loc[:, num.notna().sum() > 2]
    if len(num.columns) > 1:
        corr = num.corr().abs()
        pairs = [(corr.columns[i], corr.columns[j], corr.iloc[i, j])
                 for i in range(len(corr)) for j in range(i + 1, len(corr))
                 if corr.iloc[i, j] >= CORRELATION_FLAG]
        print(f"\n  Correlated feature pairs (|r| >= {CORRELATION_FLAG}): "
              f"{len(pairs)}")
        for a, b_, r in sorted(pairs, key=lambda p: -p[2])[:15]:
            print(f"    {r:.3f}  {a}  ~  {b_}")
        if len(pairs) > 15:
            print(f"    (+{len(pairs)-15} more)")
        if pairs:
            print("    Note: correlated features split importance between "
                  "them. Group-level\n    ablation (A->B->C->D) is unaffected; "
                  "per-feature ranking is.")


# ── Main ──────────────────────────────────────────────────────

def run(db, samsung_dir, dexcom_csv, out_dir):
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)

    print("=" * 66)
    print("SYNCHRONIZE FEATURE ENGINEERING PIPELINE  v2")
    print(f"Run: {datetime.now():%Y-%m-%d %H:%M}")
    print(f"Training window: {WINDOW_START[:10]} .. {WINDOW_END[:10]} "
          "(half-open)")
    print("=" * 66)

    print("\nLoading app database...")
    app = load_app_db(db)
    print("\nLoading Samsung streams...")
    samsung = load_samsung(samsung_dir)
    print("\nLoading Dexcom (source of record)...")
    dexcom, cal = load_dexcom(dexcom_csv)

    print("\nBuilding primary matrix...")
    m = build_matrix(app, samsung, dexcom, ms(WINDOW_START), ms(WINDOW_END),
                     'primary')
    m.to_csv(out / 'feature_matrix.csv', index=False)
    write_subsets(m, out)
    report(m, cal)

    # ---- sensitivity variants ----
    print("\n" + "=" * 66)
    print("SENSITIVITY VARIANTS")
    print("=" * 66)

    m2 = m[m['cgm_anomalous_day'] == 0].copy()
    m2.to_csv(out / 'sens_no_anomaly_feature_matrix.csv', index=False)
    write_subsets(m2, out, prefix='sens_no_anomaly_')
    print(f"  excl. {', '.join(CGM_ANOMALY_DAYS)}: {len(m2)} labels "
          f"({len(m)-len(m2)} removed)")

    print("\nBuilding extended-window variant...")
    m3 = build_matrix(app, samsung, dexcom, ms(EXTENDED_START),
                      ms(WINDOW_END), 'extended')
    m3.to_csv(out / 'sens_extended_feature_matrix.csv', index=False)
    write_subsets(m3, out, prefix='sens_extended_')

    if len(cal):
        cal.assign(datetime=cal['ts'].apply(local_dt)).to_csv(
            out / 'cgm_calibration_events.csv', index=False)
        print(f"\n  Wrote cgm_calibration_events.csv ({len(cal)} events)")

    print("\n" + "=" * 66)
    print(f"Wrote matrices + model subsets to {out.resolve()}")
    print("=" * 66)


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--db', required=True)
    p.add_argument('--samsung', default='samsung_parsed')
    p.add_argument('--dexcom', default=None)
    p.add_argument('--out', default='features')
    a = p.parse_args()
    run(a.db, a.samsung, a.dexcom, a.out)