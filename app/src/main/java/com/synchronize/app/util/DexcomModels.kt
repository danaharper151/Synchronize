package com.synchronize.app.util

import com.google.gson.annotations.SerializedName

// ── OAuth Token Response ──────────────────────────────────────

data class DexcomTokenResponse(
    @SerializedName("access_token")  val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("token_type")    val tokenType: String,
    @SerializedName("expires_in")    val expiresIn: Int
)

// ── EGV (Estimated Glucose Value) Response ────────────────────

data class DexcomEgvResponse(
    @SerializedName("recordType")  val recordType: String?,
    @SerializedName("recordVersion") val recordVersion: String?,
    @SerializedName("userId")      val userId: String?,
    @SerializedName("records")     val records: List<DexcomEgvRecord>?
)

data class DexcomEgvRecord(
    @SerializedName("recordId")       val recordId: String?,
    @SerializedName("systemTime")     val systemTime: String,   // ISO 8601
    @SerializedName("displayTime")    val displayTime: String?, // ISO 8601
    @SerializedName("transmitterId")  val transmitterId: String?,
    @SerializedName("transmitterTicks") val transmitterTicks: Long?,
    @SerializedName("value")          val value: Int?,          // glucose mg/dL
    @SerializedName("status")         val status: String?,      // "high", "low", or null
    @SerializedName("trend")          val trend: String?,       // trend direction
    @SerializedName("trendRate")      val trendRate: Double?,   // mg/dL per minute
    @SerializedName("unit")           val unit: String?,        // "mg/dL"
    @SerializedName("rateUnit")       val rateUnit: String?,    // "mg/dL/min"
    @SerializedName("displayDevice")  val displayDevice: String?,
    @SerializedName("transmitterGeneration") val transmitterGeneration: String? // "g7"
)

// ── Data Range Response ───────────────────────────────────────

data class DexcomDataRangeResponse(
    @SerializedName("egvs") val egvs: DexcomDateRange?
)

data class DexcomDateRange(
    @SerializedName("start") val start: DexcomTimeStamp?,
    @SerializedName("end")   val end: DexcomTimeStamp?
)

data class DexcomTimeStamp(
    @SerializedName("systemTime")  val systemTime: String?,
    @SerializedName("displayTime") val displayTime: String?
)

// ── Device Response ───────────────────────────────────────────

data class DexcomDeviceResponse(
    @SerializedName("records") val records: List<DexcomDevice>?
)

data class DexcomDevice(
    @SerializedName("transmitterId")         val transmitterId: String?,
    @SerializedName("transmitterGeneration") val transmitterGeneration: String?,
    @SerializedName("displayDevice")         val displayDevice: String?,
    @SerializedName("lastUploadDate")        val lastUploadDate: String?,
    @SerializedName("alertScheduleList")     val alertScheduleList: List<Any>?
)