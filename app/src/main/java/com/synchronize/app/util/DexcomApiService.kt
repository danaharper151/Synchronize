package com.synchronize.app.util

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// ── Retrofit Interface ────────────────────────────────────────

interface DexcomApiService {

    // Exchange auth code or refresh token for access token
    @FormUrlEncoded
    @POST(Constants.DEXCOM_TOKEN_ENDPOINT)
    suspend fun getToken(
        @Field("client_id")     clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("code")          code: String,
        @Field("grant_type")    grantType: String,
        @Field("redirect_uri")  redirectUri: String,
        @Field("code_verifier") codeVerifier: String?
    ): DexcomTokenResponse

    @FormUrlEncoded
    @POST(Constants.DEXCOM_TOKEN_ENDPOINT)
    suspend fun refreshToken(
        @Field("client_id")     clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("refresh_token") refreshToken: String,
        @Field("grant_type")    grantType: String = "refresh_token",
        @Field("redirect_uri")  redirectUri: String
    ): DexcomTokenResponse

    // Fetch EGV readings in a time range
    @GET(Constants.DEXCOM_EGV_ENDPOINT)
    suspend fun getEgvs(
        @Header("Authorization") authorization: String,
        @Query("startDate")      startDate: String,   // ISO 8601
        @Query("endDate")        endDate: String       // ISO 8601
    ): DexcomEgvResponse

    // Get available data range
    @GET("v3/users/self/dataRange")
    suspend fun getDataRange(
        @Header("Authorization") authorization: String
    ): DexcomDataRangeResponse

    // Get device info
    @GET("v3/users/self/devices")
    suspend fun getDevices(
        @Header("Authorization") authorization: String
    ): DexcomDeviceResponse
}

// ── Retrofit Builder ──────────────────────────────────────────

object DexcomRetrofitClient {

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val instance: DexcomApiService by lazy {
        Retrofit.Builder()
            .baseUrl(Constants.DEXCOM_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DexcomApiService::class.java)
    }
}