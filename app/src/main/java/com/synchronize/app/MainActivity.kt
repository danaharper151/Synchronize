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
import androidx.lifecycle.lifecycleScope
import com.synchronize.app.data.repository.SynchronizeRepository
import com.synchronize.app.ui.NavGraph
import com.synchronize.app.ui.screens.LockScreen
import com.synchronize.app.ui.theme.SynchronizeTheme
import com.synchronize.app.ui.viewmodel.MainViewModel
import com.synchronize.app.util.BiometricAuthManager
import com.synchronize.app.util.DexcomAuthManager
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import androidx.health.connect.client.PermissionController

class MainActivity : FragmentActivity() {

    // ── ViewModel ─────────────────────────────────────────────
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory(
            SynchronizeRepository(SynchronizeApp.instance.database)
        )
    }

    // ── Auth managers ─────────────────────────────────────────
    private lateinit var biometricAuthManager: BiometricAuthManager
    private lateinit var authService: AuthorizationService
    private lateinit var dexcomAuthManager: DexcomAuthManager

    // ── Dexcom OAuth launcher ─────────────────────────────────
    private val authLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        android.util.Log.d("DexcomAuth", "Auth result received, resultCode: ${result.resultCode}")
        val data = result.data
        android.util.Log.d("DexcomAuth", "Intent data: $data")

        if (data == null) {
            android.util.Log.e("DexcomAuth", "No intent data returned")
            return@registerForActivityResult
        }

        val authResponse = AuthorizationResponse.fromIntent(data)
        val authException = AuthorizationException.fromIntent(data)

        android.util.Log.d("DexcomAuth", "authResponse: $authResponse")
        android.util.Log.d("DexcomAuth", "authException: $authException")

        when {
            authResponse != null -> {
                android.util.Log.d("DexcomAuth", "Got auth code: ${authResponse.authorizationCode}")
                lifecycleScope.launch {
                    val exchangeResult = dexcomAuthManager.exchangeCodeForTokens(
                        authResponse, authService
                    )
                    if (exchangeResult.isSuccess) {
                        viewModel.setDexcomConnected(true)
                        android.util.Log.d("DexcomAuth", "Connected successfully!")
                    } else {
                        android.util.Log.e("DexcomAuth",
                            "Token exchange failed: " +
                                    "${exchangeResult.exceptionOrNull()?.message}")
                    }
                }
            }
            authException != null -> {
                android.util.Log.e("DexcomAuth",
                    "Auth error code: ${authException.code}, message: ${authException.message}")
            }
            else -> {
                android.util.Log.e("DexcomAuth", "Neither authResponse nor authException — user may have cancelled")
            }
        }
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
    private val healthConnectPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        android.util.Log.d("HealthConnect", "Granted: ${granted.size} permissions")
        granted.forEach { android.util.Log.d("HealthConnect", "  $it") }
        if (granted.isNotEmpty()) {
            viewModel.initHealthConnect(this)
        }
    }

    fun requestHealthConnectPermissions() {
        val manager = com.synchronize.app.util.HealthConnectManager(this)
        healthConnectPermissionLauncher.launch(manager.requiredPermissions)
    }

    // ── onCreate ──────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize auth managers
        biometricAuthManager = BiometricAuthManager(this)
        authService = AuthorizationService(this)
        dexcomAuthManager = DexcomAuthManager(this)
        lifecycleScope.launch {
            when {
                dexcomAuthManager.isAuthenticated() -> {
                    viewModel.setDexcomConnected(true)
                    android.util.Log.d("DexcomAuth", "Restored Dexcom connection")
                }
                dexcomAuthManager.hasStoredCredentials() -> {
                    viewModel.setDexcomConnected(false)
                    android.util.Log.w("DexcomAuth",
                        "Stored credentials present but refresh failed — reauthorization required")
                }
                else -> {
                    viewModel.setDexcomConnected(false)
                    android.util.Log.d("DexcomAuth", "No Dexcom credentials stored")
                }
            }
        }

        requestAllPermissions()

        setContent {
            SynchronizeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppContent()
                }
            }
        }
    }

    // ── App content with biometric gate ───────────────────────
    @Composable
    fun AppContent() {
        val isAuthenticated by viewModel.isUnlocked.collectAsState()

        if (!isAuthenticated) {
            LockScreen(
                authManager = biometricAuthManager,
                activity = this,
                onAuthenticated = { viewModel.setUnlocked(true) }
            )
            LaunchedEffect(Unit) {
                if (biometricAuthManager.canAuthenticate()) {
                    biometricAuthManager.authenticate(this@MainActivity)
                } else {
                    viewModel.setUnlocked(true)
                }
            }
        } else {
            NavGraph(viewModel = viewModel)
        }
    }

    // ── Dexcom auth launch ────────────────────────────────────
    fun launchDexcomAuth() {
        val authIntent = dexcomAuthManager.getAuthIntent(authService)
        authLauncher.launch(authIntent)
    }

    // ── Permissions ───────────────────────────────────────────
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

    // ── Cleanup ───────────────────────────────────────────────
    override fun onDestroy() {
        super.onDestroy()
        authService.dispose()
    }
}