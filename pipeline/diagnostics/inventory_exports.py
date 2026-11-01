
"""
inventory_exports.py — Synchronize pipeline

Surveys every data file in the pipeline folders and reports what is there,
what format it is in, and what date range it covers. Read-only; touches
nothing.

Handles the quirks already known in this project:
  - Samsung CSVs with systematically misaligned headers (columns located by
    content, not name)
  - Keto-Mojo semicolon-delimited CSVs with two junk lines
  - Dexcom Clarity CSVs with alert-settings rows before the EGV rows
  - SQLite app-database backups (opened read-only + immutable)

Usage, from the synchronize-pipeline folder:

    python diagnostics/inventory_exports.py --root .

  or point at specific places:

    python diagnostics/inventory_exports.py --root . --extra <path/to/exports>
"""

from __future__ import annotations

import argparse
import re
import sqlite3
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pandas as pd

# Training window, per the pre-registered determination of 2026-08-14
TRAIN_START = datetime(2026, 8, 2, tzinfo=timezone(timedelta(hours=-7)))
TRAIN_END = datetime(2026, 9, 1, tzinfo=timezone(timedelta(hours=-7)))

DATE_PAT = re.compile(r'^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}')
EPOCH_LO, EPOCH_HI = 1.5e12, 2.0e12          # plausible ms epoch range

SKIP_DIRS = {'.git', '__pycache__', '.venv', 'node_modules', '.idea'}

# Samsung's export contains thousands of sharded binning JSONs. Summarize the
# tree rather than listing every file; parse_samsung_export.py consumes them.
SUMMARIZE_DIRS = {'jsons'}


# ── helpers ───────────────────────────────────────────────────

def human(n: int) -> str:
    return f"{n:,}"


def read_csv_any(path: Path) -> tuple[pd.DataFrame, str]:
    """Try the read strategies this project actually needs, in order."""
    attempts = [
        ("plain", dict(low_memory=False)),
        ("skiprows=1", dict(skiprows=1, low_memory=False)),
        ("sep=';' skiprows=2", dict(sep=';', skiprows=2, quotechar='"',
                                    low_memory=False)),
        ("sep=';'", dict(sep=';', quotechar='"', low_memory=False)),
        ("skiprows=2", dict(skiprows=2, low_memory=False)),
    ]
    best, best_note, best_score = None, "", -1
    for note, kw in attempts:
        try:
            df = pd.read_csv(path, **kw)
        except Exception:
            continue
        if df.empty:
            continue
        # prefer the parse that yields the most columns with real data
        score = df.notna().any().sum()
        if score > best_score:
            best, best_note, best_score = df, note, score
    if best is None:
        return pd.DataFrame(), "unreadable"
    return best, best_note


def detect_time_column(df: pd.DataFrame):
    """Content-based. Returns (column, kind) or (None, None)."""
    for col in df.columns:
        s = df[col].dropna()
        if not len(s):
            continue
        num = pd.to_numeric(s, errors='coerce').dropna()
        if len(num) / len(s) > 0.8 and num.between(EPOCH_LO, EPOCH_HI).mean() > 0.8:
            return col, 'epoch_ms'
    best, best_score = None, 0.0
    for col in df.columns:
        s = df[col].dropna()
        if not len(s):
            continue
        frac = s.astype(str).str.match(DATE_PAT).mean()
        if frac > best_score:
            best, best_score = col, frac
    return (best, 'datetime_str') if best_score > 0.8 else (None, None)


def parse_times(df: pd.DataFrame, col: str, kind: str):
    if kind == 'epoch_ms':
        return pd.to_datetime(pd.to_numeric(df[col], errors='coerce'),
                              unit='ms', utc=True).dt.tz_convert(
                                  'America/Los_Angeles').dt.tz_localize(None)
    return pd.to_datetime(df[col], errors='coerce')


def coverage_line(ts: pd.Series) -> str:
    ts = ts.dropna()
    if ts.empty:
        return "no parseable timestamps"
    days = sorted(set(ts.dt.date))
    full = pd.date_range(days[0], days[-1], freq='D').date
    missing = [d for d in full if d not in set(days)]
    in_win = ts[(ts >= TRAIN_START.replace(tzinfo=None)) &
                (ts < TRAIN_END.replace(tzinfo=None))]
    out = (f"{ts.min():%Y-%m-%d %H:%M} -> {ts.max():%Y-%m-%d %H:%M}  "
           f"{len(days)} days")
    if missing:
        out += f"  MISSING {len(missing)}: " + ", ".join(str(m) for m in missing[:6])
        if len(missing) > 6:
            out += f" (+{len(missing)-6})"
    out += f"  | in training window: {human(len(in_win))}"
    return out


# ── inspectors ────────────────────────────────────────────────

