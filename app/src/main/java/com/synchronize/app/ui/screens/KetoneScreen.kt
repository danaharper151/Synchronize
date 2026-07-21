package com.synchronize.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synchronize.app.data.db.entity.KetoneReading
import com.synchronize.app.ui.theme.*
import com.synchronize.app.ui.viewmodel.MainViewModel
import com.synchronize.app.util.Constants
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun KetoneScreen(viewModel: MainViewModel) {

    val ketoneReadings by viewModel.allKetoneReadings.collectAsState()
    val latestKetone by viewModel.latestKetone.collectAsState()

    // Context selector state
    var selectedContext by remember {
        mutableStateOf(Constants.CONTEXT_FASTING)
    }
    var manualBhb by remember { mutableStateOf("") }
    var manualGlucose by remember { mutableStateOf("") }
    var manualSystolic by remember { mutableStateOf("") }
    var manualDiastolic by remember { mutableStateOf("") }
    var showConfirmation by remember { mutableStateOf(false) }

    val contexts = listOf(
        Constants.CONTEXT_FASTING,
        Constants.CONTEXT_PRE_LUNCH,
        Constants.CONTEXT_POST_LUNCH,
        Constants.CONTEXT_PRE_DINNER,
        Constants.CONTEXT_PRE_SLEEP
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SyncBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 32.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Header ────────────────────────────────────────────
        Text(
            text = "Blood Ketones",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = SyncGreen,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        // ── Latest reading card ───────────────────────────────
        latestKetone?.let { reading ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SyncDarkSurface)
                    .border(1.dp, SyncGreen, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(text = "Latest Reading", fontSize = 12.sp, color = SyncLightGray)
                Text(
                    text = "%.2f mmol/L".format(reading.betaHydroxybutyrate),
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    color = ketoneStatusColor(reading.betaHydroxybutyrate)
                )
                Text(
                    text = ketoneStatusLabel(reading.betaHydroxybutyrate),
                    fontSize = 14.sp,
                    color = ketoneStatusColor(reading.betaHydroxybutyrate)
                )
                reading.readingContext?.let {
                    Text(text = it.replace("_", " "), fontSize = 11.sp, color = SyncLightGray)
                }
                reading.glucoseMgDl?.let {
                    Text(
                        text = "Paired glucose: $it mg/dL",
                        fontSize = 11.sp,
                        color = SyncLightGray
                    )
                }
            }
        } ?: run {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SyncDarkSurface)
                    .border(1.dp, SyncMagenta, RoundedCornerShape(12.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No ketone readings yet\nConnect Keto-Mojo to begin",
                    fontSize = 13.sp,
                    color = SyncMagenta,
                    textAlign = TextAlign.Center
                )
            }
        }

        // ── Manual entry card ─────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SyncDarkSurface)
                .border(1.dp, SyncMagenta, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Log Reading",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = SyncGreen
            )

            // Context selector
            Text(text = "Reading context:", fontSize = 12.sp, color = SyncLightGray)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                contexts.take(3).forEach { context ->
                    ContextChip(
                        label = context.replace("_", "\n"),
                        selected = selectedContext == context,
                        onClick = { selectedContext = context },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                contexts.drop(3).forEach { context ->
                    ContextChip(
                        label = context.replace("_", "\n"),
                        selected = selectedContext == context,
                        onClick = { selectedContext = context },
                        modifier = Modifier.weight(1f)
                    )
                }
                // Spacer to balance the row
                Spacer(modifier = Modifier.weight(1f))
            }

            // BHB value entry
            OutlinedTextField(
                value = manualBhb,
                onValueChange = { manualBhb = it },
                label = { Text("BHB (mmol/L)", color = SyncLightGray, fontSize = 12.sp) },
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

            // Fasting-only fields: paired glucose + manual BP
            if (selectedContext == Constants.CONTEXT_FASTING) {
                OutlinedTextField(
                    value = manualGlucose,
                    onValueChange = { manualGlucose = it },
                    label = {
                        Text(
                            "Paired glucose (mg/dL) — fasting CGM validation",
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

                // BP cuff reading
                Text(
                    text = "Manual BP cuff reading (fasting, daily)",
                    fontSize = 11.sp,
                    color = SyncLightGray
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = manualSystolic,
                        onValueChange = { manualSystolic = it },
                        label = {
                            Text("Systolic", color = SyncLightGray, fontSize = 11.sp)
                        },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = SyncGreen,
                            unfocusedBorderColor = SyncMidGray,
                            focusedTextColor     = SyncWhite,
                            unfocusedTextColor   = SyncWhite,
                            cursorColor          = SyncGreen
                        ),
                        singleLine = true,
                        suffix = { Text("mmHg", color = SyncLightGray, fontSize = 10.sp) }
                    )
                    OutlinedTextField(
                        value = manualDiastolic,
                        onValueChange = { manualDiastolic = it },
                        label = {
                            Text("Diastolic", color = SyncLightGray, fontSize = 11.sp)
                        },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = SyncGreen,
                            unfocusedBorderColor = SyncMidGray,
                            focusedTextColor     = SyncWhite,
                            unfocusedTextColor   = SyncWhite,
                            cursorColor          = SyncGreen
                        ),
                        singleLine = true,
                        suffix = { Text("mmHg", color = SyncLightGray, fontSize = 10.sp) }
                    )
                }
            }

            Button(
                onClick = {
                    val bhb = manualBhb.toDoubleOrNull()
                    if (bhb != null) {
                        val now = System.currentTimeMillis()
                        val bpNote = if (selectedContext == Constants.CONTEXT_FASTING &&
                            manualSystolic.isNotBlank() && manualDiastolic.isNotBlank())
                            "BP:${manualSystolic}/${manualDiastolic}mmHg" else null
                        viewModel.insertKetoneReading(
                            KetoneReading(
                                deviceTimestamp     = now,
                                receiveTimestamp    = now,
                                betaHydroxybutyrate = bhb,
                                glucoseMgDl         = manualGlucose.toIntOrNull(),
                                source              = "MANUAL",
                                readingContext      = selectedContext,
                                notes               = bpNote
                            )
                        )
                        manualBhb = ""
                        manualGlucose = ""
                        manualSystolic = ""
                        manualDiastolic = ""
                        showConfirmation = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = manualBhb.toDoubleOrNull() != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor         = SyncGreen,
                    disabledContainerColor = SyncMidGray
                )
            ) {
                Text(
                    text = "Log Ketone Reading",
                    color = SyncBlack,
                    fontWeight = FontWeight.Bold
                )
            }

            if (showConfirmation) {
                Text(
                    text = "✓ Reading logged successfully",
                    fontSize = 12.sp,
                    color = SyncGreen
                )
                LaunchedEffect(showConfirmation) {
                    kotlinx.coroutines.delay(2000)
                    showConfirmation = false
                }
            }
        }

        // ── Reading history ───────────────────────────────────
        if (ketoneReadings.isNotEmpty()) {
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
                    text = "Recent Readings (${ketoneReadings.size} total)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SyncGreen
                )
                ketoneReadings.take(10).forEach { reading ->
                    KetoneReadingRow(reading = reading)
                }
            }
        }
    }
}

