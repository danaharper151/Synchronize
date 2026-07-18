package com.synchronize.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.synchronize.app.data.db.entity.MealEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface MealEntryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: MealEntry): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<MealEntry>)

    @Update
    suspend fun update(entry: MealEntry)

    @Query("SELECT * FROM meal_entries ORDER BY deviceTimestamp DESC")
    fun getAllEntries(): Flow<List<MealEntry>>

    @Query("SELECT * FROM meal_entries WHERE deviceTimestamp >= :startTime AND deviceTimestamp <= :endTime ORDER BY deviceTimestamp ASC")
    fun getEntriesInRange(startTime: Long, endTime: Long): Flow<List<MealEntry>>

    @Query("SELECT * FROM meal_entries ORDER BY deviceTimestamp DESC LIMIT 1")
    suspend fun getLatestEntry(): MealEntry?

    @Query("SELECT * FROM meal_entries WHERE confirmed = 0 ORDER BY deviceTimestamp DESC")
    fun getUnconfirmedEntries(): Flow<List<MealEntry>>

    @Query("UPDATE meal_entries SET confirmed = 1 WHERE id = :id")
    suspend fun confirmEntry(id: Long)

    @Query("SELECT * FROM meal_entries WHERE mealLabel = :label ORDER BY deviceTimestamp DESC")
    fun getEntriesByLabel(label: String): Flow<List<MealEntry>>

    @Query("SELECT SUM(netCarbGrams) FROM meal_entries WHERE deviceTimestamp >= :startTime AND deviceTimestamp <= :endTime")
    suspend fun getTotalNetCarbsInRange(startTime: Long, endTime: Long): Double?

    @Query("SELECT SUM(fatGrams) FROM meal_entries WHERE deviceTimestamp >= :startTime AND deviceTimestamp <= :endTime")
    suspend fun getTotalFatInRange(startTime: Long, endTime: Long): Double?

    @Query("SELECT SUM(proteinGrams) FROM meal_entries WHERE deviceTimestamp >= :startTime AND deviceTimestamp <= :endTime")
    suspend fun getTotalProteinInRange(startTime: Long, endTime: Long): Double?

    @Query("SELECT AVG(ketogenicRatio) FROM meal_entries WHERE deviceTimestamp >= :startTime")
    suspend fun getAverageKetogenicRatioSince(startTime: Long): Double?

    @Query("SELECT COUNT(*) FROM meal_entries")
    suspend fun getCount(): Int

    @Query("DELETE FROM meal_entries")
    suspend fun deleteAll()

    @Query("DELETE FROM meal_entries WHERE id = :id")
    suspend fun deleteById(id: Long)
}