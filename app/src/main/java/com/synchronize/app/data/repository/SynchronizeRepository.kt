package com.synchronize.app.data.repository

import com.synchronize.app.data.db.SynchronizeDatabase
import com.synchronize.app.data.db.entity.BiosignalSample
import com.synchronize.app.data.db.entity.GlucoseReading
import com.synchronize.app.data.db.entity.KetoneReading
import com.synchronize.app.data.db.entity.MealEntry
import com.synchronize.app.data.db.entity.SleepSession
import kotlinx.coroutines.flow.Flow

class SynchronizeRepository(private val db: SynchronizeDatabase) {

    // ── Glucose ───────────────────────────────────────────────

    suspend fun insertGlucoseReading(reading: GlucoseReading) =
        db.glucoseReadingDao().insert(reading)

    suspend fun insertGlucoseReadings(readings: List<GlucoseReading>) =
        db.glucoseReadingDao().insertAll(readings)

    fun getAllGlucoseReadings(): Flow<List<GlucoseReading>> =
        db.glucoseReadingDao().getAllReadings()

    fun getGlucoseReadingsInRange(startTime: Long, endTime: Long): Flow<List<GlucoseReading>> =
        db.glucoseReadingDao().getReadingsInRange(startTime, endTime)

    suspend fun getLatestGlucoseReading(): GlucoseReading? =
        db.glucoseReadingDao().getLatestReading()

    suspend fun getLatestGlucoseTimestamp(): Long? =
        db.glucoseReadingDao().getLatestTimestamp()

    suspend fun getGlucoseCount(): Int =
        db.glucoseReadingDao().getCount()

    // ── Ketones ───────────────────────────────────────────────

    suspend fun insertKetoneReading(reading: KetoneReading) =
        db.ketoneReadingDao().insert(reading)

    fun getAllKetoneReadings(): Flow<List<KetoneReading>> =
        db.ketoneReadingDao().getAllReadings()

    fun getKetoneReadingsInRange(startTime: Long, endTime: Long): Flow<List<KetoneReading>> =
        db.ketoneReadingDao().getReadingsInRange(startTime, endTime)

    suspend fun getLatestKetoneReading(): KetoneReading? =
        db.ketoneReadingDao().getLatestReading()

    fun getKetoneReadingsByContext(context: String): Flow<List<KetoneReading>> =
        db.ketoneReadingDao().getReadingsByContext(context)

    suspend fun getKetoneCount(): Int =
        db.ketoneReadingDao().getCount()

    suspend fun getAverageBhbSince(startTime: Long): Double? =
        db.ketoneReadingDao().getAverageBhbSince(startTime)

    // ── Biosignals ────────────────────────────────────────────

    suspend fun insertBiosignalSample(sample: BiosignalSample) =
        db.biosignalSampleDao().insert(sample)

    suspend fun insertBiosignalSamples(samples: List<BiosignalSample>) =
        db.biosignalSampleDao().insertAll(samples)

    fun getAllBiosignalSamples(): Flow<List<BiosignalSample>> =
        db.biosignalSampleDao().getAllSamples()

    fun getBiosignalSamplesInRange(startTime: Long, endTime: Long): Flow<List<BiosignalSample>> =
        db.biosignalSampleDao().getSamplesInRange(startTime, endTime)

    suspend fun getLatestBiosignalSample(): BiosignalSample? =
        db.biosignalSampleDao().getLatestSample()

    suspend fun getLatestBiosignalTimestamp(): Long? =
        db.biosignalSampleDao().getLatestTimestamp()

    suspend fun getAverageHeartRate(startTime: Long, endTime: Long): Double? =
        db.biosignalSampleDao().getAverageHeartRate(startTime, endTime)

    suspend fun getAverageRmssd(startTime: Long, endTime: Long): Double? =
        db.biosignalSampleDao().getAverageRmssd(startTime, endTime)

    suspend fun getBiosignalCount(): Int =
        db.biosignalSampleDao().getCount()

    // ── Sleep ─────────────────────────────────────────────────

    suspend fun insertSleepSession(session: SleepSession) =
        db.sleepSessionDao().insert(session)

    fun getAllSleepSessions(): Flow<List<SleepSession>> =
        db.sleepSessionDao().getAllSessions()

    fun getSleepSessionsInRange(startTime: Long, endTime: Long): Flow<List<SleepSession>> =
        db.sleepSessionDao().getSessionsInRange(startTime, endTime)

    suspend fun getLatestSleepSession(): SleepSession? =
        db.sleepSessionDao().getLatestSession()

    suspend fun getSleepSessionBeforeWake(wakeTime: Long): SleepSession? =
        db.sleepSessionDao().getSessionBeforeWake(wakeTime)

    suspend fun getAverageDeepSleepPercent(startTime: Long): Double? =
        db.sleepSessionDao().getAverageDeepSleepPercentSince(startTime)

    suspend fun getSleepCount(): Int =
        db.sleepSessionDao().getCount()

    // ── Meals ─────────────────────────────────────────────────

    suspend fun insertMealEntry(entry: MealEntry): Long =
        db.mealEntryDao().insert(entry)

    suspend fun updateMealEntry(entry: MealEntry) =
        db.mealEntryDao().update(entry)

    fun getAllMealEntries(): Flow<List<MealEntry>> =
        db.mealEntryDao().getAllEntries()

    fun getMealEntriesInRange(startTime: Long, endTime: Long): Flow<List<MealEntry>> =
        db.mealEntryDao().getEntriesInRange(startTime, endTime)

    suspend fun getLatestMealEntry(): MealEntry? =
        db.mealEntryDao().getLatestEntry()

    fun getUnconfirmedMealEntries(): Flow<List<MealEntry>> =
        db.mealEntryDao().getUnconfirmedEntries()

    suspend fun confirmMealEntry(id: Long) =
        db.mealEntryDao().confirmEntry(id)

    suspend fun deleteMealEntry(id: Long) =
        db.mealEntryDao().deleteById(id)

    suspend fun getTotalNetCarbsInRange(startTime: Long, endTime: Long): Double? =
        db.mealEntryDao().getTotalNetCarbsInRange(startTime, endTime)

    suspend fun getAverageKetogenicRatio(startTime: Long): Double? =
        db.mealEntryDao().getAverageKetogenicRatioSince(startTime)

    suspend fun getMealCount(): Int =
        db.mealEntryDao().getCount()

    // ── Data quality summary ──────────────────────────────────

    suspend fun getDataQualitySummary(): Map<String, Int> = mapOf(
        "glucose" to getGlucoseCount(),
        "ketones" to getKetoneCount(),
        "biosignals" to getBiosignalCount(),
        "sleep" to getSleepCount(),
        "meals" to getMealCount()
    )
}