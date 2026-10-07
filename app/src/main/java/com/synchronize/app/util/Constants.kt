package com.synchronize.app.util
import com.synchronize.app.BuildConfig


object Constants {

    // ── Dexcom OAuth ──────────────────────────────────────────


    const val DEXCOM_REDIRECT_URI = "https://danaharper151.github.io/synchronize-oauth/"
    const val DEXCOM_APP_REDIRECT_URI = "com.synchronize.app://oauth/callback"
    const val DEXCOM_SCOPE         = "offline_access"
    val DEXCOM_CLIENT_ID     = BuildConfig.DEXCOM_CLIENT_ID
    val DEXCOM_CLIENT_SECRET = BuildConfig.DEXCOM_CLIENT_SECRET
    val DB_PASSPHRASE        = BuildConfig.DB_PASSPHRASE


    // Sandbox (development) — switch to PROD_BASE_URL when ready for real data
    const val DEXCOM_SANDBOX_BASE_URL = "https://sandbox-api.dexcom.com/"
    const val DEXCOM_PROD_BASE_URL    = "https://api.dexcom.com/"

    // Toggle this to false when switching to production data
    const val USE_SANDBOX = false

    val DEXCOM_BASE_URL get() = if (USE_SANDBOX) DEXCOM_SANDBOX_BASE_URL
    else DEXCOM_PROD_BASE_URL

    // OAuth endpoints
    val DEXCOM_AUTH_URL get() = "${DEXCOM_BASE_URL}v2/oauth2/login?" +
            "client_id=$DEXCOM_CLIENT_ID" +
            "&redirect_uri=$DEXCOM_REDIRECT_URI" +
            "&response_type=code" +
            "&scope=$DEXCOM_SCOPE"

    const val DEXCOM_TOKEN_ENDPOINT = "v2/oauth2/token"
    const val DEXCOM_EGV_ENDPOINT   = "v3/users/self/egvs"

    // ── Poll interval ─────────────────────────────────────────
    const val DEXCOM_POLL_INTERVAL_MS = 5 * 60 * 1000L  // 5 minutes

    // ── Keto-Mojo MyMojoHealth ────────────────────────────────
    val MOJO_CLIENT_ID     = BuildConfig.MOJO_CLIENT_ID
    val MOJO_CLIENT_SECRET = BuildConfig.MOJO_CLIENT_SECRET

    const val MOJO_AUTH_BASE_URL = "https://auth.us.mymojohealth.com/"
    const val MOJO_API_BASE_URL  = "https://api.us.mymojohealth.com/"

    const val MOJO_AUTHORIZE_ENDPOINT = "oauth/authorize"
    const val MOJO_TOKEN_ENDPOINT     = "api/v1/oauth/token"
    const val MOJO_READINGS_ENDPOINT  = "api/v1/readings"

    const val MOJO_REDIRECT_URI =
        "https://danaharper151.github.io/synchronize-oauth/mojo/"

    const val MOJO_SCOPE =
        "readings:retrieve readings:stats:retrieve users:profile:retrieve"


    // ── Subject ───────────────────────────────────────────────
    const val SUBJECT_ID = "subject_001"

    // ── Ketone reading contexts ───────────────────────────────
    const val CONTEXT_FASTING    = "FASTING"
    const val CONTEXT_PRE_LUNCH  = "PRE_LUNCH"
    const val CONTEXT_POST_LUNCH = "POST_LUNCH"
    const val CONTEXT_PRE_DINNER = "PRE_DINNER"
    const val CONTEXT_PRE_SLEEP  = "PRE_SLEEP"

    // ── Clinical thresholds ───────────────────────────────────
    const val GLUCOSE_LOW          = 70    // mg/dL
    const val GLUCOSE_HIGH         = 140   // mg/dL
    const val GLUCOSE_VERY_HIGH    = 180   // mg/dL
    const val KETONE_SUB_KETOSIS   = 0.5   // mmol/L
    const val KETONE_NUTRITIONAL   = 3.0   // mmol/L
    const val KETONE_ELEVATED      = 5.0   // mmol/L
}