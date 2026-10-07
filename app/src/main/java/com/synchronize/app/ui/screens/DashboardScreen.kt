package com.synchronize.app.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synchronize.app.data.db.entity.KetoneReading
import com.synchronize.app.data.db.entity.MealEntry
import com.synchronize.app.ui.theme.*
import com.synchronize.app.ui.viewmodel.MainViewModel

import com.synchronize.app.util.HealthConnectManager
import com.synchronize.app.util.KetoMojoManager
import com.synchronize.app.util.VoiceInputManager
import java.time.Instant
import kotlinx.coroutines.launch
import com.synchronize.app.MainActivity

import com.synchronize.app.data.db.entity.ExerciseSession
import androidx.compose.foundation.clickable
import com.synchronize.app.data.db.entity.DailyCovariate
import androidx.compose.runtime.saveable.rememberSaveable

@Composable
fun DashboardScreen(viewModel: MainViewModel) {

    val context = LocalContext.current
    val activity = context as MainActivity

    // ── State collectors ──────────────────────────────────────
    val latestGlucose     by viewModel.latestGlucose.collectAsState()
    val latestKetone      by viewModel.latestKetone.collectAsState()
    val latestBiosignal   by viewModel.latestBiosignal.collectAsState()
    val dataQuality       by viewModel.dataQuality.collectAsState()
    val isListening       by viewModel.isListening.collectAsState()
    val voiceTranscription by viewModel.voiceTranscription.collectAsState()
    val pendingMeal       by viewModel.pendingMealEntry.collectAsState()
    val isDexcomConnected by viewModel.isDexcomConnected.collectAsState()
    val glucoseSyncCount  by viewModel.glucoseSyncCount.collectAsState()
    val bleState          by viewModel.bleState.collectAsState()
    val latestKetoneReading by viewModel.latestKetoneReading.collectAsState()
    val hcState           by viewModel.hcState.collectAsState()
    val lastBiosignalSync by viewModel.lastBiosignalSync.collectAsState()
    val caffeineMg        by viewModel.caffeineMg.collectAsState()
    val meditationAm      by viewModel.meditationAmMinutes.collectAsState()
    val meditationPm      by viewModel.meditationPmMinutes.collectAsState()
    val covariatesLogged  by viewModel.covariatesLoggedToday.collectAsState()
    val todayExercise by viewModel.todayExercise.collectAsState()
    val allExerciseSessions by viewModel.allExerciseSessions.collectAsState()
    var editingExerciseId by rememberSaveable { mutableStateOf<Long?>(null) }
    val exerciseToEdit = editingExerciseId?.let { id ->
        allExerciseSessions.find { it.id == id }
    }
    val covariateDateKey by viewModel.covariateDateKey.collectAsState()
    val allCovariates by viewModel.allCovariates.collectAsState()
    val isMojoConnected by viewModel.isMojoConnected.collectAsState()
    val mojoStatus by viewModel.mojoSyncStatus.collectAsState()






    // ── Init effects ──────────────────────────────────────────
    LaunchedEffect(Unit) {
        viewModel.initVoiceInput(context)
        viewModel.initHealthConnect(context)
        viewModel.initReminders(context)
    }

    LaunchedEffect(Unit) {
        viewModel.voiceState.collect { voiceState ->
            when (voiceState) {
                is VoiceInputManager.VoiceState.Result -> {
                    viewModel.setVoiceTranscription(voiceState.text)
                    viewModel.setListening(false)
                    voiceState.macros?.let { macros ->
                        val now = System.currentTimeMillis()
                        viewModel.setPendingMealEntry(
                            MealEntry(
                                deviceTimestamp   = now,
                                receiveTimestamp  = now,
                                calories          = macros.calories,
                                fatGrams          = macros.fatGrams,
                                proteinGrams      = macros.proteinGrams,
                                netCarbGrams      = macros.netCarbGrams,
                                fiberGrams        = macros.fiberGrams,
                                totalCarbGrams    = macros.totalCarbGrams,
                                ketogenicRatio    = macros.ketogenicRatio,
                                parsingMethod     = macros.parsingMethod,
                                mealLabel         = null,
                                rawTranscription  = voiceState.text,
                                caffeineMg        = null,
                                meditationMinutes = null,
                                meditationSession = null
                            )
                        )
                    }
                    viewModel.clearVoiceState()          // ← consume
                }
                is VoiceInputManager.VoiceState.Error -> {
                    viewModel.setVoiceTranscription("Error: ${voiceState.message}")
                    viewModel.setListening(false)
                    viewModel.clearVoiceState()          // ← consume
                }
                is VoiceInputManager.VoiceState.Listening -> {
                    viewModel.setVoiceTranscription("Listening...")
                }
                else -> {}
            }
        }
    }


    // ── Main dashboard ────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SyncBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 48.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        // ── Header ────────────────────────────────────────────
        Text(
            text = "Synchronize",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = SyncGreen,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Text(
            text = "Data Sync Platform",
            fontSize = 12.sp,
            color = SyncLightGray,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(4.dp))

        // ── Summary cards ─────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SummaryCard(
                modifier = Modifier.weight(1f),
                label = "Glucose",
                value = latestGlucose?.glucoseMgDl?.toString() ?: "--",
                unit = "mg/dL",
                valueColor = latestGlucose?.let { glucoseColor(it.glucoseMgDl) }
                    ?: SyncLightGray,
                subtitle = latestGlucose?.trend ?: "No data"
            )
            SummaryCard(
                modifier = Modifier.weight(1f),
                label = "Ketones",
                value = latestKetone?.betaHydroxybutyrate
                    ?.let { "%.1f".format(it) } ?: "--",
                unit = "mmol/L",
                valueColor = latestKetone?.let { ketoneColor(it.betaHydroxybutyrate) }
                    ?: SyncLightGray,
                subtitle = latestKetone?.readingContext ?: "No data"
            )
            SummaryCard(
                modifier = Modifier.weight(1f),
                label = "Heart Rate",
                value = latestBiosignal?.heartRateBpm?.toString() ?: "--",
                unit = "bpm",
                valueColor = SyncGreen,
                subtitle = latestBiosignal?.let {
                    "HRV ${it.hrvRmssd?.let { r -> "%.0f".format(r) } ?: "--"} ms"
                } ?: "No data"
            )
        }

        // ── Voice logging card ────────────────────────────────
        VoiceLoggingCard(
            isListening = isListening,
            transcription = voiceTranscription,
            pendingMeal = pendingMeal?.let {
                "Cal:${it.calories} | F:${it.fatGrams.toInt()}g | " +
                        "P:${it.proteinGrams.toInt()}g | NC:${it.netCarbGrams.toInt()}g | " +
                        "Fib:${it.fiberGrams.toInt()}g"
            },
            onMicClick = {
                if (isListening) {
                    viewModel.stopVoiceListening()
                    viewModel.setListening(false)
                } else {
                    viewModel.initVoiceInput(context)
                    viewModel.startVoiceListening()
                    viewModel.setListening(true)
                    viewModel.setVoiceTranscription("Listening...")
                }
            },
            onConfirm = { viewModel.confirmPendingMealEntry() },
            onDiscard = { viewModel.discardPendingMealEntry() }
        )

        // ── Daily covariates card ─────────────────────────────
        DailyCovariatesCard(
            caffeineMg = caffeineMg,
            meditationAm = meditationAm,
            meditationPm = meditationPm,
            isLogged = covariatesLogged,
            dateKey = covariateDateKey,
            allCovariates = allCovariates,
            onDateChange = { viewModel.setCovariateDateKey(it) },
            onLog = { caffeine, am, pm ->
                viewModel.logDailyCovariates(caffeine, am, pm)
            },
            onDelete = { id -> viewModel.deleteCovariate(id) }
        )

        ExerciseCard(
            todaySession = todayExercise,
            allSessions = allExerciseSessions,
            onLog = { type, mins, ts ->
                viewModel.logExerciseSession(type, mins, ts)
            },
            onEditRequest = { editingExerciseId = it.id }
        )

        // ── Data quality card ─────────────────────────────────
        if (dataQuality.isNotEmpty()) {
            DataQualityCard(dataQuality = dataQuality)
        }

        // ── Dexcom connect card ───────────────────────────────
        DexcomConnectCard(
            isConnected = isDexcomConnected,
            glucoseCount = glucoseSyncCount,
            onConnectClick = { activity.launchDexcomAuth() },
            onSyncClick = { viewModel.syncDexcomFullHistory(context) }
        )

        // ── Keto-Mojo API card ────────────────────────────────
        DexcomConnectCard(
            isConnected = isMojoConnected,
            glucoseCount = 0,
            onConnectClick = { activity.launchMojoAuth() },
            onSyncClick = { viewModel.syncMojo(context) }
        )
        mojoStatus?.let {
            Text(it, fontSize = 12.sp, color = SyncLightGray)
        }

        // ── Health Connect / Galaxy Watch card ────────────────
        HealthConnectCard(
            hcState = hcState,
            lastSyncTime = lastBiosignalSync,
            biosignalCount = dataQuality["biosignals"] ?: 0,
            sleepCount = dataQuality["sleep"] ?: 0,
            onSyncClick = {
                if (hcState is HealthConnectManager.HcState.Ready) {
                    viewModel.syncBiosignals()
                } else {
                    activity.requestHealthConnectPermissions()
                }
            },
            onSyncSleepClick = {
                viewModel.initHealthConnect(context)
                viewModel.syncSleepSessions()
            }
        )

        // ── Stream status card ────────────────────────────────
        StreamStatusCard(
            glucoseCount = dataQuality["glucose"] ?: 0,
            ketoneCount = dataQuality["ketones"] ?: 0,
            biosignalCount = dataQuality["biosignals"] ?: 0,
            sleepCount = dataQuality["sleep"] ?: 0,
            mealCount = dataQuality["meals"] ?: 0
        )
    }
    exerciseToEdit?.let { session ->
        ExerciseEditDialog(
            session = session,
            onSave = {
                viewModel.updateExerciseSession(it)
                editingExerciseId = null
            },
            onDelete = { id ->
                viewModel.deleteExerciseSession(id)
                editingExerciseId = null
            },
            onDismiss = { editingExerciseId = null }
        )
    }
}

