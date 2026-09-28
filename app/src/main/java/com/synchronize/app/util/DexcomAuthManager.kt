package com.synchronize.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ClientAuthentication
import net.openid.appauth.ClientSecretPost
import net.openid.appauth.GrantTypeValues
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationException
import kotlin.coroutines.resumeWithException

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

    private val TAG = "DexcomAuthManager"

    // ── AppAuth service configuration ─────────────────────────

    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse("${Constants.DEXCOM_BASE_URL}v2/oauth2/login"),
        Uri.parse("${Constants.DEXCOM_BASE_URL}v2/oauth2/token")
    )

    // ── Build authorization request ───────────────────────────

    fun buildAuthRequest(): AuthorizationRequest {
        return AuthorizationRequest.Builder(
            serviceConfig,
            Constants.DEXCOM_CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(Constants.DEXCOM_REDIRECT_URI)
        )
            .setScope(Constants.DEXCOM_SCOPE)
            .build()
    }

    // ── Get auth intent to launch Chrome Custom Tab ───────────

    fun getAuthIntent(authService: AuthorizationService): Intent {
        val authRequest = buildAuthRequest()
        return authService.getAuthorizationRequestIntent(authRequest)
    }

    // ── Exchange auth response for tokens ─────────────────────

    suspend fun exchangeCodeForTokens(
        authResponse: AuthorizationResponse,
        authService: AuthorizationService
    ): Result<Unit> {
        return try {
            val code = authResponse.authorizationCode
                ?: return Result.failure(Exception("No authorization code in response"))

            Log.d(TAG, "Exchanging code via Retrofit...")

            val api = DexcomRetrofitClient.instance
            val tokenResponse = api.getToken(
                clientId     = Constants.DEXCOM_CLIENT_ID,
                clientSecret = Constants.DEXCOM_CLIENT_SECRET,
                code         = code,
                grantType    = "authorization_code",
                redirectUri  = Constants.DEXCOM_REDIRECT_URI,
                codeVerifier = authResponse.request.codeVerifier
            )

            context.dexcomDataStore.edit { prefs ->
                prefs[DexcomPrefKeys.ACCESS_TOKEN]  = tokenResponse.accessToken
                prefs[DexcomPrefKeys.REFRESH_TOKEN] = tokenResponse.refreshToken
                prefs[DexcomPrefKeys.TOKEN_EXPIRY]  =
                    System.currentTimeMillis() + (tokenResponse.expiresIn * 1000L)
            }

            Log.d(TAG, "Token exchange successful — access token stored")
            Result.success(Unit)

        } catch (e: retrofit2.HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            Log.e(TAG, "HTTP ${e.code()} — $errorBody")
            Result.failure(Exception("HTTP ${e.code()}: $errorBody"))
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange failed", e)
            Result.failure(e)
        }
    }
    // ── Refresh access token ──────────────────────────────────

    /**
     * Refreshes the access token via Retrofit rather than AppAuth's
     * performTokenRequest. AppAuth's response parser throws on Dexcom's error
     * payload shape and obscures the server's message; Retrofit's HttpException
     * surfaces it. This mirrors the code-exchange path above.
     *
     * Dexcom rotates the refresh token on each use and returns a new one. It must
     * be persisted or the following refresh will fail.
     */
    suspend fun refreshAccessToken(): Result<Unit> {
        return try {
            val stored = getRefreshToken()
                ?: return Result.failure(Exception("No refresh token stored"))

            val r = DexcomRetrofitClient.instance.refreshToken(
                clientId     = Constants.DEXCOM_CLIENT_ID,
                clientSecret = Constants.DEXCOM_CLIENT_SECRET,
                refreshToken = stored
            )

            context.dexcomDataStore.edit { prefs ->
                prefs[DexcomPrefKeys.ACCESS_TOKEN]  = r.accessToken
                prefs[DexcomPrefKeys.REFRESH_TOKEN] = r.refreshToken
                prefs[DexcomPrefKeys.TOKEN_EXPIRY]  =
                    System.currentTimeMillis() + (r.expiresIn * 1000L)
            }

            Log.d(TAG, "Token refreshed; expires in ${r.expiresIn}s")
            Result.success(Unit)

        } catch (e: retrofit2.HttpException) {
            val body = e.response()?.errorBody()?.string()
            Log.e(TAG, "Refresh failed HTTP ${e.code()} — $body")
            Result.failure(Exception("HTTP ${e.code()}: $body"))
        } catch (e: Exception) {
            Log.e(TAG, "Refresh failed", e)
            Result.failure(e)
        }
    }

    suspend fun getValidAccessToken(): String? {
        val expiry = getTokenExpiry() ?: return null
        if (System.currentTimeMillis() < expiry - 300_000) return getAccessToken()
        return if (refreshAccessToken().isSuccess) getAccessToken() else null
    }

    /** Credentials are stored. Does not mean the session is usable. */
    suspend fun hasStoredCredentials(): Boolean = getRefreshToken() != null

    /** A usable session: valid access token, or a refresh token that yields one. */
    suspend fun isAuthenticated(): Boolean = getValidAccessToken() != null



    // ── DataStore accessors ───────────────────────────────────

    suspend fun getAccessToken(): String? =
        context.dexcomDataStore.data.map {
            it[DexcomPrefKeys.ACCESS_TOKEN]?.ifBlank { null }
        }.first()

    suspend fun getRefreshToken(): String? =
        context.dexcomDataStore.data.map {
            it[DexcomPrefKeys.REFRESH_TOKEN]?.ifBlank { null }
        }.first()

    private suspend fun getTokenExpiry(): Long? =
        context.dexcomDataStore.data.map { it[DexcomPrefKeys.TOKEN_EXPIRY] }.first()

    suspend fun getLastSyncTime(): Long? =
        context.dexcomDataStore.data.map { it[DexcomPrefKeys.LAST_SYNC_TIME] }.first()

    suspend fun saveLastSyncTime(time: Long) {
        context.dexcomDataStore.edit { prefs ->
            prefs[DexcomPrefKeys.LAST_SYNC_TIME] = time
        }
    }



    suspend fun clearTokens() {
        context.dexcomDataStore.edit { it.clear() }
    }
}