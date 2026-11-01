"""
Create a sample test database that mimics the Synchronize Android app output.
Run this to generate a test database for the export pipeline.

Usage:
    python create_test_db.py
"""

import sqlite3
import random
import math
from datetime import datetime, timezone, timedelta

DB_PATH = "test_synchronize_db"
SUBJECT_ID = "subject_001"
DAYS = 5  # generate 5 days of sample data

def ms(dt: datetime) -> int:
    """Convert datetime to epoch milliseconds."""
    return int(dt.timestamp() * 1000)

def create_tables(conn: sqlite3.Connection):
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS glucose_readings (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            subjectId TEXT, deviceTimestamp INTEGER, receiveTimestamp INTEGER,
            glucoseMgDl INTEGER, trend TEXT, trendRate REAL, transmitterId TEXT,
            displayOnly INTEGER DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS ketone_readings (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            subjectId TEXT, deviceTimestamp INTEGER, receiveTimestamp INTEGER,
            betaHydroxybutyrate REAL, glucoseMgDl INTEGER, source TEXT,
            readingContext TEXT, notes TEXT
        );
        CREATE TABLE IF NOT EXISTS biosignal_samples (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            subjectId TEXT, deviceTimestamp INTEGER, receiveTimestamp INTEGER,
            heartRateBpm INTEGER, heartRateStatus TEXT,
            hrvSdnn REAL, hrvRmssd REAL, spo2Percent REAL, spo2Status TEXT,
            skinTempCelsius REAL, skinTempDelta REAL,
            systolicMmHg INTEGER, diastolicMmHg INTEGER, bpStatus TEXT,
            ecgMv REAL, ecgStatus TEXT, stepCount INTEGER,
            activeCalories REAL, stressScore INTEGER, vo2Max REAL,
            ppgGreen INTEGER, ppgRed INTEGER, ppgInfrared INTEGER,
            exerciseType TEXT, isExerciseSession INTEGER DEFAULT 0,
            sensorType TEXT
        );
        CREATE TABLE IF NOT EXISTS sleep_sessions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            subjectId TEXT, deviceTimestamp INTEGER, receiveTimestamp INTEGER,
            sleepStartTimestamp INTEGER, sleepEndTimestamp INTEGER,
            totalDurationMinutes INTEGER,
            deepSleepMinutes INTEGER, remSleepMinutes INTEGER,
            lightSleepMinutes INTEGER, awakeDuringSleeepMinutes INTEGER,
            deepSleepPercent REAL, remSleepPercent REAL,
            lightSleepPercent REAL, awakePercent REAL,
            meanOvernightRmssd REAL, meanOvernightSdnn REAL,
            meanOvernightHeartRate INTEGER, meanSkinTempCelsius REAL,
            skinTempDeltaCelsius REAL, sleepScore INTEGER, sleepEfficiency REAL
        );
        CREATE TABLE IF NOT EXISTS meal_entries (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            subjectId TEXT, deviceTimestamp INTEGER, receiveTimestamp INTEGER,
            calories INTEGER, fatGrams REAL, proteinGrams REAL,
            netCarbGrams REAL, fiberGrams REAL, totalCarbGrams REAL,
            ketogenicRatio REAL, caffeineMg REAL, meditationMinutes INTEGER,
            meditationSession TEXT, rawTranscription TEXT, parsingMethod TEXT,
            confirmed INTEGER DEFAULT 1, mealLabel TEXT, notes TEXT
        );
    """)
    conn.commit()

def generate_glucose(conn: sqlite3.Connection, start: datetime):
    """Generate 5-minute CGM readings for DAYS days."""
    rows = []
    trends = ['FLAT', 'SINGLE_UP', 'FORTY_FIVE_UP', 'FORTY_FIVE_DOWN', 'SINGLE_DOWN']
    t = start
    glucose = 95.0
    for _ in range(DAYS * 24 * 12):  # 12 readings/hour × 24h × DAYS
        h = t.hour
        # Simulate meal spikes
        if h in [8, 9]:   glucose += random.uniform(0, 3)
        elif h in [12, 13]: glucose += random.uniform(0, 4)
        elif h in [18, 19]: glucose += random.uniform(0, 3)
        else:              glucose -= random.uniform(0, 1)
        glucose = max(75, min(165, glucose + random.gauss(0, 1.5)))
        rate = random.gauss(0, 0.3)
        rows.append((
            SUBJECT_ID, ms(t), ms(t) + random.randint(100, 500),
            int(glucose), random.choice(trends), round(rate, 2),
            "G7-TX001", 0
        ))
        t += timedelta(minutes=5)
    conn.executemany(
        "INSERT INTO glucose_readings VALUES (NULL,?,?,?,?,?,?,?,?)", rows)
    conn.commit()
    print(f"  Generated {len(rows)} glucose readings")

def generate_ketones(conn: sqlite3.Connection, start: datetime):
    """Generate 4-5 ketone readings per day."""
    rows = []
    contexts = ['FASTING', 'PRE_LUNCH', 'POST_LUNCH', 'PRE_DINNER', 'PRE_SLEEP']
    times    = [7, 11, 13, 17, 21]  # hours
    for day in range(DAYS):
        base_bhb = random.uniform(0.8, 2.2)
        for ctx, hour in zip(contexts, times):
            t = start + timedelta(days=day, hours=hour,
                                  minutes=random.randint(0, 30))
            bhb = max(0.3, base_bhb + random.gauss(0, 0.3))
            glucose = int(random.uniform(80, 130))
            rows.append((
                SUBJECT_ID, ms(t), ms(t) + random.randint(100, 2000),
                round(bhb, 2), glucose, "BLE", ctx, None
            ))
    conn.executemany(
        "INSERT INTO ketone_readings VALUES (NULL,?,?,?,?,?,?,?,?)", rows)
    conn.commit()
    print(f"  Generated {len(rows)} ketone readings")

def generate_biosignals(conn: sqlite3.Connection, start: datetime):
    """Generate biosignal samples every 5 minutes."""
    rows = []
    t = start
    hr = 62.0
    for _ in range(DAYS * 24 * 12):
        h = t.hour
        exercise = (h == 9 and 0 <= t.minute <= 45)
        sleeping = h < 6 or h >= 22
        if exercise:   hr = min(165, hr + random.uniform(2, 8))
        elif sleeping: hr = max(48, hr - random.uniform(0, 1))
        else:          hr += random.gauss(0, 1.5)
        hr = max(48, min(175, hr))
        hrv = max(15, random.gauss(55 if sleeping else 38, 8))
        spo2 = min(99.5, max(95, random.gauss(98.2, 0.4)))
        skin_temp = random.gauss(33.5 if sleeping else 32.8, 0.3)
        rows.append((
            SUBJECT_ID, ms(t), ms(t) + random.randint(100, 500),
            int(hr), "COMPLETE",
            round(random.gauss(55, 10), 1), round(hrv, 1),
            round(spo2, 1), "COMPLETE",
            round(skin_temp, 2), round(random.gauss(0, 0.15), 2),
            int(random.gauss(118, 6)), int(random.gauss(76, 4)), "COMPLETE",
            None, None,
            int(_ * 8.3 + random.randint(0, 5)),
            round(random.uniform(0, 8), 1),
            int(random.gauss(35, 15)),
            round(random.uniform(38, 42), 1),
            None, None, None,
            "RUNNING" if exercise else None,
            1 if exercise else 0,
            "HEALTH_CONNECT"
        ))
        t += timedelta(minutes=5)
    conn.executemany("""
        INSERT INTO biosignal_samples VALUES
        (NULL,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
    """, rows)
    conn.commit()
    print(f"  Generated {len(rows)} biosignal samples")

def generate_sleep(conn: sqlite3.Connection, start: datetime):
    """Generate one sleep session per night."""
    rows = []
    for day in range(DAYS):
        sleep_start = start + timedelta(days=day, hours=22,
                                        minutes=random.randint(0, 45))
        sleep_end   = sleep_start + timedelta(hours=random.uniform(6.5, 8.5))
        total = int((sleep_end - sleep_start).total_seconds() / 60)
        deep  = int(total * random.uniform(0.15, 0.25))
        rem   = int(total * random.uniform(0.20, 0.25))
        light = int(total * random.uniform(0.45, 0.55))
        awake = total - deep - rem - light
        rows.append((
            SUBJECT_ID, ms(sleep_start), ms(sleep_start) + 1000,
            ms(sleep_start), ms(sleep_end), total,
            deep, rem, light, max(0, awake),
            round(deep/total*100, 1), round(rem/total*100, 1),
            round(light/total*100, 1), round(max(0,awake)/total*100, 1),
            round(random.gauss(58, 8), 1), round(random.gauss(68, 10), 1),
            int(random.gauss(56, 4)),
            round(random.gauss(33.2, 0.3), 2),
            round(random.gauss(-0.4, 0.2), 2),
            int(random.gauss(78, 8)),
            round((total - max(0, awake)) / total * 100, 1)
        ))
    conn.executemany("""
        INSERT INTO sleep_sessions VALUES
        (NULL,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
    """, rows)
    conn.commit()
    print(f"  Generated {len(rows)} sleep sessions")

def generate_meals(conn: sqlite3.Connection, start: datetime):
    """Generate 3 meal entries per day."""
    rows = []
    meals = [
        ("BREAKFAST", 8,  660, 54, 39, 2,  15, "660 54 39 2 15"),
        ("LUNCH",     12, 520, 42, 23, 0,  32, "520 42 23 0 32"),
        ("DINNER",    18, 470, 39, 23, 2,  6,  "470 39 23 2 6"),
    ]
    for day in range(DAYS):
        for label, hour, cal, fat, prot, nc, fib, transcript in meals:
            t = start + timedelta(days=day, hours=hour,
                                  minutes=random.randint(0, 20))
            total_carb = nc + fib
            kr = fat / (nc + prot) if (nc + prot) > 0 else 0
            caffeine = 400.0 if label == "BREAKFAST" else None
            med_min  = 10 if label == "BREAKFAST" else (
                       15 if label == "DINNER" else None)
            med_sess = "AM" if label == "BREAKFAST" else (
                       "PM" if label == "DINNER" else None)
            rows.append((
                SUBJECT_ID, ms(t), ms(t) + random.randint(5000, 30000),
                cal, float(fat), float(prot), float(nc), float(fib),
                float(total_carb), round(kr, 3),
                caffeine, med_min, med_sess,
                transcript, "REGEX", 1, label, None
            ))
    conn.executemany("""
        INSERT INTO meal_entries VALUES
        (NULL,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
    """, rows)
    conn.commit()
    print(f"  Generated {len(rows)} meal entries")

# ── Main ──────────────────────────────────────────────────────

if __name__ == '__main__':
    import os
    if os.path.exists(DB_PATH):
        os.remove(DB_PATH)
        print(f"Removed existing {DB_PATH}")

    conn = sqlite3.connect(DB_PATH)
    start = datetime(2025, 7, 1, 6, 0, 0, tzinfo=timezone.utc)

    print(f"Creating test database: {DB_PATH}")
    print(f"Generating {DAYS} days of sample data starting {start.date()}...")
    print()

    create_tables(conn)
    generate_glucose(conn, start)
    generate_ketones(conn, start)
    generate_biosignals(conn, start)
    generate_sleep(conn, start)
    generate_meals(conn, start)

    conn.close()
    print(f"\nTest database created: {DB_PATH}")
    print("Run the export pipeline with:")
    print(f"  python export_pipeline.py --db {DB_PATH} --out ./test_output")