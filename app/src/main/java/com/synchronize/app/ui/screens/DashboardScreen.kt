package com.synchronize.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synchronize.app.data.db.entity.GlucoseReading
import com.synchronize.app.data.db.entity.KetoneReading
import com.synchronize.app.data.db.entity.BiosignalSample
import com.synchronize.app.ui.theme.*
import com.synchronize.app.ui.viewmodel.MainViewModel
import androidx.compose.ui.platform.LocalContext
import com.synchronize.app.util.DexcomAuthManager
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import android.util.Log


@Composable
fun DashboardScreen(viewModel: MainViewModel) {

    val latestGlucose by viewModel.latestGlucose.collectAsState()
    val latestKetone by viewModel.latestKetone.collectAsState()
    val latestBiosignal by viewModel.latestBiosignal.collectAsState()
    val dataQuality by viewModel.dataQuality.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val voiceTranscription by viewModel.voiceTranscription.collectAsState()
    val pendingMeal by viewModel.pendingMealEntry.collectAsState()
    val isDexcomConnected by viewModel.isDexcomConnected.collectAsState()
    val glucoseSyncCount by viewModel.glucoseSyncCount.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
            text = "Ketone Prediction Research Dashboard",
            fontSize = 12.sp,
            color = SyncLightGray,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(4.dp))

        // ── Summary cards row ─────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SummaryCard(
                modifier = Modifier.weight(1f),
                label = "Glucose",
                value = latestGlucose?.glucoseMgDl?.toString() ?: "--",
                unit = "mg/dL",
                valueColor = latestGlucose?.let { glucoseColor(it.glucoseMgDl) } ?: SyncLightGray,
                subtitle = latestGlucose?.trend ?: "No data"
            )
            SummaryCard(
                modifier = Modifier.weight(1f),
                label = "Ketones",
                value = latestKetone?.betaHydroxybutyrate?.let { "%.1f".format(it) } ?: "--",
                unit = "mmol/L",
                valueColor = latestKetone?.let { ketoneColor(it.betaHydroxybutyrate) } ?: SyncLightGray,
                subtitle = latestKetone?.readingContext ?: "No data"
            )
            SummaryCard(
                modifier = Modifier.weight(1f),
                label = "Heart Rate",
                value = latestBiosignal?.heartRateBpm?.toString() ?: "--",
                unit = "bpm",
                valueColor = SyncGreen,
                subtitle = latestBiosignal?.let { "HRV ${it.hrvRmssd?.let { r -> "%.0f".format(r) } ?: "--"} ms" } ?: "No data"
            )
        }

        // ── Voice logging card ────────────────────────────────
        VoiceLoggingCard(
            isListening = isListening,
            transcription = voiceTranscription,
            pendingMeal = pendingMeal?.let {
                "C:${it.netCarbGrams}g F:${it.fatGrams}g P:${it.proteinGrams}g " +
                        "Fib:${it.fiberGrams}g | ${it.calories} cal"
            },
            onMicClick = {
                viewModel.setListening(!isListening)
                if (!isListening) {
                    // Voice recognition will be wired here in next step
                    viewModel.setVoiceTranscription("Listening...")
                }
            },
            onConfirm = { viewModel.confirmPendingMealEntry() },
            onDiscard = { viewModel.discardPendingMealEntry() }
        )

        // ── Data quality card ─────────────────────────────────
        if (dataQuality.isNotEmpty()) {
            DataQualityCard(dataQuality = dataQuality)
        }

        DexcomConnectCard(
            isConnected = isDexcomConnected,
            glucoseCount = glucoseSyncCount,
            onConnectClick = {
                DexcomAuthManager(context).launchAuthFlow()
            },
            onSyncClick = { viewModel.refreshLatestReadings() }
        )
        val scope = rememberCoroutineScope()

        DexcomManualCodeEntry(
            onCodeSubmit = { code ->
                android.util.Log.d("DexcomAuth", "Code received: ${code.take(8)}")
                viewModel.setDexcomConnected(true)
            }
        )


        // ── Status indicators ─────────────────────────────────
        StreamStatusCard(
            glucoseCount = dataQuality["glucose"] ?: 0,
            ketoneCount = dataQuality["ketones"] ?: 0,
            biosignalCount = dataQuality["biosignals"] ?: 0,
            sleepCount = dataQuality["sleep"] ?: 0,
            mealCount = dataQuality["meals"] ?: 0
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
            .border(1.dp, SyncMidGray, RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = label, fontSize = 11.sp, color = SyncLightGray)
        Text(text = value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = valueColor)
        Text(text = unit, fontSize = 10.sp, color = SyncLightGray)
        Text(text = subtitle, fontSize = 9.sp, color = SyncLightGray, textAlign = TextAlign.Center)
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
            .border(1.dp, if (isListening) SyncGreen else SyncMidGray, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "Voice Macro Entry", fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold, color = SyncGreen)
        Text(
            text = "Format: calories / fat / protein / net carbs / fiber",
            fontSize = 11.sp, color = SyncLightGray
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
                        brush = androidx.compose.ui.graphics.SolidColor(SyncMagenta)
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
            .border(1.dp, SyncMidGray, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = "Data Collection", fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold, color = SyncGreen)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            dataQuality.forEach { (key, count) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = count.toString(), fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, color = SyncGreen)
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
            .border(1.dp, SyncMidGray, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(text = "Stream Status", fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold, color = SyncGreen)
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
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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

// ── Helper functions ──────────────────────────────────────────

fun glucoseColor(mgDl: Int): Color = when {
    mgDl < 70  -> GlucoseLow
    mgDl <= 140 -> GlucoseNormal
    mgDl <= 180 -> GlucoseHigh
    else        -> GlucoseVeryHigh
}

fun ketoneColor(mmol: Double): Color = when {
    mmol < 0.5  -> KetoneSubKetosis
    mmol <= 3.0 -> KetoneNutritional
    mmol <= 5.0 -> KetoneElevated
    else        -> KetoneHigh
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
                    text = if (isConnected)
                        "$glucoseCount readings synced"
                    else
                        "Not connected",
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
                colors = ButtonDefaults.buttonColors(
                    containerColor = SyncMagenta
                )
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
                        brush = androidx.compose.ui.graphics.SolidColor(SyncGreen)
                    )
                ) {
                    Text("Sync Now", color = SyncGreen)
                }
                OutlinedButton(
                    onClick = onConnectClick,
                    modifier = Modifier.weight(1f),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(SyncLightGray)
                    )
                ) {
                    Text("Reconnect", color = SyncLightGray)
                }
            }
        }
    }
}
// ── Temporary Dev Tool: Manual Auth Code Entry ────────────────
// Remove before production deployment

@Composable
fun DexcomManualCodeEntry(
    onCodeSubmit: (String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SyncDarkSurface)
            .border(1.dp, SyncMidGray, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Dev: Manual Auth Code",
                fontSize = 12.sp,
                color = SyncLightGray
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    text = if (expanded) "Hide" else "Show",
                    color = SyncLightGray,
                    fontSize = 11.sp
                )
            }
        }

        if (expanded) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = {
                    Text("Paste auth code here", color = SyncLightGray,
                        fontSize = 11.sp)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SyncGreen,
                    unfocusedBorderColor = SyncMidGray,
                    focusedTextColor = SyncWhite,
                    unfocusedTextColor = SyncWhite,
                    cursorColor = SyncGreen
                ),
                singleLine = false,
                maxLines = 3
            )

            Button(
                onClick = {
                    if (code.isNotBlank()) {
                        onCodeSubmit(code.trim())
                        code = ""
                        expanded = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SyncDarkGray
                )
            ) {
                Text("Submit Code", color = SyncGreen)
            }
        }
    }
}