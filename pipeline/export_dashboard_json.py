#!/usr/bin/env python3
"""
export_dashboard_json.py — Synchronize pipeline

Reads a WAL-checkpointed COPY of the Synchronize app database and emits a
single dashboard_data.json for the read-only synchronize-dashboard React app.

v1.1 — column names corrected against the live schema (2026-08-12).

Design constraints (deliberate — read before modifying):

  1. NEVER touches the live app database. Opens read-only + immutable, and
     refuses by default if -wal/-shm siblings are present.

  2. biosignal_samples is WIDE (one row per timestamp, many metric columns),
     not long. Each metric is emitted as its own series with nulls dropped,
     so the dashboard can toggle streams independently and so sparse metrics
     are visibly sparse rather than silently zero-filled.

  3. A REQUIRED column that fails to resolve ZEROES that stream and raises a
     hard error. An empty series must never look like a populated one.

  4. Pilot-phase data (7/25-7/29) is TAGGED, not dropped. Training excludes
     it; the dashboard shows it marked. See thesis 3.7.6.

  5. All timestamps stay UTC epoch milliseconds end to end.

  6. transmitterId is deliberately excluded — device identifier, no dashboard
     value, and no reason to copy it into another artifact.

Usage:
    python export_dashboard_json.py --db <path/to/synchronize_db> \
                                --out dashboard_data.json
"""

from __future__ import annotations

import argparse
import json
import os
import sqlite3
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

# --------------------------------------------------------------------------
# Study constants — keep in sync with build_features.py
# --------------------------------------------------------------------------

PILOT_START_MS = 1753426800000   # 2026-07-25 00:00 America/Los_Angeles
STUDY_START_MS = 1753858800000   # 2026-07-30 00:00 America/Los_Angeles
STUDY_END_MS = 1756450800000     # 2026-08-29 00:00 America/Los_Angeles

LOCAL_UTC_OFFSET_HOURS = -7      # PDT; used only for day-bucketing labels

CGM_GAP_THRESHOLD_MS = 15 * 60 * 1000
CGM_EXPECTED_INTERVAL_MS = 5 * 60 * 1000

SCHEMA_VERSION = "dashboard-1.1"


# --------------------------------------------------------------------------
# Table + column definitions (real names, verified 2026-08-12)
# --------------------------------------------------------------------------

