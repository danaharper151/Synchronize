package com.synchronize.app.util

import android.content.Context
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class BiometricAuthManager(private val context: Context) {

    private val TAG = "BiometricAuthManager"

    sealed class AuthState {
        object Unauthenticated : AuthState()
        object Authenticating  : AuthState()
        object Authenticated   : AuthState()
        data class Error(val message: String) : AuthState()
    }

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val authState: StateFlow<AuthState> = _authState

    // ── Check biometric availability ──────────────────────────

    fun canAuthenticate(): Boolean {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun getAuthenticatorType(): String {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate()) {
            BiometricManager.BIOMETRIC_SUCCESS -> "Fingerprint / Face ID"
            else -> "PIN / Password"
        }
    }

    // ── Show biometric prompt ─────────────────────────────────

    fun authenticate(activity: FragmentActivity) {
        _authState.value = AuthState.Authenticating

        val executor = ContextCompat.getMainExecutor(context)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: BiometricPrompt.AuthenticationResult
            ) {
                Log.d(TAG, "Authentication succeeded")
                _authState.value = AuthState.Authenticated
            }

            override fun onAuthenticationError(
                errorCode: Int,
                errString: CharSequence
            ) {
                Log.e(TAG, "Authentication error: $errString ($errorCode)")
                when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> {
                        _authState.value = AuthState.Unauthenticated
                    }
                    else -> {
                        _authState.value = AuthState.Error(errString.toString())
                    }
                }
            }

            override fun onAuthenticationFailed() {
                Log.w(TAG, "Authentication failed — wrong biometric")
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Synchronize")
            .setSubtitle("Research Health Data")
            .setDescription("Authenticate to access your health data")
            .setDeviceCredentialAllowed(true)
            .build()

        val biometricPrompt = BiometricPrompt(activity, executor, callback)
        biometricPrompt.authenticate(promptInfo)
    }

    // ── State helpers ─────────────────────────────────────────

    fun isAuthenticated() = _authState.value == AuthState.Authenticated

    fun lock() {
        _authState.value = AuthState.Unauthenticated
        Log.d(TAG, "App locked")
    }
}