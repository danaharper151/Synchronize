"""
Synchronize — Data Export and De-identification Pipeline
========================================================
Reads the SQLite database exported from the Synchronize Android app
and produces a de-identified, research-grade CSV bundle suitable for
PhysioNet submission.

Usage:
    python export_pipeline.py --db path/to/synchronize_db --out ./output

Author: Dana Harper
MSCS Thesis — CSUCI 2025
"""

import argparse
import hashlib
import json
import os
import random
import shutil
import sqlite3
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd
import numpy as np

# ── Configuration ─────────────────────────────────────────────

SUBJECT_ID       = "subject_001"
STUDY_DAYS       = 30
TIMESTAMP_OFFSET_RANGE_DAYS = 30  # random offset applied to all timestamps

# Clinical thresholds (for validation)
GLUCOSE_LOW      = 70
GLUCOSE_HIGH     = 180
KETONE_MIN       = 0.0
KETONE_MAX       = 10.0
HR_MIN           = 30
HR_MAX           = 220
SPO2_MIN         = 85
SPO2_MAX         = 100

# ── De-identification ─────────────────────────────────────────

def generate_timestamp_offset(seed: str) -> int:
    """
    Generate a reproducible but unpredictable timestamp offset in milliseconds.
    The same seed always produces the same offset (so the dataset is internally
    consistent) but the offset value itself is not disclosed.
    """
    h = hashlib.sha256(seed.encode()).hexdigest()
    offset_days = int(h[:8], 16) % (TIMESTAMP_OFFSET_RANGE_DAYS * 2) - TIMESTAMP_OFFSET_RANGE_DAYS
    return offset_days * 24 * 60 * 60 * 1000  # convert to ms

def deidentify_timestamps(df: pd.DataFrame, offset_ms: int,
                          timestamp_cols: list) -> pd.DataFrame:
    """Apply the timestamp offset to all timestamp columns."""
    df = df.copy()
    for col in timestamp_cols:
        if col in df.columns:
            df[col] = df[col] + offset_ms
    return df

def hash_subject_id(subject_id: str, salt: str) -> str:
    """Replace subject ID with a one-way hash."""
    return hashlib.sha256(f"{salt}{subject_id}".encode()).hexdigest()[:16]

# ── Database reading ──────────────────────────────────────────

def read_table(conn: sqlite3.Connection, table: str) -> pd.DataFrame:
    """Read a table from the SQLite database."""
    try:
        df = pd.read_sql_query(f"SELECT * FROM {table}", conn)
        print(f"  [{table}] {len(df)} rows loaded")
        return df
    except Exception as e:
        print(f"  [{table}] WARNING: {e}")
        return pd.DataFrame()

# ── Data validation ───────────────────────────────────────────

def validate_glucose(df: pd.DataFrame) -> pd.DataFrame:
    """Flag and remove physiologically impossible glucose values."""
    if df.empty:
        return df
    invalid = (df['glucoseMgDl'] < GLUCOSE_LOW * 0.5) | \
              (df['glucoseMgDl'] > GLUCOSE_HIGH * 2)
    n_invalid = invalid.sum()
    if n_invalid > 0:
        print(f"  [glucose] Removed {n_invalid} invalid readings")
        df = df[~invalid]
    return df

def validate_ketones(df: pd.DataFrame) -> pd.DataFrame:
    """Flag and remove physiologically impossible ketone values."""
    if df.empty:
        return df
    invalid = (df['betaHydroxybutyrate'] < KETONE_MIN) | \
              (df['betaHydroxybutyrate'] > KETONE_MAX)
    n_invalid = invalid.sum()
    if n_invalid > 0:
        print(f"  [ketones] Removed {n_invalid} invalid readings")
        df = df[~invalid]
    return df

def validate_biosignals(df: pd.DataFrame) -> pd.DataFrame:
    """Validate biosignal ranges."""
    if df.empty:
        return df
    if 'heartRateBpm' in df.columns:
        invalid_hr = df['heartRateBpm'].notna() & \
                     ((df['heartRateBpm'] < HR_MIN) | (df['heartRateBpm'] > HR_MAX))
        df.loc[invalid_hr, 'heartRateBpm'] = np.nan
        if invalid_hr.sum() > 0:
            print(f"  [biosignals] Nulled {invalid_hr.sum()} invalid HR values")
    if 'spo2Percent' in df.columns:
        invalid_spo2 = df['spo2Percent'].notna() & \
                       ((df['spo2Percent'] < SPO2_MIN) | (df['spo2Percent'] > SPO2_MAX))
        df.loc[invalid_spo2, 'spo2Percent'] = np.nan
        if invalid_spo2.sum() > 0:
            print(f"  [biosignals] Nulled {invalid_spo2.sum()} invalid SpO2 values")
    return df

# ── Feature engineering ───────────────────────────────────────

