package com.synchronize.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.synchronize.app.ui.theme.*
import com.synchronize.app.util.BiometricAuthManager

@Composable
fun LockScreen(
    authManager: BiometricAuthManager,
    activity: FragmentActivity,
    onAuthenticated: () -> Unit
) {
    val authState by authManager.authState.collectAsState()

    // Trigger authentication when state changes to Authenticated
    LaunchedEffect(authState) {
        if (authState is BiometricAuthManager.AuthState.Authenticated) {
            onAuthenticated()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SyncBlack),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(32.dp)
        ) {

            // ── App icon / logo area ──────────────────────────
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(SyncDarkSurface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = "Synchronize",
                    tint = SyncGreen,
                    modifier = Modifier.size(52.dp)
                )
            }

            // ── App name ──────────────────────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Synchronize",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = SyncGreen
                )
                Text(
                    text = "Ketone Prediction Research",
                    fontSize = 13.sp,
                    color = SyncLightGray,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Auth status message ───────────────────────────
            when (authState) {
                is BiometricAuthManager.AuthState.Unauthenticated -> {
                    Text(
                        text = "Authentication required\nto access health data",
                        fontSize = 13.sp,
                        color = SyncLightGray,
                        textAlign = TextAlign.Center
                    )
                }
                is BiometricAuthManager.AuthState.Authenticating -> {
                    Text(
                        text = "Waiting for authentication...",
                        fontSize = 13.sp,
                        color = SyncLightGray,
                        textAlign = TextAlign.Center
                    )
                    CircularProgressIndicator(
                        color = SyncGreen,
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 2.dp
                    )
                }
                is BiometricAuthManager.AuthState.Error -> {
                    Text(
                        text = (authState as BiometricAuthManager.AuthState.Error).message,
                        fontSize = 12.sp,
                        color = SyncError,
                        textAlign = TextAlign.Center
                    )
                }
                is BiometricAuthManager.AuthState.Authenticated -> {
                    Text(
                        text = "Authenticated ✓",
                        fontSize = 13.sp,
                        color = SyncGreen,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // ── Unlock button ─────────────────────────────────
            if (authState !is BiometricAuthManager.AuthState.Authenticating &&
                authState !is BiometricAuthManager.AuthState.Authenticated) {
                Button(
                    onClick = { authManager.authenticate(activity) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SyncGreen
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Unlock — ${authManager.getAuthenticatorType()}",
                        color = SyncBlack,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }

            // ── Security note ─────────────────────────────────
            Text(
                text = "Your health data is encrypted at rest\nand protected by device authentication.",
                fontSize = 10.sp,
                color = SyncMidGray,
                textAlign = TextAlign.Center,
                lineHeight = 14.sp
            )
        }
    }
}