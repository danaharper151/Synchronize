package com.synchronize.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.synchronize.app.data.db.entity.GlucoseReading
import kotlinx.coroutines.flow.Flow

@Dao
interface GlucoseReadingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reading: GlucoseReading)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(readings: List<GlucoseReading>)

    @Query("SELECT * FROM glucose_readings ORDER BY deviceTimestamp DESC")
    fun getAllReadings(): Flow<List<GlucoseReading>>

    @Query("SELECT * FROM glucose_readings WHERE deviceTimestamp >= :startTime AND deviceTimestamp <= :endTime ORDER BY deviceTimestamp ASC")
    fun getReadingsInRange(startTime: Long, endTime: Long): Flow<List<GlucoseReading>>

    @Query("SELECT * FROM glucose_readings ORDER BY deviceTimestamp DESC LIMIT 1")
    suspend fun getLatestReading(): GlucoseReading?

    @Query("SELECT MAX(deviceTimestamp) FROM glucose_readings")
    suspend fun getLatestTimestamp(): Long?

    @Query("SELECT COUNT(*) FROM glucose_readings")
    suspend fun getCount(): Int

    @Query("DELETE FROM glucose_readings")
    suspend fun deleteAll()
}