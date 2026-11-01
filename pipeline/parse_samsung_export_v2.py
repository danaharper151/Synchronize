#!/usr/bin/env python3
"""
Synchronize — Samsung Health Export Parser  (v2)
================================================
Flattens a Samsung Health export into clean time-series CSVs for the feature
engineering pipeline, and reports on every stream in the export — including
ones not currently consumed — so that feature selection is made from evidence
rather than from what the parser happened to be configured for.

Samsung export structure:
    <export>/com.samsung.health.hrv.<stamp>.csv      index, one row per hour
    <export>/jsons/com.samsung.health.hrv/<shard>/<uuid>.<type>.binning_data.json

Four defects in the export format are handled explicitly:

  1. TRAILING DELIMITER. Every stream CSV ends each data row with a trailing
     comma, giving one more field than there are header names. pandas responds
     by promoting the first column to the index, which shifts every column
     label one position left of its data. Read with index_col=False.
     (This is what made the AGEs 'score' column appear empty and 'percent'
     appear to hold 70-million-scale values.)

  2. VARIABLE PREAMBLE. Some files carry one metadata line before the header,
     some two. The header line is located by field count, not assumed.

  3. HEADER/DATA MISALIGNMENT. Even after 1 and 2, some files carry data under
     names that do not describe it. Timestamp and pointer columns are located
     by content.

  4. TIMEZONE. Binned JSON payloads carry epoch milliseconds. Converting with
     pd.to_datetime(..., unit='ms') yields UTC, which shifted every reported
     hour by +7 and made overnight HRV appear to be 5.8% of measurements
     rather than 90.7%. All display timestamps are converted to LOCAL_TZ.

Usage:
    python parse_samsung_export_v2.py --export <path/to/samsunghealth_export> --out samsung_parsed
    python parse_samsung_export_v2.py --export "..." --out ... --discover-only
"""

from __future__ import annotations

import argparse
import os
import json
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pandas as pd
import numpy as np

# ── Configuration ─────────────────────────────────────────────

# Streams whose date strings are UTC rather than local wall time. Determined
# empirically: app-logged exercise sessions matched Samsung counterparts at a
# median offset of exactly 420 minutes (the PDT offset), while step_count
# already aligned. Verify with diag_join.py if a new export behaves oddly.
UTC_STORED_STREAMS = {'exercise'}

LOCAL_TZ = os.environ.get('SYNCHRONIZE_TZ', 'America/Los_Angeles')

# Pre-registered training window (§3.7.6, recorded 2026-08-14)
WINDOW_START = pd.Timestamp('2026-08-02 00:00:00')
WINDOW_END = pd.Timestamp('2026-09-01 00:00:00')

DATE_PAT = re.compile(r'^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}')
EPOCH_LO, EPOCH_HI = 1.5e12, 2.0e12