# field -> (candidate column names, required)
TABLE_SPECS: dict[str, dict[str, Any]] = {
    "glucose": {
        "table": ["glucose_readings"],
        "order": "t",
        "fields": {
            "t":        (["deviceTimestamp"], True),
            "value":    (["glucoseMgDl"], True),
            "trend":    (["trend"], False),
            "trendRate": (["trendRate"], False),
        },
    },
    "ketone": {
        "table": ["ketone_readings"],
        "order": "t",
        "fields": {
            "t":             (["deviceTimestamp"], True),
            "value":         (["betaHydroxybutyrate"], True),
            "pairedGlucose": (["glucoseMgDl"], False),
            "context":       (["readingContext"], False),
            "source":        (["source"], False),
            "notes":         (["notes"], False),
        },
    },
    "meals": {
        "table": ["meal_entries"],
        "order": "t",
        "fields": {
            "t":              (["deviceTimestamp"], True),
            "label":          (["mealLabel"], False),
            "calories":       (["calories"], False),
            "fat":            (["fatGrams"], False),
            "protein":        (["proteinGrams"], False),
            "netCarb":        (["netCarbGrams"], False),
            "fiber":          (["fiberGrams"], False),
            "totalCarb":      (["totalCarbGrams"], False),
            "ketogenicRatio": (["ketogenicRatio"], False),
            "transcription":  (["rawTranscription"], False),
            "parsingMethod":  (["parsingMethod"], False),
            "confirmed":      (["confirmed"], False),
        },
    },
    "exercise": {
        "table": ["exercise_sessions"],
        "order": "t",
        "fields": {
            "t":           (["startTimestamp"], True),
            "durationMin": (["durationMinutes"], False),
            "type":        (["sessionType"], False),
            "isHiit":      (["isHiit"], False),
            "notes":       (["notes"], False),
        },
    },
    "sleep": {
        "table": ["sleep_sessions"],
        "order": "t",
        "fields": {
            "t":            (["sleepStartTimestamp"], True),
            "endT":         (["sleepEndTimestamp"], False),
            "totalMin":     (["totalDurationMinutes"], False),
            "deepMin":      (["deepSleepMinutes"], False),
            "remMin":       (["remSleepMinutes"], False),
            "lightMin":     (["lightSleepMinutes"], False),
            # NOTE: three-e typo is in the live schema. Do not rename
            # mid-collection — that is a migration. Fix post-collection.
            "awakeMin":     (["awakeDuringSleeepMinutes",
                              "awakeDuringSleepMinutes"], False),
            "deepPct":      (["deepSleepPercent"], False),
            "remPct":       (["remSleepPercent"], False),
            "lightPct":     (["lightSleepPercent"], False),
            "awakePct":     (["awakePercent"], False),
            "rmssd":        (["meanOvernightRmssd"], False),
            "sdnn":         (["meanOvernightSdnn"], False),
            "meanHr":       (["meanOvernightHeartRate"], False),
            "skinTemp":     (["meanSkinTempCelsius"], False),
            "skinTempDelta": (["skinTempDeltaCelsius"], False),
            "sleepScore":   (["sleepScore"], False),
            "efficiency":   (["sleepEfficiency"], False),
        },
    },
    "covariates": {
        "table": ["daily_covariates"],
        "order": "dateKey",
        "fields": {
            "dateKey":         (["dateKey"], True),
            "t":               (["deviceTimestamp"], False),
            "caffeineMg":      (["caffeineMg"], False),
            "meditationAmMin": (["meditationAmMinutes"], False),
            "meditationPmMin": (["meditationPmMinutes"], False),
            "notes":           (["notes"], False),
        },
    },
}

# biosignal_samples is wide: metric key -> column name
BIOSIGNAL_METRICS: dict[str, str] = {
    "heartRate":      "heartRateBpm",
    "hrvRmssd":       "hrvRmssd",
    "hrvSdnn":        "hrvSdnn",
    "spo2":           "spo2Percent",
    "skinTemp":       "skinTempCelsius",
    "skinTempDelta":  "skinTempDelta",
    "systolic":       "systolicMmHg",
    "diastolic":      "diastolicMmHg",
    "ecgMv":          "ecgMv",
    "steps":          "stepCount",
    "activeCalories": "activeCalories",
    "stress":         "stressScore",
    "vo2Max":         "vo2Max",
    "ppgGreen":       "ppgGreen",
    "ppgRed":         "ppgRed",
    "ppgInfrared":    "ppgInfrared",
}

BIOSIGNAL_TIME_COL = "deviceTimestamp"


# --------------------------------------------------------------------------
# Schema resolution
# --------------------------------------------------------------------------

class SchemaReport:
    def __init__(self) -> None:
        self.resolved: dict[str, dict[str, str]] = {}
        self.unmapped: list[str] = []
        self.hard_errors: list[str] = []
        self.missing_tables: list[str] = []
        self.notes: list[str] = []

    def to_dict(self) -> dict[str, Any]:
        return {
            "resolved": self.resolved,
            "unmapped_optional": self.unmapped,
            "hard_errors": self.hard_errors,
            "missing_tables": self.missing_tables,
            "notes": self.notes,
        }


def list_tables(conn: sqlite3.Connection) -> list[str]:
    return [
        r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' "
            "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' "
            "AND name NOT LIKE 'room_%'"
        )
    ]


def _norm(s: str) -> str:
    return "".join(ch for ch in s.lower() if ch.isalnum())


