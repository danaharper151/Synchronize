#!/usr/bin/env python3
"""
inventory_samsung_export.py  --  v1.2

Structural inventory of a Samsung Health export. Emits NO measurement values:
only file paths, field names, record counts, timestamp ranges, and hour-of-day
histograms. Output is safe to share.

Purpose
-------
1. Find streams and fields the purpose-built parser never looked for.
2. Determine EMPIRICALLY which streams are sleep-gated and which are
   continuous, by reading timestamps out of the JSON payloads themselves
   rather than inferring from device settings or from CSV index timestamps.
3. Report the header/data field-count relationship per stream, which is the
   root cause of the apparent column misalignment in this export format.
4. Flag fields carrying direct identifiers, for the de-identification pipeline.

Version history
---------------
v1.0  Initial. CSV reader accepted the leading metadata line as a header,
      reporting every stream as one row.
v1.1  Header chosen by maximum column count. Still wrong: data rows carry a
      trailing delimiter and so have one MORE field than the header, so the
      first data row won the comparison and was consumed as the header.
v1.2  Header located by field-count comparison against the data rows, and all
      reads use index_col=False.

      The trailing delimiter is the whole story of this export format. Data
      rows carry one more field than the header. Pandas resolves that
      ambiguity by promoting the first column to the index, which shifts every
      column label one position left of its data. The file parses without
      error, has the expected shape, and every value sits under the wrong
      name. index_col=False resolves it completely; the headers themselves are
      correctly ordered.

Usage
-----
    python diagnostics/inventory_samsung_export.py <path/to/samsunghealth_export> --out inventory
"""

import argparse
import csv
import json
import os
import re
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone

import pandas as pd

# Samsung exports naive local-time strings in CSVs and epoch milliseconds in
# JSON payloads. Set this to the collection timezone.
LOCAL_TZ = os.environ.get("SYNCHRONIZE_TZ", "America/Los_Angeles")

EPOCH_MS_MIN = 1_600_000_000_000   # 2020-09
EPOCH_MS_MAX = 2_000_000_000_000   # 2033-05

IDENTIFIER_TOKENS = {
    "age", "gender", "sex", "height", "weight", "birth", "birthday",
    "email", "phone", "imei", "serial", "address", "ssn",
}
IDENTIFIER_NAMES = {
    "name", "username", "user_name", "fullname", "full_name",
    "device_id", "deviceid", "bluetooth_name", "user_id", "uuid_user",
}

GATING_SLEEP_MAX = 0.02
GATING_MOSTLY_NIGHT_MAX = 0.25
DAY_START, DAY_END = 8, 20

# Column names that hold record bookkeeping times rather than measurement
# times. A stream whose only timestamps are these cannot be gated from the CSV.
RECORD_TIME_NAMES = {"create_time", "update_time", "modify_time",
                     "create_sh_ver", "modify_sh_ver"}


# --------------------------------------------------------------------------
# CSV inventory
# --------------------------------------------------------------------------

def sniff_delimiter(path, default=","):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            for _ in range(4):
                line = fh.readline()
                if not line:
                    break
                counts = {d: line.count(d) for d in [",", ";", "\t", "|"]}
                best = max(counts, key=counts.get)
                if counts[best] > 0:
                    return best
    except OSError:
        pass
    return default


def probe_layout(path, sep, probe=8):
    """Locate the header row by comparing field counts, not column counts.

    Samsung stream CSVs are laid out as:
        line 0   metadata: <stream>,<version>,<n>          -- few fields
        line 1   header:   create_sh_ver,start_time,...    -- N fields
        line 2+  data:     ...,                            -- N+1 fields

    The trailing delimiter on data rows is what makes the header shorter than
    the data. Selecting the row whose field count is within one of the data
    rows' field count identifies the header without trusting any header name.
    """
    rows = []
    try:
        with open(path, "r", encoding="utf-8", errors="replace", newline="") as fh:
            for i, row in enumerate(csv.reader(fh, delimiter=sep)):
                rows.append(row)
                if i + 1 >= probe:
                    break
    except OSError:
        return None

    if not rows:
        return None

    counts = [len(r) for r in rows]
    tail = counts[2:] if len(counts) > 2 else counts[1:]
    data_fields = Counter(tail).most_common(1)[0][0] if tail else counts[-1]

    header_idx = None
    for i, c in enumerate(counts):
        if c in (data_fields, data_fields - 1):
            header_idx = i
            break
    if header_idx is None:
        header_idx = min(1, len(counts) - 1)

    header_fields = counts[header_idx]
    return {
        "header_row_index": header_idx,
        "header_fields": header_fields,
        "data_fields": data_fields,
        "trailing_delimiter": bool(data_fields == header_fields + 1),
        "field_counts_probed": counts,
    }


