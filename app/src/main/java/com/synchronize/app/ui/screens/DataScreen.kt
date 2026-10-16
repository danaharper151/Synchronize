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
import com.synchronize.app.ui.theme.*
import com.synchronize.app.ui.viewmodel.MainViewModel

@Composable
fun DataScreen(viewModel: MainViewModel) {

    val allGlucose by viewModel.allGlucoseReadings.collectAsState()
    val allKetones by viewModel.allKetoneReadings.collectAsState()
    val allBiosignals by viewModel.allBiosignalSamples.collectAsState()
    val allSleep by viewModel.allSleepSessions.collectAsState()
    val allMeals by viewModel.allMealEntries.collectAsState()

    // Protocol targets for a 30-day collection period
    val targetKetones    = 150    // 5 scheduled contexts × 30 days
    val targetMeals      = 90     // 3/day × 30 days
    val targetSleep      = 30     // 1/night × 30 nights
    val targetGlucose    = 8640   // 288 EGVs/day (5-min) × 30 days
    val targetBiosignals = 43200  // 1440/day (1-min) × 30 days

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SyncBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 32.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Data Collection",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = SyncGreen,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        // ── Progress against protocol targets ─────────────────
        DataCard {
            Text(
                text = "30-Day Protocol Progress",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = SyncGreen
            )

            DataProgressRow("Blood Ketones", allKetones.size, targetKetones, "readings")
            DataProgressRow("Meal Entries", allMeals.size, targetMeals, "entries")
            DataProgressRow("Sleep Sessions", allSleep.size, targetSleep, "nights")
            DataProgressRow("CGM Readings", allGlucose.size, targetGlucose, "readings")
            DataProgressRow("Biosignal Samples", allBiosignals.size, targetBiosignals, "samples")
        }

        // ── Stream summary ────────────────────────────────────
        DataCard {
            Text(
                text = "Stream Summary",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = SyncGreen
            )

            DataSummaryRow("Dexcom G7 (CGM)", allGlucose.size, "readings")
            DataSummaryRow("Keto-Mojo GK+", allKetones.size, "readings")
            DataSummaryRow("Galaxy Watch 7", allBiosignals.size, "samples")
            DataSummaryRow("Sleep Sessions", allSleep.size, "sessions")
            DataSummaryRow("Meal Entries", allMeals.size, "entries")

            val totalRecords = allGlucose.size + allKetones.size +
                    allBiosignals.size + allSleep.size + allMeals.size
            HorizontalDivider(color = SyncMidGray)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Total records",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SyncWhite
                )
                Text(
                    text = totalRecords.toString(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = SyncGreen
                )
            }
        }

        // ── Analysis hand-off ─────────────────────────────────
        DataCard {
            Text(
                text = "Analysis Pipeline",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = SyncGreen
            )
            Text(
                text = "Records stay on-device in the app database. For analysis, the " +
                        "database is extracted over adb and processed by the companion " +
                        "Python pipeline (synchronize-pipeline), which builds the " +
                        "multi-resolution feature windows used for model training.",
                fontSize = 12.sp,
                color = SyncLightGray
            )
        }
    }
}

// ── Card wrapper ──────────────────────────────────────────────

@Composable
private fun DataCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SyncDarkSurface)
            .border(1.dp, SyncMagenta, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

// ── Progress row with linear indicator ───────────────────────

@Composable
fun DataProgressRow(
    label: String,
    current: Int,
    target: Int,
    unit: String
) {
    val progress = (current.toFloat() / target.toFloat()).coerceIn(0f, 1f)
    val pct = (progress * 100).toInt()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, fontSize = 12.sp, color = SyncWhite)
            Text(
                text = "$current / $target $unit ($pct%)",
                fontSize = 11.sp,
                color = if (progress >= 1f) SyncGreen else SyncLightGray
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = if (progress >= 1f) SyncGreen else SyncMagenta,
            trackColor = SyncMidGray
        )
    }
}

// ── Summary row ───────────────────────────────────────────────

@Composable
fun DataSummaryRow(label: String, count: Int, unit: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        if (count > 0) SyncGreen else SyncMidGray,
                        RoundedCornerShape(4.dp)
                    )
            )
            Text(text = label, fontSize = 12.sp, color = SyncWhite)
        }
        Text(
            text = if (count > 0) "$count $unit" else "No data",
            fontSize = 12.sp,
            color = if (count > 0) SyncGreen else SyncLightGray
        )
    }
}