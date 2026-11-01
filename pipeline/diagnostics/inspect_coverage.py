"""
Synchronize — Samsung Coverage Inspector
=========================================
Visualizes when each Samsung biosignal stream actually has data, as an
hourly density heatmap per day. Answers: does a stream record continuously,
or does it have a systematic gap at certain times of day — specifically,
the moments you actually take ketone readings?

Usage:
    python diagnostics/inspect_coverage.py --samsung samsung_parsed --db <path/to/synchronize_db>
"""

import argparse
from datetime import datetime, timedelta

import pandas as pd

import sys
from pathlib import Path as _Path
sys.path.insert(0, str(_Path(__file__).resolve().parent.parent))  # pipeline/
from build_features_v2 import load_samsung, load_app_db as load_synchronize_db, MS_PER_MIN


STREAMS_TO_SHOW = [
    'hrv', 'heart_rate', 'spo2', 'skin_temperature',
    'stress', 'respiratory_rate',
]

DENSITY_THRESHOLDS = [(0, ' '), (1, '.'), (6, ':'), (20, '#')]


def density_char(count: int) -> str:
    ch = ' '
    for threshold, c in DENSITY_THRESHOLDS:
        if count >= threshold:
            ch = c
    return ch


def build_heatmap(df: pd.DataFrame, day_start_ms: int) -> list:
    if df.empty or 'ts' not in df.columns:
        return [' '] * 24
    row = []
    for h in range(24):
        bin_start = day_start_ms + h * 60 * MS_PER_MIN
        bin_end = bin_start + 60 * MS_PER_MIN
        count = int(((df['ts'] >= bin_start) & (df['ts'] < bin_end)).sum())
        row.append(density_char(count))
    return row


def run(samsung_dir: str, db_path: str | None):
    print("=" * 78)
    print("SAMSUNG STREAM COVERAGE — hourly density per day")
    print("Legend:  (blank)=0 samples   .=1-5   :=6-19   #=20+")
    print("  'o' = ketone reading logged, stream had ZERO samples that hour")
    print("  '*' = ketone reading logged, stream DID have samples that hour")
    print("=" * 78)

    samsung = load_samsung(samsung_dir)

    all_ts = []
    for name in STREAMS_TO_SHOW:
        df = samsung.get(name, pd.DataFrame())
        if not df.empty and 'ts' in df.columns:
            all_ts.append(df['ts'])
    if not all_ts:
        print("No timestamped streams found.")
        return

    combined = pd.concat(all_ts)
    min_ts, max_ts = int(combined.min()), int(combined.max())

    start_day = datetime.fromtimestamp(min_ts / 1000).replace(
        hour=0, minute=0, second=0, microsecond=0)
    end_day = datetime.fromtimestamp(max_ts / 1000).replace(
        hour=0, minute=0, second=0, microsecond=0)

    reading_hours_by_day = {}
    if db_path:
        app = load_synchronize_db(db_path)
        ketones = app.get('ketone_readings', pd.DataFrame())
        if not ketones.empty:
            for _, k in ketones.iterrows():
                dt = datetime.fromtimestamp(k['deviceTimestamp'] / 1000)
                key = dt.strftime('%Y-%m-%d')
                reading_hours_by_day.setdefault(key, []).append(
                    (dt.hour, str(k.get('readingContext') or '')[:4])
                )

    hour_header = ''.join(str(h % 10) for h in range(24))
    print(f"\n{'':12} {hour_header}")

    for name in STREAMS_TO_SHOW:
        df = samsung.get(name, pd.DataFrame())
        print(f"\n{name}  ({len(df):,} total samples)")

        day = start_day
        while day <= end_day:
            day_start_ms = int(day.timestamp() * 1000)
            heat = build_heatmap(df, day_start_ms)

            key = day.strftime('%Y-%m-%d')
            for h, _ctx in reading_hours_by_day.get(key, []):
                if 0 <= h < 24:
                    heat[h] = 'o' if heat[h] == ' ' else '*'

            print(f"  {key} {''.join(heat)}")
            day += timedelta(days=1)

    # Reading-time reference, once, so the hour columns are easy to read
    if reading_hours_by_day:
        print("\nReading contexts by day (hour: context)")
        for key in sorted(reading_hours_by_day):
            entries = sorted(reading_hours_by_day[key])
            summary = ', '.join(f"{h}:{ctx}" for h, ctx in entries)
            print(f"  {key}  {summary}")

    print("\n" + "=" * 78)


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--samsung', required=True)
    p.add_argument('--db', default=None)
    a = p.parse_args()
    run(a.samsung, a.db)