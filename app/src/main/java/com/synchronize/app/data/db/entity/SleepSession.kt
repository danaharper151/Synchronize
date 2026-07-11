package com.synchronize.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sleep_sessions")
data class SleepSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val subjectId: String = "subject_001",
    val deviceTimestamp: Long,           // timestamp reported by Galaxy Watch
    val receiveTimestamp: Long,          // timestamp recorded by app at ingestion

    // Session bounds
    val sleepStartTimestamp: Long,       // when sleep began
    val sleepEndTimestamp: Long,         // when sleep ended
    val totalDurationMinutes: Int,       // total time in bed in minutes

    // Sleep stage durations (in minutes)
    val deepSleepMinutes: Int?,          // N3 slow-wave sleep
    val remSleepMinutes: Int?,           // REM sleep
    val lightSleepMinutes: Int?,         // N1 + N2 light sleep
    val awakeDuringSleeepMinutes: Int?,  // awake periods during sleep window

    // Sleep stage percentages (derived)
    val deepSleepPercent: Double?,       // % of total sleep in deep
    val remSleepPercent: Double?,        // % of total sleep in REM
    val lightSleepPercent: Double?,      // % of total sleep in light
    val awakePercent: Double?,           // % of total sleep awake

    // Overnight biosignals (averages across sleep window)
    val meanOvernightRmssd: Double?,     // mean HRV RMSSD during sleep
    val meanOvernightSdnn: Double?,      // mean HRV SDNN during sleep
    val meanOvernightHeartRate: Int?,    // mean HR during sleep
    val meanSkinTempCelsius: Double?,    // mean skin temperature during sleep
    val skinTempDeltaCelsius: Double?,   // delta from sleep onset to wake

    // Sleep quality
    val sleepScore: Int?,               // Samsung sleep score 0-100
    val sleepEfficiency: Double?        // time asleep / time in bed * 100
)