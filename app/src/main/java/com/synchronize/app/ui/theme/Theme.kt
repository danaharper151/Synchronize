package com.synchronize.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SynchronizeColorScheme = darkColorScheme(
    primary           = SyncGreen,
    onPrimary         = SyncBlack,
    primaryContainer  = SyncDarkGray,
    onPrimaryContainer = SyncGreen,

    secondary         = SyncMagenta,
    onSecondary       = SyncBlack,
    secondaryContainer = SyncDarkGray,
    onSecondaryContainer = SyncMagenta,

    tertiary          = SyncInfo,
    onTertiary        = SyncBlack,

    background        = SyncBlack,
    onBackground      = SyncWhite,

    surface           = SyncDarkSurface,
    onSurface         = SyncWhite,
    surfaceVariant    = SyncDarkGray,
    onSurfaceVariant  = SyncLightGray,

    outline           = SyncMidGray,
    outlineVariant    = SyncDarkGray,

    error             = SyncError,
    onError           = SyncWhite,
    errorContainer    = Color(0xFF4D0000),
    onErrorContainer  = SyncError,

    inverseSurface    = SyncWhite,
    inverseOnSurface  = SyncBlack,
    inversePrimary    = SyncDarkSurface,

    scrim             = SyncBlack.copy(alpha = 0.6f)
)

@Composable
fun SynchronizeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SynchronizeColorScheme,
        typography  = SynchronizeTypography,
        content     = content
    )
}