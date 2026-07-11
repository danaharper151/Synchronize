package com.synchronize.app.util

import android.content.Context
import com.synchronize.app.data.db.entity.GlucoseReading
import com.synchronize.app.data.repository.SynchronizeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class DexcomSyncManager(
    private val context: Context,
    private val repository: SynchronizeRepository,
    private val clientSecret: String
) {

    private val authManager = DexcomAuthManager(context)
    private val api = DexcomRetrofitClient.instance
    private val scope = CoroutineScope(Dispatchers.IO)
    private var pollJob: Job? = null

    private val isoFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        .withZone(ZoneOffset.UTC)

    // ── Start polling every 5 minutes ─────────────────────────

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                syncLatestEgvs()
                delay(Constants.DEXCOM_POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // ── Sync EGVs from last known timestamp ───────────────────

    suspend fun syncLatestEgvs(): Result<Int> {
        return try {
            val token = authManager.getValidAccessToken(clientSecret)
                ?: return Result.failure(Exception("Not authenticated"))

            // Use last sync time or default to 3 hours ago
            val lastSync = authManager.getLastSyncTime()
                ?: (System.currentTimeMillis() - 3 * 60 * 60 * 1000L)

            val startDate = isoFormatter.format(Instant.ofEpochMilli(lastSync))
            val endDate   = isoFormatter.format(Instant.now())

            val response = api.getEgvs(
                authorization = "Bearer $token",
                startDate     = startDate,
                endDate       = endDate
            )

            val records = response.records ?: emptyList()
            if (records.isEmpty()) return Result.success(0)

            // Convert to GlucoseReading entities and insert
            val readings = records.mapNotNull { record ->
                val glucoseValue = record.value ?: return@mapNotNull null
                val systemTimeMs = parseIsoToMs(record.systemTime)
                    ?: return@mapNotNull null

                GlucoseReading(
                    subjectId       = Constants.SUBJECT_ID,
                    deviceTimestamp = systemTimeMs,
                    receiveTimestamp = System.currentTimeMillis(),
                    glucoseMgDl     = glucoseValue,
                    trend           = record.trend ?: "NONE",
                    trendRate       = record.trendRate,
                    transmitterId   = record.transmitterId,
                    displayOnly     = record.status != null
                )
            }

            repository.insertGlucoseReadings(readings)
            authManager.saveLastSyncTime(System.currentTimeMillis())

            Result.success(readings.size)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Full historical sync (first run) ──────────────────────

    suspend fun syncFullHistory(): Result<Int> {
        return try {
            val token = authManager.getValidAccessToken(clientSecret)
                ?: return Result.failure(Exception("Not authenticated"))

            // Get available data range first
            val rangeResponse = api.getDataRange("Bearer $token")
            val startDate = rangeResponse.egvs?.start?.systemTime
                ?: isoFormatter.format(
                    Instant.now().minusSeconds(90 * 24 * 60 * 60L)
                )
            val endDate = isoFormatter.format(Instant.now())

            val response = api.getEgvs(
                authorization = "Bearer $token",
                startDate     = startDate,
                endDate       = endDate
            )

            val records = response.records ?: emptyList()
            val readings = records.mapNotNull { record ->
                val glucoseValue = record.value ?: return@mapNotNull null
                val systemTimeMs = parseIsoToMs(record.systemTime)
                    ?: return@mapNotNull null

                GlucoseReading(
                    subjectId        = Constants.SUBJECT_ID,
                    deviceTimestamp  = systemTimeMs,
                    receiveTimestamp = System.currentTimeMillis(),
                    glucoseMgDl      = glucoseValue,
                    trend            = record.trend ?: "NONE",
                    trendRate        = record.trendRate,
                    transmitterId    = record.transmitterId,
                    displayOnly      = record.status != null
                )
            }

            repository.insertGlucoseReadings(readings)
            authManager.saveLastSyncTime(System.currentTimeMillis())

            Result.success(readings.size)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Helper: parse ISO 8601 string to epoch ms ─────────────

    private fun parseIsoToMs(isoString: String?): Long? {
        return try {
            isoString?.let {
                Instant.parse(if (it.endsWith("Z")) it else "${it}Z").toEpochMilli()
            }
        } catch (e: Exception) {
            null
        }
    }
}