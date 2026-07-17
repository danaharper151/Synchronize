package com.synchronize.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.synchronize.app.ui.screens.DashboardScreen
import com.synchronize.app.ui.screens.DataScreen
import com.synchronize.app.ui.screens.KetoneScreen
import com.synchronize.app.ui.screens.MealScreen
import com.synchronize.app.ui.screens.SettingsScreen
import com.synchronize.app.ui.theme.SyncBlack
import com.synchronize.app.ui.theme.SyncDarkSurface
import com.synchronize.app.ui.theme.SyncGreen
import com.synchronize.app.ui.theme.SyncLightGray
import com.synchronize.app.ui.theme.SyncMidGray
import com.synchronize.app.ui.viewmodel.MainViewModel

@Composable
fun NavGraph(viewModel: MainViewModel) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = SyncDarkSurface,
                tonalElevation = androidx.compose.ui.unit.Dp.Unspecified
            ) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                Screen.bottomNavItems.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any {
                        it.route == screen.route
                    } == true

                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = screen.label
                            )
                        },
                        label = {
                            Text(
                                text = screen.label,
                                fontSize = androidx.compose.ui.unit.TextUnit(
                                    10f,
                                    androidx.compose.ui.unit.TextUnitType.Sp
                                )
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor   = SyncGreen,
                            selectedTextColor   = SyncGreen,
                            unselectedIconColor = SyncLightGray,
                            unselectedTextColor = SyncLightGray,
                            indicatorColor      = SyncMidGray
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SyncBlack)
                .padding(innerPadding)
        ) {
            NavHost(
                navController = navController,
                startDestination = Screen.Dashboard.route
            ) {
                composable(Screen.Dashboard.route) {
                    DashboardScreen(viewModel = viewModel)
                }
                composable(Screen.Ketones.route) {
                    KetoneScreen(viewModel = viewModel)
                }
                composable(Screen.Meals.route) {
                    MealScreen(viewModel = viewModel)
                }
                composable(Screen.Data.route) {
                    DataScreen(viewModel = viewModel)
                }
                composable(Screen.Settings.route) {
                    SettingsScreen(viewModel = viewModel)
                }
            }
        }
    }
}