package com.synchronize.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// ── DataStore extension ───────────────────────────────────────

val Context.dexcomDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "dexcom_prefs"
)

// ── Token storage keys ────────────────────────────────────────

object DexcomPrefKeys {
    val ACCESS_TOKEN   = stringPreferencesKey("dexcom_access_token")
    val REFRESH_TOKEN  = stringPreferencesKey("dexcom_refresh_token")
    val TOKEN_EXPIRY   = longPreferencesKey("dexcom_token_expiry")
    val LAST_SYNC_TIME = longPreferencesKey("dexcom_last_sync")
}

// ── Auth Manager ──────────────────────────────────────────────

class DexcomAuthManager(private val context: Context) {

    private val api = DexcomRetrofitClient.instance

    // ── Launch OAuth login in Chrome Custom Tab ────────────────

    fun launchAuthFlow() {
        val authUri = Uri.parse(Constants.DEXCOM_AUTH_URL)
        val customTabsIntent = CustomTabsIntent.Builder().build()
        customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        customTabsIntent.launchUrl(context, authUri)
    }

    // ── Exchange auth code for tokens ─────────────────────────

    suspend fun exchangeCodeForTokens(
        code: String,
        clientSecret: String
    ): Result<DexcomTokenResponse> {
        return try {
            val response = api.getToken(
                clientId    = Constants.DEXCOM_CLIENT_ID,
                clientSecret = clientSecret,
                code        = code,
                grantType   = "authorization_code",
                redirectUri = Constants.DEXCOM_REDIRECT_URI
            )
            saveTokens(response)
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Refresh access token ──────────────────────────────────

    suspend fun refreshAccessToken(clientSecret: String): Result<DexcomTokenResponse> {
        return try {
            val storedRefreshToken = getRefreshToken() ?: return Result.failure(
                Exception("No refresh token stored")
            )
            val response = api.refreshToken(
                clientId     = Constants.DEXCOM_CLIENT_ID,
                clientSecret = clientSecret,
                refreshToken = storedRefreshToken,
                redirectUri  = Constants.DEXCOM_REDIRECT_URI
            )
            saveTokens(response)
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Get valid access token (refreshes if expired) ─────────

    suspend fun getValidAccessToken(clientSecret: String): String? {
        val expiry = getTokenExpiry()
        val now = System.currentTimeMillis()
        return if (expiry != null && now < expiry - 60_000) {
            // Token still valid — return it
            getAccessToken()
        } else {
            // Token expired or expiring soon — refresh
            val result = refreshAccessToken(clientSecret)
            if (result.isSuccess) getAccessToken() else null
        }
    }

    // ── Save tokens to DataStore ──────────────────────────────

    private suspend fun saveTokens(response: DexcomTokenResponse) {
        context.dexcomDataStore.edit { prefs ->
            prefs[DexcomPrefKeys.ACCESS_TOKEN]  = response.accessToken
            prefs[DexcomPrefKeys.REFRESH_TOKEN] = response.refreshToken
            prefs[DexcomPrefKeys.TOKEN_EXPIRY]  = System.currentTimeMillis() +
                    (response.expiresIn * 1000L)
        }
    }

    // ── Read tokens from DataStore ────────────────────────────

    suspend fun getAccessToken(): String? =
        context.dexcomDataStore.data.map { it[DexcomPrefKeys.ACCESS_TOKEN] }.first()

    suspend fun getRefreshToken(): String? =
        context.dexcomDataStore.data.map { it[DexcomPrefKeys.REFRESH_TOKEN] }.first()

    private suspend fun getTokenExpiry(): Long? =
        context.dexcomDataStore.data.map { it[DexcomPrefKeys.TOKEN_EXPIRY] }.first()

    suspend fun getLastSyncTime(): Long? =
        context.dexcomDataStore.data.map { it[DexcomPrefKeys.LAST_SYNC_TIME] }.first()

    suspend fun saveLastSyncTime(time: Long) {
        context.dexcomDataStore.edit { prefs ->
            prefs[DexcomPrefKeys.LAST_SYNC_TIME] = time
        }
    }

    // ── Auth state ────────────────────────────────────────────

    suspend fun isAuthenticated(): Boolean = getAccessToken() != null

    val isAuthenticatedFlow: Flow<Boolean> =
        context.dexcomDataStore.data.map { it[DexcomPrefKeys.ACCESS_TOKEN] != null }

    suspend fun clearTokens() {
        context.dexcomDataStore.edit { it.clear() }
    }
}