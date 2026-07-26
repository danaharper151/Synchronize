package com.synchronize.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "exercise_sessions",
    indices = [Index(value = ["startTimestamp"], unique = true)]
)
data class ExerciseSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val subjectId: String = "subject_001",
    val deviceTimestamp: Long,        // when the entry was logged
    val receiveTimestamp: Long,       // when committed to database
    val startTimestamp: Long,         // when the session began
    val durationMinutes: Int,         // session length
    val sessionType: String,          // HIIT, CORE_STRETCH, REST, OTHER
    val isHiit: Boolean,              // derived binary flag for the model
    val notes: String? = null
) {
    companion object {
        const val TYPE_HIIT         = "HIIT"
        const val TYPE_CORE_STRETCH = "CORE_STRETCH"
        const val TYPE_REST         = "REST"
        const val TYPE_OTHER        = "OTHER"

        val ALL_TYPES = listOf(TYPE_HIIT, TYPE_CORE_STRETCH, TYPE_REST, TYPE_OTHER)

        fun displayName(type: String) = when (type) {
            TYPE_HIIT         -> "HIIT + Core"
            TYPE_CORE_STRETCH -> "Core + Stretch"
            TYPE_REST         -> "Rest Day"
            else              -> "Other"
        }
    }
}
