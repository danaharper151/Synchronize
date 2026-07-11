package com.synchronize.app.ui.theme

import androidx.compose.ui.graphics.Color

// ── Synchronize Brand Colors ──────────────────────────────────
val SyncGreen       = Color(0xFF00FF77)   // primary — neon green
val SyncMagenta     = Color(0xFFFF00FF)   // accent — magenta
val SyncBlack       = Color(0xFF000000)   // background
val SyncDarkSurface = Color(0xFF0D0D0D)   // card / surface background
val SyncDarkGray    = Color(0xFF1A1A1A)   // elevated surface
val SyncMidGray     = Color(0xFF2C2C2C)   // borders / dividers
val SyncLightGray   = Color(0xFF9E9E9E)   // secondary text
val SyncWhite       = Color(0xFFFFFFFF)   // primary text on dark

// ── Semantic Colors ───────────────────────────────────────────
val SyncSuccess     = Color(0xFF00FF77)   // same as green — good readings
val SyncWarning     = Color(0xFFFFD600)   // yellow — borderline readings
val SyncError       = Color(0xFFFF3D3D)   // red — out of range / alerts
val SyncInfo        = Color(0xFF00B0FF)   // blue — informational

// ── CGM Glucose Range Colors ──────────────────────────────────
val GlucoseLow      = Color(0xFFFF3D3D)   // < 70 mg/dL
val GlucoseNormal   = Color(0xFF00FF77)   // 70–140 mg/dL
val GlucoseHigh     = Color(0xFFFFD600)   // 140–180 mg/dL
val GlucoseVeryHigh = Color(0xFFFF3D3D)   // > 180 mg/dL

// ── Ketone Range Colors ───────────────────────────────────────
val KetoneSubKetosis    = Color(0xFF9E9E9E)   // < 0.5 mmol/L — not in ketosis
val KetoneNutritional   = Color(0xFF00FF77)   // 0.5–3.0 mmol/L — nutritional ketosis
val KetoneElevated      = Color(0xFFFFD600)   // 3.0–5.0 mmol/L — elevated
val KetoneHigh          = Color(0xFFFF3D3D)   // > 5.0 mmol/L — high / DKA risk

// ── Chart Line Colors ─────────────────────────────────────────
val ChartGlucose    = Color(0xFF00FF77)   // green — glucose line
val ChartKetone     = Color(0xFFFF00FF)   // magenta — ketone line
val ChartHeartRate  = Color(0xFFFF3D3D)   // red — heart rate line
val ChartHrv        = Color(0xFF00B0FF)   // blue — HRV line
val ChartSpo2       = Color(0xFF7C4DFF)   // purple — SpO2 line
val ChartBp         = Color(0xFFFFD600)   // yellow — blood pressure line
val ChartMeal       = Color(0xFFFF9800)   // orange — meal marker
val ChartExercise   = Color(0xFF00E5FF)   // cyan — exercise marker
val ChartSleep      = Color(0xFF7C4DFF)   // purple — sleep marker = Color(0xFF7D5260)