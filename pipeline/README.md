# Synchronize analysis pipeline

Python tooling that turns a Synchronize app database and three vendor exports
into the feature matrices, models, and results reported in the thesis
*Predicting Blood Ketone Levels from Consumer Wearable Biosignals: A Feature
Ablation Study* (CSUCI, 2026).

Most of this code exists because the vendor exports are undocumented and their
defects fail silently: files parse without error, have the expected shape, and
hold values under the wrong names or at the wrong hour. Every stage is paired
with a diagnostic that checks its output against what the instrument could
plausibly have produced. Those diagnostics are kept here, not deleted, because
they are how each defect below was found.

No study data is included. The scripts run on your own exports.

---

## Layout

```
pipeline/
├── parse_samsung_export_v2.py   Samsung Health export → clean per-stream CSVs
├── build_features_v2.py         app DB + Samsung + Dexcom → feature matrix and model subsets
├── train_models.py              Random Forest / XGBoost across the four-model ablation
├── export_pipeline.py           de-identified CSV bundle from the app database
├── export_dashboard_json.py     read-only JSON feed for the companion dashboard
├── create_test_db.py            synthetic five-day app database for testing
├── diagnostics/                 parsing and data-quality verification
├── analysis/                    post-hoc analyses reported in the thesis
└── results/                     aggregate results from the thesis run (no readings)
```

`v2` marks the parser and feature builder that produced the thesis results.
They superseded earlier versions that lacked the timezone and alignment fixes
described below.

---

## Export defects handled

None of these raises an error.

| Source | Defect | Handling |
|---|---|---|
| Samsung Health | **Trailing delimiter.** Every data row has one more field than the header, so pandas promotes the first column to the index and shifts every label one position left of its data. | All reads use `index_col=False`. |
| Samsung Health | **Variable preamble.** Some files carry one metadata line before the header, some two. | Header located by field count, not assumed. |
| Samsung Health | **Header/data dissociation.** Even after the two fixes above, some columns hold data their names do not describe. | Timestamp and pointer columns located by content. |
| Samsung Health | **Mixed time conventions.** Tables in the same export disagree: `exercise` stores UTC, `step_count` stores local wall time. Binned JSON payloads carry epoch milliseconds in UTC. | Each table's own `time_offset` column decides; display times converted to local. |
| Health Connect (app DB) | **Forward-filled SpO₂.** The column looks densely populated, but the values are carried forward, not measured. | SpO₂ features come from the Samsung export instead. |
| Samsung Health | **Sentinel zeros.** Respiratory rate and stress use `0` for "no valid measurement"; most overnight stress samples are unscorable. | Zeros nulled for those streams before feature construction. |
| Dexcom Clarity | The *Rate of Change* column is blank on glucose rows in practice. | Rate of change computed from consecutive readings. |
| Dexcom G7 | Readings below 55 mg/dL cluster in the first hours of each sensor session. | First 12 hours of every session excluded from CGM features. |

The reasoning for each is in the docstring at the top of
`parse_samsung_export_v2.py` and `build_features_v2.py`.

---

## Workflow

```bash
cd pipeline
python -m venv .venv && source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

1. **Extract the app database** from the phone (see the root README, *Extracting the data*).
2. **Export** Samsung Health (in-app data download), Dexcom Clarity (CSV), and Keto-Mojo (CSV).
3. **Parse Samsung:**
   ```bash
   python parse_samsung_export_v2.py --export <path/to/samsunghealth_export> --out samsung_parsed
   ```
   Add `--discover-only` first to see every stream in the export without writing anything.
4. **Build features:**
   ```bash
   python build_features_v2.py --db <path/to/synchronize_db> --samsung samsung_parsed \
                               --dexcom <path/to/clarity_export.csv> --out features
   ```
   Writes `feature_matrix.csv`, one subset per model
   (`model_A_cgm_watch_macros.csv` … `model_D_watch_only.csv`), and the two
   sensitivity variants. Absent sensor features are excluded at this stage, not
   at training time, so each subset holds only what that model may use.
5. **Train and evaluate:**
   ```bash
   python train_models.py --features features
   ```
   Leave-one-day-out cross-validation is primary. Readings from one day share
   sleep vectors, exercise flags, and covariates, so leave-one-out would leak.
   LOOCV is reported only for comparison with prior work.
6. **Analyses** (each reads the `features` folder): `analysis/clarke_error_grid.py`,
   `analysis/check_glucose_direction.py`, `analysis/posthoc_context_sleep.py`.

Every script takes `--help`. Diagnostics and analyses are run from `pipeline/`,
for example `python diagnostics/inspect_coverage.py --help`.

### Diagnostics

| Script | Question it answers |
|---|---|
| `inventory_samsung_export.py` | What streams and fields does this export contain, and which are sleep-gated? Emits structure only, no values. |
| `inventory_exports.py` | What data files are present, in what format, over what date range? |
| `debug_parser.py` | Do a stream's CSV pointers match the JSON files on disk? |
| `diagnose_alignment.py` | Are timestamps offset between sources, and what columns do the streams really have? |
| `diag_join.py` | Why did a session-level join come back empty? |
| `inspect_coverage.py` | When does each biosignal stream actually record, hour by hour? |
| `inspect_mojo_crosscheck.py` | Do manually entered ketone values match the meter's own export? |
| `list_mojo_readings.py` | Every meter reading by day, for comparison with the on-device history. |
| `audit_stability.py` | Which labels came from sessions where replicate readings disagreed? |
| `dump_schema.py` | Every table, column, and a sample row of an app database. |

---

## Adapting it to another study

- **Timezone.** Set `SYNCHRONIZE_TZ` (an IANA name such as `America/New_York`)
  before running. The default is `America/Los_Angeles`, where this study ran.
- **Study window and protocol constants** live at the top of
  `build_features_v2.py` (training window, window lengths, warm-up exclusion,
  thresholds). Several are dated, pre-registered decisions for this study; the
  docstring records why each was set. Change them for a new study and record
  the change.
- **Section references** (§3.x) in script docstrings follow the thesis
  proposal's numbering, which became Chapter 4 in the final thesis.

## Testing without real data

```bash
python create_test_db.py
python export_pipeline.py --db test_synchronize_db --out test_output
python diagnostics/dump_schema.py test_synchronize_db
```

The synthetic database exercises the app-database readers and the export.
It has no Samsung or Dexcom exports and falls outside the study's training
window, so it cannot drive `build_features_v2.py` end to end.

## Results

`results/` holds the aggregate outputs of the thesis run: the performance
matrix, ablation tests, feature importances, glucose-direction statistics, and
the run configuration (seed 42, 140 labels over 30 days). No individual
readings are included.
