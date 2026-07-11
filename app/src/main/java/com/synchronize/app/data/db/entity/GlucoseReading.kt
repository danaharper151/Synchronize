package com.synchronize.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "glucose_readings")
data class GlucoseReading(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val subjectId: String = "subject_001",
    val deviceTimestamp: Long,        // timestamp reported by Dexcom sensor
    val receiveTimestamp: Long,       // timestamp recorded by app at ingestion
    val glucoseMgDl: Int,             // glucose value in mg/dL
    val trend: String,                // NONE, DOUBLE_UP, SINGLE_UP, FORTY_FIVE_UP,
                                      // FLAT, FORTY_FIVE_DOWN, SINGLE_DOWN, DOUBLE_DOWN
    val trendRate: Double?,           // rate of change mg/dL per minute (nullable)
    val transmitterId: String?,       // Dexcom transmitter ID
    val displayOnly: Boolean = false  // true if reading is display-only (warmup period)
)