// ── Summary Card ──────────────────────────────────────────────

@Composable
fun SummaryCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    unit: String,
    valueColor: Color,
    subtitle: String
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SyncDarkSurface)
            .border(1.dp, SyncMagenta, RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = label, fontSize = 11.sp, color = SyncLightGray)
        Text(text = value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = valueColor)
        Text(text = unit, fontSize = 10.sp, color = SyncLightGray)
        Text(text = subtitle, fontSize = 9.sp, color = SyncLightGray,
            textAlign = TextAlign.Center)
    }
}

// ── Voice Logging Card ────────────────────────────────────────

@Composable
fun VoiceLoggingCard(
    isListening: Boolean,
    transcription: String?,
    pendingMeal: String?,
    onMicClick: () -> Unit,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SyncDarkSurface)
            .border(
                1.dp,
                if (isListening) SyncGreen else SyncMagenta,
                RoundedCornerShape(12.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Voice Macro Entry",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = SyncGreen
        )
        Text(
            text = "Format: calories / fat / protein / net carbs / fiber",
            fontSize = 11.sp,
            color = SyncLightGray
        )
        Button(
            onClick = onMicClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isListening) SyncGreen else SyncDarkGray
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "Microphone",
                tint = if (isListening) SyncBlack else SyncGreen
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isListening) "Listening..." else "Tap to Log Meal",
                color = if (isListening) SyncBlack else SyncGreen
            )
        }
        transcription?.let {
            Text(text = it, fontSize = 12.sp, color = SyncWhite)
        }
        pendingMeal?.let {
            Text(text = "Parsed: $it", fontSize = 12.sp, color = SyncGreen)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = SyncGreen)
                ) {
                    Text("Confirm", color = SyncBlack, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onDiscard,
                    modifier = Modifier.weight(1f),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = SolidColor(SyncMagenta)
                    )
                ) {
                    Text("Discard", color = SyncMagenta)
                }
            }
        }
    }
}

