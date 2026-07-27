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
import com.synchronize.app.util.Constants
import com.synchronize.app.util.DexcomAuthManager
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults


@Composable
fun SettingsScreen(viewModel: MainViewModel) {

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
            text = "Settings",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = SyncGreen,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )



        // ── Study configuration ───────────────────────────────
        SettingsSection(title = "Study Configuration") {
            SettingsInfoRow("Subject ID", Constants.SUBJECT_ID)
            SettingsInfoRow("Data collection", "30-day longitudinal")
            SettingsInfoRow("Ketone readings/day", "4–5 at standardized times")
            SettingsInfoRow("ML models", "Rocket + Random Forest")
            SettingsInfoRow("Evaluation", "Leave-One-Out CV (LOOCV)")
        }

        // ── Device configuration ──────────────────────────────
        SettingsSection(title = "Devices") {
            SettingsInfoRow("CGM", "Dexcom G7 — 5-min poll interval")
            SettingsInfoRow("Smartwatch", "Samsung Galaxy Watch 7")
            SettingsInfoRow("Ketone meter", "Keto-Mojo GK+ via BLE")
            SettingsInfoRow("Environment", if (Constants.USE_SANDBOX) "Sandbox" else "Production")
        }

        // ── Clinical thresholds ───────────────────────────────
        SettingsSection(title = "Clinical Thresholds") {
            SettingsInfoRow("Glucose low", "${Constants.GLUCOSE_LOW} mg/dL")
            SettingsInfoRow("Glucose high", "${Constants.GLUCOSE_HIGH} mg/dL")
            SettingsInfoRow("Ketosis threshold", "${Constants.KETONE_SUB_KETOSIS} mmol/L")
            SettingsInfoRow("Nutritional ketosis", "0.5 – ${Constants.KETONE_NUTRITIONAL} mmol/L")
            SettingsInfoRow("Elevated ketones", "> ${Constants.KETONE_ELEVATED} mmol/L")
        }

        // ── Security ──────────────────────────────────────────
        SettingsSection(title = "Security & Privacy") {
            SettingsInfoRow("Database encryption", "SQLCipher AES-256")
            SettingsInfoRow("API transport", "HTTPS with certificate pinning")
            SettingsInfoRow("Authentication", "BiometricPrompt")
            SettingsInfoRow("De-identification", "Timestamp offset + PII suppression")
            SettingsInfoRow("Public release", "PhysioNet on thesis completion")
        }

        // ── About ─────────────────────────────────────────────
        SettingsSection(title = "About Synchronize") {
            SettingsInfoRow("Version", "1.0.0 — Development build")
            SettingsInfoRow("Platform", "Android 14+ / Kotlin / Jetpack Compose")
            SettingsInfoRow("Architecture", "MVVM + Room + Coroutines")
            SettingsInfoRow("Repository", "github.com/danaharper151/Synchronize")
            SettingsInfoRow("Thesis", "MSCS — CSUCI 2025")
        }

        SettingsSection(title = "Reading Reminders") {
            val remindersEnabled by viewModel.remindersEnabled.collectAsState()
            val context = androidx.compose.ui.platform.LocalContext.current

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (remindersEnabled) "Reminders ON" else "Reminders OFF",
                        fontSize = 13.sp,
                        color = if (remindersEnabled) SyncGreen else SyncLightGray
                    )
                    Text(
                        text = "7:30 · 11:30 · 13:30 · 17:30 · 21:30",
                        fontSize = 10.sp,
                        color = SyncLightGray
                    )
                }
                Switch(
                    checked = remindersEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) viewModel.enableReminders(context)
                        else viewModel.disableReminders()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor   = SyncBlack,
                        checkedTrackColor   = SyncGreen,
                        uncheckedThumbColor = SyncLightGray,
                        uncheckedTrackColor = SyncMidGray
                    )
                )
            }

            if (remindersEnabled) {
                Text(
                    text = "Daily reminders active for all 5 reading contexts",
                    fontSize = 11.sp,
                    color = SyncLightGray
                )
            }
        }
        SettingsSection(title = "Data Backup") {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            var backupMsg by remember { mutableStateOf<String?>(null) }

            Text(
                text = "Back up the database daily. Files are saved to app storage " +
                        "and survive rebuilds.",
                fontSize = 11.sp,
                color = SyncLightGray
            )
            Button(
                onClick = {
                    val path = viewModel.backupDatabase(ctx)
                    backupMsg = if (path != null) "Backup saved ✓" else "Backup failed"
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = SyncGreen)
            ) {
                Text("Back Up Now", color = SyncBlack, fontWeight = FontWeight.Bold)
            }
            backupMsg?.let {
                Text(text = it, fontSize = 11.sp, color = SyncGreen)
            }
        }

        // ── Danger zone ───────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SyncDarkSurface)
                .border(1.dp, SyncError, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Developer Tools",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = SyncError
            )
            Text(
                text = "These options are for development only and will be removed before production deployment.",
                fontSize = 11.sp,
                color = SyncLightGray
            )
            OutlinedButton(
                onClick = { /* Switch sandbox/production */ },
                modifier = Modifier.fillMaxWidth(),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = androidx.compose.ui.graphics.SolidColor(SyncError)
                )
            ) {
                Text(
                    text = if (Constants.USE_SANDBOX)
                        "Currently: Sandbox — tap to switch to Production"
                    else
                        "Currently: Production — tap to switch to Sandbox",
                    color = SyncError,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ── Settings section wrapper ──────────────────────────────────

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
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
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = SyncGreen
        )
        content()
    }
}

// ── Settings info row ─────────────────────────────────────────

@Composable
fun SettingsInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = SyncLightGray,
            modifier = Modifier.weight(0.45f)
        )
        Text(
            text = value,
            fontSize = 12.sp,
            color = SyncWhite,
            modifier = Modifier.weight(0.55f),
            textAlign = TextAlign.End
        )
    }
}