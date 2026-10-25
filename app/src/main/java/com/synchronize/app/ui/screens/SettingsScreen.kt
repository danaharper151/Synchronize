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

/**
 * Settings.
 *
 * Two sections are interactive; the rest report configuration. Each reported
 * value names the file that defines it, so a researcher adapting the
 * application to a different protocol can find what to change without
 * reading the whole source tree. Protocol parameters are compile-time
 * constants in this version.
 */
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

            Text(
                text = "One reminder per reading context, through AlarmManager with " +
                        "a boot-persistent receiver. Times are defined in " +
                        "ReminderManager.kt.",
                fontSize = 11.sp,
                color = SyncLightGray
            )
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

        // ── Collection protocol ───────────────────────────────
        //
        // Replaces the former "Study Protocol" section. That section described
        // the study this application was built for, including the model
        // architectures and cross-validation scheme — neither of which is in
        // this application. Both belong to the analysis pipeline, which is a
        // separate repository.
        SettingsSection(title = "Collection Protocol") {
            Text(
                text = "Fixed at compile time in this version. Each row names the " +
                        "file that defines it.",
                fontSize = 11.sp,
                color = SyncLightGray
            )
            SettingsInfoRow("Subject identifier", Constants.SUBJECT_ID)
            SettingsInfoRow("Reading contexts", "5 — Constants.kt")
            SettingsInfoRow("Reminder times", "5 daily — ReminderManager.kt")
            SettingsInfoRow("Exercise types", "ExerciseSession.kt")
            SettingsInfoRow("Daily covariates", "DailyCovariate.kt")
            Text(
                text = "Every table carries a subject identifier, so multi-subject " +
                        "collection needs no schema migration.",
                fontSize = 10.sp,
                color = SyncLightGray
            )
        }

        // ── Data sources ──────────────────────────────────────
        SettingsSection(title = "Data Sources") {
            SettingsInfoRow("CGM", "Dexcom API (OAuth 2.0 + PKCE), 5-min EGVs")
            SettingsInfoRow("Ketone meter", "Keto-Mojo API + manual entry")
            SettingsInfoRow("Smartwatch", "Health Connect + vendor export parsing")
            SettingsInfoRow("Diet", "Voice logging (Android SpeechRecognizer)")
            SettingsInfoRow("API environment", if (Constants.USE_SANDBOX) "Sandbox" else "Production")
            Text(
                text = "Health Connect supplies three of seven watch streams. The " +
                        "remainder are recovered from weekly vendor exports by the " +
                        "analysis pipeline.",
                fontSize = 10.sp,
                color = SyncLightGray
            )
        }

        // ── Display thresholds ────────────────────────────────
        SettingsSection(title = "Display Thresholds") {
            SettingsInfoRow("Glucose low", "${Constants.GLUCOSE_LOW} mg/dL")
            SettingsInfoRow("Glucose high", "${Constants.GLUCOSE_HIGH} mg/dL")
            SettingsInfoRow("Glucose very high", "${Constants.GLUCOSE_VERY_HIGH} mg/dL")
            SettingsInfoRow("Ketosis threshold", "${Constants.KETONE_SUB_KETOSIS} mmol/L")
            SettingsInfoRow("Nutritional ketosis", "${Constants.KETONE_SUB_KETOSIS} – ${Constants.KETONE_NUTRITIONAL} mmol/L")
            SettingsInfoRow("Elevated ketones", "> ${Constants.KETONE_ELEVATED} mmol/L")
            Text(
                text = "Color-coding only. Not a diagnostic tool. Defined in " +
                        "Constants.kt.",
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
            SettingsInfoRow("API credentials", "BuildConfig, from local.properties")
            SettingsInfoRow("Data access", "Extracted via adb for analysis")
            Text(
                text = "Collected data is stored unencrypted on the device. Use " +
                        "device-level encryption and a screen lock. Encryption at " +
                        "rest, certificate pinning and audit logging are not " +
                        "implemented; see the README.",
                fontSize = 10.sp,
                color = SyncLightGray
            )
        }

        // ── About ─────────────────────────────────────────────
        SettingsSection(title = "About Synchronize") {
            SettingsInfoRow("Version", BuildConfig.VERSION_NAME)
            SettingsInfoRow("Platform", "Android · Kotlin · Jetpack Compose")
            SettingsInfoRow("Architecture", "MVVM + Room + Coroutines")
            SettingsInfoRow("License", "MIT")
            SettingsInfoRow("Repository", "github.com/danaharper151/Synchronize")
            Text(
                text = "Built as the data collection instrument for an M.S. Computer " +
                        "Science thesis at CSU Channel Islands, 2026.",
                fontSize = 10.sp,
                color = SyncLightGray
            )
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