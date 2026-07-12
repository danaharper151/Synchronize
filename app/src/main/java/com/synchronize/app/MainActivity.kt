package com.synchronize.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.synchronize.app.data.repository.SynchronizeRepository
import com.synchronize.app.ui.screens.DashboardScreen
import com.synchronize.app.ui.theme.SynchronizeTheme
import com.synchronize.app.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory(
            SynchronizeRepository(SynchronizeApp.instance.database)
        )
    }

    // ── Permission launchers ──────────────────────────────────

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        android.util.Log.d("MainActivity",
            "Mic permission: ${if (granted) "granted" else "denied"}")
    }

    private val requestBlePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.forEach { (perm, granted) ->
            android.util.Log.d("MainActivity", "$perm: ${if (granted) "granted" else "denied"}")
        }
    }

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.forEach { (perm, granted) ->
            android.util.Log.d("MainActivity", "$perm: ${if (granted) "granted" else "denied"}")
        }
    }

    // ── onCreate ──────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestAllPermissions()
        setContent {
            SynchronizeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DashboardScreen(viewModel = viewModel)
                }
            }
        }
    }

    // ── Request all permissions ───────────────────────────────

    private fun requestAllPermissions() {
        requestMicrophonePermission()
        requestBlePermissions()
        requestLocationPermissions()
    }

    private fun requestMicrophonePermission() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blePerms = mutableListOf<String>()
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN))
                blePerms.add(Manifest.permission.BLUETOOTH_SCAN)
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
                blePerms.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (blePerms.isNotEmpty()) {
                requestBlePermissions.launch(blePerms.toTypedArray())
            }
        }
    }

    private fun requestLocationPermissions() {
        val locationPerms = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION))
            locationPerms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION))
            locationPerms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (locationPerms.isNotEmpty()) {
            requestLocationPermission.launch(locationPerms.toTypedArray())
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED
}