# mode 'binned' → values in jsons/<folder>/<shard>/<uuid>...json
# mode 'direct' → values are columns in the CSV itself
# value_hints  → candidate names for the measurement column(s)
STREAMS = {
    'hrv': dict(csv_prefix='com.samsung.health.hrv',
                json_folder='com.samsung.health.hrv', mode='binned',
                expected_per_day=800, value_hints=['rmssd', 'sdnn']),
    'heart_rate': dict(csv_prefix='com.samsung.shealth.tracker.heart_rate',
                       json_folder='com.samsung.shealth.tracker.heart_rate',
                       mode='binned', expected_per_day=1000,
                       value_hints=['heart_rate', 'value', 'mean']),
    'spo2': dict(csv_prefix='com.samsung.shealth.tracker.oxygen_saturation',
                 json_folder='com.samsung.shealth.tracker.oxygen_saturation',
                 mode='binned', expected_per_day=50,      # sleep-gated
                 value_hints=['spo2', 'oxygen_saturation', 'value']),
    'spo2_raw': dict(csv_prefix='com.samsung.health.oxygen_saturation.raw',
                     json_folder='com.samsung.health.oxygen_saturation.raw',
                     mode='binned', expected_per_day=20000,
                     value_hints=['spo2', 'value']),
    'skin_temperature': dict(csv_prefix='com.samsung.health.skin_temperature',
                             json_folder='com.samsung.health.skin_temperature',
                             mode='binned', expected_per_day=400,
                             value_hints=['mean', 'temperature', 'value']),
    'respiratory_rate': dict(csv_prefix='com.samsung.health.respiratory_rate',
                             json_folder='com.samsung.health.respiratory_rate',
                             mode='binned', expected_per_day=400,
                             value_hints=['respiratory_rate', 'value', 'mean']),
    'stress': dict(csv_prefix='com.samsung.shealth.stress',
                   json_folder='com.samsung.shealth.stress', mode='binned',
                   expected_per_day=400,
                   value_hints=['score', 'stress_score', 'value']),
    'movement': dict(csv_prefix='com.samsung.health.movement',
                     json_folder='com.samsung.health.movement', mode='binned',
                     expected_per_day=1000, value_hints=['value', 'mean']),
    'sleep_raw': dict(csv_prefix='com.samsung.shealth.sleep_raw_data',
                      json_folder='com.samsung.shealth.sleep_raw_data',
                      mode='binned', expected_per_day=0, value_hints=[]),
    'ecg': dict(csv_prefix='com.samsung.health.ecg',
                json_folder='com.samsung.health.ecg', mode='binned',
                expected_per_day=0, value_hints=[]),
    # --- direct: values live in the CSV ---
    'sleep_stage': dict(csv_prefix='com.samsung.health.sleep_stage',
                        json_folder=None, mode='direct', expected_per_day=60,
                        value_hints=['stage']),
    'sleep': dict(csv_prefix='com.samsung.shealth.sleep', json_folder=None,
                  mode='direct', expected_per_day=1, value_hints=[]),
    'exercise': dict(csv_prefix='com.samsung.shealth.exercise',
                     json_folder=None, mode='direct', expected_per_day=1,
                     value_hints=['exercise_type', 'duration', 'calorie']),
    'step_count': dict(
        csv_prefix='com.samsung.shealth.tracker.pedometer_step_count',
        json_folder=None, mode='direct', expected_per_day=300,
        value_hints=['count', 'step_count', 'walk_step', 'calorie',
                     'distance', 'speed']),
    'step_daily': dict(csv_prefix='com.samsung.shealth.step_daily_trend',
                       json_folder=None, mode='direct', expected_per_day=1,
                       value_hints=['count', 'calorie', 'distance']),
    'calories_burned': dict(
        csv_prefix='com.samsung.shealth.calories_burned.details',
        json_folder=None, mode='direct', expected_per_day=1,
        value_hints=['active_calorie', 'rest_calorie', 'tef_calorie']),
    'activity_day_summary': dict(
        csv_prefix='com.samsung.shealth.activity.day_summary',
        json_folder=None, mode='direct', expected_per_day=1,
        value_hints=['calorie', 'distance', 'step_count', 'active_time']),
}


# ── CSV reading ───────────────────────────────────────────────

def read_samsung_csv(path: Path) -> pd.DataFrame:
    """
    Locate the header line by field count, then read with index_col=False so
    the trailing delimiter does not shift every column one position left.
    """
    try:
        with open(path, 'r', encoding='utf-8-sig', errors='replace') as f:
            preamble = [f.readline() for _ in range(3)]
    except Exception as e:
        print(f"    could not open {path.name}: {e}")
        return pd.DataFrame()

    counts = [ln.count(',') for ln in preamble]
    header_row = 0 if counts[0] >= max(counts[1:2] or [0]) else counts.index(max(counts[:2]))
    # Samsung's first line is a short metadata triple; the header has many more
    if len(counts) > 1 and counts[1] > counts[0]:
        header_row = 1
    if len(counts) > 2 and counts[2] > counts[1] and counts[1] <= counts[0]:
        header_row = 2

    for skip in (header_row, 0, 1, 2):
        try:
            df = pd.read_csv(path, skiprows=skip, index_col=False,
                             low_memory=False, encoding='utf-8-sig')
        except Exception:
            continue
        if not df.empty and len(df.columns) > 1:
            df.columns = [str(c).strip() for c in df.columns]
            return df
    return pd.DataFrame()


def detect_time_column(df: pd.DataFrame):
    """Content-based. Header names in this export are not reliable."""
    for col in df.columns:
        s = df[col].dropna()
        if not len(s):
            continue
        num = pd.to_numeric(s, errors='coerce').dropna()
        if len(num) / len(s) > 0.8 and num.between(EPOCH_LO, EPOCH_HI).mean() > 0.8:
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


def to_local(series: pd.Series, kind: str) -> pd.Series:
    if kind == 'epoch_ms':
        return (pd.to_datetime(pd.to_numeric(series, errors='coerce'),
                               unit='ms', utc=True)
                .dt.tz_convert(LOCAL_TZ).dt.tz_localize(None))
    return pd.to_datetime(series, errors='coerce')