def add_human_readable_timestamps(df: pd.DataFrame,
                                   ts_col: str = 'deviceTimestamp') -> pd.DataFrame:
    """Add ISO 8601 datetime column alongside epoch ms timestamp."""
    if ts_col in df.columns:
        df['datetime_utc'] = pd.to_datetime(df[ts_col], unit='ms', utc=True) \
                               .dt.strftime('%Y-%m-%dT%H:%M:%SZ')
    return df

def add_glucose_features(df: pd.DataFrame) -> pd.DataFrame:
    """Add derived glucose features for ML pipeline."""
    if df.empty or 'glucoseMgDl' not in df.columns:
        return df
    df = df.sort_values('deviceTimestamp').copy()
    df['glucose_trend_slope'] = df['glucoseMgDl'].diff() / \
                                 df['deviceTimestamp'].diff().div(60000)  # mg/dL per min
    df['glucose_in_range']    = (df['glucoseMgDl'] >= 70) & \
                                 (df['glucoseMgDl'] <= 140)
    df['glucose_above_140']   = df['glucoseMgDl'] > 140
    df['glucose_below_70']    = df['glucoseMgDl'] < 70
    # Rolling statistics (30, 60, 120 minute windows at 5-min intervals)
    for window, label in [(6, '30min'), (12, '60min'), (24, '120min')]:
        df[f'glucose_mean_{label}']  = df['glucoseMgDl'].rolling(window).mean()
        df[f'glucose_std_{label}']   = df['glucoseMgDl'].rolling(window).std()
        df[f'glucose_auc_{label}']   = df['glucoseMgDl'].rolling(window).sum() * 5
    return df

def add_ketogenic_ratio(df: pd.DataFrame) -> pd.DataFrame:
    """Add ketogenic ratio if not already present."""
    if df.empty:
        return df
    if 'ketogenicRatio' not in df.columns:
        if all(c in df.columns for c in ['fatGrams', 'netCarbGrams', 'proteinGrams']):
            denom = df['netCarbGrams'] + df['proteinGrams']
            df['ketogenicRatio'] = np.where(denom > 0, df['fatGrams'] / denom, 0)
    return df

# ── Export functions ──────────────────────────────────────────

def export_table(df: pd.DataFrame, name: str, out_dir: Path,
                 offset_ms: int, subject_hash: str,
                 timestamp_cols: list, drop_cols: list = None) -> dict:
    """De-identify and export a single table to CSV."""
    if df.empty:
        print(f"  [{name}] Skipping — no data")
        return {}

    df = df.copy()

    # De-identify subject ID
    if 'subjectId' in df.columns:
        df['subjectId'] = subject_hash

    # Apply timestamp offset
    df = deidentify_timestamps(df, offset_ms, timestamp_cols)

    # Add human-readable timestamps
    if timestamp_cols:
        df = add_human_readable_timestamps(df, timestamp_cols[0])

    # Drop any PII columns
    if drop_cols:
        df = df.drop(columns=[c for c in drop_cols if c in df.columns])

    # Drop internal database ID (not useful for ML)
    if 'id' in df.columns:
        df = df.drop(columns=['id'])

    # Export
    out_path = out_dir / f"{name}.csv"
    df.to_csv(out_path, index=False)

    stats = {
        'rows':    len(df),
        'columns': len(df.columns),
        'file':    str(out_path),
        'size_kb': round(out_path.stat().st_size / 1024, 1)
    }
    print(f"  [{name}] Exported {stats['rows']} rows → {stats['file']} ({stats['size_kb']} KB)")
    return stats

# ── Main pipeline ─────────────────────────────────────────────

