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

interface MojoApiService {

    @FormUrlEncoded
    @POST(Constants.MOJO_TOKEN_ENDPOINT)
    suspend fun exchangeCode(
        @Field("client_id")     clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("code")          code: String,
        @Field("grant_type")    grantType: String = "authorization_code",
        @Field("redirect_uri")  redirectUri: String,
        @Field("code_verifier") codeVerifier: String?
    ): MojoTokenResponse

    @FormUrlEncoded
    @POST(Constants.MOJO_TOKEN_ENDPOINT)
    suspend fun refreshToken(
        @Field("client_id")     clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("refresh_token") refreshToken: String,
        @Field("grant_type")    grantType: String = "refresh_token"
    ): MojoTokenResponse
}

interface MojoReadingsService {

    @GET(Constants.MOJO_READINGS_ENDPOINT)
    suspend fun getReadings(
        @Header("Authorization") authorization: String,
        @Query("reading_type")            readingType: String? = null,
        @Query("reading_timestamp_from")  from: String? = null,
        @Query("reading_timestamp_to")    to: String? = null,
        @Query("page_size")               pageSize: Int? = 200,
        @Query("cursor")                  cursor: String? = null
    ): List<MojoReading>
}

private val mojoHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
                redactHeader("Authorization")
            }
        )
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}

/** Token endpoints live on the auth host. */
object MojoAuthClient {
    val instance: MojoApiService by lazy {
        Retrofit.Builder()
            .baseUrl(Constants.MOJO_AUTH_BASE_URL)
            .client(mojoHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MojoApiService::class.java)
    }
}

/** Readings live on the api host — a different base URL from the token. */
object MojoApiClient {
    val instance: MojoReadingsService by lazy {
        Retrofit.Builder()
            .baseUrl(Constants.MOJO_API_BASE_URL)
            .client(mojoHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MojoReadingsService::class.java)
    }

}