def find_one(pool: list[str], candidates: list[str]) -> str | None:
    lowered = {x.lower(): x for x in pool}
    for c in candidates:
        if c.lower() in lowered:
            return lowered[c.lower()]
    normed = {_norm(x): x for x in pool}
    for c in candidates:
        if _norm(c) in normed:
            return normed[_norm(c)]
    return None


def columns_of(conn: sqlite3.Connection, table: str) -> list[str]:
    return [r[1] for r in conn.execute(f'PRAGMA table_info("{table}")')]


# --------------------------------------------------------------------------
# Extraction
# --------------------------------------------------------------------------

def phase_for(ts_ms: int | None) -> str:
    if ts_ms is None:
        return "unknown"
    if ts_ms < PILOT_START_MS:
        return "pre-study"
    if ts_ms < STUDY_START_MS:
        return "pilot"
    if ts_ms <= STUDY_END_MS:
        return "collection"
    return "post-study"


def local_day(ts_ms: int) -> str:
    dt = datetime.fromtimestamp(ts_ms / 1000, tz=timezone.utc) \
        + timedelta(hours=LOCAL_UTC_OFFSET_HOURS)
    return dt.strftime("%Y-%m-%d")


def extract_table(conn: sqlite3.Connection, key: str, spec: dict,
                  tables: list[str], report: SchemaReport) -> list[dict]:
    table = find_one(tables, spec["table"])
    if table is None:
        report.missing_tables.append(spec["table"][0])
        return []

    cols = columns_of(conn, table)
    mapping: dict[str, str] = {}
    for field, (candidates, required) in spec["fields"].items():
        col = find_one(cols, candidates)
        if col is None:
            label = f"{table}.{field}"
            if required:
                report.hard_errors.append(
                    f"{label} REQUIRED but unresolved — '{key}' stream zeroed"
                )
                return []
            report.unmapped.append(label)
        else:
            mapping[field] = col

    report.resolved[table] = mapping
    select = ", ".join(f'"{c}" AS "{f}"' for f, c in mapping.items())
    order = spec.get("order")
    sql = f'SELECT {select} FROM "{table}"'
    if order and order in mapping:
        sql += f' ORDER BY "{mapping[order]}" ASC'

    conn.row_factory = sqlite3.Row
    rows = [dict(r) for r in conn.execute(sql)]
    for r in rows:
        if r.get("t") is not None:
            r["phase"] = phase_for(r["t"])
    return rows


def extract_biosignals(conn: sqlite3.Connection, tables: list[str],
                       report: SchemaReport) -> tuple[dict, dict]:
    """Wide -> per-metric series. Returns (series, per-metric coverage)."""
    table = find_one(tables, ["biosignal_samples"])
    if table is None:
        report.missing_tables.append("biosignal_samples")
        return {}, {}

    cols = columns_of(conn, table)
    tcol = find_one(cols, [BIOSIGNAL_TIME_COL, "timestamp"])
    if tcol is None:
        report.hard_errors.append(
            "biosignal_samples timestamp REQUIRED but unresolved — zeroed"
        )
        return {}, {}

    present: dict[str, str] = {}
    for metric, col in BIOSIGNAL_METRICS.items():
        found = find_one(cols, [col])
        if found:
            present[metric] = found
        else:
            report.unmapped.append(f"{table}.{metric}")

    report.resolved[table] = {"t": tcol, **present}

    total_rows = conn.execute(f'SELECT COUNT(*) FROM "{table}"').fetchone()[0]

    series: dict[str, list[dict]] = {}
    coverage: dict[str, Any] = {}

    for metric, col in present.items():
        conn.row_factory = sqlite3.Row
        rows = conn.execute(
            f'SELECT "{tcol}" AS t, "{col}" AS v FROM "{table}" '
            f'WHERE "{col}" IS NOT NULL ORDER BY "{tcol}" ASC'
        ).fetchall()

        pts = [{"t": r["t"], "v": r["v"]} for r in rows]
        series[metric] = pts

        days: dict[str, int] = {}
        in_window = 0
        for p in pts:
            if p["t"] is None:
                continue
            days[local_day(p["t"])] = days.get(local_day(p["t"]), 0) + 1
            if STUDY_START_MS <= p["t"] <= STUDY_END_MS:
                in_window += 1

        coverage[metric] = {
            "non_null": len(pts),
            "pct_of_rows": round(100 * len(pts) / total_rows, 2)
            if total_rows else 0.0,
            "in_collection_window": in_window,
            "days_present": len(days),
            "first": pts[0]["t"] if pts else None,
            "last": pts[-1]["t"] if pts else None,
            "per_day": dict(sorted(days.items())),
        }

    coverage["_total_rows"] = total_rows
    return series, coverage