def numeric_columns(df: pd.DataFrame, min_frac=0.5) -> list:
    """Columns that actually hold numbers, with cardinality > 1."""
    out = []
    for c in df.columns:
        s = df[c].dropna()
        if not len(s):
            continue
        n = pd.to_numeric(s, errors='coerce')
        if n.notna().mean() > min_frac and n.nunique() > 1:
            out.append((c, n.nunique(), float(n.min()), float(n.max())))
    return out


# ── Binned (JSON) streams ─────────────────────────────────────

def build_uuid_index(folder: Path) -> dict:
    index = {}
    if not folder.exists():
        return index
    for p in folder.rglob('*'):
        if p.is_file():
            index.setdefault(p.name.split('.')[0], p)
    return index


def find_csv(export_dir: Path, prefix: str):
    matches = sorted(export_dir.glob(f"{prefix}.*.csv"))
    exact = [m for m in matches if m.name.split('.20')[0] == prefix]
    return exact[0] if exact else (matches[0] if matches else None)


def find_pointer_column(df: pd.DataFrame):
    for col in df.columns:
        s = df[col].dropna().astype(str).head(30)
        if len(s) and s.str.endswith('.json').mean() > 0.5:
            return col
    return None


def flatten_binned(export_dir: Path, json_root: Path, name: str,
                   cfg: dict) -> pd.DataFrame:
    csv_path = find_csv(export_dir, cfg['csv_prefix'])
    if csv_path is None:
        print(f"  [{name}] no CSV found")
        return pd.DataFrame()

    df = read_samsung_csv(csv_path)
    if df.empty:
        print(f"  [{name}] CSV unreadable")
        return pd.DataFrame()

    pointer_col = find_pointer_column(df)
    if pointer_col is None:
        print(f"  [{name}] no .json pointer column")
        return pd.DataFrame()

    uuid_index = build_uuid_index(json_root / cfg['json_folder'])
    if not uuid_index:
        print(f"  [{name}] JSON folder missing: {cfg['json_folder']}")
        return pd.DataFrame()

    rows, unresolved = [], 0
    
    for pointer in df[pointer_col].dropna():
        uuid = str(pointer).split('.')[0]
        path = uuid_index.get(uuid)
        if path is None:
            unresolved += 1
            continue
        try:
            payload = json.load(open(path, 'r', encoding='utf-8'))
        except Exception:
            unresolved += 1
            continue
        if isinstance(payload, dict):
            payload = [payload]
        rows.extend(r for r in payload if isinstance(r, dict))

    if not rows:
        print(f"  [{name}] no measurements recovered ({unresolved} unresolved)")
        return pd.DataFrame()

    out = pd.DataFrame(rows)
    for col in ('start_time', 'time', 'timestamp', 'start'):
        if col in out.columns:
            out['timestamp_ms'] = pd.to_numeric(out[col], errors='coerce')
            break

    if 'timestamp_ms' in out.columns:
        out = out.dropna(subset=['timestamp_ms'])
        out['datetime'] = to_local(out['timestamp_ms'], 'epoch_ms')
        out = out.sort_values('timestamp_ms').reset_index(drop=True)

    note = f"  ({unresolved} unresolved)" if unresolved else ""
    print(f"  [{name}] {len(out):,} measurements from {len(uuid_index):,} files{note}")
    return out


# ── Direct streams ────────────────────────────────────────────

