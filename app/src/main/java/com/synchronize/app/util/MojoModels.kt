package com.synchronize.app.util

import com.google.gson.annotations.SerializedName

// ── OAuth ─────────────────────────────────────────────────────

data class MojoTokenResponse(
    @SerializedName("access_token")  val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String?,
    @SerializedName("token_type")    val tokenType: String?,
    @SerializedName("expires_in")    val expiresIn: Int,
    @SerializedName("scope")         val scope: String?
)

// ── Readings ──────────────────────────────────────────────────

/**
 * The readings endpoint paginates. The wrapper key was not certain from the
 * published specification, so all plausible names are declared and the first
 * non-null is used (see MojoReadingsResponse.items).
 */
data class MojoReadingsResponse(
    @SerializedName("results")     val results: List<MojoReading>?,
    @SerializedName("items")       val itemsAlt: List<MojoReading>?,
    @SerializedName("data")        val data: List<MojoReading>?,
    @SerializedName("readings")    val readings: List<MojoReading>?,
    @SerializedName("next_cursor") val nextCursor: String?,
    @SerializedName("total")       val total: Int?
) {
    val items: List<MojoReading>
        get() = results ?: itemsAlt ?: data ?: readings ?: emptyList()
}

/**
 * reading_value is a high-precision decimal represented as a STRING in the
 * API, deliberately — parsing it as a float would reintroduce the rounding
 * the API avoids. It is kept as a string here and converted once, explicitly.
 *
 * error_code is the field that solves the LO problem: the CSV export encoded
 * the below-range indicator as a numeric 0.00 with no way to distinguish it
 * from a real measurement. Here it arrives separately, so a reading with a
 * non-null error_code is never treated as a value.
 */
data class MojoReading(
    @SerializedName("id")                val id: String?,
    @SerializedName("reading_type")      val readingType: String?,
    @SerializedName("reading_value")     val readingValue: String?,
    @SerializedName("error_code")        val errorCode: String?,
    @SerializedName("units")             val units: String?,
    @SerializedName("reading_timestamp") val readingTimestamp: String?,
    @SerializedName("reading_timezone")  val readingTimezone: String?,
    @SerializedName("device_timestamp")  val deviceTimestamp: String?,
    @SerializedName("source")            val source: String?,
    @SerializedName("meter_type")        val meterType: String?,
    @SerializedName("reading_sample_type") val readingSampleType: String?,
    @SerializedName("reading_unit")        val readingUnit: String?,
    @SerializedName("notes")             val notes: String?

) {
    val isError: Boolean get() = !errorCode.isNullOrBlank()

    /** Null when the reading carries an error code or an unparseable value. */
    fun valueOrNull(): Double? =
        if (isError) null else readingValue?.toDoubleOrNull()
}