def inspect_sqlite(path: Path):
    print(f"\n  SQLITE  {path.name}")
    posix = path.as_posix()
    if len(posix) > 1 and posix[1] == ":":
        posix = "/" + posix
    try:
        conn = sqlite3.connect(f"file:{posix}?mode=ro&immutable=1", uri=True)
    except Exception as e:
        print(f"    could not open: {e}")
        return
    try:
        tables = [r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' "
            "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' "
            "AND name NOT LIKE 'room_%' ORDER BY name")]
        for t in tables:
            n = conn.execute(f'SELECT COUNT(*) FROM "{t}"').fetchone()[0]
            cols = [r[1] for r in conn.execute(f'PRAGMA table_info("{t}")')]
            tcol = next((c for c in cols if c in
                         ('deviceTimestamp', 'startTimestamp',
                          'sleepStartTimestamp')), None)
            rng = ""
            if tcol and n:
                lo, hi = conn.execute(
                    f'SELECT MIN("{tcol}"), MAX("{tcol}") FROM "{t}"').fetchone()
                if lo and hi:
                    f = lambda ms: (datetime.fromtimestamp(ms/1000, tz=timezone.utc)
                                    + timedelta(hours=-7)).strftime('%m-%d %H:%M')
                    win = conn.execute(
                        f'SELECT COUNT(*) FROM "{t}" WHERE "{tcol}" >= ? AND "{tcol}" < ?',
                        (int(TRAIN_START.timestamp()*1000),
                         int(TRAIN_END.timestamp()*1000))).fetchone()[0]
                    rng = f"  {f(lo)} -> {f(hi)}  | in window: {human(win)}"
            print(f"    {t:<22} {human(n):>9} rows  {len(cols):>2} cols{rng}")
    finally:
        conn.close()


def inspect_csv(path: Path, indent="    "):
    df, note = read_csv_any(path)
    if df.empty:
        print(f"{indent}{path.name:<38} UNREADABLE")
        return
    col, kind = detect_time_column(df)
    head = f"{indent}{path.name:<38} {human(len(df)):>9} rows {len(df.columns):>3} cols"
    if note != "plain":
        head += f"  [{note}]"
    print(head)
    if col:
        ts = parse_times(df, col, kind)
        print(f"{indent}   time col: '{str(col)[:44]}' ({kind})")
        print(f"{indent}   {coverage_line(ts)}")
    else:
        print(f"{indent}   no timestamp column detected")
        print(f"{indent}   columns: {list(df.columns)[:8]}")


# ── main ──────────────────────────────────────────────────────

def walk(root: Path):
    print("=" * 78)
    print(f"INVENTORY  {root.resolve()}")
    print(f"Run: {datetime.now():%Y-%m-%d %H:%M}")
    print(f"Training window: {TRAIN_START:%Y-%m-%d} -> {TRAIN_END:%Y-%m-%d}")
    print("=" * 78)

    dirs = {}
    shard_counts = {}
    for p in sorted(root.rglob('*')):
        if any(part in SKIP_DIRS for part in p.parts):
            continue
        if any(part in SUMMARIZE_DIRS for part in p.parts):
            if p.is_file():
                key = next(q for q in p.parents if q.name in SUMMARIZE_DIRS)
                c, sz = shard_counts.get(key, (0, 0))
                shard_counts[key] = (c + 1, sz + p.stat().st_size)
            continue
        if not p.is_file():
            continue
        if p.suffix.lower() not in ('.csv', '.db', '.sqlite', '.json'):
            if not (p.suffix == '' and p.stat().st_size > 100_000):
                continue
        dirs.setdefault(p.parent, []).append(p)

    for d in sorted(dirs):
        rel = d.relative_to(root) if d != root else Path('.')
        files = dirs[d]
        total_mb = sum(f.stat().st_size for f in files) / 1e6
        print(f"\n{'-'*78}\n{rel}    ({len(files)} files, {total_mb:.1f} MB)\n{'-'*78}")
        for f in files:
            if f.suffix.lower() in ('.db', '.sqlite') or f.suffix == '':
                try:
                    inspect_sqlite(f)
                except Exception as e:
                    print(f"    {f.name}: {e}")
            elif f.suffix.lower() == '.csv':
                try:
                    inspect_csv(f)
                except Exception as e:
                    print(f"    {f.name}: ERROR {e}")
            elif f.suffix.lower() == '.json':
                print(f"    {f.name:<38} {f.stat().st_size/1e6:>8.1f} MB  (json)")

    for d, (n, sz) in sorted(shard_counts.items()):
        try:
            rel = d.relative_to(root)
        except ValueError:
            rel = d
        print(f"\n{'-'*78}\n{rel}    (SHARD TREE — summarized)\n{'-'*78}")
        print(f"    {human(n)} JSON files, {sz/1e6:.1f} MB")
        print("    consumed by parse_samsung_export.py; not listed individually")


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--root', default='.')
    ap.add_argument('--extra', action='append', default=[],
                    help='additional folders to survey')
    a = ap.parse_args()
    walk(Path(a.root))
    for e in a.extra:
        walk(Path(e))