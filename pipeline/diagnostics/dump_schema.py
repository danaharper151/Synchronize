#!/usr/bin/env python3
"""
dump_schema.py — one-off utility for synchronize-pipeline

Prints every table, every column (with declared type), and one sample row
per table so the export script's column mappings can be corrected.

Read-only. Safe to run against a backup. Delete once mappings are fixed.

Usage:
    python diagnostics/dump_schema.py <path/to/synchronize_db>
"""

import sqlite3
import sys
from pathlib import Path


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit("usage: py dump_schema.py <path-to-db>")

    path = Path(sys.argv[1]).expanduser().resolve()
    if not path.exists():
        sys.exit(f"not found: {path}")

    posix = path.as_posix()
    if len(posix) > 1 and posix[1] == ":":      # Windows drive letter
        posix = "/" + posix

    conn = sqlite3.connect(f"file:{posix}?mode=ro&immutable=1", uri=True)
    conn.row_factory = sqlite3.Row

    tables = [
        r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' "
            "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' "
            "ORDER BY name"
        )
    ]

    for t in tables:
        cols = list(conn.execute(f'PRAGMA table_info("{t}")'))
        count = conn.execute(f'SELECT COUNT(*) FROM "{t}"').fetchone()[0]

        print(f"\n{'=' * 70}")
        print(f"{t}   ({count:,} rows)")
        print("=" * 70)
        for c in cols:
            print(f"  {c[1]:<34} {c[2]}")

        row = conn.execute(f'SELECT * FROM "{t}" LIMIT 1').fetchone()
        if row:
            print("\n  sample row:")
            for k in row.keys():
                v = row[k]
                if isinstance(v, str) and len(v) > 60:
                    v = v[:60] + "..."
                print(f"    {k:<32} = {v!r}")

    # Distinct signal types drive how the dashboard splits biosignal series.
    for tbl in ("biosignal_samples",):
        if tbl not in tables:
            continue
        names = [c[1] for c in conn.execute(f'PRAGMA table_info("{tbl}")')]
        for cand in names:
            if any(k in cand.lower()
                   for k in ("type", "metric", "signal", "kind", "name")):
                print(f"\n{'=' * 70}")
                print(f"distinct {tbl}.{cand}")
                print("=" * 70)
                for r in conn.execute(
                    f'SELECT "{cand}" AS v, COUNT(*) AS n FROM "{tbl}" '
                    f'GROUP BY "{cand}" ORDER BY n DESC'
                ):
                    print(f"  {str(r['v']):<34} {r['n']:>8,}")

    conn.close()
    print()


if __name__ == "__main__":
    main()