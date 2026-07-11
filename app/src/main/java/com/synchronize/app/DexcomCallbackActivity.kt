package com.synchronize.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.synchronize.app.util.DexcomAuthManager
import kotlinx.coroutines.launch

class DexcomCallbackActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val uri = intent.data ?: run {
            navigateToMain()
            return
        }

        // Extract auth code from redirect URI
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")

        when {
            code != null -> {
                lifecycleScope.launch {
                    val authManager = DexcomAuthManager(applicationContext)
                    // Client secret stored here for dev — move to secure storage
                    // before production deployment
                    val clientSecret = "6uZeNZfmzithf9n1"
                    authManager.exchangeCodeForTokens(code, clientSecret)
                    navigateToMain()
                }
            }
            error != null -> {
                // Auth was denied or failed — navigate back to main
                navigateToMain()
            }
            else -> navigateToMain()
        }
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }
}