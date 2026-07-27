package com.synchronize.app

import android.app.Application
import android.util.Log
import com.synchronize.app.data.db.SynchronizeDatabase

class SynchronizeApp : Application() {

    val database: SynchronizeDatabase by lazy {
        try {
            SynchronizeDatabase.getInstance(
                this,
                "synchronize_dev_key_2025".toByteArray()
            )
        } catch (e: Exception) {
            Log.e("SynchronizeApp", "Database init failed", e)
            throw e
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d("SynchronizeApp", "Application started")
        try {
            // Force database initialization on startup to catch errors early
            val db = database
            Log.d("SynchronizeApp", "Database initialized: $db")
        } catch (e: Exception) {
            Log.e("SynchronizeApp",
                "DATABASE INIT FAILED — likely schema version mismatch. " +
                        "Do NOT clear app data; rebuild from correct source instead.", e)
            throw e
        }
    }

    companion object {
        lateinit var instance: SynchronizeApp
            private set
    }
}