package com.synchronize.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "daily_covariates",
    indices = [Index(value = ["dateKey"], unique = true)]
)
data class DailyCovariate(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val subjectId: String = "subject_001",
    val dateKey: String,              // "yyyy-MM-dd" — one row per day
    val deviceTimestamp: Long,
    val receiveTimestamp: Long,
    val caffeineMg: Double,
    val meditationAmMinutes: Int,
    val meditationPmMinutes: Int,
    val notes: String? = null
)