def flatten_direct(export_dir: Path, name: str, cfg: dict) -> pd.DataFrame:
    csv_path = find_csv(export_dir, cfg['csv_prefix'])
    if csv_path is None:
        print(f"  [{name}] no CSV found")
        return pd.DataFrame()

    df = read_samsung_csv(csv_path)
    if df.empty:
        print(f"  [{name}] CSV unreadable")
        return pd.DataFrame()

    col, kind = detect_time_column(df)
    if col is None:
        print(f"  [{name}] {len(df):,} rows, {len(df.columns)} cols "
              f"— NO TIMESTAMP COLUMN FOUND")
        return df

    df['datetime'] = to_local(df[col], kind)
    if kind == 'epoch_ms':
        df['timestamp_ms'] = pd.to_numeric(df[col], errors='coerce')
    else:
        # Samsung is inconsistent about what its date strings mean: step_count
        # stores local wall time, exercise stores UTC. Reading both as local
        # put exercise 7 hours ahead, so no app-logged session matched its
        # Samsung counterpart (median offset 420 min = the PDT offset exactly).
        # The table's own time_offset column ("UTC-0700") disambiguates.
        off_col = next((c for c in df.columns
                        if str(c).endswith('time_offset')), None)
        stored_utc = False
        if off_col is not None:
            offs = df[off_col].astype(str).str.extract(r'UTC([+-]\d{2})(\d{2})')
            if offs[0].notna().mean() > 0.8:
                hrs = pd.to_numeric(offs[0], errors='coerce')
                mns = pd.to_numeric(offs[1], errors='coerce') * np.sign(hrs)
                shifted = (df['datetime']
                           + pd.to_timedelta(hrs, unit='h')
                           + pd.to_timedelta(mns, unit='m'))
                                # Samsung stores exercise timestamps in UTC and step_count in
                # local wall time. Rather than inferring from hour
                # distributions, apply the offset only where the export's own
                # column indicates it is needed, verified against app-logged
                # session times (median offset was exactly 420 min = PDT).
                if name in UTC_STORED_STREAMS:
                    df['datetime'] = shifted
                    stored_utc = True

        localized = df['datetime'].dt.tz_localize(
            LOCAL_TZ, ambiguous='NaT', nonexistent='NaT')
        df['timestamp_ms'] = (localized.dt.tz_convert('UTC')
                              .dt.tz_localize(None)
                              - pd.Timestamp('1970-01-01')) // pd.Timedelta('1ms')

        ts = pd.to_numeric(df['timestamp_ms'], errors='coerce')
        ok = ts.between(1.5e12, 2.0e12)
        if ts.notna().any() and ok.mean() < 0.5:
            print(f"  [{name}] WARNING: timestamp_ms outside plausible epoch-ms "
                  f"range (min {ts.min():.0f}) — check unit scaling")
        if stored_utc:
            print(f"  [{name}] source stored UTC; shifted to local via "
                  f"'{off_col}'")

    df = df.dropna(subset=['datetime']).sort_values('datetime').reset_index(drop=True)
    flag = "" if col.lower() in ('start_time', 'time') else f"  [time col: '{col[:40]}']"
    print(f"  [{name}] {len(df):,} rows, {len(df.columns)} cols{flag}")
    return df



# ── Reporting ─────────────────────────────────────────────────

def coverage_row(name: str, df: pd.DataFrame, cfg: dict) -> str:
    if df.empty or 'datetime' not in df.columns or df['datetime'].isna().all():
        return f"  {name:<22} {len(df):>9,}   —      —   (no timestamps)"
    ts = df['datetime'].dropna()
    days = sorted(set(ts.dt.date))
    full = pd.date_range(days[0], days[-1], freq='D').date
    missing = [d for d in full if d not in set(days)]
    in_win = ts[(ts >= WINDOW_START) & (ts < WINDOW_END)]
    win_days = in_win.dt.date.nunique()
    per_day = len(df) / max(len(days), 1)
    flag = ""
    exp = cfg.get('expected_per_day', 0)
    if exp > 0 and per_day < exp * 0.25:
        flag = "  <- sparse"
    miss = f"  miss:{len(missing)}" if missing else ""
    return (f"  {name:<22} {len(df):>9,} {len(days):>4} {per_day:>8,.0f}  "
            f"{ts.min():%m-%d} -> {ts.max():%m-%d}  "
            f"win:{len(in_win):>7,}/{win_days:>2}d{miss}{flag}")


def report_missing_days(streams: dict):
    print("\n" + "=" * 78)
    print("MISSING DAYS  (within each stream's own span)")
    print("=" * 78)
    for name, df in streams.items():
        if df.empty or 'datetime' not in df.columns or df['datetime'].isna().all():
            continue
        ts = df['datetime'].dropna()
        days = set(ts.dt.date)
        full = pd.date_range(min(days), max(days), freq='D').date
        missing = [d for d in full if d not in days]
        if not missing:
            continue
        in_window = [d for d in missing
                     if WINDOW_START.date() <= d < WINDOW_END.date()]
        tag = "  ** IN TRAINING WINDOW **" if in_window else "  (outside window)"
        print(f"  {name:<22} {len(missing)} missing: "
              f"{', '.join(str(d) for d in missing[:8])}{tag}")


