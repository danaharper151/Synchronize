package com.synchronize.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.synchronize.app.data.repository.SynchronizeRepository
import com.synchronize.app.ui.NavGraph
import com.synchronize.app.ui.screens.LockScreen
import com.synchronize.app.ui.theme.SynchronizeTheme
import com.synchronize.app.ui.viewmodel.MainViewModel
import com.synchronize.app.util.BiometricAuthManager

class MainActivity : FragmentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory(
            SynchronizeRepository(SynchronizeApp.instance.database)
        )
    }

    private lateinit var authManager: BiometricAuthManager

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
            android.util.Log.d("MainActivity",
                "$perm: ${if (granted) "granted" else "denied"}")
        }
    }

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.forEach { (perm, granted) ->
            android.util.Log.d("MainActivity",
                "$perm: ${if (granted) "granted" else "denied"}")
        }
    }

    // ── onCreate ──────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        authManager = BiometricAuthManager(this)
        requestAllPermissions()

        setContent {
            SynchronizeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppContent()
                }
            }
        }
    }

    // ── App content with auth gate ────────────────────────────

    @Composable
    fun AppContent() {
        val authState by authManager.authState.collectAsState()
        var isAuthenticated by remember { mutableStateOf(false) }

        if (!isAuthenticated) {
            LockScreen(
                authManager = authManager,
                activity    = this,
                onAuthenticated = {
                    isAuthenticated = true
                }
            )
            // Auto-trigger biometric prompt on first launch
            LaunchedEffect(Unit) {
                if (authManager.canAuthenticate()) {
                    authManager.authenticate(this@MainActivity)
                } else {
                    // No biometric available — skip lock screen
                    isAuthenticated = true
                }
            }
        } else {
            NavGraph(viewModel = viewModel)
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
            if (blePerms.isNotEmpty())
                requestBlePermissions.launch(blePerms.toTypedArray())
        }
    }

    private fun requestLocationPermissions() {
        val locationPerms = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION))
            locationPerms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION))
            locationPerms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (locationPerms.isNotEmpty())
            requestLocationPermission.launch(locationPerms.toTypedArray())
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED
}