def read_csv_tolerant(path):
    sep = sniff_delimiter(path)
    layout = probe_layout(path, sep)
    skip = layout["header_row_index"] if layout else 0
    try:
        df = pd.read_csv(
            path, sep=sep, skiprows=skip, index_col=False, engine="python",
            on_bad_lines="skip", encoding="utf-8", encoding_errors="replace",
        )
    except Exception:
        df = None
    return df, sep, skip, layout


def classify_column(series):
    non_null = series.dropna()
    if len(non_null) == 0:
        return "empty", None
    sample = non_null.head(500)

    if sample.astype(str).str.endswith(".json").mean() > 0.8:
        return "json_pointer", None

    numeric = pd.to_numeric(sample, errors="coerce").dropna()
    if len(numeric) > 0.8 * len(sample):
        if numeric.between(EPOCH_MS_MIN, EPOCH_MS_MAX).mean() > 0.9:
            return "epoch_ms", None
        return "numeric", None

    parsed = pd.to_datetime(sample, errors="coerce", format="mixed")
    if parsed.notna().mean() > 0.8:
        return "datetime_str", None

    return "categorical", int(non_null.nunique())


def to_local(series, kind):
    try:
        if kind == "epoch_ms":
            return pd.to_datetime(series, unit="ms", utc=True,
                                  errors="coerce").dt.tz_convert(LOCAL_TZ)
        ts = pd.to_datetime(series, errors="coerce", format="mixed")
        if getattr(ts.dt, "tz", None) is None:
            return ts.dt.tz_localize(LOCAL_TZ, ambiguous="NaT", nonexistent="NaT")
        return ts.dt.tz_convert(LOCAL_TZ)
    except Exception:
        return None


def summarize_hours(hours):
    day = sum(hours.get(h, 0) for h in range(DAY_START, DAY_END))
    night = sum(hours.get(h, 0)
                for h in list(range(DAY_END, 24)) + list(range(0, DAY_START)))
    out = {
        "hour_histogram": {str(h): int(hours.get(h, 0)) for h in range(24)},
        "daytime_0800_2000": int(day),
        "overnight_2000_0800": int(night),
    }
    total = day + night
    if total:
        share = day / total
        out["daytime_share"] = round(share, 4)
        out["gating"] = ("sleep_gated" if share < GATING_SLEEP_MAX
                         else "predominantly_overnight"
                         if share < GATING_MOSTLY_NIGHT_MAX else "continuous")
    return out


def flag_identifiers(names):
    flagged = set()
    for raw in names:
        low = str(raw).strip().lower()
        tokens = {t for t in re.split(r"[^a-z0-9]+", low) if t}
        if low in IDENTIFIER_NAMES or tokens & IDENTIFIER_TOKENS:
            flagged.add(str(raw))
    return sorted(flagged)