// ── Data Quality Card ─────────────────────────────────────────

@Composable
fun DataQualityCard(dataQuality: Map<String, Int>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SyncDarkSurface)
            .border(1.dp, SyncMagenta, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Data Collection",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = SyncGreen
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            dataQuality.forEach { (key, count) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = count.toString(),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = SyncGreen
                    )
                    Text(text = key, fontSize = 10.sp, color = SyncLightGray)
                }
            }
        }
    }
}

// ── Stream Status Card ────────────────────────────────────────

@Composable
fun StreamStatusCard(
    glucoseCount: Int,
    ketoneCount: Int,
    biosignalCount: Int,
    sleepCount: Int,
    mealCount: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SyncDarkSurface)
            .border(1.dp, SyncMagenta, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Stream Status",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = SyncGreen
        )
        StreamStatusRow("CGM (Dexcom G7)", glucoseCount, "readings")
        StreamStatusRow("Blood Ketones (Keto-Mojo)", ketoneCount, "readings")
        StreamStatusRow("Biosignals (Galaxy Watch)", biosignalCount, "samples")
        StreamStatusRow("Sleep Sessions", sleepCount, "sessions")
        StreamStatusRow("Meal Entries", mealCount, "entries")
    }
}

@Composable
fun StreamStatusRow(label: String, count: Int, unit: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 12.sp, color = SyncWhite)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        if (count > 0) SyncGreen else SyncMidGray,
                        shape = RoundedCornerShape(4.dp)
                    )
            )
            Text(
                text = if (count > 0) "$count $unit" else "No data",
                fontSize = 12.sp,
                color = if (count > 0) SyncGreen else SyncLightGray
            )
        }
    }
}

