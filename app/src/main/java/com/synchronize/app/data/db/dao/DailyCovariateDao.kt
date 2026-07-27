package com.synchronize.app.data.db.dao

import androidx.room.*
import com.synchronize.app.data.db.entity.DailyCovariate
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyCovariateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(covariate: DailyCovariate): Long

    @Update
    suspend fun update(covariate: DailyCovariate)

    @Query("SELECT * FROM daily_covariates ORDER BY dateKey DESC")
    fun getAll(): Flow<List<DailyCovariate>>

    @Query("SELECT * FROM daily_covariates WHERE dateKey = :dateKey LIMIT 1")
    suspend fun getForDate(dateKey: String): DailyCovariate?

    @Query("SELECT COUNT(*) FROM daily_covariates")
    suspend fun getCount(): Int

    @Query("DELETE FROM daily_covariates WHERE id = :id")
    suspend fun deleteById(id: Long)
}