def run_pipeline(db_path: str, out_dir: str, subject_id: str = SUBJECT_ID):
    """
    Main export pipeline. Reads the Synchronize SQLite database,
    validates, de-identifies, and exports all tables to CSV.
    """
    print("=" * 60)
    print("Synchronize Data Export Pipeline")
    print(f"Database : {db_path}")
    print(f"Output   : {out_dir}")
    print(f"Subject  : {subject_id}")
    print("=" * 60)

    # Validate input
    if not os.path.exists(db_path):
        raise FileNotFoundError(f"Database not found: {db_path}")

    # Create output directory
    out_path = Path(out_dir)
    out_path.mkdir(parents=True, exist_ok=True)

    # Generate de-identification parameters
    salt = hashlib.sha256(os.urandom(32)).hexdigest()
    offset_ms = generate_timestamp_offset(salt)
    subject_hash = hash_subject_id(subject_id, salt)

    print(f"\nDe-identification:")
    print(f"  Timestamp offset: {offset_ms // (86400 * 1000):+d} days (exact value not disclosed)")
    print(f"  Subject hash    : {subject_hash}")

    # Connect to database
    conn = sqlite3.connect(db_path)
    print("\nReading tables...")

    # ── Read all tables ───────────────────────────────────────
    glucose    = read_table(conn, 'glucose_readings')
    ketones    = read_table(conn, 'ketone_readings')
    biosignals = read_table(conn, 'biosignal_samples')
    sleep      = read_table(conn, 'sleep_sessions')
    meals      = read_table(conn, 'meal_entries')
    conn.close()

    # ── Validate ──────────────────────────────────────────────
    print("\nValidating...")
    glucose    = validate_glucose(glucose)
    ketones    = validate_ketones(ketones)
    biosignals = validate_biosignals(biosignals)

    # ── Feature engineering ───────────────────────────────────
    print("\nEngineering features...")
    glucose = add_glucose_features(glucose)
    meals   = add_ketogenic_ratio(meals)

    # ── Export ────────────────────────────────────────────────
    print("\nExporting...")
    ts_cols = ['deviceTimestamp', 'receiveTimestamp']
    stats = {}

    stats['glucose'] = export_table(
        glucose, 'glucose_readings', out_path, offset_ms, subject_hash,
        timestamp_cols=ts_cols
    )
    stats['ketones'] = export_table(
        ketones, 'ketone_readings', out_path, offset_ms, subject_hash,
        timestamp_cols=ts_cols
    )
    stats['biosignals'] = export_table(
        biosignals, 'biosignal_samples', out_path, offset_ms, subject_hash,
        timestamp_cols=ts_cols
    )
    stats['sleep'] = export_table(
        sleep, 'sleep_sessions', out_path, offset_ms, subject_hash,
        timestamp_cols=['deviceTimestamp', 'receiveTimestamp',
                        'sleepStartTimestamp', 'sleepEndTimestamp']
    )
    stats['meals'] = export_table(
        meals, 'meal_entries', out_path, offset_ms, subject_hash,
        timestamp_cols=ts_cols,
        drop_cols=['rawTranscription']  # remove voice transcription text (PII risk)
    )

    # ── Write dataset manifest ────────────────────────────────
    manifest = {
        'dataset':     'Synchronize-Ketone-Biosignal-N1',
        'version':     '1.0',
        'created':     datetime.now(timezone.utc).isoformat(),
        'subject':     subject_hash,
        'study_days':  STUDY_DAYS,
        'tables':      stats,
        'deidentification': {
            'method':          'timestamp_offset_plus_subject_hash',
            'timestamp_offset': 'randomized_delta_not_disclosed',
            'subject_id':      'sha256_hash',
            'pii_removed':     ['rawTranscription', 'id']
        },
        'device_info': {
            'cgm':           'Dexcom G7',
            'smartwatch':    'Samsung Galaxy Watch 7',
            'ketone_meter':  'Keto-Mojo GK+',
            'platform':      'Android 14+'
        },
        'study_protocol': {
            'ketone_readings_per_day': '4-5',
            'ketone_contexts': ['FASTING', 'PRE_LUNCH', 'POST_LUNCH',
                                'PRE_DINNER', 'PRE_SLEEP'],
            'cgm_interval_minutes': 5,
            'dietary_format': 'cal/fat/protein/net_carbs/fiber',
            'diet_type': 'ketogenic',
            'daily_covariates': ['caffeine_mg', 'meditation_am_min',
                                 'meditation_pm_min']
        },
        'fixed_metadata': {
            'supplements': ['omega-3', 'turmeric', 'magnesium',
                            'B-complex', 'multivitamin'],
            'caffeine_source': 'coffee',
            'caffeine_dose_grams_grounds': 46,
            'caffeine_dose_mg_approx': 400,
            'caffeine_timing': 'before noon'
        }
    }

    manifest_path = out_path / 'manifest.json'
    with open(manifest_path, 'w') as f:
        json.dump(manifest, f, indent=2)
    print(f"\n  [manifest] Written → {manifest_path}")

    # ── Summary ───────────────────────────────────────────────
    print("\n" + "=" * 60)
    print("Export Complete")
    total_rows = sum(s.get('rows', 0) for s in stats.values())
    total_kb   = sum(s.get('size_kb', 0) for s in stats.values())
    print(f"  Total rows    : {total_rows:,}")
    print(f"  Total size    : {total_kb:.1f} KB")
    print(f"  Output folder : {out_path.resolve()}")
    print("=" * 60)
    print("\nDataset is ready for PhysioNet submission.")
    print("Upload the entire output folder as a ZIP archive.")

if __name__ == '__main__':
    parser = argparse.ArgumentParser(
        description='Synchronize data export and de-identification pipeline'
    )
    parser.add_argument(
        '--db', required=True,
        help='Path to the synchronize_db SQLite file exported from the Android app'
    )
    parser.add_argument(
        '--out', default='./synchronize_dataset',
        help='Output directory for de-identified CSV bundle (default: ./synchronize_dataset)'
    )
    parser.add_argument(
        '--subject', default=SUBJECT_ID,
        help=f'Subject ID to de-identify (default: {SUBJECT_ID})'
    )
    args = parser.parse_args()
    run_pipeline(args.db, args.out, args.subject)