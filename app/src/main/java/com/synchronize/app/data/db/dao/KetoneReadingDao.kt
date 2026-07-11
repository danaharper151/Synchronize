package com.synchronize.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.synchronize.app.data.db.entity.KetoneReading
import kotlinx.coroutines.flow.Flow

@Dao
interface KetoneReadingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reading: KetoneReading)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(readings: List<KetoneReading>)

    @Query("SELECT * FROM ketone_readings ORDER BY deviceTimestamp DESC")
    fun getAllReadings(): Flow<List<KetoneReading>>

    @Query("SELECT * FROM ketone_readings WHERE deviceTimestamp >= :startTime AND deviceTimestamp <= :endTime ORDER BY deviceTimestamp ASC")
    fun getReadingsInRange(startTime: Long, endTime: Long): Flow<List<KetoneReading>>

    @Query("SELECT * FROM ketone_readings ORDER BY deviceTimestamp DESC LIMIT 1")
    suspend fun getLatestReading(): KetoneReading?

    @Query("SELECT * FROM ketone_readings WHERE readingContext = :context ORDER BY deviceTimestamp DESC")
    fun getReadingsByContext(context: String): Flow<List<KetoneReading>>

    @Query("SELECT COUNT(*) FROM ketone_readings")
    suspend fun getCount(): Int

    @Query("SELECT AVG(betaHydroxybutyrate) FROM ketone_readings WHERE deviceTimestamp >= :startTime")
    suspend fun getAverageBhbSince(startTime: Long): Double?

    @Query("DELETE FROM ketone_readings")
    suspend fun deleteAll()
}