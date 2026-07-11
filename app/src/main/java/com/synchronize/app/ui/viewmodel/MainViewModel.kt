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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    // ── Init ──────────────────────────────────────────────────

    init {
        refreshLatestReadings()
        refreshDataQuality()
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
        _pendingMealEntry.value = entry
    }

    fun confirmPendingMealEntry() {
        viewModelScope.launch {
            _pendingMealEntry.value?.let { entry ->
                val id = repository.insertMealEntry(entry)
                repository.confirmMealEntry(id)
                _pendingMealEntry.value = null
                _latestMeal.value = entry
                refreshDataQuality()
            }
        }
    }

    fun discardPendingMealEntry() {
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