// ── Daily Covariates Card (with date + history) ───────────────

@Composable
fun DailyCovariatesCard(
    caffeineMg: Double,
    meditationAm: Int,
    meditationPm: Int,
    isLogged: Boolean,
    dateKey: String,
    allCovariates: List<DailyCovariate>,
    onDateChange: (String) -> Unit,
    onLog: (caffeine: Double, am: Int, pm: Int) -> Unit,
    onDelete: (Long) -> Unit
) {
    var caffeineInput by rememberSaveable(caffeineMg) {
        mutableStateOf(caffeineMg.toInt().toString())
    }
    var amInput by rememberSaveable(meditationAm) {
        mutableStateOf(if (meditationAm > 0) meditationAm.toString() else "")
    }
    var pmInput by rememberSaveable(meditationPm) {
        mutableStateOf(if (meditationPm > 0) meditationPm.toString() else "")
    }
    var dateInput by rememberSaveable(dateKey) { mutableStateOf(dateKey) }
    var expanded by rememberSaveable { mutableStateOf(!isLogged) }
    var showHistory by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SyncDarkSurface)
            .border(
                1.dp,
                if (isLogged) SyncGreen else SyncMagenta,
                RoundedCornerShape(12.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ── Header ────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Daily Covariates",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isLogged) SyncGreen else SyncWhite
                )
                Text(
                    text = if (isLogged)
                        "$dateKey ✓ — ${caffeineMg.toInt()}mg · " +
                                "AM ${meditationAm}m · PM ${meditationPm}m"
                    else
                        "Not logged for $dateKey",
                    fontSize = 11.sp,
                    color = SyncLightGray
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    text = if (expanded) "Hide" else "Edit",
                    color = if (isLogged) SyncLightGray else SyncMagenta,
                    fontSize = 11.sp
                )
            }
        }

        if (expanded) {

            // ── Date ──────────────────────────────────────────
            OutlinedTextField(
                value = dateInput,
                onValueChange = {
                    dateInput = it
                    if (it.length == 10) onDateChange(it)
                },
                label = {
                    Text("Date (yyyy-MM-dd)", color = SyncLightGray, fontSize = 11.sp)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = SyncGreen,
                    unfocusedBorderColor = SyncMidGray,
                    focusedTextColor     = SyncWhite,
                    unfocusedTextColor   = SyncWhite,
                    cursorColor          = SyncGreen
                ),
                singleLine = true
            )

            HorizontalDivider(color = SyncMidGray, thickness = 0.5.dp)

            // ── Caffeine ──────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Caffeine", fontSize = 11.sp, color = SyncLightGray)
                    Text(
                        "46g grounds ≈ 400mg",
                        fontSize = 9.sp,
                        color = SyncMidGray
                    )
                }
                OutlinedTextField(
                    value = caffeineInput,
                    onValueChange = { caffeineInput = it },
                    modifier = Modifier.width(100.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = SyncGreen,
                        unfocusedBorderColor = SyncMidGray,
                        focusedTextColor     = SyncWhite,
                        unfocusedTextColor   = SyncWhite,
                        cursorColor          = SyncGreen
                    ),
                    singleLine = true,
                    suffix = { Text("mg", color = SyncLightGray, fontSize = 11.sp) }
                )
            }

            // ── Meditation AM ─────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Meditation AM",
                    fontSize = 11.sp,
                    color = SyncLightGray,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = amInput,
                    onValueChange = { amInput = it },
                    modifier = Modifier.width(100.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = SyncGreen,
                        unfocusedBorderColor = SyncMidGray,
                        focusedTextColor     = SyncWhite,
                        unfocusedTextColor   = SyncWhite,
                        cursorColor          = SyncGreen
                    ),
                    singleLine = true,
                    suffix = { Text("min", color = SyncLightGray, fontSize = 11.sp) }
                )
            }

            // ── Meditation PM ─────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Meditation PM",
                    fontSize = 11.sp,
                    color = SyncLightGray,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = pmInput,
                    onValueChange = { pmInput = it },
                    modifier = Modifier.width(100.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = SyncGreen,
                        unfocusedBorderColor = SyncMidGray,
                        focusedTextColor     = SyncWhite,
                        unfocusedTextColor   = SyncWhite,
                        cursorColor          = SyncGreen
                    ),
                    singleLine = true,
                    suffix = { Text("min", color = SyncLightGray, fontSize = 11.sp) }
                )
            }

            Button(
                onClick = {
                    onLog(
                        caffeineInput.toDoubleOrNull() ?: 400.0,
                        amInput.toIntOrNull() ?: 0,
                        pmInput.toIntOrNull() ?: 0
                    )
                    expanded = false
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = SyncGreen)
            ) {
                Text(
                    text = if (isLogged) "Update $dateKey" else "Log $dateKey",
                    color = SyncBlack,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // ── History ───────────────────────────────────────────
        if (allCovariates.isNotEmpty()) {
            HorizontalDivider(color = SyncMidGray, thickness = 0.5.dp)
            TextButton(
                onClick = { showHistory = !showHistory },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (showHistory)
                        "Hide history"
                    else
                        "Show history (${allCovariates.size} days)",
                    color = SyncLightGray,
                    fontSize = 11.sp
                )
            }

            if (showHistory) {
                allCovariates.take(60).forEach { cov ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDateChange(cov.dateKey) }
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = cov.dateKey,
                            fontSize = 11.sp,
                            color = SyncWhite
                        )
                        Text(
                            text = "${cov.caffeineMg.toInt()}mg · " +
                                    "${cov.meditationAmMinutes}/${cov.meditationPmMinutes}m",
                            fontSize = 10.sp,
                            color = SyncLightGray
                        )
                    }
                    HorizontalDivider(color = SyncMidGray, thickness = 0.5.dp)
                }
            }
        }
    }
}
// ── Exercise Card (with timestamp + history) ──────────────────

