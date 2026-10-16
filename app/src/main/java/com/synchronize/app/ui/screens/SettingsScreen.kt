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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synchronize.app.BuildConfig
import com.synchronize.app.ui.theme.*
import com.synchronize.app.ui.viewmodel.MainViewModel
import com.synchronize.app.util.Constants

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

        // ── Reading reminders (interactive) ───────────────────
        SettingsSection(title = "Reading Reminders") {
            val remindersEnabled by viewModel.remindersEnabled.collectAsState()
            val context = LocalContext.current

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

        // ── Data backup (interactive) ─────────────────────────
        SettingsSection(title = "Data Backup") {
            val ctx = LocalContext.current
            var backupMsg by remember { mutableStateOf<String?>(null) }

            Text(
                text = "Copies the database to app storage. Back up daily during " +
                        "collection; backups survive app rebuilds but not an uninstall.",
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

        // ── Study protocol (as run) ───────────────────────────
        SettingsSection(title = "Study Protocol") {
            SettingsInfoRow("Subject ID", Constants.SUBJECT_ID)
            SettingsInfoRow("Design", "N-of-1, 30-day self-collection")
            SettingsInfoRow("Ketone readings/day", "5 scheduled reading contexts")
            SettingsInfoRow("Models", "Random Forest, XGBoost (ablation A–D)")
            SettingsInfoRow("Evaluation", "Leave-one-day-out (primary), LOOCV")
        }

        // ── Data sources ──────────────────────────────────────
        SettingsSection(title = "Data Sources") {
            SettingsInfoRow("CGM", "Dexcom G7 — Dexcom API (OAuth), 5-min EGVs")
            SettingsInfoRow("Ketone meter", "Keto-Mojo GK+ — Keto-Mojo API + manual entry")
            SettingsInfoRow("Smartwatch", "Galaxy Watch 7 — Health Connect + Samsung exports")
            SettingsInfoRow("Diet", "Voice logging (Android SpeechRecognizer)")
            SettingsInfoRow("API environment", if (Constants.USE_SANDBOX) "Sandbox" else "Production")
        }

        // ── Display thresholds ────────────────────────────────
        SettingsSection(title = "Display Thresholds") {
            SettingsInfoRow("Glucose low", "${Constants.GLUCOSE_LOW} mg/dL")
            SettingsInfoRow("Glucose high", "${Constants.GLUCOSE_HIGH} mg/dL")
            SettingsInfoRow("Ketosis threshold", "${Constants.KETONE_SUB_KETOSIS} mmol/L")
            SettingsInfoRow("Nutritional ketosis", "0.5 – ${Constants.KETONE_NUTRITIONAL} mmol/L")
            SettingsInfoRow("Elevated ketones", "> ${Constants.KETONE_ELEVATED} mmol/L")
            Text(
                text = "Used for color-coding only. Not a diagnostic tool.",
                fontSize = 10.sp,
                color = SyncLightGray
            )
        }

        // ── Storage & privacy ─────────────────────────────────
        SettingsSection(title = "Storage & Privacy") {
            SettingsInfoRow("Storage", "Room / SQLite, app-private, on-device")
            SettingsInfoRow("App lock", "Biometric (BiometricPrompt)")
            SettingsInfoRow("Database encryption", "Not encrypted")
            SettingsInfoRow("API transport", "HTTPS (TLS)")
            SettingsInfoRow("API credentials", "BuildConfig — not committed to source")
            SettingsInfoRow("Data access", "Extracted via adb for analysis")
        }

        // ── About ─────────────────────────────────────────────
        SettingsSection(title = "About Synchronize") {
            SettingsInfoRow("Version", BuildConfig.VERSION_NAME)
            SettingsInfoRow("Platform", "Android · Kotlin · Jetpack Compose")
            SettingsInfoRow("Architecture", "MVVM + Room + Coroutines")
            SettingsInfoRow("Repository", "github.com/danaharper151/Synchronize")
            SettingsInfoRow("Thesis", "MSCS — CSU Channel Islands, 2026")
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