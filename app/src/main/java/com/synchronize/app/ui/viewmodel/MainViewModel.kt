package com.synchronize.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.synchronize.app.data.db.entity.BiosignalSample
import com.synchronize.app.data.db.entity.GlucoseReading
import com.synchronize.app.data.db.entity.KetoneReading
import com.synchronize.app.data.db.entity.MealEntry
import com.synchronize.app.data.db.entity.SleepSession
import com.synchronize.app.data.repository.SynchronizeRepository
import com.synchronize.app.util.Constants
import com.synchronize.app.util.DexcomAuthManager
import com.synchronize.app.util.KetoMojoManager
import com.synchronize.app.util.VoiceInputManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.synchronize.app.util.HealthConnectManager
// import com.synchronize.app.util.DashboardWebSocketServer
import kotlinx.coroutines.Dispatchers
import com.synchronize.app.util.ReminderManager
import kotlinx.coroutines.withContext
import com.synchronize.app.data.db.entity.ExerciseSession
import com.synchronize.app.data.db.entity.DailyCovariate

class MainViewModel(private val repository: SynchronizeRepository) : ViewModel() {

    // ── Latest readings (for dashboard summary cards) ─────────

    private val _latestGlucose = MutableStateFlow<GlucoseReading?>(null)
    val latestGlucose: StateFlow<GlucoseReading?> = _latestGlucose

    private val _latestKetone = MutableStateFlow<KetoneReading?>(null)
    val latestKetone: StateFlow<KetoneReading?> = _latestKetone

    private val _latestBiosignal = MutableStateFlow<BiosignalSample?>(null)
    val latestBiosignal: StateFlow<BiosignalSample?> = _latestBiosignal

    private val _latestSleep = MutableStateFlow<SleepSession?>(null)
    val latestSleep: StateFlow<SleepSession?> = _latestSleep

    private val _latestMeal = MutableStateFlow<MealEntry?>(null)
    val latestMeal: StateFlow<MealEntry?> = _latestMeal

    // ── Data quality summary ──────────────────────────────────

    private val _dataQuality = MutableStateFlow<Map<String, Int>>(emptyMap())
    val dataQuality: StateFlow<Map<String, Int>> = _dataQuality

    // ── Voice logging state ───────────────────────────────────

    private val _voiceTranscription = MutableStateFlow<String?>(null)
    val voiceTranscription: StateFlow<String?> = _voiceTranscription

    private val _pendingMealEntry = MutableStateFlow<MealEntry?>(null)
    val pendingMealEntry: StateFlow<MealEntry?> = _pendingMealEntry

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    // ── Voice input manager ───────────────────────────────────────
    private var voiceInputManager: VoiceInputManager? = null

    private val _voiceState = MutableStateFlow<VoiceInputManager.VoiceState>(
        VoiceInputManager.VoiceState.Idle
    )
    val voiceState: StateFlow<VoiceInputManager.VoiceState> = _voiceState

    fun initVoiceInput(context: android.content.Context) {
        if (voiceInputManager == null) {
            voiceInputManager = VoiceInputManager(context)
            viewModelScope.launch {
                voiceInputManager?.state?.collect { state ->
                    _voiceState.value = state
                }
            }
        }
    }

    fun startVoiceListening() {
        voiceInputManager?.startListening()
        _isListening.value = true
    }

    fun stopVoiceListening() {
        voiceInputManager?.stopListening()
        _isListening.value = false
    }

    // ── Daily covariates ──────────────────────────────────────────
    private val _caffeineMg = MutableStateFlow(400.0)
    val caffeineMg: StateFlow<Double> = _caffeineMg

    private val _meditationAmMinutes = MutableStateFlow(0)
    val meditationAmMinutes: StateFlow<Int> = _meditationAmMinutes

    private val _meditationPmMinutes = MutableStateFlow(0)
    val meditationPmMinutes: StateFlow<Int> = _meditationPmMinutes

