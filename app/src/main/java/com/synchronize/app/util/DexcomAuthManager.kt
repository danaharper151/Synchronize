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

    suspend fun refreshAccessToken(
        authService: AuthorizationService
    ): Result<Unit> {
        return try {
            val refreshToken = getRefreshToken()
                ?: return Result.failure(Exception("No refresh token stored"))

            val clientAuth: ClientAuthentication = ClientSecretPost(
                Constants.DEXCOM_CLIENT_SECRET
            )

            val tokenRequest = TokenRequest.Builder(
                serviceConfig,
                Constants.DEXCOM_CLIENT_ID
            )
                .setGrantType(GrantTypeValues.REFRESH_TOKEN)
                .setRefreshToken(refreshToken)
                .setRedirectUri(Uri.parse(Constants.DEXCOM_REDIRECT_URI))
                .build()

            val tokenResponse = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                authService.performTokenRequest(tokenRequest, clientAuth) { response, ex ->
                    if (response != null) {
                        cont.resume(response) {}
                    } else {
                        cont.resumeWithException(
                            ex ?: Exception("Token refresh failed")
                        )
                    }
                }
            }

            context.dexcomDataStore.edit { prefs ->
                prefs[DexcomPrefKeys.ACCESS_TOKEN]  = tokenResponse.accessToken ?: ""
                prefs[DexcomPrefKeys.REFRESH_TOKEN] = tokenResponse.refreshToken
                    ?: refreshToken
                prefs[DexcomPrefKeys.TOKEN_EXPIRY]  = tokenResponse.accessTokenExpirationTime
                    ?: (System.currentTimeMillis() + 3600000L)
            }

            Log.d(TAG, "Token refresh successful")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Token refresh failed", e)
            Result.failure(e)
        }
    }

    // ── Get valid access token ────────────────────────────────

    suspend fun getValidAccessToken(
        authService: AuthorizationService
    ): String? {
        val expiry = getTokenExpiry()
        val now = System.currentTimeMillis()
        return if (expiry != null && now < expiry - 60_000) {
            getAccessToken()
        } else {
            val result = refreshAccessToken(authService)
            if (result.isSuccess) getAccessToken() else null
        }
    }

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

    suspend fun isAuthenticated(): Boolean = getAccessToken() != null

    val isAuthenticatedFlow: Flow<Boolean> =
        context.dexcomDataStore.data.map {
            !it[DexcomPrefKeys.ACCESS_TOKEN].isNullOrBlank()
        }

    suspend fun clearTokens() {
        context.dexcomDataStore.edit { it.clear() }
    }
}