@Composable
fun ExerciseCard(
    todaySession: ExerciseSession?,
    allSessions: List<ExerciseSession>,
    onLog: (type: String, durationMinutes: Int, startTimestamp: Long) -> Unit,
    onEditRequest: (ExerciseSession) -> Unit
) {
    val fmt = remember {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    }
    val displayFmt = remember {
        java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
    }

    var selectedType by rememberSaveable { mutableStateOf(ExerciseSession.TYPE_HIIT) }
    var durationInput by rememberSaveable { mutableStateOf("60") }
    var timestampInput by rememberSaveable {
        mutableStateOf(fmt.format(java.util.Date()))
    }
    var expanded by rememberSaveable { mutableStateOf(todaySession == null) }
    var showHistory by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SyncDarkSurface)
            .border(
                1.dp,
                if (todaySession != null) SyncGreen else SyncMagenta,
                RoundedCornerShape(12.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ── Header ────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Exercise",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (todaySession != null) SyncGreen else SyncWhite
                )
                Text(
                    text = todaySession?.let {
                        "Today ✓ — ${ExerciseSession.displayName(it.sessionType)} · " +
                                "${it.durationMinutes} min · " +
                                displayFmt.format(java.util.Date(it.startTimestamp))
                    } ?: "No session logged today",
                    fontSize = 11.sp,
                    color = SyncLightGray
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    text = if (expanded) "Hide" else "Log",
                    color = if (todaySession != null) SyncLightGray else SyncMagenta,
                    fontSize = 11.sp
                )
            }
        }

        // ── Entry form ────────────────────────────────────────
        if (expanded) {

            Text("Session type", fontSize = 11.sp, color = SyncLightGray)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ExerciseSession.ALL_TYPES.take(2).forEach { type ->
                    ExerciseTypeChip(
                        label = ExerciseSession.displayName(type),
                        selected = selectedType == type,
                        onClick = { selectedType = type },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ExerciseSession.ALL_TYPES.drop(2).forEach { type ->
                    ExerciseTypeChip(
                        label = ExerciseSession.displayName(type),
                        selected = selectedType == type,
                        onClick = { selectedType = type },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (selectedType != ExerciseSession.TYPE_REST) {
                OutlinedTextField(
                    value = durationInput,
                    onValueChange = { durationInput = it },
                    label = {
                        Text("Duration (minutes)", color = SyncLightGray, fontSize = 11.sp)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = SyncGreen,
                        unfocusedBorderColor = SyncMidGray,
                        focusedTextColor     = SyncWhite,
                        unfocusedTextColor   = SyncWhite,
                        cursorColor          = SyncGreen
                    ),
                    singleLine = true
                )
            }

            OutlinedTextField(
                value = timestampInput,
                onValueChange = { timestampInput = it },
                label = {
                    Text(
                        "Start time (yyyy-MM-dd HH:mm)",
                        color = SyncLightGray,
                        fontSize = 11.sp
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = SyncGreen,
                    unfocusedBorderColor = SyncMidGray,
                    focusedTextColor     = SyncWhite,
                    unfocusedTextColor   = SyncWhite,
                    cursorColor          = SyncGreen
                ),
                singleLine = true
            )

            Button(
                onClick = {
                    val mins = if (selectedType == ExerciseSession.TYPE_REST) 0
                    else durationInput.toIntOrNull() ?: 0
                    val ts = try {
                        fmt.parse(timestampInput)?.time ?: System.currentTimeMillis()
                    } catch (e: Exception) {
                        System.currentTimeMillis()
                    }
                    onLog(selectedType, mins, ts)
                    // reset for next entry — keeps bulk entry fast
                    timestampInput = fmt.format(java.util.Date())
                    durationInput = "60"
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = SyncGreen)
            ) {
                Text("Log Session", color = SyncBlack, fontWeight = FontWeight.Bold)
            }
        }

        // ── History ───────────────────────────────────────────
        if (allSessions.isNotEmpty()) {
            HorizontalDivider(color = SyncMidGray, thickness = 0.5.dp)
            TextButton(
                onClick = { showHistory = !showHistory },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (showHistory)
                        "Hide history"
                    else
                        "Show history (${allSessions.size} sessions)",
                    color = SyncLightGray,
                    fontSize = 11.sp
                )
            }

            if (showHistory) {
                allSessions.take(60).forEach { session ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEditRequest(session) }
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = displayFmt.format(
                                    java.util.Date(session.startTimestamp)
                                ),
                                fontSize = 11.sp,
                                color = SyncWhite
                            )
                            Text(
                                text = ExerciseSession.displayName(session.sessionType),
                                fontSize = 10.sp,
                                color = if (session.isHiit) SyncGreen else SyncLightGray
                            )
                        }
                        Text(
                            text = if (session.durationMinutes > 0)
                                "${session.durationMinutes} min" else "—",
                            fontSize = 11.sp,
                            color = SyncLightGray
                        )
                    }
                    HorizontalDivider(color = SyncMidGray, thickness = 0.5.dp)
                }
            }
        }
    }
}