def report_values(streams: dict):
    print("\n" + "=" * 78)
    print("VALUE COLUMNS  (numeric, cardinality > 1)")
    print("=" * 78)
    for name, df in streams.items():
        if df.empty:
            continue
        cfg = STREAMS[name]
        cols = numeric_columns(df)
        cols = [c for c in cols if c[0] not in
                ('timestamp_ms', 'start_time', 'end_time', 'time')]
        if not cols:
            print(f"  {name:<22} none")
            continue
        print(f"  {name}")
        hits = [c for c in cols if any(h in c[0].lower()
                                       for h in cfg.get('value_hints', []))]
        show = hits if hits else cols[:6]
        for c, uniq, lo, hi in show:
            mark = "*" if (c, uniq, lo, hi) in hits else " "
            print(f"    {mark} {c[:44]:<46} uniq={uniq:>6}  {lo:g} .. {hi:g}")
        if hits and len(cols) > len(hits):
            print(f"      (+{len(cols)-len(hits)} other numeric columns)")


def discover(export_dir: Path):
    """Report on every top-level CSV, including unconfigured ones."""
    configured = {cfg['csv_prefix'] for cfg in STREAMS.values()}
    print("\n" + "=" * 78)
    print("UNCONFIGURED STREAMS IN THIS EXPORT")
    print("=" * 78)
    for path in sorted(export_dir.glob('*.csv')):
        prefix = path.name.split('.20')[0]
        if prefix in configured:
            continue
        df = read_samsung_csv(path)
        if df.empty:
            continue
        col, kind = detect_time_column(df)
        rng, win = "no timestamps", ""
        if col:
            ts = to_local(df[col], kind).dropna()
            if len(ts):
                inw = ts[(ts >= WINDOW_START) & (ts < WINDOW_END)]
                rng = f"{ts.min():%m-%d} -> {ts.max():%m-%d}"
                win = f"  win:{len(inw):,}/{inw.dt.date.nunique()}d"
        nums = [c for c, u, lo, hi in numeric_columns(df)][:5]
        print(f"\n  {prefix}")
        print(f"    {len(df):,} rows, {len(df.columns)} cols   {rng}{win}")
        if nums:
            print(f"    numeric: {', '.join(n[:34] for n in nums)}")


# ── Main ──────────────────────────────────────────────────────

def run(export_dir: str, out_dir: str, discover_only: bool):
    export_path = Path(export_dir)
    out_path = Path(out_dir)
    json_root = export_path / 'jsons'
    if not json_root.exists():
        found = list(export_path.glob('**/jsons'))
        json_root = found[0] if found else export_path

    print("=" * 78)
    print("SAMSUNG HEALTH EXPORT PARSER  v2")
    print(f"Export : {export_path}")
    print(f"Run    : {datetime.now():%Y-%m-%d %H:%M}")
    print(f"Window : {WINDOW_START:%Y-%m-%d} -> {WINDOW_END:%Y-%m-%d}")
    print("=" * 78)

    if discover_only:
        discover(export_path)
        return

    out_path.mkdir(parents=True, exist_ok=True)
    print("\nParsing...")
    parsed = {}
    for name, cfg in STREAMS.items():
        if cfg['mode'] == 'binned':
            df = flatten_binned(export_path, json_root, name, cfg)
        else:
            df = flatten_direct(export_path, name, cfg)
        parsed[name] = df
        if not df.empty:
            df.to_csv(out_path / f"{name}.csv", index=False)

    print("\n" + "=" * 78)
    print("COVERAGE")
    print("=" * 78)
    print(f"  {'STREAM':<22} {'ROWS':>9} {'DAY':>4} {'PER DAY':>8}  RANGE          IN WINDOW")
    print("-" * 78)
    for name, df in parsed.items():
        print(coverage_row(name, df, STREAMS[name]))

    report_missing_days(parsed)
    report_values(parsed)
    discover(export_path)

    # Sleep-gating check — the §3.4 justification, recomputed each run
    hrv = parsed.get('hrv', pd.DataFrame())
    if not hrv.empty and 'datetime' in hrv.columns:
        h = hrv['datetime'].dt.hour
        night = ((h >= 23) | (h < 6)).sum()
        day = ((h >= 8) & (h < 20)).sum()
        print("\n" + "=" * 78)
        print("SLEEP-GATING CHECK (HRV, local time)")
        print("=" * 78)
        print(f"  overnight 23:00-06:00 : {night:,} ({100*night/len(hrv):.1f}%)")
        print(f"  daytime   08:00-20:00 : {day:,} ({100*day/len(hrv):.1f}%)")

    n = sum(1 for d in parsed.values() if not d.empty)
    print("\n" + "=" * 78)
    print(f"Wrote {n} CSVs to {out_path.resolve()}")
    print("=" * 78)


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--export', required=True)
    p.add_argument('--out', default='./samsung_parsed')
    p.add_argument('--discover-only', action='store_true',
                   help='report on all streams without writing CSVs')
    a = p.parse_args()
    run(a.export, a.out, a.discover_only)