# --------------------------------------------------------------------------
# Coverage / QA
# --------------------------------------------------------------------------

def cgm_coverage(glucose: list[dict]) -> dict[str, Any]:
    pts = sorted(r["t"] for r in glucose if r.get("t") is not None)
    if len(pts) < 2:
        return {"readings": len(pts), "gaps": [], "note": "insufficient data"}

    gaps = [
        {"from": a, "to": b, "minutes": round((b - a) / 60000, 1),
         "day": local_day(a)}
        for a, b in zip(pts, pts[1:]) if (b - a) > CGM_GAP_THRESHOLD_MS
    ]
    span = pts[-1] - pts[0]
    expected = span / CGM_EXPECTED_INTERVAL_MS if span else 0
    return {
        "readings": len(pts),
        "in_collection_window": sum(
            1 for p in pts if STUDY_START_MS <= p <= STUDY_END_MS),
        "first": pts[0], "last": pts[-1],
        "span_days": round(span / 86400000, 2),
        "expected_readings": int(expected),
        "missing_pct": round(100 * (1 - len(pts) / expected), 2)
        if expected > 0 else None,
        "gap_count": len(gaps),
        "gaps": gaps[:100],
    }


def per_day(series: list[dict], tkey: str = "t") -> dict[str, int]:
    out: dict[str, int] = {}
    for r in series:
        ts = r.get(tkey)
        if ts is None:
            continue
        d = local_day(ts)
        out[d] = out.get(d, 0) + 1
    return dict(sorted(out.items()))


def nights_elapsed(now_ms: int) -> int:
    end = min(now_ms, STUDY_END_MS)
    return max(0, int((end - STUDY_START_MS) / 86400000))


# --------------------------------------------------------------------------
# Safety
# --------------------------------------------------------------------------

def open_db_readonly(path: Path, allow_live: bool) -> sqlite3.Connection:
    if not path.exists():
        sys.exit(f"ERROR: database not found: {path}")

    present = [
        s.name for s in
        (path.with_name(path.name + x) for x in ("-wal", "-shm"))
        if s.exists()
    ]
    if present and not allow_live:
        sys.exit(
            f"ERROR: found {', '.join(present)} next to {path.name}.\n"
            "That usually means a live database rather than a checkpointed "
            "backup.\nPull a checkpointed copy first, or pass --allow-live if "
            "you are certain.\nRefusing to contend with the collection app for "
            "the live DB."
        )

    posix = path.as_posix()
    if len(posix) > 1 and posix[1] == ":":      # Windows drive letter
        posix = "/" + posix
    return sqlite3.connect(f"file:{posix}?mode=ro&immutable=1", uri=True)


# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------