// ── Exercise edit dialog ──────────────────────────────────────

@Composable
fun ExerciseEditDialog(
    session: ExerciseSession,
    onSave: (ExerciseSession) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val fmt = remember {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    }

    var selectedType by rememberSaveable { mutableStateOf(session.sessionType) }
    var durationInput by rememberSaveable {
        mutableStateOf(session.durationMinutes.toString())
    }
    var timestampInput by rememberSaveable {
        mutableStateOf(fmt.format(java.util.Date(session.startTimestamp)))
    }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SyncDarkSurface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Edit Exercise Session",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = SyncGreen
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ExerciseSession.ALL_TYPES.take(2).forEach { type ->
                    ExerciseTypeChip(
                        label = ExerciseSession.displayName(type),
                        selected = selectedType == type,
                        onClick = { selectedType = type },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ExerciseSession.ALL_TYPES.drop(2).forEach { type ->
                    ExerciseTypeChip(
                        label = ExerciseSession.displayName(type),
                        selected = selectedType == type,
                        onClick = { selectedType = type },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            OutlinedTextField(
                value = durationInput,
                onValueChange = { durationInput = it },
                label = {
                    Text("Duration (minutes)", color = SyncLightGray, fontSize = 11.sp)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = SyncGreen,
                    unfocusedBorderColor = SyncMidGray,
                    focusedTextColor     = SyncWhite,
                    unfocusedTextColor   = SyncWhite,
                    cursorColor          = SyncGreen
                ),
                singleLine = true
            )

            OutlinedTextField(
                value = timestampInput,
                onValueChange = { timestampInput = it },
                label = {
                    Text(
                        "Start time (yyyy-MM-dd HH:mm)",
                        color = SyncLightGray,
                        fontSize = 11.sp
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = SyncGreen,
                    unfocusedBorderColor = SyncMidGray,
                    focusedTextColor     = SyncWhite,
                    unfocusedTextColor   = SyncWhite,
                    cursorColor          = SyncGreen
                ),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = SolidColor(SyncMidGray)
                    )
                ) {
                    Text("Cancel", color = SyncLightGray)
                }
                Button(
                    onClick = {
                        val mins = if (selectedType == ExerciseSession.TYPE_REST) 0
                        else durationInput.toIntOrNull()
                            ?: session.durationMinutes
                        val ts = try {
                            fmt.parse(timestampInput)?.time ?: session.startTimestamp
                        } catch (e: Exception) {
                            session.startTimestamp
                        }
                        onSave(
                            session.copy(
                                sessionType     = selectedType,
                                isHiit          = selectedType == ExerciseSession.TYPE_HIIT,
                                durationMinutes = mins,
                                startTimestamp  = ts
                            )
                        )
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = SyncGreen)
                ) {
                    Text("Save", color = SyncBlack, fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider(color = SyncMidGray, thickness = 0.5.dp)

            if (!confirmDelete) {
                TextButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Delete this session", color = SyncError, fontSize = 12.sp)
                }
            } else {
                Text(
                    text = "Delete permanently?",
                    fontSize = 12.sp,
                    color = SyncError
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { confirmDelete = false },
                        modifier = Modifier.weight(1f),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = SolidColor(SyncMidGray)
                        )
                    ) {
                        Text("Keep", color = SyncLightGray)
                    }
                    Button(
                        onClick = { onDelete(session.id) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = SyncError)
                    ) {
                        Text("Delete", color = SyncWhite, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ── Type chip ─────────────────────────────────────────────────

@Composable
fun ExerciseTypeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) SyncGreen else SyncDarkGray)
            .border(
                1.dp,
                if (selected) SyncGreen else SyncMidGray,
                RoundedCornerShape(8.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        TextButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp)
        ) {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) SyncBlack else SyncLightGray,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ── Dexcom Connect Card ───────────────────────────────────────

@Composable
fun DexcomConnectCard(
    isConnected: Boolean,
    glucoseCount: Int,
    onConnectClick: () -> Unit,
    onSyncClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SyncDarkSurface)
            .border(
                1.dp,
                if (isConnected) SyncGreen else SyncMagenta,
                RoundedCornerShape(12.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Dexcom G7",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isConnected) SyncGreen else SyncWhite
                )
                Text(
                    text = if (isConnected) "$glucoseCount readings synced"
                    else "Not connected",
                    fontSize = 11.sp,
                    color = SyncLightGray
                )
            }
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        if (isConnected) SyncGreen else SyncMagenta,
                        shape = RoundedCornerShape(6.dp)
                    )
            )
        }

        if (!isConnected) {
            Button(
                onClick = onConnectClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = SyncMagenta)
            ) {
                Text(
                    text = "Connect Dexcom G7",
                    color = SyncBlack,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onSyncClick,
                    modifier = Modifier.weight(1f),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = SolidColor(SyncGreen)
                    )
                ) {
                    Text("Sync Now", color = SyncGreen)
                }
                OutlinedButton(
                    onClick = onConnectClick,
                    modifier = Modifier.weight(1f),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = SolidColor(SyncLightGray)
                    )
                ) {
                    Text("Reconnect", color = SyncLightGray)
                }
            }
        }
    }
}

