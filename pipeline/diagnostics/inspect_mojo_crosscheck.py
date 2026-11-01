"""
Synchronize — Keto-Mojo Cross-Check
====================================
Compares Synchronize's manually-entered ketone readings against the
Keto-Mojo meter's own export, to:

  1. Catch transcription errors — a Synchronize entry whose value doesn't
     match its corresponding meter reading.
  2. Catch missed logging — a meter reading with no corresponding
     Synchronize entry, isolated in time (not part of a consistency check).
  3. Summarize routine consistency checks — meter readings taken close to
     an already-logged reading (extra triplicate-style checks). Expected,
     not a data quality concern, but tallied for §3.7.2 documentation.

Usage:
    python diagnostics/inspect_mojo_crosscheck.py --db <path/to/synchronize_db> --mojo <path/to/ketomojo_export.csv>
"""

import argparse
import pandas as pd

import sys
from pathlib import Path as _Path
sys.path.insert(0, str(_Path(__file__).resolve().parent.parent))  # pipeline/
from build_features_v2 import load_app_db as load_synchronize_db, to_epoch_ms

MATCH_TOLERANCE_MIN = 5       # Synchronize entry <-> meter reading, same event
NEARBY_TOLERANCE_MIN = 20     # meter reading near ANY logged entry = likely
                               # a consistency-check extra, not a missed log
VALUE_DISCREPANCY_THRESHOLD = 0.1   # mmol/L — matches the researcher's own
                                     # stated triplicate-consistency threshold


def load_mojo(csv_path: str) -> pd.DataFrame:
    df = pd.read_csv(csv_path, sep=';', skiprows=2, quotechar='"',
                     low_memory=False)

    # Defensive cleanup — strip whitespace and any quote characters that
    # weren't fully handled by the CSV parser given this export's format.
    df.columns = [c.strip().strip('"') for c in df.columns]
    for col in df.select_dtypes(include='object').columns:
        df[col] = df[col].astype(str).str.strip().str.strip('"')

    ketones = df[df['reading_type'] == 'ketone'].copy()

    # reading_timestamp already carries an explicit UTC offset, so no
    # assume_local guessing is needed — but still route through
    # to_epoch_ms for the same resolution-independent conversion used
    # everywhere else in the pipeline (this exact 1970-epoch bug already
    # bit us once today, in load_dexcom).
    ketones['ts_ms'] = to_epoch_ms(ketones['reading_timestamp'])
    ketones['ts'] = pd.to_datetime(ketones['ts_ms'], unit='ms')
    ketones['value'] = pd.to_numeric(ketones['reading_value'], errors='coerce')
    ketones = ketones.dropna(subset=['ts_ms', 'value'])
    ketones = ketones.sort_values('ts_ms').reset_index(drop=True)

    print(f"  [mojo] {len(ketones)} ketone readings loaded "
          f"({ketones['ts'].min():%Y-%m-%d %H:%M} → "
          f"{ketones['ts'].max():%Y-%m-%d %H:%M})")
    return ketones


def run(db_path: str, mojo_csv: str):
    print("=" * 74)
    print("KETO-MOJO CROSS-CHECK")
    print("=" * 74)

    print("\nLoading Synchronize database...")
    app = load_synchronize_db(db_path)
    sync = app.get('ketone_readings', pd.DataFrame())
    if sync.empty:
        print("No ketone readings in Synchronize database.")
        return

    print("\nLoading Keto-Mojo export...")
    mojo = load_mojo(mojo_csv)

    tol_ms = MATCH_TOLERANCE_MIN * 60_000
    nearby_ms = NEARBY_TOLERANCE_MIN * 60_000

    mismatches = []
    matched_mojo_idx = set()

    print("\nMatching Synchronize entries to meter readings...")
    for _, s in sync.iterrows():
        s_ts = s['deviceTimestamp']
        s_val = s['betaHydroxybutyrate']

        candidates = mojo[(mojo['ts_ms'] >= s_ts - tol_ms) &
                          (mojo['ts_ms'] <= s_ts + tol_ms)]
        if candidates.empty:
            mismatches.append({
                'type': 'NO METER MATCH',
                'sync_time': pd.Timestamp(s_ts, unit='ms'),
                'sync_value': s_val,
                'meter_value': None,
                'diff': None,
            })
            continue

        candidates = candidates.copy()
        candidates['time_diff'] = (candidates['ts_ms'] - s_ts).abs()
        best = candidates.loc[candidates['time_diff'].idxmin()]
        matched_mojo_idx.add(best.name)

        diff = abs(s_val - best['value'])
        if diff > VALUE_DISCREPANCY_THRESHOLD:
            mismatches.append({
                'type': 'VALUE MISMATCH',
                'sync_time': pd.Timestamp(s_ts, unit='ms'),
                'sync_value': s_val,
                'meter_value': best['value'],
                'diff': diff,
            })

    unmatched = mojo[~mojo.index.isin(matched_mojo_idx)]
    isolated, nearby_extra = [], []

    for _, m in unmatched.iterrows():
        near_any = ((sync['deviceTimestamp'] >= m['ts_ms'] - nearby_ms) &
                    (sync['deviceTimestamp'] <= m['ts_ms'] + nearby_ms)).any()
        entry = {'time': pd.Timestamp(m['ts_ms'], unit='ms'), 'value': m['value']}
        (nearby_extra if near_any else isolated).append(entry)

    # ── Report ────────────────────────────────────────────────
    print("\n" + "=" * 74)
    print("RESULTS")
    print("=" * 74)

    no_match = [m for m in mismatches if m['type'] == 'NO METER MATCH']
    value_mismatches = [m for m in mismatches if m['type'] == 'VALUE MISMATCH']

    print(f"\nSynchronize entries: {len(sync)}")
    print(f"Meter readings (ketone): {len(mojo)}")

    print(f"\nVALUE MISMATCHES (>{VALUE_DISCREPANCY_THRESHOLD} mmol/L "
          f"difference): {len(value_mismatches)}")
    for m in sorted(value_mismatches, key=lambda x: -x['diff']):
        print(f"  {m['sync_time']:%Y-%m-%d %H:%M}  "
              f"Synchronize={m['sync_value']:.1f}  "
              f"Meter={m['meter_value']:.1f}  diff={m['diff']:.2f}")

    print(f"\nSYNCHRONIZE ENTRIES WITH NO METER MATCH: {len(no_match)}")
    for m in no_match:
        print(f"  {m['sync_time']:%Y-%m-%d %H:%M}  "
              f"Synchronize={m['sync_value']:.1f}  (no meter reading within "
              f"{MATCH_TOLERANCE_MIN} min)")

    print(f"\nLIKELY MISSED LOGS — isolated meter reading, no nearby "
          f"Synchronize entry: {len(isolated)}")
    for e in sorted(isolated, key=lambda x: x['time']):
        print(f"  {e['time']:%Y-%m-%d %H:%M}  value={e['value']:.1f}")

    print(f"\nLikely consistency-check extras (near a logged reading, "
          f"not a concern): {len(nearby_extra)}")

    matched_count = len(sync) - len(no_match)
    if matched_count > 0:
        exact_or_close = matched_count - len(value_mismatches)
        rate = exact_or_close / matched_count * 100
        print(f"\nTranscription accuracy: {exact_or_close}/{matched_count} "
              f"({rate:.1f}%) within {VALUE_DISCREPANCY_THRESHOLD} mmol/L "
              f"of meter value  [feeds §3.7.2]")

    print("\n" + "=" * 74)


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--db', required=True)
    p.add_argument('--mojo', required=True)
    a = p.parse_args()
    run(a.db, a.mojo)