def inventory_csv(path, root):
    df, sep, skip, layout = read_csv_tolerant(path)
    rel = os.path.relpath(path, root)
    if df is None:
        return {"path": rel, "readable": False, "layout": layout}

    entry = {"path": rel, "readable": True, "delimiter": sep,
             "header_skiprows": skip, "layout": layout,
             "rows": int(len(df)), "columns": {}, "timestamp_candidates": [],
             "has_json_pointer": False}

    best_ts, best_n, best_col = None, 0, None
    for col in df.columns:
        kind, card = classify_column(df[col])
        info = {"detected_type": kind,
                "non_null": int(df[col].notna().sum()),
                "null_rate": round(float(df[col].isna().mean()), 4)}
        if card is not None:
            info["distinct_values"] = card
        entry["columns"][str(col)] = info

        if kind == "json_pointer":
            entry["has_json_pointer"] = True

        if kind in ("epoch_ms", "datetime_str"):
            entry["timestamp_candidates"].append(str(col))
            # Prefer a measurement-time column over a bookkeeping column.
            is_record_time = str(col).strip().lower() in RECORD_TIME_NAMES
            n = int(df[col].notna().sum())
            score = n * (0.1 if is_record_time else 1.0)
            if score > best_n:
                ts = to_local(df[col], kind)
                if ts is not None and ts.notna().sum():
                    best_ts, best_n, best_col = ts.dropna(), score, str(col)

    entry["identifier_fields"] = flag_identifiers(df.columns)

    if best_ts is not None and len(best_ts):
        entry["temporal"] = {
            "timestamp_column": best_col,
            "first": best_ts.min().isoformat(),
            "last": best_ts.max().isoformat(),
            "distinct_days": int(best_ts.dt.date.nunique()),
            **summarize_hours(Counter(best_ts.dt.hour)),
        }
    return entry


# --------------------------------------------------------------------------
# JSON payload traversal
# --------------------------------------------------------------------------

def walk_payload(obj, stats, depth=0):
    if depth > 8 or stats["records"] > stats["record_cap"]:
        return
    if isinstance(obj, dict):
        ts = None
        for key, val in obj.items():
            stats["fields"][str(key)] += 1
            if (ts is None and isinstance(val, (int, float))
                    and not isinstance(val, bool)
                    and EPOCH_MS_MIN <= val <= EPOCH_MS_MAX):
                ts = int(val)
        if ts is not None:
            stats["records"] += 1
            stats["timestamps"].append(ts)
        for val in obj.values():
            if isinstance(val, (dict, list)):
                walk_payload(val, stats, depth + 1)
    elif isinstance(obj, list):
        for val in obj:
            if isinstance(val, (dict, list)):
                walk_payload(val, stats, depth + 1)


def sample_evenly(items, k):
    if len(items) <= k:
        return items
    step = len(items) / k
    return [items[int(i * step)] for i in range(k)]


def inventory_json_streams(root, files_per_stream, record_cap, max_mb):
    jsons_root = os.path.join(root, "jsons")
    if not os.path.isdir(jsons_root):
        return []

    by_stream = defaultdict(list)
    for dirpath, _, filenames in os.walk(jsons_root):
        for fn in filenames:
            if not fn.lower().endswith(".json"):
                continue
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, jsons_root)
            by_stream[rel.split(os.sep)[0]].append(full)

    results = []
    for stream in sorted(by_stream):
        paths = sorted(by_stream[stream])
        chosen = sample_evenly(paths, files_per_stream)
        stats = {"records": 0, "timestamps": [], "fields": Counter(),
                 "record_cap": record_cap}
        read_ok, errors, suffixes = 0, Counter(), Counter()

        for path in chosen:
            base = os.path.basename(path)
            suffixes[base.split(".", 1)[1] if "." in base else base] += 1
            try:
                if os.path.getsize(path) > max_mb * 1024 * 1024:
                    errors["oversize_skipped"] += 1
                    continue
                with open(path, "r", encoding="utf-8", errors="replace") as fh:
                    payload = json.load(fh)
            except Exception as exc:
                errors[type(exc).__name__] += 1
                continue
            read_ok += 1
            walk_payload(payload, stats)

        entry = {
            "stream": stream,
            "files_total": len(paths),
            "files_sampled": len(chosen),
            "files_read": read_ok,
            "payload_suffixes": sorted(suffixes),
            "records_in_sample": stats["records"],
            "fields": [f for f, _ in stats["fields"].most_common(40)],
            "identifier_fields": flag_identifiers(stats["fields"]),
            "errors": dict(errors),
        }
        if read_ok:
            entry["est_records_total"] = int(
                round(stats["records"] / read_ok * len(paths)))
        if stats["timestamps"]:
            ts = pd.to_datetime(pd.Series(stats["timestamps"]), unit="ms",
                                utc=True).dt.tz_convert(LOCAL_TZ)
            entry["temporal"] = {
                "first": ts.min().isoformat(),
                "last": ts.max().isoformat(),
                "distinct_days": int(ts.dt.date.nunique()),
                **summarize_hours(Counter(ts.dt.hour)),
            }
        results.append(entry)
    return results


