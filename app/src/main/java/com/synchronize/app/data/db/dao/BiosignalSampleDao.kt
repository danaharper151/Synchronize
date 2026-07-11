package com.synchronize.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.synchronize.app.data.db.entity.BiosignalSample
import kotlinx.coroutines.flow.Flow

@Dao
interface BiosignalSampleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sample: BiosignalSample)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(samples: List<BiosignalSample>)

    @Query("SELECT * FROM biosignal_samples ORDER BY deviceTimestamp DESC")
    fun getAllSamples(): Flow<List<BiosignalSample>>

    @Query("SELECT * FROM biosignal_samples WHERE deviceTimestamp >= :startTime AND deviceTimestamp <= :endTime ORDER BY deviceTimestamp ASC")
    fun getSamplesInRange(startTime: Long, endTime: Long): Flow<List<BiosignalSample>>

    @Query("SELECT * FROM biosignal_samples ORDER BY deviceTimestamp DESC LIMIT 1")
    suspend fun getLatestSample(): BiosignalSample?

    @Query("SELECT MAX(deviceTimestamp) FROM biosignal_samples")
    suspend fun getLatestTimestamp(): Long?

    @Query("SELECT AVG(heartRateBpm) FROM biosignal_samples WHERE deviceTimestamp >= :startTime AND deviceTimestamp <= :endTime")
    suspend fun getAverageHeartRate(startTime: Long, endTime: Long): Double?

    @Query("SELECT AVG(hrvRmssd) FROM biosignal_samples WHERE deviceTimestamp >= :startTime AND deviceTimestamp <= :endTime")
    suspend fun getAverageRmssd(startTime: Long, endTime: Long): Double?

    @Query("SELECT AVG(spo2Percent) FROM biosignal_samples WHERE deviceTimestamp >= :startTime AND deviceTimestamp <= :endTime")
    suspend fun getAverageSpo2(startTime: Long, endTime: Long): Double?

    @Query("SELECT * FROM biosignal_samples WHERE isExerciseSession = 1 ORDER BY deviceTimestamp DESC")
    fun getExerciseSessions(): Flow<List<BiosignalSample>>

    @Query("SELECT COUNT(*) FROM biosignal_samples")
    suspend fun getCount(): Int

    @Query("DELETE FROM biosignal_samples")
    suspend fun deleteAll()
}