// ── Keto-Mojo Card ────────────────────────────────────────────

@Composable
fun KetoMojoCard(
    bleState: KetoMojoManager.BleState,
    latestReading: KetoneReading?,
    onScanClick: () -> Unit,
    onDisconnectClick: () -> Unit
) {
    val isConnected = bleState is KetoMojoManager.BleState.Connected ||
            bleState is KetoMojoManager.BleState.ReadingReceived
    val isScanning = bleState is KetoMojoManager.BleState.Scanning
    val isConnecting = bleState is KetoMojoManager.BleState.Connecting

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SyncDarkSurface)
            .border(
                1.dp,
                when {
                    isConnected -> SyncGreen
                    isScanning || isConnecting -> SyncMagenta
                    else -> SyncMagenta
                },
                RoundedCornerShape(12.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Keto-Mojo GK+",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isConnected) SyncGreen else SyncWhite
                )
                Text(
                    text = when (bleState) {
                        is KetoMojoManager.BleState.Idle -> "Not connected"
                        is KetoMojoManager.BleState.Scanning -> "Scanning..."
                        is KetoMojoManager.BleState.DeviceFound -> "Device found"
                        is KetoMojoManager.BleState.Connecting -> "Connecting..."
                        is KetoMojoManager.BleState.Connected -> "Connected"
                        is KetoMojoManager.BleState.ReadingReceived -> "Reading received"
                        is KetoMojoManager.BleState.Disconnected -> "Disconnected"
                        is KetoMojoManager.BleState.Error -> bleState.message
                    },
                    fontSize = 11.sp,
                    color = SyncLightGray
                )
            }
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        if (isConnected) SyncGreen else SyncMagenta,
                        shape = RoundedCornerShape(6.dp)
                    )
            )
        }

        latestReading?.let { reading ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SyncDarkGray)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "%.1f".format(reading.betaHydroxybutyrate),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = ketoneColor(reading.betaHydroxybutyrate)
                    )
                    Text(text = "mmol/L BHB", fontSize = 10.sp, color = SyncLightGray)
                }
                reading.glucoseMgDl?.let { glucose ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = glucose.toString(),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = glucoseColor(glucose)
                        )
                        Text(text = "mg/dL Glucose", fontSize = 10.sp, color = SyncLightGray)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onScanClick,
                modifier = Modifier.weight(1f),
                enabled = !isScanning && !isConnecting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) SyncDarkGray else SyncMagenta,
                    disabledContainerColor = SyncMidGray
                )
            ) {
                Text(
                    text = when {
                        isScanning -> "Scanning..."
                        isConnecting -> "Connecting..."
                        isConnected -> "Scan Again"
                        else -> "Connect Meter"
                    },
                    color = if (isConnected) SyncLightGray else SyncBlack,
                    fontWeight = FontWeight.Bold
                )
            }
            if (isConnected) {
                OutlinedButton(
                    onClick = onDisconnectClick,
                    modifier = Modifier.weight(1f),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = SolidColor(SyncLightGray)
                    )
                ) {
                    Text("Disconnect", color = SyncLightGray)
                }
            }
        }
    }
}

