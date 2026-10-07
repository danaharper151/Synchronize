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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues

val Context.mojoDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mojo_prefs"
)

object MojoPrefKeys {
    val ACCESS_TOKEN   = stringPreferencesKey("mojo_access_token")
    val REFRESH_TOKEN  = stringPreferencesKey("mojo_refresh_token")
    val TOKEN_EXPIRY   = longPreferencesKey("mojo_token_expiry")
    val LAST_SYNC_TIME = longPreferencesKey("mojo_last_sync")
}

class MojoAuthManager(private val context: Context) {

    private val TAG = "MojoAuthManager"

    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse(Constants.MOJO_AUTH_BASE_URL + Constants.MOJO_AUTHORIZE_ENDPOINT),
        Uri.parse(Constants.MOJO_AUTH_BASE_URL + Constants.MOJO_TOKEN_ENDPOINT)
    )

    fun buildAuthRequest(): AuthorizationRequest =
        AuthorizationRequest.Builder(
            serviceConfig,
            Constants.MOJO_CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(Constants.MOJO_REDIRECT_URI)
        )
            .setScope(Constants.MOJO_SCOPE)
            .build()

    fun getAuthIntent(authService: AuthorizationService): Intent =
        authService.getAuthorizationRequestIntent(buildAuthRequest())

    /**
     * Exchanges the authorization code via Retrofit rather than AppAuth's own
     * token request, for the same reason the Dexcom integration does: an error
     * response is surfaced with the server's message rather than swallowed by
     * AppAuth's parser.
     */
    suspend fun exchangeCodeForTokens(
        authResponse: AuthorizationResponse
    ): Result<Unit> {
        return try {
            val code = authResponse.authorizationCode
                ?: return Result.failure(Exception("No authorization code in response"))

            val r = MojoAuthClient.instance.exchangeCode(
                clientId     = Constants.MOJO_CLIENT_ID,
                clientSecret = Constants.MOJO_CLIENT_SECRET,
                code         = code,
                redirectUri  = Constants.MOJO_REDIRECT_URI,
                codeVerifier = authResponse.request.codeVerifier
            )

            persist(r)
            Log.d(TAG, "Token exchange successful; expires in ${r.expiresIn}s")
            Result.success(Unit)

        } catch (e: retrofit2.HttpException) {
            val body = e.response()?.errorBody()?.string()
            Log.e(TAG, "Token exchange failed HTTP ${e.code()} — $body")
            Result.failure(Exception("HTTP ${e.code()}: $body"))
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange failed", e)
            Result.failure(e)
        }
    }

    suspend fun refreshAccessToken(): Result<Unit> {
        return try {
            val stored = getRefreshToken()
                ?: return Result.failure(Exception("No refresh token stored"))

            val r = MojoAuthClient.instance.refreshToken(
                clientId     = Constants.MOJO_CLIENT_ID,
                clientSecret = Constants.MOJO_CLIENT_SECRET,
                refreshToken = stored
            )

            persist(r, fallbackRefresh = stored)
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

    private suspend fun persist(r: MojoTokenResponse, fallbackRefresh: String? = null) {
        context.mojoDataStore.edit { prefs ->
            prefs[MojoPrefKeys.ACCESS_TOKEN] = r.accessToken
            // Some providers omit refresh_token on refresh; keep the old one.
            prefs[MojoPrefKeys.REFRESH_TOKEN] =
                r.refreshToken ?: fallbackRefresh ?: ""
            prefs[MojoPrefKeys.TOKEN_EXPIRY] =
                System.currentTimeMillis() + (r.expiresIn * 1000L)
        }
    }

    suspend fun getValidAccessToken(): String? {
        val expiry = getTokenExpiry() ?: return null
        if (System.currentTimeMillis() < expiry - 300_000) return getAccessToken()
        return if (refreshAccessToken().isSuccess) getAccessToken() else null
    }

    suspend fun getAccessToken(): String? =
        context.mojoDataStore.data.map {
            it[MojoPrefKeys.ACCESS_TOKEN]?.ifBlank { null }
        }.first()

    suspend fun getRefreshToken(): String? =
        context.mojoDataStore.data.map {
            it[MojoPrefKeys.REFRESH_TOKEN]?.ifBlank { null }
        }.first()

    private suspend fun getTokenExpiry(): Long? =
        context.mojoDataStore.data.map { it[MojoPrefKeys.TOKEN_EXPIRY] }.first()

    suspend fun getLastSyncTime(): Long? =
        context.mojoDataStore.data.map { it[MojoPrefKeys.LAST_SYNC_TIME] }.first()

    suspend fun saveLastSyncTime(time: Long) {
        context.mojoDataStore.edit { it[MojoPrefKeys.LAST_SYNC_TIME] = time }
    }

    suspend fun hasStoredCredentials(): Boolean = getRefreshToken() != null

    suspend fun isAuthenticated(): Boolean = getValidAccessToken() != null

    suspend fun clearTokens() {
        context.mojoDataStore.edit { it.clear() }
    }
}