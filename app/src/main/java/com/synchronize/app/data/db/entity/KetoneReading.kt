package com.synchronize.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ketone_readings")
data class KetoneReading(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val subjectId: String = "subject_001",
    val deviceTimestamp: Long,         // timestamp reported by Keto-Mojo meter
    val receiveTimestamp: Long,        // timestamp recorded by app at ingestion
    val betaHydroxybutyrate: Double,   // blood BHB in mmol/L
    val glucoseMgDl: Int?,             // paired fingerstick glucose (nullable)
    val source: String = "BLE",        // BLE or TERRA (fallback)
    val readingContext: String?,       // FASTING, PRE_LUNCH, POST_LUNCH,
    // PRE_DINNER, PRE_SLEEP
    val notes: String? = null          // optional free-text note
)