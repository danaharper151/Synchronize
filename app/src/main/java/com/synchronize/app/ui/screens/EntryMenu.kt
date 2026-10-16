package com.synchronize.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synchronize.app.ui.theme.*

// Shared edit pattern for every logged entry (meals, ketones, exercise,
// covariates): a three-dot menu with Edit and Delete, and a confirmation
// dialog before anything is removed.

@Composable
fun EntryOptionsMenu(
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }

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
                text = { Text("Edit", color = SyncGreen, fontSize = 13.sp) },
                onClick = {
                    menuExpanded = false
                    onEdit()
                }
            )
            DropdownMenuItem(
                text = { Text("Delete", color = SyncError, fontSize = 13.sp) },
                onClick = {
                    menuExpanded = false
                    onDelete()
                }
            )
        }
    }
}

@Composable
fun ConfirmDeleteDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SyncDarkSurface,
        title = { Text(title, color = SyncWhite, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                text = "$message\n\nThis cannot be undone.",
                color = SyncLightGray,
                fontSize = 13.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = SyncError)
            ) {
                Text("Delete", color = SyncWhite, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = SolidColor(SyncMidGray)
                )
            ) {
                Text("Cancel", color = SyncLightGray)
            }
        }
    )
}