    private val _covariatesLoggedToday = MutableStateFlow(false)
    val covariatesLoggedToday: StateFlow<Boolean> = _covariatesLoggedToday

    private val _covariateDateKey = MutableStateFlow(todayKey())
    val covariateDateKey: StateFlow<String> = _covariateDateKey

    val allCovariates = repository.getAllDailyCovariates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun todayKey(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())

    fun setCovariateDateKey(key: String) {
        _covariateDateKey.value = key
        viewModelScope.launch { loadCovariatesForDate(key) }
    }

    fun loadCovariatesForDate(dateKey: String) {
        viewModelScope.launch {
            val existing = repository.getCovariateForDate(dateKey)
            if (existing != null) {
                _caffeineMg.value = existing.caffeineMg
                _meditationAmMinutes.value = existing.meditationAmMinutes
                _meditationPmMinutes.value = existing.meditationPmMinutes
                _covariatesLoggedToday.value = true
            } else {
                _caffeineMg.value = 400.0
                _meditationAmMinutes.value = 0
                _meditationPmMinutes.value = 0
                _covariatesLoggedToday.value = false
            }
        }
    }

    fun logDailyCovariates(
        caffeineMg: Double,
        meditationAm: Int,
        meditationPm: Int,
        dateKey: String = _covariateDateKey.value
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val existing = repository.getCovariateForDate(dateKey)
            val record = DailyCovariate(
                id                  = existing?.id ?: 0,
                subjectId           = Constants.SUBJECT_ID,
                dateKey             = dateKey,
                deviceTimestamp     = existing?.deviceTimestamp ?: now,
                receiveTimestamp    = now,
                caffeineMg          = caffeineMg,
                meditationAmMinutes = meditationAm,
                meditationPmMinutes = meditationPm
            )
            repository.insertDailyCovariate(record)

            _caffeineMg.value = caffeineMg
            _meditationAmMinutes.value = meditationAm
            _meditationPmMinutes.value = meditationPm
            _covariatesLoggedToday.value = true
            refreshDataQuality()
        }
    }

    fun deleteCovariate(id: Long) {
        viewModelScope.launch {
            repository.deleteDailyCovariate(id)
            loadCovariatesForDate(_covariateDateKey.value)
            refreshDataQuality()
        }
    }

    // ── Exercise sessions ─────────────────────────────────────────
    private val _todayExercise = MutableStateFlow<ExerciseSession?>(null)
    val todayExercise: StateFlow<ExerciseSession?> = _todayExercise

    val allExerciseSessions = repository.getAllExerciseSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun refreshTodayExercise() {
        viewModelScope.launch {
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val dayStart = cal.timeInMillis
            val dayEnd = dayStart + 24 * 60 * 60 * 1000L
            _todayExercise.value = repository.getExerciseSessionForDay(dayStart, dayEnd)
        }
    }

    fun logExerciseSession(
        sessionType: String,
        durationMinutes: Int,
        startTimestamp: Long = System.currentTimeMillis()
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val session = ExerciseSession(
                subjectId        = Constants.SUBJECT_ID,
                deviceTimestamp  = now,
                receiveTimestamp = now,
                startTimestamp   = startTimestamp,
                durationMinutes  = durationMinutes,
                sessionType      = sessionType,
                isHiit           = sessionType == ExerciseSession.TYPE_HIIT,
                notes            = null
            )
            repository.insertExerciseSession(session)
            _todayExercise.value = session
            refreshDataQuality()
            android.util.Log.d("MainViewModel",
                "Exercise logged: $sessionType, ${durationMinutes}min")
        }
    }

    fun deleteExerciseSession(id: Long) {
        viewModelScope.launch {
            repository.deleteExerciseSession(id)
            refreshTodayExercise()
            refreshDataQuality()
        }
    }

    fun updateExerciseSession(session: ExerciseSession) {
        viewModelScope.launch {
            repository.updateExerciseSession(session)
            refreshTodayExercise()
            refreshDataQuality()
        }
    }

    // ── Keto-Mojo BLE ─────────────────────────────────────────────
    private var ketoMojoManager: KetoMojoManager? = null

    private val _bleState = MutableStateFlow<KetoMojoManager.BleState>(
        KetoMojoManager.BleState.Idle
    )
    val bleState: StateFlow<KetoMojoManager.BleState> = _bleState

    private val _latestKetoneReading = MutableStateFlow<com.synchronize.app.data.db.entity.KetoneReading?>(null)
    val latestKetoneReading: StateFlow<com.synchronize.app.data.db.entity.KetoneReading?> = _latestKetoneReading

    fun initKetoMojo(context: android.content.Context) {
        if (ketoMojoManager == null) {
            ketoMojoManager = KetoMojoManager(context)
            viewModelScope.launch {
                ketoMojoManager?.state?.collect { state ->
                    _bleState.value = state
                    if (state is KetoMojoManager.BleState.ReadingReceived) {
                        _latestKetoneReading.value = state.reading
                        insertKetoneReading(state.reading)
                    }
                }
            }
        }
    }

    fun startKetoMojoScan() {
        ketoMojoManager?.startScan()
    }

    fun stopKetoMojoScan() {
        ketoMojoManager?.stopScan()
    }

    fun disconnectKetoMojo() {
        ketoMojoManager?.disconnect()
    }
    // ── Health Connect ────────────────────────────────────────────
    private var healthConnectManager: HealthConnectManager? = null

    private val _hcState = MutableStateFlow<HealthConnectManager.HcState>(
        HealthConnectManager.HcState.Unavailable
    )
    val hcState: StateFlow<HealthConnectManager.HcState> = _hcState

    private val _lastBiosignalSync = MutableStateFlow<Long?>(null)
    val lastBiosignalSync: StateFlow<Long?> = _lastBiosignalSync

    fun initHealthConnect(context: android.content.Context) {
        if (healthConnectManager == null) {
            healthConnectManager = HealthConnectManager(context)
            viewModelScope.launch {
                val ready = healthConnectManager?.initialize() ?: false
                _hcState.value = if (ready)
                    HealthConnectManager.HcState.Ready
                else
                    HealthConnectManager.HcState.Unavailable
            }
        }
    }

    fun syncBiosignals() {
        viewModelScope.launch {
            val samples = healthConnectManager?.syncBiosignals() ?: emptyList()
            if (samples.isNotEmpty()) {
                repository.insertBiosignalSamples(samples)
                _lastBiosignalSync.value = System.currentTimeMillis()
                refreshDataQuality()
                android.util.Log.d("MainViewModel",
                    "Synced ${samples.size} biosignal samples")
            }
        }
    }

    fun syncSleepSessions() {
        viewModelScope.launch {
            val sessions = healthConnectManager?.syncSleepSessions() ?: emptyList()
            if (sessions.isNotEmpty()) {
                sessions.forEach { repository.insertSleepSession(it) }
                refreshDataQuality()
                android.util.Log.d("MainViewModel",
                    "Synced ${sessions.size} sleep sessions")
            }
        }
    }

    fun getHealthConnectPermissions() =
        healthConnectManager?.requiredPermissions ?: emptySet()

    // ── Reading reminders ─────────────────────────────────────────
    private var reminderManager: ReminderManager? = null

    private val _remindersEnabled = MutableStateFlow(false)
    val remindersEnabled: StateFlow<Boolean> = _remindersEnabled

    fun initReminders(context: android.content.Context) {
        if (reminderManager == null) {
            reminderManager = ReminderManager(context)
            _remindersEnabled.value = reminderManager?.areRemindersScheduled() ?: false
        }
    }

    fun enableReminders(context: android.content.Context) {
        val manager = ReminderManager(context)
        manager.scheduleAllReminders()
        reminderManager = manager
        _remindersEnabled.value = true
        android.util.Log.d("MainViewModel", "Reading reminders enabled")
    }

    fun disableReminders() {
        reminderManager?.cancelAllReminders()
        _remindersEnabled.value = false
        android.util.Log.d("MainViewModel", "Reading reminders disabled")
    }

   /* // ── WebSocket dashboard server ────────────────────────────────
    private var wsServer: DashboardWebSocketServer? = null

    private val _wsClientCount = MutableStateFlow(0)
    val wsClientCount: StateFlow<Int> = _wsClientCount

    fun startWebSocketServer() {
        if (wsServer?.isRunning() == true) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                wsServer = DashboardWebSocketServer(8080)
                wsServer?.start()
                android.util.Log.d("MainViewModel", "WebSocket server started on port 8080")
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "WebSocket server failed to start", e)
            }
        }
    }

    fun stopWebSocketServer() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                wsServer?.stop()
                wsServer = null
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "WebSocket server stop error", e)
            }
        }
    }

    fun broadcastAllData() {
        viewModelScope.launch {
            val server = wsServer ?: return@launch
            server.broadcastGlucose(allGlucoseReadings.value)
            server.broadcastKetones(allKetoneReadings.value)
            server.broadcastBiosignals(allBiosignalSamples.value)
            server.broadcastMeals(allMealEntries.value)
            server.broadcastSleep(allSleepSessions.value)
            server.broadcastDataQuality(dataQuality.value)
            _wsClientCount.value = server.clientCount()
        }
    }


*/

    // ── All readings (for graph screens) ─────────────────────

    val allGlucoseReadings = repository.getAllGlucoseReadings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allKetoneReadings = repository.getAllKetoneReadings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allBiosignalSamples = repository.getAllBiosignalSamples()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSleepSessions = repository.getAllSleepSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allMealEntries = repository.getAllMealEntries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unconfirmedMealEntries = repository.getUnconfirmedMealEntries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Dexcom auth state ─────────────────────────────────────────
    private val _isDexcomConnected = MutableStateFlow(false)
    val isDexcomConnected: StateFlow<Boolean> = _isDexcomConnected

    private val _glucoseSyncCount = MutableStateFlow(0)
    val glucoseSyncCount: StateFlow<Int> = _glucoseSyncCount

    fun setDexcomConnected(connected: Boolean) {
        _isDexcomConnected.value = connected
    }

    fun setGlucoseSyncCount(count: Int) {
        _glucoseSyncCount.value = count
    }

    // ── Init ──────────────────────────────────────────────────

    init {
        loadCovariatesForDate(todayKey())
        refreshLatestReadings()
        refreshDataQuality()
        refreshTodayExercise()
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(3000) // wait for UI to load first
            // startWebSocketServer()
        }
    }

    // ── Refresh functions ─────────────────────────────────────

    fun refreshLatestReadings() {
        viewModelScope.launch {
            _latestGlucose.value = repository.getLatestGlucoseReading()
            _latestKetone.value = repository.getLatestKetoneReading()
            _latestBiosignal.value = repository.getLatestBiosignalSample()
            _latestSleep.value = repository.getLatestSleepSession()
            _latestMeal.value = repository.getLatestMealEntry()
        }
    }

    fun refreshDataQuality() {
        viewModelScope.launch {
            _dataQuality.value = repository.getDataQualitySummary()
        }
    }

    // ── Insert functions ──────────────────────────────────────

    fun insertGlucoseReading(reading: GlucoseReading) {
        viewModelScope.launch {
            repository.insertGlucoseReading(reading)
            _latestGlucose.value = reading
        }
    }

    fun insertGlucoseReadings(readings: List<GlucoseReading>) {
        viewModelScope.launch {
            repository.insertGlucoseReadings(readings)
            refreshLatestReadings()
        }
    }

    fun insertKetoneReading(reading: KetoneReading) {
        viewModelScope.launch {
            repository.insertKetoneReading(reading)
            _latestKetone.value = reading
            refreshDataQuality()
            //broadcastAllData()  // push to web dashboard immediately
        }
    }
    fun updateKetoneReading(reading: KetoneReading) {
        viewModelScope.launch {
            repository.updateKetoneReading(reading)
            refreshLatestReadings()
            refreshDataQuality()
        }
    }

    fun deleteKetoneReading(id: Long) {
        viewModelScope.launch {
            repository.deleteKetoneReading(id)
            refreshLatestReadings()
            refreshDataQuality()
        }
    }

    fun insertBiosignalSample(sample: BiosignalSample) {
        viewModelScope.launch {
            repository.insertBiosignalSample(sample)
            _latestBiosignal.value = sample
        }
    }

    fun insertSleepSession(session: SleepSession) {
        viewModelScope.launch {
            repository.insertSleepSession(session)
            _latestSleep.value = session
        }
    }

    // ── Meal entry functions ──────────────────────────────────

    fun setPendingMealEntry(entry: MealEntry) {
        android.util.Log.d("MealDebug", "setPendingMealEntry called: ${entry.calories} cal")
        _pendingMealEntry.value = entry
    }

    fun confirmPendingMealEntry() {
        viewModelScope.launch {
            val entry = _pendingMealEntry.value ?: return@launch
            android.util.Log.d("MealDebug", "confirm: clearing pending")
            _pendingMealEntry.value = null
            _voiceTranscription.value = null
            val id = repository.insertMealEntry(entry)
            repository.confirmMealEntry(id)
            _latestMeal.value = entry
            refreshDataQuality()
            android.util.Log.d("MealDebug", "confirm: done, pending=${_pendingMealEntry.value}")
        }
    }

    fun deleteMealEntry(id: Long) {
        viewModelScope.launch {
            repository.deleteMealEntry(id)
            refreshDataQuality()
            android.util.Log.d("MainViewModel", "Deleted meal entry $id")
        }
    }

    fun updateMealEntry(entry: com.synchronize.app.data.db.entity.MealEntry) {
        viewModelScope.launch {
            repository.updateMealEntry(entry)
            refreshDataQuality()
            android.util.Log.d("MainViewModel", "Updated meal entry ${entry.id}")
        }
    }

    fun discardPendingMealEntry() {
        android.util.Log.d("MealDebug", "discard called")
        _pendingMealEntry.value = null
        _voiceTranscription.value = null
    }

    // ── Voice logging functions ───────────────────────────────

    fun setListening(listening: Boolean) {
        _isListening.value = listening
    }

    fun setVoiceTranscription(text: String) {
        _voiceTranscription.value = text
    }

    fun clearVoiceTranscription() {
        _voiceTranscription.value = null
    }
    fun clearVoiceState() {
        voiceInputManager?.reset()
        _voiceState.value = VoiceInputManager.VoiceState.Idle
    }

    // -- Data Backup function

    fun backupDatabase(context: android.content.Context): String? {
        return try {
            // Force WAL contents into the main database file first
            val db = com.synchronize.app.SynchronizeApp.instance.database
            db.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }

            val dbFile = context.getDatabasePath("synchronize_db")
            val backupDir = java.io.File(
                context.getExternalFilesDir(null), "backups"
            ).apply { mkdirs() }
            val stamp = java.text.SimpleDateFormat(
                "yyyyMMdd_HHmm", java.util.Locale.US
            ).format(java.util.Date())
            val dest = java.io.File(backupDir, "synchronize_db_$stamp")
            dbFile.copyTo(dest, overwrite = true)
            android.util.Log.d("Backup", "Saved: ${dest.absolutePath}")
            dest.absolutePath
        } catch (e: Exception) {
            android.util.Log.e("Backup", "Backup failed", e)
            null
        }
    }


    // ── ViewModelFactory ──────────────────────────────────────

    class Factory(private val repository: SynchronizeRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return MainViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}