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
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MealScreen(viewModel: MainViewModel) {

    val mealEntries by viewModel.allMealEntries.collectAsState()

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
            text = "Meal Log",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = SyncGreen,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        // ── Daily summary card ────────────────────────────────
        if (mealEntries.isNotEmpty()) {
            val todayStart = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }.timeInMillis

            val todayEntries = mealEntries.filter {
                it.deviceTimestamp >= todayStart
            }

            if (todayEntries.isNotEmpty()) {
                val totalCal  = todayEntries.sumOf { it.calories }
                val totalFat  = todayEntries.sumOf { it.fatGrams }
                val totalProt = todayEntries.sumOf { it.proteinGrams }
                val totalNC   = todayEntries.sumOf { it.netCarbGrams }
                val totalFib  = todayEntries.sumOf { it.fiberGrams }
                val avgKR     = todayEntries.map { it.ketogenicRatio }.average()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SyncDarkSurface)
                        .border(1.dp, SyncGreen, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Today's Totals",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SyncGreen
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        MacroSummaryItem("Calories", "$totalCal")
                        MacroSummaryItem("Fat", "${totalFat.toInt()}g")
                        MacroSummaryItem("Protein", "${totalProt.toInt()}g")
                        MacroSummaryItem("Net Carbs", "${totalNC.toInt()}g")
                        MacroSummaryItem("Fiber", "${totalFib.toInt()}g")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Avg ketogenic ratio: ${"%.1f".format(avgKR)}",
                            fontSize = 12.sp,
                            color = SyncLightGray
                        )
                        Text(
                            text = "${todayEntries.size} meals logged",
                            fontSize = 12.sp,
                            color = SyncLightGray
                        )
                    }
                }
            }
        }

        // ── Meal history ──────────────────────────────────────
        if (mealEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SyncDarkSurface)
                    .border(1.dp, SyncMidGray, RoundedCornerShape(12.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No meals logged yet\nUse the voice entry on the Dashboard",
                    fontSize = 13.sp,
                    color = SyncLightGray,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SyncDarkSurface)
                    .border(1.dp, SyncMidGray, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Recent Meals (${mealEntries.size} total)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SyncGreen
                )

                mealEntries.take(20).forEach { entry ->
                    val formatter = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                    val time = formatter.format(Date(entry.deviceTimestamp))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = time, fontSize = 11.sp, color = SyncLightGray)
                            Text(
                                text = "${entry.calories} cal",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SyncWhite
                            )
                        }
                        Text(
                            text = "F:${entry.fatGrams.toInt()}g  " +
                                    "P:${entry.proteinGrams.toInt()}g  " +
                                    "NC:${entry.netCarbGrams.toInt()}g  " +
                                    "Fib:${entry.fiberGrams.toInt()}g  " +
                                    "KR:${"%.1f".format(entry.ketogenicRatio)}",
                            fontSize = 11.sp,
                            color = SyncLightGray
                        )
                        entry.rawTranscription?.let {
                            Text(
                                text = "\"$it\"",
                                fontSize = 10.sp,
                                color = SyncMidGray
                            )
                        }
                    }
                    HorizontalDivider(color = SyncMidGray, thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
fun MacroSummaryItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = SyncGreen
        )
        Text(text = label, fontSize = 9.sp, color = SyncLightGray)
    }
}