// ── Health Connect Card ───────────────────────────────────────

@Composable
fun HealthConnectCard(
    hcState: HealthConnectManager.HcState,
    lastSyncTime: Long?,
    biosignalCount: Int,
    sleepCount: Int,
    onSyncClick: () -> Unit,
    onSyncSleepClick: () -> Unit
) {
    val isReady = hcState is HealthConnectManager.HcState.Ready


    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SyncDarkSurface)
            .border(
                1.dp,
                if (isReady) SyncGreen else SyncMagenta,
                RoundedCornerShape(12.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Galaxy Watch 7",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isReady) SyncGreen else SyncWhite
                )
                Text(
                    text = when (hcState) {
                        is HealthConnectManager.HcState.Unavailable ->
                            "Health Connect unavailable"
                        is HealthConnectManager.HcState.NotInstalled ->
                            "Health Connect needs update"
                        is HealthConnectManager.HcState.PermissionsRequired ->
                            "Permissions required"
                        is HealthConnectManager.HcState.Ready ->
                            "$biosignalCount biosignals · $sleepCount sleep sessions"
                        is HealthConnectManager.HcState.Error -> hcState.message
                    },
                    fontSize = 11.sp,
                    color = SyncLightGray
                )
                lastSyncTime?.let {
                    val mins = ((System.currentTimeMillis() - it) / 60000).toInt()
                    Text(
                        text = "Last sync: ${if (mins < 1) "just now" else "$mins min ago"}",
                        fontSize = 10.sp,
                        color = SyncLightGray
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        if (isReady) SyncGreen else SyncMidGray,
                        shape = RoundedCornerShape(6.dp)
                    )
            )
        }

        if (biosignalCount > 0 || sleepCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SyncDarkGray)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = biosignalCount.toString(),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = SyncGreen
                    )
                    Text(text = "Biosignals", fontSize = 10.sp, color = SyncLightGray)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = sleepCount.toString(),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = SyncGreen
                    )
                    Text(text = "Sleep Sessions", fontSize = 10.sp, color = SyncLightGray)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onSyncClick,
                modifier = Modifier.weight(1f),
                enabled = isReady,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SyncGreen,
                    disabledContainerColor = SyncMidGray
                )
            ) {
                Text(
                    text = "Sync Biosignals",
                    color = SyncBlack,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
            Button(
                onClick = onSyncSleepClick,
                modifier = Modifier.weight(1f),
                enabled = isReady,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SyncDarkGray,
                    disabledContainerColor = SyncMidGray
                )
            ) {
                Text(
                    text = "Sync Sleep",
                    color = if (isReady) SyncGreen else SyncLightGray,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ── Helper functions ──────────────────────────────────────────

fun glucoseColor(mgDl: Int): Color = when {
    mgDl < 70   -> Color(0xFFFF3D3D)
    mgDl <= 140 -> Color(0xFF00FF77)
    mgDl <= 180 -> Color(0xFFFFD600)
    else        -> Color(0xFFFF3D3D)
}

fun ketoneColor(mmol: Double): Color = when {
    mmol < 0.5  -> Color(0xFF9E9E9E)
    mmol <= 3.0 -> Color(0xFF00FF77)
    mmol <= 5.0 -> Color(0xFFFFD600)
    else        -> Color(0xFFFF3D3D)
}