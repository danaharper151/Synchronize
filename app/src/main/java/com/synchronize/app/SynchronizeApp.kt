package com.synchronize.app

import android.app.Application
import com.synchronize.app.data.db.SynchronizeDatabase

class SynchronizeApp : Application() {

    val database: SynchronizeDatabase by lazy {
        SynchronizeDatabase.getInstance(
            this,
            // Temporary fixed passphrase for development
            // Will be replaced with BiometricPrompt-derived key in production
            "synchronize_dev_key_2025".toByteArray()
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: SynchronizeApp
            private set
    }
}