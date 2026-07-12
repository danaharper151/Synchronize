package com.synchronize.app.util

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.synchronize.app.data.db.entity.BiosignalSample
import com.synchronize.app.data.db.entity.SleepSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.temporal.ChronoUnit

class HealthConnectManager(private val context: Context) {

    private val TAG = "HealthConnectManager"

    // ── Required permissions ──────────────────────────────────

    val requiredPermissions = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(SkinTemperatureRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class)
    )

    // ── State ─────────────────────────────────────────────────

    sealed class HcState {
        object Unavailable : HcState()
        object NotInstalled : HcState()
        object PermissionsRequired : HcState()
        object Ready : HcState()
        data class Error(val message: String) : HcState()
    }

    private val _state = MutableStateFlow<HcState>(HcState.Unavailable)
    val state: StateFlow<HcState> = _state

    private val _lastSyncTime = MutableStateFlow<Long?>(null)
    val lastSyncTime: StateFlow<Long?> = _lastSyncTime

    // ── Client ────────────────────────────────────────────────

    private var client: HealthConnectClient? = null

    // ── Initialize ────────────────────────────────────────────

    suspend fun initialize(): Boolean {
        val availability = HealthConnectClient.getSdkStatus(context)
        return when (availability) {
            HealthConnectClient.SDK_UNAVAILABLE -> {
                Log.e(TAG, "Health Connect SDK unavailable")
                _state.value = HcState.Unavailable
                false
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                Log.w(TAG, "Health Connect needs update")
                _state.value = HcState.NotInstalled
                false
            }
            else -> {
                client = HealthConnectClient.getOrCreate(context)
                val granted = checkPermissions()
                if (granted) {
                    _state.value = HcState.Ready
                    Log.d(TAG, "Health Connect initialized and ready")
                } else {
                    _state.value = HcState.PermissionsRequired
                    Log.w(TAG, "Health Connect permissions required")
                }
                granted
            }
        }
    }

    suspend fun checkPermissions(): Boolean {
        val granted = client?.permissionController?.getGrantedPermissions()
            ?: return false
        return granted.containsAll(requiredPermissions)
    }

    // ── Read heart rate ───────────────────────────────────────

    suspend fun readHeartRate(
        startTime: Instant,
        endTime: Instant
    ): List<HeartRateRecord> {
        return try {
            client?.readRecords(
                ReadRecordsRequest(
                    HeartRateRecord::class,
                    TimeRangeFilter.between(startTime, endTime)
                )
            )?.records ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading heart rate", e)
            emptyList()
        }
    }

    // ── Read HRV ──────────────────────────────────────────────

    suspend fun readHrv(
        startTime: Instant,
        endTime: Instant
    ): List<HeartRateVariabilityRmssdRecord> {
        return try {
            client?.readRecords(
                ReadRecordsRequest(
                    HeartRateVariabilityRmssdRecord::class,
                    TimeRangeFilter.between(startTime, endTime)
                )
            )?.records ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading HRV", e)
            emptyList()
        }
    }

    // ── Read SpO2 ─────────────────────────────────────────────

    suspend fun readSpo2(
        startTime: Instant,
        endTime: Instant
    ): List<OxygenSaturationRecord> {
        return try {
            client?.readRecords(
                ReadRecordsRequest(
                    OxygenSaturationRecord::class,
                    TimeRangeFilter.between(startTime, endTime)
                )
            )?.records ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading SpO2", e)
            emptyList()
        }
    }

    // ── Read blood pressure ───────────────────────────────────

    suspend fun readBloodPressure(
        startTime: Instant,
        endTime: Instant
    ): List<BloodPressureRecord> {
        return try {
            client?.readRecords(
                ReadRecordsRequest(
                    BloodPressureRecord::class,
                    TimeRangeFilter.between(startTime, endTime)
                )
            )?.records ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading blood pressure", e)
            emptyList()
        }
    }

    // ── Read sleep sessions ───────────────────────────────────

    suspend fun readSleepSessions(
        startTime: Instant,
        endTime: Instant
    ): List<SleepSessionRecord> {
        return try {
            client?.readRecords(
                ReadRecordsRequest(
                    SleepSessionRecord::class,
                    TimeRangeFilter.between(startTime, endTime)
                )
            )?.records ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading sleep sessions", e)
            emptyList()
        }
    }

    // ── Read steps ────────────────────────────────────────────

    suspend fun readSteps(
        startTime: Instant,
        endTime: Instant
    ): List<StepsRecord> {
        return try {
            client?.readRecords(
                ReadRecordsRequest(
                    StepsRecord::class,
                    TimeRangeFilter.between(startTime, endTime)
                )
            )?.records ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading steps", e)
            emptyList()
        }
    }

    // ── Read skin temperature ─────────────────────────────────

    suspend fun readSkinTemperature(
        startTime: Instant,
        endTime: Instant
    ): List<SkinTemperatureRecord> {
        return try {
            client?.readRecords(
                ReadRecordsRequest(
                    SkinTemperatureRecord::class,
                    TimeRangeFilter.between(startTime, endTime)
                )
            )?.records ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading skin temperature", e)
            emptyList()
        }
    }

    // ── Read exercise sessions ────────────────────────────────

    suspend fun readExerciseSessions(
        startTime: Instant,
        endTime: Instant
    ): List<ExerciseSessionRecord> {
        return try {
            client?.readRecords(
                ReadRecordsRequest(
                    ExerciseSessionRecord::class,
                    TimeRangeFilter.between(startTime, endTime)
                )
            )?.records ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading exercise sessions", e)
            emptyList()
        }
    }

    // ── Sync all biosignals for a time window ─────────────────

    suspend fun syncBiosignals(
        startTime: Instant = Instant.now().minus(24, ChronoUnit.HOURS),
        endTime: Instant = Instant.now()
    ): List<BiosignalSample> {
        if (_state.value != HcState.Ready) {
            Log.w(TAG, "Health Connect not ready — skipping sync")
            return emptyList()
        }

        return try {
            val hrRecords  = readHeartRate(startTime, endTime)
            val hrvRecords = readHrv(startTime, endTime)
            val spo2Records = readSpo2(startTime, endTime)
            val bpRecords  = readBloodPressure(startTime, endTime)
            val stepsRecords = readSteps(startTime, endTime)
            val skinTempRecords = readSkinTemperature(startTime, endTime)
            val exerciseRecords = readExerciseSessions(startTime, endTime)

            Log.d(TAG, "Synced: ${hrRecords.size} HR, ${hrvRecords.size} HRV, " +
                    "${spo2Records.size} SpO2, ${bpRecords.size} BP, " +
                    "${stepsRecords.size} steps, ${skinTempRecords.size} skin temp, " +
                    "${exerciseRecords.size} exercise")

            // Build biosignal samples from HR records as the primary timeline
            val samples = hrRecords.flatMap { hrRecord ->
                hrRecord.samples.map { sample ->
                    val sampleTime = sample.time

                    // Find closest HRV reading
                    val hrv = hrvRecords.minByOrNull {
                        Math.abs(it.time.toEpochMilli() - sampleTime.toEpochMilli())
                    }

                    // Find closest SpO2 reading
                    val spo2 = spo2Records.minByOrNull {
                        Math.abs(it.time.toEpochMilli() - sampleTime.toEpochMilli())
                    }

                    // Find closest BP reading
                    val bp = bpRecords.minByOrNull {
                        Math.abs(it.time.toEpochMilli() - sampleTime.toEpochMilli())
                    }

                    // Find closest skin temp reading
                    val skinTemp = skinTempRecords.minByOrNull {
                        Math.abs(
                            it.startTime.toEpochMilli() - sampleTime.toEpochMilli()
                        )
                    }

                    // Check if in exercise session
                    val inExercise = exerciseRecords.any {
                        sampleTime >= it.startTime && sampleTime <= it.endTime
                    }
                    val exerciseType = exerciseRecords.firstOrNull {
                        sampleTime >= it.startTime && sampleTime <= it.endTime
                    }?.exerciseType?.toString()

                    // Total steps in window
                    val totalSteps = stepsRecords.sumOf { it.count }.toInt()

                    BiosignalSample(
                        subjectId        = Constants.SUBJECT_ID,
                        deviceTimestamp  = sampleTime.toEpochMilli(),
                        receiveTimestamp = Instant.now().toEpochMilli(),
                        heartRateBpm     = sample.beatsPerMinute.toInt(),
                        heartRateStatus  = "COMPLETE",
                        hrvSdnn          = null,
                        hrvRmssd         = hrv?.heartRateVariabilityMillis,
                        spo2Percent      = spo2?.percentage?.value,
                        spo2Status       = if (spo2 != null) "COMPLETE" else null,
                        skinTempCelsius  = skinTemp?.baseline?.inCelsius,
                        skinTempDelta = skinTemp?.deltas?.firstOrNull()
                            ?.delta?.inCelsius,
                        systolicMmHg     = bp?.systolic?.inMillimetersOfMercury?.toInt(),
                        diastolicMmHg    = bp?.diastolic?.inMillimetersOfMercury?.toInt(),
                        bpStatus         = if (bp != null) "COMPLETE" else null,
                        ecgMv            = null,
                        ecgStatus        = null,
                        stepCount        = totalSteps,
                        activeCalories   = null,
                        stressScore      = null,
                        vo2Max           = null,
                        ppgGreen         = null,
                        ppgRed           = null,
                        ppgInfrared      = null,
                        exerciseType     = exerciseType,
                        isExerciseSession = inExercise,
                        sensorType       = "HEALTH_CONNECT"
                    )
                }
            }

            _lastSyncTime.value = Instant.now().toEpochMilli()
            Log.d(TAG, "Built ${samples.size} biosignal samples")
            samples

        } catch (e: Exception) {
            Log.e(TAG, "Error syncing biosignals", e)
            emptyList()
        }
    }

    // ── Sync sleep sessions ───────────────────────────────────

    suspend fun syncSleepSessions(
        startTime: Instant = Instant.now().minus(24, ChronoUnit.HOURS),
        endTime: Instant = Instant.now()
    ): List<SleepSession> {
        if (_state.value != HcState.Ready) return emptyList()

        return try {
            val records = readSleepSessions(startTime, endTime)
            records.map { record ->
                val totalMinutes = ChronoUnit.MINUTES.between(
                    record.startTime, record.endTime
                ).toInt()

                // Extract stage durations
                var deepMinutes  = 0
                var remMinutes   = 0
                var lightMinutes = 0
                var awakeMinutes = 0

                record.stages.forEach { stage ->
                    val stageMins = ChronoUnit.MINUTES.between(
                        stage.startTime, stage.endTime
                    ).toInt()
                    when (stage.stage) {
                        SleepSessionRecord.STAGE_TYPE_DEEP   -> deepMinutes  += stageMins
                        SleepSessionRecord.STAGE_TYPE_REM    -> remMinutes   += stageMins
                        SleepSessionRecord.STAGE_TYPE_LIGHT  -> lightMinutes += stageMins
                        SleepSessionRecord.STAGE_TYPE_AWAKE  -> awakeMinutes += stageMins
                    }
                }

                val now = Instant.now().toEpochMilli()
                SleepSession(
                    subjectId              = Constants.SUBJECT_ID,
                    deviceTimestamp        = record.startTime.toEpochMilli(),
                    receiveTimestamp       = now,
                    sleepStartTimestamp    = record.startTime.toEpochMilli(),
                    sleepEndTimestamp      = record.endTime.toEpochMilli(),
                    totalDurationMinutes   = totalMinutes,
                    deepSleepMinutes       = deepMinutes,
                    remSleepMinutes        = remMinutes,
                    lightSleepMinutes      = lightMinutes,
                    awakeDuringSleeepMinutes = awakeMinutes,
                    deepSleepPercent       = if (totalMinutes > 0)
                        deepMinutes.toDouble() / totalMinutes * 100 else null,
                    remSleepPercent        = if (totalMinutes > 0)
                        remMinutes.toDouble() / totalMinutes * 100 else null,
                    lightSleepPercent      = if (totalMinutes > 0)
                        lightMinutes.toDouble() / totalMinutes * 100 else null,
                    awakePercent           = if (totalMinutes > 0)
                        awakeMinutes.toDouble() / totalMinutes * 100 else null,
                    meanOvernightRmssd     = null,
                    meanOvernightSdnn      = null,
                    meanOvernightHeartRate = null,
                    meanSkinTempCelsius    = null,
                    skinTempDeltaCelsius   = null,
                    sleepScore             = null,
                    sleepEfficiency        = if (totalMinutes > 0)
                        ((totalMinutes - awakeMinutes).toDouble() / totalMinutes * 100)
                    else null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing sleep sessions", e)
            emptyList()
        }
    }
}