package com.synchronize.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "meal_entries")
data class MealEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val subjectId: String = "subject_001",
    val deviceTimestamp: Long,           // timestamp recorded by app at meal entry
    val receiveTimestamp: Long,          // timestamp committed to database

    // Core macros (cal / fat / protein / net carbs / fiber format)
    val calories: Int,                   // total calories
    val fatGrams: Double,                // total fat in grams
    val proteinGrams: Double,            // protein in grams
    val netCarbGrams: Double,            // net carbohydrates in grams (total - fiber)
    val fiberGrams: Double,              // dietary fiber in grams
    val totalCarbGrams: Double,          // net carbs + fiber (derived)

    // Derived ketogenic ratio
    val ketogenicRatio: Double,          // fat / (net_carbs + protein)

    // Lifestyle covariates
    val caffeineMg: Double?,             // caffeine intake in mg (if logged at meal)
    val meditationMinutes: Int?,         // meditation duration in minutes (AM or PM)
    val meditationSession: String?,      // AM or PM

    // Voice logging metadata
    val rawTranscription: String?,       // raw Whisper transcription text
    val parsingMethod: String,           // REGEX or EDAMAM
    val confirmed: Boolean = false,      // true once user confirms on screen

    // Meal context
    val mealLabel: String?,              // BREAKFAST, LUNCH, DINNER, SNACK
    val notes: String? = null            // optional free-text note
)