// ── Context chip ──────────────────────────────────────────────

@Composable
fun ContextChip(
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
            )
            .then(Modifier.padding(vertical = 8.dp, horizontal = 4.dp)),
        contentAlignment = Alignment.Center
    ) {
        TextButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(0.dp)
        ) {
            Text(
                text = label,
                fontSize = 9.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) SyncBlack else SyncLightGray,
                textAlign = TextAlign.Center,
                lineHeight = 12.sp
            )
        }
    }
}

// ── Reading history row ───────────────────────────────────────

@Composable
fun KetoneReadingRow(reading: KetoneReading) {
    val formatter = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    val time = formatter.format(Date(reading.deviceTimestamp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = time, fontSize = 11.sp, color = SyncLightGray)
            reading.readingContext?.let {
                Text(
                    text = it.replace("_", " "),
                    fontSize = 10.sp,
                    color = SyncLightGray
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            reading.glucoseMgDl?.let {
                Text(text = "$it mg/dL", fontSize = 11.sp, color = SyncLightGray)
            }
            Text(
                text = "%.2f".format(reading.betaHydroxybutyrate),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = ketoneStatusColor(reading.betaHydroxybutyrate)
            )
            Text(text = "mmol/L", fontSize = 10.sp, color = SyncLightGray)
        }
    }
    HorizontalDivider(color = SyncMidGray, thickness = 0.5.dp)
}

// ── Helper functions ──────────────────────────────────────────

fun ketoneStatusColor(mmol: Double) = when {
    mmol < 0.5  -> androidx.compose.ui.graphics.Color(0xFF9E9E9E)
    mmol <= 3.0 -> androidx.compose.ui.graphics.Color(0xFF00FF77)
    mmol <= 5.0 -> androidx.compose.ui.graphics.Color(0xFFFFD600)
    else        -> androidx.compose.ui.graphics.Color(0xFFFF3D3D)
}

fun ketoneStatusLabel(mmol: Double) = when {
    mmol < 0.5  -> "Sub-ketosis"
    mmol <= 0.5 -> "Light ketosis"
    mmol <= 1.5 -> "Nutritional ketosis"
    mmol <= 3.0 -> "Optimal ketosis"
    mmol <= 5.0 -> "Elevated — monitor"
    else        -> "High — seek guidance"
}