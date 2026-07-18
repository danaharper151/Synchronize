package com.synchronize.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.synchronize.app.data.db.entity.MealEntry
import com.synchronize.app.ui.theme.*
import com.synchronize.app.ui.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MealScreen(viewModel: MainViewModel) {

    val mealEntries by viewModel.allMealEntries.collectAsState()
    var entryToEdit by remember { mutableStateOf<MealEntry?>(null) }
    var entryToDelete by remember { mutableStateOf<MealEntry?>(null) }

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
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val todayEntries = mealEntries.filter { it.deviceTimestamp >= todayStart }

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
                            text = "Avg KR: ${"%.1f".format(avgKR)}",
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
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "All Entries (${mealEntries.size} total)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SyncGreen
                )

                mealEntries.forEach { entry ->
                    MealEntryRow(
                        entry = entry,
                        onEdit = { entryToEdit = entry },
                        onDelete = { entryToDelete = entry }
                    )
                }
            }
        }
    }

    // ── Edit dialog ───────────────────────────────────────────
    entryToEdit?.let { entry ->
        MealEditDialog(
            entry = entry,
            onSave = { updated ->
                viewModel.updateMealEntry(updated)
                entryToEdit = null
            },
            onDismiss = { entryToEdit = null }
        )
    }

    // ── Delete confirmation dialog ────────────────────────────
    entryToDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { entryToDelete = null },
            containerColor = SyncDarkSurface,
            title = {
                Text("Delete Entry?", color = SyncWhite, fontWeight = FontWeight.Bold)
            },
            text = {
                val formatter = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                Text(
                    text = "Delete the ${entry.mealLabel ?: "meal"} entry from " +
                            "${formatter.format(Date(entry.deviceTimestamp))}?\n\n" +
                            "This cannot be undone.",
                    color = SyncLightGray,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteMealEntry(entry.id)
                        entryToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SyncError)
                ) {
                    Text("Delete", color = SyncWhite, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { entryToDelete = null },
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(SyncMidGray)
                    )
                ) {
                    Text("Cancel", color = SyncLightGray)
                }
            }
        )
    }
}

// ── Meal entry row with three-dot menu ────────────────────────

@Composable
fun MealEntryRow(
    entry: MealEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val formatter = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    entry.mealLabel?.let {
                        Text(
                            text = it,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SyncGreen
                        )
                    }
                    Text(
                        text = formatter.format(Date(entry.deviceTimestamp)),
                        fontSize = 10.sp,
                        color = SyncLightGray
                    )
                }
                Text(
                    text = "${entry.calories} cal  |  " +
                            "F:${entry.fatGrams.toInt()}g  " +
                            "P:${entry.proteinGrams.toInt()}g  " +
                            "NC:${entry.netCarbGrams.toInt()}g  " +
                            "Fib:${entry.fiberGrams.toInt()}g",
                    fontSize = 11.sp,
                    color = SyncWhite
                )
                Text(
                    text = "KR: ${"%.1f".format(entry.ketogenicRatio)}",
                    fontSize = 10.sp,
                    color = SyncLightGray
                )
            }

            // Three-dot menu
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = SyncLightGray,
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = SyncDarkSurface
                ) {
                    DropdownMenuItem(
                        text = {
                            Text("Edit", color = SyncGreen, fontSize = 13.sp)
                        },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text("Delete", color = SyncError, fontSize = 13.sp)
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
        HorizontalDivider(color = SyncMidGray, thickness = 0.5.dp)
    }
}

// ── Edit dialog ───────────────────────────────────────────────

@Composable
fun MealEditDialog(
    entry: MealEntry,
    onSave: (MealEntry) -> Unit,
    onDismiss: () -> Unit
) {
    var calories  by remember { mutableStateOf(entry.calories.toString()) }
    var fat       by remember { mutableStateOf(entry.fatGrams.toString()) }
    var protein   by remember { mutableStateOf(entry.proteinGrams.toString()) }
    var netCarbs  by remember { mutableStateOf(entry.netCarbGrams.toString()) }
    var fiber     by remember { mutableStateOf(entry.fiberGrams.toString()) }
    var label     by remember { mutableStateOf(entry.mealLabel ?: "MEAL") }

    // Simple date/time editing via timestamp offset
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    var dateTimeStr by remember {
        mutableStateOf(formatter.format(Date(entry.deviceTimestamp)))
    }

    val labels = listOf("BREAKFAST", "LUNCH", "DINNER", "SNACK", "MEAL")
    var labelMenuExpanded by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SyncDarkSurface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Edit Meal Entry",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = SyncGreen
            )

            // Meal label selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Label:", fontSize = 12.sp, color = SyncLightGray)
                Box {
                    OutlinedButton(
                        onClick = { labelMenuExpanded = true },
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = androidx.compose.ui.graphics.SolidColor(SyncMidGray)
                        )
                    ) {
                        Text(label, color = SyncWhite, fontSize = 12.sp)
                    }
                    DropdownMenu(
                        expanded = labelMenuExpanded,
                        onDismissRequest = { labelMenuExpanded = false },
                        containerColor = SyncDarkSurface
                    ) {
                        labels.forEach { l ->
                            DropdownMenuItem(
                                text = { Text(l, color = SyncWhite, fontSize = 12.sp) },
                                onClick = { label = l; labelMenuExpanded = false }
                            )
                        }
                    }
                }
            }

            // Date/time
            MacroEditField("Date/Time (yyyy-MM-dd HH:mm)", dateTimeStr) {
                dateTimeStr = it
            }

            // Macro fields
            MacroEditField("Calories", calories) { calories = it }
            MacroEditField("Fat (g)", fat) { fat = it }
            MacroEditField("Protein (g)", protein) { protein = it }
            MacroEditField("Net Carbs (g)", netCarbs) { netCarbs = it }
            MacroEditField("Fiber (g)", fiber) { fiber = it }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(SyncMidGray)
                    )
                ) {
                    Text("Cancel", color = SyncLightGray)
                }
                Button(
                    onClick = {
                        val cal = calories.toIntOrNull() ?: entry.calories
                        val f   = fat.toDoubleOrNull() ?: entry.fatGrams
                        val p   = protein.toDoubleOrNull() ?: entry.proteinGrams
                        val nc  = netCarbs.toDoubleOrNull() ?: entry.netCarbGrams
                        val fib = fiber.toDoubleOrNull() ?: entry.fiberGrams
                        val tc  = nc + fib
                        val kr  = if ((nc + p) > 0) f / (nc + p) else 0.0
                        val ts  = try {
                            formatter.parse(dateTimeStr)?.time ?: entry.deviceTimestamp
                        } catch (e: Exception) {
                            entry.deviceTimestamp
                        }
                        onSave(
                            entry.copy(
                                calories         = cal,
                                fatGrams         = f,
                                proteinGrams     = p,
                                netCarbGrams     = nc,
                                fiberGrams       = fib,
                                totalCarbGrams   = tc,
                                ketogenicRatio   = kr,
                                mealLabel        = label,
                                deviceTimestamp  = ts
                            )
                        )
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = SyncGreen)
                ) {
                    Text("Save", color = SyncBlack, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ── Reusable edit field ───────────────────────────────────────

@Composable
fun MacroEditField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = SyncLightGray, fontSize = 11.sp) },
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

// ── Macro summary item ────────────────────────────────────────

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