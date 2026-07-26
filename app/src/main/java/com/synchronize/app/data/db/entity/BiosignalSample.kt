package com.synchronize.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "biosignal_samples",
    indices = [Index(value = ["deviceTimestamp"], unique = true)]
)
data class BiosignalSample(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val subjectId: String = "subject_001",
    val deviceTimestamp: Long,          // timestamp reported by Galaxy Watch
    val receiveTimestamp: Long,         // timestamp recorded by app at ingestion

    // Heart rate
    val heartRateBpm: Int?,             // instantaneous HR in bpm
    val heartRateStatus: String?,       // INITIAL, UPDATING, COMPLETE, NO_CONTACT

    // Heart rate variability
    val hrvSdnn: Double?,               // SDNN in milliseconds
    val hrvRmssd: Double?,              // RMSSD in milliseconds

    // SpO2
    val spo2Percent: Double?,           // blood oxygen saturation %
    val spo2Status: String?,            // INITIAL, UPDATING, COMPLETE, NO_CONTACT

    // Skin temperature
    val skinTempCelsius: Double?,       // skin surface temperature in °C
    val skinTempDelta: Double?,         // delta from baseline °C

    // Blood pressure (watch-derived, calibration-dependent)
    val systolicMmHg: Int?,             // systolic BP
    val diastolicMmHg: Int?,            // diastolic BP
    val bpStatus: String?,              // INITIAL, UPDATING, COMPLETE

    // ECG
    val ecgMv: Double?,                 // ECG signal in millivolts
    val ecgStatus: String?,             // INITIAL, UPDATING, COMPLETE, NO_CONTACT

    // Activity
    val stepCount: Int?,                // cumulative step count
    val activeCalories: Double?,        // active calories burned
    val stressScore: Int?,              // Samsung stress score 0-100
    val vo2Max: Double?,                // VO2 max estimate ml/kg/min

    // PPG (raw, collected during exercise)
    val ppgGreen: Int?,                 // PPG green LED value
    val ppgRed: Int?,                   // PPG red LED value
    val ppgInfrared: Int?,              // PPG infrared LED value

    // Exercise
    val exerciseType: String?,          // RUNNING, WALKING, CYCLING, etc.
    val isExerciseSession: Boolean = false,

    // Source
    val sensorType: String              // CONTINUOUS, ON_DEMAND, HEALTH_CONNECT
)