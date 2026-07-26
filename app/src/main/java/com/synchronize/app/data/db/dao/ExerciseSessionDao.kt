package com.synchronize.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.synchronize.app.data.db.entity.ExerciseSession
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: ExerciseSession): Long

    @Update
    suspend fun update(session: ExerciseSession)

    @Query("SELECT * FROM exercise_sessions ORDER BY startTimestamp DESC")
    fun getAllSessions(): Flow<List<ExerciseSession>>

    @Query("SELECT * FROM exercise_sessions WHERE startTimestamp >= :startTime AND startTimestamp <= :endTime ORDER BY startTimestamp ASC")
    fun getSessionsInRange(startTime: Long, endTime: Long): Flow<List<ExerciseSession>>

    @Query("SELECT * FROM exercise_sessions ORDER BY startTimestamp DESC LIMIT 1")
    suspend fun getLatestSession(): ExerciseSession?

    @Query("SELECT * FROM exercise_sessions WHERE startTimestamp >= :dayStart AND startTimestamp < :dayEnd LIMIT 1")
    suspend fun getSessionForDay(dayStart: Long, dayEnd: Long): ExerciseSession?

    @Query("SELECT COUNT(*) FROM exercise_sessions WHERE isHiit = 1")
    suspend fun getHiitCount(): Int

    @Query("SELECT COUNT(*) FROM exercise_sessions")
    suspend fun getCount(): Int

    @Query("DELETE FROM exercise_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM exercise_sessions")
    suspend fun deleteAll()
}