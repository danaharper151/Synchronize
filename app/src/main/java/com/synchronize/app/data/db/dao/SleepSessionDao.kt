package com.synchronize.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.synchronize.app.data.db.entity.SleepSession
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SleepSession)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sessions: List<SleepSession>)

    @Query("SELECT * FROM sleep_sessions ORDER BY sleepStartTimestamp DESC")
    fun getAllSessions(): Flow<List<SleepSession>>

    @Query("SELECT * FROM sleep_sessions WHERE sleepStartTimestamp >= :startTime AND sleepEndTimestamp <= :endTime ORDER BY sleepStartTimestamp ASC")
    fun getSessionsInRange(startTime: Long, endTime: Long): Flow<List<SleepSession>>

    @Query("SELECT * FROM sleep_sessions ORDER BY sleepStartTimestamp DESC LIMIT 1")
    suspend fun getLatestSession(): SleepSession?

    @Query("SELECT * FROM sleep_sessions WHERE sleepEndTimestamp >= :wakeTime ORDER BY sleepEndTimestamp ASC LIMIT 1")
    suspend fun getSessionBeforeWake(wakeTime: Long): SleepSession?

    @Query("SELECT AVG(deepSleepPercent) FROM sleep_sessions WHERE sleepStartTimestamp >= :startTime")
    suspend fun getAverageDeepSleepPercentSince(startTime: Long): Double?

    @Query("SELECT AVG(meanOvernightRmssd) FROM sleep_sessions WHERE sleepStartTimestamp >= :startTime")
    suspend fun getAverageOvernightRmssdSince(startTime: Long): Double?

    @Query("SELECT COUNT(*) FROM sleep_sessions")
    suspend fun getCount(): Int

    @Query("DELETE FROM sleep_sessions")
    suspend fun deleteAll()
}