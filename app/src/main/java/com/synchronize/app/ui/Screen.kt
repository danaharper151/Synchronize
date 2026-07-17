package com.synchronize.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    object Dashboard : Screen(
        route = "dashboard",
        label = "Dashboard",
        icon = Icons.Default.Home
    )
    object Ketones : Screen(
        route = "ketones",
        label = "Ketones",
        icon = Icons.Default.Favorite
    )
    object Meals : Screen(
        route = "meals",
        label = "Meals",
        icon = Icons.Default.Star
    )
    object Data : Screen(
        route = "data",
        label = "Data",
        icon = Icons.Default.DateRange
    )
    object Settings : Screen(
        route = "settings",
        label = "Settings",
        icon = Icons.Default.Settings
    )

    companion object {
        val bottomNavItems = listOf(
            Dashboard, Ketones, Meals, Data, Settings
        )
    }
}