# --------------------------------------------------------------------------

def build_report(m):
    L = [
        "SAMSUNG HEALTH EXPORT INVENTORY  (v1.2)",
        f"generated {m['generated']}",
        f"root      {m['root']}",
        f"timezone  {m['local_timezone']}",
        f"{m['csv_count']} CSV files, {m['json_count']} JSON payloads",
        "",
        "Contains no measurement values -- field names and counts only.",
        "Gating is measured from record timestamps, not from device settings.",
        "CSV index timestamps are often record bookkeeping times; trust the",
        "JSON payload gating over the CSV gating where the two disagree.",
        "",
        "=" * 78,
        "CSV LAYOUT SUMMARY  (header vs data field counts)",
        "=" * 78,
        "",
        f"{'file':58s} {'hdr':>4s} {'data':>5s}  trailing",
    ]
    for e in sorted(m["csv_files"], key=lambda x: x["path"]):
        lay = e.get("layout") or {}
        L.append(f"{os.path.basename(e['path'])[:58]:58s} "
                 f"{lay.get('header_fields', '?'):>4} "
                 f"{lay.get('data_fields', '?'):>5}  "
                 f"{'YES' if lay.get('trailing_delimiter') else 'no'}")
    n_trail = sum(1 for e in m["csv_files"]
                  if (e.get("layout") or {}).get("trailing_delimiter"))
    L.append("")
    L.append(f"{n_trail} of {len(m['csv_files'])} CSV files carry a trailing "
             f"delimiter (data fields = header fields + 1).")

    L += ["", "=" * 78,
          "JSON PAYLOAD STREAMS  (sorted by estimated record count)",
          "=" * 78]
    for s in sorted(m["json_streams"], key=lambda x: -x.get("est_records_total", 0)):
        L.append("")
        L.append(s["stream"])
        L.append(f"    files {s['files_total']:,}  "
                 f"(sampled {s['files_sampled']}, read {s['files_read']})")
        if "est_records_total" in s:
            L.append(f"    records ~{s['est_records_total']:,} estimated  "
                     f"({s['records_in_sample']:,} in sample)")
        t = s.get("temporal")
        if t:
            L.append(f"    span {t['first'][:16]} -> {t['last'][:16]}  "
                     f"({t['distinct_days']} days)")
            L.append(f"    day 08-20: {t['daytime_0800_2000']:,}   "
                     f"night 20-08: {t['overnight_2000_0800']:,}   "
                     f"({t.get('daytime_share', 0):.1%} daytime)")
            L.append(f"    => {t.get('gating', 'unknown').upper()}")
        else:
            L.append("    => NO TIMESTAMPS FOUND (reference or config data)")
        L.append(f"    fields: {', '.join(s['fields'][:18])}")
        if s["identifier_fields"]:
            L.append(f"    ** IDENTIFIER FIELDS: {', '.join(s['identifier_fields'])}")
        if s["errors"]:
            L.append(f"    errors: {s['errors']}")

    L += ["", "=" * 78, "CSV STREAMS  (sorted by row count)", "=" * 78]
    for e in sorted([x for x in m["csv_files"] if x.get("rows")],
                    key=lambda x: -x["rows"]):
        lay = e.get("layout") or {}
        L.append("")
        L.append(e["path"])
        L.append(f"    {e['rows']:,} rows  (sep={e['delimiter']!r}, "
                 f"header row {e['header_skiprows']}, "
                 f"{len(e['columns'])} columns, "
                 f"trailing delimiter: "
                 f"{'YES' if lay.get('trailing_delimiter') else 'no'})")
        t = e.get("temporal")
        if t:
            L.append(f"    timestamp column: {t['timestamp_column']}")
            L.append(f"    span {t['first'][:16]} -> {t['last'][:16]}  "
                     f"({t['distinct_days']} days)")
            L.append(f"    day 08-20: {t['daytime_0800_2000']:,}   "
                     f"night 20-08: {t['overnight_2000_0800']:,}   "
                     f"=> {t.get('gating', 'unknown').upper()}")
            if e.get("has_json_pointer"):
                L.append("    (index timestamps -- gate this stream from its "
                         "JSON payloads instead)")
        if e["identifier_fields"]:
            L.append(f"    ** IDENTIFIER COLUMNS: {', '.join(e['identifier_fields'])}")
        for name, info in e["columns"].items():
            if info["detected_type"] == "empty":
                L.append(f"    [EMPTY]       {name}")
            elif info["null_rate"] > 0.5:
                L.append(f"    [{info['null_rate']:.0%} null]  {name} "
                         f"({info['detected_type']})")
            else:
                L.append(f"    {name}  ({info['detected_type']}, "
                         f"{info['non_null']:,} non-null)")

    empty = [e for e in m["csv_files"] if e.get("readable") and not e.get("rows")]
    if empty:
        L += ["", "=" * 78, "EMPTY OR HEADER-ONLY CSVs", "=" * 78]
        L += [f"  {e['path']}" for e in empty]
    unread = [e for e in m["csv_files"] if not e.get("readable")]
    if unread:
        L += ["", "=" * 78, "UNREADABLE CSVs", "=" * 78]
        L += [f"  {e['path']}" for e in unread]
    return "\n".join(L)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root")
    ap.add_argument("--out", default="inventory")
    ap.add_argument("--files-per-stream", type=int, default=15)
    ap.add_argument("--record-cap", type=int, default=200_000)
    ap.add_argument("--max-mb", type=float, default=60.0)
    args = ap.parse_args()

    root = os.path.abspath(args.root)
    if not os.path.isdir(root):
        sys.exit(f"Not a directory: {root}")

    csvs, json_total = [], 0
    for dirpath, _, filenames in os.walk(root):
        for fn in filenames:
            low = fn.lower()
            if low.endswith((".csv", ".tsv")):
                csvs.append(os.path.join(dirpath, fn))
            elif low.endswith(".json"):
                json_total += 1

    print(f"Found {len(csvs)} CSV files and {json_total} JSON payloads under {root}")

    manifest = {
        "generated": datetime.now(timezone.utc).isoformat(),
        "root": root, "local_timezone": LOCAL_TZ,
        "csv_count": len(csvs), "json_count": json_total,
        "csv_files": [], "json_streams": [],
    }

    print("\nInventorying CSV files...")
    for i, path in enumerate(sorted(csvs), 1):
        entry = inventory_csv(path, root)
        manifest["csv_files"].append(entry)
        lay = entry.get("layout") or {}
        print(f"  [{i}/{len(csvs)}] {os.path.basename(path)[:52]:52s} "
              f"hdr={lay.get('header_fields', '?')} "
              f"data={lay.get('data_fields', '?')} "
              f"rows={entry.get('rows', 0):,}")

    print(f"\nTraversing JSON payload streams "
          f"(up to {args.files_per_stream} files per stream)...")
    manifest["json_streams"] = inventory_json_streams(
        root, args.files_per_stream, args.record_cap, args.max_mb)
    for s in manifest["json_streams"]:
        gate = s.get("temporal", {}).get("gating", "no timestamps")
        print(f"  {s['stream']:52s} {s['files_total']:6,} files  {gate}")

    n_trail = sum(1 for e in manifest["csv_files"]
                  if (e.get("layout") or {}).get("trailing_delimiter"))
    print(f"\nTrailing delimiter present in {n_trail} of {len(csvs)} CSV files.")

    with open(f"{args.out}.json", "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2)
    with open(f"{args.out}.txt", "w", encoding="utf-8") as fh:
        fh.write(build_report(manifest))
    print(f"Wrote {args.out}.json and {args.out}.txt")


if __name__ == "__main__":
    main()