def main() -> None:
    ap = argparse.ArgumentParser(
        description="Export Synchronize data to dashboard_data.json")
    ap.add_argument("--db", required=True,
                    help="path to a WAL-checkpointed COPY of the app database")
    ap.add_argument("--out", default="dashboard_data.json")
    ap.add_argument("--allow-live", action="store_true")
    ap.add_argument("--indent", type=int, default=None)
    args = ap.parse_args()

    db_path = Path(args.db).expanduser().resolve()
    report = SchemaReport()
    conn = open_db_readonly(db_path, args.allow_live)

    try:
        tables = list_tables(conn)
        report.notes.append(f"tables: {', '.join(sorted(tables))}")

        streams: dict[str, Any] = {}
        for key, spec in TABLE_SPECS.items():
            streams[key] = extract_table(conn, key, spec, tables, report)

        bio_series, bio_coverage = extract_biosignals(conn, tables, report)
        streams["biosignals"] = bio_series
    finally:
        conn.close()

    now_ms = int(datetime.now(tz=timezone.utc).timestamp() * 1000)
    sleep_nights = len({local_day(r["t"]) for r in streams["sleep"]
                        if r.get("t") is not None})
    elapsed = nights_elapsed(now_ms)

    payload = {
        "meta": {
            "schema_version": SCHEMA_VERSION,
            "generated_at": now_ms,
            "source_db": db_path.name,
            "source_db_mtime": int(os.path.getmtime(db_path) * 1000),
            "study": {
                "pilot_start": PILOT_START_MS,
                "collection_start": STUDY_START_MS,
                "collection_end": STUDY_END_MS,
                "nights_elapsed": elapsed,
            },
            "counts": {
                k: (sum(len(v) for v in val.values())
                    if isinstance(val, dict) else len(val))
                for k, val in streams.items()
            },
            "schema_report": report.to_dict(),
        },
        "coverage": {
            "cgm": cgm_coverage(streams["glucose"]),
            "biosignals": bio_coverage,
            "ketone_per_day": per_day(streams["ketone"]),
            "meals_per_day": per_day(streams["meals"]),
            "exercise_per_day": per_day(streams["exercise"]),
            "sleep": {
                "sessions": len(streams["sleep"]),
                "distinct_nights": sleep_nights,
                "nights_elapsed": elapsed,
                "pct_of_nights": round(100 * sleep_nights / elapsed, 1)
                if elapsed else None,
            },
        },
        "streams": streams,
    }

    out_path = Path(args.out).expanduser().resolve()
    with out_path.open("w", encoding="utf-8") as f:
        json.dump(payload, f, indent=args.indent)

    # ---- console report ---------------------------------------------------
    print(f"\nWrote {out_path}  ({out_path.stat().st_size / 1024:,.0f} KB)\n")

    print("Stream counts:")
    for k, v in payload["meta"]["counts"].items():
        print(f"  {k:24s} {v:>8,}")

    cov = payload["coverage"]["cgm"]
    if cov.get("readings", 0) >= 2:
        print(f"\nCGM:  {cov['readings']:,} readings over "
              f"{cov['span_days']} days | missing {cov['missing_pct']}% "
              f"[criterion <5%] | {cov['gap_count']} gaps >15min")

    print("\nBiosignal metric coverage "
          f"({bio_coverage.get('_total_rows', 0):,} sample rows):")
    print(f"  {'metric':<16} {'non-null':>9} {'% rows':>8} {'days':>6}")
    for metric, c in bio_coverage.items():
        if metric.startswith("_"):
            continue
        flag = "" if c["non_null"] else "   <-- EMPTY"
        print(f"  {metric:<16} {c['non_null']:>9,} "
              f"{c['pct_of_rows']:>7}% {c['days_present']:>6}{flag}")

    s = payload["coverage"]["sleep"]
    print(f"\nSleep: {s['distinct_nights']} nights captured of "
          f"{s['nights_elapsed']} elapsed ({s['pct_of_nights']}%) "
          "[criterion >90%]")

    if report.hard_errors:
        print("\n  HARD ERRORS — streams zeroed:")
        for e in report.hard_errors:
            print(f"      {e}")

    if report.unmapped:
        print("\n  Unmapped optional fields (absent from schema, expected):")
        for u in report.unmapped:
            print(f"      {u}")

    print()
    if report.hard_errors:
        sys.exit(1)


if __name__ == "__main__":
    main()