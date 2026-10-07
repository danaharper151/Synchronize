package com.synchronize.app.util


import android.content.Context
import android.util.Log
import com.synchronize.app.data.db.entity.KetoneReading
import com.synchronize.app.data.repository.SynchronizeRepository
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

class MojoSyncManager(
    private val context: Context,
    private val repository: SynchronizeRepository
) {

    private val TAG = "MojoSync"
    private val authManager = MojoAuthManager(context)
    private val api = MojoApiClient.instance

    private val iso = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneOffset.UTC)

    /** Pairing tolerance: a glucose reading within this window of a ketone
     *  reading is treated as the paired capillary measurement. */
    private val PAIR_TOLERANCE_MS = 5 * 60 * 1000L

    /**
     * Retrieves ketone and glucose readings since the last sync, pairs them,
     * and inserts any that are not already stored.
     *
     * Readings carrying an error_code are skipped rather than stored. The CSV
     * export encoded the below-range LO indicator as a numeric 0.00 with no
     * accompanying error field, making it indistinguishable from a valid
     * measurement; the API reports it separately.
     */
    suspend fun syncReadings(sinceMs: Long? = null): Result<Int> {
        return try {
            val token = authManager.getValidAccessToken()
                ?: return Result.failure(
                    Exception("Not authenticated — reauthorization required")
                )

            val from = sinceMs
                ?: authManager.getLastSyncTime()
                ?: (System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000)

            val fromStr = iso.format(Instant.ofEpochMilli(from))
            val toStr   = iso.format(Instant.now())
            Log.d(TAG, "range $fromStr -> $toStr")

            val ketones = fetchAll(token, "ketone", fromStr, toStr)
                .filter { it.readingSampleType == "blood" }
            val glucose = fetchAll(token, "glucose", fromStr, toStr)
                .filter { it.readingSampleType == "blood" }

            val readings = ketones.mapNotNull { k ->
                val bhb = k.valueOrNull() ?: run {
                    if (k.isError) Log.d(TAG, "skipped error_code=${k.errorCode}")
                    return@mapNotNull null
                }
                val ts = parseIso(k.readingTimestamp) ?: return@mapNotNull null

                // Pair with the nearest glucose reading inside the tolerance.
                val paired = glucose
                    .mapNotNull { g ->
                        val gt = parseIso(g.readingTimestamp) ?: return@mapNotNull null
                        val v = g.valueOrNull() ?: return@mapNotNull null
                        Triple(g, gt, v)
                    }
                    .filter { abs(it.second - ts) <= PAIR_TOLERANCE_MS }
                    .minByOrNull { abs(it.second - ts) }

                KetoneReading(
                    deviceTimestamp     = ts,
                    receiveTimestamp    = System.currentTimeMillis(),
                    betaHydroxybutyrate = bhb,
                    glucoseMgDl         = paired?.third?.toInt(),
                    source              = "MOJO_API",
                    readingContext      = null,   // assigned by the user
                    notes               = null
                )
            }

            if (readings.isEmpty()) {
                authManager.saveLastSyncTime(System.currentTimeMillis())
                return Result.success(0)
            }

            val inserted = repository.insertKetoneReadingsIfNew(readings)
            authManager.saveLastSyncTime(System.currentTimeMillis())
            Log.d(TAG, "inserted $inserted of ${readings.size}")
            Result.success(inserted)

        } catch (e: retrofit2.HttpException) {
            val body = e.response()?.errorBody()?.string()
            Log.e(TAG, "HTTP ${e.code()} — $body")
            Result.failure(Exception("HTTP ${e.code()}: $body"))
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            Result.failure(e)
        }
    }

    /**
     * The readings endpoint returns a bare JSON array rather than a paged
     * envelope, so there is no cursor to follow; page_size bounds the result.
     */
    private suspend fun fetchAll(
        token: String,
        readingType: String,
        from: String,
        to: String
    ): List<MojoReading> =
        api.getReadings(
            authorization = "Bearer $token",
            readingType   = readingType,
            from          = from,
            to            = to,
            pageSize      = 1000,
            cursor        = null
        )

    /**
     * Timestamps arrive as ISO-8601 with an explicit offset
     * (2026-08-16T07:10:00.000000-07:00), sometimes with none at all.
     * OffsetDateTime handles the first; a bare value is treated as UTC.
     */
    private fun parseIso(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return try {
            java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (e: Exception) {
            try {
                java.time.LocalDateTime.parse(s)
                    .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            } catch (e2: Exception) {
                Log.w(TAG, "unparseable timestamp: $s")
                null
            }
        }
    }
}